import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Account, AccountRole, ManualAccountRequest, SyncRound } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AccountsPage } from './accounts-page';

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
  let syncCalls = 0;
  let accounts: Account[];

  beforeEach(() => {
    changedRoles.length = 0;
    created.length = 0;
    deleted.length = 0;
    renamed.length = 0;
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
    expect(buttons).toEqual(['rinomina']); // il nome si può scegliere per ogni conto
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
