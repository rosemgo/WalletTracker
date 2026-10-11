import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withFetch } from '@angular/common/http';
import localeIt from '@angular/common/locales/it';
import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';

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
  ],
};
