import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TYPES, TYPE_LABELS } from '../api/labels';
import { Account, Transaction, TransactionPage, TransactionType } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Quanti movimenti per pagina. */
const PAGE_SIZE = 50;

/**
 * La pagina dei movimenti: filtri per conto e per tipo, elenco a pagine, correzione manuale del tipo e
 * della categoria, e "crea una regola" da una correzione.
 */
@Component({
  selector: 'app-transactions-page',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  templateUrl: './transactions-page.html',
})
export class TransactionsPage implements OnInit {
  private readonly api = inject(WalletApi);

  protected readonly types = TYPES;
  protected readonly typeLabels = TYPE_LABELS;

  protected readonly accounts = signal<Account[]>([]);
  protected readonly result = signal<TransactionPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // I filtri scelti dall'utente (null = tutti) e la pagina corrente.
  protected readonly accountId = signal<number | null>(null);
  protected readonly type = signal<TransactionType | null>(null);
  protected readonly page = signal(0);

  /** Le categorie già usate, suggerite mentre si scrive (elemento HTML <datalist>). */
  protected readonly categories = signal<string[]>([]);
  /** Il movimento di cui si sta modificando la categoria; null = nessuno. */
  protected readonly editingCategory = signal<number | null>(null);

  /**
   * Un signal "calcolato": il nome di ogni conto, per id. Si ricalcola da solo quando cambia
   * accounts(), e solo allora (il risultato viene memorizzato).
   */
  protected readonly accountNames = computed(
    () => new Map(this.accounts().map((a) => [a.id, a.name ?? a.bank])),
  );

  ngOnInit(): void {
    this.api.accounts().subscribe({ next: (accounts) => this.accounts.set(accounts) });
    this.loadCategories();
    this.load();
  }

  /** Carica la pagina corrente con i filtri correnti. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .transactions({
        accountId: this.accountId(),
        type: this.type(),
        page: this.page(),
        size: PAGE_SIZE,
      })
      .subscribe({
        next: (result) => {
          this.result.set(result);
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Impossibile caricare i movimenti: il backend è avviato?');
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

  /** "Salva" della categoria: il testo scritto, oppure null se il campo è vuoto (toglie la scelta). */
  saveCategory(transaction: Transaction, value: string): void {
    const category = value.trim() === '' ? null : value.trim();
    this.api.correctCategory(transaction.id, category).subscribe({
      next: () => {
        this.editingCategory.set(null);
        this.loadCategories(); // una categoria nuova entra subito nei suggerimenti
        this.load();
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'La categoria non è stata salvata.'),
    });
  }

  /**
   * I dati per "crea una regola da questa correzione": la pagina Regole li legge dall'indirizzo
   * (es. /regole?pattern=NETFLIX&resultType=EXPENSE&category=Abbonamenti) e compila il modulo.
   * Come testo da cercare proponiamo la controparte o, se manca, la causale: l'utente può accorciarlo.
   */
  protected ruleParams(transaction: Transaction): Record<string, string> {
    const pattern = (transaction.counterparty || transaction.description || '')
      .trim()
      .slice(0, 200);
    return {
      pattern,
      resultType: transaction.type ?? 'EXPENSE',
      amountSign: transaction.amount < 0 ? 'NEGATIVE' : 'POSITIVE',
      category: transaction.category ?? '',
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

  private loadCategories(): void {
    this.api.categories().subscribe({ next: (categories) => this.categories.set(categories) });
  }
}
