import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { Account, ImportMapping, ImportPreview, ImportResult } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { ImportPage, MAX_FILE_BYTES } from './import-page';

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

/** La mappatura che il backend "propone" per il file del test. */
const GUESSED: ImportMapping = {
  delimiter: ';',
  dateColumn: 0,
  dateFormat: 'dd/MM/yyyy',
  amountMode: 'SINGLE',
  amountColumn: 2,
  incomeColumn: null,
  expenseColumn: null,
  decimalSeparator: 'COMMA',
  descriptionColumn: 1,
  counterpartyColumn: null,
  currencyColumn: null,
};

function previewOf(mapping: ImportMapping, errors: string[] = []): ImportPreview {
  return {
    headers: ['Data', 'Descrizione', 'Importo'],
    sampleRows: [['03/10/2026', 'NETFLIX.COM', '-12,99']],
    totalRows: 1,
    mapping,
    parsedRows:
      errors.length > 0
        ? []
        : [
            {
              date: '2026-10-03',
              amount: -12.99,
              currency: 'EUR',
              description: 'NETFLIX.COM',
              counterparty: null,
            },
          ],
    errorCount: errors.length,
    errors,
  };
}

/** Il test con un WalletApi finto (vedi accounts-page.spec.ts). */
describe('ImportPage', () => {
  let accounts: Account[];
  let previews: { accountId: number; mapping: ImportMapping | null }[];
  let imports: { accountId: number; mapping: ImportMapping }[];
  let nextPreview: (mapping: ImportMapping | null) => Observable<ImportPreview>;
  let importResponse: Observable<ImportResult>;
  let queryConto: string | null;

  beforeEach(() => {
    accounts = [
      account(1, 'ENABLE_BANKING', 'ING'),
      account(5, 'MANUAL', 'Trade Republic'),
      account(6, 'MANUAL', 'Scalable'),
    ];
    previews = [];
    imports = [];
    // Come il backend: senza mappatura propone GUESSED, con una mappatura la usa.
    nextPreview = (mapping) => of(previewOf(mapping ?? GUESSED));
    importResponse = of({ imported: 1, duplicates: 0 });
    queryConto = null;
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of(accounts),
      previewImport: (_file: File, accountId: number, mapping: ImportMapping | null) => {
        previews.push({ accountId, mapping });
        return nextPreview(mapping);
      },
      importCsv: (_file: File, accountId: number, mapping: ImportMapping) => {
        imports.push({ accountId, mapping });
        return importResponse;
      },
    };
    TestBed.configureTestingModule({
      imports: [ImportPage],
      providers: [
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
        provideRouter([]),
        // ?conto=... dell'indirizzo, letto al primo caricamento.
        {
          provide: ActivatedRoute,
          useFactory: () => ({
            snapshot: { queryParamMap: convertToParamMap(queryConto ? { conto: queryConto } : {}) },
          }),
        },
      ],
    });
  });

  async function open() {
    const fixture = TestBed.createComponent(ImportPage);
    await fixture.whenStable();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  /** Simula la scelta di un file: in jsdom `files` non si può assegnare, lo definiamo noi. */
  async function chooseFile(
    fixture: { whenStable(): Promise<unknown> },
    page: HTMLElement,
    size = 100,
  ) {
    const input = page.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['x'], 'estratto.csv', { type: 'text/csv' });
    Object.defineProperty(file, 'size', { value: size });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  function selectByLabel(page: HTMLElement, label: string): HTMLSelectElement {
    const labelElement = Array.from(page.querySelectorAll('label')).find((l) =>
      l.textContent?.trim().startsWith(label),
    );
    return labelElement?.querySelector('select') as HTMLSelectElement;
  }

  function importButton(page: HTMLElement): HTMLButtonElement {
    return Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Importa 1 riga'),
    ) as HTMLButtonElement;
  }

  it('offers only the manual accounts and selects the one in the link', async () => {
    queryConto = '6';
    const { page } = await open();

    const options = Array.from(selectByLabel(page, 'Conto').options).map((o) =>
      o.textContent?.trim(),
    );
    expect(options).toEqual(['Trade Republic · Conto 5 (EUR)', 'Scalable · Conto 6 (EUR)']);
    expect(selectByLabel(page, 'Conto').selectedOptions[0].textContent).toContain('Scalable');
  });

  it('without manual accounts it says to create one first', async () => {
    accounts = [account(1, 'ENABLE_BANKING', 'ING')];
    const { page } = await open();

    expect(page.textContent).toContain('Non hai ancora un conto manuale');
    expect(page.querySelector('input[type="file"]')).toBeNull();
  });

  it('a file gets the proposed mapping, the preview and can be imported', async () => {
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    // La prima anteprima è senza mappatura: la propone il backend.
    expect(previews).toEqual([{ accountId: 5, mapping: null }]);
    expect(selectByLabel(page, 'Data').selectedOptions[0].textContent?.trim()).toBe('1 · Data');
    expect(selectByLabel(page, "Colonna dell'importo").selectedOptions[0].textContent?.trim()).toBe(
      '3 · Importo',
    );
    expect(page.textContent).toContain('NETFLIX.COM');
    expect(page.textContent).toContain('-12,99 €');

    importButton(page).click();
    await fixture.whenStable();

    expect(imports).toEqual([{ accountId: 5, mapping: GUESSED }]);
    expect(page.textContent).toContain('1 movimento importato');
  });

  it('changing a menu asks for a new preview with that mapping', async () => {
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    const description = selectByLabel(page, 'Descrizione');
    description.selectedIndex = 0; // "— nessuna —"
    description.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(previews).toHaveLength(2);
    expect(previews[1].mapping).toEqual({ ...GUESSED, descriptionColumn: null });
  });

  it('with unreadable rows the errors are shown and the import is disabled', async () => {
    nextPreview = (mapping) =>
      of(previewOf(mapping ?? GUESSED, ['Riga 2: l\'importo "dodici" non è un numero.']));
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(page.textContent).toContain('1 riga non si legge');
    expect(page.textContent).toContain('Riga 2: l\'importo "dodici" non è un numero.');
    expect(importButton(page).disabled).toBe(true);
  });

  it('a file too big is refused without sending it', async () => {
    const { fixture, page } = await open();
    await chooseFile(fixture, page, MAX_FILE_BYTES + 1);

    expect(previews).toEqual([]);
    expect(page.textContent).toContain('Il file è troppo grande');
  });

  it('shows the reason when the backend rejects the import', async () => {
    importResponse = throwError(() => ({
      error: { detail: '1 righe non si possono leggere.', errors: ['Riga 9: manca la data.'] },
    }));
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    importButton(page).click();
    await fixture.whenStable();

    expect(page.querySelector('.error')?.textContent).toContain('Riga 9: manca la data.');
    expect(page.textContent).not.toContain('Importazione completata');
  });
});
