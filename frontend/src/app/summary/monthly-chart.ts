import { CurrencyPipe, formatDate, formatNumber } from '@angular/common';
import { Component, LOCALE_ID, computed, inject, input, signal } from '@angular/core';

import { MonthSummary } from '../api/models';

// Le misure del disegno, in "unità SVG". Il viewBox le scala sulla larghezza reale della pagina.
const WIDTH = 720;
const HEIGHT = 260;
const MARGIN = { top: 12, right: 8, bottom: 28, left: 56 };
const PLOT_WIDTH = WIDTH - MARGIN.left - MARGIN.right;
const PLOT_HEIGHT = HEIGHT - MARGIN.top - MARGIN.bottom;
const BASELINE = MARGIN.top + PLOT_HEIGHT; // la y dello zero
const MAX_BAR = 24; // una colonna non è mai più larga di così: il resto della fascia resta vuoto
const GAP = 2; // lo spazio tra la colonna delle entrate e quella delle spese
const RADIUS = 4; // l'angolo arrotondato in cima alla colonna

/** Una colonna già calcolata, pronta da disegnare. */
interface Bar {
  path: string;
  series: 'income' | 'expenses';
}

/** Una fascia del grafico: un mese, con le sue colonne e la sua etichetta. */
interface Band {
  index: number;
  x: number; // dove inizia la fascia
  center: number;
  label: string; // es. "ott 26"; vuota se non c'è spazio per tutte
  bars: Bar[];
  month: MonthSummary;
}

/**
 * Il grafico "entrate e spese, mese per mese": per ogni mese due colonne affiancate.
 *
 * È disegnato a mano in SVG, senza librerie: un SVG è HTML fatto di forme (`<rect>`, `<path>`, `<text>`),
 * e Angular lo genera come qualsiasi altro template, con @for e i binding `[attr.x]`. Così si vede
 * come funziona un grafico "dentro", il progetto non ha una dipendenza in più e i test lo leggono come
 * normale HTML. Per grafici molto più complessi useremmo una libreria (es. Chart.js).
 *
 * `input()` è un signal che riceve il valore dal componente padre: `<app-monthly-chart [months]="...">`.
 * Quando il padre cambia i dati, tutti i `computed` qui sotto si ricalcolano da soli.
 */
@Component({
  selector: 'app-monthly-chart',
  imports: [CurrencyPipe],
  templateUrl: './monthly-chart.html',
})
export class MonthlyChart {
  private readonly locale = inject(LOCALE_ID);

  readonly months = input.required<MonthSummary[]>();
  readonly currency = input.required<string>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly plotLeft = MARGIN.left;
  protected readonly plotRight = WIDTH - MARGIN.right;
  protected readonly plotTop = MARGIN.top;
  protected readonly baseline = BASELINE;

  /** Il mese sotto il mouse (o con il focus da tastiera): mostra il riquadro con i valori. */
  protected readonly active = signal<number | null>(null);

  /**
   * Le linee orizzontali con i valori, a numeri "tondi": 0, 500, 1.000, 1.500...
   * Il valore più alto della scala è il primo numero tondo sopra il mese più alto.
   */
  protected readonly ticks = computed(() => {
    const highest = Math.max(0, ...this.months().flatMap((m) => [m.income, m.expenses]));
    const step = niceStep(highest / 4);
    const ticks: number[] = [];
    for (let value = 0; value <= highest + step - 1e-9 && ticks.length < 10; value += step) {
      ticks.push(value);
    }
    return ticks.length > 1 ? ticks : [0, step];
  });

  protected readonly tickLines = computed(() =>
    this.ticks().map((value) => ({
      y: this.y(value),
      label: formatNumber(value, this.locale, '1.0-0'),
    })),
  );

  protected readonly bands = computed<Band[]>(() => {
    const months = this.months();
    const bandWidth = PLOT_WIDTH / Math.max(months.length, 1);
    const barWidth = Math.min(MAX_BAR, (bandWidth * 0.7 - GAP) / 2);
    const labelEvery = months.length > 12 ? 2 : 1; // con 24 mesi, un'etichetta ogni due
    return months.map((month, index) => {
      const x = MARGIN.left + index * bandWidth;
      const center = x + bandWidth / 2;
      const bars: Bar[] = [];
      if (month.income > 0) {
        bars.push({
          series: 'income',
          path: this.column(center - GAP / 2 - barWidth, barWidth, month.income),
        });
      }
      if (month.expenses > 0) {
        bars.push({
          series: 'expenses',
          path: this.column(center + GAP / 2, barWidth, month.expenses),
        });
      }
      const label =
        index % labelEvery === 0 ? formatDate(`${month.month}-01`, 'MMM yy', this.locale) : '';
      return { index, x, center, label, bars, month };
    });
  });

  protected readonly bandWidth = computed(() => PLOT_WIDTH / Math.max(this.months().length, 1));

  /** Il riquadro dei valori: il mese attivo e la sua posizione, in percentuale della larghezza. */
  protected readonly tooltip = computed(() => {
    const index = this.active();
    const band = index === null ? undefined : this.bands()[index];
    if (!band) {
      return null;
    }
    return {
      month: formatDate(`${band.month.month}-01`, 'MMMM yyyy', this.locale),
      values: band.month,
      leftPercent: (band.center / WIDTH) * 100,
      alignRight: band.center > WIDTH * 0.6, // a destra il riquadro si apre verso sinistra, per non uscire
    };
  });

  /** Da un valore in euro alla coordinata y: 0 sta in basso (BASELINE), il valore più alto in cima. */
  private y(value: number): number {
    const top = this.ticks()[this.ticks().length - 1];
    return BASELINE - (value / top) * PLOT_HEIGHT;
  }

  /**
   * Il contorno di una colonna: dritta alla base, arrotondata in cima. È un `<path>`: una "penna" che
   * segue i comandi M (sposta), V (linea verticale), H (orizzontale), Q (curva) e Z (chiudi).
   */
  private column(x: number, width: number, value: number): string {
    const top = this.y(value);
    const r = Math.min(RADIUS, BASELINE - top, width / 2);
    return [
      `M${x},${BASELINE}`,
      `V${top + r}`,
      `Q${x},${top} ${x + r},${top}`,
      `H${x + width - r}`,
      `Q${x + width},${top} ${x + width},${top + r}`,
      `V${BASELINE}`,
      'Z',
    ].join(' ');
  }
}

/** Il passo "tondo" più vicino sopra un valore: 1, 2, 2.5 o 5 per una potenza di 10 (es. 250, 500, 1000). */
export function niceStep(rough: number): number {
  if (rough <= 0) {
    return 100;
  }
  const power = 10 ** Math.floor(Math.log10(rough));
  const multiple = [1, 2, 2.5, 5, 10].find((m) => m * power >= rough) ?? 10;
  return multiple * power;
}
