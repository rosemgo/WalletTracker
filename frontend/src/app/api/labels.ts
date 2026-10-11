// I nomi in italiano di ruoli e tipi, da mostrare nell'interfaccia.
//
// Record<K, V> è un tipo di TypeScript: un oggetto con una chiave per ogni valore di K. Se un giorno
// aggiungessimo un ruolo o un tipo in models.ts dimenticando di tradurlo qui, la compilazione fallirebbe.

import { AccountRole, TransactionType } from './models';

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
  INVESTMENT_INCOME: 'Cedole e dividendi',
  INVESTMENT_TAX: 'Tasse investimenti',
  IGNORED: 'Ignorato',
  TO_REVIEW: 'Da verificare',
};

/** Gli elenchi, nell'ordine in cui compaiono nei menu a tendina. */
export const ROLES = Object.keys(ROLE_LABELS) as AccountRole[];
export const TYPES = Object.keys(TYPE_LABELS) as TransactionType[];
