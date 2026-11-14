import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';

/**
 * La lingua dell'app, con il paese ("it-IT"): decide il formato di date e numeri, i nomi di mesi e giorni e le regole
 * dei plurali. È scritta in un posto solo: Angular la riceve come LOCALE_ID (app.config.ts), e la usa il codice che
 * non passa da Angular (le funzioni pure: {@link plural}, {@link formatLocalDate}).
 *
 * Oggi l'app è solo in italiano. Quando avrà un'altra lingua, sarà qui che si sceglie quale usare.
 */
export const APP_LOCALE = 'it-IT';

// I dati della lingua (nomi dei mesi, "10/10/2026", "-630,39 €") per le funzioni e le pipe di Angular: date, numeri,
// valute. Sta qui, e non in app.config.ts, perché serve a chiunque usi APP_LOCALE: anche a una funzione pura in un test.
registerLocaleData(localeIt);
