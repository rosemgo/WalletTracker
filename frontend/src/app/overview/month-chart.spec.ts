import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { MonthChart, smoothPath } from './month-chart';

/** I punti per cui passa un tracciato: il primo dopo "M", poi l'ultimo di ogni curva "C". */
function endpoints(d: string): { x: number; y: number }[] {
  return d
    .split(/[MC]/)
    .filter(Boolean)
    .map((segment) => {
      const numbers = segment.trim().split(/\s+/).map(Number);
      return { x: numbers[numbers.length - 2], y: numbers[numbers.length - 1] };
    });
}

describe('MonthChart', () => {
  beforeEach(() => {
    registerLocaleData(localeIt);
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: 'it-IT' }] });
  });

  async function render(spentByDay: number[], usualByDay: number[]) {
    const fixture = TestBed.createComponent(MonthChart);
    fixture.componentRef.setInput('spentByDay', spentByDay);
    fixture.componentRef.setInput('usualByDay', usualByDay);
    fixture.componentRef.setInput('daysInMonth', 31);
    fixture.componentRef.setInput('monthShort', 'ott');
    fixture.componentRef.setInput('description', 'Spesa accumulata nel mese');
    await fixture.whenStable();
    return { fixture, chart: fixture.nativeElement as HTMLElement };
  }

  it('draws the month up to today, the typical month to the end, and the dates under it', async () => {
    const { chart } = await render([0, 100, 100, 250], [10, 20, 30, 40, 50]);
    const month = chart.querySelector('path.month')?.getAttribute('d') ?? '';

    expect(endpoints(month)).toHaveLength(4); // un punto per giorno, fino a oggi
    expect(month.startsWith('M0.0 ')).toBe(true); // il giorno 1 è a sinistra
    expect(endpoints(chart.querySelector('path.usual')?.getAttribute('d') ?? '')).toHaveLength(5);
    expect(chart.querySelector('circle.today')?.getAttribute('cx')).toBe(String((3 / 30) * 340));
    expect(chart.textContent).toContain('1 ott');
    expect(chart.textContent).toContain('15 ott');
    expect(chart.textContent).toContain('31 ott');
  });

  it('the line of today is higher when more was spent', async () => {
    const { chart } = await render([0, 50, 300], []);
    const ys = endpoints(chart.querySelector('path.month')?.getAttribute('d') ?? '').map(
      (p) => p.y,
    );

    expect(ys[0]).toBe(128); // zero: sulla base
    expect(ys[2]).toBeLessThan(ys[1]); // più spesa = più in alto (la y dell'SVG cresce verso il basso)
    expect(chart.querySelector('path.usual')).toBeNull(); // nessun mese tipico
  });

  it('draws reference lines at round values, with the thousands separated', async () => {
    const { chart } = await render([0, 1800], [0, 2000]);
    const labels = Array.from(chart.querySelectorAll('text')).map((t) => t.textContent?.trim());

    expect(labels).toContain('1.000 €');
    expect(labels).toContain('2.000 €');
  });

  it('with the arrow keys moves along the days and tells the amount of each', async () => {
    const { fixture, chart } = await render([10, 20, 35, 50], [5, 15, 30, 40, 60]);
    const days: (number | null)[] = [];
    fixture.componentInstance.scrubbed.subscribe((day) => days.push(day));
    const slider = chart.querySelector('[role="slider"]') as HTMLElement;
    const press = async (key: string) => {
      slider.dispatchEvent(new KeyboardEvent('keydown', { key }));
      await fixture.whenStable();
    };

    await press('ArrowLeft'); // da oggi (il giorno 4) al giorno prima
    expect(slider.getAttribute('aria-valuetext')).toMatch(/^3 ott: 35\s€, di solito 30\s€$/);
    expect(chart.querySelector('.scrub-tip')?.textContent?.trim()).toMatch(
      /^3 ott · di solito 30\s€$/,
    );

    await press('End'); // l'ultimo giorno: la spesa non c'è ancora (e qui nemmeno il mese tipico)
    expect(slider.getAttribute('aria-valuetext')).toBe('31 ott');
    await press('Home');
    await press('Escape');
    expect(days).toEqual([3, 31, 1, null]);
    expect(chart.querySelector('.scrub-tip')).toBeNull();
  });
});

describe('smoothPath', () => {
  it('passes through every point and never goes above or below them', () => {
    // Una spesa accumulata: sale, resta ferma, sale. La curva non deve "scendere" nel tratto fermo.
    const points = [
      { x: 0, y: 100 },
      { x: 10, y: 60 },
      { x: 20, y: 60 },
      { x: 30, y: 20 },
    ];
    const d = smoothPath(points);
    const numbers = d
      .split(/[MC]/)
      .filter(Boolean)
      .flatMap((s) => s.trim().split(/\s+/).map(Number));
    const ys = numbers.filter((_, i) => i % 2 === 1);

    expect(endpoints(d)).toEqual(points);
    expect(Math.min(...ys)).toBeGreaterThanOrEqual(20);
    expect(Math.max(...ys)).toBeLessThanOrEqual(100);
    // Nel tratto fermo (da x 10 a 20) i punti di controllo restano alla stessa altezza.
    expect(d).toContain('C13.3 60.0 16.7 60.0 20.0 60.0');
  });

  it('draws nothing without points and only a start with one', () => {
    expect(smoothPath([])).toBe('');
    expect(smoothPath([{ x: 5, y: 7 }])).toBe('M5.0 7.0');
  });
});
