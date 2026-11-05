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

/** Un movimento inventato per gli elenchi. */
function movement(id: number, date: string, amount: number, description: string, type: string) {
  return {
    id,
    bookingDate: date,
    amount,
    currency: 'EUR',
    description,
    type,
    note: null,
  } as unknown as Transaction;
}

/**
 * Un anno inventato, da novembre 2025: ogni mese 200 € di bonifico verso il wallet e 200 € di acquisto, a marzo 1.000 €
 * in più (bonifico e acquisto), ad agosto una vendita da 300 €, a settembre 100 € tornati indietro; una cedola e un
 * dividendo.
 */
function investments(period: InvestmentPeriod): Investments {
  let cumulative = 400; // l'investito di prima del periodo
  const months = Array.from({ length: 12 }, (_, i) => {
    const date = new Date(2025, 10 + i, 1);
    const month = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    const bought = i === 4 ? 1200 : 200;
    const sold = i === 9 ? 300 : 0;
    cumulative += bought - sold;
    return {
      month,
      bought,
      sold,
      investedCumulative: cumulative,
      transferred: i === 4 ? 1200 : i === 10 ? -100 : 200,
      income: i === 5 ? 30 : i === 7 ? 12.5 : 0,
      withholding: i === 5 ? -3.9 : 0,
      otherTaxes: i === 8 ? -2 : 0,
    };
  });
  // 10 bonifici, per provare "Mostra tutti" (se ne vedono 8).
  const transfers = Array.from({ length: 10 }, (_, i) =>
    movement(
      100 + i,
      `2026-${String(10 - i).padStart(2, '0')}-05`,
      -200,
      'BONIFICO A WALLET',
      'INVESTMENT_DEPOSIT',
    ),
  );
  return {
    currency: 'EUR',
    currencies: ['EUR'],
    period,
    from: '2025-11',
    to: '2026-10',
    investedTotal: 3700,
    investedThisYear: 2900,
    investedThisMonth: 200,
    transferredTotal: 4000,
    incomeThisYear: 42.5,
    boughtInPeriod: 3600,
    soldInPeriod: 300,
    transferredInPeriod: 3300,
    incomeInPeriod: 42.5,
    withholdingInPeriod: -3.9,
    otherTaxesInPeriod: -2,
    months,
    incomeByType: [
      { name: 'Cedole', amount: 30, movements: 1 },
      { name: 'Dividendi', amount: 12.5, movements: 1 },
    ],
    incomeBySource: [{ name: 'CED.SU BTP ITALIA', amount: 30, movements: 1 }],
    otherTaxesByType: [{ name: 'Imposta di bollo', amount: -2, movements: 1 }],
    trades: [
      movement(41, '2026-10-06', -200, 'NUOVO VERSAMENTO ROBO ADVISOR', 'SECURITIES_BUY'),
      movement(42, '2026-08-10', 300, 'SELL TRADE ETF MONDO', 'SECURITIES_SELL'),
    ],
    transfers,
    onlyTransfers: [],
    cash: [],
  };
}

const NOTHING: Investments = {
  ...investments('YEAR'),
  investedTotal: 0,
  investedThisYear: 0,
  investedThisMonth: 0,
  transferredTotal: 0,
  incomeThisYear: 0,
  months: investments('YEAR').months.map((m) => ({
    ...m,
    bought: 0,
    sold: 0,
    investedCumulative: 0,
    transferred: 0,
    income: 0,
    withholding: 0,
    otherTaxes: 0,
  })),
  incomeByType: [],
  incomeBySource: [],
  otherTaxesByType: [],
  trades: [],
  transfers: [],
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
      accounts: () => of([]), // per i loghi nel cerchio dei movimenti (AccountsStore)
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

  it('shows the invested money with its line, purchases and sales apart, the transfers and the income', async () => {
    const { page: p } = await page();
    const text = p.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(requests).toEqual(['YEAR']);
    // L'investito in titoli: la cifra grande, la linea, acquistato e rientrato separati, la frase vera.
    expect(text).toContain('Investito in titoli');
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/3\.700\s€/);
    expect(text).toMatch(/2\.900\s€ quest'anno · 200\s€ questo mese/);
    expect(p.querySelector('app-trend-chart path.month')).not.toBeNull();
    expect(text).toMatch(/Acquistato · ultimi 12 mesi\s*3\.600\s€/);
    expect(text).toMatch(/Rientrato dalle vendite\s*300\s€/);
    expect(text).toContain('Non è quanto valgono oggi');
    expect(text).not.toContain('Versato');

    // I bonifici: la loro cifra e le barre, una per mese, in su e in giù (settembre: 100 € tornati indietro).
    const transfers = p.querySelector('.transfers-card') as HTMLElement;
    expect(transfers.textContent).toMatch(
      /Bonifici verso i conti di investimento · ultimi 12 mesi\s*3\.300\s€/,
    );
    const transferBars = Array.from(transfers.querySelectorAll('app-bar-chart path.bar'));
    expect(transferBars.length).toBe(12);
    expect(transferBars.filter((b) => b.classList.contains('neg')).length).toBe(1);
    expect(transfers.textContent).toContain('Non dicono quanti ne hai investiti');

    // Le rendite: solo le due barre dei mesi con una rendita; in evidenza l'ultima (giugno 2026, il dividendo).
    const incomeCard = Array.from(p.querySelectorAll('section.card')).find((c) =>
      c.textContent?.includes('Cedole, dividendi e interessi'),
    ) as HTMLElement;
    const bars = Array.from(incomeCard.querySelectorAll('app-bar-chart path.bar'));
    expect(bars.length).toBe(12);
    expect(bars.filter((b) => b.getAttribute('d') !== '').length).toBe(2);
    expect(bars[7].classList.contains('on')).toBe(true);
    expect(text).toMatch(/Cedole\s*1 movimento\s*30,00\s€/);
    expect(text).toContain('Ced.su BTP Italia'); // leggibile, non tutto maiuscolo
    // Solo il netto (42,50 − 3,90): niente lordo e ritenute, che con Trade Republic non si
    // conoscono. Il bollo a parte, non tolto dal netto.
    expect(incomeCard.querySelector('.mid-figure')?.textContent).toMatch(/39\s€/);
    expect(incomeCard.textContent).not.toContain('Lordo');
    expect(incomeCard.textContent).toContain('Netti: quello che è arrivato sul conto');
    expect(text).toMatch(
      /Altre tasse sugli investimenti\s*Imposta di bollo\s*1 movimento\s*-2,00\s€/,
    );
    expect(text).toContain('Le vendite non sono qui');

    // Nessun conto con soli bonifici: nessun avviso.
    expect(p.querySelector('.only-transfers')).toBeNull();
  });

  it('touching a bar tells the month and its amounts, and a line tells the monthly average', async () => {
    const { fixture, page: p } = await page();
    const transfers = p.querySelector('.transfers-card') as HTMLElement;
    const incomeCard = Array.from(p.querySelectorAll('section.card')).find((c) =>
      c.textContent?.includes('Cedole, dividendi e interessi'),
    ) as HTMLElement;
    const pick = (card: HTMLElement) => card.querySelector('.bar-pick')?.textContent?.trim();

    // Prima di toccare: in evidenza l'ultimo mese con un valore, e la sua cifra.
    expect(pick(transfers)).toMatch(/^ottobre 2026: 200,00\s€$/);
    expect(pick(incomeCard)).toMatch(/^giugno 2026: 12,50\s€ netti$/);
    // La linea della media: 3.100 € in 12 mesi.
    expect(transfers.querySelector('.average-label')?.textContent).toMatch(/media 258\s€ al mese/);

    (transfers.querySelectorAll('app-bar-chart rect.hit')[10] as SVGElement).dispatchEvent(
      new MouseEvent('click'),
    );
    (incomeCard.querySelectorAll('app-bar-chart rect.hit')[5] as SVGElement).dispatchEvent(
      new MouseEvent('click'),
    );
    await fixture.whenStable();

    expect(pick(transfers)).toMatch(/^settembre 2026: -100,00\s€$/);
    // Aprile: cedola lorda di 30 € con la ritenuta di 3,90 €; la barra e la cifra sono il netto.
    expect(pick(incomeCard)).toMatch(/^aprile 2026: 26,10\s€ netti$/);
    expect(incomeCard.querySelectorAll('app-bar-chart path.bar')[5].classList.contains('on')).toBe(
      true,
    );
  });

  it('the rows of the income and of the taxes lead to their movements', async () => {
    answer = (period) => ({
      ...investments(period),
      incomeBySource: [
        { name: 'ROMANIA-7OT45 6,5%', amount: 455, movements: 1 },
        {
          name: 'Trade Republic',
          amount: 6.69,
          movements: 2,
          accountId: 9,
          search: 'interest payment for payout collection',
        },
      ],
    });
    const { page: p } = await page();
    const href = (text: string) =>
      Array.from(p.querySelectorAll<HTMLAnchorElement>('ul.rows a'))
        .find((a) => a.textContent?.includes(text))
        ?.getAttribute('href');
    const period = 'from=2025-11-01&to=2026-10-31';

    expect(href('Cedole')).toBe(`/movimenti?${period}&type=INVESTMENT_INCOME&text=cedole`);
    // Un titolo: cedole e ritenute, cercando il suo nome; un conto: le rendite di quel conto.
    expect(href('Romania')).toBe(`/movimenti?${period}&text=romania-7ot45%206,5%25`);
    expect(href('Trade Republic')).toBe(
      `/movimenti?${period}&accountId=9&type=INVESTMENT_INCOME&text=interest%20payment%20for%20payout%20collection`,
    );
    expect(href('Imposta di bollo')).toBe(
      `/movimenti?${period}&type=INVESTMENT_TAX&text=imposta%20di%20bollo`,
    );
  });

  it('lists the purchases and sales and the transfers, eight at first, then all', async () => {
    const { fixture, page: p } = await page();
    const lists = Array.from(p.querySelectorAll('app-investment-movements'));
    expect(lists.map((l) => l.querySelector('h2')?.textContent?.trim())).toEqual([
      'Acquisti e vendite · ultimi 12 mesi',
      'Bonifici verso i conti di investimento · ultimi 12 mesi',
    ]);

    const [trades, transfers] = lists as HTMLElement[];
    expect(trades.querySelectorAll('a.movement').length).toBe(2);
    expect(trades.querySelector('a.movement')?.getAttribute('href')).toBe('/movimenti/41');
    expect(trades.querySelector('button.show-all')).toBeNull();

    expect(transfers.querySelectorAll('a.movement').length).toBe(8);
    const more = transfers.querySelector('button.show-all') as HTMLButtonElement;
    expect(more.textContent).toContain('Mostra tutti (10)');
    more.click();
    await fixture.whenStable();
    expect(transfers.querySelectorAll('a.movement').length).toBe(10);
    expect(transfers.querySelector('button.show-all')).toBeNull();
  });

  it('shows the cash on the investment accounts, and how to get a balance that is missing', async () => {
    answer = (period) => ({
      ...investments(period),
      cash: [
        {
          accountId: 2,
          account: 'Trade Republic',
          manual: false,
          balance: { amount: 118.5, date: '2026-10-14', source: 'BANK', startDate: null },
        },
        {
          accountId: 3,
          account: 'Tinaba · Wallet',
          manual: true,
          balance: { amount: 46.74, date: '2026-10-28', source: 'MANUAL', startDate: '2026-10-28' },
        },
        { accountId: 4, account: 'Fondo Beta · PAC', manual: true, balance: null },
      ],
    });
    const { page: p } = await page();
    const card = p.querySelector('.cash-card') as HTMLElement;
    const text = card.textContent?.replace(/\s+/g, ' ') ?? '';

    // Il totale somma i saldi che si conoscono.
    expect(card.querySelector('.mid-figure')?.textContent).toMatch(/165\s€/);
    expect(text).toMatch(/Trade Republic Al 14 ott 2026 · dalla banca 118,50\s€/);
    expect(text).toMatch(/Tinaba · Wallet Al 28 ott 2026 · dal saldo che hai scritto 46,74\s€/);
    expect(text).toContain('Fondo Beta · PAC Saldo non scritto: scrivilo in Conti');
    expect(card.querySelector('a')?.getAttribute('href')).toBe('/conti');
  });

  it('without investment accounts there is no cash card', async () => {
    const { page: p } = await page();
    expect(p.querySelector('.cash-card')).toBeNull();
  });

  it('warns about the accounts where only the transfers are seen', async () => {
    answer = (period) => ({ ...investments(period), onlyTransfers: ['Fondo Beta · PAC'] });
    const { page: p } = await page();

    const warning = p.querySelector('.only-transfers');
    expect(warning?.textContent?.replace(/\s+/g, ' ')).toContain(
      'Per Fondo Beta · PAC si vedono solo i bonifici',
    );
    expect(warning?.querySelector('a')?.getAttribute('href')).toBe('/importa');
  });

  it('the period buttons ask again; scrolling the line shows that month', async () => {
    const { fixture, page: p } = await page();
    const button = (label: string) =>
      Array.from(p.querySelectorAll('.segmented button')).find(
        (b) => b.textContent?.trim() === label,
      ) as HTMLButtonElement;

    button("Quest'anno").click();
    await fixture.whenStable();
    expect(button("Quest'anno").getAttribute('aria-pressed')).toBe('true');
    expect(p.textContent).toContain('da inizio anno');

    button('Tutto').click();
    await fixture.whenStable();
    expect(requests).toEqual(['YEAR', 'YEAR_TO_DATE', 'ALL']);
    expect(button('Tutto').getAttribute('aria-pressed')).toBe('true');
    expect(p.textContent).toContain('da sempre');

    const slider = p.querySelector('app-trend-chart [role="slider"]') as HTMLElement;
    slider.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home' }));
    await fixture.whenStable();
    expect(p.textContent).toContain('Investito fino a novembre 2025');
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/600\s€/);
  });

  it('with nothing invested explains how to mark the purchases and the transfers', async () => {
    answer = () => NOTHING;
    const { page: p } = await page();

    expect(p.textContent).toContain('Ancora nessun investimento');
    expect(p.textContent).toContain('Acquisto titoli');
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
    expect(card?.textContent?.replace(/\s+/g, ' ')).toMatch(
      /Investito in titoli quest'anno\s*2\.900\s€/,
    );
    expect(card?.textContent?.replace(/\s+/g, ' ')).toMatch(/Rendite quest'anno\s*43\s€/);
    expect(card?.querySelector('path.spark-line')?.getAttribute('d')).toMatch(/^M0\.0 /);

    answer = () => NOTHING;
    const empty = TestBed.createComponent(InvestmentsCard);
    await empty.whenStable();
    expect((empty.nativeElement as HTMLElement).querySelector('a')).toBeNull();
  });
});
