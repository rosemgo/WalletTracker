import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withFetch } from '@angular/common/http';
import localeIt from '@angular/common/locales/it';
import {
  ApplicationConfig,
  LOCALE_ID,
  isDevMode,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';

// I formati italiani (date "10/10/2026", importi "-630,39 €") per le pipe date e currency.
registerLocaleData(localeIt);

/**
 * La configurazione dell'applicazione: l'elenco dei "provider", cioè dei servizi che Angular mette a
 * disposizione per l'iniezione. Prende il posto di AppModule delle versioni precedenti.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch()), // HttpClient, usato da WalletApi
    { provide: LOCALE_ID, useValue: 'it-IT' },
    // Il service worker dell'app installabile (docs/42, parte C): solo nella build di produzione (con ng serve
    // darebbe solo fastidio, servendo file vecchi mentre si programma). Si registra quando la pagina è pronta, o
    // dopo 30 secondi al massimo: prima conta che l'app si apra in fretta.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
