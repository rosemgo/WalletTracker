import { CurrencyPipe, DatePipe, PercentPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Summary } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MonthlyChart } from './monthly-chart';

/** I periodi che si possono scegliere, in mesi (il mese in corso compreso). */
const PERIODS = [6, 12, 24];

/**
 * Il riepilogo: entrate, spese e risparmio del periodo, mese per mese.
 *
 * Tutti i conti insieme: i trasferimenti tra conti propri e il saldo della carta di credito non contano,
 * perché la classificazione li ha già riconosciuti (vedi SummaryService nel backend).
 */
@Component({
  selector: 'app-summary-page',
  imports: [CurrencyPipe, DatePipe, PercentPipe, RouterLink, MonthlyChart],
  templateUrl: './summary-page.html',
})
export class SummaryPage implements OnInit {
  private readonly api = inject(WalletApi);

  protected readonly periods = PERIODS;
  protected readonly months = signal(12);
  protected readonly summary = signal<Summary | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** La valuta scelta; null = la prima disponibile (quasi sempre ce n'è una sola, l'euro). */
  protected readonly chosenCurrency = signal<string | null>(null);

  /** Il riepilogo della valuta mostrata. */
  protected readonly current = computed(() => {
    const currencies = this.summary()?.currencies ?? [];
    return currencies.find((c) => c.currency === this.chosenCurrency()) ?? currencies[0] ?? null;
  });

  /** Quanta parte delle entrate è stata risparmiata (0,25 = 25%); null se non ci sono entrate. */
  protected readonly savingsRate = computed(() => {
    const total = this.current()?.total;
    return total && total.income > 0 ? total.savings / total.income : null;
  });

  ngOnInit(): void {
    this.load();
  }

  onPeriod(event: Event): void {
    this.months.set(Number((event.target as HTMLSelectElement).value));
    this.load();
  }

  onCurrency(event: Event): void {
    this.chosenCurrency.set((event.target as HTMLSelectElement).value);
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.monthlySummary(firstMonth(new Date(), this.months())).subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossibile caricare il riepilogo: il backend è avviato?');
        this.loading.set(false);
      },
    });
  }
}

/**
 * Il primo mese di un periodo di `months` mesi che finisce con il mese di `today`, nel formato "2025-11".
 * `new Date(anno, mese, 1)` sistema da sola i mesi fuori scala: il mese -2 del 2026 è ottobre 2025.
 */
export function firstMonth(today: Date, months: number): string {
  const first = new Date(today.getFullYear(), today.getMonth() - (months - 1), 1);
  return `${first.getFullYear()}-${String(first.getMonth() + 1).padStart(2, '0')}`;
}
