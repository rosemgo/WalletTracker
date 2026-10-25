import { CurrencyPipe } from '@angular/common';
import {
  Component,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TYPES, TYPE_LABELS } from '../api/labels';
import { Account, Transaction, TransactionType } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { SuggestionsPanel } from '../suggestions/suggestions-panel';
import { groupByDay, nameOf } from './day-groups';
import { PERIODS, PERIOD_LABELS, Period, periodRange } from './period';
import { TransactionsChanged } from './transactions-changed';

/** Quanti movimenti per volta: i primi all'apertura, altrettanti a ogni "Mostra altri". */
export const PAGE_SIZE = 50;

/** Il massimo che il backend dà in una richiesta (TransactionService.MAX_PAGE_SIZE). */
const MAX_PAGE_SIZE = 200;

/** Quanto aspettare dopo l'ultimo tasto prima di cercare. */
export const SEARCH_DELAY_MS = 300;

/** La larghezza da cui il dettaglio sta accanto all'elenco (come nel CSS). */
const SIDE_BY_SIDE = '(min-width: 1100px)';

/**
 * Movimenti (docs/37): l'elenco raggruppato per giorno, con il totale del giorno, la ricerca e i filtri a "pillole".
 * Toccando un movimento si apre il suo dettaglio (`/movimenti/42`, la pagina figlia nel `<router-outlet>`): sul
 * telefono al posto dell'elenco, sul PC accanto. L'elenco resta in vita sotto il dettaglio, con i suoi filtri e i
 * movimenti già caricati: tornando indietro è come lo si era lasciato.
 */
@Component({
  selector: 'app-transactions-page',
  imports: [CurrencyPipe, RouterLink, RouterLinkActive, RouterOutlet, SuggestionsPanel],
  templateUrl: './transactions-page.html',
})
export class TransactionsPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly route = inject(ActivatedRoute);
  private readonly changed = inject(TransactionsChanged);

  /** Il pannello dei suggerimenti nel template: viewChild() ci dà il componente, per chiamarne i metodi. */
  private readonly suggestionsPanel = viewChild(SuggestionsPanel);

  protected readonly types = TYPES;
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly periods = PERIODS;
  protected readonly periodLabels = PERIOD_LABELS;
  protected readonly nameOf = nameOf;

  protected readonly accounts = signal<Account[]>([]);
  /** I movimenti caricati finora (la prima pagina, più quelle di "Mostra altri"). */
  protected readonly items = signal<Transaction[]>([]);
  /** Quanti movimenti ci sono in tutto con questi filtri; null prima della prima risposta. */
  protected readonly totalItems = signal<number | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // I filtri scelti dall'utente (null = tutti).
  protected readonly accountId = signal<number | null>(null);
  protected readonly type = signal<TransactionType | null>(null);
  protected readonly uncategorized = signal(false);
  protected readonly period = signal<Period>('ALL');
  protected readonly from = signal<string | null>(null); // "2026-10-01"; null = nessun limite
  protected readonly to = signal<string | null>(null);
  protected readonly text = signal(''); // la ricerca: causale, controparte, categoria, nota o importo
  /** Solo una categoria (dalla pagina di una categoria, "Vedi i movimenti"); null = tutte. */
  protected readonly category = signal<{ id: number; name: string } | null>(null);

  /** È aperto il dettaglio di un movimento (la pagina figlia)? */
  protected readonly detailOpen = signal(false);

  /** Il timer della ricerca: si cerca quando l'utente smette di scrivere (vedi onSearch). */
  private searchTimer: ReturnType<typeof setTimeout> | undefined;
  /** Il numero dell'ultima richiesta: le risposte delle richieste superate si ignorano. */
  private lastRequest = 0;
  /** Dove era l'elenco sul telefono prima di aprire un dettaglio: ci si torna chiudendolo. */
  private listScroll = 0;

  protected readonly accountNames = computed(
    () => new Map(this.accounts().map((a) => [a.id, a.label])),
  );

  /** I movimenti per giorno: si ricalcola quando cambiano i movimenti caricati. */
  protected readonly days = computed(() => groupByDay(this.items(), new Date()));

  protected readonly hasMore = computed(() => this.items().length < (this.totalItems() ?? 0));

  constructor() {
    // Quando il dettaglio cambia qualcosa, l'elenco si ricarica (non alla prima esecuzione: lì carica ngOnInit).
    let first = true;
    effect(() => {
      this.changed.version();
      if (first) {
        first = false;
        return;
      }
      untracked(() => this.reloadLoaded());
    });
    // Una ricerca chiesta dal dettaglio: il testo nel campo, e si cerca.
    effect(() => {
      const request = this.changed.search();
      if (request !== null) {
        untracked(() => {
          this.changed.search.set(null); // usata: non vale più per la prossima apertura della pagina
          this.text.set(request.text.slice(0, 100));
          this.load();
        });
      }
    });
  }

  ngOnInit(): void {
    // Filtri nell'indirizzo, dai link delle altre pagine: /movimenti?type=TO_REVIEW (il Riepilogo), ?text=AMAZON.
    const params = this.route.snapshot.queryParamMap;
    const type = params.get('type');
    if (type !== null && (TYPES as readonly string[]).includes(type)) {
      this.type.set(type as TransactionType);
    }
    this.text.set((params.get('text') ?? '').slice(0, 100));
    const categoryId = Number(params.get('categoryId'));
    if (Number.isInteger(categoryId) && categoryId > 0) {
      this.category.set({ id: categoryId, name: 'Una categoria' });
      // Il nome, per la pillola: dall'elenco delle categorie.
      this.api.categories().subscribe({
        next: (categories) => {
          const found = categories.find((c) => c.id === categoryId);
          if (found && this.category()?.id === categoryId) {
            this.category.set({ id: categoryId, name: found.name });
          }
        },
      });
    }
    this.api.accounts().subscribe({ next: (accounts) => this.accounts.set(accounts) });
    this.load();
  }

  /** Carica da capo la prima pagina con i filtri correnti. */
  load(): void {
    this.fetch(0, PAGE_SIZE, false);
  }

  /** "Mostra altri": la pagina successiva, aggiunta in fondo. */
  loadMore(): void {
    this.fetch(this.items().length / PAGE_SIZE, PAGE_SIZE, true);
  }

  /** Ricarica i movimenti già caricati (dopo una modifica), senza tornare alla sola prima pagina. */
  private reloadLoaded(): void {
    const size = Math.min(MAX_PAGE_SIZE, Math.max(PAGE_SIZE, this.items().length));
    this.fetch(0, size, false);
    this.suggestionsPanel()?.reload();
  }

  private fetch(page: number, size: number, append: boolean): void {
    const from = this.from();
    const to = this.to();
    // Le date "2026-10-01" si confrontano anche come testo: l'ordine alfabetico è quello delle date.
    if (from !== null && to !== null && from > to) {
      this.error.set('La data di inizio è dopo quella di fine.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    // Le risposte possono arrivare in un ordine diverso da quello delle richieste: conta solo l'ultima.
    const request = ++this.lastRequest;
    this.api
      .transactions({
        accountId: this.accountId(),
        type: this.type(),
        uncategorized: this.uncategorized(),
        from,
        to,
        text: this.text(),
        categoryId: this.category()?.id ?? null,
        page,
        size,
      })
      .subscribe({
        next: (result) => {
          if (request !== this.lastRequest) {
            return;
          }
          this.items.set(append ? [...this.items(), ...result.items] : result.items);
          this.totalItems.set(result.totalItems);
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
    this.load();
  }

  onTypeFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.type.set(value === '' ? null : (value as TransactionType));
    this.load();
  }

  /** La pillola "Da verificare": accende o spegne il filtro per tipo TO_REVIEW. */
  toggleToReview(): void {
    this.type.set(this.type() === 'TO_REVIEW' ? null : 'TO_REVIEW');
    this.load();
  }

  /** La pillola della categoria: toccandola si toglie il filtro. */
  clearCategory(): void {
    this.category.set(null);
    this.load();
  }

  toggleUncategorized(): void {
    this.uncategorized.set(!this.uncategorized());
    this.load();
  }

  /**
   * La ricerca: si cerca 300 millisecondi dopo l'ultimo tasto, non a ogni lettera ("debounce"). Così scrivendo
   * "netflix" parte una richiesta sola, non sette. Svuotare il campo (anche con la x del campo) mostra tutto.
   */
  onSearch(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), SEARCH_DELAY_MS);
  }

  /** Un periodo dal menu ("Questo mese"...): le date le calcoliamo noi; "Scegli le date" mostra i due campi. */
  onPeriod(event: Event): void {
    const period = (event.target as HTMLSelectElement).value as Period;
    this.period.set(period);
    if (period !== 'CUSTOM') {
      const range = periodRange(period, new Date());
      this.from.set(range.from);
      this.to.set(range.to);
    }
    this.load();
  }

  /** Una data scelta dal calendario. Una data cancellata (vuota) toglie il limite. */
  onDate(which: 'from' | 'to', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    (which === 'from' ? this.from : this.to).set(value === '' ? null : value);
    this.period.set('CUSTOM');
    this.load();
  }

  /**
   * Si apre un dettaglio. Sul telefono il dettaglio prende il posto dell'elenco: ricordiamo dove era l'elenco e
   * partiamo dall'alto; chiudendo, si torna allo stesso punto.
   */
  onDetailOpen(): void {
    if (!this.detailOpen() && !matchMedia(SIDE_BY_SIDE).matches) {
      this.listScroll = window.scrollY;
      window.scrollTo(0, 0);
    }
    this.detailOpen.set(true);
  }

  onDetailClose(): void {
    this.detailOpen.set(false);
    if (!matchMedia(SIDE_BY_SIDE).matches) {
      const y = this.listScroll;
      setTimeout(() => window.scrollTo(0, y)); // dopo che l'elenco è di nuovo visibile
    }
  }

  /** La riga sotto il nome: categoria (o tipo) e conto, e "in attesa" se non è ancora contabilizzato. */
  protected metaOf(t: Transaction): string {
    let what: string;
    if (t.category) {
      what = t.category;
    } else if (t.type === 'EXPENSE' || t.type === 'INCOME') {
      what = 'senza categoria';
    } else {
      what = t.type ? this.typeLabels[t.type].toLowerCase() : 'da verificare';
    }
    const parts = [what, this.accountNames().get(t.accountId) ?? ''];
    if (t.status === 'PENDING') {
      parts.push('in attesa');
    }
    return parts.filter((p) => p !== '').join(' · ');
  }
}
