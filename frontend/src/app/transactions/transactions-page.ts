import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';

import { TYPES, TYPE_LABELS } from '../api/labels';
import { Account, Transaction, TransactionPage, TransactionType } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Quanti movimenti per pagina. */
const PAGE_SIZE = 50;

/**
 * La pagina dei movimenti: filtri per conto e per tipo, elenco a pagine, correzione manuale del tipo.
 */
@Component({
  selector: 'app-transactions-page',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './transactions-page.html',
})
export class TransactionsPage implements OnInit {
  private readonly api = inject(WalletApi);

  protected readonly types = TYPES;
  protected readonly typeLabels = TYPE_LABELS;

  protected readonly accounts = signal<Account[]>([]);
  protected readonly result = signal<TransactionPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // I filtri scelti dall'utente (null = tutti) e la pagina corrente.
  protected readonly accountId = signal<number | null>(null);
  protected readonly type = signal<TransactionType | null>(null);
  protected readonly page = signal(0);

  /**
   * Un signal "calcolato": il nome di ogni conto, per id. Si ricalcola da solo quando cambia
   * accounts(), e solo allora (il risultato viene memorizzato).
   */
  protected readonly accountNames = computed(
    () => new Map(this.accounts().map((a) => [a.id, a.name ?? a.bank])),
  );

  ngOnInit(): void {
    this.api.accounts().subscribe({ next: (accounts) => this.accounts.set(accounts) });
    this.load();
  }

  /** Carica la pagina corrente con i filtri correnti. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .transactions({
        accountId: this.accountId(),
        type: this.type(),
        page: this.page(),
        size: PAGE_SIZE,
      })
      .subscribe({
        next: (result) => {
          this.result.set(result);
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Impossibile caricare i movimenti: il backend è avviato?');
          this.loading.set(false);
        },
      });
  }

  onAccountFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.accountId.set(value === '' ? null : Number(value));
    this.page.set(0); // con un filtro nuovo si riparte dalla prima pagina
    this.load();
  }

  onTypeFilter(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.type.set(value === '' ? null : (value as TransactionType));
    this.page.set(0);
    this.load();
  }

  goToPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  /** L'utente ha scelto un tipo dal menu del movimento: è una correzione manuale. */
  onCorrect(transaction: Transaction, event: Event): void {
    const type = (event.target as HTMLSelectElement).value as TransactionType;
    this.correct(transaction, type);
  }

  /** Toglie la correzione manuale: il tipo torna a deciderlo il motore. */
  onResetCorrection(transaction: Transaction): void {
    this.correct(transaction, null);
  }

  private correct(transaction: Transaction, type: TransactionType | null): void {
    this.api.correctType(transaction.id, type).subscribe({
      // Ricarichiamo la pagina intera: una correzione può cambiare anche altri movimenti
      // (ad esempio l'altra metà di un trasferimento).
      next: () => this.load(),
      error: () => this.error.set('La correzione non è stata salvata.'),
    });
  }

  /** Controparte e causale, senza ripetere lo stesso testo due volte. */
  protected textOf(transaction: Transaction): string {
    const counterparty = transaction.counterparty ?? '';
    const description = transaction.description ?? '';
    if (counterparty.toLowerCase() === description.toLowerCase()) {
      return counterparty || '(nessuna descrizione)';
    }
    return `${counterparty} ${description}`.trim();
  }
}
