import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { BrowserLocation } from '../api/browser-location';
import { AuthStatus, Bank, SetupStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AuthStore } from '../auth/auth-store';
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
  // Da dove si apre la pagina, e gli indirizzi di ritorno del .env (docs/47, parte Q).
  let origin: string;
  let redirectUrls: string[];

  beforeEach(() => {
    visited.length = 0;
    started.length = 0;
    countries.length = 0;
    queryParams = {};
    origin = 'https://localhost:8443';
    redirectUrls = ['https://localhost:8443/callback'];
    const fakeApi: Partial<WalletApi> = {
      banks: (country: string) => {
        countries.push(country);
        return of(BANKS);
      },
      setupStatus: () =>
        of<SetupStatus>({
          configured: true,
          source: 'ENV',
          applicationId: 'app',
          redirectUrl: redirectUrls[0] ?? null,
          redirectUrls,
        } as SetupStatus),
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
        {
          provide: BrowserLocation,
          useValue: { go: (url: string) => visited.push(url), origin: () => origin },
        },
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

  /** Il PIN, come lo direbbe il backend: lo mettiamo direttamente nello store, che qui nessuno chiede. */
  function pin(pinSet: boolean): void {
    TestBed.inject(AuthStore).status.set({
      pinSet,
      withoutPin: !pinSet,
      authenticated: true,
      local: true,
    } as AuthStatus);
  }

  it('from the server without Tailscale it explains what to do, instead of the banks', async () => {
    origin = 'http://localhost:8000';
    redirectUrls = [];
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('[data-testid="needs-tailscale"]')?.textContent).toContain(
      'Per collegare una banca serve Tailscale',
    );
    expect(page.querySelector('tbody button')).toBeNull();
  });

  it('from the server with Tailscale it says where the bank will come back, and asks for a PIN first', async () => {
    origin = 'http://localhost:8000';
    redirectUrls = ['https://localhost:8443/callback', 'https://pc-casa.tail0000.ts.net/callback'];
    pin(false);
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('[data-testid="return-elsewhere"]')?.textContent).toContain(
      'https://pc-casa.tail0000.ts.net',
    );
    expect(page.querySelector('[data-testid="needs-pin"]')?.textContent).toContain(
      'Prima crea un PIN',
    );
    expect(page.querySelector('tbody button')).not.toBeNull();

    pin(true);
    await fixture.whenStable();
    expect(page.querySelector('[data-testid="needs-pin"]')).toBeNull();
  });

  it('from the same address of the return (ng serve, or Tailscale from the phone) it says nothing more', async () => {
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('[data-testid="needs-tailscale"]')).toBeNull();
    expect(page.querySelector('[data-testid="return-elsewhere"]')).toBeNull();
  });

  it('in development, with only the Tailscale address, it warns that the bank would not come back here', async () => {
    origin = 'https://localhost:8443';
    redirectUrls = ['https://pc-casa.tail0000.ts.net/callback'];
    const fixture = TestBed.createComponent(LinkPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    const warning = page.querySelector('[data-testid="dev-return-elsewhere"]');
    expect(warning?.textContent).toContain('https://localhost:8443/callback');
    expect(warning?.textContent).toContain('./mvnw');
    // Non l'avviso del server: lì Tailscale è la strada giusta, qui no.
    expect(page.querySelector('[data-testid="return-elsewhere"]')).toBeNull();
  });
});
