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
