import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import {
  ExploreGroup,
  ExploreGroups,
  ExploreSeries,
  Transaction,
  TransactionFilter,
} from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { ExplorePage, groupNote } from './explore-page';

function group(
  name: string | null,
  amount: number,
  usual: number | null,
  categoryId: number | null = 1,
): ExploreGroup {
  return { key: String(categoryId), categoryId, name, amount, movements: 4, usual };
}

/** Un anno inventato: 12 mesi, ottobre (l'ultimo) 1.284 €, di solito 1.550 € al mese. */
function series(query: {
  metric: string;
  period: string;
  categoryId: number | null;
}): ExploreSeries {
  const months = Array.from({ length: 12 }, (_, i) => {
    const date = new Date(2025, 10 + i, 1);
    const month = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    return { from: `${month}-01`, to: `${month}-28`, value: i === 11 ? 1284 : 1500, future: false };
  });
  return {
    metric: query.metric as ExploreSeries['metric'],
    period: query.period as ExploreSeries['period'],
    categoryId: query.categoryId,
    currency: 'EUR',
    currencies: ['EUR'],
    unit: 'MONTH',
    bars:
      query.metric === 'NET'
        ? months.map((m, i) => ({ ...m, value: i === 3 ? -200 : 600 }))
        : months,
    averagePerUnit: 1550,
    usualMonths: 6,
  };
}

describe('ExplorePage', () => {
  const seriesQueries: Record<string, unknown>[] = [];
  const groupQueries: Record<string, unknown>[] = [];
  const transactionQueries: TransactionFilter[] = [];
  let routeParams: Record<string, string>;
  let navigations: Record<string, unknown>[];

  beforeEach(() => {
    registerLocaleData(localeIt);
    seriesQueries.length = 0;
    groupQueries.length = 0;
    transactionQueries.length = 0;
    routeParams = {};
    navigations = [];
    const fakeApi: Partial<WalletApi> = {
      exploreSeries: (query) => {
        seriesQueries.push(query);
        return of(series(query));
      },
      exploreGroups: (query) => {
        groupQueries.push(query);
        const answer: ExploreGroups = {
          metric: query.metric,
          by: query.by,
          from: query.from,
          to: query.to,
          currency: 'EUR',
          total: 1284,
          groups:
            query.metric === 'NET'
              ? []
              : [
                  group('Spesa alimentare', 412, 380, 1),
                  group('Bar e ristoranti', 196, 150, 2),
                  group(null, 30, null, null),
                ],
          income: query.metric === 'NET' ? [group('Stipendio e pensione', 2100, null, 9)] : [],
          expenses: query.metric === 'NET' ? [group('Spesa alimentare', 412, null, 1)] : [],
        };
        return of(answer);
      },
      transactions: (filter: TransactionFilter) => {
        transactionQueries.push(filter);
        const item = {
          id: 7,
          bookingDate: filter.from,
          amount: -26,
          currency: 'EUR',
          description: 'PIZZERIA DA GINO',
          type: 'EXPENSE',
          note: null,
        } as unknown as Transaction;
        return of({ items: [item], page: 0, size: 200, totalItems: 1, totalPages: 1 });
      },
    };
    TestBed.configureTestingModule({
      imports: [ExplorePage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
        {
          provide: ActivatedRoute,
          useFactory: () => ({ snapshot: { queryParamMap: convertToParamMap(routeParams) } }),
        },
      ],
    });
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockImplementation((_commands, extras) => {
      navigations.push(extras?.queryParams ?? {});
      return Promise.resolve(true);
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(ExplorePage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.replace(/\s+/g, ' ').trim() === text,
      )!;
    return { fixture, page, button };
  }

  it('starts with the expenses of the year, the last month chosen, the usual and the categories', async () => {
    const { page } = await render();
    const text = page.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(seriesQueries[0]).toMatchObject({
      metric: 'EXPENSES',
      period: 'YEAR',
      categoryId: null,
    });
    expect(groupQueries[0]).toMatchObject({ by: 'CATEGORY', from: '2026-10-01', to: '2026-10-28' });
    expect(text).toContain('Spese · ottobre 2026');
    expect(page.querySelector('.big-figure')?.textContent).toMatch(/1\.284\s€/);
    expect(text).toMatch(/Di solito 1\.550\s€ al mese/);
    expect(page.querySelectorAll('path.bar.on').length).toBe(1);
    const rows = Array.from(page.querySelectorAll('ul.groups li')).map((li) =>
      li.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(rows[0]).toMatch(/Spesa alimentare\s*4 movimenti · \+32\s€ sul solito\s*412\s€\s*65%/);
    expect(rows[2]).toContain('Senza categoria');
    // Le scelte nell'indirizzo, senza un passo nella cronologia.
    expect(navigations.at(-1)).toMatchObject({ cosa: 'spese', periodo: 'anno', per: 'categoria' });
  });

  it('a bar shows its period; the metric, the period and the grouping change the view', async () => {
    const { fixture, page, button } = await render();

    (page.querySelectorAll('rect.hit')[3] as SVGRectElement).dispatchEvent(new MouseEvent('click'));
    await fixture.whenStable();
    expect(groupQueries.at(-1)).toMatchObject({ from: '2026-02-01' });
    expect(page.textContent).toContain('febbraio 2026');

    button('Esercente').click();
    await fixture.whenStable();
    expect(groupQueries.at(-1)).toMatchObject({ by: 'MERCHANT', from: '2026-02-01' });

    button('6 mesi').click();
    await fixture.whenStable();
    expect(seriesQueries.at(-1)).toMatchObject({ period: 'SIX_MONTHS' });
    expect(groupQueries.at(-1)).toMatchObject({ from: '2026-10-01' }); // nel periodo nuovo, di nuovo l'ultima barra

    button('Entrate').click();
    await fixture.whenStable();
    expect(seriesQueries.at(-1)).toMatchObject({ metric: 'INCOME' });
  });

  it('the net shows income and expenses in two lists, with bars up and down', async () => {
    const { fixture, page, button } = await render();
    button('Netto').click();
    await fixture.whenStable();

    expect(page.querySelectorAll('path.bar.neg').length).toBe(1);
    expect(page.querySelector('line.average')).toBeNull();
    expect(
      Array.from(page.querySelectorAll('section.card h2')).map((h) => h.textContent?.trim()),
    ).toEqual(['Entrate', 'Uscite']);
    expect(page.textContent).toMatch(/Entrate 2\.100\s€ · Uscite 412\s€/);
    expect(page.querySelector('.big-figure')?.textContent).toMatch(/\+600\s€/);
  });

  it('a category shows only itself, with its merchants and its movements, and can be cleared', async () => {
    const { fixture, page, button } = await render();
    (page.querySelector('ul.groups button.group-row') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(seriesQueries.at(-1)).toMatchObject({ categoryId: 1 });
    expect(groupQueries.at(-1)).toMatchObject({ categoryId: 1, by: 'MERCHANT' });
    expect(transactionQueries.at(-1)).toMatchObject({
      categoryId: 1,
      from: '2026-10-01',
      to: '2026-10-28',
    });
    expect(page.textContent).toContain('Spese · Spesa alimentare · ottobre 2026');
    expect(page.textContent).toContain('Pizzeria da Gino');
    expect(page.querySelector('.filter-chip a')?.getAttribute('href')).toBe(
      '/analisi/categoria/1?mese=2026-10',
    );
    expect(button('Categoria')).toBeUndefined(); // dentro una categoria non si raggruppa per categoria

    (page.querySelector('.filter-chip button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(seriesQueries.at(-1)).toMatchObject({ categoryId: null });
    expect(groupQueries.at(-1)).toMatchObject({ by: 'CATEGORY' });
  });

  it('a category in the address is grouped by merchant, as when it is chosen', async () => {
    routeParams = { categoria: '1', nome: 'Spesa alimentare', per: 'categoria' };
    await render();
    expect(groupQueries[0]).toMatchObject({ categoryId: 1, by: 'MERCHANT' });
  });

  it('the view comes back from the address', async () => {
    routeParams = {
      cosa: 'entrate',
      periodo: 'mese',
      per: 'conto',
      categoria: '9',
      nome: 'Stipendio e pensione',
      barra: '2',
    };
    const { page } = await render();

    expect(seriesQueries[0]).toMatchObject({ metric: 'INCOME', period: 'MONTH', categoryId: 9 });
    expect(groupQueries[0]).toMatchObject({ by: 'ACCOUNT', from: '2026-01-01' });
    expect(page.querySelector('.filter-chip button')?.textContent).toContain(
      'Stipendio e pensione',
    );
  });

  it('puts the difference from the usual into words', () => {
    const euros = (v: number) => `${v} €`;
    expect(groupNote(group('X', 412, 380), euros)).toBe('4 movimenti · +32 € sul solito');
    expect(groupNote(group('X', 150, 152), euros)).toBe('4 movimenti · come al solito');
    expect(groupNote(group('X', 100, 156), euros)).toBe('4 movimenti · −56 € sul solito');
    expect(groupNote({ ...group('X', 10, null), movements: 1 }, euros)).toBe('1 movimento');
  });

  it('a period that cannot be loaded removes the old list and says why', async () => {
    const { fixture, page } = await render();
    const api = TestBed.inject(WalletApi);
    vi.spyOn(api, 'exploreGroups').mockReturnValue(
      throwError(() => ({
        status: 400,
        error: { detail: 'Il giorno 2026-11-01 non è ancora arrivato.' },
      })),
    );

    (page.querySelectorAll('rect.hit')[2] as SVGRectElement).dispatchEvent(new MouseEvent('click'));
    await fixture.whenStable();

    expect(page.querySelector('.error')?.textContent).toContain('non è ancora arrivato');
    expect(page.textContent).not.toContain('Spesa alimentare');
  });
});
