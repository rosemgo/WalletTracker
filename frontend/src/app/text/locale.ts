import { registerLocaleData } from '@angular/common';
import localeEnGb from '@angular/common/locales/en-GB';
import localeIt from '@angular/common/locales/it';

import { LOCALES, Language, SOURCE_LANGUAGE, isLanguage } from '../language/language';

/**
 * La lingua in cui l'app è aperta. La decide main.ts prima di caricare l'app, e la scrive sulla pagina
 * (<html lang="en">): qui la si legge da lì. Senza (nei test, che non passano da main.ts), l'italiano.
 */
function pageLanguage(): Language {
  const language = typeof document === 'undefined' ? null : document.documentElement.lang;
  return isLanguage(language) ? language : SOURCE_LANGUAGE;
}

export const APP_LANGUAGE: Language = pageLanguage();

/**
 * La lingua dell'app con il paese ("it-IT", "en-GB"): decide il formato di date e numeri, i nomi di mesi e giorni e
 * le regole dei plurali. È scritta in un posto solo: Angular la riceve come LOCALE_ID (app.config.ts), e la usa il
 * codice che non passa da Angular (le funzioni pure: {@link plural}, {@link formatLocalDate}).
 */
export const APP_LOCALE = LOCALES[APP_LANGUAGE];

// I dati delle lingue (nomi dei mesi, "10/10/2026", "-630,39 €") per le funzioni e le pipe di Angular: date, numeri,
// valute. Stanno qui, e non in app.config.ts, perché servono a chiunque usi APP_LOCALE: anche a una funzione pura in
// un test. Sono piccoli: si registrano tutti, qualunque sia la lingua aperta.
registerLocaleData(localeIt);
registerLocaleData(localeEnGb);
