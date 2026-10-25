import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { Overview, OverviewCategory } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import {
  OverviewPage,
  addMonths,
  categoryNote,
  comparisonSentence,
  monthOf,
} from './overview-page';

/** Un Riepilogo inventato del mese in corso: 1.284 € spesi, di solito 1.410 € allo stesso giorno. */
function overview(changes: Partial<Overview> = {}): Overview {
  const month = monthOf(new Date());
  return {
    month,
    day: `${month}-25`,
    daysInMonth: 31,
    currency: 'EUR',
    currencies: ['EUR'],
    spent: 1284,
    income: 2113,
    spentByDay: Array.from({ length: 25 }, (_, i) => Math.round(((i + 1) / 25) * 1284)),
    usualMonths: 6,
    usualSoFar: 1410,
    usualMonth: 1720,
    usualByDay: Array.from({ length: 31 }, (_, i) => Math.round(((i + 1) / 31) * 1720)),
    categories: [
      category('Spesa alimentare', 412, 380),
      category('Spese fisse e utenze', 268, 270),
      category('Bar e ristoranti', 196, 150),
      category('Trasporti e auto', 154, 210),
      category('Shopping', 98, 120),
      category(null, 61, 40),
    ],
    upcoming: [{ subscriptionId: 1, name: 'Netflix', date: '2026-11-03', amount: 12.99 }],
    toReview: 7,
    suggestions: 1,
    ...changes,
  };
}

let nextId = 1;
function category(name: string | null, amount: number, usual: number | null): OverviewCategory {
  return {
    categoryId: name === null ? null : nextId++,
    name,
    amount,
    movements: 4,
    usualSoFar: usual,
    usualMonth: usual,
  };
}

const euros = (v: number) => `${Math.round(v)} €`;

describe('OverviewPage', () => {
  const requests: [string | null, string | null][] = [];
  let respond: (month: string | null) => Observable<Overview>;

  beforeEach(() => {
    registerLocaleData(localeIt);
    requests.length = 0;
    respond = () => of(overview());
    const fakeApi: Partial<WalletApi> = {
      alerts: () => of([]),
      overview: (month: string | null, currency: string | null) => {
        requests.push([month, currency]);
        return respond(month);
      },
    };
    TestBed.configureTestingModule({
      imports: [OverviewPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(OverviewPage);
    await fixture.whenStable();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  it('shows one big figure with the sentence about the usual, the chart and the income', async () => {
    const { page } = await render();
    const text = page.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(page.querySelector('.big-figure')?.textContent).toMatch(/1\.284\s€/);
    expect(text).toContain('126 € in meno del solito a questo punto del mese.');
    expect(text).toContain('Un mese tipico (media di 6 mesi)');
    expect(text).toMatch(/Entrate finora: 2\.113\s€/);
    const chart = page.querySelector('svg.month-chart') as SVGElement;
    expect(chart.getAttribute('role')).toBe('img');
    expect(chart.getAttribute('aria-label')).toMatch(/contro 1\.410\s€ di un mese tipico/);
    // La linea del mese sta in uno strato sopra (docs/41, parte G): la cerchiamo in tutto il grafico.
    expect(page.querySelector('app-month-chart path.month')).not.toBeNull();
    expect(chart.querySelector('path.usual')).not.toBeNull();
  });

  it('shows the top five categories with the difference from the usual, and a link to Analisi', async () => {
    const { page } = await render();
    const rows = Array.from(page.querySelectorAll('ul.bars li')).map((li) =>
      li.textContent?.replace(/\s+/g, ' ').trim(),
    );

    expect(rows.length).toBe(5); // la sesta (senza categoria) è in Analisi
    expect(rows[0]).toMatch(/^Spesa alimentare\s*412\s€\s*\+32\s€ sul solito$/);
    expect(rows[1]).toContain('come al solito');
    expect(rows[3]).toMatch(/−56\s€ sul solito/);
    expect(page.querySelector('.card-head a')?.getAttribute('href')).toBe('/analisi');
    // Una categoria apre la sua pagina; le spese senza categoria no.
    const links = Array.from(page.querySelectorAll('ul.bars a.bar-link')).map((a) =>
      a.getAttribute('href'),
    );
    expect(links[0]).toMatch(/^\/analisi\/categoria\/\d+$/);
    expect(links.length).toBe(5);
    // La barra più lunga è la prima.
    const widths = Array.from(page.querySelectorAll<HTMLElement>('.bar-line .fill')).map(
      (bar) => bar.style.width,
    );
    expect(widths[0]).toBe('100%');
  });

  it('shows what is coming and what to fix, with links to the movements', async () => {
    const { page } = await render();
    const text = page.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(text).toContain('In arrivo nei prossimi 7 giorni');
    expect(text).toMatch(/Netflix\s*martedì 3 novembre\s*12,99\s€/);
    const links = Array.from(page.querySelectorAll<HTMLAnchorElement>('.todo a'));
    expect(links.map((a) => a.getAttribute('aria-label'))).toEqual([
      '7 movimenti da verificare',
      '1 categoria suggerita',
    ]);
    expect(links[0].getAttribute('href')).toBe('/movimenti?type=TO_REVIEW');
  });

  it('goes back to past months and not beyond the current one', async () => {
    respond = (month) => of(overview({ month: month ?? monthOf(new Date()), upcoming: [] }));
    const { fixture, page } = await render();
    const [previous, next] = Array.from(page.querySelectorAll<HTMLButtonElement>('button.icon'));
    const current = monthOf(new Date());

    expect(next.disabled).toBe(true);
    previous.click();
    await fixture.whenStable();

    expect(requests.map((r) => r[0])).toEqual([current, addMonths(current, -1)]);
    expect(next.disabled).toBe(false);
    expect(page.textContent).toContain('Tutto il mese');
    expect(page.textContent).toContain('Speso nel mese');
    next.click();
    await fixture.whenStable();
    expect(requests.at(-1)?.[0]).toBe(current);
  });

  it('lets choose the currency when there is more than one', async () => {
    respond = () => of(overview({ currencies: ['EUR', 'USD'] }));
    const { fixture, page } = await render();
    const select = page.querySelector('.currency-choice select') as HTMLSelectElement;

    select.value = 'USD';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(requests.at(-1)).toEqual([monthOf(new Date()), 'USD']);
  });

  it('explains an error of the backend', async () => {
    respond = () => throwError(() => ({ status: 0 }));
    const { page } = await render();

    expect(page.querySelector('.error')?.textContent).toContain('il backend è avviato?');
  });

  it('puts the comparison into words', () => {
    expect(comparisonSentence(overview({ spent: 1500, usualSoFar: 1410 }), true, euros)).toBe(
      '90 € più del solito a questo punto del mese.',
    );
    expect(comparisonSentence(overview({ spent: 1412, usualSoFar: 1410 }), true, euros)).toBe(
      'In linea con il solito a questo punto del mese.',
    );
    // Un mese passato si confronta intero.
    expect(comparisonSentence(overview({ spent: 1300, usualSoFar: 1410 }), false, euros)).toBe(
      '110 € in meno del solito.',
    );
    expect(comparisonSentence(overview({ usualSoFar: null }), true, euros)).toContain(
      'dopo il primo mese completo',
    );
  });

  it('a category without usual says how many movements it has', () => {
    expect(categoryNote({ ...category('Shopping', 98, null), movements: 1 }, euros)).toBe(
      '1 movimento',
    );
    expect(categoryNote(category('Shopping', 98, null), euros)).toBe('4 movimenti');
  });

  it('counts months across the year', () => {
    expect(addMonths('2026-01', -1)).toBe('2025-12');
    expect(addMonths('2026-12', 1)).toBe('2027-01');
    expect(monthOf(new Date(2026, 9, 25))).toBe('2026-10');
  });
});
