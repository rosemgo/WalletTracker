import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { BrowserLocation } from '../api/browser-location';
import { Bank } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { LinkPage } from './link-page';

const BANKS: Bank[] = [
  { name: 'ING', country: 'IT', maxConsentDays: 90 },
  { name: 'Revolut', country: 'IT', maxConsentDays: null },
];

describe('LinkPage', () => {
  const visited: string[] = [];
  const started: string[] = [];
  const countries: string[] = [];
  let queryParams: Record<string, string>;

  beforeEach(() => {
    visited.length = 0;
    started.length = 0;
    countries.length = 0;
    queryParams = {};
    const fakeApi: Partial<WalletApi> = {
      banks: (country: string) => {
        countries.push(country);
        return of(BANKS);
      },
      startLink: (bank: string) => {
        started.push(bank);
        return of({ url: 'https://banca.example/login' });
      },
    };
    TestBed.configureTestingModule({
      imports: [LinkPage],
      providers: [
        { provide: WalletApi, useValue: fakeApi },
        // Il browser simulato non deve lasciare davvero la pagina: ci segniamo solo dove andrebbe.
        { provide: BrowserLocation, useValue: { go: (url: string) => visited.push(url) } },
        // I parametri dell'indirizzo, letti quando la pagina parte (vedi RulesPage per lo stesso trucco).
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              get queryParamMap() {
                return convertToParamMap(queryParams);
              },
            },
          },
        },
      ],
    });
  });

  it('filters the banks while typing', async () => {
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const search = page.querySelector('input') as HTMLInputElement;

    search.value = 'rev';
    search.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(page.querySelectorAll('tbody tr').length).toBe(1);
    expect(page.textContent).toContain('Revolut');
    expect(page.textContent).not.toContain('ING');
  });

  it('"Collega" starts the link and sends the browser to the bank', async () => {
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const firstButton = (fixture.nativeElement as HTMLElement).querySelector(
      'tbody button',
    ) as HTMLButtonElement;

    firstButton.click();
    await fixture.whenStable();

    expect(started).toEqual(['ING']);
    expect(visited).toEqual(['https://banca.example/login']);
  });

  it('coming from a consent alert, the bank to renew is already chosen', async () => {
    queryParams = { bank: 'Revolut', country: 'DE' };
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(countries).toEqual(['DE']);
    expect((page.querySelector('input') as HTMLInputElement).value).toBe('Revolut');
    expect(page.querySelectorAll('tbody tr').length).toBe(1);
    expect(page.textContent).toContain('Per rinnovare il collegamento con Revolut');
  });

  it('ignores a country that is not in the list', async () => {
    queryParams = { country: 'XX' };
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();

    expect(countries).toEqual(['IT']);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Per rinnovare');
  });
});
