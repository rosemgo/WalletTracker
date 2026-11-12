// I gruppi di categorie nei menu a discesa.
//
// Ogni categoria ha un gruppo (Spese, Entrate, Investimenti, Altro). In un menu mettiamo per primo il gruppo
// che si addice al movimento (a una spesa le categorie di spesa) e poi gli altri: il gruppo ORDINA il menu,
// non vieta niente.

import { Category, CategoryKind, TransactionType } from './models';

/** Come si chiama ogni gruppo nei menu (l'elemento HTML <optgroup>). */
export const KIND_LABELS: Record<CategoryKind, string> = {
  EXPENSE: 'Categorie di spesa',
  INCOME: 'Categorie di entrata',
  INVESTMENT: 'Categorie di investimento',
  OTHER: 'Altre categorie',
};

export const KINDS = Object.keys(KIND_LABELS) as CategoryKind[];

/** Il gruppo che si addice a un tipo di movimento: lo stesso calcolo di CategoryKind.of nel backend. */
export function kindOf(type: TransactionType | null): CategoryKind {
  switch (type) {
    case 'EXPENSE':
      return 'EXPENSE';
    case 'INCOME':
      return 'INCOME';
    case 'INVESTMENT_INCOME':
    case 'WITHHOLDING_TAX':
    case 'INVESTMENT_TAX':
    case 'SECURITIES_BUY':
    case 'SECURITIES_SELL':
    case 'INVESTMENT_DEPOSIT':
    case 'INVESTMENT_WITHDRAWAL':
      return 'INVESTMENT';
    default:
      return 'OTHER';
  }
}

/**
 * I tipi per cui la categoria serve (docs/categorie-e-tipi.md): risponde a una domanda che il tipo lascia aperta (una
 * spesa, ma per che cosa? una rendita, ma quale?). Lo stesso elenco di TransactionType.WITH_CATEGORY nel backend.
 * Un movimento ancora da verificare (null o TO_REVIEW) la mostra: prima si sceglie il tipo.
 */
export function needsCategory(type: TransactionType | null): boolean {
  return (
    type === null ||
    type === 'TO_REVIEW' ||
    type === 'EXPENSE' ||
    type === 'INCOME' ||
    type === 'INVESTMENT_INCOME' ||
    type === 'INVESTMENT_TAX'
  );
}

/** "Per un acquisto di titoli non serve una categoria": come dirlo per ogni tipo che non ne ha bisogno. */
export const WITHOUT_CATEGORY: Partial<Record<TransactionType, string>> = {
  SECURITIES_BUY: 'un acquisto di titoli',
  SECURITIES_SELL: 'una vendita di titoli',
  INVESTMENT_DEPOSIT: 'un versamento negli investimenti',
  INVESTMENT_WITHDRAWAL: 'un prelievo dagli investimenti',
  INTERNAL_TRANSFER: 'un trasferimento tra i tuoi conti',
  CARD_SETTLEMENT: 'il saldo della carta di credito',
  IGNORED: 'un movimento ignorato',
  WITHHOLDING_TAX: 'una ritenuta', // docs/investimenti.md, parte T: il tipo dice già tutto
};

/** Un gruppo pronto per un <optgroup>: il titolo e le sue categorie. */
export interface CategoryGroup {
  kind: CategoryKind;
  label: string;
  categories: Category[];
}

/** Le categorie divise per gruppo, con il gruppo `first` in cima. I gruppi vuoti non compaiono. */
export function categoryGroups(categories: Category[], first: CategoryKind): CategoryGroup[] {
  const order = [first, ...KINDS.filter((kind) => kind !== first)];
  return order
    .map((kind) => ({
      kind,
      label: KIND_LABELS[kind],
      categories: categories.filter((c) => c.kind === kind),
    }))
    .filter((group) => group.categories.length > 0);
}
