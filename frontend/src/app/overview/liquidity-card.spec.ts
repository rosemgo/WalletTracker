import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AccountsStore } from '../accounts/accounts-store';
import { Account, AccountRole, BalanceHistory } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { LiquidityCard } from './liquidity-card';

registerLocaleData(localeIt);

/** Un conto inventato, con il saldo (o senza, con amount null). */
function account(
  id: number,
  label: string,
  role: AccountRole,
  amount: number | null,
  currency = 'EUR',
): Account {
  return {
    id,
    label,
    displayName: null,
    bank: 'Banca Alfa',
    name: null,
    iban: null,
    currency,
    role,
    source: 'MANUAL',
    consentValidUntil: null,
    connectionRemovedAt: null,
    lastSyncAt: null,
    lastSyncError: null,
    balance:
      amount === null ? null : { amount, date: '2026-11-06', source: 'BANK', startDate: null },
    availableCredit: null,
    logoVersion: null,
  };
}

/** Riepilogo → Liquidità: i soldi sui conti adesso, in tutto e conto per conto. */
describe('LiquidityCard', () => {
  let accounts: Account[];
  let history: BalanceHistory;
  let accountHistory: BalanceHistory; // lo storico di un conto solo
  let asked: string[];
  let fixture: ComponentFixture<LiquidityCard>;

  beforeEach(() => {
    history = { currency: 'EUR', accounts: 0, points: [] }; // di solito niente storico: niente grafico
    accountHistory = { currency: 'EUR', accounts: 0, points: [] };
    asked = [];
  });

  async function render(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: WalletApi,
          useValue: {
            accounts: () => of([...accounts]), // ogni volta una lista nuova, come dal backend
            balanceHistory: (currency: string, accountId: number | null = null) => {
              asked.push(accountId === null ? currency : `${currency}|${accountId}`);
              return of(accountId === null ? history : accountHistory);
            },
          },
        },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
    fixture = TestBed.createComponent(LiquidityCard);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('adds up the accounts, the investment ones too, but not the credit card nor the excluded ones', async () => {
    accounts = [
      account(1, 'Banca Alfa ··0001', 'MAIN', 3245.18),
      account(2, 'Broker Beta', 'INVESTMENT', 410.5),
      account(3, 'Carta Gamma', 'CREDIT_CARD', 1500),
      account(4, 'Conto chiuso', 'EXCLUDED', 99),
    ];
    const page = await render();

    const totals = page.querySelectorAll('[data-testid="liquidity-total"]');
    expect(totals).toHaveLength(1);
    expect(totals[0].textContent).toMatch(/3\.656\s€/);
    expect(page.querySelector('.liquidity-currency')).toBeNull(); // una valuta sola: niente titoletti
    const rows = Array.from(page.querySelectorAll('ul.rows li')).map((li) =>
      li.textContent?.replace(/\s+/g, ' ').trim(),
    );
    // Dal più ricco.
    expect(rows).toEqual([
      expect.stringMatching(/^Banca Alfa ··0001 al 6 nov\s?3\.245,18\s€$/),
      expect.stringMatching(/^Broker Beta al 6 nov\s?410,50\s€$/),
    ]);
    expect(page.textContent).toContain('Su 2 conti, adesso');
    expect(page.querySelector('a')?.getAttribute('href')).toBe('/conti');
  });

  it('shows one total for each currency, without converting, and the accounts under their currency', async () => {
    accounts = [
      account(1, 'Banca Alfa', 'MAIN', 3245.18),
      account(2, 'Broker Beta', 'INVESTMENT', 410.5),
      account(5, 'Conto in dollari', 'SPENDING', 700, 'USD'),
    ];
    const page = await render();

    // Prima l'euro (più conti), poi il dollaro; le due cifre non si sommano.
    const totals = Array.from(page.querySelectorAll('[data-testid="liquidity-total"]')).map((t) =>
      t.textContent?.trim(),
    );
    expect(totals).toEqual([
      expect.stringMatching(/^3\.656\s€$/),
      expect.stringMatching(/^700\sUSD$/), // in italiano il dollaro si scrive con il codice
    ]);
    expect(
      Array.from(page.querySelectorAll('.liquidity-currency')).map((h) => h.textContent?.trim()),
    ).toEqual(['EUR', 'USD']);
    const lists = page.querySelectorAll('ul.rows');
    expect(lists[1].textContent).toContain('Conto in dollari');
    expect(page.textContent).toContain('Su 3 conti, adesso');
  });

  it('says how many accounts have no balance, and is not there without any balance', async () => {
    accounts = [account(1, 'Banca Alfa', 'MAIN', 100), account(2, 'Conto manuale', 'MAIN', null)];
    let page = await render();
    expect(page.textContent).toContain('1 conto senza saldo non è nel totale');

    TestBed.resetTestingModule();
    accounts = [account(2, 'Conto manuale', 'MAIN', null)];
    page = await render();
    expect(page.querySelector('.liquidity-card')).toBeNull();
  });

  it('draws the liquidity day by day: estimated and dashed before the saved balances', async () => {
    accounts = [
      account(1, 'Banca Alfa', 'MAIN', 1000),
      account(5, 'Conto USD', 'SPENDING', 70, 'USD'),
    ];
    history = {
      currency: 'EUR',
      accounts: 1,
      points: [
        { date: '2026-11-13', amount: -300, estimated: true },
        { date: '2026-11-14', amount: -500, estimated: true },
        { date: '2026-11-15', amount: 1000, estimated: false },
        { date: '2026-11-16', amount: 1000, estimated: false },
      ],
    };
    const page = await render();

    // Lo storico si chiede per la valuta principale, una volta: anche se i conti si aggiornano di nuovo.
    expect(asked).toEqual(['EUR']);
    TestBed.inject(AccountsStore).refresh();
    await fixture.whenStable();
    expect(asked).toEqual(['EUR']);
    expect(page.querySelector('app-trend-chart path.estimate')).not.toBeNull();
    expect(page.querySelector('app-trend-chart path.month')).not.toBeNull();
    expect(page.querySelector('.chart-keys')?.textContent).toContain('Stima dai movimenti');
    // Due giorni salvati di fila: c'è un tratto pieno, e la legenda ha tutte e due le voci.
    expect(page.querySelector('.chart-keys')?.textContent).toContain('Saldi salvati');

    // Scorrendo indietro di due giorni, il totale in euro mostra il 14 novembre, stimato.
    const wrap = page.querySelector('app-trend-chart .month-chart-wrap') as HTMLElement;
    wrap.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    wrap.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    fixture.detectChanges();
    expect(page.querySelector('[data-testid="liquidity-total"]')?.textContent).toMatch(/^-500\s€$/);
    expect(page.textContent).toContain('Il 14 novembre 2026 · stima dai movimenti');
    // Il totale in dollari non è nel grafico: resta quello di adesso.
    expect(page.querySelectorAll('[data-testid="liquidity-total"]')[1].textContent).toMatch(
      /70\sUSD/,
    );
  });

  it('dashes every estimated stretch, also a gap between saved days', async () => {
    accounts = [account(1, 'Banca Alfa', 'MAIN', 1000)];
    const day = (d: number, estimated: boolean) => ({
      date: `2026-11-${String(d).padStart(2, '0')}`,
      amount: 900 + d,
      estimated,
    });
    // Salvati il 10 e l'11, l'app spenta il 12 e il 13, di nuovo salvati il 14 e il 15, oggi stimato.
    history = {
      currency: 'EUR',
      accounts: 1,
      points: [
        day(10, false),
        day(11, false),
        day(12, true),
        day(13, true),
        day(14, false),
        day(15, false),
        day(16, true),
      ],
    };
    const page = await render();
    const solid = page.querySelectorAll('app-trend-chart path.month');
    const dashed = page.querySelectorAll('app-trend-chart path.estimate');
    expect(solid).toHaveLength(2); // 10-11 e 14-15
    expect(dashed).toHaveLength(2); // 11-14 e 15-16
  });

  it('with only today saved the legend shows only the estimate, and no explanation', async () => {
    accounts = [account(1, 'Banca Alfa', 'MAIN', 1000)];
    history = {
      currency: 'EUR',
      accounts: 1,
      points: [
        { date: '2026-11-15', amount: 900, estimated: true },
        { date: '2026-11-16', amount: 1000, estimated: false },
      ],
    };
    const page = await render();
    const keys = page.querySelector('.chart-keys')?.textContent ?? '';
    expect(keys).toContain('Stima dai movimenti');
    expect(keys).not.toContain('Saldi salvati');
    expect(page.querySelector('.history-note')).toBeNull();
  });

  it('without a history of at least two days there is no chart', async () => {
    accounts = [account(1, 'Banca Alfa', 'MAIN', 1000)];
    history = {
      currency: 'EUR',
      accounts: 1,
      points: [{ date: '2026-11-16', amount: 1000, estimated: false }],
    };
    const page = await render();
    expect(page.querySelector('app-trend-chart')).toBeNull();
  });

  it('with more currencies lets choose which one to draw, one at a time', async () => {
    accounts = [
      account(1, 'Banca Alfa', 'MAIN', 1000),
      account(2, 'Broker Beta', 'INVESTMENT', 500),
      account(5, 'Conto USD', 'SPENDING', 700, 'USD'),
      account(6, 'Conto CHF', 'SPENDING', 300, 'CHF'),
    ];
    const histories: Record<string, BalanceHistory> = {
      EUR: {
        currency: 'EUR',
        accounts: 2,
        points: [
          { date: '2026-11-15', amount: 1400, estimated: true },
          { date: '2026-11-16', amount: 1500, estimated: false },
        ],
      },
      USD: {
        currency: 'USD',
        accounts: 1,
        points: [
          { date: '2026-11-15', amount: 650, estimated: true },
          { date: '2026-11-16', amount: 700, estimated: false },
        ],
      },
      CHF: {
        currency: 'CHF',
        accounts: 1,
        points: [{ date: '2026-11-16', amount: 300, estimated: false }],
      },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: WalletApi,
          useValue: {
            accounts: () => of([...accounts]),
            balanceHistory: (currency: string) => {
              asked.push(currency);
              return of(histories[currency]);
            },
          },
        },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
    fixture = TestBed.createComponent(LiquidityCard);
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    const buttons = () =>
      Array.from(page.querySelectorAll<HTMLButtonElement>('[data-testid="chart-currency"] button'));

    // Tutte le valute dei conti, la principale (più conti) scelta.
    expect(buttons().map((b) => b.textContent?.trim())).toEqual(['EUR', 'CHF', 'USD']);
    expect(buttons()[0].getAttribute('aria-pressed')).toBe('true');
    expect(asked).toEqual(['EUR']);

    buttons()[2].click(); // USD
    await fixture.whenStable();
    fixture.detectChanges();
    expect(asked).toEqual(['EUR', 'USD']);
    expect(buttons()[2].getAttribute('aria-pressed')).toBe('true');
    expect(page.querySelector('app-trend-chart')).not.toBeNull();
    // Scorrendo, cambia il totale in dollari; quello in euro resta.
    const wrap = page.querySelector('app-trend-chart .month-chart-wrap') as HTMLElement;
    wrap.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    fixture.detectChanges();
    const totals = Array.from(page.querySelectorAll('[data-testid="liquidity-total"]')).map((t) =>
      t.textContent?.trim(),
    );
    expect(totals).toEqual([
      expect.stringMatching(/^1\.500\s€$/),
      expect.stringMatching(/^300\sCHF$/),
      expect.stringMatching(/^650\sUSD$/),
    ]);

    // Una valuta senza storico da disegnare lo dice, e i pulsanti restano per tornare indietro.
    buttons()[1].click(); // CHF
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.querySelector('app-trend-chart')).toBeNull();
    expect(page.textContent).toContain('Storico non ancora disponibile per CHF.');
    expect(buttons()).toHaveLength(3);
  });

  it('with one currency there is no choice', async () => {
    accounts = [account(1, 'Banca Alfa', 'MAIN', 1000)];
    const page = await render();
    expect(page.querySelector('[data-testid="chart-currency"]')).toBeNull();
  });

  it('touching an account draws only that account, and touching it again all of them', async () => {
    accounts = [
      account(1, 'Banca Alfa', 'MAIN', 1000),
      account(2, 'Broker Beta', 'INVESTMENT', 400),
    ];
    history = {
      currency: 'EUR',
      accounts: 2,
      points: [
        { date: '2026-11-15', amount: 1300, estimated: true },
        { date: '2026-11-16', amount: 1400, estimated: false },
      ],
    };
    accountHistory = {
      currency: 'EUR',
      accounts: 1,
      points: [
        { date: '2026-11-15', amount: 350, estimated: true },
        { date: '2026-11-16', amount: 400, estimated: false },
      ],
    };
    const page = await render();
    const row = (label: string) =>
      Array.from(page.querySelectorAll<HTMLButtonElement>('button.liquidity-row')).find((b) =>
        b.textContent?.includes(label),
      )!;

    row('Broker Beta').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(asked).toEqual(['EUR', 'EUR|2']);
    expect(row('Broker Beta').getAttribute('aria-pressed')).toBe('true');
    expect(page.querySelector('[data-testid="chart-account"]')?.textContent).toContain(
      'Broker Beta',
    );

    // Scorrendo, il saldo del conto in quel giorno sta sopra il grafico; il totale resta quello di adesso.
    const wrap = page.querySelector('app-trend-chart .month-chart-wrap') as HTMLElement;
    wrap.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    fixture.detectChanges();
    expect(page.querySelector('[data-testid="chart-account"]')?.textContent).toMatch(
      /350\s€ · 15 novembre 2026 · stima/,
    );
    expect(page.querySelector('[data-testid="liquidity-total"]')?.textContent).toMatch(/1\.400\s€/);

    // Di nuovo lo stesso conto: tutti. Con "Tutti i conti" lo stesso.
    row('Broker Beta').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.querySelector('[data-testid="chart-account"]')).toBeNull();
    row('Banca Alfa').click();
    await fixture.whenStable();
    fixture.detectChanges();
    (page.querySelector('[data-testid="chart-account"] button') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.querySelector('[data-testid="chart-account"]')).toBeNull();
    expect(asked).toEqual(['EUR', 'EUR|2', 'EUR', 'EUR|1', 'EUR']);
  });
});
