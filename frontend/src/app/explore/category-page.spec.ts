import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { Budget, CategoryOverview } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CategoryPage } from './category-page';

function overview(changes: Partial<CategoryOverview> = {}): CategoryOverview {
  return {
    categoryId: 1,
    name: 'Spesa alimentare',
    metric: 'EXPENSES',
    month: '2026-10',
    day: '2026-10-25',
    currency: 'EUR',
    amount: 412,
    usualSoFar: 380,
    usualMonth: 450,
    usualMonths: 6,
    months: Array.from({ length: 12 }, (_, i) => ({
      month: `2026-${String(i + 1).padStart(2, '0')}`,
      value: 360 + i,
    })),
    merchants: [
      {
        key: 'ESSELUNGA',
        categoryId: null,
        name: 'ESSELUNGA MILANO',
        amount: 238,
        movements: 6,
        usual: null,
      },
      { key: 'LIDL', categoryId: null, name: 'LIDL', amount: 96, movements: 1, usual: null },
    ],
    budget: {
      id: 3,
      categoryId: 1,
      currency: 'EUR',
      monthlyAmount: 450,
      category: 'Spesa alimentare',
      spent: 412,
      remaining: 38,
      expectedSoFar: 363,
      averageLast3Months: 380,
      status: 'WARNING',
    } as Budget,
    ...changes,
  };
}

describe('CategoryPage', () => {
  let answer: () => ReturnType<WalletApi['categoryOverview']>;
  const requested: [number, string | null][] = [];
  let routeQuery: Record<string, string>;

  beforeEach(() => {
    registerLocaleData(localeIt);
    requested.length = 0;
    routeQuery = {};
    answer = () => of(overview());
    const fakeApi: Partial<WalletApi> = {
      categoryOverview: (id: number, month: string | null) => {
        requested.push([id, month]);
        return answer();
      },
    };
    TestBed.configureTestingModule({
      imports: [CategoryPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
        {
          provide: ActivatedRoute,
          useFactory: () => ({
            paramMap: of(convertToParamMap({ id: '1' })),
            snapshot: { queryParamMap: convertToParamMap(routeQuery) },
          }),
        },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(CategoryPage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the month against the usual, the budget with the pace mark, the year and where', async () => {
    const page = await render();
    const text = page.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(requested).toEqual([[1, null]]);
    expect(page.querySelector('h1')?.textContent?.trim()).toBe('Spesa alimentare');
    expect(text).toMatch(/a ottobre, finora · 32\s€ più del solito \(380\s€\)\./);
    expect(text).toMatch(/Budget 450\s€/);
    expect(text).toMatch(/38\s€ rimasti · 6 giorni/);
    const fill = page.querySelector('.pace-fill') as HTMLElement;
    const mark = page.querySelector('.pace-mark') as HTMLElement;
    expect(Number(fill.style.width.replace('%', ''))).toBeCloseTo(91.56, 1);
    expect(Number(mark.style.left.replace('%', ''))).toBeCloseTo(80.67, 1);
    expect(page.querySelectorAll('path.bar').length).toBe(12);
    // Accesa la barra del mese guardato (ottobre, la decima di questi dati), non per forza l'ultima.
    expect(page.querySelector('path.bar.on')).toBe(page.querySelectorAll('path.bar')[9]);
    expect(text).toMatch(/ESSELUNGA MILANO\s*6 volte/);
    expect(text).toMatch(/LIDL\s*1 volta/);
    expect(page.querySelector('a.button.primary')?.getAttribute('href')).toBe(
      '/movimenti?categoryId=1',
    );
  });

  it('a past month has no pace mark, an exceeded budget says by how much, no budget offers one', async () => {
    answer = () =>
      of(
        overview({
          day: '2026-09-30',
          month: '2026-09',
          budget: { ...overview().budget!, remaining: -20, status: 'OVER' },
        }),
      );
    routeQuery = { mese: '2026-09' };
    const page = await render();
    // Il mese aperto (prima, se è negli ultimi 12 mesi, anche il grafico fino a oggi).
    expect(requested.at(-1)).toEqual([1, '2026-09']);
    expect(page.querySelector('.pace-mark')).toBeNull();
    expect(page.querySelector('.pace-fill')?.classList.contains('over')).toBe(true);
    expect(page.textContent).toMatch(/superato di 20\s€/);
    expect(page.textContent).toContain('a settembre ·');
  });

  it('without a budget it links to the budgets', async () => {
    answer = () => of(overview({ budget: null, usualSoFar: null }));
    const page = await render();

    expect(page.textContent).toContain('Imposta un budget per questa categoria');
    expect(page.textContent?.replace(/\s+/g, ' ')).toContain('a ottobre, finora.');
  });

  it('the chart shows the last 12 months, or this year from January', async () => {
    // Come l'API: i 12 mesi che finiscono con ottobre 2026, da novembre 2025.
    const months = Array.from({ length: 12 }, (_, i) => {
      const date = new Date(2025, 10 + i, 1);
      return {
        month: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`,
        value: 100 + i,
      };
    });
    answer = () => of(overview({ months }));
    const fixture = TestBed.createComponent(CategoryPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const chartCard = () =>
      Array.from(page.querySelectorAll('section.card')).find((c) =>
        c.querySelector('app-bar-chart'),
      ) as HTMLElement;
    const bars = () => chartCard().querySelectorAll('app-bar-chart path.bar').length;
    const button = (label: string) =>
      Array.from(chartCard().querySelectorAll('.segmented button')).find(
        (b) => b.textContent?.trim() === label,
      ) as HTMLButtonElement;

    expect(chartCard().querySelector('h2')?.textContent?.trim()).toBe('Ultimi 12 mesi');
    expect(bars()).toBe(12);

    button("Quest'anno").click();
    await fixture.whenStable();
    expect(bars()).toBe(10); // da gennaio a ottobre 2026
    expect(button("Quest'anno").getAttribute('aria-pressed')).toBe('true');
    expect(chartCard().querySelector('app-bar-chart svg')?.getAttribute('aria-label')).toContain(
      'da gennaio',
    );
  });

  it('each merchant of the month leads to its movements in this category', async () => {
    const page = await render();
    const link = Array.from(page.querySelectorAll<HTMLAnchorElement>('ul.rows a')).find((a) =>
      a.textContent?.includes('ESSELUNGA'),
    );
    const o = overview();
    expect(link?.getAttribute('href')).toBe(
      `/movimenti?categoryId=${o.categoryId}&from=2026-10-01&to=2026-10-25&text=esselunga`,
    );
  });

  it('touching a bar shows that month, its figure and where; the chart stays as it is', async () => {
    // Come l'API: i 12 mesi fino a ottobre 2026. Ottobre 360 €, settembre 120 € (due esercenti diversi).
    const months = Array.from({ length: 12 }, (_, i) => {
      const date = new Date(2025, 10 + i, 1);
      return {
        month: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`,
        value: i === 10 ? 120 : i === 11 ? 360 : 50,
      };
    });
    const october = overview({ months, amount: 360 });
    const september = overview({
      month: '2026-09',
      day: '2026-09-30',
      amount: 120,
      months: months.map((m) => ({ ...m })), // l'API dà i 12 mesi fino a settembre: qui non contano
      merchants: [
        {
          key: 'ZIA PINA',
          categoryId: null,
          name: 'ZIA PINA',
          amount: 120,
          movements: 1,
          usual: null,
        },
      ],
    });
    answer = () => of(requested.at(-1)?.[1] === '2026-09' ? september : october);
    const fixture = TestBed.createComponent(CategoryPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const text = () => page.textContent?.replace(/\s+/g, ' ') ?? '';
    const bars = () => Array.from(page.querySelectorAll('app-bar-chart path.bar'));

    expect(page.querySelector('.bar-pick')?.textContent).toMatch(/ottobre 2026: 360,00\s€/);
    expect(bars()[11].classList.contains('on')).toBe(true);

    (page.querySelectorAll('app-bar-chart rect.hit')[10] as SVGElement).dispatchEvent(
      new MouseEvent('click'),
    );
    await fixture.whenStable();

    expect(requested.at(-1)).toEqual([1, '2026-09']);
    expect(page.querySelector('.bar-pick')?.textContent).toMatch(/settembre 2026: 120,00\s€/);
    expect(page.querySelector('.big-figure')?.textContent).toMatch(/120\s€/);
    expect(text()).toContain('Dove, a settembre');
    expect(text()).toContain('ZIA PINA');
    // Il grafico è lo stesso: 12 barre fino a ottobre, accesa quella di settembre.
    expect(bars().length).toBe(12);
    expect(bars()[10].classList.contains('on')).toBe(true);
    expect(bars()[11].classList.contains('on')).toBe(false);
  });

  it('opened on an earlier month, the chart still reaches the current month', async () => {
    const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const today = new Date();
    const current = iso(today);
    const previous = iso(new Date(today.getFullYear(), today.getMonth() - 1, 1));
    const months = Array.from({ length: 12 }, (_, i) => ({
      month: iso(new Date(today.getFullYear(), today.getMonth() - 11 + i, 1)),
      value: 100 + i,
    }));
    routeQuery = { mese: previous }; // per esempio, dal Riepilogo del mese scorso
    answer = () =>
      of(
        requested.at(-1)?.[1] === previous
          ? overview({ month: previous, day: `${previous}-28`, amount: 110, months })
          : overview({ month: current, day: `${current}-02`, amount: 111, months }),
      );
    const fixture = TestBed.createComponent(CategoryPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const bars = Array.from(page.querySelectorAll('app-bar-chart path.bar'));

    // Due richieste: il grafico (fino a oggi) e il mese aperto.
    expect(requested).toEqual([
      [1, null],
      [1, previous],
    ]);
    expect(bars.length).toBe(12);
    expect(bars[10].classList.contains('on')).toBe(true); // il mese aperto, non l'ultimo
    expect(page.querySelector('.big-figure')?.textContent).toMatch(/110\s€/);
  });

  it('says when the category does not exist', async () => {
    answer = () => throwError(() => ({ status: 404 }));
    const page = await render();
    expect(page.querySelector('.error')?.textContent).toContain('Questa categoria non esiste più.');
  });
});
