import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import {
  Account,
  Category,
  CategoryChange,
  Transaction,
  TransactionDetail as Detail,
  TransactionType,
} from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { TransactionDetail, merchantSearch } from './transaction-detail';
import { TransactionsChanged } from './transactions-changed';

const CATEGORIES: Category[] = [
  { id: 1, name: 'Shopping', kind: 'EXPENSE', builtin: true, uses: 3 },
  { id: 2, name: 'Multimedia ed elettronica', kind: 'EXPENSE', builtin: true, uses: 1 },
  { id: 3, name: 'Stipendio e pensione', kind: 'INCOME', builtin: true, uses: 2 },
];

function movement(changes: Partial<Transaction> = {}): Transaction {
  return {
    id: 42,
    accountId: 2,
    status: 'BOOKED',
    bookingDate: '2026-10-24',
    valueDate: '2026-10-26',
    amount: -9.98,
    currency: 'EUR',
    counterparty: null,
    description: 'PAGAMENTO POS AMAZON.IT',
    type: 'EXPENSE',
    categoryId: 1,
    category: 'Shopping',
    manualType: null,
    manualCategoryId: 1,
    transferPeerId: null,
    note: null,
    ...changes,
  };
}

function detail(changes: Partial<Detail> = {}, transaction: Partial<Transaction> = {}): Detail {
  return {
    transaction: movement(transaction),
    rawJson: '{"remittance_information":["PAGAMENTO POS AMAZON.IT"],"status":"BOOK"}',
    importedAt: '2026-10-25T07:02:00Z',
    origin: 'BANK',
    categoryOrigin: 'MANUAL',
    ruleName: null,
    merchant: {
      key: 'PAGAMENTO POS AMAZON IT',
      name: 'PAGAMENTO POS AMAZON.IT',
      movements: 4,
      total: -78.28,
      categories: 1,
      recent: [movement({ id: 41, bookingDate: '2026-10-06', amount: -23.4 })],
    },
    suggestion: null,
    ...changes,
  };
}

describe('TransactionDetail', () => {
  let current: Detail;
  let similar: Transaction[];
  const calls: string[] = [];
  const blockChanges: CategoryChange[][] = [];
  let answer: (id: number) => Observable<Detail>;

  beforeEach(() => {
    registerLocaleData(localeIt);
    current = detail();
    similar = [];
    calls.length = 0;
    blockChanges.length = 0;
    answer = () => of(current);
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of([{ id: 2, label: 'Carta di credito' } as unknown as Account]),
      categories: () => of(CATEGORIES),
      transaction: (id: number) => answer(id),
      usedCategories: () =>
        of({ word: 'PAGAMENTO', categories: [{ categoryId: 1, category: 'Shopping', times: 3 }] }),
      correctCategory: (id: number, categoryId: number | null) => {
        calls.push(`category ${id} ${categoryId}`);
        return of(movement({ categoryId }));
      },
      similarTransactions: (id: number, categoryId: number | null) => {
        calls.push(`similar ${id} ${categoryId}`);
        return of(similar);
      },
      changeCategories: (changes: CategoryChange[]) => {
        blockChanges.push(changes);
        return of(changes.map((c) => ({ id: c.id, categoryId: 1 })));
      },
      correctType: (id: number, type: TransactionType | null) => {
        calls.push(`type ${id} ${type}`);
        return of(movement());
      },
      acceptCategorySuggestion: (key: string, categoryId: number) => {
        calls.push(`accept ${key} ${categoryId}`);
        return of({ updated: 1 });
      },
      dismissCategorySuggestion: () => of(undefined),
      changeNote: (id: number, note: string | null) => {
        calls.push(`note ${id} ${note}`);
        return of(movement({ note }));
      },
    };
    TestBed.configureTestingModule({
      imports: [TransactionDetail],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: '42' })) } },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(TransactionDetail);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
      Array.from(page.querySelectorAll('button')).find((b) =>
        b.textContent?.replace(/\s+/g, ' ').trim().startsWith(text),
      ) as HTMLButtonElement;
    return { fixture, page, button };
  }

  it('shows the movement, why it has its category, the merchant and the details', async () => {
    const { page } = await render();
    const text = page.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(page.querySelector('h1')?.textContent?.trim()).toBe('Pagamento POS Amazon.it');
    expect(page.querySelector('.detail-amount')?.textContent).toMatch(/-9,98\s€/);
    expect(text).toContain('sabato 24 ottobre 2026 · Carta di credito');
    expect(text).toContain('Scelta da te: le regole non la cambiano.');
    expect(text).toMatch(/4 movimenti nell'ultimo anno · -78,28\s€ in tutto/);
    expect(text).toContain('martedì 6 ottobre');
    expect(text).toContain('dalla banca, con la sincronizzazione');
    expect(text).toContain('Data valuta');
    expect(page.querySelector('.rule-advice a')?.getAttribute('href')).toContain(
      '/regole?pattern=',
    );
  });

  it('a category from a rule says which one', async () => {
    current = detail(
      { categoryOrigin: 'RULE', ruleName: 'Canone del conto' },
      { manualCategoryId: null },
    );
    const { page } = await render();
    expect(page.textContent).toContain('Messa dalla regola «Canone del conto».');
    expect(page.querySelector('.rule-advice')).toBeNull(); // niente da trasformare in regola
  });

  it('a merchant in several categories gets an explanation instead of the rule link', async () => {
    current = detail();
    current.merchant!.categories = 3;
    const { page } = await render();
    expect(page.querySelector('.rule-advice')?.textContent).toContain('in 3 categorie diverse');
    expect(page.querySelector('.rule-advice a')).toBeNull();
  });

  it('choosing a category proposes to move the similar ones, and undo puts everything back', async () => {
    similar = [movement({ id: 41 }), movement({ id: 40 })];
    const { fixture, page, button } = await render();

    button('Categoria').click();
    await fixture.whenStable();
    const sheet = page.querySelector('dialog.sheet') as HTMLElement;
    expect(sheet.textContent).toContain('Già usate per «PAGAMENTO…»');
    const multimedia = Array.from(sheet.querySelectorAll<HTMLButtonElement>('button.choice')).find(
      (b) => b.textContent?.includes('Multimedia'),
    )!;
    multimedia.click();
    await fixture.whenStable();

    expect(calls).toEqual(['category 42 2', 'similar 42 1']); // la vecchia categoria era Shopping (1)
    expect(page.textContent).toContain(
      'Ci sono altri 2 movimenti di questo esercente in «Shopping».',
    );

    button('Sposta anche quelli').click();
    await fixture.whenStable();
    expect(blockChanges[0]).toEqual([
      { id: 41, categoryId: 2 },
      { id: 40, categoryId: 2 },
    ]);
    expect(page.querySelector('.done')?.textContent).toContain(
      'Spostati 3 movimenti in «Multimedia',
    );

    button('Annulla').click();
    await fixture.whenStable();
    // Le scelte di prima di tutti e tre: i due simili (dalla risposta) e questo (manualCategoryId 1).
    expect(blockChanges[1]).toEqual([
      { id: 41, categoryId: 1 },
      { id: 40, categoryId: 1 },
      { id: 42, categoryId: 1 },
    ]);
    expect(page.querySelector('.done')).toBeNull();
  });

  it('"Solo questo" keeps the others, and still can be undone', async () => {
    similar = [movement({ id: 41 })];
    const { fixture, page, button } = await render();
    button('Categoria').click();
    await fixture.whenStable();
    Array.from(page.querySelectorAll<HTMLButtonElement>('button.choice'))
      .find((b) => b.textContent?.includes('Multimedia'))!
      .click();
    await fixture.whenStable();
    expect(page.textContent).toContain("C'è un altro movimento");

    button('Solo questo').click();
    await fixture.whenStable();
    expect(page.querySelector('.done')?.textContent).toContain('Spostato solo questo movimento.');
    expect(blockChanges.length).toBe(0);
  });

  it('the category list can be searched', async () => {
    const { fixture, page, button } = await render();
    button('Categoria').click();
    await fixture.whenStable();
    const search = page.querySelector('input.sheet-search') as HTMLInputElement;

    search.value = 'stip';
    search.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    const names = Array.from(page.querySelectorAll('button.choice')).map((b) =>
      b.textContent?.trim(),
    );
    expect(names).toEqual(['Stipendio e pensione', 'Togli la mia scelta (decidono le regole)']);
  });

  it('"Conta come" changes the type, or gives it back to the app', async () => {
    current = detail({}, { manualType: 'IGNORED', type: 'IGNORED' });
    const { fixture, page, button } = await render();
    expect(page.textContent).toContain('(scelto da te)');

    button('Conta come').click();
    await fixture.whenStable();
    const choices = Array.from(page.querySelectorAll<HTMLButtonElement>('button.choice'));
    expect(choices[0].textContent).toContain('Conta nelle spese e nella sua categoria');
    choices.find((b) => b.textContent?.includes('Automatico'))!.click();
    await fixture.whenStable();

    expect(calls).toEqual(['type 42 null']);
  });

  it('a suggestion can be accepted', async () => {
    current = detail(
      {
        categoryOrigin: 'NONE',
        suggestion: {
          merchantKey: 'PIZZERIA DA GINO',
          categoryId: 1,
          category: 'Bar e ristoranti',
          model: null,
        },
      },
      { category: null, categoryId: null, manualCategoryId: null },
    );
    const { fixture, page, button } = await render();
    expect(page.textContent).toContain('Suggerita: Bar e ristoranti');
    expect(page.textContent).toContain('Nessuna categoria: sceglila tu, o crea una regola.');

    button('Accetta').click();
    await fixture.whenStable();
    expect(calls).toEqual(['accept PIZZERIA DA GINO 1']);
  });

  it('the note is written here', async () => {
    const { fixture, page, button } = await render();
    button('Aggiungi una nota').click();
    await fixture.whenStable();
    const input = page.querySelector('.note-edit input') as HTMLInputElement;
    expect(input.maxLength).toBe(300);

    input.value = 'regalo per Luca';
    button('Salva').click();
    await fixture.whenStable();
    expect(calls).toEqual(['note 42 regalo per Luca']);
  });

  it('shows the original data on request, indented', async () => {
    const { fixture, page, button } = await render();
    expect(page.querySelector('pre.raw')).toBeNull();

    button('Mostra i dati originali').click();
    await fixture.whenStable();
    expect(page.querySelector('pre.raw')?.textContent).toContain('\n  "status": "BOOK"');
  });

  it('every change tells the list, and "all the movements" asks the list to search', async () => {
    const changed = TestBed.inject(TransactionsChanged);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const { fixture, button } = await render();

    button('Aggiungi una nota').click();
    await fixture.whenStable();
    button('Salva').click();
    await fixture.whenStable();
    expect(changed.version()).toBe(1);

    button('Tutti i movimenti di questo esercente').click();
    expect(changed.search()).toEqual({ text: 'pagamento pos amazon it' });
    expect(navigate).toHaveBeenCalledWith(['/movimenti']);
  });

  it('the search for the merchant keeps at most 10 whole words, the limit of the search', () => {
    // Una chiave lunga (inventata), tagliata a 60 caratteri dentro una parola: 12 parole.
    const key = 'BONIFICO ISTANTANEO DA VOI DISPOSTO N A FAVORE DI MARIO ROSSI IB';
    expect(merchantSearch(key)).toBe('bonifico istantaneo da voi disposto n a favore di mario');
    expect(merchantSearch('PAGAMENTO POS AMAZON IT')).toBe('pagamento pos amazon it');
  });

  it('says when the movement does not exist', async () => {
    answer = () => throwError(() => ({ status: 404 }));
    const { page } = await render();
    expect(page.querySelector('.error')?.textContent).toContain('Questo movimento non esiste più.');
  });
});
