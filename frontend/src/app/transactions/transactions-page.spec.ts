import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { Transaction, TransactionFilter, TransactionType } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { TransactionsPage } from './transactions-page';

const SETTLEMENT: Transaction = {
  id: 449,
  accountId: 2,
  status: 'BOOKED',
  bookingDate: '2026-10-10',
  valueDate: '2026-10-10',
  amount: -630.39,
  currency: 'EUR',
  counterparty: null,
  description: 'Estratto conto carta di credito al 20261010',
  type: 'CARD_SETTLEMENT',
  category: 'Saldo carta',
  manualType: null,
  transferPeerId: null,
};

describe('TransactionsPage', () => {
  const requests: TransactionFilter[] = [];
  const corrections: [number, TransactionType | null][] = [];

  beforeEach(() => {
    requests.length = 0;
    corrections.length = 0;
    registerLocaleData(localeIt);
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of([]),
      transactions: (filter: TransactionFilter) => {
        requests.push(filter);
        return of({ items: [SETTLEMENT], page: 0, size: 50, totalItems: 1, totalPages: 1 });
      },
      correctType: (id: number, type: TransactionType | null) => {
        corrections.push([id, type]);
        return of({ ...SETTLEMENT, manualType: type });
      },
    };
    TestBed.configureTestingModule({
      imports: [TransactionsPage],
      providers: [
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  it('shows dates and amounts in Italian format and the type in Italian', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('10/10/2026');
    expect(text).toMatch(/-630,39\s€/); // tra numero e simbolo c'è uno spazio "speciale" (non separabile)
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'td select',
    ) as HTMLSelectElement;
    expect(select.selectedOptions[0].textContent?.trim()).toBe('Saldo carta di credito');
  });

  it('a filter restarts from the first page', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const typeFilter = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '.filters select',
    )[1] as HTMLSelectElement;

    typeFilter.value = 'TO_REVIEW';
    typeFilter.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(requests.at(-1)).toEqual({ accountId: null, type: 'TO_REVIEW', page: 0, size: 50 });
  });

  it('a correction is saved and the page is reloaded', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'td select',
    ) as HTMLSelectElement;

    select.value = 'EXPENSE';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(corrections).toEqual([[449, 'EXPENSE']]);
    expect(requests.length).toBe(2); // il primo caricamento + quello dopo la correzione
  });
});
