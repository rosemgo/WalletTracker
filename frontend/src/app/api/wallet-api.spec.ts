import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { WalletApi } from './wallet-api';

/**
 * Test del servizio, SENZA backend: provideHttpClientTesting sostituisce la rete con un finto server
 * (HttpTestingController). Controlliamo quale richiesta parte e decidiamo noi la risposta.
 * È l'equivalente di MockRestServiceServer che abbiamo usato nei test Java del client Enable Banking.
 */
describe('WalletApi', () => {
  let api: WalletApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(WalletApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify()); // nessuna richiesta inattesa

  it('sends only the filters that are set', () => {
    api.transactions({ accountId: 3, type: null, page: 1, size: 50 }).subscribe();

    const request = http.expectOne((r) => r.url === '/api/transactions');
    expect(request.request.params.get('accountId')).toBe('3');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.has('type')).toBe(false); // filtro spento: non va mandato
    request.flush({ items: [], page: 1, size: 50, totalItems: 0, totalPages: 0 });
  });

  it('removes a manual correction by sending type null', () => {
    api.correctType(42, null).subscribe();

    const request = http.expectOne('/api/transactions/42/manual-type');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ type: null });
    request.flush({});
  });
});
