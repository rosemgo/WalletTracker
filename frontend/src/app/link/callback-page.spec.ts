import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { LinkResult } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CallbackPage } from './callback-page';

describe('CallbackPage', () => {
  const completed: [string, string][] = [];
  let answer: LinkResult;

  /** Prepara la pagina come se la banca avesse rimandato il browser con questi parametri. */
  function setUp(queryParams: Record<string, string>): void {
    completed.length = 0;
    answer = { bank: 'ING', accounts: 3, imported: 154, errors: [] };
    const fakeApi: Partial<WalletApi> = {
      completeLink: (code: string, state: string) => {
        completed.push([code, state]);
        return of<LinkResult>(answer);
      },
    };
    TestBed.configureTestingModule({
      imports: [CallbackPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        // Un ActivatedRoute finto, con i parametri dell'indirizzo che vogliamo simulare.
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });
    // La pagina toglie i parametri dalla barra degli indirizzi: nei test non serve navigare davvero.
    TestBed.inject(Router).navigate = () => Promise.resolve(true);
  }

  it('completes the link with code and state and shows the result', async () => {
    setUp({ code: 'code-1', state: 'state-1' });
    const fixture = TestBed.createComponent(CallbackPage);
    await fixture.whenStable();

    expect(completed).toEqual([['code-1', 'state-1']]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      '154 movimenti nuovi importati',
    );
  });

  it('shows the error sent by the bank and does not call the backend', async () => {
    setUp({ error: 'access_denied', error_description: "L'utente ha annullato" });
    const fixture = TestBed.createComponent(CallbackPage);
    await fixture.whenStable();

    expect(completed).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      "La banca ha risposto: L'utente ha annullato",
    );
  });

  it('with accounts linked it also tells what to do if one is missing', async () => {
    setUp({ code: 'code-1', state: 'state-1' });
    const fixture = TestBed.createComponent(CallbackPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    const missing = page.querySelector('[data-testid="missing-account"]');
    expect(missing?.querySelector('summary')?.textContent).toContain('Manca un conto');
    expect(missing?.textContent).toContain('Link accounts');
    expect(page.querySelector('[data-testid="no-accounts"]')).toBeNull();
  });

  it('with no account passed by Enable Banking it explains the restricted mode and the steps', async () => {
    setUp({ code: 'code-1', state: 'state-1' });
    answer = { bank: 'FinecoBank', accounts: 0, imported: 0, errors: [] };
    const fixture = TestBed.createComponent(CallbackPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    const warning = page.querySelector('[data-testid="no-accounts"]') as HTMLElement;
    expect(warning.textContent?.replace(/\s+/g, ' ')).toContain(
      'FinecoBank: nessun conto passato da Enable Banking',
    );
    expect(warning.textContent).toContain('modalità ristretta');
    expect(warning.querySelectorAll('ol.linked-steps li')).toHaveLength(4);
    expect(warning.querySelector('a[href^="https://enablebanking.com"]')).toBeTruthy();
    // niente "collegata", e niente invito a scegliere il ruolo di conti che non ci sono
    expect(page.textContent).not.toContain('collegata:');
    expect(page.textContent).not.toContain('ruolo dei nuovi conti');
  });
});
