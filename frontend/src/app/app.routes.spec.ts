import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { routes } from './app.routes';

describe('routes', () => {
  it('the old address of "Completa i movimenti" opens Importa, with the account', async () => {
    // La pagina è stata unita a Importa (docs/importa-file.md, parte J): un segnalibro vecchio funziona ancora.
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/importa/completa?conto=7');

    expect(router.url).toBe('/importa?conto=7');
  });
});
