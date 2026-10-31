import { DatePipe, formatCurrency, formatDate } from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InvestmentPeriod, Investments } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { BarChart } from '../explore/bar-chart';
import { MoneyPipe } from '../privacy/money.pipe';
import { PrivacyStore } from '../privacy/privacy-store';
import { ReadablePipe } from '../text/readable';
import { avatarHue, initialOf, nameOf } from '../transactions/day-groups';
import { TrendChart } from './trend-chart';

const PERIODS: { value: InvestmentPeriod; label: string }[] = [
  { value: 'YEAR', label: '12 mesi' },
  { value: 'TWO_YEARS', label: '2 anni' },
  { value: 'ALL', label: 'Tutto' },
];

/**
 * La pagina Investimenti (docs/investimenti.md): quanto hai versato negli investimenti, con la linea che cresce mese
 * per mese, e quanto ti hanno reso (cedole, dividendi, interessi), con le barre dei mesi e gli elenchi.
 *
 * "Versato" sono i soldi messi, non quanto valgono oggi: l'app non conosce le quotazioni dei titoli. La pagina lo
 * dice sotto il grafico.
 */
@Component({
  selector: 'app-investments-page',
  imports: [BarChart, DatePipe, MoneyPipe, ReadablePipe, RouterLink, TrendChart],
  templateUrl: './investments-page.html',
})
export class InvestmentsPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly locale = inject(LOCALE_ID);
  private readonly privacy = inject(PrivacyStore);

  protected readonly periods = PERIODS;
  protected readonly nameOf = nameOf;
  protected readonly initialOf = initialOf;
  protected readonly avatarHue = avatarHue;

  protected readonly period = signal<InvestmentPeriod>('YEAR');
  protected readonly currency = signal<string | null>(null);
  protected readonly data = signal<Investments | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Il mese su cui si scorre nel grafico (null = nessuno): la cifra grande mostra quel mese. */
  protected readonly scrubIndex = signal<number | null>(null);

  private lastRequest = 0;

  /** Niente di niente: né versamenti né rendite. La pagina spiega come farli riconoscere. */
  protected readonly empty = computed(() => {
    const d = this.data();
    return (
      d !== null &&
      d.contributedTotal === 0 &&
      d.months.every((m) => m.contributed === 0 && m.income === 0 && m.taxes === 0)
    );
  });

  protected readonly cumulative = computed(
    () => this.data()?.months.map((m) => m.cumulative) ?? [],
  );
  protected readonly income = computed(() => this.data()?.months.map((m) => m.income) ?? []);

  /** La barra in evidenza: l'ultimo mese con una rendita (null se non ce n'è). */
  protected readonly lastIncome = computed(() => {
    const income = this.income();
    for (let i = income.length - 1; i >= 0; i--) {
      if (income[i] !== 0) {
        return i;
      }
    }
    return null;
  });

  /** I mesi abbreviati ("nov") per l'asse della linea. */
  protected readonly monthLabels = computed(
    () => this.data()?.months.map((m) => this.monthName(m.month, 'MMM').replace('.', '')) ?? [],
  );

  /** I mesi per esteso ("ottobre 2026"). */
  protected readonly monthNames = computed(
    () => this.data()?.months.map((m) => this.monthName(m.month, 'MMMM y')) ?? [],
  );

  /** Le etichette delle barre: l'iniziale del mese; con più di 12 mesi, solo una ogni tanto. */
  protected readonly barLabels = computed(() => {
    const months = this.data()?.months ?? [];
    const every = Math.ceil(months.length / 12);
    return months.map((m, i) =>
      (months.length - 1 - i) % every === 0 ? this.monthName(m.month, 'MMMMM').toUpperCase() : '',
    );
  });

  protected readonly barNames = computed(() => {
    const d = this.data();
    return (d?.months ?? []).map(
      (m, i) => `${this.monthNames()[i]}, ${this.euros(m.income, d!.currency)}`,
    );
  });

  /** La cifra grande: il versato in totale, o fino al mese scelto scorrendo il grafico. */
  protected readonly figure = computed(() => {
    const d = this.data();
    if (d === null) {
      return null;
    }
    const i = this.scrubIndex();
    if (i !== null && d.months[i]) {
      return { label: `Versato fino a ${this.monthNames()[i]}`, value: d.months[i].cumulative };
    }
    return { label: 'Versato in totale', value: d.contributedTotal };
  });

  /** "+2.900 € quest'anno · 200 € questo mese". */
  protected readonly comparison = computed(() => {
    const d = this.data();
    if (d === null) {
      return '';
    }
    const year = this.euros(d.contributedThisYear, d.currency);
    const month = this.euros(d.contributedThisMonth, d.currency);
    return `${year} quest'anno · ${month} questo mese`;
  });

  protected readonly lineDescription = computed(() => {
    const d = this.data();
    return d === null
      ? ''
      : `Versato negli investimenti, mese per mese: ${this.euros(d.contributedTotal, d.currency)} in tutto`;
  });

  protected readonly barDescription = computed(() => {
    const d = this.data();
    return d === null
      ? ''
      : `Cedole, dividendi e interessi, mese per mese: ${this.euros(d.incomeInPeriod, d.currency)} nel periodo`;
  });

  ngOnInit(): void {
    this.load();
  }

  /** "ultimi 12 mesi", "ultimi 2 anni", "da sempre": accanto al titolo delle rendite. */
  protected periodLabel(period: InvestmentPeriod): string {
    return period === 'YEAR'
      ? 'ultimi 12 mesi'
      : period === 'TWO_YEARS'
        ? 'ultimi 2 anni'
        : 'da sempre';
  }

  choosePeriod(period: InvestmentPeriod): void {
    this.period.set(period);
    this.load();
  }

  onCurrency(event: Event): void {
    this.currency.set((event.target as HTMLSelectElement).value);
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.scrubIndex.set(null);
    const request = ++this.lastRequest;
    this.api.investments(this.period(), this.currency()).subscribe({
      next: (data) => {
        if (request === this.lastRequest) {
          this.data.set(data);
          this.loading.set(false);
        }
      },
      error: () => {
        if (request === this.lastRequest) {
          this.error.set('Impossibile caricare gli investimenti: il backend è avviato?');
          this.loading.set(false);
        }
      },
    });
  }

  private monthName(month: string, format: string): string {
    return formatDate(`${month}-01`, format, this.locale);
  }

  /** Un importo senza decimali, con le cifre nascoste in modalità discreta (docs/42). */
  private euros(value: number, currency: string): string {
    return this.privacy.hide(
      formatCurrency(value, this.locale, currency === 'EUR' ? '€' : currency, currency, '1.0-0'),
    );
  }
}
