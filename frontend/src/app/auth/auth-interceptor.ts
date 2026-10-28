import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { EMPTY, catchError, throwError } from 'rxjs';

import { AuthStore } from './auth-store';

/**
 * Un "intercettore" di HttpClient: vede passare tutte le richieste e le risposte dell'app (docs/46, parte F4).
 *
 * Se il backend risponde 401 ("non sei dentro") a una richiesta qualsiasi, la sessione è finita: dopo 12 ore senza
 * usare l'app, dopo un riavvio del backend, o perché il PIN è cambiato su un altro dispositivo. Invece di lasciare ogni
 * pagina con il suo errore, lo diciamo all'{@link AuthStore}, che mostra la pagina del PIN.
 *
 * E il 401 finisce qui: la richiesta si chiude senza risultato e senza errore ({@code EMPTY}). La pagina che l'aveva
 * fatta sta per sparire, sostituita da quella del PIN: un errore che arrivasse fino a lei finirebbe solo, come "errore
 * non gestito", nella console del browser (l'abbiamo visto provando, docs/46 parte M).
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
        return EMPTY;
      }
      return throwError(() => error);
    }),
  );
};
