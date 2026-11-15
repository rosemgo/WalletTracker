import { loadTranslations } from '@angular/localize';

import { SOURCE_LANGUAGE, startLanguage } from './app/language/language';
import { translationsOf } from './app/language/translations';

/**
 * L'avvio, in tre tempi (docs/lingue-2-inglese.md, parte D):
 *
 * 1. si decide la lingua;
 * 2. si carica la sua traduzione e la si consegna ad Angular;
 * 3. solo adesso si carica l'app (import() al posto di import in cima al file) e la si avvia.
 *
 * L'ordine conta: ogni frase dell'app ($localize nel TypeScript, i18n nei template) viene scritta nella lingua giusta
 * nel momento in cui il suo file viene caricato. Caricata prima della traduzione, resterebbe in italiano.
 */
async function start(): Promise<void> {
  let language = await startLanguage();
  try {
    const translations = await translationsOf(language);
    if (translations !== null) {
      loadTranslations(translations);
    }
  } catch (error) {
    // La traduzione non si è caricata (la rete è caduta proprio adesso): meglio l'app in italiano che niente.
    console.error(error);
    language = SOURCE_LANGUAGE;
  }
  // La lingua della pagina: la leggono i lettori di schermo, e da qui la legge l'app (text/locale.ts).
  document.documentElement.lang = language;

  const { bootstrap } = await import('./bootstrap');
  await bootstrap();
}

start().catch((error) => console.error(error));
