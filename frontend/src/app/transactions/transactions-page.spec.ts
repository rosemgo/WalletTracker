import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';

import { Account, Category, Transaction, TransactionFilter, TransactionPage } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { isoDate } from './period';
import { TransactionsChanged } from './transactions-changed';
import { TransactionsPage, isoDateParam } from './transactions-page';

/** Un movimento inventato; `changes` cambia solo quello che serve al test. */
function movement(id: number, changes: Partial<Transaction> = {}): Transaction {
  return {
    id,
    accountId: 2,
    status: 'BOOKED',
    bookingDate: isoDate(new Date()),
    valueDate: null,
    amount: -10,
    currency: 'EUR',
    counterparty: null,
    description: `NEGOZIO ${id}`,
    type: 'EXPENSE',
    categoryId: 1,
    category: 'Shopping',
    manualType: null,
    manualCategoryId: null,
    transferPeerId: null,
    note: null,
    ...changes,
  };
}

const today = isoDate(new Date());
const yesterday = isoDate(new Date(Date.now() - 86_400_000));

describe('TransactionsPage', () => {
  const requests: TransactionFilter[] = [];
  let items: Transaction[];
  let total: number | null;
  let respond: ((filter: TransactionFilter) => Observable<TransactionPage>) | null;
  let routeParams: Record<string, string>;

  beforeEach(() => {
    requests.length = 0;
    items = [
      movement(1, { description: 'ESSELUNGA MILANO', amount: -56.3, category: 'Spesa alimentare' }),
      movement(2, {
        description: 'GIROCONTO',
        amount: -500,
        type: 'INTERNAL_TRANSFER',
        category: null,
      }),
      movement(3, {
        bookingDate: yesterday,
        counterparty: 'ACME SRL',
        amount: 2100,
        type: 'INCOME',
        category: 'Stipendio e pensione',
        note: 'ottobre',
      }),
      movement(4, { bookingDate: yesterday, amount: -12.99, status: 'PENDING', category: null }),
    ];
    total = null;
    respond = null;
    routeParams = {};
    registerLocaleData(localeIt);
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of([{ id: 2, label: 'Conto corrente' } as unknown as Account]),
      transactions: (filter: TransactionFilter) => {
        requests.push(filter);
        if (respond) {
          return respond(filter);
        }
        const start = filter.page * filter.size;
        return of({
          items: items.slice(start, start + filter.size),
          page: filter.page,
          size: filter.size,
          totalItems: total ?? items.length,
          totalPages: 1,
        });
      },
      categorySuggestions: () => of([]),
      categories: () =>
        of([{ id: 5, name: 'Shopping', kind: 'EXPENSE', builtin: true, uses: 1 } as Category]),
      aiSuggestionsStatus: () => of({ enabled: false, model: 'gemma3:4b', toAsk: 0, job: null }),
    };
    TestBed.configureTestingModule({
      imports: [TransactionsPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
        {
          provide: ActivatedRoute,
          useFactory: () => ({ snapshot: { queryParamMap: convertToParamMap(routeParams) } }),
        },
      ],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(TransactionsPage);
    await fixture.whenStable();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  const pill = (page: HTMLElement, text: string) =>
    Array.from(page.querySelectorAll<HTMLButtonElement>('button.pill')).find(
      (b) => b.textContent?.trim() === text,
    )!;

  it('groups the movements by day, with the total of real expenses and income', async () => {
    const { page } = await render();
    const days = Array.from(page.querySelectorAll('section.day'));

    expect(days.map((d) => d.querySelector('h2')?.textContent?.trim())).toEqual([
      expect.stringMatching(/^Oggi · /),
      expect.stringMatching(/^Ieri · /),
    ]);
    // Oggi: solo la spesa (il giroconto non conta nel totale); ieri: 2.100 − 12,99.
    expect(days[0].querySelector('.day-head span')?.textContent).toMatch(/-56,30\s€/);
    expect(days[1].querySelector('.day-head span')?.textContent).toMatch(/2\.087,01\s€/);
  });

  it('shows each movement with name, category or type, account, note and amount', async () => {
    const { page } = await render();
    const rows = Array.from(page.querySelectorAll('a.movement')).map((a) =>
      a.textContent?.replace(/\s+/g, ' ').trim(),
    );

    // Prima l'iniziale nel cerchio colorato; il nome della banca, tutto maiuscolo, diventa leggibile (docs/41).
    expect(rows[0]).toMatch(
      /^E\s*Esselunga Milano\s*Spesa alimentare · Conto corrente\s*-56,30\s€$/,
    );
    expect(rows[1]).toContain('trasferimento interno · Conto corrente'); // senza categoria: il tipo
    expect(rows[2]).toMatch(/Acme SRL\s*Stipendio e pensione · Conto corrente\s*ottobre/);
    expect(rows[3]).toContain('senza categoria · Conto corrente · in attesa');
    // Un'entrata ha il colore delle entrate (il link al dettaglio lo prova il browser: docs/37, parte H).
    expect(page.querySelectorAll('a.movement .amount.positive').length).toBe(1);
  });

  it('the pills switch the filters on and off, from the first page', async () => {
    const { fixture, page } = await render();

    pill(page, 'Da verificare').click();
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ type: 'TO_REVIEW', page: 0 });
    expect(pill(page, 'Da verificare').getAttribute('aria-pressed')).toBe('true');
    expect(pill(page, 'Da verificare').classList.contains('on')).toBe(true);

    pill(page, 'Da verificare').click();
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ type: null });

    pill(page, 'Senza categoria').click();
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ uncategorized: true, page: 0 });
  });

  it('a period from the menu sets both dates; "Scegli le date" shows the calendar', async () => {
    const { fixture, page } = await render();
    const period = page.querySelector('select[aria-label="Periodo"]') as HTMLSelectElement;
    const year = new Date().getFullYear();

    period.value = 'THIS_YEAR';
    period.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ from: `${year}-01-01`, to: `${year}-12-31` });
    expect(page.querySelector('.dates')).toBeNull();

    period.value = 'CUSTOM';
    period.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const [from, to] = Array.from(
      page.querySelectorAll<HTMLInputElement>('.dates input[type="date"]'),
    );
    expect(from.value).toBe(`${year}-01-01`); // le date del periodo di prima, da correggere
    to.value = `${year}-03-31`;
    to.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ from: `${year}-01-01`, to: `${year}-03-31` });

    // Una data di inizio dopo quella di fine non parte, e si spiega.
    const sent = requests.length;
    from.value = `${year}-12-01`;
    from.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(requests.length).toBe(sent);
    expect(page.querySelector('.error')?.textContent).toContain('dopo quella di fine');
  });

  it('searches when the user stops typing', async () => {
    vi.useFakeTimers();
    try {
      const fixture = TestBed.createComponent(TransactionsPage);
      fixture.detectChanges();
      const search = (fixture.nativeElement as HTMLElement).querySelector(
        'input[type="search"]',
      ) as HTMLInputElement;
      const before = requests.length;

      for (const value of ['n', 'net', 'netflix']) {
        search.value = value;
        search.dispatchEvent(new Event('input'));
        vi.advanceTimersByTime(100); // si scrive più in fretta dei 300 ms
      }
      expect(requests.length).toBe(before);
      vi.advanceTimersByTime(300);

      expect(requests.length).toBe(before + 1); // una richiesta sola, con l'ultimo testo
      expect(requests.at(-1)).toMatchObject({ text: 'netflix', page: 0 });
    } finally {
      vi.useRealTimers();
    }
  });

  it('only the answer to the last request is shown, and an empty search says so', async () => {
    const answers: Subject<TransactionPage>[] = [];
    respond = () => {
      const answer = new Subject<TransactionPage>();
      answers.push(answer);
      return answer;
    };
    const { fixture, page } = await render();
    const result = (n: number, list: Transaction[]): TransactionPage => ({
      items: list,
      page: 0,
      size: 50,
      totalItems: list.length,
      totalPages: 1,
    });

    pill(page, 'Senza categoria').click(); // richiesta 2 (la 1 è quella dell'apertura)
    pill(page, 'Senza categoria').click(); // richiesta 3
    answers[2].next(result(3, [movement(30, { description: 'RISULTATO 3' })]));
    answers[1].next(result(2, [movement(20, { description: 'RISULTATO 2' })])); // arriva tardi: ignorata
    answers[0].next(result(1, []));
    await fixture.whenStable();
    expect(page.textContent).toContain('Risultato 3');
    expect(page.textContent).not.toContain('RISULTATO 2');
  });

  it('says when nothing is found', async () => {
    items = [];
    routeParams = { text: 'spotify' };
    const { page } = await render();

    expect(page.textContent).toContain('Nessun movimento trovato per "spotify" con questi filtri.');
  });

  it('"Mostra altri" adds the next page at the bottom', async () => {
    items = Array.from({ length: 70 }, (_, i) => movement(i + 1));
    const { fixture, page } = await render();
    expect(page.querySelectorAll('a.movement').length).toBe(50);
    expect(page.querySelector('.count')?.textContent).toContain('50 di 70 movimenti');

    (page.querySelector('button.more') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(requests.at(-1)).toMatchObject({ page: 1, size: 50 });
    expect(page.querySelectorAll('a.movement').length).toBe(70);
    expect(page.querySelector('button.more')).toBeNull();
  });

  it('a type or a text in the address are the starting filters', async () => {
    routeParams = { type: 'TO_REVIEW', text: 'amazon' };
    const { page } = await render();
    expect(requests[0]).toMatchObject({ type: 'TO_REVIEW', text: 'amazon' });
    expect((page.querySelector('input[type="search"]') as HTMLInputElement).value).toBe('amazon');
    expect(pill(page, 'Da verificare').classList.contains('on')).toBe(true);
  });

  it('from Analisi: without category, of a type, in the dates of a bar', async () => {
    routeParams = { uncategorized: 'true', type: 'EXPENSE', from: '2026-09-01', to: '2026-09-30' };
    const { page } = await render();
    expect(requests[0]).toMatchObject({
      uncategorized: true,
      type: 'EXPENSE',
      from: '2026-09-01',
      to: '2026-09-30',
    });
    expect(pill(page, 'Senza categoria').classList.contains('on')).toBe(true);
    expect((page.querySelector('input[type="date"]') as HTMLInputElement).value).toBe('2026-09-01');
  });

  it('dates in the address that are not real dates are ignored', () => {
    expect(isoDateParam('2026-09-30')).toBe('2026-09-30');
    expect(isoDateParam('2026-02-30')).toBeNull(); // il 30 febbraio non esiste
    expect(isoDateParam('30/09/2026')).toBeNull();
    expect(isoDateParam(null)).toBeNull();
  });

  it('an unknown type in the address is ignored', async () => {
    routeParams = { type: 'BOH' };
    await render();
    expect(requests[0].type).toBeNull();
  });

  it('reloads the loaded movements when the detail changes something, and searches when asked', async () => {
    items = Array.from({ length: 70 }, (_, i) => movement(i + 1));
    const { fixture, page } = await render();
    (page.querySelector('button.more') as HTMLButtonElement).click();
    await fixture.whenStable();

    TestBed.inject(TransactionsChanged).notify();
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ page: 0, size: 70 }); // tutti quelli già caricati

    TestBed.inject(TransactionsChanged).searchFor('amazon');
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ text: 'amazon', page: 0, size: 50 });
    expect((page.querySelector('input[type="search"]') as HTMLInputElement).value).toBe('amazon');
  });

  it('a category in the address (from the page of a category) is a pill that can be removed', async () => {
    routeParams = { categoryId: '5' };
    const { fixture, page } = await render();
    expect(requests[0]).toMatchObject({ categoryId: 5 });
    const chip = page.querySelector('button.pill.on') as HTMLButtonElement;
    expect(chip.textContent).toContain('Shopping');

    chip.click();
    await fixture.whenStable();
    expect(requests.at(-1)).toMatchObject({ categoryId: null, page: 0 });
    expect(page.textContent).not.toContain('Shopping ✕');
  });
});
