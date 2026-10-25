import { TestBed } from '@angular/core/testing';

import { BarChart } from './bar-chart';

describe('BarChart', () => {
  async function render(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(BarChart);
    const all = { labels: ['A', 'B', 'C'], description: 'Spese', ...inputs };
    for (const [key, value] of Object.entries(all)) {
      fixture.componentRef.setInput(key, value);
    }
    await fixture.whenStable();
    return { fixture, svg: fixture.nativeElement as HTMLElement };
  }

  it('the chosen bar has the accent, the others are grey, the average is a line with its label', async () => {
    const { svg } = await render({
      values: [100, 300, 200],
      selected: 1,
      average: 150,
      averageLabel: 'media 150 €',
    });
    const bars = Array.from(svg.querySelectorAll('path.bar'));

    expect(bars.map((b) => Array.from(b.classList).sort().join(' '))).toEqual([
      'bar',
      'bar on',
      'bar',
    ]);
    expect(svg.querySelector('line.average')).not.toBeNull();
    expect(svg.querySelector('.average-label')?.textContent?.trim()).toBe('media 150 €');
    expect(svg.querySelector('svg')?.getAttribute('aria-label')).toBe('Spese');
    // Più alto il valore, più in alto la barra (la y dell'SVG cresce verso il basso).
    const top = (i: number) => Number(bars[i].getAttribute('d')!.split('V')[1].split('Q')[0]);
    expect(top(1)).toBeLessThan(top(2));
    expect(top(2)).toBeLessThan(top(0));
  });

  it('the net goes up and down in two colours', async () => {
    const { svg } = await render({ values: [500, -200, 0], selected: 0, diverging: true });
    const classes = Array.from(svg.querySelectorAll('path.bar')).map((b) =>
      Array.from(b.classList).sort().join(' '),
    );

    expect(classes).toEqual(['bar on pos', 'bar neg', 'bar pos']);
    expect(svg.querySelectorAll('path.bar')[2].getAttribute('d')).toBe(''); // zero: nessuna barra
  });

  it('interactive bars are buttons, also with the keyboard; future bars cannot be chosen', async () => {
    const { fixture, svg } = await render({
      values: [100, 300, 0],
      names: ['uno', 'due', 'tre'],
      future: [false, false, true],
      selected: 0,
      interactive: true,
    });
    const picked: number[] = [];
    fixture.componentInstance.picked.subscribe((i) => picked.push(i));
    const hits = Array.from(svg.querySelectorAll<SVGRectElement>('rect.hit'));

    expect(hits.map((h) => h.getAttribute('aria-label'))).toEqual(['uno', 'due']);
    expect(hits[0].getAttribute('role')).toBe('button');
    expect(hits[0].getAttribute('aria-pressed')).toBe('true');
    hits[1].dispatchEvent(new MouseEvent('click'));
    hits[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(picked).toEqual([1, 0]);
  });

  it('a chart that is only to look at has no buttons', async () => {
    const { svg } = await render({ values: [1, 2, 3] });
    expect(svg.querySelectorAll('rect.hit').length).toBe(0);
  });
});
