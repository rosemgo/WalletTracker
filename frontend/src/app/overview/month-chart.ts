import { formatCurrency } from '@angular/common';
import { Component, LOCALE_ID, computed, inject, input, output, signal } from '@angular/core';

import { PrivacyStore } from '../privacy/privacy-store';

/** La larghezza e l'altezza del disegno: l'SVG poi si allarga quanto la scheda (viewBox). */
const WIDTH = 340;
const HEIGHT = 156;
const BASE = 128; // la linea di base (zero); sotto, le date
const TOP = 14; // lo spazio sopra il punto più alto

/** I passi "tondi" per le linee di riferimento: 50 €, 100 €, 200 €... */
const STEPS = [
  10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000, 25000, 50000,
];

/** Un punto del disegno. */
interface Point {
  x: number;
  y: number;
}

/**
 * Il grafico del mese (docs/36, parte E; rinnovato in docs/41, parte G): la spesa accumulata dal giorno 1 a oggi
 * (accento), contro la stessa curva di un mese tipico (tratteggiata). A colpo d'occhio: la linea colorata sopra quella
 * tratteggiata vuol dire "sto spendendo più del solito", e la pendenza dice se sto accelerando.
 *
 * Perché sembri "vivo", come i grafici delle app delle banche:
 * - la linea è una **curva morbida** (spline monotona, vedi smoothPath) e si **disegna** da sinistra all'apertura;
 * - sotto la linea, una **sfumatura** che sparisce verso il basso;
 * - il punto di oggi **pulsa** (solo nel mese in corso: è il dato che sta ancora cambiando);
 * - passando il dito o il mouse sul grafico si **scorre** tra i giorni: una linea verticale, i due punti e la cifra di
 *   quel giorno. Con la tastiera: Tab sul grafico, poi le frecce (Inizio e Fine per il primo e l'ultimo giorno).
 *
 * È fatto di tre disegni SVG sovrapposti, con lo stesso viewBox: le linee di riferimento, la sfumatura e la linea.
 * Tre strati invece di uno perché la sfumatura è una maschera CSS (mask-image) e l'alone della linea un filtro CSS
 * (drop-shadow): funzionano su un elemento intero, non su un pezzo di un disegno. Le sfumature SVG (linearGradient)
 * si richiamano con url(#id), che con il <base href> delle app Angular in alcuni browser non trova il disegno.
 */
@Component({
  selector: 'app-month-chart',
  template: `
    <div
      class="month-chart-wrap"
      [class.scrubbing]="day() !== null"
      tabindex="0"
      role="slider"
      [attr.aria-label]="description() + '. Con le frecce si scorrono i giorni.'"
      [attr.aria-valuemin]="1"
      [attr.aria-valuemax]="daysInMonth()"
      [attr.aria-valuenow]="day() ?? spentByDay().length"
      [attr.aria-valuetext]="tip()?.text ?? null"
      (pointermove)="onPointer($event)"
      (pointerdown)="onPointer($event)"
      (pointerleave)="pick(null)"
      (keydown)="onKey($event)"
      (blur)="pick(null)"
    >
      <!-- @for con una chiave sola: quando cambia il mese, il disegno si ricrea e l'animazione riparte. -->
      @for (k of [animationKey()]; track k) {
        <svg
          class="month-chart"
          [attr.viewBox]="'0 0 ' + width + ' ' + height"
          role="img"
          [attr.aria-label]="description()"
        >
          @for (line of grid(); track line.value) {
            <line class="grid" x1="0" [attr.y1]="line.y" [attr.x2]="width" [attr.y2]="line.y" />
            <text class="axis" [attr.x]="width" [attr.y]="line.y - 5" text-anchor="end">
              {{ line.label }}
            </text>
          }
          <line class="base" x1="0" [attr.y1]="base" [attr.x2]="width" [attr.y2]="base" />
          @if (usualPath()) {
            <path class="usual" [attr.d]="usualPath()" />
          }
          @for (d of dates(); track d.day) {
            <text class="axis" [attr.x]="d.x" [attr.y]="height - 4" [attr.text-anchor]="d.anchor">
              {{ d.label }}
            </text>
          }
        </svg>
        @if (monthPath()) {
          <svg class="month-chart layer area-layer" [attr.viewBox]="'0 0 ' + width + ' ' + height">
            <path class="area" [attr.d]="areaPath()" />
          </svg>
          <svg class="month-chart layer line-layer" [attr.viewBox]="'0 0 ' + width + ' ' + height">
            <!-- pathLength="1": la lunghezza della linea vale 1, così l'animazione che la disegna è la stessa per
                 ogni mese (stroke-dasharray e stroke-dashoffset in CSS). -->
            <path class="month" pathLength="1" [attr.d]="monthPath()" />
            @if (tip(); as t) {
              <line
                class="guide"
                [attr.x1]="t.x"
                [attr.x2]="t.x"
                [attr.y1]="top"
                [attr.y2]="base"
              />
              @if (t.usualY !== null) {
                <circle class="usual-dot" [attr.cx]="t.x" [attr.cy]="t.usualY" r="3.5" />
              }
              @if (t.spentY !== null) {
                <circle class="today" [attr.cx]="t.x" [attr.cy]="t.spentY" r="5" />
              }
            } @else {
              @if (live()) {
                <circle class="halo" [attr.cx]="todayPoint().x" [attr.cy]="todayPoint().y" r="5" />
              }
              <circle class="today" [attr.cx]="todayPoint().x" [attr.cy]="todayPoint().y" r="5" />
            }
          </svg>
        }
      }
      @if (tip(); as t) {
        <!-- L'etichetta del giorno: HTML sopra il disegno, così il testo resta nitido e della sua misura. -->
        <div class="scrub-tip" [style.left.%]="t.left" aria-hidden="true">{{ t.label }}</div>
      }
    </div>
  `,
})
export class MonthChart {
  private readonly locale = inject(LOCALE_ID);
  private readonly privacy = inject(PrivacyStore);

  /** La spesa accumulata, un valore per giorno dal giorno 1 a oggi. */
  readonly spentByDay = input.required<number[]>();
  /** Il mese tipico, un valore per ogni giorno del mese; vuoto se non c'è un solito. */
  readonly usualByDay = input<number[]>([]);
  readonly daysInMonth = input.required<number>();
  /** Il mese abbreviato per le date sotto il grafico: "ott". */
  readonly monthShort = input.required<string>();
  /** La frase per chi non vede il grafico (lettore di schermo). */
  readonly description = input.required<string>();
  readonly currency = input('EUR');
  /** Il mese in corso: il punto di oggi pulsa. */
  readonly live = input(false);

  /** Il giorno su cui si sta scorrendo (1 = il primo), o null: per aggiornare la cifra grande sopra il grafico. */
  readonly scrubbed = output<number | null>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly base = BASE;
  protected readonly top = TOP;

  /** Il giorno scelto scorrendo, o null. */
  protected readonly day = signal<number | null>(null);

  protected readonly animationKey = computed(
    () => `${this.monthShort()}-${this.daysInMonth()}-${this.spentByDay().length}`,
  );

  /** Il valore più alto da far stare nel disegno, con un po' di spazio sopra. */
  private readonly max = computed(() => {
    const highest = Math.max(1, ...this.spentByDay(), ...this.usualByDay());
    return highest * 1.08;
  });

  private x(day: number): number {
    return ((day - 1) / Math.max(1, this.daysInMonth() - 1)) * WIDTH;
  }

  private y(value: number): number {
    return BASE - (value / this.max()) * (BASE - TOP);
  }

  private points(values: number[]): Point[] {
    return values.map((v, i) => ({ x: this.x(i + 1), y: this.y(v) }));
  }

  protected readonly monthPath = computed(() => smoothPath(this.points(this.spentByDay())));
  protected readonly usualPath = computed(() => smoothPath(this.points(this.usualByDay())));

  /** L'area sotto la linea del mese: la stessa linea, chiusa giù fino alla base. */
  protected readonly areaPath = computed(() => {
    const days = this.spentByDay().length;
    return `${this.monthPath()}L${this.x(days).toFixed(1)} ${BASE}L0 ${BASE}Z`;
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
      const label = this.privacy.hide(`${String(value).replace(/\B(?=(\d{3})+(?!\d))/g, '.')} €`);
      lines.push({ value, y: this.y(value), label });
    }
    return lines;
  });

  /** Le date sotto il grafico: il primo giorno, ogni settimana, l'ultimo. */
  protected readonly dates = computed(() => {
    const last = this.daysInMonth();
    const days = [1, 8, 15, 22].filter((d) => last - d >= 4);
    days.push(last);
    return days.map((day) => ({
      day,
      x: this.x(day),
      label: `${day} ${this.monthShort()}`,
      anchor: day === 1 ? 'start' : day === last ? 'end' : 'middle',
    }));
  });

  /** Che cosa mostrare del giorno scelto: la cifra, la data, dove disegnare i punti. */
  /**
   * Che cosa mostrare del giorno scelto: la data e il solito nell'etichetta sopra il grafico (la cifra spesa la mostra
   * la cifra grande, con l'output scrubbed), la frase completa per i lettori di schermo, dove disegnare i punti.
   */
  protected readonly tip = computed(() => {
    const day = this.day();
    if (day === null) {
      return null;
    }
    const spent = this.spentByDay()[day - 1];
    const usual = this.usualByDay()[day - 1];
    const date = `${day} ${this.monthShort()}`;
    const usualText = usual !== undefined ? `di solito ${this.euros(usual)}` : null;
    const spentText = spent !== undefined ? this.euros(spent) : null;
    const x = this.x(day);
    return {
      x,
      left: Math.min(85, Math.max(15, (x / WIDTH) * 100)), // l'etichetta non esce dalla scheda
      spentY: spent !== undefined ? this.y(spent) : null,
      usualY: usual !== undefined ? this.y(usual) : null,
      label: [date, usualText].filter(Boolean).join(' · '),
      text: [date, [spentText, usualText].filter(Boolean).join(', ')].filter(Boolean).join(': '),
    };
  });

  protected onPointer(event: PointerEvent): void {
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    if (box.width === 0) {
      return;
    }
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    this.pick(Math.round(ratio * (this.daysInMonth() - 1)) + 1);
  }

  protected onKey(event: KeyboardEvent): void {
    const today = this.spentByDay().length;
    const current = this.day() ?? today;
    const next: Record<string, number | null> = {
      ArrowLeft: Math.max(1, current - 1),
      ArrowRight: Math.min(this.daysInMonth(), current + 1),
      Home: 1,
      End: this.daysInMonth(),
      Escape: null,
    };
    if (event.key in next) {
      event.preventDefault();
      this.pick(next[event.key]);
    }
  }

  protected pick(day: number | null): void {
    if (day !== this.day()) {
      this.day.set(day);
      this.scrubbed.emit(day);
    }
  }

  private euros(value: number): string {
    const currency = this.currency();
    // Con la modalità discreta, le cifre nascoste (docs/42): anche nelle frasi costruite qui.
    return this.privacy.hide(
      formatCurrency(value, this.locale, currency === 'EUR' ? '€' : currency, currency, '1.0-0'),
    );
  }
}

/**
 * Una curva morbida che passa per tutti i punti: una "spline cubica monotona" (il metodo di Fritsch e Carlson).
 *
 * Tra due punti vicini disegna una curva di Bézier ("C" nel tracciato SVG), invece di un segmento ("L"). La pendenza
 * in ogni punto è una media di quelle dei due segmenti vicini, ridotta dove serve: così la curva non va mai più su
 * del punto più alto né più giù di quello più basso tra due giorni. Con una curva qualsiasi (per esempio Catmull-Rom)
 * una spesa accumulata, che non scende mai, potrebbe sembrare scendere per un attimo: sarebbe un dato falso.
 */
export function smoothPath(points: Point[]): string {
  const n = points.length;
  if (n === 0) {
    return '';
  }
  const f = (v: number) => v.toFixed(1);
  if (n === 1) {
    return `M${f(points[0].x)} ${f(points[0].y)}`;
  }
  // Le pendenze dei segmenti, e quelle nei punti (la media dei due vicini, zero dove la curva cambia verso).
  const slopes = points.slice(1).map((p, i) => (p.y - points[i].y) / (p.x - points[i].x || 1));
  const tangents = points.map((_, i) => {
    if (i === 0) return slopes[0];
    if (i === n - 1) return slopes[n - 2];
    const [a, b] = [slopes[i - 1], slopes[i]];
    return a * b <= 0 ? 0 : (2 * a * b) / (a + b); // media "armonica": resta vicina alla pendenza più piccola
  });
  let d = `M${f(points[0].x)} ${f(points[0].y)}`;
  for (let i = 0; i < n - 1; i++) {
    const [p, q] = [points[i], points[i + 1]];
    const dx = (q.x - p.x) / 3; // i punti di controllo a un terzo della distanza, lungo la pendenza
    d += `C${f(p.x + dx)} ${f(p.y + tangents[i] * dx)} ${f(q.x - dx)} ${f(q.y - tangents[i + 1] * dx)} ${f(q.x)} ${f(q.y)}`;
  }
  return d;
}
