import { TestBed } from '@angular/core/testing';
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

  beforeEach(() => {
    visited.length = 0;
    started.length = 0;
    const fakeApi: Partial<WalletApi> = {
      banks: () => of(BANKS),
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
});
