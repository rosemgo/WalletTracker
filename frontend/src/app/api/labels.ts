// I nomi in italiano di ruoli e tipi, da mostrare nell'interfaccia.
//
// Record<K, V> è un tipo di TypeScript: un oggetto con una chiave per ogni valore di K. Se un giorno
// aggiungessimo un ruolo o un tipo in models.ts dimenticando di tradurlo qui, la compilazione fallirebbe.

import {
  AccountRole,
  AmountSign,
  InvestmentGroup,
  InvestmentGroupKind,
  MatchMode,
  TransactionType,
} from './models';

export const ROLE_LABELS: Record<AccountRole, string> = {
  UNASSIGNED: 'Da assegnare',
  MAIN: 'Conto principale',
  SPENDING: 'Conto spese',
  CREDIT_CARD: 'Carta di credito',
  INVESTMENT: 'Investimenti',
  EXCLUDED: 'Escluso',
};

export const TYPE_LABELS: Record<TransactionType, string> = {
  EXPENSE: 'Spesa',
  INCOME: 'Entrata',
  INTERNAL_TRANSFER: 'Trasferimento interno',
  CARD_SETTLEMENT: 'Saldo carta di credito',
  INVESTMENT_DEPOSIT: 'Versamento investimenti',
  INVESTMENT_WITHDRAWAL: 'Prelievo investimenti',
  SECURITIES_BUY: 'Acquisto titoli',
  SECURITIES_SELL: 'Vendita titoli',
  INVESTMENT_INCOME: 'Cedole, dividendi e interessi',
  WITHHOLDING_TAX: 'Ritenute sulle rendite',
  INVESTMENT_TAX: 'Altre tasse investimenti',
  IGNORED: 'Ignorato',
  TO_REVIEW: 'Da verificare',
};

/** Gli elenchi, nell'ordine in cui compaiono nei menu a tendina. */
export const ROLES = Object.keys(ROLE_LABELS) as AccountRole[];
export const TYPES = Object.keys(TYPE_LABELS) as TransactionType[];

export const MATCH_MODE_LABELS: Record<MatchMode, string> = {
  CONTAINS: 'contiene',
  ALL_WORDS: 'contiene tutte le parole',
  STARTS_WITH: 'inizia con',
};

export const AMOUNT_SIGN_LABELS: Record<AmountSign, string> = {
  ANY: 'entrate e uscite',
  NEGATIVE: 'solo uscite',
  POSITIVE: 'solo entrate',
};

export const MATCH_MODES = Object.keys(MATCH_MODE_LABELS) as MatchMode[];
export const AMOUNT_SIGNS = Object.keys(AMOUNT_SIGN_LABELS) as AmountSign[];

/** Le voci degli elenchi di Investimenti che non hanno un nome loro: il backend dice quale voce è, qui c'è la frase. */
export const INVESTMENT_GROUP_LABELS: Record<Exclude<InvestmentGroupKind, 'NAMED'>, string> = {
  OTHER_INCOME: 'Altre rendite',
  UNNAMED_SECURITY: 'Senza nome del titolo',
  UNMATCHED_WITHHOLDING: 'Ritenute senza la loro rendita nel periodo',
  UNCATEGORIZED: 'Senza categoria',
};

/** Il nome da mostrare per una voce: il suo, oppure la frase del suo tipo. */
export function investmentGroupName(group: InvestmentGroup): string {
  return group.kind === 'NAMED' || group.name !== null
    ? (group.name ?? '')
    : INVESTMENT_GROUP_LABELS[group.kind];
}
