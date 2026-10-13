// I tipi dei dati che arrivano dalle API REST del backend.
//
// Sono lo specchio dei record Java AccountDto, TransactionDto e TransactionPageDto: stessi nomi, stessi
// campi. TypeScript li usa solo mentre scrivi il codice (controlla che tu non sbagli il nome di un campo);
// nel JavaScript finale spariscono.

/** I ruoli di un conto (enum AccountRole nel backend). */
export type AccountRole =
  'UNASSIGNED' | 'MAIN' | 'SPENDING' | 'CREDIT_CARD' | 'INVESTMENT' | 'EXCLUDED';

/** I tipi di movimento (enum TransactionType nel backend). */
export type TransactionType =
  | 'EXPENSE'
  | 'INCOME'
  | 'INTERNAL_TRANSFER'
  | 'CARD_SETTLEMENT'
  | 'INVESTMENT_DEPOSIT'
  | 'INVESTMENT_WITHDRAWAL'
  | 'SECURITIES_BUY'
  | 'SECURITIES_SELL'
  | 'INVESTMENT_INCOME'
  | 'INVESTMENT_TAX'
  | 'IGNORED'
  | 'TO_REVIEW';

/** Un conto, come lo restituisce GET /api/accounts. */
export interface Account {
  id: number;
  bank: string;
  name: string | null;
  iban: string | null;
  currency: string;
  role: AccountRole;
  consentValidUntil: string; // data e ora in formato ISO, es. "2027-01-05T14:35:51Z"
  lastSyncAt: string | null;
  lastSyncError: string | null;
}

/** Un movimento, come lo restituisce GET /api/transactions. */
export interface Transaction {
  id: number;
  accountId: number;
  status: 'BOOKED' | 'PENDING';
  bookingDate: string; // es. "2026-10-10"
  valueDate: string | null;
  amount: number;
  currency: string;
  counterparty: string | null;
  description: string | null;
  type: TransactionType | null;
  category: string | null;
  manualType: TransactionType | null;
  transferPeerId: number | null;
}

/** Una pagina di movimenti. */
export interface TransactionPage {
  items: Transaction[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** I filtri della ricerca dei movimenti: null = filtro spento. */
export interface TransactionFilter {
  accountId: number | null;
  type: TransactionType | null;
  page: number;
  size: number;
}

/** Una banca supportata da Enable Banking (GET /api/banks). */
export interface Bank {
  name: string;
  country: string;
  maxConsentDays: number | null; // per quanti giorni la banca concede il consenso (null = non indicato)
}

/** Il risultato di un collegamento completato (POST /api/connections/complete). */
export interface LinkResult {
  bank: string;
  accounts: number; // quanti conti sono stati autorizzati
  imported: number; // quanti movimenti nuovi sono stati importati
  errors: string[]; // gli errori di importazione, conto per conto
}

/** Il riepilogo di "Aggiorna ora" (POST /api/sync). */
export interface SyncRound {
  updated: number;
  failed: number;
  notDue: number;
}

/** Come una regola confronta il testo (enum MatchMode nel backend). */
export type MatchMode = 'CONTAINS' | 'STARTS_WITH';

/** A quali importi si applica una regola (enum AmountSign nel backend). */
export type AmountSign = 'ANY' | 'NEGATIVE' | 'POSITIVE';

/** I dati di una regola che l'utente può scrivere (POST/PUT /api/rules, POST /api/rules/preview). */
export interface RuleRequest {
  name: string;
  priority: number;
  enabled: boolean;
  matchMode: MatchMode;
  pattern: string;
  amountSign: AmountSign;
  resultType: TransactionType;
  category: string;
}

/** Una regola salvata (GET /api/rules): i dati scritti dall'utente più quelli calcolati dal backend. */
export interface Rule extends Omit<RuleRequest, 'category'> {
  id: number;
  category: string | null;
  builtin: boolean; // regola di partenza: si può modificare o disattivare, non cancellare
  uses: number; // quanti movimenti ha classificato
}

/** Il risultato della prova di una regola. */
export interface RulePreview {
  matches: number;
  examples: Transaction[];
}
