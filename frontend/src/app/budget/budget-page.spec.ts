import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { Account, Budget, BudgetRequest, Category } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { addMonths, BudgetPage, monthOf, paceSentence } from './budget-page';

const CATEGORIES: Category[] = [
  { id: 1, name: 'Spesa alimentare', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 2, name: 'Bar e ristoranti', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 3, name: 'Viaggi e vacanze', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 4, name: 'Stipendio e pensione', kind: 'INCOME', builtin: true, uses: 0 },
];

const GROCERIES: Budget = {
  id: 1,
  categoryId: 1,
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
  categoryId: 3,
  category: 'Viaggi e vacanze',
  monthlyAmount: 100,
  spent: 130,
  remaining: -30,
  status: 'OVER',
};

/** Come il backend vero: nella risposta c'è anche il nome della categoria scelta. */
function nameOf(categoryId: number): string {
  return CATEGORIES.find((c) => c.id === categoryId)?.name ?? '?';
}

describe('BudgetPage', () => {
  const requestedMonths: string[] = [];
  const created: BudgetRequest[] = [];
  const updated: [number, BudgetRequest][] = [];
  let accounts: Partial<Account>[];
  let duplicate: boolean;
  let budgetList: Budget[];

  beforeEach(() => {
    registerLocaleData(localeIt);
    requestedMonths.length = 0;
    created.length = 0;
    updated.length = 0;
    accounts = [{ currency: 'EUR' }];
    duplicate = false;
    budgetList = [GROCERIES, TRAVEL];
    const fakeApi: Partial<WalletApi> = {
      budgets: (month: string) => {
        requestedMonths.push(month);
        return of(budgetList);
      },
      categories: () => of(CATEGORIES),
      accounts: () => of(accounts as Account[]),
      createBudget: (budget: BudgetRequest) => {
        created.push(budget);
        return duplicate
          ? throwError(() => ({
              error: { detail: 'Esiste già un budget per "Spesa alimentare" in EUR.' },
            }))
          : of({ ...GROCERIES, ...budget, category: nameOf(budget.categoryId), id: 3 });
      },
      updateBudget: (id: number, budget: BudgetRequest) => {
        updated.push([id, budget]);
        return of({ ...GROCERIES, ...budget, category: nameOf(budget.categoryId) });
      },
    };
    TestBed.configureTestingModule({
      imports: [BudgetPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  /** Scrive in un campo del modulo come farebbe l'utente (l'evento "input" avvisa il Reactive Form). */
  function type(page: HTMLElement, field: string, value: string): void {
    const input = page.querySelector(`[formControlName="${field}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  /**
   * Sceglie in un menu del modulo la voce con questo testo, come farebbe l'utente. Cerchiamo per testo
   * perché con [ngValue] il valore HTML di un'opzione non è l'id (Angular ci mette un suo codice).
   */
  function choose(page: HTMLElement, field: string, text: string): void {
    const select = page.querySelector(`[formControlName="${field}"]`) as HTMLSelectElement;
    select.selectedIndex = Array.from(select.options).findIndex(
      (o) => o.textContent?.trim() === text,
    );
    select.dispatchEvent(new Event('change'));
  }

  it('shows the exceeded budgets first, each with its status as icon and words and how much is left', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.budget');

    expect(requestedMonths).toEqual([monthOf(new Date())]);
    // Prima il superato (Viaggi), poi il quasi esaurito (Spesa alimentare).
    expect(cards[0].textContent).toContain('Superato');
    expect(cards[0].textContent).toMatch(/superato di 30,00\s€/);
    // La barra non va oltre il 100%, anche se la spesa sì.
    expect((cards[0].querySelector('.meter-fill') as HTMLElement).style.width).toBe('100%');

    expect(cards[1].textContent).toContain('Quasi esaurito');
    expect(cards[1].textContent).toMatch(/restano 80,00\s€/);
    expect((cards[1].querySelector('.meter-fill') as HTMLElement).style.width).toBe('80%');
    // Il segno del ritmo: 200 di 400 = a metà della barra; e la frase.
    expect((cards[1].querySelector('.meter-today') as HTMLElement).style.left).toBe('50%');
    expect(cards[1].querySelector('.pace')?.textContent).toMatch(/120\s€ sopra il ritmo di oggi/);
    // Il nome della categoria apre la sua pagina, nel mese guardato.
    expect(cards[1].querySelector('a.budget-name')?.getAttribute('href')).toBe(
      `/analisi/categoria/1?mese=${monthOf(new Date())}`,
    );
  });

  it('sums all the budgets on top, with the pace of the month', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const total = (fixture.nativeElement as HTMLElement).querySelector(
      '.budget-total',
    ) as HTMLElement;

    expect(total.querySelector('.big-figure')?.textContent).toMatch(/450\s€/);
    expect(total.textContent?.replace(/\s+/g, ' ')).toMatch(
      /su 500\s€ · 50\s€ sopra il ritmo di oggi/,
    );
  });

  it('does not add up euros and dollars', async () => {
    budgetList = [GROCERIES, { ...TRAVEL, currency: 'USD' }];
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('.budget-total')).toBeNull();
    expect(page.querySelectorAll('.budget').length).toBe(2);
  });

  it('puts the pace into words', () => {
    const euros = (v: number) => `${v} €`;
    expect(paceSentence(203, 200, euros)).toBe('In linea con il ritmo di oggi.');
    expect(paceSentence(150, 200, euros)).toBe('50 € sotto il ritmo di oggi.');
    expect(paceSentence(260, 200, euros)).toContain('60 € sopra il ritmo di oggi');
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
    // Un mese passato si giudica intero: quanti budget rispettati, e la media dei tre mesi prima.
    expect(page.querySelector('.budget-total')?.textContent).toContain('Rispettati 1 su 2.');
    expect(page.querySelector('.pace')?.textContent).toMatch(/Nei tre mesi prima, in media 150\s€/);
  });

  it('creates a budget, accepting the Italian decimal comma', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    choose(page, 'categoryId', 'Bar e ristoranti');
    type(page, 'monthlyAmount', '150,50');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(created).toEqual([
      { categoryId: 2, currency: 'EUR', monthlyAmount: 150.5 }, // Bar e ristoranti
    ]);
    expect(page.textContent).toContain('Budget "Bar e ristoranti" salvato');
  });

  it('does not send an invalid amount, and shows the backend message for a duplicate', async () => {
    const fixture = TestBed.createComponent(BudgetPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const submit = page.querySelector('button[type="submit"]') as HTMLButtonElement;

    choose(page, 'categoryId', 'Spesa alimentare');
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

    // "Modifica" nella scheda della Spesa alimentare (la seconda: prima c'è il budget superato).
    const groceries = Array.from(page.querySelectorAll('.budget')).find((card) =>
      card.textContent?.includes('Spesa alimentare'),
    ) as HTMLElement;
    (
      Array.from(groceries.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Modifica',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    type(page, 'monthlyAmount', '450');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(updated).toEqual([[1, { categoryId: 1, currency: 'EUR', monthlyAmount: 450 }]]);
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
        '[formControlName="categoryId"] option',
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
