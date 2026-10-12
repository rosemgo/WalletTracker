import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { LinkResult } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CallbackPage } from './callback-page';

describe('CallbackPage', () => {
  const completed: [string, string][] = [];

  /** Prepara la pagina come se la banca avesse rimandato il browser con questi parametri. */
  function setUp(queryParams: Record<string, string>): void {
    completed.length = 0;
    const fakeApi: Partial<WalletApi> = {
      completeLink: (code: string, state: string) => {
        completed.push([code, state]);
        return of<LinkResult>({ bank: 'ING', accounts: 3, imported: 154, errors: [] });
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
});
