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
