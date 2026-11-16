import { formatCurrency } from '@angular/common';
import { Component, LOCALE_ID, computed, inject, input, output, signal } from '@angular/core';

import { smoothPath } from '../overview/month-chart';
import { PrivacyStore } from '../privacy/privacy-store';

const WIDTH = 340;
const HEIGHT = 156;
const BASE = 128; // la linea di base; sotto, i mesi
const TOP = 14;

/** I passi "tondi" per le linee di riferimento, come nel grafico del mese. */
const STEPS = [
  50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 20000, 25000, 50000, 100000, 200000,
  250000, 500000,
];

/**
 * Una linea che cresce mese per mese (docs/investimenti.md, parte D): il versato negli investimenti. È il fratello
 * del grafico del Riepilogo (MonthChart): la stessa curva (smoothPath), la stessa sfumatura, la stessa linea che si
 * disegna all'apertura e lo stesso scorrimento con il dito, il mouse o le frecce. Cambia la scala: un punto per
 * mese invece che per giorno. Gli stili sono quelli di MonthChart (le classi month-chart...).
 */
@Component({
  selector: 'app-trend-chart',
  template: `
    <div
      class="month-chart-wrap"
      [class.scrubbing]="index() !== null"
      tabindex="0"
      role="slider"
      [attr.aria-label]="description() + '. Con le frecce si scorrono i mesi.'"
      [attr.aria-valuemin]="1"
      [attr.aria-valuemax]="values().length"
      [attr.aria-valuenow]="(index() ?? values().length - 1) + 1"
      [attr.aria-valuetext]="tip()?.text ?? null"
      (pointermove)="onPointer($event)"
      (pointerdown)="onPointer($event)"
      (pointerleave)="pick(null)"
      (keydown)="onKey($event)"
      (blur)="pick(null)"
    >
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
          @for (d of ticks(); track d.index) {
            <text class="axis" [attr.x]="d.x" [attr.y]="height - 4" [attr.text-anchor]="d.anchor">
              {{ d.label }}
            </text>
          }
        </svg>
        <svg class="month-chart layer area-layer" [attr.viewBox]="'0 0 ' + width + ' ' + height">
          <path class="area" [attr.d]="areaPath()" />
        </svg>
        <svg class="month-chart layer line-layer" [attr.viewBox]="'0 0 ' + width + ' ' + height">
          <!-- I tratti stimati (parametro estimated) tratteggiati, quelli veri pieni. -->
          @for (seg of segments(); track $index) {
            @if (seg.estimated) {
              <path class="estimate" [attr.d]="seg.path" />
            } @else {
              <path class="month" pathLength="1" [attr.d]="seg.path" />
            }
          }
          @if (tip(); as t) {
            <line class="guide" [attr.x1]="t.x" [attr.x2]="t.x" [attr.y1]="top" [attr.y2]="base" />
            <circle class="today" [attr.cx]="t.x" [attr.cy]="t.y" r="5" />
          } @else {
            <circle class="today" [attr.cx]="last().x" [attr.cy]="last().y" r="5" />
          }
        </svg>
      }
      @if (tip(); as t) {
        <div class="scrub-tip" [style.left.%]="t.left" aria-hidden="true">{{ t.label }}</div>
      }
    </div>
  `,
})
export class TrendChart {
  private readonly locale = inject(LOCALE_ID);
  private readonly privacy = inject(PrivacyStore);

  /** Un valore per mese, dal più vecchio. */
  readonly values = input.required<number[]>();
  /** Il mese abbreviato di ogni valore, per l'asse: "nov", "dic"... */
  readonly labels = input.required<string[]>();
  /** Il mese per esteso, per l'etichetta mentre si scorre: "ottobre 2026". */
  readonly names = input.required<string[]>();
  readonly description = input.required<string>();
  readonly currency = input('EUR');
  /**
   * Per ogni valore, se è stimato e non vero (lo storico della liquidità, docs/saldi.md parte L): un tratto tra due
   * valori veri si disegna pieno, ogni altro tratteggiato. Vuoto (il solito) = tutti veri.
   */
  readonly estimated = input<readonly boolean[]>([]);
  /**
   * true (il solito): la scala parte da zero, come per l'investito. false: parte poco sotto il valore più basso, per
   * vedere come cambia una cifra che non va mai vicino allo zero (la liquidità).
   */
  readonly zeroBased = input(true);

  /** Il mese su cui si sta scorrendo (0 = il primo), o null. */
  readonly scrubbed = output<number | null>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly base = BASE;
  protected readonly top = TOP;

  protected readonly index = signal<number | null>(null);

  protected readonly animationKey = computed(() => this.values().join(','));

  /** La scala: da zero (o dal valore più basso, se è negativo) al più alto, con un po' di spazio sopra. */
  private readonly scale = computed(() => {
    const values = this.values();
    if (!this.zeroBased() && values.length > 0) {
      const low = Math.min(...values);
      const high = Math.max(...values);
      const pad = (high - low) * 0.15 || Math.abs(high) * 0.05 || 1;
      return { max: high + pad, min: low - pad };
    }
    const max = Math.max(1, ...values) * 1.08;
    const min = Math.min(0, ...values);
    return { max, min };
  });

  private x(i: number): number {
    return (i / Math.max(1, this.values().length - 1)) * WIDTH;
  }

  private y(value: number): number {
    const { max, min } = this.scale();
    return BASE - ((value - min) / (max - min)) * (BASE - TOP);
  }

  private readonly points = computed(() =>
    this.values().map((v, i) => ({ x: this.x(i), y: this.y(v) })),
  );

  /**
   * La linea, in pezzi: ogni pezzo è una serie di tratti dello stesso tipo, vero (tra due valori veri) o stimato. Due
   * pezzi vicini hanno in comune il punto dove si toccano, così la linea è continua. Senza stime, un pezzo solo.
   */
  protected readonly segments = computed(() => {
    const points = this.points();
    const estimated = this.estimated();
    if (points.length < 2) {
      return points.length === 1 ? [{ estimated: false, path: smoothPath(points) }] : [];
    }
    const kind = (t: number) => Boolean(estimated[t] || estimated[t + 1]); // il tratto dal punto t a t+1
    const segments: { estimated: boolean; path: string }[] = [];
    let from = 0; // il primo tratto del pezzo in corso
    for (let t = 1; t <= points.length - 1; t++) {
      // Finiti i tratti, o il tratto t è di un altro tipo: il pezzo va dal punto "from" al punto t.
      if (t === points.length - 1 || kind(t) !== kind(from)) {
        segments.push({ estimated: kind(from), path: smoothPath(points.slice(from, t + 1)) });
        from = t;
      }
    }
    return segments;
  });

  /** L'area sotto tutta la linea, stima compresa. */
  protected readonly areaPath = computed(() => {
    const n = this.values().length;
    return n === 0
      ? ''
      : `${smoothPath(this.points())}L${this.x(n - 1).toFixed(1)} ${BASE}L0 ${BASE}Z`;
  });

  protected readonly last = computed(() => {
    const values = this.values();
    return { x: this.x(values.length - 1), y: this.y(values[values.length - 1] ?? 0) };
  });

  /** Le linee di riferimento a valori tondi, con la scritta (nascosta in modalità discreta). */
  protected readonly grid = computed(() => {
    const { max, min } = this.scale();
    const step = STEPS.find((s) => (max - min) / s <= 2.5) ?? STEPS[STEPS.length - 1];
    const lines = [];
    // Dal primo passo sopra lo zero; con la scala che non parte da zero, dal primo multiplo sopra il fondo.
    const first = this.zeroBased() ? step : (Math.floor(min / step) + 1) * step;
    for (let value = first; value < max; value += step) {
      const symbol = this.currency() === 'EUR' ? '€' : this.currency(); // come il resto dell'app: "700 USD"
      const label = this.privacy.hide(
        `${String(value).replace(/\B(?=(\d{3})+(?!\d))/g, '.')} ${symbol}`,
      );
      lines.push({ value, y: this.y(value), label });
    }
    return lines;
  });

  /** Cinque mesi sotto il grafico, distribuiti: il primo, l'ultimo e tre in mezzo. */
  protected readonly ticks = computed(() => {
    const n = this.values().length;
    const wanted = Math.min(5, n);
    const indexes = new Set<number>();
    for (let k = 0; k < wanted; k++) {
      indexes.add(Math.round((k * (n - 1)) / Math.max(1, wanted - 1)));
    }
    return [...indexes].map((i) => ({
      index: i,
      x: this.x(i),
      label: this.labels()[i] ?? '',
      anchor: i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle',
    }));
  });

  protected readonly tip = computed(() => {
    const i = this.index();
    if (i === null) {
      return null;
    }
    const value = this.values()[i] ?? 0;
    const x = this.x(i);
    const amount = this.privacy.hide(
      formatCurrency(
        value,
        this.locale,
        this.currency() === 'EUR' ? '€' : this.currency(),
        this.currency(),
        '1.0-0',
      ),
    );
    return {
      x,
      y: this.y(value),
      left: Math.min(85, Math.max(15, (x / WIDTH) * 100)),
      label: this.names()[i] ?? '',
      text: `${this.names()[i] ?? ''}: ${amount}`,
    };
  });

  protected onPointer(event: PointerEvent): void {
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    if (box.width === 0 || this.values().length === 0) {
      return;
    }
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    this.pick(Math.round(ratio * (this.values().length - 1)));
  }

  protected onKey(event: KeyboardEvent): void {
    const lastIndex = this.values().length - 1;
    const current = this.index() ?? lastIndex;
    const next: Record<string, number | null> = {
      ArrowLeft: Math.max(0, current - 1),
      ArrowRight: Math.min(lastIndex, current + 1),
      Home: 0,
      End: lastIndex,
      Escape: null,
    };
    if (event.key in next) {
      event.preventDefault();
      this.pick(next[event.key]);
    }
  }

  protected pick(index: number | null): void {
    if (index !== this.index()) {
      this.index.set(index);
      this.scrubbed.emit(index);
    }
  }
}
