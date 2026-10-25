import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { CategoryGroup, categoryGroups, kindOf } from '../api/category-groups';
import { TYPES, TYPE_LABELS } from '../api/labels';
import {
  Account,
  Category,
  Transaction,
  TransactionPage,
  TransactionType,
  UsedCategories,
} from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { SuggestionsPanel } from '../suggestions/suggestions-panel';
import { PERIODS, PERIOD_LABELS, Period, periodRange } from './period';

/** Quanti movimenti per pagina. */
const PAGE_SIZE = 50;

/** La nota più lunga, come nel backend (TransactionService.MAX_NOTE_LENGTH). */
export const MAX_NOTE_LENGTH = 300;

/** Quanto aspettare dopo l'ultimo tasto prima di cercare. */
export const SEARCH_DELAY_MS = 300;

/**
 * La pagina dei movimenti: filtri per conto e per tipo, elenco a pagine, correzione manuale del tipo e
 * della categoria, "crea una regola" da una correzione, e la nota di un movimento.
 */
@Component({
  selector: 'app-transactions-page',
  imports: [CurrencyPipe, DatePipe, RouterLink, SuggestionsPanel],
  templateUrl: './transactions-page.html',
})
export class TransactionsPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly route = inject(ActivatedRoute);

  /** Il pannello dei suggerimenti nel template: viewChild() ci dà il componente, per chiamarne i metodi. */
  private readonly suggestionsPanel = viewChild(SuggestionsPanel);

  protected readonly types = TYPES;
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly periods = PERIODS;
  protected readonly periodLabels = PERIOD_LABELS;
  protected readonly maxNoteLength = MAX_NOTE_LENGTH;

  protected readonly accounts = signal<Account[]>([]);
  protected readonly result = signal<TransactionPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // I filtri scelti dall'utente (null = tutti) e la pagina corrente.
  protected readonly accountId = signal<number | null>(null);
  protected readonly type = signal<TransactionType | null>(null);
  protected readonly uncategorized = signal(false);
  protected readonly period = signal<Period>('ALL');
  protected readonly from = signal<string | null>(null); // "2026-10-01"; null = nessun limite
  protected readonly to = signal<string | null>(null);
  protected readonly text = signal(''); // la ricerca: causale, controparte, categoria o importo
  protected readonly page = signal(0);

  /** Il timer della ricerca: si cerca quando l'utente smette di scrivere (vedi onSearch). */
  private searchTimer: ReturnType<typeof setTimeout> | undefined;
  /** Il numero dell'ultima richiesta: le risposte delle richieste superate si ignorano (vedi load). */
  private lastRequest = 0;

  /** L'elenco delle categorie, per il menu a discesa. */
  protected readonly categories = signal<Category[]>([]);
  /** Il movimento di cui si sta modificando la categoria; null = nessuno. */
  protected readonly editingCategory = signal<number | null>(null);
  /** Il movimento di cui si sta scrivendo la nota; null = nessuno. */
  protected readonly editingNote = signal<number | null>(null);
  /** Le categorie già usate per esercenti simili a quello del movimento in modifica: in cima al menu. */
  protected readonly usedCategories = signal<UsedCategories | null>(null);

  /**
   * Un signal "calcolato": il nome di ogni conto, per id. Si ricalcola da solo quando cambia
   * accounts(), e solo allora (il risultato viene memorizzato).
   */
  protected readonly accountNames = computed(
    () => new Map(this.accounts().map((a) => [a.id, a.label])),
  );

  ngOnInit(): void {
    // Un filtro nell'indirizzo, dai link delle altre pagine: /movimenti?type=TO_REVIEW (il Riepilogo, "da verificare").
    const type = this.route.snapshot.queryParamMap.get('type');
    if (type !== null && (TYPES as readonly string[]).includes(type)) {
      this.type.set(type as TransactionType);
    }
    this.api.accounts().subscribe({ next: (accounts) => this.accounts.set(accounts) });
    this.loadCategories();
    this.load();
  }

  /** Carica la pagina corrente con i filtri correnti. */
  load(): void {
    const from = this.from();
    const to = this.to();
    // Le date "2026-10-01" si confrontano anche come testo: l'ordine alfabetico è quello delle date.
    if (from !== null && to !== null && from > to) {
      this.error.set('La data di inizio è dopo quella di fine.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    // Scrivendo nella ricerca partono più richieste di fila, e le risposte possono arrivare in un ordine
    // diverso: "netf" dopo "netflix" rimetterebbe in pagina i risultati vecchi. Ogni richiesta ha un numero, e
    // conta solo la risposta all'ultima.
    const request = ++this.lastRequest;
    this.api
      .transactions({
        accountId: this.accountId(),
        type: this.type(),
        uncategorized: this.uncategorized(),
        from,
        to,
        text: this.text(),
        page: this.page(),
        size: PAGE_SIZE,
      })
      .subscribe({
        next: (result) => {
          if (request !== this.lastRequest) {
            return;
          }
          this.result.set(result);
          this.loading.set(false);
        },
        error: (response) => {
          if (request !== this.lastRequest) {
            return;
          }
          this.error.set(
            response.status === 400
              ? (response.error?.detail ?? 'Ricerca non valida.')
              : 'Impossibile caricare i movimenti: il backend è avviato?',
          );
          this.loading.set(false);
        },
      });
  }

  onAccountFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.accountId.set(value === '' ? null : Number(value));
    this.page.set(0); // con un filtro nuovo si riparte dalla prima pagina
    this.load();
  }

  onTypeFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.type.set(value === '' ? null : (value as TransactionType));
    this.page.set(0);
    this.load();
  }

  /**
   * La ricerca: si cerca 300 millisecondi dopo l'ultimo tasto, non a ogni lettera ("debounce"). Così scrivendo
   * "netflix" parte una richiesta sola, non sette. Svuotare il campo (anche con la x del campo) mostra tutto.
   */
  onSearch(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.page.set(0);
      this.load();
    }, SEARCH_DELAY_MS);
  }

  onUncategorizedFilter(event: Event): void {
    this.uncategorized.set((event.target as HTMLInputElement).checked);
    this.page.set(0);
    this.load();
  }

  /** Un periodo dal menu ("Questo mese"...): le date le calcoliamo noi; "Scegli le date" lascia quelle che ci sono. */
  onPeriod(event: Event): void {
    const period = (event.target as HTMLSelectElement).value as Period;
    this.period.set(period);
    if (period !== 'CUSTOM') {
      const range = periodRange(period, new Date());
      this.from.set(range.from);
      this.to.set(range.to);
    }
    this.page.set(0);
    this.load();
  }

  /**
   * Una data scelta dal calendario (campo <input type="date">, che il browser mostra con il suo calendario).
   * Cambiare una data a mano vuol dire "Scegli le date". Una data cancellata (vuota) toglie il limite.
   */
  onDate(which: 'from' | 'to', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    (which === 'from' ? this.from : this.to).set(value === '' ? null : value);
    this.period.set('CUSTOM');
    this.page.set(0);
    this.load();
  }

  goToPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  /** L'utente ha scelto un tipo dal menu del movimento: è una correzione manuale. */
  onCorrect(transaction: Transaction, event: Event): void {
    const type = (event.target as HTMLSelectElement).value as TransactionType;
    this.correct(transaction, type);
  }

  /** Toglie la correzione manuale: il tipo torna a deciderlo il motore. */
  onResetCorrection(transaction: Transaction): void {
    this.correct(transaction, null);
  }

  private correct(transaction: Transaction, type: TransactionType | null): void {
    this.api.correctType(transaction.id, type).subscribe({
      // Ricarichiamo la pagina intera: una correzione può cambiare anche altri movimenti
      // (ad esempio l'altra metà di un trasferimento).
      next: () => this.load(),
      error: () => this.error.set('La correzione non è stata salvata.'),
    });
  }

  /** Il nome di un tipo nel menu di un movimento. Per un'entrata, "Spesa" vuol dire rimborso di una spesa. */
  protected typeLabel(transaction: Transaction, type: TransactionType): string {
    return type === 'EXPENSE' && transaction.amount > 0
      ? 'Rimborso di una spesa'
      : this.typeLabels[type];
  }

  /** Le categorie del menu, con in cima il gruppo che si addice al tipo del movimento. */
  protected groupsFor(transaction: Transaction): CategoryGroup[] {
    return categoryGroups(this.categories(), kindOf(transaction.type));
  }

  /**
   * La categoria scelta nel menu. Il valore di un'opzione HTML è sempre testo ("7"): lo trasformiamo nel
   * numero dell'id. "" (nessuna categoria) toglie la scelta manuale.
   */
  saveCategory(transaction: Transaction, value: string): void {
    this.api.correctCategory(transaction.id, value === '' ? null : Number(value)).subscribe({
      next: () => {
        this.editingCategory.set(null);
        this.load();
        // Una categoria scelta a mano può far nascere un suggerimento per gli altri movimenti dell'esercente.
        this.suggestionsPanel()?.reload();
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'La categoria non è stata salvata.'),
    });
  }

  /**
   * "modifica" la categoria: apre il menu e chiede al backend le categorie già usate per gli esercenti
   * con la stessa prima parola (es. AMAZON), che il menu mette in cima ("Usate per «AMAZON…»"). Se la
   * risposta arriva quando l'utente è già passato a un altro movimento, la ignoriamo: non è più quella giusta.
   */
  openCategory(transaction: Transaction): void {
    this.usedCategories.set(null);
    this.editingCategory.set(transaction.id);
    this.api.usedCategories(transaction.id).subscribe({
      next: (used) => {
        if (this.editingCategory() === transaction.id) {
          this.usedCategories.set(used);
        }
      },
      // Senza le categorie usate il menu funziona lo stesso: non mostriamo errori.
    });
  }

  /**
   * I dati per "crea una regola da questa correzione": la pagina Regole li legge dall'indirizzo
   * (es. /regole?pattern=NETFLIX&resultType=EXPENSE&categoryId=8) e compila il modulo.
   * Come testo da cercare proponiamo la controparte o, se manca, la causale: l'utente può accorciarlo.
   */
  protected ruleParams(transaction: Transaction): Record<string, string> {
    // Spazi ridotti a uno, come li mostra la tabella: molte banche riempiono i campi di spazi
    // ("MAR GROUP SPA   FOGLIANISE    ITA"). Il backend comunque non bada al numero di spazi.
    const pattern = (transaction.counterparty || transaction.description || '')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 200);
    return {
      pattern,
      resultType: transaction.type ?? 'EXPENSE',
      amountSign: transaction.amount < 0 ? 'NEGATIVE' : 'POSITIVE',
      categoryId: transaction.categoryId !== null ? String(transaction.categoryId) : '',
    };
  }

  /** Controparte e causale, senza ripetere lo stesso testo due volte. */
  protected textOf(transaction: Transaction): string {
    const counterparty = transaction.counterparty ?? '';
    const description = transaction.description ?? '';
    if (counterparty.toLowerCase() === description.toLowerCase()) {
      return counterparty || '(nessuna descrizione)';
    }
    return `${counterparty} ${description}`.trim();
  }

  /**
   * Salva la nota scritta nel campo (vuota la toglie). Aggiorniamo solo quel movimento nella pagina, senza
   * ricaricarla: una nota non cambia né i totali né la classificazione degli altri.
   */
  saveNote(transaction: Transaction, note: string): void {
    this.api.changeNote(transaction.id, note.trim() === '' ? null : note).subscribe({
      next: (saved) => {
        this.editingNote.set(null);
        this.result.update((page) =>
          page === null
            ? page
            : {
                ...page,
                items: page.items.map((t) => (t.id === saved.id ? { ...t, note: saved.note } : t)),
              },
        );
      },
      error: (response) => this.error.set(response.error?.detail ?? 'La nota non è stata salvata.'),
    });
  }

  private loadCategories(): void {
    this.api.categories().subscribe({ next: (categories) => this.categories.set(categories) });
  }
}
