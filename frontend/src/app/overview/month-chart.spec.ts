import { TestBed } from '@angular/core/testing';

import { MonthChart } from './month-chart';

describe('MonthChart', () => {
  async function render(spentByDay: number[], usualByDay: number[]) {
    const fixture = TestBed.createComponent(MonthChart);
    fixture.componentRef.setInput('spentByDay', spentByDay);
    fixture.componentRef.setInput('usualByDay', usualByDay);
    fixture.componentRef.setInput('daysInMonth', 31);
    fixture.componentRef.setInput('monthShort', 'ott');
    fixture.componentRef.setInput('description', 'Spesa accumulata nel mese');
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('draws the month up to today, the typical month to the end, and the dates under it', async () => {
    const chart = await render([0, 100, 100, 250], [10, 20, 30, 40, 50]);
    const month = chart.querySelector('path.month')?.getAttribute('d') ?? '';

    expect(month.match(/[ML]/g)?.length).toBe(4); // un punto per giorno, fino a oggi
    expect(month.startsWith('M0.0 ')).toBe(true); // il giorno 1 è a sinistra
    expect(chart.querySelector('path.usual')?.getAttribute('d')?.match(/L/g)?.length).toBe(4);
    expect(chart.querySelector('circle.today')?.getAttribute('cx')).toBe(String((3 / 30) * 340));
    expect(chart.textContent).toContain('1 ott');
    expect(chart.textContent).toContain('31 ott');
  });

  it('the line of today is higher when more was spent', async () => {
    const chart = await render([0, 50, 300], []);
    const ys = (chart.querySelector('path.month')?.getAttribute('d') ?? '')
      .split(/[ML]/)
      .filter(Boolean)
      .map((point) => Number(point.split(' ')[1]));

    expect(ys[0]).toBe(112); // zero: sulla base
    expect(ys[2]).toBeLessThan(ys[1]); // più spesa = più in alto (la y dell'SVG cresce verso il basso)
    expect(chart.querySelector('path.usual')).toBeNull(); // nessun mese tipico
  });

  it('draws reference lines at round values, with the thousands separated', async () => {
    const chart = await render([0, 1800], [0, 2000]);
    const labels = Array.from(chart.querySelectorAll('text')).map((t) => t.textContent?.trim());

    expect(labels).toContain('1.000 €');
    expect(labels).toContain('2.000 €');
  });
});
