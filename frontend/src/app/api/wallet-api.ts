import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Account,
  AiCloudProvider,
  AuthStatus,
  RecoveryCode,
  AiConnectionTest,
  AiProvider,
  AiSettingsChange,
  AiStatus,
  AiSuggestionsStatus,
  AccountRole,
  Alert,
  Bank,
  Budget,
  BudgetRequest,
  Category,
  CategoryRequest,
  CategorySuggestion,
  ImportMapping,
  ImportPreview,
  CompletionPreview,
  CompletionResult,
  ImportResult,
  LinkResult,
  ManualAccountRequest,
  CategoryChange,
  TransactionDetail,
  Overview,
  CategoryOverview,
  ExploreGroups,
  ExplorePeriod,
  ExploreSeries,
  GroupBy,
  InvestmentPeriod,
  Investments,
  Metric,
  Rule,
  RulePreview,
  RuleRequest,
  SetupStatus,
  SubscriptionConfirmation,
  SubscriptionOverview,
  SubscriptionSuggestion,
  SyncRound,
  Transaction,
  TransactionFilter,
  TransactionPage,
  TransactionType,
  UsedCategories,
} from './models';

/**
 * Il servizio che parla con il backend: è l'unico punto dell'applicazione che conosce gli indirizzi delle API.
 *
 * - `@Injectable({ providedIn: 'root' })`: Angular ne crea UNA sola istanza per tutta l'applicazione e la
 *   "inietta" in chi la chiede. È lo stesso ruolo di un `@Service` in Spring.
 * - `inject(HttpClient)`: chiede ad Angular il client HTTP. È l'alternativa moderna al parametro del
 *   costruttore (`constructor(private http: HttpClient)`), che funziona ancora.
 * - Ogni metodo restituisce un `Observable`: la richiesta parte solo quando qualcuno fa `subscribe()`,
 *   e la risposta arriva dopo, in modo asincrono.
 *
 * Gli indirizzi iniziano con `/api`: durante lo sviluppo il server di Angular li inoltra al backend
 * (vedi proxy.conf.json), quindi qui non serve scrivere `http://localhost:8080`.
 */
@Injectable({ providedIn: 'root' })
export class WalletApi {
  private readonly http = inject(HttpClient);

  /** GET /api/accounts */
  accounts(): Observable<Account[]> {
    return this.http.get<Account[]>('/api/accounts');
  }

  /** PUT /api/accounts/{id}/role */
  changeRole(accountId: number, role: AccountRole): Observable<Account> {
    return this.http.put<Account>(`/api/accounts/${accountId}/role`, { role });
  }

  /** PUT /api/accounts/{id}/display-name: il nome del conto scelto dall'utente; vuoto lo toglie. */
  renameAccount(accountId: number, displayName: string): Observable<Account> {
    return this.http.put<Account>(`/api/accounts/${accountId}/display-name`, { displayName });
  }

  /** POST /api/accounts: crea un conto manuale. */
  createManualAccount(account: ManualAccountRequest): Observable<Account> {
    return this.http.post<Account>('/api/accounts', account);
  }

  /** DELETE /api/accounts/{id}: elimina un conto manuale con i suoi movimenti. */
  deleteAccount(accountId: number): Observable<void> {
    return this.http.delete<void>(`/api/accounts/${accountId}`);
  }

  /** POST /api/imports/preview: l'anteprima di un file CSV; senza mappatura, il backend ne propone una. */
  previewImport(
    file: File,
    accountId: number,
    mapping: ImportMapping | null,
  ): Observable<ImportPreview> {
    return this.http.post<ImportPreview>('/api/imports/preview', importForm(file, mapping), {
      params: { accountId },
    });
  }

  /** POST /api/imports: importa il file nel conto manuale, con la mappatura confermata. */
  importCsv(file: File, accountId: number, mapping: ImportMapping): Observable<ImportResult> {
    return this.http.post<ImportResult>('/api/imports', importForm(file, mapping), {
      params: { accountId },
    });
  }

  /** POST /api/imports/completion/preview: che cosa cambierebbe completando un conto collegato (docs/31). */
  previewCompletion(file: File, accountId: number): Observable<CompletionPreview> {
    return this.http.post<CompletionPreview>(
      '/api/imports/completion/preview',
      importForm(file, null),
      {
        params: { accountId },
      },
    );
  }

  /** POST /api/imports/completion: completa i movimenti del conto collegato con il file. */
  completeTransactions(file: File, accountId: number): Observable<CompletionResult> {
    return this.http.post<CompletionResult>('/api/imports/completion', importForm(file, null), {
      params: { accountId },
    });
  }

  /** GET /api/transactions?accountId=...&type=...&page=...&size=... (i filtri null non vengono mandati) */
  transactions(filter: TransactionFilter): Observable<TransactionPage> {
    let params = new HttpParams().set('page', filter.page).set('size', filter.size);
    if (filter.accountId !== null) {
      params = params.set('accountId', filter.accountId);
    }
    if (filter.type !== null) {
      params = params.set('type', filter.type);
    }
    if (filter.categoryId != null) {
      params = params.set('categoryId', filter.categoryId);
    }
    if (filter.uncategorized) {
      params = params.set('uncategorized', true);
    }
    if (filter.from !== null) {
      params = params.set('from', filter.from);
    }
    if (filter.to !== null) {
      params = params.set('to', filter.to);
    }
    if (filter.text.trim() !== '') {
      params = params.set('text', filter.text.trim());
    }
    return this.http.get<TransactionPage>('/api/transactions', { params });
  }

  /** PUT /api/transactions/{id}/manual-type; con `null` la correzione viene tolta. */
  correctType(transactionId: number, type: TransactionType | null): Observable<Transaction> {
    return this.http.put<Transaction>(`/api/transactions/${transactionId}/manual-type`, { type });
  }

  /** PUT /api/transactions/{id}/manual-category con l'id della categoria; con `null` la scelta viene tolta. */
  correctCategory(transactionId: number, categoryId: number | null): Observable<Transaction> {
    return this.http.put<Transaction>(`/api/transactions/${transactionId}/manual-category`, {
      categoryId,
    });
  }

  /** GET /api/transactions/{id}: il dettaglio di un movimento. */
  transaction(id: number): Observable<TransactionDetail> {
    return this.http.get<TransactionDetail>(`/api/transactions/${id}`);
  }

  /**
   * GET /api/transactions/{id}/similar?categoryId=7: gli altri movimenti dello stesso esercente con quella categoria
   * (con `null`: quelli senza categoria).
   */
  similarTransactions(id: number, categoryId: number | null): Observable<Transaction[]> {
    const params: Record<string, number> = categoryId === null ? {} : { categoryId };
    return this.http.get<Transaction[]>(`/api/transactions/${id}/similar`, { params });
  }

  /** PUT /api/transactions/manual-categories: la categoria di più movimenti; risponde con le scelte di prima. */
  changeCategories(changes: CategoryChange[]): Observable<CategoryChange[]> {
    return this.http.put<CategoryChange[]>('/api/transactions/manual-categories', changes);
  }

  /** PUT /api/transactions/{id}/note; con `null` o un testo vuoto la nota viene tolta. */
  changeNote(transactionId: number, note: string | null): Observable<Transaction> {
    return this.http.put<Transaction>(`/api/transactions/${transactionId}/note`, { note });
  }

  /** GET /api/categories: l'elenco delle categorie, per gruppo e nell'ordine dei menu. */
  categories(): Observable<Category[]> {
    return this.http.get<Category[]>('/api/categories');
  }

  /** POST /api/categories */
  createCategory(category: CategoryRequest): Observable<Category> {
    return this.http.post<Category>('/api/categories', category);
  }

  /** PUT /api/categories/{id}: rinomina (movimenti, regole e budget seguono da soli). */
  updateCategory(id: number, category: CategoryRequest): Observable<Category> {
    return this.http.put<Category>(`/api/categories/${id}`, category);
  }

  /** DELETE /api/categories/{id} */
  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`/api/categories/${id}`);
  }

  /** GET /api/banks?country=IT */
  banks(country: string): Observable<Bank[]> {
    return this.http.get<Bank[]>('/api/banks', { params: { country } });
  }

  /** POST /api/connections: avvia il collegamento e restituisce l'indirizzo del login sulla banca. */
  startLink(bank: string, country: string): Observable<{ url: string }> {
    return this.http.post<{ url: string }>('/api/connections', { bank, country });
  }

  /** POST /api/connections/complete: completa il collegamento con i parametri ricevuti dalla banca. */
  completeLink(code: string, state: string): Observable<LinkResult> {
    return this.http.post<LinkResult>('/api/connections/complete', { code, state });
  }

  /** POST /api/sync: "Aggiorna ora". */
  syncNow(): Observable<SyncRound> {
    return this.http.post<SyncRound>('/api/sync', {});
  }

  /** GET /api/rules */
  rules(): Observable<Rule[]> {
    return this.http.get<Rule[]>('/api/rules');
  }

  /** POST /api/rules */
  createRule(rule: RuleRequest): Observable<Rule> {
    return this.http.post<Rule>('/api/rules', rule);
  }

  /** PUT /api/rules/{id} */
  updateRule(id: number, rule: RuleRequest): Observable<Rule> {
    return this.http.put<Rule>(`/api/rules/${id}`, rule);
  }

  /** DELETE /api/rules/{id} (il backend risponde 204, senza corpo: per questo Observable<void>) */
  deleteRule(id: number): Observable<void> {
    return this.http.delete<void>(`/api/rules/${id}`);
  }

  /** POST /api/rules/preview: a quali movimenti si applicherebbe la regola, senza salvarla. */
  /** POST /api/rules/preview (?id=7 mentre si modifica la regola 7: non blocca sé stessa) */
  previewRule(rule: RuleRequest, id: number | null = null): Observable<RulePreview> {
    return this.http.post<RulePreview>('/api/rules/preview', rule, {
      params: id === null ? {} : { id },
    });
  }

  /** GET /api/setup */
  /** GET /api/auth/status: lo stato del PIN. Dà anche il cookie XSRF-TOKEN, che Angular rimanda da solo. */
  authStatus(): Observable<AuthStatus> {
    return this.http.get<AuthStatus>('/api/auth/status');
  }

  /** POST /api/auth/no-pin: la scelta "senza PIN", alla prima apertura (solo dal PC). */
  chooseWithoutPin(): Observable<void> {
    return this.http.post<void>('/api/auth/no-pin', {});
  }

  /** POST /api/auth/pin: crea il PIN (solo dal PC) e apre la sessione; risponde con il codice di recupero. */
  createPin(pin: string): Observable<RecoveryCode> {
    return this.http.post<RecoveryCode>('/api/auth/pin', { pin });
  }

  /** POST /api/auth/login: controlla il PIN e apre la sessione (401 sbagliato, 429 in pausa). */
  login(pin: string): Observable<void> {
    return this.http.post<void>('/api/auth/login', { pin });
  }

  /** POST /api/auth/recover: con il codice di recupero sceglie un PIN nuovo; risponde con il codice nuovo. */
  recover(code: string, pin: string): Observable<RecoveryCode> {
    return this.http.post<RecoveryCode>('/api/auth/recover', { code, pin });
  }

  /** POST /api/auth/pin/remove: toglie il PIN (solo dal PC, con il PIN di adesso). */
  removePin(pin: string): Observable<void> {
    return this.http.post<void>('/api/auth/pin/remove', { pin });
  }

  /** POST /api/auth/logout: chiude la sessione. */
  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {});
  }

  setupStatus(): Observable<SetupStatus> {
    return this.http.get<SetupStatus>('/api/setup');
  }

  /** PUT /api/setup/enable-banking: verifica e salva le credenziali di Enable Banking. */
  saveEnableBanking(applicationId: string, privateKeyPem: string): Observable<SetupStatus> {
    return this.http.put<SetupStatus>('/api/setup/enable-banking', {
      applicationId,
      privateKeyPem,
    });
  }

  /**
   * GET /api/overview?month=2026-10&currency=EUR: il Riepilogo di un mese. Senza mese, quello in corso; senza
   * valuta, quella con più spese.
   */
  overview(month: string | null, currency: string | null): Observable<Overview> {
    let params = new HttpParams();
    if (month) {
      params = params.set('month', month);
    }
    if (currency) {
      params = params.set('currency', currency);
    }
    return this.http.get<Overview>('/api/overview', { params });
  }

  /** GET /api/explore/series: le barre di Analisi. */
  exploreSeries(query: {
    metric: Metric;
    period: ExplorePeriod;
    categoryId: number | null;
    currency: string | null;
  }): Observable<ExploreSeries> {
    return this.http.get<ExploreSeries>('/api/explore/series', { params: definedParams(query) });
  }

  /** GET /api/explore/groups: i movimenti di una barra raggruppati. */
  exploreGroups(query: {
    metric: Metric;
    from: string;
    to: string;
    by: GroupBy;
    categoryId: number | null;
    currency: string | null;
  }): Observable<ExploreGroups> {
    return this.http.get<ExploreGroups>('/api/explore/groups', { params: definedParams(query) });
  }

  /** GET /api/categories/{id}/overview?month=2026-10: la pagina di una categoria. */
  categoryOverview(id: number, month: string | null): Observable<CategoryOverview> {
    return this.http.get<CategoryOverview>(`/api/categories/${id}/overview`, {
      params: month ? { month } : {},
    });
  }

  /** GET /api/investments?period=YEAR&currency=EUR (docs/investimenti.md) */
  investments(period: InvestmentPeriod, currency: string | null = null): Observable<Investments> {
    return this.http.get<Investments>('/api/investments', {
      params: currency ? { period, currency } : { period },
    });
  }

  /** GET /api/budgets?month=2026-10 */
  budgets(month: string): Observable<Budget[]> {
    return this.http.get<Budget[]>('/api/budgets', { params: { month } });
  }

  /** POST /api/budgets */
  createBudget(budget: BudgetRequest): Observable<Budget> {
    return this.http.post<Budget>('/api/budgets', budget);
  }

  /** PUT /api/budgets/{id} */
  updateBudget(id: number, budget: BudgetRequest): Observable<Budget> {
    return this.http.put<Budget>(`/api/budgets/${id}`, budget);
  }

  /** DELETE /api/budgets/{id} */
  deleteBudget(id: number): Observable<void> {
    return this.http.delete<void>(`/api/budgets/${id}`);
  }

  /** GET /api/alerts: gli avvisi di adesso, dal più urgente. */
  alerts(): Observable<Alert[]> {
    return this.http.get<Alert[]>('/api/alerts');
  }

  /** GET /api/subscriptions: abbonamenti confermati, totali, esercenti ignorati. */
  subscriptions(): Observable<SubscriptionOverview> {
    return this.http.get<SubscriptionOverview>('/api/subscriptions');
  }

  /** GET /api/ai: le impostazioni dell'IA e lo stato di Ollama. */
  aiStatus(): Observable<AiStatus> {
    return this.http.get<AiStatus>('/api/ai');
  }

  /** PUT /api/ai: accende o spegne l'IA, sceglie il fornitore e i modelli. */
  changeAi(change: AiSettingsChange): Observable<AiStatus> {
    return this.http.put<AiStatus>('/api/ai', change);
  }

  /** PUT /api/ai/keys/{provider}: salva la chiave di un servizio cloud (il backend la cifra). */
  saveAiKey(provider: AiCloudProvider, apiKey: string): Observable<AiStatus> {
    return this.http.put<AiStatus>(`/api/ai/keys/${provider}`, { apiKey });
  }

  /** DELETE /api/ai/keys/{provider}: toglie la chiave di un servizio cloud. */
  deleteAiKey(provider: AiCloudProvider): Observable<AiStatus> {
    return this.http.delete<AiStatus>(`/api/ai/keys/${provider}`);
  }

  /** POST /api/ai/connection-test/{provider}: una domanda di prova. */
  testAiConnection(provider: AiCloudProvider): Observable<AiConnectionTest> {
    return this.http.post<AiConnectionTest>(`/api/ai/connection-test/${provider}`, {});
  }

  /** POST /api/ai/model/download: fa partire lo scaricamento del modello (in background). */
  downloadAiModel(): Observable<AiStatus> {
    return this.http.post<AiStatus>('/api/ai/model/download', {});
  }

  /** POST /api/ai/evaluation: fa partire la misura della precisione di un modello (in background). */
  evaluateAi(provider: AiProvider, model: string, samples: number): Observable<AiStatus> {
    return this.http.post<AiStatus>('/api/ai/evaluation', { provider, model, samples });
  }

  /** GET /api/ai/suggestions: "Chiedi all'IA", quanti esercenti si possono chiedere e a che punto è. */
  aiSuggestionsStatus(): Observable<AiSuggestionsStatus> {
    return this.http.get<AiSuggestionsStatus>('/api/ai/suggestions');
  }

  /** POST /api/ai/suggestions: "Chiedi all'IA", fa partire le domande (in background). */
  askAi(): Observable<AiSuggestionsStatus> {
    return this.http.post<AiSuggestionsStatus>('/api/ai/suggestions', {});
  }

  /** GET /api/category-suggestions: i suggerimenti di categoria dalla tua storia. */
  categorySuggestions(): Observable<CategorySuggestion[]> {
    return this.http.get<CategorySuggestion[]>('/api/category-suggestions');
  }

  /** GET /api/category-suggestions/for-transaction/{id}: le categorie già usate per esercenti simili. */
  usedCategories(transactionId: number): Observable<UsedCategories> {
    return this.http.get<UsedCategories>(
      `/api/category-suggestions/for-transaction/${transactionId}`,
    );
  }

  /** POST /api/category-suggestions/accept: la categoria a tutti i movimenti senza categoria dell'esercente. */
  acceptCategorySuggestion(
    merchantKey: string,
    categoryId: number,
  ): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>('/api/category-suggestions/accept', {
      merchantKey,
      categoryId,
    });
  }

  /** POST /api/category-suggestions/dismissed: "no, non suggerirlo più". */
  dismissCategorySuggestion(merchantKey: string, categoryId: number): Observable<void> {
    return this.http.post<void>('/api/category-suggestions/dismissed', { merchantKey, categoryId });
  }

  /** GET /api/subscriptions/suggestions */
  subscriptionSuggestions(): Observable<SubscriptionSuggestion[]> {
    return this.http.get<SubscriptionSuggestion[]>('/api/subscriptions/suggestions');
  }

  /** POST /api/subscriptions: "sì, è un abbonamento". */
  confirmSubscription(confirmation: SubscriptionConfirmation): Observable<void> {
    return this.http.post<void>('/api/subscriptions', confirmation);
  }

  /** POST /api/subscriptions/dismissed: "no, non suggerirlo più". */
  dismissSubscription(
    merchantKey: string,
    currency: string,
    typicalAmount: number,
  ): Observable<void> {
    return this.http.post<void>('/api/subscriptions/dismissed', {
      merchantKey,
      currency,
      typicalAmount,
    });
  }

  /** DELETE /api/subscriptions/{id}: cancella una decisione. */
  deleteSubscription(id: number): Observable<void> {
    return this.http.delete<void>(`/api/subscriptions/${id}`);
  }
}

/**
 * Il corpo "multipart" di un'importazione, come un modulo HTML con un file allegato: il pezzo `file` è il
 * CSV, il pezzo `mapping` è il JSON della mappatura. Lo mettiamo in un Blob di tipo application/json,
 * così il backend sa che deve leggerlo come JSON (@RequestPart). Il Content-Type della richiesta
 * (multipart/form-data, con il "boundary" che separa i pezzi) lo scrive il browser: non va impostato a mano.
 */
function importForm(file: File, mapping: ImportMapping | null): FormData {
  const form = new FormData();
  form.append('file', file);
  if (mapping) {
    form.append('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }));
  }
  return form;
}

/** I parametri di una richiesta senza quelli null: "nessun filtro" vuol dire non mandarli. */
function definedParams(
  query: Record<string, string | number | null>,
): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== null) {
      params[key] = value;
    }
  }
  return params;
}
