import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { Account, Budget, BudgetRequest, Category } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { addMonths, BudgetPage, monthOf } from './budget-page';

const CATEGORIES: Category[] = [
  { id: 1, name: 'Spesa alimentare', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 2, name: 'Bar e ristoranti', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 3, name: 'Viaggi e vacanze', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 4, name: 'Stipendio e pensione', kind: 'INCOME', builtin: true, uses: 0 },
];

const GROCERIES: Budget = {
  id: 1,
  category: 'Spesa alimentare',
  currency: 'EUR',
  monthlyAmount: 400,
  spent: 320,
  remaining: 80,
  expectedSoFar: 200,
  averageLast3Months: 150,
  status: 'WARNING',
};

const TRAVEL: Budget = {
  ...GROCERIES,
  id: 2,
  category: 'Viaggi e vacanze',
  monthlyAmount: 100,
  spent: 130,
  remaining: -30,
  status: 'OVER',
};

describe('BudgetPage', () => {
  const requestedMonths: string[] = [];
  const created: BudgetRequest[] = [];
  const updated: [number, BudgetRequest][] = [];
  let accounts: Partial<Account>[];
  let duplicate: boolean;

  beforeEach(() => {
    registerLocaleData(localeIt);
    requestedMonths.length = 0;
    created.length = 0;
    updated.length = 0;
    accounts = [{ currency: 'EUR' }];
    duplicate = false;
    const fakeApi: Partial<WalletApi> = {
      budgets: (month: string) => {
        requestedMonths.push(month);
        return of([GROCERIES, TRAVEL]);
      },
      categories: () => of(CATEGORIES),
      accounts: () => of(accounts as Account[]),
      createBudget: (budget: BudgetRequest) => {
        created.push(budget);
        return duplicate
          ? throwError(() => ({
              error: { detail: 'Esiste già un budget per "Spesa alimentare" in EUR.' },
            }))
          : of({ ...GROCERIES, ...budget, id: 3 });
      },
      updateBudget: (id: number, budget: BudgetRequest) => {
        updated.push([id, budget]);
        return of({ ...GROCERIES, ...budget });
      },
    };
    TestBed.configureTestingModule({
      imports: [BudgetPage],
      providers: [
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  /** Scrive in un campo del modulo, o sceglie una voce in un menu, come farebbe l'utente. */
  function type(page: HTMLElement, field: string, value: string): void {
    const element = page.querySelector(`[formControlName="${field}"]`) as HTMLInputElement;
    element.value = value;
    // Un <input> avvisa il Reactive Form con l'evento "input", un <select> con "change".
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input'));
  }

  it('shows each budget with its status as icon and words, and how much is left', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.budget');

    expect(requestedMonths).toEqual([monthOf(new Date())]);
    expect(cards[0].textContent).toContain('Quasi esaurito');
    expect(cards[0].textContent).toMatch(/restano 80,00\s€/);
    expect((cards[0].querySelector('.meter-fill') as HTMLElement).style.width).toBe('80%');
    // Il segno "oggi": 200 di 400 = a metà della barra.
    expect((cards[0].querySelector('.meter-today') as HTMLElement).style.left).toBe('50%');

    expect(cards[1].textContent).toContain('Superato');
    expect(cards[1].textContent).toMatch(/superato di 30,00\s€/);
    // La barra non va oltre il 100%, anche se la spesa sì.
    expect((cards[1].querySelector('.meter-fill') as HTMLElement).style.width).toBe('100%');
  });

  it('moves between months, but not past the current one', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const previous = page.querySelector('[aria-label="Mese precedente"]') as HTMLButtonElement;
    const next = page.querySelector('[aria-label="Mese successivo"]') as HTMLButtonElement;

    expect(next.disabled).toBe(true);
    previous.click();
    await fixture.whenStable();

    expect(requestedMonths[1]).toBe(addMonths(monthOf(new Date()), -1));
    expect(next.disabled).toBe(false);
    expect(page.querySelector('.meter-today')).toBeNull(); // in un mese passato non c'è "oggi"
  });

  it('creates a budget, accepting the Italian decimal comma', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    type(page, 'category', 'Bar e ristoranti');
    type(page, 'monthlyAmount', '150,50');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(created).toEqual([
      { category: 'Bar e ristoranti', currency: 'EUR', monthlyAmount: 150.5 },
    ]);
    expect(page.textContent).toContain('Budget "Bar e ristoranti" salvato');
  });

  it('does not send an invalid amount, and shows the backend message for a duplicate', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const submit = page.querySelector('button[type="submit"]') as HTMLButtonElement;

    type(page, 'category', 'Spesa alimentare');
    type(page, 'monthlyAmount', '10.123');
    submit.click();
    await fixture.whenStable();
    expect(created).toEqual([]);
    expect(page.textContent).toContain('al massimo con due decimali');

    duplicate = true;
    type(page, 'monthlyAmount', '100');
    submit.click();
    await fixture.whenStable();
    expect(page.textContent).toContain('Esiste già un budget per "Spesa alimentare" in EUR.');
  });

  it('"modifica" fills the form and saving updates the same budget', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    (
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'modifica',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    type(page, 'monthlyAmount', '450');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(updated).toEqual([
      [1, { category: 'Spesa alimentare', currency: 'EUR', monthlyAmount: 450 }],
    ]);
  });

  it('offers the currency only to those who have accounts in more than one', async () => {
    accounts = [{ currency: 'USD' }, { currency: 'EUR' }];
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[formControlName="currency"]',
    ) as HTMLSelectElement;

    expect(Array.from(select.options).map((o) => o.value)).toEqual(['EUR', 'USD']);
    expect(select.value).toBe('EUR');
  });

  it('offers only expense categories for a budget', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[formControlName="category"] option',
      ),
    ).map((o) => o.textContent?.trim());

    expect(options).toEqual([
      '— scegli —',
      'Spesa alimentare',
      'Bar e ristoranti',
      'Viaggi e vacanze',
    ]);
  });

  it('computes months across the year boundary', () => {
    expect(addMonths('2026-01', -1)).toBe('2025-12');
    expect(addMonths('2025-12', 1)).toBe('2026-01');
    expect(monthOf(new Date(2026, 9, 17))).toBe('2026-10');
  });
});
