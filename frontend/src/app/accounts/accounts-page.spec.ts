import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Account, AccountRole, ManualAccountRequest, SyncRound, Transaction } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AccountsPage, outcomeLine } from './accounts-page';
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
  logoVersion: null,
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
  logoVersion: null,
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
  const logos: [number, string][] = [];
  let syncCalls = 0;
  let round: SyncRound;
  let accounts: Account[];

  beforeEach(() => {
    changedRoles.length = 0;
    created.length = 0;
    deleted.length = 0;
    renamed.length = 0;
    balances.length = 0;
    logos.length = 0;
    syncCalls = 0;
    round = { updated: 0, failed: 0, notDue: 0, accounts: [] };
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
      uploadAccountLogo: (id: number, file: File) => {
        logos.push([id, file.name]);
        accounts = accounts.map((a) => (a.id === id ? { ...a, logoVersion: 2 } : a));
        return of(undefined);
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
        return of<SyncRound>(round);
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

  it('a credit card shows only the available credit: it has no balance to write', async () => {
    registerLocaleData(localeIt);
    TestBed.overrideProvider(LOCALE_ID, { useValue: 'it-IT' });
    accounts = [
      {
        ...CARD,
        role: 'CREDIT_CARD',
        lastSyncAt: '2026-11-02T16:57:00Z',
        availableCredit: { amount: 2500, date: '2026-11-02' },
      },
      { ...CARD, id: 4, role: 'CREDIT_CARD', lastSyncAt: '2026-11-02T16:57:00Z' },
    ];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const cells = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('td.balance-cell'),
    ) as HTMLElement[];
    const text = (cell: HTMLElement) => cell.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(cells[0].getAttribute('data-label')).toBe('Plafond');
    expect(text(cells[0])).toMatch(
      /Plafond disponibile\s*2\.500,00\s€\s*al 2 nov 2026 · dalla banca/,
    );
    expect(text(cells[0])).not.toContain('saldo');
    expect(cells[0].querySelector('button')).toBeNull();
    expect(text(cells[1])).toContain('plafond non ancora letto: premi «Aggiorna ora»');
    expect(cells[1].querySelector('button')).toBeNull();
  });

  it('a manual account gets the logo chosen by the user, and shows it next to its name', async () => {
    accounts = [BROKER];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const input = page.querySelector('.logo-upload input') as HTMLInputElement;
    expect(page.querySelector('.logo-upload')?.textContent).toContain('carica il logo');

    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'tr.png', {
      type: 'image/png',
    });
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(logos).toEqual([[8, 'tr.png']]);
    expect(page.querySelector('img.account-logo')?.getAttribute('src')).toBe(
      '/api/accounts/8/logo?v=2',
    );
    expect(page.querySelector('.logo-upload')?.textContent).toContain('cambia il logo');
  });

  it('a logo too big is refused before sending it', async () => {
    accounts = [BROKER];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const input = page.querySelector('.logo-upload input') as HTMLInputElement;

    const big = new File([new Uint8Array(300 * 1024)], 'grande.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [big] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(logos).toEqual([]);
    expect(page.textContent).toContain('Il logo è troppo grande');
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
    // Ogni conto porta ai suoi movimenti.
    const link = Array.from(page.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('vedi i movimenti'),
    );
    expect(link?.getAttribute('href')).toBe('/movimenti?accountId=3');
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
    accounts = [CARD, BROKER];
    round = {
      updated: 1,
      failed: 1,
      notDue: 0,
      accounts: [
        { accountId: 3, inserted: 2, pending: 1, error: null },
        { accountId: 8, inserted: 0, pending: 0, error: 'troppe richieste alla banca' },
      ],
    };
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const button = (fixture.nativeElement as HTMLElement).querySelector(
      '.toolbar button',
    ) as HTMLButtonElement;

    button.click();
    await fixture.whenStable();

    expect(syncCalls).toBe(1);
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Aggiornato 1 conto, 1 con errori');
    const lines = Array.from(page.querySelectorAll('.sync-outcomes li')).map((li) =>
      li.textContent?.trim(),
    );
    expect(lines).toEqual([
      'ING · Carta di credito: 2 movimenti nuovi, 1 in attesa',
      'Trade Republic · Conto titoli: non aggiornato (troppe richieste alla banca)',
    ]);
    expect(page.textContent).not.toContain('La banca non ha mandato niente di nuovo');
  });

  it('"Aggiorna ora" says when the bank sent nothing new, and why today\'s card payments may be missing', async () => {
    accounts = [CARD];
    round = {
      updated: 1,
      failed: 0,
      notDue: 0,
      accounts: [{ accountId: 3, inserted: 0, pending: 0, error: null }],
    };
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    (page.querySelector('.toolbar button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(page.querySelector('.sync-outcomes')?.textContent).toContain(
      'ING · Carta di credito: nessun movimento nuovo',
    );
    expect(page.textContent).toContain('La banca non ha mandato niente di nuovo');
    expect(page.textContent).toContain('1–2 giorni lavorativi');
  });

  it('puts the outcome of an account into words', () => {
    const ok = { accountId: 1, error: null };
    expect(outcomeLine({ ...ok, inserted: 1, pending: 0 }, 'Conto')).toBe(
      'Conto: 1 movimento nuovo',
    );
    expect(outcomeLine({ ...ok, inserted: 0, pending: 3 }, 'Conto')).toBe('Conto: 3 in attesa');
    expect(outcomeLine({ ...ok, inserted: 0, pending: 0 }, 'Conto')).toBe(
      'Conto: nessun movimento nuovo',
    );
  });

  it('a manual account has no consent date, links to the import and is deleted after confirming', async () => {
    accounts = [CARD, BROKER];
    const fixture = TestBed.createComponent(AccountsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const row = page.querySelectorAll('tbody tr')[1] as HTMLElement;

    expect(row.textContent).toContain('conto manuale');
    const link = Array.from(row.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('importa un file'),
    ) as HTMLAnchorElement;
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
