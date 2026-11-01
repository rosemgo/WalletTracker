import { formatCurrency, formatDate } from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { InvestmentPeriod, Investments } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { BarChart } from '../explore/bar-chart';
import { MoneyPipe } from '../privacy/money.pipe';
import { PrivacyStore } from '../privacy/privacy-store';
import { ReadablePipe } from '../text/readable';
import { InvestmentMovements } from './investment-movements';
import { TrendChart } from './trend-chart';

const PERIODS: { value: InvestmentPeriod; label: string }[] = [
  { value: 'YEAR', label: '12 mesi' },
  { value: 'TWO_YEARS', label: '2 anni' },
  { value: 'ALL', label: 'Tutto' },
];

/**
 * La pagina Investimenti (docs/investimenti.md, parte I). Tre cose, ognuna con il suo nome:
 * <ul>
 *   <li>l'<b>investito in titoli</b>: acquisti meno vendite, la linea che cresce mese per mese, e sotto acquistato e
 *       rientrato dalle vendite, separati;</li>
 *   <li>i <b>bonifici verso i conti di investimento</b>: i soldi spostati dai conti di tutti i giorni, barre mese per
 *       mese (non dicono quanti ne sono stati investiti);</li>
 *   <li>le <b>rendite</b>: cedole, dividendi e interessi. Le vendite non ci sono: restituiscono soprattutto capitale.</li>
 * </ul>
 * Sotto, i movimenti che fanno i due grafici. L'investito non è quanto valgono oggi i titoli: l'app non conosce le
 * quotazioni, e la pagina lo dice.
 */
@Component({
  selector: 'app-investments-page',
  imports: [BarChart, InvestmentMovements, MoneyPipe, ReadablePipe, RouterLink, TrendChart],
  templateUrl: './investments-page.html',
})
export class InvestmentsPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly locale = inject(LOCALE_ID);
  private readonly privacy = inject(PrivacyStore);

  protected readonly periods = PERIODS;

  protected readonly period = signal<InvestmentPeriod>('YEAR');
  protected readonly currency = signal<string | null>(null);
  protected readonly data = signal<Investments | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Il mese su cui si scorre nel grafico (null = nessuno): la cifra grande mostra quel mese. */
  protected readonly scrubIndex = signal<number | null>(null);

  private lastRequest = 0;

  /** Niente di niente in tutta la storia: né acquisti, né bonifici, né rendite. La pagina spiega come farli riconoscere. */
  protected readonly empty = computed(() => {
    const d = this.data();
    return (
      d !== null &&
      d.investedTotal === 0 &&
      d.transferredTotal === 0 &&
      d.months.every(
        (m) =>
          m.bought === 0 && m.sold === 0 && m.transferred === 0 && m.income === 0 && m.taxes === 0,
      )
    );
  });

  protected readonly invested = computed(
    () => this.data()?.months.map((m) => m.investedCumulative) ?? [],
  );
  protected readonly transferred = computed(
    () => this.data()?.months.map((m) => m.transferred) ?? [],
  );
  protected readonly income = computed(() => this.data()?.months.map((m) => m.income) ?? []);

  /** La barra in evidenza delle rendite: l'ultimo mese con una rendita (null se non ce n'è). */
  protected readonly lastIncome = computed(() => lastNonZero(this.income()));

  /** La barra in evidenza dei bonifici: l'ultimo mese con un bonifico. */
  protected readonly lastTransfer = computed(() => lastNonZero(this.transferred()));

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

  /** Il nome di ogni barra per il lettore di schermo: "ottobre 2026, 200 €". */
  protected readonly incomeBarNames = computed(() => this.barNames(this.income()));
  protected readonly transferBarNames = computed(() => this.barNames(this.transferred()));

  /** La cifra grande: l'investito in totale, o fino al mese scelto scorrendo il grafico. */
  protected readonly figure = computed(() => {
    const d = this.data();
    if (d === null) {
      return null;
    }
    const i = this.scrubIndex();
    if (i !== null && d.months[i]) {
      return {
        label: `Investito fino a ${this.monthNames()[i]}`,
        value: d.months[i].investedCumulative,
      };
    }
    return { label: 'Investito in titoli', value: d.investedTotal };
  });

  /** "1.200 € quest'anno · 0 € questo mese". */
  protected readonly comparison = computed(() => {
    const d = this.data();
    if (d === null) {
      return '';
    }
    const year = this.euros(d.investedThisYear, d.currency);
    const month = this.euros(d.investedThisMonth, d.currency);
    return `${year} quest'anno · ${month} questo mese`;
  });

  protected readonly lineDescription = computed(() => {
    const d = this.data();
    return d === null
      ? ''
      : `Investito in titoli, acquisti meno vendite, mese per mese: ${this.euros(d.investedTotal, d.currency)} in tutto`;
  });

  protected readonly transferDescription = computed(() => {
    const d = this.data();
    return d === null
      ? ''
      : `Bonifici verso i conti di investimento, mese per mese: ${this.euros(d.transferredInPeriod, d.currency)} nel periodo`;
  });

  protected readonly incomeDescription = computed(() => {
    const d = this.data();
    return d === null
      ? ''
      : `Cedole, dividendi e interessi, mese per mese: ${this.euros(d.incomeInPeriod, d.currency)} nel periodo`;
  });

  ngOnInit(): void {
    this.load();
  }

  /** "ultimi 12 mesi", "ultimi 2 anni", "da sempre": accanto ai titoli delle schede e degli elenchi. */
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

  private barNames(values: number[]): string[] {
    const d = this.data();
    return d === null
      ? []
      : values.map((v, i) => `${this.monthNames()[i]}, ${this.euros(v, d.currency)}`);
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

/** L'ultima posizione con un valore diverso da zero (null se sono tutti zero). */
function lastNonZero(values: number[]): number | null {
  for (let i = values.length - 1; i >= 0; i--) {
    if (values[i] !== 0) {
      return i;
    }
  }
  return null;
}
