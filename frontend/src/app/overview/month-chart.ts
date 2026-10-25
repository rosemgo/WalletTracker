import { Component, computed, input } from '@angular/core';

/** La larghezza e l'altezza del disegno: l'SVG poi si allarga quanto la scheda (viewBox). */
const WIDTH = 340;
const PLOT_HEIGHT = 112; // l'altezza dell'area delle linee; sotto, le date
const TOP = 6;

/** I passi "tondi" per la linea di riferimento: 50 €, 100 €, 200 €... */
const STEPS = [
  10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000, 25000, 50000,
];

/**
 * Il grafico del mese (docs/36, parte E): la spesa accumulata dal giorno 1 a oggi (accento), contro la stessa curva di
 * un mese tipico (grigia). A colpo d'occhio: la linea colorata sopra quella grigia vuol dire "sto spendendo più del
 * solito", e la pendenza dice se sto accelerando.
 *
 * Disegnato a mano in SVG, come il grafico mensile (docs/18): due linee, un'area, un punto, una scala sola.
 */
@Component({
  selector: 'app-month-chart',
  template: `
    <svg
      class="month-chart"
      [attr.viewBox]="'0 0 ' + width + ' 130'"
      role="img"
      [attr.aria-label]="description()"
    >
      <line class="base" x1="0" [attr.y1]="plotHeight" [attr.x2]="width" [attr.y2]="plotHeight" />
      @for (line of grid(); track line.value) {
        <line class="grid" x1="0" [attr.y1]="line.y" [attr.x2]="width" [attr.y2]="line.y" />
        <text class="axis" x="0" [attr.y]="line.y - 4">{{ line.label }}</text>
      }
      @if (usualPath()) {
        <path class="usual" [attr.d]="usualPath()" />
      }
      @if (monthPath()) {
        <path class="area" [attr.d]="areaPath()" />
        <path class="month" [attr.d]="monthPath()" />
        <circle class="today" [attr.cx]="todayPoint().x" [attr.cy]="todayPoint().y" r="5" />
      }
      <text class="axis" x="0" y="127">1 {{ monthShort() }}</text>
      <text class="axis" [attr.x]="width" y="127" text-anchor="end">
        {{ daysInMonth() }} {{ monthShort() }}
      </text>
    </svg>
  `,
})
export class MonthChart {
  /** La spesa accumulata, un valore per giorno dal giorno 1 a oggi. */
  readonly spentByDay = input.required<number[]>();
  /** Il mese tipico, un valore per ogni giorno del mese; vuoto se non c'è un solito. */
  readonly usualByDay = input<number[]>([]);
  readonly daysInMonth = input.required<number>();
  /** Il mese abbreviato per le date sotto il grafico: "ott". */
  readonly monthShort = input.required<string>();
  /** La frase per chi non vede il grafico (lettore di schermo). */
  readonly description = input.required<string>();

  protected readonly width = WIDTH;
  protected readonly plotHeight = PLOT_HEIGHT;

  /** Il valore più alto da far stare nel disegno, con un po' di spazio sopra. */
  private readonly max = computed(() => {
    const highest = Math.max(1, ...this.spentByDay(), ...this.usualByDay());
    return highest * 1.08;
  });

  private x(day: number): number {
    return ((day - 1) / Math.max(1, this.daysInMonth() - 1)) * WIDTH;
  }

  private y(value: number): number {
    return PLOT_HEIGHT - (value / this.max()) * (PLOT_HEIGHT - TOP);
  }

  /** Il tracciato di una curva: "M x y" il primo punto, "L x y" i successivi. */
  private path(values: number[]): string {
    return values
      .map((v, i) => `${i === 0 ? 'M' : 'L'}${this.x(i + 1).toFixed(1)} ${this.y(v).toFixed(1)}`)
      .join('');
  }

  protected readonly monthPath = computed(() => this.path(this.spentByDay()));
  protected readonly usualPath = computed(() => this.path(this.usualByDay()));

  /** L'area sotto la linea del mese: la stessa linea, chiusa giù fino alla base. */
  protected readonly areaPath = computed(() => {
    const days = this.spentByDay().length;
    return `${this.monthPath()}L${this.x(days).toFixed(1)} ${PLOT_HEIGHT}L0 ${PLOT_HEIGHT}Z`;
  });

  protected readonly todayPoint = computed(() => {
    const values = this.spentByDay();
    return { x: this.x(values.length), y: this.y(values[values.length - 1] ?? 0) };
  });

  /** Una o due linee di riferimento a valori tondi (500 €, 1.000 €), con la scritta. */
  protected readonly grid = computed(() => {
    const max = this.max();
    const step = STEPS.find((s) => max / s <= 2.5) ?? STEPS[STEPS.length - 1];
    const lines = [];
    for (let value = step; value < max; value += step) {
      // A mano: in italiano toLocaleString non separa le migliaia sotto 10.000 ("1000 €").
      const label = `${String(value).replace(/\B(?=(\d{3})+(?!\d))/g, '.')} €`;
      lines.push({ value, y: this.y(value), label });
    }
    return lines;
  });
}
