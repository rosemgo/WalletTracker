import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { InvestmentPeriod, Investments, Transaction } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { PrivacyStore } from '../privacy/privacy-store';
import { InvestmentsCard } from './investments-card';
import { InvestmentsPage } from './investments-page';

/** Un anno inventato: un PAC da 200 € al mese, 1.000 € a marzo, una cedola e un dividendo. */
function investments(period: InvestmentPeriod): Investments {
  const months = Array.from({ length: 12 }, (_, i) => {
    const date = new Date(2025, 10 + i, 1);
    const month = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    return {
      month,
      contributed: i === 4 ? 1200 : 200,
      cumulative: 200 * (i + 2) + (i >= 4 ? 1000 : 0),
      income: i === 5 ? 30 : i === 7 ? 12.5 : 0,
      taxes: i === 8 ? -2 : 0,
    };
  });
  return {
    currency: 'EUR',
    currencies: ['EUR'],
    period,
    from: '2025-11',
    to: '2026-10',
    contributedTotal: 3600,
    contributedThisYear: 3000,
    contributedThisMonth: 200,
    incomeThisYear: 42.5,
    contributedInPeriod: 3400,
    incomeInPeriod: 42.5,
    taxesInPeriod: -2,
    months,
    incomeByType: [
      { name: 'Cedole', amount: 30, movements: 1 },
      { name: 'Dividendi', amount: 12.5, movements: 1 },
    ],
    incomeBySource: [{ name: 'CED.SU BTP ITALIA', amount: 30, movements: 1 }],
    recentContributions: [
      {
        id: 41,
        bookingDate: '2026-10-05',
        amount: -200,
        currency: 'EUR',
        description: 'BONIFICO PAC',
        type: 'INVESTMENT_DEPOSIT',
        note: null,
      } as unknown as Transaction,
    ],
  };
}

const NOTHING: Investments = {
  ...investments('YEAR'),
  contributedTotal: 0,
  contributedThisYear: 0,
  contributedThisMonth: 0,
  incomeThisYear: 0,
  months: investments('YEAR').months.map((m) => ({
    ...m,
    contributed: 0,
    cumulative: 0,
    income: 0,
    taxes: 0,
  })),
  incomeByType: [],
  incomeBySource: [],
  recentContributions: [],
};

describe('Investments', () => {
  const requests: InvestmentPeriod[] = [];
  let answer: (period: InvestmentPeriod) => Investments;

  beforeEach(() => {
    registerLocaleData(localeIt);
    localStorage.clear();
    requests.length = 0;
    answer = investments;
    const fakeApi: Partial<WalletApi> = {
      investments: (period: InvestmentPeriod) => {
        requests.push(period);
        return of(answer(period));
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  afterEach(() => localStorage.clear());

  async function page() {
    const fixture = TestBed.createComponent(InvestmentsPage);
    await fixture.whenStable();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  it('shows the money invested with its line, and the income with its bars and lists', async () => {
    const { page: p } = await page();
    const text = p.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(requests).toEqual(['YEAR']);
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/3\.600\s€/);
    expect(text).toMatch(/3\.000\s€ quest'anno · 200\s€ questo mese/);
    expect(text).toContain('non quanto valgono oggi');
    expect(p.querySelector('app-trend-chart path.month')).not.toBeNull();
    // Una barra per mese; disegnate (tracciato non vuoto) solo le due dei mesi con una rendita.
    const bars = Array.from(p.querySelectorAll('app-bar-chart path.bar'));
    expect(bars.length).toBe(12);
    expect(bars.filter((b) => b.getAttribute('d') !== '').length).toBe(2);
    expect(text).toMatch(/Cedole\s*1 movimento\s*30,00\s€/);
    // In evidenza la barra dell'ultima rendita (giugno 2026: il dividendo), non il mese in corso.
    expect(bars[7].classList.contains('on')).toBe(true);
    expect(text).toContain('Ced.su BTP Italia'); // leggibile, non tutto maiuscolo
    expect(text).toMatch(/Tasse sugli investimenti nello stesso periodo: -2\s€/);
    // L'ultimo versamento porta al suo dettaglio.
    expect(p.querySelector('a.movement')?.getAttribute('href')).toBe('/movimenti/41');
  });

  it('the period buttons ask again; scrolling the line shows that month', async () => {
    const { fixture, page: p } = await page();
    const button = (label: string) =>
      Array.from(p.querySelectorAll('.segmented button')).find(
        (b) => b.textContent?.trim() === label,
      ) as HTMLButtonElement;

    button('Tutto').click();
    await fixture.whenStable();
    expect(requests).toEqual(['YEAR', 'ALL']);
    expect(button('Tutto').getAttribute('aria-pressed')).toBe('true');
    expect(p.textContent).toContain('da sempre');

    const slider = p.querySelector('app-trend-chart [role="slider"]') as HTMLElement;
    slider.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home' }));
    await fixture.whenStable();
    expect(p.textContent).toContain('Versato fino a novembre 2025');
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/400\s€/);
  });

  it('with nothing invested explains how to mark the transfers', async () => {
    answer = () => NOTHING;
    const { page: p } = await page();

    expect(p.textContent).toContain('Ancora nessun investimento');
    expect(p.textContent).toContain('Versamento investimenti');
    expect(p.querySelector('app-trend-chart')).toBeNull();
  });

  it('the discreet mode hides the figures', async () => {
    TestBed.inject(PrivacyStore).toggle();
    const { page: p } = await page();

    expect(p.querySelector('.big-figure')?.textContent).toMatch(/^•••\s€$/);
    expect(p.textContent).not.toMatch(/\d\s€/);
  });

  it('the card of the Riepilogo shows the year and leads to the page, and hides when there is nothing', async () => {
    const fixture = TestBed.createComponent(InvestmentsCard);
    await fixture.whenStable();
    const card = (fixture.nativeElement as HTMLElement).querySelector('a.investments-card');
    expect(card?.getAttribute('href')).toBe('/investimenti');
    expect(card?.textContent?.replace(/\s+/g, ' ')).toMatch(/Versato quest'anno\s*3\.000\s€/);
    expect(card?.textContent?.replace(/\s+/g, ' ')).toMatch(/Rendite quest'anno\s*43\s€/);
    expect(card?.querySelector('path.spark-line')?.getAttribute('d')).toMatch(/^M0\.0 /);

    answer = () => NOTHING;
    const empty = TestBed.createComponent(InvestmentsCard);
    await empty.whenStable();
    expect((empty.nativeElement as HTMLElement).querySelector('a')).toBeNull();
  });
});
