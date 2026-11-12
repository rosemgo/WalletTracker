import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { Account, Transaction } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { Avatar } from '../transactions/avatar';
import { AccountsStore, shortAccountName } from './accounts-store';

function account(id: number, bank: string, label: string, changes: Partial<Account> = {}): Account {
  return { id, bank, label, displayName: null, logoVersion: null, ...changes } as Account;
}

const ING = account(1, 'ING', 'ING ··5078', { logoVersion: 1730000000000 });
const ING_CARD = account(2, 'ING', 'ING · Carta di credito');
const REVOLUT = account(3, 'Revolut', 'Revolut ··1995');
const ALL = [ING, ING_CARD, REVOLUT];

describe('the name of an account next to a movement', () => {
  it('is the bank alone when the bank has only that account, the full name otherwise', () => {
    expect(shortAccountName(REVOLUT, ALL)).toBe('Revolut');
    expect(shortAccountName(ING, ALL)).toBe('ING ··5078');
    expect(shortAccountName(ING_CARD, ALL)).toBe('ING · Carta di credito');
  });

  it('is the name chosen by the user, when there is one', () => {
    expect(shortAccountName({ ...ING, displayName: 'Conto stipendio' }, ALL)).toBe(
      'Conto stipendio',
    );
  });
});

@Component({
  imports: [Avatar],
  template: `<span class="avatar" [appAvatar]="t()"></span>`,
})
class Host {
  readonly t = signal({ accountId: 1, description: 'ESSELUNGA MILANO' } as unknown as Transaction);
}

describe('the circle of a movement', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: WalletApi, useValue: { accounts: () => of(ALL) } }],
    });
  });

  it('has the initial of the merchant and, in the corner, the logo of the bank of the account', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const circle = (fixture.nativeElement as HTMLElement).querySelector(
      'span.avatar',
    ) as HTMLElement;

    expect(circle.textContent?.trim()).toBe('E');
    expect(circle.querySelector('img.avatar-bank')?.getAttribute('src')).toBe(
      '/api/accounts/1/logo?v=1730000000000',
    );

    // Un conto senza logo: solo l'iniziale.
    fixture.componentInstance.t.set({
      accountId: 3,
      description: 'NETFLIX',
    } as unknown as Transaction);
    await fixture.whenStable();
    expect(circle.querySelector('img')).toBeNull();
    expect(TestBed.inject(AccountsStore).shortName(3)).toBe('Revolut');
  });
});
