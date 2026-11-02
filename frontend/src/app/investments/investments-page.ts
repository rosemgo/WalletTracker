import { DatePipe, formatCurrency, formatDate } from '@angular/common';
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
  imports: [
    BarChart,
    DatePipe,
    InvestmentMovements,
    MoneyPipe,
    ReadablePipe,
    RouterLink,
    TrendChart,
  ],
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
          m.bought === 0 &&
          m.sold === 0 &&
          m.transferred === 0 &&
          m.income === 0 &&
          m.withholding === 0 &&
          m.otherTaxes === 0,
      )
    );
  });

  /** La liquidità: la somma dei saldi conosciuti dei conti di investimento. */
  protected readonly cashTotal = computed(() =>
    (this.data()?.cash ?? []).reduce((sum, c) => sum + (c.balance?.amount ?? 0), 0),
  );

  protected readonly invested = computed(
    () => this.data()?.months.map((m) => m.investedCumulative) ?? [],
  );
  protected readonly transferred = computed(
    () => this.data()?.months.map((m) => m.transferred) ?? [],
  );
  protected readonly income = computed(() => this.data()?.months.map((m) => m.income) ?? []);

  /** La barra toccata (null = nessuna: si guarda l'ultimo mese con un valore). */
  protected readonly incomePick = signal<number | null>(null);
  protected readonly transferPick = signal<number | null>(null);

  /** La barra in evidenza delle rendite: quella toccata, o l'ultimo mese con una rendita (null se non ce n'è). */
  protected readonly incomeBar = computed(() => this.incomePick() ?? lastNonZero(this.income()));

  /** La barra in evidenza dei bonifici: quella toccata, o l'ultimo mese con un bonifico. */
  protected readonly transferBar = computed(
    () => this.transferPick() ?? lastNonZero(this.transferred()),
  );

  /** Il netto delle rendite nel periodo: lordo più ritenute (negative). */
  protected readonly netIncome = computed(() => {
    const d = this.data();
    return d === null ? 0 : d.incomeInPeriod + d.withholdingInPeriod;
  });

  /** "ottobre 2026: lordo 2.000 € · ritenute -400 € · netto 1.600 €", per la barra in evidenza. */
  protected readonly incomeBarText = computed(() => {
    const d = this.data();
    const i = this.incomeBar();
    if (d === null || i === null || !d.months[i]) {
      return '';
    }
    const m = d.months[i];
    const parts = [`lordo ${this.euros(m.income, d.currency, 2)}`];
    if (m.withholding !== 0) {
      parts.push(`ritenute ${this.euros(m.withholding, d.currency, 2)}`);
      parts.push(`netto ${this.euros(m.income + m.withholding, d.currency, 2)}`);
    }
    return `${this.monthNames()[i]}: ${parts.join(' · ')}`;
  });

  /** "ottobre 2026: 1.200 €", per la barra in evidenza dei bonifici. */
  protected readonly transferBarText = computed(() => {
    const d = this.data();
    const i = this.transferBar();
    return d === null || i === null || !d.months[i]
      ? ''
      : `${this.monthNames()[i]}: ${this.euros(d.months[i].transferred, d.currency, 2)}`;
  });

  /** La media al mese delle barre, per la linea tratteggiata: un riferimento per leggere le altezze. */
  protected readonly incomeAverage = computed(() => average(this.income()));
  protected readonly transferAverage = computed(() => average(this.transferred()));
  protected readonly incomeAverageLabel = computed(() => this.averageLabel(this.incomeAverage()));
  protected readonly transferAverageLabel = computed(() =>
    this.averageLabel(this.transferAverage()),
  );

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
    this.incomePick.set(null);
    this.transferPick.set(null);
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

  private averageLabel(value: number | null): string {
    const d = this.data();
    return d === null || value === null ? '' : `media ${this.euros(value, d.currency)} al mese`;
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
  private euros(value: number, currency: string, decimals = 0): string {
    const digits = decimals === 0 ? '1.0-0' : `1.${decimals}-${decimals}`;
    return this.privacy.hide(
      formatCurrency(value, this.locale, currency === 'EUR' ? '€' : currency, currency, digits),
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

/** La media dei valori, o null se sono tutti zero (una linea della media a zero non dice niente). */
function average(values: number[]): number | null {
  if (values.length === 0 || values.every((v) => v === 0)) {
    return null;
  }
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}
