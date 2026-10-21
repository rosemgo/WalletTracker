import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import {
  Subscription,
  SubscriptionConfirmation,
  SubscriptionOverview,
  SubscriptionSuggestion,
} from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { SubscriptionsPage } from './subscriptions-page';

const NETFLIX: Subscription = {
  id: 1,
  merchantKey: 'NETFLIX COM',
  currency: 'EUR',
  name: 'Netflix',
  frequency: 'MONTHLY',
  expectedAmount: 12.99,
  lastAmount: 13.99,
  lastDate: '2026-10-03',
  nextDate: '2026-11-03',
  monthlyCost: 13.99,
  priceChanged: true,
  late: false,
};

const GYM: Subscription = {
  ...NETFLIX,
  id: 2,
  merchantKey: 'PALESTRA FIT',
  name: 'Palestra',
  expectedAmount: 35,
  lastAmount: 35,
  lastDate: '2026-07-01',
  monthlyCost: 35,
  priceChanged: false,
  late: true,
};

const SPOTIFY: SubscriptionSuggestion = {
  merchantKey: 'SPOTIFY AB',
  currency: 'EUR',
  name: 'Spotify AB',
  frequency: 'MONTHLY',
  typicalAmount: 10.99,
  occurrences: 5,
  lastDate: '2026-10-05',
  nextDate: '2026-11-05',
};

describe('SubscriptionsPage', () => {
  const confirmed: SubscriptionConfirmation[] = [];
  const dismissed: [string, number][] = [];
  const deleted: number[] = [];
  let loads: number;

  beforeEach(() => {
    registerLocaleData(localeIt);
    confirmed.length = 0;
    dismissed.length = 0;
    deleted.length = 0;
    loads = 0;
    const overview: SubscriptionOverview = {
      subscriptions: [NETFLIX, GYM],
      totals: [{ currency: 'EUR', monthly: 13.99, yearly: 167.88 }],
      dismissed: [
        { id: 9, merchantKey: 'ASSICURAZIONE AUTO', currency: 'EUR', amount: null }, // prima della V21
        { id: 10, merchantKey: 'FASTWEB S P A', currency: 'EUR', amount: 23.95 },
      ],
    };
    const fakeApi: Partial<WalletApi> = {
      subscriptions: () => {
        loads++;
        return of(overview);
      },
      subscriptionSuggestions: () => of([SPOTIFY]),
      confirmSubscription: (c: SubscriptionConfirmation) => {
        confirmed.push(c);
        return of(undefined);
      },
      dismissSubscription: (key: string, _currency: string, amount: number) => {
        dismissed.push([key, amount]);
        return of(undefined);
      },
      deleteSubscription: (id: number) => {
        deleted.push(id);
        return of(undefined);
      },
    };
    TestBed.configureTestingModule({
      imports: [SubscriptionsPage],
      providers: [
        { provide: WalletApi, useValue: fakeApi },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(SubscriptionsPage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function button(page: HTMLElement, text: string, index = 0): HTMLButtonElement {
    return Array.from(page.querySelectorAll('button')).filter(
      (b) => b.textContent?.trim() === text,
    )[index] as HTMLButtonElement;
  }

  it('shows the total cost and each subscription, with price changes and late payments', async () => {
    const page = await render();
    const rows = page.querySelectorAll('tbody tr');

    expect(page.querySelector('.tile-value')?.textContent).toMatch(/13,99\s€/);
    expect(rows[0].textContent).toContain('Netflix');
    expect(rows[0].textContent).toMatch(/prezzo cambiato: prima 12,99\s€/);
    expect(rows[0].textContent).toContain('03/11/2026'); // il prossimo pagamento
    expect(rows[1].classList).toContain('disabled');
    expect(rows[1].textContent).toContain("forse l'hai disdetto");
  });

  it('confirms a suggestion with the name chosen by the user', async () => {
    const page = await render();
    const name = page.querySelector('.suggestion input') as HTMLInputElement;

    name.value = '  Spotify  ';
    button(page, 'È un abbonamento').click();

    expect(confirmed).toEqual([
      {
        merchantKey: 'SPOTIFY AB',
        currency: 'EUR',
        name: 'Spotify',
        frequency: 'MONTHLY',
        expectedAmount: 10.99,
      },
    ]);
    expect(loads).toBe(2); // ricaricata dopo la conferma
  });

  it('an empty name keeps the proposed one', async () => {
    const page = await render();
    (page.querySelector('.suggestion input') as HTMLInputElement).value = ' ';
    button(page, 'È un abbonamento').click();

    expect(confirmed[0].name).toBe('Spotify AB');
  });

  it('dismisses a suggestion and can bring a dismissed merchant back', async () => {
    const page = await render();

    button(page, 'no, non suggerirlo più').click();
    expect(dismissed).toEqual([['SPOTIFY AB', SPOTIFY.typicalAmount]]); // con l'importo della serie

    const list = page.querySelector('.dismissed')!.textContent!.replace(/\s+/g, ' ');
    expect(list).toContain('ASSICURAZIONE AUTO · tutti gli importi (EUR)');
    expect(list).toContain('FASTWEB S P A · circa 23,95 €');
    button(page, 'suggeriscilo di nuovo').click();
    expect(deleted).toEqual([9]);
  });
});
