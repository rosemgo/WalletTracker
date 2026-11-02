import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Account, AccountRole, ManualAccountRequest, SyncRound, Transaction } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AccountsPage } from './accounts-page';
import { parseBalance } from './balance-form';

const CARD: Account = {
  id: 3,
  label: 'ING · Carta di credito',
  displayName: null,
  bank: 'ING',
  name: 'Carta di credito',
  iban: null,
  currency: 'EUR',
  role: 'SPENDING',
  source: 'ENABLE_BANKING',
  consentValidUntil: '2027-01-05T14:35:51Z',
  lastSyncAt: null,
  lastSyncError: 'HTTP 429 troppe letture',
  balance: null,
  availableCredit: null,
};

/** Un conto manuale: i movimenti arrivano da file CSV. */
const BROKER: Account = {
  id: 8,
  label: 'Trade Republic · Conto titoli',
  displayName: null,
  bank: 'Trade Republic',
  name: 'Conto titoli',
  iban: null,
  currency: 'EUR',
  role: 'INVESTMENT',
  source: 'MANUAL',
  consentValidUntil: null,
  lastSyncAt: null,
  lastSyncError: null,
  balance: null,
  availableCredit: null,
};

/**
 * Test del componente con un WalletApi FINTO: al posto del servizio vero ne diamo uno scritto da noi
 * ({ provide: WalletApi, useValue: ... }), che risponde subito con dati preparati. È l'idea di
 * @MockitoBean nei test Spring.
 */
describe('AccountsPage', () => {
  const changedRoles: [number, AccountRole][] = [];
  const created: ManualAccountRequest[] = [];
  const deleted: number[] = [];
  const renamed: [number, string][] = [];
  const balances: [number, number | null][] = [];
  let syncCalls = 0;
  let accounts: Account[];

  beforeEach(() => {
    changedRoles.length = 0;
    created.length = 0;
    deleted.length = 0;
    renamed.length = 0;
    balances.length = 0;
    syncCalls = 0;
    accounts = [CARD];
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of(accounts),
      createManualAccount: (request: ManualAccountRequest) => {
        created.push(request);
        return of({
          ...BROKER,
          bank: request.bankName,
          name: request.name,
          label: `${request.bankName} · ${request.name}`,
        });
      },
      renameAccount: (id: number, displayName: string) => {
        renamed.push([id, displayName]);
        return of({
          ...CARD,
          displayName: displayName === '' ? null : displayName,
          label: displayName === '' ? CARD.label : displayName,
        });
      },
      deleteAccount: (id: number) => {
        deleted.push(id);
        return of(undefined);
      },
      changeRole: (id: number, role: AccountRole) => {
        changedRoles.push([id, role]);
        return of({ ...CARD, role });
      },
      changeBalance: (id: number, balance: number | null) => {
        balances.push([id, balance]);
        return of({
          ...BROKER,
          balance: {
            amount: balance!,
            date: '2026-10-28',
            source: 'MANUAL',
            startDate: '2026-10-28',
          },
        } as Account);
      },
      transactions: () =>
        of({
          items: [
            {
              id: 9,
              bookingDate: '2026-10-28',
              amount: 200,
              currency: 'EUR',
              description: 'Ricevuto da Mario Rossi',
            } as unknown as Transaction,
          ],
          page: 0,
          size: 1,
          totalItems: 1,
          totalPages: 1,
        }),
      syncNow: () => {
        syncCalls++;
        return of<SyncRound>({ updated: 2, failed: 1, notDue: 0 });
      },
    };
    TestBed.configureTestingModule({
      imports: [AccountsPage],
      providers: [{ provide: WalletApi, useValue: fakeApi }, provideRouter([])],
    });
  });

  it('shows the balance of the bank, and lets you write the one of a manual account', async () => {
    registerLocaleData(localeIt);
    TestBed.overrideProvider(LOCALE_ID, { useValue: 'it-IT' });
    accounts = [
      {
        ...CARD,
        balance: { amount: 1234.56, date: '2026-10-31', source: 'BANK', startDate: null },
      },
      BROKER,
    ];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const cells = Array.from(page.querySelectorAll('td.balance-cell')) as HTMLElement[];
    const text = (cell: HTMLElement) => cell.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(text(cells[0])).toMatch(/1\.234,56\s€ al 31 ott 2026 · dalla banca/);
    expect(cells[0].querySelector('button')).toBeNull(); // il saldo della banca non si scrive a mano
    expect(text(cells[1])).toContain('non ancora scritto');

    (cells[1].querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    // L'ultimo movimento importato, da cercare nell'app della banca.
    expect(text(cells[1])).toContain('28 ottobre 2026 · Ricevuto da Mario Rossi');
    const input = cells[1].querySelector('input') as HTMLInputElement;
    input.value = 'dodici';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();
    expect(text(cells[1])).toContain('Scrivi un importo');
    expect(balances).toEqual([]);

    input.value = '350,00';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();
    expect(balances).toEqual([[8, 350]]);
    expect(text(page.querySelectorAll('td.balance-cell')[1] as HTMLElement)).toMatch(
      /350,00\s€ al 28 ott 2026 · scritto da te/,
    );
  });

  it('a credit card shows the available credit, apart from the balance', async () => {
    registerLocaleData(localeIt);
    TestBed.overrideProvider(LOCALE_ID, { useValue: 'it-IT' });
    accounts = [
      {
        ...CARD,
        role: 'CREDIT_CARD',
        lastSyncAt: '2026-11-02T16:57:00Z',
        availableCredit: { amount: 2500, date: '2026-11-02' },
      },
    ];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const cell = (fixture.nativeElement as HTMLElement).querySelector(
      'td.balance-cell',
    ) as HTMLElement;
    const text = cell.textContent?.replace(/\s+/g, ' ') ?? '';

    // Il saldo non c'è (la banca dà solo il disponibile); il plafond sì, con il suo nome.
    expect(text).toContain('la banca non lo dà');
    expect(cell.querySelector('.available-credit')?.textContent?.replace(/\s+/g, ' ')).toMatch(
      /Plafond disponibile\s*2\.500,00\s€\s*al 2 nov 2026 · dalla banca/,
    );
  });

  it('reads the amounts written the Italian or the English way', () => {
    expect(parseBalance('1.234,56')).toBe(1234.56);
    expect(parseBalance('1234,5')).toBe(1234.5);
    expect(parseBalance('1234.56')).toBe(1234.56);
    expect(parseBalance('1.234')).toBe(1234);
    expect(parseBalance('-12,30 €')).toBe(-12.3);
    expect(parseBalance('0')).toBe(0);
    expect(parseBalance('12,345')).toBeNull();
    expect(parseBalance('')).toBeNull();
    expect(parseBalance('dodici')).toBeNull();
  });

  it('shows the accounts with their role and the last sync error', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.textContent).toContain('Carta di credito');
    expect(page.textContent).toContain('HTTP 429 troppe letture');
    const select = page.querySelector('select') as HTMLSelectElement;
    expect(select.selectedOptions[0].textContent?.trim()).toBe('Conto spese');
  });

  it('saves the role chosen from the menu', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'select',
    ) as HTMLSelectElement;

    // Simuliamo l'utente: sceglie "Carta di credito" e il browser lancia l'evento "change".
    select.value = 'CREDIT_CARD';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(changedRoles).toEqual([[3, 'CREDIT_CARD']]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'movimenti riclassificati',
    );
  });

  it('"Aggiorna ora" updates the accounts and shows the summary', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const button = (fixture.nativeElement as HTMLElement).querySelector(
      '.toolbar button',
    ) as HTMLButtonElement;

    button.click();
    await fixture.whenStable();

    expect(syncCalls).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Aggiornati 2 conti, 1 con errori',
    );
  });

  it('a manual account has no consent date, links to the import and is deleted after confirming', async () => {
    accounts = [CARD, BROKER];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const row = page.querySelectorAll('tbody tr')[1] as HTMLElement;

    expect(row.textContent).toContain('conto manuale');
    const link = row.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/importa?conto=8');

    // "elimina" chiede conferma; solo "Elimina" cancella davvero.
    (
      Array.from(row.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'elimina',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    expect(deleted).toEqual([]);
    expect(row.textContent).toContain('verranno eliminati');
    accounts = [CARD];
    (
      Array.from(row.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Elimina',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(deleted).toEqual([8]);
    expect(page.textContent).toContain('Conto "Trade Republic · Conto titoli" eliminato');
    expect(page.querySelector('tbody')?.textContent).not.toContain('Conto titoli'); // elenco ricaricato
  });

  it('a bank account has no delete button', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const row = (fixture.nativeElement as HTMLElement).querySelector('tbody tr') as HTMLElement;

    const buttons = Array.from(row.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).not.toContain('elimina');
    // il nome si può scegliere per ogni conto; il saldo si scrive se la banca non lo dà (docs/saldi.md, parte E)
    expect(buttons).toEqual(['rinomina', 'scrivi il saldo']);
  });

  it('creates a manual account from the form', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const form = page.querySelector('form') as HTMLFormElement;
    const type = (name: string, value: string) => {
      const input = form.querySelector(`[formControlName="${name}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };

    type('bankName', '  Trade Republic ');
    type('name', 'Conto titoli');
    type('currency', 'eur');
    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    // Spazi tolti, valuta in maiuscolo, IBAN vuoto = null.
    expect(created).toEqual([
      {
        bankName: 'Trade Republic',
        name: 'Conto titoli',
        iban: null,
        currency: 'EUR',
        role: 'UNASSIGNED',
      },
    ]);
    expect(page.textContent).toContain('Conto "Trade Republic · Conto titoli" creato');
  });

  it('does not send an incomplete manual account and says what is missing', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const form = page.querySelector('form') as HTMLFormElement;
    const iban = form.querySelector('[formControlName="iban"]') as HTMLInputElement;
    iban.value = 'IT12';
    iban.dispatchEvent(new Event('input'));

    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(created).toEqual([]);
    expect(form.textContent).toContain('Scrivi il nome della banca.');
    expect(form.textContent).toContain('Non sembra un IBAN');
  });

  it('a chosen name replaces the label, and an empty one removes it', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const row = () => page.querySelector('tbody tr') as HTMLElement;
    const button = (text: string) =>
      Array.from(row().querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === text,
      ) as HTMLButtonElement;

    button('rinomina').click();
    await fixture.whenStable();
    const input = row().querySelector('input') as HTMLInputElement;
    input.value = '  Carta Visa ';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();

    expect(renamed).toEqual([[3, 'Carta Visa']]);
    expect(row().textContent).toContain('Carta Visa');
    expect(page.textContent).toContain('Conto rinominato in "Carta Visa"');

    button('rinomina').click();
    await fixture.whenStable();
    const again = row().querySelector('input') as HTMLInputElement;
    again.value = '';
    again.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();
    expect(renamed[1]).toEqual([3, '']);
    expect(page.textContent).toContain('ora il conto si vede come "ING · Carta di credito"');
  });

  it('shows the name the bank gives, so the user knows which account it is', async () => {
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'per la banca: Carta di credito',
    );
  });
});
