import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Account, AccountRole } from '../api/models';
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

  async function render(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: { accounts: () => of(accounts) } },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
    const fixture = TestBed.createComponent(LiquidityCard);
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
});
