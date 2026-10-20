import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import {
  Category,
  Transaction,
  TransactionFilter,
  TransactionType,
  UsedCategories,
} from '../api/models';
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
  categoryId: 4,
  category: 'Saldo carta',
  manualType: null,
  manualCategoryId: null,
  transferPeerId: null,
};

/** Un pezzo dell'elenco delle categorie, come lo restituisce GET /api/categories. */
const CATEGORIES: Category[] = [
  { id: 1, name: 'Shopping', kind: 'EXPENSE', builtin: true, uses: 3 },
  { id: 2, name: 'Intrattenimento e tempo libero', kind: 'EXPENSE', builtin: true, uses: 1 },
  { id: 3, name: 'Stipendio e pensione', kind: 'INCOME', builtin: true, uses: 2 },
  { id: 4, name: 'Saldo carta', kind: 'OTHER', builtin: true, uses: 3 },
];

describe('TransactionsPage', () => {
  const requests: TransactionFilter[] = [];
  const corrections: [number, TransactionType | null][] = [];
  const categoryChanges: [number, number | null][] = [];
  let items: Transaction[];
  let suggestionLoads: number;
  let used: UsedCategories;

  beforeEach(() => {
    requests.length = 0;
    corrections.length = 0;
    categoryChanges.length = 0;
    items = [SETTLEMENT];
    suggestionLoads = 0;
    used = { word: null, categories: [] };
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
      correctCategory: (id: number, categoryId: number | null) => {
        categoryChanges.push([id, categoryId]);
        return of({ ...SETTLEMENT, manualCategoryId: categoryId });
      },
      categories: () => of(CATEGORIES),
      usedCategories: () => of(used),
      categorySuggestions: () => {
        suggestionLoads++;
        return of([]);
      },
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

    expect(requests.at(-1)).toEqual({
      accountId: null,
      type: 'TO_REVIEW',
      uncategorized: false,
      from: null,
      to: null,
      page: 0,
      size: 50,
    });
  });

  it('"Solo senza categoria" asks only for the movements without a category', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const box = (fixture.nativeElement as HTMLElement).querySelector(
      '.filters input[type="checkbox"]',
    ) as HTMLInputElement;

    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(requests.at(-1)).toMatchObject({ uncategorized: true, page: 0 });
  });

  it('a period from the menu sets both dates, and a date from the calendar means custom', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const period = Array.from(page.querySelectorAll('.filters select')).at(-1) as HTMLSelectElement;
    const [from, to] = Array.from(
      page.querySelectorAll('.filters input[type="date"]'),
    ) as HTMLInputElement[];

    period.value = 'THIS_YEAR';
    period.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const year = new Date().getFullYear();
    expect(requests.at(-1)).toMatchObject({ from: `${year}-01-01`, to: `${year}-12-31` });
    expect(from.value).toBe(`${year}-01-01`); // il calendario mostra le date del periodo

    to.value = `${year}-03-31`;
    to.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ from: `${year}-01-01`, to: `${year}-03-31` });
    expect(period.selectedOptions[0].textContent?.trim()).toBe('Scegli le date');

    period.value = 'ALL';
    period.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ from: null, to: null });
  });

  it('a start date after the end date is not sent and is explained', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const [from, to] = Array.from(
      page.querySelectorAll('.filters input[type="date"]'),
    ) as HTMLInputElement[];
    to.value = '2026-10-01';
    to.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const sent = requests.length;

    from.value = '2026-10-31';
    from.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(requests.length).toBe(sent);
    expect(page.querySelector('.error')?.textContent).toContain(
      'La data di inizio è dopo quella di fine',
    );
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

  it('the category is chosen from a menu with the right group first, and "nessuna" removes it', async () => {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === text,
      ) as HTMLButtonElement;

    button('modifica').click();
    await fixture.whenStable();
    const select = page.querySelector('.category-edit select') as HTMLSelectElement;
    // Si parte dalla categoria attuale. Il valore di un'opzione HTML è testo: l'id 4 diventa "4".
    expect(select.value).toBe('4');
    // Un saldo della carta (tipo "Altro"): in cima le altre categorie, poi spese ed entrate.
    expect(Array.from(select.querySelectorAll('optgroup')).map((g) => g.label)).toEqual([
      'Altre categorie',
      'Categorie di spesa',
      'Categorie di entrata',
    ]);

    select.value = '1'; // Shopping
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(categoryChanges).toEqual([[449, 1]]); // l'id, come numero
    expect(page.querySelector('.category-edit')).toBeNull(); // il menu si chiude

    button('modifica').click();
    await fixture.whenStable();
    const again = page.querySelector('.category-edit select') as HTMLSelectElement;
    again.value = '';
    again.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(categoryChanges[1]).toEqual([449, null]);
    // Una scelta a mano può far nascere un suggerimento: il pannello si ricarica (1 all'avvio + 2).
    expect(suggestionLoads).toBe(3);
  });

  it('the categories already used for the merchant come first in the menu', async () => {
    used = {
      word: 'AMAZON',
      categories: [
        { categoryId: 1, category: 'Shopping', times: 2 },
        { categoryId: 2, category: 'Intrattenimento e tempo libero', times: 1 },
      ],
    };
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    (
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'modifica',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    const select = page.querySelector('.category-edit select') as HTMLSelectElement;
    const first = select.querySelector('optgroup') as HTMLOptGroupElement;

    expect(first.label).toBe('Usate per «AMAZON…»');
    expect(Array.from(first.querySelectorAll('option')).map((o) => o.textContent?.trim())).toEqual([
      'Shopping (2 volte)',
      'Intrattenimento e tempo libero (1 volta)',
    ]);
    expect(select.value).toBe('4'); // si parte sempre dalla categoria attuale

    select.selectedIndex = Array.from(select.options).findIndex((o) =>
      o.textContent?.includes('Shopping (2 volte)'),
    );
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(categoryChanges).toEqual([[449, 1]]);
  });

  it('after a correction, offers to create a rule prefilled with the movement data', async () => {
    items = [
      {
        ...SETTLEMENT,
        id: 7,
        amount: -12.99,
        counterparty: ' NETFLIX.COM   AMSTERDAM\u00a0 NLD ', // campi a larghezza fissa, come li mandano le banche
        description: 'Pagamento carta',
        type: 'EXPENSE',
        categoryId: 2,
        category: 'Intrattenimento e tempo libero',
        manualCategoryId: 2,
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
      pattern: 'NETFLIX.COM AMSTERDAM NLD', // spazi ridotti a uno
      resultType: 'EXPENSE',
      amountSign: 'NEGATIVE',
      categoryId: '2',
    });
  });

  it('for an income, the "Spesa" type is offered as the refund of an expense', async () => {
    items = [{ ...SETTLEMENT, amount: 40, type: 'INCOME', categoryId: null, category: null }];
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('td select option'),
    ).map((o) => o.textContent?.trim());

    expect(options).toContain('Rimborso di una spesa');
    expect(options).not.toContain('Spesa');
  });
});
