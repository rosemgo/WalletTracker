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

/** Da dove arrivano i movimenti di un conto: dalla banca (Enable Banking) o da file CSV (conto manuale). */
export type AccountSource = 'ENABLE_BANKING' | 'MANUAL';

/** Un conto, come lo restituisce GET /api/accounts. */
export interface Account {
  id: number;
  label: string; // come mostrarlo: il nome scelto, oppure banca e ultime cifre dell'IBAN (es. "ING ··4521")
  displayName: string | null; // il nome scelto dall'utente
  bank: string;
  name: string | null;
  iban: string | null;
  currency: string;
  role: AccountRole;
  source: AccountSource;
  consentValidUntil: string | null; // data e ora ISO, es. "2027-01-05T14:35:51Z"; null nei conti manuali
  lastSyncAt: string | null;
  lastSyncError: string | null;
}

/** Il corpo di POST /api/accounts: un conto manuale, i cui movimenti si caricano da file CSV. */
export interface ManualAccountRequest {
  bankName: string;
  name: string;
  iban: string | null;
  currency: string;
  role: AccountRole;
}

/** Una colonna con l'importo con il segno, oppure due colonne (entrate e uscite). */
export type AmountMode = 'SINGLE' | 'SPLIT';

/** Il separatore dei decimali: virgola (12,50) o punto (12.50). */
export type DecimalSeparator = 'COMMA' | 'DOT';

/**
 * Come leggere un file CSV (record ImportMapping nel backend). Le colonne sono numeri da 0 (la prima);
 * null vuol dire "nessuna colonna".
 */
export interface ImportMapping {
  delimiter: string; // ";" "," "\t" oppure "|"
  dateColumn: number | null;
  dateFormat: string; // es. "dd/MM/yyyy"
  amountMode: AmountMode;
  amountColumn: number | null;
  incomeColumn: number | null;
  expenseColumn: number | null;
  decimalSeparator: DecimalSeparator;
  descriptionColumn: number | null;
  counterpartyColumn: number | null;
  currencyColumn: number | null;
}

/** Una riga letta con la mappatura, nell'anteprima. */
export interface ImportPreviewRow {
  date: string; // "2026-10-03"
  amount: number;
  currency: string;
  description: string | null;
  counterparty: string | null;
}

/** La risposta di POST /api/imports/preview. */
export interface ImportPreview {
  headers: string[];
  sampleRows: string[][]; // le prime righe così come sono nel file
  totalRows: number;
  mapping: ImportMapping; // quella proposta dal backend, o quella mandata
  parsedRows: ImportPreviewRow[];
  errorCount: number;
  errors: string[];
}

/** La risposta di POST /api/imports. */
export interface ImportResult {
  imported: number;
  duplicates: number; // righe già importate in passato, saltate
}

/**
 * L'anteprima di POST /api/imports/completion/preview: che cosa cambierebbe completando i movimenti di un conto
 * collegato con il file di Trade Republic (docs/31).
 */
export interface CompletionPreview {
  from: string; // il primo giorno del file, "2026-07-01"
  to: string;
  bankFrom: string | null; // il primo giorno dei movimenti arrivati dalla banca
  fileRows: number; // le righe che muovono denaro
  skippedRows: number; // le operazioni sui titoli, senza denaro
  toComplete: Completion[];
  toAdd: CompletionNewMovement[]; // le righe più vecchie dello storico della banca, che diventano movimenti
  alreadyComplete: number; // righe già fatte: movimenti con i testi, o aggiunti da un file caricato prima
  rowsWithoutMovement: CompletionFileRow[];
  movementsWithoutRow: CompletionMovement[];
}

/** Un movimento da completare, con i testi che riceve. */
export interface Completion {
  transactionId: number;
  date: string;
  amount: number;
  currency: string;
  counterparty: string | null;
  description: string | null;
}

/** Una riga del file che diventerà un movimento. */
export interface CompletionNewMovement {
  date: string;
  amount: number;
  currency: string;
  counterparty: string | null;
  description: string | null;
}

/** Una riga del file senza un movimento. */
export interface CompletionFileRow {
  line: number;
  date: string;
  amount: number;
  currency: string;
  description: string | null;
}

/** Un movimento del conto che nel file non c'è. */
export interface CompletionMovement {
  id: number;
  date: string;
  amount: number;
  currency: string;
}

/** La risposta di POST /api/imports/completion. */
export interface CompletionResult {
  completed: number;
  added: number;
  alreadyComplete: number;
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
  categoryId: number | null; // l'id della categoria (tiene già conto di quella scelta a mano)
  category: string | null; // il nome della categoria, da mostrare
  manualType: TransactionType | null;
  manualCategoryId: number | null; // la categoria scelta a mano, se c'è (vince su quella delle regole)
  transferPeerId: number | null;
  note: string | null; // la nota scritta dall'utente, se c'è
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
  uncategorized: boolean; // solo i movimenti senza categoria
  from: string | null; // dal giorno, incluso: "2026-10-01"
  to: string | null; // al giorno, incluso
  text: string; // la ricerca: causale, controparte, categoria o importo ("12,99"); vuoto = nessun filtro
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
export type MatchMode = 'CONTAINS' | 'STARTS_WITH' | 'ALL_WORDS';

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
  categoryId: number | null; // l'id di una categoria dell'elenco, oppure null
}

/** Una regola salvata (GET /api/rules): i dati scritti dall'utente più quelli calcolati dal backend. */
export interface Rule extends RuleRequest {
  id: number;
  category: string | null; // il nome della categoria, da mostrare
  builtin: boolean; // regola di partenza: si può modificare o disattivare, non cancellare
  uses: number; // quanti movimenti ha classificato
}

/** Il risultato della prova di una regola. */
export interface RulePreview {
  matches: number;
  examples: Transaction[];
}

/** Lo stato della configurazione di Enable Banking (GET /api/setup). */
export interface SetupStatus {
  configured: boolean;
  source: 'DASHBOARD' | 'ENV' | null; // da dove arrivano le credenziali in uso
  applicationId: string | null;
  redirectUrl: string; // da registrare nell'applicazione Enable Banking
}

/** I numeri di un mese (record MonthSummary nel backend). Le spese sono positive: 1200 = spesi 1200. */
export interface MonthSummary {
  month: string; // es. "2026-10"
  income: number;
  expenses: number;
  savings: number; // entrate meno spese
  investmentNet: number; // rendite meno tasse degli investimenti
  toReview: number; // movimenti ancora da verificare in quel mese
}

/** I totali del periodo: le stesse voci di MonthSummary, senza il mese. */
export type SummaryTotals = Omit<MonthSummary, 'month'>;

/** Le spese di una categoria nel periodo (positive; i rimborsi le riducono). */
export interface CategoryExpense {
  categoryId: number | null;
  category: string | null; // il nome; null = spese senza categoria
  amount: number;
  movements: number;
}

/** Il riepilogo di una valuta: un elemento per mese, anche i mesi vuoti. */
export interface CurrencySummary {
  currency: string;
  months: MonthSummary[];
  total: SummaryTotals;
  expensesByCategory: CategoryExpense[]; // dalla spesa più alta
}

/** La risposta di GET /api/summary/monthly. */
export interface Summary {
  from: string;
  to: string;
  currencies: CurrencySummary[]; // vuoto se nel periodo non ci sono movimenti
}

/** Come sta andando un budget (enum BudgetStatus nel backend). */
export type BudgetStatus = 'OK' | 'WARNING' | 'OVER';

/** I dati di un budget che l'utente può scrivere (POST/PUT /api/budgets). */
export interface BudgetRequest {
  categoryId: number; // l'id di una categoria dell'elenco
  currency: string; // es. "EUR"
  monthlyAmount: number;
}

/** Un budget con l'andamento del mese scelto (GET /api/budgets?month=2026-10). */
export interface Budget extends BudgetRequest {
  id: number;
  category: string; // il nome della categoria, da mostrare
  spent: number; // le spese della categoria nel mese (i rimborsi le riducono)
  remaining: number; // negativo = superato
  expectedSoFar: number; // quanto "dovresti" aver speso finora spendendo in modo regolare
  averageLast3Months: number; // la spesa media nei 3 mesi prima
  status: BudgetStatus;
}

/** I tipi di avviso (enum AlertKind nel backend). */
export type AlertKind = 'CONSENT_EXPIRED' | 'CONSENT_EXPIRING' | 'SYNC_FAILED' | 'BUDGET_OVER';

/**
 * Un avviso (GET /api/alerts). Il backend manda i dati, non la frase: il testo lo scrive la dashboard
 * (vedi AlertsPanel). I campi che non servono a un tipo di avviso sono null.
 */
export interface Alert {
  kind: AlertKind;
  severity: 'CRITICAL' | 'WARNING';
  bank: string | null;
  country: string | null;
  account: string | null;
  date: string | null; // la scadenza del consenso, es. "2027-01-05T14:35:51Z"
  daysLeft: number | null; // giorni alla scadenza; negativo = scaduto
  items: string[]; // le categorie dei budget superati
  message: string | null; // il motivo dell'errore di sincronizzazione
}

/** Ogni quanto si ripete un abbonamento (enum Frequency nel backend). */
export type Frequency = 'MONTHLY' | 'YEARLY';

/** Un abbonamento suggerito (GET /api/subscriptions/suggestions). */
export interface SubscriptionSuggestion {
  merchantKey: string; // la "chiave" dell'esercente: va rimandata per confermare o ignorare
  currency: string;
  name: string; // il nome proposto: il testo dell'ultimo pagamento
  frequency: Frequency;
  typicalAmount: number;
  occurrences: number;
  lastDate: string;
  nextDate: string;
}

/** Un abbonamento confermato, con i dati aggiornati dai movimenti. */
export interface Subscription {
  id: number;
  merchantKey: string;
  currency: string;
  name: string;
  frequency: Frequency;
  expectedAmount: number; // l'importo al momento della conferma
  lastAmount: number | null;
  lastDate: string | null;
  nextDate: string | null;
  monthlyCost: number;
  priceChanged: boolean;
  late: boolean; // il pagamento previsto non è arrivato: forse disdetto?
}

/** La risposta di GET /api/subscriptions. */
export interface SubscriptionOverview {
  subscriptions: Subscription[];
  totals: { currency: string; monthly: number; yearly: number }[];
  // amount: l'importo della serie rifiutata; null per i rifiuti di prima della V21 (valgono per tutti gli importi)
  dismissed: { id: number; merchantKey: string; currency: string; amount: number | null }[];
}

/** Il corpo di POST /api/subscriptions. */
export interface SubscriptionConfirmation {
  merchantKey: string;
  currency: string;
  name: string;
  frequency: Frequency;
  expectedAmount: number;
}

/**
 * Il gruppo di una categoria (enum CategoryKind nel backend): a quali movimenti si addice.
 * Serve a mettere in cima ai menu le categorie giuste, senza vietare le altre.
 */
export type CategoryKind = 'EXPENSE' | 'INCOME' | 'INVESTMENT' | 'OTHER';

/** Una categoria dell'elenco (GET /api/categories). */
export interface Category {
  id: number;
  name: string;
  kind: CategoryKind;
  builtin: boolean; // categoria di partenza
  uses: number; // quanti movimenti la usano
}

/** Il corpo di POST/PUT /api/categories. */
export interface CategoryRequest {
  name: string;
  kind: CategoryKind;
}

/**
 * Un suggerimento di categoria "dalla tua storia" (GET /api/category-suggestions): per questo esercente
 * hai scelto a mano questa categoria, e ci sono altri suoi movimenti ancora senza.
 */
export interface CategorySuggestion {
  merchantKey: string; // l'esercente, come lo riconosce il programma (es. "PIZZERIA DA GINO")
  merchantName: string; // un nome leggibile, dal movimento più recente
  categoryId: number;
  category: string;
  transactions: number; // quanti movimenti senza categoria la riceverebbero
  examples: number; // su quante tue scelte si basa (0 per un suggerimento dell'IA)
  model: string | null; // il modello che l'ha suggerito, o null se viene dalla tua storia
}

/** "Chiedi all'IA": il giro di domande in corso o l'ultimo fatto. */
export interface AiAnswerJob {
  model: string;
  running: boolean;
  done: number; // esercenti chiesti
  total: number;
  found: number; // risposte con una categoria (le altre sono "non so")
  error: string | null;
}

/** La risposta di GET /api/ai/suggestions. */
export interface AiSuggestionsStatus {
  enabled: boolean; // l'IA è accesa?
  model: string;
  toAsk: number; // quanti esercenti si possono chiedere adesso
  job: AiAnswerJob | null;
}

/** Una categoria già usata per esercenti simili a quello di un movimento, e quante volte (per il suo menu). */
export interface UsedCategory {
  categoryId: number;
  category: string;
  times: number;
}

/**
 * La risposta di GET /api/category-suggestions/for-transaction/{id}: le categorie già usate (a mano o dalle
 * regole) per gli esercenti che iniziano con la stessa parola del movimento (es. "AMAZON").
 */
export interface UsedCategories {
  word: string | null; // null se il movimento non ha testo
  categories: UsedCategory[];
}

/**
 * Chi suggerisce le categorie: nessuno (spento), un modello sul proprio computer (Ollama), o un servizio cloud
 * (Google Gemini: passo 7c).
 */
export type AiProvider = 'NONE' | 'OLLAMA' | 'GEMINI';

/** I servizi cloud. */
export type AiCloudProvider = 'GEMINI';

/** Lo stato di un servizio cloud. La chiave non c'è mai: solo se è salvata e le sue ultime 4 lettere. */
export interface AiCloudStatus {
  model: string;
  keySet: boolean;
  keyHint: string | null; // es. "…a1b2"
  consent: boolean; // l'utente ha accettato l'invio dei dati a questo servizio
}

/** Il risultato di "Prova la connessione". */
export interface AiConnectionTest {
  ok: boolean;
  answer: string | null; // la categoria scelta per ESSELUNGA, o "non so"
  seconds: number;
  error: string | null;
  model: string | null; // il modello che ha risposto davvero (dietro un alias come "gemini-flash-latest")
  thinkingTokens: number | null; // i token di "ragionamento" usati prima di rispondere (solo Gemini)
}

/** Il corpo di PUT /api/ai. */
export interface AiSettingsChange {
  provider: AiProvider;
  ollamaModel: string;
  geminiModel: string;
  consent: boolean; // il consenso all'invio dei dati, se si sceglie un servizio cloud
}

/** Lo scaricamento di un modello in Ollama. */
export interface ModelDownload {
  model: string;
  running: boolean;
  status: string; // l'ultimo messaggio di Ollama, es. "downloading", "success"
  completed: number | null; // byte scaricati della parte in corso
  total: number | null;
  error: string | null;
}

/** Un errore della misura: l'esercente, la tua scelta e la risposta del modello. */
export interface EvaluationMistake {
  merchant: string;
  expected: string;
  answer: string;
}

/** La misura della precisione di un modello sulle tue scelte (in corso o l'ultima fatta). */
export interface ModelEvaluation {
  model: string;
  running: boolean;
  done: number; // domande fatte
  total: number;
  right: number; // risposte uguali alla tua scelta
  wrong: number;
  unsure: number; // "non so"
  seconds: number; // tempo medio per domanda
  mistakes: EvaluationMistake[];
  error: string | null;
}

/** La risposta di GET /api/ai: la scelta dell'utente e lo stato di Ollama. */
export interface AiStatus {
  provider: AiProvider;
  ollamaModel: string;
  ollamaReachable: boolean;
  installedModels: string[];
  modelInstalled: boolean;
  download: ModelDownload | null;
  evaluation: ModelEvaluation | null;
  gemini: AiCloudStatus;
}
