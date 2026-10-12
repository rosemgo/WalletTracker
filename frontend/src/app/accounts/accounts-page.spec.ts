import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Account, AccountRole, SyncRound } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AccountsPage } from './accounts-page';

const CARD: Account = {
  id: 3,
  bank: 'ING',
  name: 'Carta di credito',
  iban: null,
  currency: 'EUR',
  role: 'SPENDING',
  consentValidUntil: '2027-01-05T14:35:51Z',
  lastSyncAt: null,
  lastSyncError: 'HTTP 429 troppe letture',
};

/**
 * Test del componente con un WalletApi FINTO: al posto del servizio vero ne diamo uno scritto da noi
 * ({ provide: WalletApi, useValue: ... }), che risponde subito con dati preparati. È l'idea di
 * @MockitoBean nei test Spring.
 */
describe('AccountsPage', () => {
  const changedRoles: [number, AccountRole][] = [];
  let syncCalls = 0;

  beforeEach(() => {
    changedRoles.length = 0;
    syncCalls = 0;
    const fakeApi: Partial<WalletApi> = {
      accounts: () => of([CARD]),
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
});
