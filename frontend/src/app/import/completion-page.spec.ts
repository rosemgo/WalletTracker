import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { Account, CompletionPreview, CompletionResult } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CompletionPage } from './completion-page';

registerLocaleData(localeIt);

function account(id: number, source: Account['source'], bank: string): Account {
  return {
    id,
    label: `${bank} · Conto ${id}`,
    displayName: null,
    bank,
    name: `Conto ${id}`,
    iban: null,
    currency: 'EUR',
    role: 'UNASSIGNED',
    source,
    consentValidUntil: null,
    lastSyncAt: null,
    lastSyncError: null,
  };
}

const PREVIEW: CompletionPreview = {
  from: '2026-05-04',
  to: '2026-07-20',
  bankFrom: '2026-07-01',
  fileRows: 3,
  skippedRows: 1,
  toComplete: [
    {
      transactionId: 7,
      date: '2026-07-01',
      amount: 7.4,
      currency: 'EUR',
      counterparty: null,
      description: 'Interest payment',
    },
    {
      transactionId: 8,
      date: '2026-07-07',
      amount: -150,
      currency: 'EUR',
      counterparty: 'Mario Rossi',
      description: 'Outgoing transfer for Mario Rossi',
    },
  ],
  toAdd: [
    {
      date: '2026-05-04',
      amount: -1003,
      currency: 'EUR',
      counterparty: 'Beta SpA',
      description: 'Buy trade IT0000000002 BETA SPA',
    },
  ],
  alreadyComplete: 0,
  rowsWithoutMovement: [
    { line: 9, date: '2026-07-20', amount: 50, currency: 'EUR', description: 'Cash Dividend' },
  ],
  movementsWithoutRow: [],
};

/** La pagina con un WalletApi finto (vedi import-page.spec.ts). */
describe('CompletionPage', () => {
  let previews: number[];
  let completions: number[];
  let previewResponse: Observable<CompletionPreview>;
  let completeResponse: Observable<CompletionResult>;

  beforeEach(() => {
    previews = [];
    completions = [];
    previewResponse = of(PREVIEW);
    completeResponse = of({ completed: 2, added: 1, alreadyComplete: 0 });
    const fakeApi: Partial<WalletApi> = {
      accounts: () =>
        of([
          account(1, 'ENABLE_BANKING', 'ING'),
          account(2, 'ENABLE_BANKING', 'Trade Republic'),
          account(3, 'MANUAL', 'Scalable'),
        ]),
      previewCompletion: (_file: File, accountId: number) => {
        previews.push(accountId);
        return previewResponse;
      },
      completeTransactions: (_file: File, accountId: number) => {
        completions.push(accountId);
        return completeResponse;
      },
    };
    TestBed.configureTestingModule({
      imports: [CompletionPage],
      providers: [
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
  });

  async function openWithFile() {
    const fixture = TestBed.createComponent(CompletionPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const input = page.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['x'], 'transactions.csv', { type: 'text/csv' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    return { fixture, page };
  }

  function text(page: HTMLElement): string {
    return page.textContent!.replace(/\s+/g, ' ');
  }

  it('offers only linked accounts, Trade Republic first, and shows the preview', async () => {
    const { page } = await openWithFile();

    const options = Array.from(page.querySelectorAll('select option')).map((o) => o.textContent);
    expect(options).toEqual(['ING · Conto 1 (EUR)', 'Trade Republic · Conto 2 (EUR)']);
    expect(previews).toEqual([2]); // scelto da solo: è Trade Republic
    expect(text(page)).toContain(
      'Il file va dal 04/05/2026 al 20/07/2026: 3 movimenti di denaro (e 1 operazione sui titoli, che non muove denaro e viene saltata).',
    );
    expect(text(page)).toContain('2 movimenti riceveranno controparte e causale');
    expect(text(page)).toContain('1 riga del file non trova un movimento e non viene aggiunta');
    expect(text(page)).toContain(
      '1 movimento verrà aggiunto: sono più vecchi del 01/07/2026, il primo giorno che la banca ha dato',
    );
    expect(text(page)).toContain('Buy trade IT0000000002 BETA SPA');
    expect(text(page)).toContain('Outgoing transfer for Mario Rossi');
  });

  it('completes and says what happened', async () => {
    const { fixture, page } = await openWithFile();

    const button = Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Completa 2 movimenti e aggiungine 1'),
    ) as HTMLButtonElement;
    button.click();
    await fixture.whenStable();

    expect(completions).toEqual([2]);
    expect(text(page)).toContain('2 movimenti completati, 1 aggiunto.');
  });

  it('shows why a file is refused', async () => {
    previewResponse = throwError(() => ({
      error: { detail: "Il file non è un'esportazione delle transazioni di Trade Republic." },
    }));

    const { page } = await openWithFile();

    expect(page.querySelector('.error')?.textContent).toContain("non è un'esportazione");
  });
});
