import { Language } from './language';

/**
 * Un file di traduzione (src/locale/en.json): per ogni testo dell'app, riconosciuto dal suo identificativo, la frase
 * italiana e la sua traduzione. La frase italiana sta lì per chi traduce (la tiene aggiornata `npm run i18n`); a
 * Angular serve solo la traduzione.
 */
export interface TranslationFile {
  locale: string;
  translations: Record<string, { it: string; en: string }>;
}

/**
 * Chi carica ogni file. import() con il percorso scritto per intero: così chi costruisce l'app sa che il file serve,
 * lo mette in un pezzo a parte (scaricato solo da chi usa quella lingua) e gli dà un nome che cambia con il contenuto,
 * come agli altri file dell'app. L'app installabile lo tiene da parte insieme al resto e funziona anche senza rete.
 */
const FILES: Partial<Record<Language, () => Promise<{ default: TranslationFile }>>> = {
  en: () => import('../../locale/en.json') as unknown as Promise<{ default: TranslationFile }>,
};

/**
 * Le traduzioni di una lingua come le vuole Angular (loadTranslations): identificativo → frase. Per l'italiano, la
 * lingua in cui l'app è scritta, non c'è niente da caricare: null.
 */
export async function translationsOf(language: Language): Promise<Record<string, string> | null> {
  const load = FILES[language];
  if (load === undefined) {
    return null;
  }
  return forAngular((await load()).default);
}

/**
 * Un testo marcato nel template può cominciare o finire con uno spazio ("Nome " prima di un campo): quello spazio fa
 * parte dell'impaginazione, non della frase. Nel file le traduzioni si scrivono senza; qui si rimettono gli spazi
 * della frase italiana, così una traduzione non può incollare due parole per uno spazio dimenticato.
 */
export function forAngular(file: TranslationFile): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [id, { it, en }] of Object.entries(file.translations)) {
    const start = /^\s*/.exec(it)?.[0] ?? '';
    const end = /\s*$/.exec(it.slice(start.length))?.[0] ?? '';
    result[id] = start + en.trim() + end;
  }
  return result;
}
