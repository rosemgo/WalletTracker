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
    expect(page.querySelector('path.bar.on')).toBe(page.querySelectorAll('path.bar')[11]);
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
    expect(requested).toEqual([[1, '2026-09']]);
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

  it('says when the category does not exist', async () => {
    answer = () => throwError(() => ({ status: 404 }));
    const page = await render();
    expect(page.querySelector('.error')?.textContent).toContain('Questa categoria non esiste più.');
  });
});
