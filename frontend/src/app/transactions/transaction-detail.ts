import { CurrencyPipe, DatePipe } from '@angular/common';
import {
  Component,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { categoryGroups, kindOf } from '../api/category-groups';
import { TYPES, TYPE_LABELS } from '../api/labels';
import {
  Account,
  Category,
  CategoryChange,
  Transaction,
  TransactionDetail as Detail,
  TransactionOrigin,
  TransactionType,
  UsedCategories,
} from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { nameOf } from './day-groups';
import { TransactionsChanged } from './transactions-changed';

/** Che effetto ha ogni tipo sui totali: la riga sotto il nome, nel foglio "Conta come". */
export const TYPE_HINTS: Record<TransactionType, string> = {
  EXPENSE: 'Conta nelle spese e nella sua categoria',
  INCOME: 'Conta nelle entrate',
  INTERNAL_TRANSFER: 'Soldi tra due tuoi conti: né spesa né entrata',
  CARD_SETTLEMENT: "L'addebito del saldo della carta: le spese sono già contate una per una",
  INVESTMENT_DEPOSIT: 'Verso un tuo conto di investimento: tenuto a parte',
  INVESTMENT_WITHDRAWAL: 'Da un tuo conto di investimento: tenuto a parte',
  SECURITIES_BUY: 'Investimenti: tenuto a parte',
  SECURITIES_SELL: 'Investimenti: tenuto a parte',
  INVESTMENT_INCOME: 'Rendite degli investimenti: tenute a parte',
  INVESTMENT_TAX: 'Tasse sugli investimenti: tenute a parte',
  IGNORED: "Resta nell'elenco, fuori da tutti i totali",
  TO_REVIEW: 'Fuori dai totali finché non lo classifichi',
};

const ORIGINS: Record<TransactionOrigin, string> = {
  BANK: 'dalla banca, con la sincronizzazione',
  CSV: 'da un file CSV',
  TRADE_REPUBLIC_FILE: 'dal file di Trade Republic',
};

/** La proposta dopo un cambio di categoria: gli altri movimenti dell'esercente che avevano la vecchia. */
interface SimilarProposal {
  from: string; // il nome della vecchia categoria
  to: string;
  categoryId: number | null;
  movements: Transaction[];
}

/** Una correzione fatta, con quello che serve per annullarla. */
interface Done {
  text: string;
  undo: CategoryChange[];
}

/**
 * Il dettaglio di un movimento (docs/37): tutti i suoi dati, la categoria (e perché ha quella), "conta come",
 * l'esercente con i suoi movimenti, la nota. Cambiando la categoria propone di spostare anche gli altri movimenti
 * dello stesso esercente che avevano la vecchia, e ogni correzione si può annullare.
 */
@Component({
  selector: 'app-transaction-detail',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  templateUrl: './transaction-detail.html',
})
export class TransactionDetail implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly changed = inject(TransactionsChanged);

  protected readonly types = TYPES;
  protected readonly typeLabels = TYPE_LABELS;
  protected readonly typeHints = TYPE_HINTS;
  protected readonly origins = ORIGINS;
  protected readonly nameOf = nameOf;
  protected readonly maxNoteLength = 300;

  protected readonly detail = signal<Detail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly accounts = signal<Account[]>([]);
  protected readonly categories = signal<Category[]>([]);

  /** Il foglio aperto: la scelta della categoria o del tipo; null = nessuno. */
  protected readonly sheet = signal<'category' | 'type' | null>(null);
  protected readonly categorySearch = signal('');
  protected readonly used = signal<UsedCategories | null>(null);

  protected readonly proposal = signal<SimilarProposal | null>(null);
  protected readonly done = signal<Done | null>(null);
  protected readonly showRaw = signal(false);
  protected readonly editingNote = signal(false);

  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('sheetDialog');

  protected readonly transaction = computed(() => this.detail()?.transaction ?? null);

  protected readonly accountName = computed(() => {
    const t = this.transaction();
    return t === null ? '' : (this.accounts().find((a) => a.id === t.accountId)?.label ?? '');
  });

  /** Perché ha questa categoria, in una frase. */
  protected readonly categoryWhy = computed(() => {
    const d = this.detail();
    if (d === null) {
      return '';
    }
    switch (d.categoryOrigin) {
      case 'MANUAL':
        return 'Scelta da te: le regole non la cambiano.';
      case 'RULE':
        return d.ruleName ? `Messa dalla regola «${d.ruleName}».` : 'Messa da una regola.';
      default:
        return d.transaction.type === 'EXPENSE' || d.transaction.type === 'INCOME'
          ? 'Nessuna categoria: sceglila tu, o crea una regola.'
          : 'Nessuna categoria.';
    }
  });

  /** I dati originali, rientrati per leggerli; com'erano se non sono JSON. */
  protected readonly rawText = computed(() => {
    const raw = this.detail()?.rawJson ?? '';
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  });

  /** Le categorie del foglio: quelle già usate per l'esercente in cima, poi i gruppi; filtrate dalla ricerca. */
  protected readonly categorySections = computed(() => {
    const t = this.transaction();
    const search = this.categorySearch().trim().toLowerCase();
    const matches = (name: string) => search === '' || name.toLowerCase().includes(search);
    const sections: { title: string; items: { id: number; name: string }[] }[] = [];
    const used = this.used();
    if (used && used.categories.length > 0 && search === '') {
      sections.push({
        title: `Già usate per «${used.word}…»`,
        items: used.categories.map((u) => ({ id: u.categoryId, name: u.category })),
      });
    }
    if (t !== null) {
      for (const group of categoryGroups(this.categories(), kindOf(t.type))) {
        const items = group.categories.filter((c) => matches(c.name));
        if (items.length > 0) {
          sections.push({
            title: group.label,
            items: items.map((c) => ({ id: c.id, name: c.name })),
          });
        }
      }
    }
    return sections;
  });

  /** "Crea una regola" ha senso solo se i movimenti dell'esercente stanno in una categoria sola. */
  protected readonly ruleAdvice = computed<'link' | 'mixed' | null>(() => {
    const d = this.detail();
    if (d === null || (d.categoryOrigin !== 'MANUAL' && d.transaction.manualType === null)) {
      return null;
    }
    return d.merchant !== null && d.merchant.categories > 1 ? 'mixed' : 'link';
  });

  constructor() {
    // Il foglio è un <dialog>: showModal() lo apre sopra la pagina (con lo sfondo scuro, il tasto Esc, il focus
    // dentro), close() lo chiude. Lo apriamo e chiudiamo seguendo il signal `sheet`.
    effect(() => {
      const dialog = this.dialog()?.nativeElement;
      if (!dialog) {
        return;
      }
      if (this.sheet() !== null && !dialog.open) {
        dialog.showModal?.();
      } else if (this.sheet() === null && dialog.open) {
        dialog.close();
      }
    });
  }

  ngOnInit(): void {
    this.api.accounts().subscribe({ next: (accounts) => this.accounts.set(accounts) });
    this.api.categories().subscribe({ next: (categories) => this.categories.set(categories) });
    // paramMap è un Observable: passando da un movimento all'altro (/movimenti/41 → /movimenti/42) la pagina
    // resta la stessa e cambia solo l'id.
    this.route.paramMap.subscribe((params) => {
      this.proposal.set(null);
      this.done.set(null);
      this.showRaw.set(false);
      this.editingNote.set(false);
      this.load(Number(params.get('id')));
    });
  }

  private load(id: number): void {
    this.error.set(null);
    this.api.transaction(id).subscribe({
      next: (detail) => this.detail.set(detail),
      error: (response) =>
        this.error.set(
          response.status === 404
            ? 'Questo movimento non esiste più.'
            : 'Impossibile caricare il movimento: il backend è avviato?',
        ),
    });
  }

  /** Dopo una correzione: ricarica il dettaglio e avvisa l'elenco. */
  private refresh(): void {
    const t = this.transaction();
    if (t !== null) {
      this.load(t.id);
    }
    this.changed.notify();
  }

  openCategories(): void {
    const t = this.transaction();
    if (t === null) {
      return;
    }
    this.categorySearch.set('');
    this.used.set(null);
    this.sheet.set('category');
    this.api.usedCategories(t.id).subscribe({ next: (used) => this.used.set(used) });
  }

  /**
   * La categoria scelta nel foglio. Poi: se lo stesso esercente ha altri movimenti con la vecchia categoria, li
   * propone; altrimenti dice che è fatto. In entrambi i casi si può annullare.
   */
  chooseCategory(categoryId: number | null, name: string): void {
    const t = this.transaction();
    this.sheet.set(null);
    if (t === null || categoryId === t.categoryId) {
      return;
    }
    const undo: CategoryChange[] = [{ id: t.id, categoryId: t.manualCategoryId }];
    const before = { id: t.categoryId, name: t.category ?? 'senza categoria' };
    this.api.correctCategory(t.id, categoryId).subscribe({
      next: () => {
        this.refresh();
        this.done.set({ text: `Categoria cambiata in «${name}».`, undo });
        if (this.detail()?.merchant) {
          this.api.similarTransactions(t.id, before.id).subscribe({
            next: (movements) => {
              if (movements.length > 0) {
                this.proposal.set({ from: before.name, to: name, categoryId, movements });
              }
            },
          });
        }
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'La categoria non è stata salvata.'),
    });
  }

  /** "Sposta anche quelli". */
  moveSimilar(): void {
    const p = this.proposal();
    const previous = this.done()?.undo ?? [];
    if (p === null) {
      return;
    }
    const changes = p.movements.map((m) => ({ id: m.id, categoryId: p.categoryId }));
    this.api.changeCategories(changes).subscribe({
      next: (before) => {
        this.proposal.set(null);
        const n = changes.length + 1;
        this.done.set({
          text: `Spostati ${n} movimenti in «${p.to}».`,
          undo: [...before, ...previous],
        });
        this.refresh();
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'I movimenti non sono stati spostati.'),
    });
  }

  /** "Solo questo". */
  onlyThis(): void {
    this.proposal.set(null);
    const d = this.done();
    if (d !== null) {
      this.done.set({ ...d, text: 'Spostato solo questo movimento.' });
    }
  }

  /** "Annulla": rimette le scelte di prima, a tutti i movimenti toccati. */
  undo(): void {
    const d = this.done();
    if (d === null) {
      return;
    }
    this.api.changeCategories(d.undo).subscribe({
      next: () => {
        this.done.set(null);
        this.proposal.set(null);
        this.refresh();
      },
      error: () => this.error.set('Non è stato possibile annullare.'),
    });
  }

  /** Il tipo scelto nel foglio "Conta come"; null = torna a deciderlo l'app. */
  chooseType(type: TransactionType | null): void {
    const t = this.transaction();
    this.sheet.set(null);
    if (t === null) {
      return;
    }
    this.api.correctType(t.id, type).subscribe({
      next: () => this.refresh(),
      error: () => this.error.set('La correzione non è stata salvata.'),
    });
  }

  acceptSuggestion(): void {
    const s = this.detail()?.suggestion;
    if (s) {
      this.api.acceptCategorySuggestion(s.merchantKey, s.categoryId).subscribe({
        next: () => this.refresh(),
        error: () => this.error.set('Il suggerimento non è stato accettato.'),
      });
    }
  }

  dismissSuggestion(): void {
    const s = this.detail()?.suggestion;
    if (s) {
      this.api.dismissCategorySuggestion(s.merchantKey, s.categoryId).subscribe({
        next: () => this.refresh(),
      });
    }
  }

  saveNote(note: string): void {
    const t = this.transaction();
    if (t === null) {
      return;
    }
    this.api.changeNote(t.id, note.trim() === '' ? null : note).subscribe({
      next: () => {
        this.editingNote.set(false);
        this.refresh();
      },
      error: (response) => this.error.set(response.error?.detail ?? 'La nota non è stata salvata.'),
    });
  }

  /** "Tutti i movimenti di …": l'elenco cerca le parole dell'esercente. */
  searchMerchant(): void {
    const key = this.detail()?.merchant?.key;
    if (key) {
      this.changed.searchFor(key.toLowerCase());
      this.router.navigate(['/movimenti']);
    }
  }

  /** Il foglio si è chiuso da solo (Esc, o un clic fuori): il signal si allinea. */
  onSheetClosed(): void {
    this.sheet.set(null);
  }

  /** Un clic sullo sfondo scuro (fuori dal contenuto del foglio) lo chiude. */
  onSheetClick(event: MouseEvent): void {
    if (event.target === this.dialog()?.nativeElement) {
      this.sheet.set(null);
    }
  }

  /**
   * I dati per "crea una regola da questa correzione": la pagina Regole li legge dall'indirizzo e compila il modulo.
   * Come testo proponiamo la controparte o, se manca, la causale: l'utente può accorciarlo.
   */
  protected ruleParams(t: Transaction): Record<string, string> {
    return {
      pattern: (t.counterparty || t.description || '').replace(/\s+/g, ' ').trim().slice(0, 200),
      resultType: t.type ?? 'EXPENSE',
      amountSign: t.amount < 0 ? 'NEGATIVE' : 'POSITIVE',
      categoryId: t.categoryId !== null ? String(t.categoryId) : '',
    };
  }

  /** Il nome di un tipo; per un'entrata, "Spesa" vuol dire rimborso di una spesa. */
  protected typeLabel(t: Transaction, type: TransactionType): string {
    return type === 'EXPENSE' && t.amount > 0 ? 'Rimborso di una spesa' : this.typeLabels[type];
  }
}
