import { CurrencyPipe, DatePipe, PercentPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Summary } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MonthlyChart } from './monthly-chart';

/** I periodi che si possono scegliere, in mesi (il mese in corso compreso). */
const PERIODS = [6, 12, 24];

/** Quante categorie mostrare una per una: le altre finiscono insieme in "Altre categorie". */
const TOP_CATEGORIES = 8;

/** Una riga delle spese per categoria, pronta da mostrare. */
interface CategoryRow {
  label: string;
  amount: number;
  share: number; // la parte delle spese totali, da 0 a 1
  barPercent: number; // la lunghezza della barra, rispetto alla categoria più alta
  muted: boolean; // "Senza categoria" e "Altre categorie" sono mostrate in grigio
}

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

  /**
   * Le spese per categoria, dalla più alta. Oltre le prime TOP_CATEGORIES, le altre vengono sommate in una
   * riga sola: un elenco di 30 categorie non si legge. Una categoria con più rimborsi che spese ha un
   * totale negativo: la barra non si disegna, ma l'importo resta scritto.
   */
  protected readonly categoryRows = computed<CategoryRow[]>(() => {
    const current = this.current();
    if (!current) {
      return [];
    }
    const all = current.expensesByCategory;
    const top = all.slice(0, TOP_CATEGORIES);
    const rest = all.slice(TOP_CATEGORIES);
    const rows = top.map((c) => ({
      label: c.category ?? 'Senza categoria',
      amount: c.amount,
      muted: c.category === null,
    }));
    if (rest.length > 0) {
      rows.push({
        label: `Altre categorie (${rest.length})`,
        amount: rest.reduce((sum, c) => sum + c.amount, 0),
        muted: true,
      });
    }
    const highest = Math.max(0, ...rows.map((r) => r.amount));
    const total = current.total.expenses;
    return rows.map((r) => ({
      ...r,
      share: total > 0 ? r.amount / total : 0,
      barPercent: highest > 0 ? (Math.max(r.amount, 0) / highest) * 100 : 0,
    }));
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
