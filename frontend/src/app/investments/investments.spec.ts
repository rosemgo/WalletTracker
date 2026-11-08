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

    expect(requests).toEqual(['YEAR_TO_DATE']); // di serie: Quest'anno
    // L'investito in titoli: la cifra grande, la linea, acquistato e rientrato separati, la frase vera.
    // La cifra grande è l'investito NEL PERIODO (3.600 acquistati - 300 rientrati), non da tutta la storia (3.700).
    expect(text).toContain('Investito in titoli · da inizio anno');
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/3\.300\s€/);
    // Con "Quest'anno" la riga sotto dice solo il mese: l'anno è già la cifra grande.
    expect(p.querySelector('.hero .comparison')?.textContent?.trim()).toMatch(
      /^200\s€ questo mese$/,
    );
    expect(text).toContain('Contano i movimenti che WalletTracker conosce');
    expect(p.querySelector('app-trend-chart path.month')).not.toBeNull();
    expect(text).toMatch(/Acquistato · da inizio anno\s*3\.600\s€/);
    expect(text).toMatch(/Rientrato dalle vendite\s*300\s€/);
    expect(text).toContain('Non è quanto valgono oggi');
    expect(text).not.toContain('Versato');

    // I bonifici: la loro cifra e le barre, una per mese, in su e in giù (settembre: 100 € tornati indietro).
    const transfers = p.querySelector('.transfers-card') as HTMLElement;
    expect(transfers.textContent).toMatch(
      /Bonifici verso i conti di investimento · da inizio anno\s*3\.300\s€/,
    );
    const transferBars = Array.from(transfers.querySelectorAll('app-bar-chart path.bar'));
    expect(transferBars.length).toBe(12);
    // Il colore di sempre: niente verde e rosso (quello è per il netto di Analisi).
    expect(transferBars.filter((b) => b.matches('.pos, .neg')).length).toBe(0);
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
      'Acquisti e vendite · da inizio anno',
      'Bonifici verso i conti di investimento · da inizio anno',
    ]);

    const [trades, transfers] = lists as HTMLElement[];
    expect(trades.querySelectorAll('a.movement').length).toBe(2);
    expect(trades.querySelector('a.movement')?.getAttribute('href')).toBe('/movimenti/41');
    expect(trades.querySelector('button.show-all')).toBeNull();

    expect(transfers.querySelectorAll('a.movement').length).toBe(8);
    const more = transfers.querySelector('button.show-all') as HTMLButtonElement;
    expect(more.textContent).toContain('Mostra tutti (10)');
    expect(more.getAttribute('aria-expanded')).toBe('false');
    more.click();
    await fixture.whenStable();
    expect(transfers.querySelectorAll('a.movement').length).toBe(10);
    // Aperto, si può richiudere.
    expect(more.textContent?.trim()).toBe('Mostra meno');
    expect(more.getAttribute('aria-expanded')).toBe('true');
    more.click();
    await fixture.whenStable();
    expect(transfers.querySelectorAll('a.movement').length).toBe(8);
  });

  it('a long list of the column shows the first five, then all, then five again; a short one stays whole', async () => {
    const sources = Array.from({ length: 12 }, (_, i) => ({
      name: `TITOLO ${i + 1}`,
      amount: 100 - i,
      movements: 1,
    }));
    answer = (period) => ({ ...investments(period), incomeBySource: sources });
    const { fixture, page: p } = await page();
    const list = () =>
      Array.from(p.querySelectorAll('h3')).find((h) => h.textContent === 'Da dove')!
        .nextElementSibling as HTMLElement;
    const button = () => list().nextElementSibling as HTMLButtonElement;

    expect(list().querySelectorAll('li').length).toBe(5);
    expect(list().textContent).toContain('Titolo 1');
    expect(list().textContent).not.toContain('Titolo 6');
    expect(button().textContent?.trim()).toBe('Mostra tutti (12)');

    button().click();
    await fixture.whenStable();
    expect(list().querySelectorAll('li').length).toBe(12);
    expect(button().textContent?.trim()).toBe('Mostra meno');

    button().click();
    await fixture.whenStable();
    expect(list().querySelectorAll('li').length).toBe(5);

    // "Per tipo" ha poche righe: tutte, e nessun pulsante.
    const types = Array.from(p.querySelectorAll('h3')).find((h) => h.textContent === 'Per tipo')!
      .nextElementSibling as HTMLElement;
    expect(types.nextElementSibling?.tagName).toBe('H3');
  });

  it('a list opened with "Mostra tutti" closes again with another period', async () => {
    const sources = Array.from({ length: 9 }, (_, i) => ({
      name: `T${i}`,
      amount: 9 - i,
      movements: 1,
    }));
    answer = (period) => ({ ...investments(period), incomeBySource: sources });
    const { fixture, page: p } = await page();
    const count = () =>
      (
        Array.from(p.querySelectorAll('h3')).find((h) => h.textContent === 'Da dove')!
          .nextElementSibling as HTMLElement
      ).querySelectorAll('li').length;
    (p.querySelector('.card button.show-all') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(count()).toBe(9);

    const other = Array.from(p.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === 'Tutto',
    );
    other!.click();
    await fixture.whenStable();
    expect(count()).toBe(5);
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

    // Di serie "Quest'anno", ed è il primo pulsante; "12 mesi" viene dopo.
    expect(p.querySelector('.segmented button')?.textContent?.trim()).toBe("Quest'anno");
    expect(button("Quest'anno").getAttribute('aria-pressed')).toBe('true');
    expect(p.textContent).toContain('da inizio anno');

    button('12 mesi').click();
    await fixture.whenStable();
    expect(button('12 mesi').getAttribute('aria-pressed')).toBe('true');
    expect(p.textContent).toContain('ultimi 12 mesi');
    // Con un altro periodo la riga sotto dice anche l'anno.
    expect(p.querySelector('.hero .comparison')?.textContent).toMatch(
      /2\.900\s€ quest'anno · 200\s€ questo mese/,
    );

    button('Tutto').click();
    await fixture.whenStable();
    expect(requests).toEqual(['YEAR_TO_DATE', 'YEAR', 'ALL']);
    expect(button('Tutto').getAttribute('aria-pressed')).toBe('true');
    expect(p.textContent).toContain('da sempre');

    const slider = p.querySelector('app-trend-chart [role="slider"]') as HTMLElement;
    slider.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home' }));
    await fixture.whenStable();
    // La linea parte da zero all'inizio del periodo: il primo mese vale i suoi acquisti (200), non lo storico di prima.
    expect(p.textContent).toContain('Investito a novembre 2025');
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/200\s€/);
    slider.dispatchEvent(new KeyboardEvent('keydown', { key: 'End' }));
    await fixture.whenStable();
    // In fondo, la somma dei mesi della linea: 11 × 200 + 1.200 - 300. (Qui il finto backend scrive a mano i totali del
    // periodo, 3.600 e 300: quello vero li calcola dagli stessi movimenti dei mesi, e le due cifre coincidono.)
    expect(p.textContent).toContain('Investito da novembre 2025 a ottobre 2026');
    expect(p.querySelector('.big-figure')?.textContent).toMatch(/3\.100\s€/);
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

  it('the transfers chart has the usual color, not green and red: a month back is a bar downwards', async () => {
    const { page: p } = await page();
    const card = p.querySelector('.transfers-card') as HTMLElement;
    const bars = Array.from(card.querySelectorAll('.bar-chart path.bar'));

    expect(bars.length).toBeGreaterThan(0);
    expect(card.querySelectorAll('.bar-chart path.pos, .bar-chart path.neg').length).toBe(0);
    // Il mese con -100 € (soldi tornati indietro) c'è lo stesso, sotto la linea dello zero.
    const zero = Number(card.querySelector('.bar-chart line.zero')?.getAttribute('y1'));
    // Una barra verso il basso comincia sulla linea dello zero ("M x zero V ..."), una verso l'alto finisce lì.
    const below = bars.filter((b) => {
      const start = (b.getAttribute('d') ?? '').match(/^M[\d.]+ ([\d.]+)V([\d.]+)/);
      return start !== null && Math.abs(Number(start[1]) - zero) < 0.2 && Number(start[2]) > zero;
    });
    expect(below.length).toBe(1);
  });
});
