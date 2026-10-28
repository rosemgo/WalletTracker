import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

import { AuthStore } from './auth-store';

/**
 * Un "intercettore" di HttpClient: vede passare tutte le richieste e le risposte dell'app (docs/46, parte E).
 *
 * Se il backend risponde 401 ("serve il PIN") a una richiesta qualsiasi, la sessione è scaduta: dopo 12 ore senza
 * usare l'app, o dopo un riavvio del backend. Invece di lasciare ogni pagina con il suo errore, lo diciamo all'
 * {@link AuthStore}, che mostra la pagina del PIN. L'errore prosegue comunque verso chi ha fatto la richiesta.
 *
 * Le richieste del PIN stesso (/api/auth/...) no: lì un 401 vuol dire "PIN sbagliato", e lo mostra la pagina.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthStore);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        request.url.startsWith('/api/') &&
        !request.url.startsWith('/api/auth/')
      ) {
        auth.sessionLost();
      }
      return throwError(() => error);
    }),
  );
};
