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

  it('sends the CSV file and the mapping as two parts of a multipart request', async () => {
    const file = new File(['Data;Importo\n03/10/2026;-1,00\n'], 'estratto.csv', {
      type: 'text/csv',
    });
    const mapping = {
      delimiter: ';',
      dateColumn: 0,
      dateFormat: 'dd/MM/yyyy',
      amountMode: 'SINGLE' as const,
      amountColumn: 1,
      incomeColumn: null,
      expenseColumn: null,
      decimalSeparator: 'COMMA' as const,
      descriptionColumn: null,
      counterpartyColumn: null,
      currencyColumn: null,
    };
    api.importCsv(file, 7, mapping).subscribe();

    const request = http.expectOne((r) => r.url === '/api/imports');
    expect(request.request.method).toBe('POST');
    expect(request.request.params.get('accountId')).toBe('7');
    const body = request.request.body as FormData;
    expect((body.get('file') as File).name).toBe('estratto.csv');
    const mappingPart = body.get('mapping') as Blob;
    expect(mappingPart.type).toBe('application/json');
    expect(JSON.parse(await mappingPart.text())).toEqual(mapping);
    request.flush({ imported: 1, duplicates: 0 });
  });

  it('asks for a preview without a mapping when there is none yet', () => {
    const file = new File(['x'], 'estratto.csv');
    api.previewImport(file, 7, null).subscribe();

    const request = http.expectOne((r) => r.url === '/api/imports/preview');
    expect((request.request.body as FormData).has('mapping')).toBe(false);
    request.flush({});
  });

  it('removes a manual correction by sending type null', () => {
    api.correctType(42, null).subscribe();

    const request = http.expectOne('/api/transactions/42/manual-type');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ type: null });
    request.flush({});
  });
});
