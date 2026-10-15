import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Account,
  AccountRole,
  Bank,
  LinkResult,
  Rule,
  RulePreview,
  RuleRequest,
  SetupStatus,
  SyncRound,
  Transaction,
  TransactionFilter,
  TransactionPage,
  TransactionType,
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

  /** GET /api/transactions?accountId=...&type=...&page=...&size=... (i filtri null non vengono mandati) */
  transactions(filter: TransactionFilter): Observable<TransactionPage> {
    let params = new HttpParams().set('page', filter.page).set('size', filter.size);
    if (filter.accountId !== null) {
      params = params.set('accountId', filter.accountId);
    }
    if (filter.type !== null) {
      params = params.set('type', filter.type);
    }
    return this.http.get<TransactionPage>('/api/transactions', { params });
  }

  /** PUT /api/transactions/{id}/manual-type; con `null` la correzione viene tolta. */
  correctType(transactionId: number, type: TransactionType | null): Observable<Transaction> {
    return this.http.put<Transaction>(`/api/transactions/${transactionId}/manual-type`, { type });
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
  previewRule(rule: RuleRequest): Observable<RulePreview> {
    return this.http.post<RulePreview>('/api/rules/preview', rule);
  }

  /** GET /api/setup */
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
}
