import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
  manualCategory: null,
  transferPeerId: null,
};

describe('TransactionsPage', () => {
  const requests: TransactionFilter[] = [];
  const corrections: [number, TransactionType | null][] = [];
  const categoryChanges: [number, string | null][] = [];
  let items: Transaction[];

  beforeEach(() => {
    requests.length = 0;
    corrections.length = 0;
    categoryChanges.length = 0;
    items = [SETTLEMENT];
    registerLocaleData(localeIt);
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of([]),
      transactions: (filter: TransactionFilter) => {
        requests.push(filter);
        return of({ items, page: 0, size: 50, totalItems: items.length, totalPages: 1 });
      },
      correctType: (id: number, type: TransactionType | null) => {
        corrections.push([id, type]);
        return of({ ...SETTLEMENT, manualType: type });
      },
      correctCategory: (id: number, category: string | null) => {
        categoryChanges.push([id, category]);
        return of({ ...SETTLEMENT, manualCategory: category });
      },
      categories: () => of(['Abbonamenti', 'Spesa alimentare']),
    };
    TestBed.configureTestingModule({
      imports: [TransactionsPage],
      providers: [
        provideRouter([]),
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

  it('a category typed by the user is saved, and an empty one removes the choice', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === text,
      ) as HTMLButtonElement;

    button('modifica').click();
    await fixture.whenStable();
    const input = page.querySelector('.category-edit input') as HTMLInputElement;
    expect(input.value).toBe('Saldo carta'); // si parte dalla categoria attuale
    expect(page.querySelectorAll('datalist option').length).toBe(2); // i suggerimenti

    input.value = '  Carta  ';
    button('Salva').click();
    await fixture.whenStable();
    expect(categoryChanges).toEqual([[449, 'Carta']]);
    expect(page.querySelector('.category-edit')).toBeNull(); // il campo si chiude

    button('modifica').click();
    await fixture.whenStable();
    (page.querySelector('.category-edit input') as HTMLInputElement).value = '';
    button('Salva').click();
    await fixture.whenStable();
    expect(categoryChanges[1]).toEqual([449, null]);
  });

  it('after a correction, offers to create a rule prefilled with the movement data', async () => {
    items = [
      {
        ...SETTLEMENT,
        id: 7,
        amount: -12.99,
        counterparty: 'NETFLIX.COM',
        description: 'Pagamento carta',
        type: 'EXPENSE',
        category: 'Abbonamenti',
        manualCategory: 'Abbonamenti',
      },
    ];
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const link = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).find(
      (a) => a.textContent?.trim() === 'crea una regola',
    ) as HTMLAnchorElement;

    const address = new URL(link.href);
    expect(address.pathname).toBe('/regole');
    expect(Object.fromEntries(address.searchParams)).toEqual({
      pattern: 'NETFLIX.COM',
      resultType: 'EXPENSE',
      amountSign: 'NEGATIVE',
      category: 'Abbonamenti',
    });
  });

  it('for an income, the "Spesa" type is offered as the refund of an expense', async () => {
    items = [{ ...SETTLEMENT, amount: 40, type: 'INCOME', category: null }];
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('td select option'),
    ).map((o) => o.textContent?.trim());

    expect(options).toContain('Rimborso di una spesa');
    expect(options).not.toContain('Spesa');
  });
});
