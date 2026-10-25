import { Component, computed, input, output } from '@angular/core';

const WIDTH = 340;
const HEIGHT = 176;
const TOP = 16; // spazio sopra la barra più alta, per la scritta della media
const BOTTOM = 150; // la base delle barre (sotto ci sono le etichette)
const MAX_BAR = 22; // le barre non diventano mai più larghe di così: sottili si leggono meglio

/**
 * Il grafico a barre di Analisi (docs/38, parte E): una barra per giorno, settimana o mese. La barra scelta ha
 * l'accento, le altre sono grigie; una linea continua segna la media di solito. Con `diverging` (il netto) le barre
 * vanno in su (risparmiato) o in giù (speso più di quanto è entrato), in due colori.
 *
 * Con `interactive` ogni barra è un pulsante: si tocca, o con la tastiera Tab e Invio. L'area toccabile è alta quanto
 * il grafico, così anche una barra bassa si prende con il pollice.
 */
@Component({
  selector: 'app-bar-chart',
  template: `
    <svg
      class="bar-chart"
      [attr.viewBox]="'0 0 ' + width + ' ' + height"
      role="img"
      [attr.aria-label]="description()"
    >
      <line class="zero" x1="0" [attr.y1]="zeroY()" [attr.x2]="width" [attr.y2]="zeroY()" />
      @for (b of shapes(); track $index) {
        <path [attr.d]="b.d" [class]="b.cls" />
        <text class="axis" [class.on]="b.selected" [attr.x]="b.cx" y="168" text-anchor="middle">
          {{ labels()[$index] }}
        </text>
      }
      @if (averageY() !== null) {
        <line
          class="average"
          x1="0"
          [attr.y1]="averageY()"
          [attr.x2]="width"
          [attr.y2]="averageY()"
        />
        <text class="average-label" [attr.x]="width" [attr.y]="averageY()! - 4" text-anchor="end">
          {{ averageLabel() }}
        </text>
      }
      @if (interactive()) {
        @for (b of shapes(); track $index) {
          @if (!b.future) {
            <rect
              class="hit"
              [attr.x]="b.x"
              y="0"
              [attr.width]="b.slot"
              [attr.height]="height"
              role="button"
              tabindex="0"
              [attr.aria-label]="names()[$index]"
              [attr.aria-pressed]="b.selected"
              (click)="picked.emit($index)"
              (keydown.enter)="picked.emit($index)"
              (keydown.space)="$event.preventDefault(); picked.emit($index)"
            />
          }
        }
      }
    </svg>
  `,
})
export class BarChart {
  readonly values = input.required<number[]>();
  readonly labels = input.required<string[]>();
  /** Il nome di ogni barra per i lettori di schermo: "ottobre 2026, 1.284 €". */
  readonly names = input<string[]>([]);
  readonly future = input<boolean[]>([]);
  readonly selected = input<number | null>(null);
  readonly average = input<number | null>(null);
  readonly averageLabel = input('');
  readonly diverging = input(false);
  readonly interactive = input(false);
  readonly description = input.required<string>();

  readonly picked = output<number>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;

  /** La scala: dal valore più basso (zero, o il più negativo) al più alto (contando anche la media). */
  private readonly scale = computed(() => {
    const values = this.values();
    const max = Math.max(0, this.average() ?? 0, ...values);
    const min = Math.min(0, ...values);
    const span = max - min || 1;
    return { max, perUnit: (BOTTOM - TOP) / span };
  });

  private y(value: number): number {
    const { max, perUnit } = this.scale();
    return TOP + (max - value) * perUnit;
  }

  protected readonly zeroY = computed(() => this.y(0));

  protected readonly averageY = computed(() => {
    const average = this.average();
    return average === null ? null : this.y(average);
  });

  protected readonly shapes = computed(() => {
    const values = this.values();
    const slot = WIDTH / Math.max(1, values.length);
    const barWidth = Math.min(MAX_BAR, slot * 0.6);
    const zero = this.zeroY();
    return values.map((value, i) => {
      const x = i * slot;
      const left = x + (slot - barWidth) / 2;
      const top = this.y(value);
      const selected = i === this.selected();
      let cls = selected ? 'bar on' : 'bar';
      if (this.diverging()) {
        cls = `bar ${value >= 0 ? 'pos' : 'neg'}${selected ? ' on' : ''}`;
      }
      return {
        x: x.toFixed(1),
        slot: slot.toFixed(1),
        cx: (x + slot / 2).toFixed(1),
        d: rounded(left, Math.min(top, zero), Math.max(top, zero), barWidth, value >= 0),
        cls,
        selected,
        future: this.future()[i] ?? false,
      };
    });
  });
}

/**
 * Il tracciato di una barra arrotondata solo dal lato del valore (in alto se positiva, in basso se negativa): dritta
 * sulla linea dello zero, come in tutti i grafici dell'app.
 */
function rounded(x: number, top: number, bottom: number, w: number, up: boolean): string {
  const h = bottom - top;
  if (h < 0.5) {
    return '';
  }
  const r = Math.min(4, h / 2, w / 2);
  const f = (n: number) => n.toFixed(1);
  return up
    ? `M${f(x)} ${f(bottom)}V${f(top + r)}Q${f(x)} ${f(top)} ${f(x + r)} ${f(top)}H${f(x + w - r)}` +
        `Q${f(x + w)} ${f(top)} ${f(x + w)} ${f(top + r)}V${f(bottom)}Z`
    : `M${f(x)} ${f(top)}V${f(bottom - r)}Q${f(x)} ${f(bottom)} ${f(x + r)} ${f(bottom)}H${f(x + w - r)}` +
        `Q${f(x + w)} ${f(bottom)} ${f(x + w)} ${f(bottom - r)}V${f(top)}Z`;
}
