import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { Account, ImportMapping, ImportPreview, ImportResult, Transaction } from '../api/models';
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
    connectionRemovedAt: null,
    lastSyncAt: null,
    lastSyncError: null,
    balance: null,
    availableCredit: null,
    logoVersion: null,
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
  signColumn: null,
  incomeWords: null,
  detailColumn: null,
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
    spreadsheet: false,
    linkedAccount: false,
    bankFrom: null,
    newRows: errors.length > 0 ? 0 : 1,
    duplicateRows: 0,
    coveredRows: 0,
    tradeRepublic: false,
    skippedRows: 0,
    completeRows: 0,
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
  let balances: { accountId: number; balance: number | null }[];

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
    importResponse = of({ imported: 1, duplicates: 0, completed: 0, covered: 0 });
    queryConto = null;
    balances = [];
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of(accounts),
      // L'ultimo movimento del conto, per scrivere il saldo (docs/saldi.md).
      transactions: () =>
        of({
          items: [
            {
              id: 70,
              bookingDate: '2026-10-03',
              amount: -12.99,
              currency: 'EUR',
              description: 'NETFLIX.COM',
            } as unknown as Transaction,
          ],
          page: 0,
          size: 1,
          totalItems: 1,
          totalPages: 1,
        }),
      changeBalance: (accountId: number, balance: number | null) => {
        balances.push({ accountId, balance });
        const updated = accounts.find((a) => a.id === accountId)!;
        return of({
          ...updated,
          balance: {
            amount: balance!,
            date: '2026-10-03',
            source: 'MANUAL',
            startDate: '2026-10-03',
          },
        } as Account);
      },
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

  it('offers the manual and the linked accounts in two groups, and selects the one in the link', async () => {
    queryConto = '6';
    const { page } = await open();

    const groups = Array.from(selectByLabel(page, 'Conto').querySelectorAll('optgroup')).map(
      (g) => ({
        label: g.label,
        options: Array.from(g.querySelectorAll('option')).map((o) => o.textContent?.trim()),
      }),
    );
    expect(groups).toEqual([
      {
        label: 'Conti manuali (non collegati a una banca)',
        options: ['Trade Republic · Conto 5 (EUR)', 'Scalable · Conto 6 (EUR)'],
      },
      { label: 'Conti collegati con Enable Banking', options: ['ING · Conto 1 (EUR)'] },
    ]);
    expect(selectByLabel(page, 'Conto').selectedOptions[0].textContent).toContain('Scalable');
    // Prima di scegliere il file la pagina dice che cosa farà con questo conto.
    expect(page.querySelector('[data-testid="import-mode"]')?.textContent).toContain(
      'Importa i movimenti di un conto manuale',
    );
  });

  it('with Trade Republic only as a manual account there is no "Completa i movimenti"', async () => {
    const { page } = await open();

    expect(page.querySelector('[data-testid="trade-republic-hint"]')).toBeNull();
  });

  it('with a linked Trade Republic account chosen it points also to "Completa i movimenti"', async () => {
    accounts = [...accounts, account(7, 'ENABLE_BANKING', 'Trade Republic Bank GmbH')];
    queryConto = '7';
    const { page } = await open();

    const hint = page.querySelector('[data-testid="trade-republic-hint"]');
    expect(hint?.querySelector('a')?.getAttribute('href')).toBe('/importa/completa');
  });

  it('without any account it says to connect a bank or create a manual account', async () => {
    accounts = [];
    const { page } = await open();

    expect(page.textContent).toContain('Non hai ancora nessun conto');
    expect(page.querySelector('input[type="file"]')).toBeNull();
  });

  it('a linked account adds only the months before the bank, and says so', async () => {
    queryConto = '1';
    nextPreview = (mapping) =>
      of({
        ...previewOf(mapping ?? GUESSED),
        totalRows: 5,
        linkedAccount: true,
        bankFrom: '2026-07-22',
        newRows: 2,
        coveredRows: 3,
      });
    importResponse = of({ imported: 2, duplicates: 0, completed: 0, covered: 3 });
    const { fixture, page } = await open();

    const mode = page
      .querySelector('[data-testid="import-mode"]')
      ?.textContent?.replace(/\s+/g, ' ');
    expect(mode).toContain('Completa lo storico di un conto collegato');
    expect(mode).toContain('solo i movimenti più vecchi');

    await chooseFile(fixture, page);

    expect(
      page.querySelector('[data-testid="import-mode"]')?.textContent?.replace(/\s+/g, ' '),
    ).toContain('(il 22 luglio 2026)');
    expect(page.querySelector('[data-testid="import-summary"]')?.textContent?.trim()).toBe(
      'Il file ha 5 righe: 2 movimenti più vecchi del 22 luglio 2026 si aggiungono; ' +
        '3 sono nel periodo che porta la banca.',
    );
    const button = Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Aggiungi 2 movimenti'),
    ) as HTMLButtonElement;
    button.click();
    await fixture.whenStable();

    expect(imports.map((i) => i.accountId)).toEqual([1]);
    expect(page.textContent).toContain(
      '2 movimenti aggiunti; 3 righe erano nel periodo che porta la banca e sono state saltate.',
    );
    // Il saldo di un conto collegato lo dà la banca: niente da scrivere.
    expect(page.querySelector('.balance-check')).toBeNull();
  });

  it('a linked account with nothing older in the file cannot import, and tells why', async () => {
    queryConto = '1';
    nextPreview = (mapping) =>
      of({
        ...previewOf(mapping ?? GUESSED),
        linkedAccount: true,
        bankFrom: '2026-07-22',
        newRows: 0,
        coveredRows: 1,
      });
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(page.querySelector('[data-testid="import-summary"]')?.textContent?.trim()).toBe(
      'Il file ha 1 riga: nessun movimento da aggiungere; 1 è nel periodo che porta la banca. ' +
        'La banca ha già tutti i mesi di questo file: dal 22 luglio 2026 in poi li porta lei.',
    );
    const button = Array.from(page.querySelectorAll('.toolbar button'))[0] as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Niente da aggiungere');
    expect(button.disabled).toBe(true);
  });

  it('the same file a second time: the older months were already added', async () => {
    queryConto = '1';
    nextPreview = (mapping) =>
      of({
        ...previewOf(mapping ?? GUESSED),
        totalRows: 11,
        linkedAccount: true,
        bankFrom: '2026-07-22',
        newRows: 0,
        coveredRows: 5,
        duplicateRows: 6,
      });
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(page.querySelector('[data-testid="import-summary"]')?.textContent?.trim()).toBe(
      'Il file ha 11 righe: nessun movimento da aggiungere; 5 sono nel periodo che porta la banca; ' +
        '6 erano già state aggiunte. I mesi più vecchi di questo file li hai già aggiunti; gli altri li ' +
        'porta la banca.',
    );
  });

  it('the Trade Republic file is read without a mapping, and the securities rows are skipped', async () => {
    nextPreview = (mapping) =>
      of({
        ...previewOf(mapping ?? GUESSED),
        totalRows: 92,
        newRows: 85,
        tradeRepublic: true,
        skippedRows: 7,
      });
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    // Niente menu della mappatura: la pagina spiega come legge il file.
    expect(selectByLabel(page, 'Data')).toBeUndefined();
    const card = page
      .querySelector('[data-testid="trade-republic-file"]')
      ?.textContent?.replace(/\s+/g, ' ');
    expect(card).toContain('quello che vedi sul conto');
    expect(card).toContain('7 righe sono operazioni sui titoli');
    expect(page.querySelector('[data-testid="import-summary"]')?.textContent?.trim()).toBe(
      '85 righe nuove; 7 sono operazioni sui titoli senza denaro, che si saltano.',
    );
    const button = Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Importa 85 righe'),
    ) as HTMLButtonElement;
    button.click();
    await fixture.whenStable();

    expect(imports.map((i) => i.accountId)).toEqual([5]);
  });

  it('the Trade Republic file in a linked account completes the movements of the bank too', async () => {
    accounts = [...accounts, account(7, 'ENABLE_BANKING', 'Trade Republic Bank GmbH')];
    queryConto = '7';
    nextPreview = (mapping) =>
      of({
        ...previewOf(mapping ?? GUESSED),
        totalRows: 92,
        linkedAccount: true,
        bankFrom: '2026-07-09',
        newRows: 0,
        completeRows: 4,
        duplicateRows: 81,
        tradeRepublic: true,
        skippedRows: 7,
      });
    importResponse = of({ imported: 0, duplicates: 81, completed: 4, covered: 0 });
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(page.querySelector('[data-testid="trade-republic-complete"]')?.textContent).toContain(
      'controparte e causale',
    );
    expect(page.querySelector('[data-testid="import-summary"]')?.textContent?.trim()).toBe(
      'Il file ha 92 righe: nessun movimento più vecchio del 9 luglio 2026 da aggiungere; ' +
        '4 movimenti arrivati dalla banca ricevono controparte e causale; 81 erano già fatte; ' +
        '7 sono operazioni sui titoli senza denaro, che si saltano.',
    );
    const button = Array.from(page.querySelectorAll('.toolbar button'))[0] as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Completa 4 movimenti');
    expect(button.disabled).toBe(false);
    button.click();
    await fixture.whenStable();

    expect(imports.map((i) => i.accountId)).toEqual([7]);
    expect(page.textContent).toContain(
      'a 4 movimenti già presenti è stata completata la descrizione',
    );
  });

  it('an Excel file has no separator to choose', async () => {
    nextPreview = (mapping) => of({ ...previewOf(mapping ?? GUESSED), spreadsheet: true });
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(selectByLabel(page, 'Separatore')).toBeUndefined();
    expect(selectByLabel(page, 'Data')).toBeTruthy();
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

    // Il conto non ha ancora un saldo: si chiede subito, mostrando l'ultimo movimento da cercare nella banca.
    const check = page.querySelector('.balance-check') as HTMLElement;
    expect(check.textContent?.replace(/\s+/g, ' ')).toContain(
      '3 ottobre 2026 · Netflix.com · -12,99',
    );
    const input = check.querySelector('input') as HTMLInputElement;
    input.value = '1.234,56';
    (
      Array.from(check.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Salva il saldo'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(balances).toEqual([{ accountId: 5, balance: 1234.56 }]);
    expect(check.textContent?.replace(/\s+/g, ' ')).toMatch(/1\.234,56\s€ al 3 ottobre 2026/);
    expect(check.textContent).toContain('Salvato');
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

  it('amounts without a sign: the column and the words of the income are shown and sent', async () => {
    // Come il file di Tinaba (docs/tinaba.md): il backend propone "senza segno" e le parole delle entrate.
    const unsigned: ImportMapping = {
      ...GUESSED,
      amountMode: 'UNSIGNED',
      signColumn: 1,
      incomeWords: 'ricevuto, ricevuta',
    };
    nextPreview = (mapping) => of(previewOf(mapping ?? unsigned));
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(selectByLabel(page, 'Importo').selectedOptions[0].textContent).toContain('senza segno');
    expect(selectByLabel(page, 'Cerca le parole').selectedOptions[0].textContent?.trim()).toBe(
      '2 · Descrizione',
    );
    const words = page.querySelector('[formControlName="incomeWords"]') as HTMLInputElement;
    expect(words.value).toBe('ricevuto, ricevuta');

    words.value = 'ricevuto, accredito';
    words.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(previews.at(-1)?.mapping).toEqual({ ...unsigned, incomeWords: 'ricevuto, accredito' });
  });

  it('a second text column is proposed, sent, and the completed descriptions are told', async () => {
    // Tinaba (docs/tinaba.md, parte H): "Causale/Messaggio" si aggiunge alla descrizione.
    const withDetail: ImportMapping = { ...GUESSED, detailColumn: 1 };
    nextPreview = (mapping) => of(previewOf(mapping ?? withDetail));
    importResponse = of({ imported: 0, duplicates: 2, completed: 1, covered: 0 });
    const { fixture, page } = await open();
    await chooseFile(fixture, page);

    expect(selectByLabel(page, 'Altro testo').selectedOptions[0].textContent?.trim()).toBe(
      '2 · Descrizione',
    );
    importButton(page).click();
    await fixture.whenStable();

    expect(imports.at(-1)?.mapping).toEqual(withDetail);
    expect(page.textContent).toContain('2 righe erano già presenti e sono state saltate');
    expect(page.textContent).toContain(
      'a 1 movimento già presente è stata completata la descrizione',
    );
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
