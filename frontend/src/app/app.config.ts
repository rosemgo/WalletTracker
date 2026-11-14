import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  LOCALE_ID,
  isDevMode,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { authInterceptor } from './auth/auth-interceptor';
import { APP_LOCALE } from './text/locale';

/**
 * La configurazione dell'applicazione: l'elenco dei "provider", cioè dei servizi che Angular mette a
 * disposizione per l'iniezione. Prende il posto di AppModule delle versioni precedenti.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // anchorScrolling: un link con un'ancora (es. /impostazioni#backup, dall'avviso sui backup) scorre fino a lei.
    provideRouter(routes, withInMemoryScrolling({ anchorScrolling: 'enabled' })),
    // HttpClient, usato da WalletApi. L'intercettore si accorge delle sessioni scadute (docs/46). La protezione CSRF
    // di Angular è accesa di serie: legge il cookie XSRF-TOKEN e lo rimanda nell'header X-XSRF-TOKEN delle richieste
    // che modificano qualcosa, proprio come vuole Spring Security.
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    // La lingua dell'app, per le pipe e le funzioni di Angular (date "10/10/2026", importi "-630,39 €"): text/locale.ts.
    { provide: LOCALE_ID, useValue: APP_LOCALE },
    // Il service worker dell'app installabile (docs/42, parte C): solo nella build di produzione (con ng serve
    // darebbe solo fastidio, servendo file vecchi mentre si programma). Si registra quando la pagina è pronta, o
    // dopo 30 secondi al massimo: prima conta che l'app si apra in fretta.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
