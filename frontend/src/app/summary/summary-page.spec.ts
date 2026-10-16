import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { MonthSummary, Summary } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { firstMonth, SummaryPage } from './summary-page';

function month(month: string, income: number, expenses: number, toReview = 0): MonthSummary {
  return { month, income, expenses, savings: income - expenses, investmentNet: 0, toReview };
}

const SUMMARY: Summary = {
  from: '2026-09',
  to: '2026-10',
  currencies: [
    {
      currency: 'EUR',
      months: [month('2026-09', 2000, 1500), month('2026-10', 2000, 2500, 3)],
      total: { income: 4000, expenses: 4000, savings: 0, investmentNet: 37, toReview: 3 },
      expensesByCategory: [
        { category: 'Casa', amount: 2000, movements: 2 },
        { category: null, amount: 1000, movements: 9 },
        { category: 'Spesa', amount: 1000, movements: 12 },
      ],
    },
  ],
};

describe('SummaryPage', () => {
  const requestedFrom: string[] = [];
  let response: Summary;

  beforeEach(() => {
    registerLocaleData(localeIt);
    requestedFrom.length = 0;
    response = SUMMARY;
    const fakeApi: Partial<WalletApi> = {
      monthlySummary: (from: string) => {
        requestedFrom.push(from);
        return of(response);
      },
    };
    TestBed.configureTestingModule({
      imports: [SummaryPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' }, // come in app.config.ts: importi e mesi in italiano
      ],
    });
  });

  it('shows the totals, the warning for movements to review and one row per month', async () => {
    const fixture = TestBed.createComponent(SummaryPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    const tiles = Array.from(page.querySelectorAll('.tile-value')).map((t) =>
      t.textContent?.trim(),
    );
    // Tra importo e "€" Angular mette uno spazio che non va mai a capo (\u00a0, "non-breaking space").
    expect(tiles).toEqual(['4.000,00\u00a0€', '4.000,00\u00a0€', '0,00\u00a0€', '37,00\u00a0€']);
    expect(page.querySelector('.warning')?.textContent).toContain('3 movimenti sono da verificare');
    expect(page.querySelectorAll('tbody tr').length).toBe(2);
    expect(page.querySelector('tbody tr')?.textContent).toContain('settembre 2026');
    // Ottobre: spese più alte delle entrate, risparmio in rosso.
    expect(page.querySelectorAll('tbody tr')[1].querySelector('.negative')?.textContent).toContain(
      '-500,00',
    );
  });

  it('asks the backend for the chosen period', async () => {
    const fixture = TestBed.createComponent(SummaryPage);
    await fixture.whenStable();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'select',
    ) as HTMLSelectElement;

    select.value = '6';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(requestedFrom).toEqual([firstMonth(new Date(), 12), firstMonth(new Date(), 6)]);
  });

  it('suggests linking a bank when there are no movements', async () => {
    response = { from: '2026-09', to: '2026-10', currencies: [] };
    const fixture = TestBed.createComponent(SummaryPage);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Nessun movimento in questo periodo',
    );
  });

  it('computes the first month of a period, across the year boundary', () => {
    expect(firstMonth(new Date(2026, 9, 16), 12)).toBe('2025-11'); // ottobre 2026 → da novembre 2025
    expect(firstMonth(new Date(2026, 0, 31), 1)).toBe('2026-01');
    expect(firstMonth(new Date(2026, 1, 10), 3)).toBe('2025-12');
  });

  it('shows the expenses by category, with their share of all expenses', async () => {
    const fixture = TestBed.createComponent(SummaryPage);
    await fixture.whenStable();
    const rows = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.category-row'),
    );

    expect(rows.map((r) => r.querySelector('.category-name')?.textContent?.trim())).toEqual([
      'Casa',
      'Senza categoria',
      'Spesa',
    ]);
    expect(rows[0].textContent).toContain('50%');
    expect(rows[1].classList).toContain('muted');
    // La barra più lunga è la categoria più alta; le altre in proporzione.
    expect((rows[0].querySelector('.category-bar') as HTMLElement).style.width).toBe('100%');
    expect((rows[2].querySelector('.category-bar') as HTMLElement).style.width).toBe('50%');
  });

  it('folds the smallest categories into one row', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({
      category: `Categoria ${i + 1}`,
      amount: 100 - i,
      movements: 1,
    }));
    response = {
      ...SUMMARY,
      currencies: [{ ...SUMMARY.currencies[0], expensesByCategory: many }],
    };
    const fixture = TestBed.createComponent(SummaryPage);
    await fixture.whenStable();
    const names = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.category-name'),
    ).map((n) => n.textContent?.trim());

    expect(names.length).toBe(9); // le prime 8 più "Altre categorie"
    expect(names[8]).toBe('Altre categorie (2)');
  });
});
