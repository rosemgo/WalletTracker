// I nomi in italiano di ruoli e tipi, da mostrare nell'interfaccia.
//
// Record<K, V> è un tipo di TypeScript: un oggetto con una chiave per ogni valore di K. Se un giorno
// aggiungessimo un ruolo o un tipo in models.ts dimenticando di tradurlo qui, la compilazione fallirebbe.

import {
  AccountRole,
  AmountSign,
  InvestmentGroup,
  InvestmentGroupKind,
  MatchField,
  MatchMode,
  TransactionType,
} from './models';

export const ROLE_LABELS: Record<AccountRole, string> = {
  UNASSIGNED: $localize`Da assegnare`,
  MAIN: $localize`Conto principale`,
  SPENDING: $localize`Conto spese`,
  CREDIT_CARD: $localize`Carta di credito`,
  INVESTMENT: $localize`Investimenti`,
  EXCLUDED: $localize`Escluso`,
};

export const TYPE_LABELS: Record<TransactionType, string> = {
  EXPENSE: $localize`Spesa`,
  INCOME: $localize`Entrata`,
  INTERNAL_TRANSFER: $localize`Trasferimento interno`,
  CARD_SETTLEMENT: $localize`Saldo carta di credito`,
  INVESTMENT_DEPOSIT: $localize`Versamento investimenti`,
  INVESTMENT_WITHDRAWAL: $localize`Prelievo investimenti`,
  SECURITIES_BUY: $localize`Acquisto titoli`,
  SECURITIES_SELL: $localize`Vendita titoli`,
  INVESTMENT_INCOME: $localize`Cedole, dividendi e interessi`,
  WITHHOLDING_TAX: $localize`Ritenute sulle rendite`,
  INVESTMENT_TAX: $localize`Altre tasse investimenti`,
  IGNORED: $localize`Ignorato`,
  TO_REVIEW: $localize`Da verificare`,
};

/** Gli elenchi, nell'ordine in cui compaiono nei menu a tendina. */
export const ROLES = Object.keys(ROLE_LABELS) as AccountRole[];
export const TYPES = Object.keys(TYPE_LABELS) as TransactionType[];

/** Dove cerca una regola: il testo, o il codice dell'operazione che alcune banche mandano (docs/codice-della-banca.md). */
export const MATCH_FIELD_LABELS: Record<MatchField, string> = {
  TEXT: $localize`nel testo (causale e controparte)`,
  BANK_CODE: $localize`nel codice dell'operazione della banca`,
};

export const MATCH_MODE_LABELS: Record<MatchMode, string> = {
  CONTAINS: $localize`contiene`,
  ALL_WORDS: $localize`contiene tutte le parole`,
  STARTS_WITH: $localize`inizia con`,
};

export const AMOUNT_SIGN_LABELS: Record<AmountSign, string> = {
  ANY: $localize`entrate e uscite`,
  NEGATIVE: $localize`solo uscite`,
  POSITIVE: $localize`solo entrate`,
};

export const MATCH_FIELDS = Object.keys(MATCH_FIELD_LABELS) as MatchField[];
export const MATCH_MODES = Object.keys(MATCH_MODE_LABELS) as MatchMode[];
export const AMOUNT_SIGNS = Object.keys(AMOUNT_SIGN_LABELS) as AmountSign[];

/** Le voci degli elenchi di Investimenti che non hanno un nome loro: il backend dice quale voce è, qui c'è la frase. */
export const INVESTMENT_GROUP_LABELS: Record<Exclude<InvestmentGroupKind, 'NAMED'>, string> = {
  OTHER_INCOME: $localize`Altre rendite`,
  UNNAMED_SECURITY: $localize`Senza nome del titolo`,
  UNMATCHED_WITHHOLDING: $localize`Ritenute senza la loro rendita nel periodo`,
  UNCATEGORIZED: $localize`Senza categoria`,
};

/** Il nome da mostrare per una voce: il suo, oppure la frase del suo tipo. */
export function investmentGroupName(group: InvestmentGroup): string {
  return group.kind === 'NAMED' || group.name !== null
    ? (group.name ?? '')
    : INVESTMENT_GROUP_LABELS[group.kind];
}
