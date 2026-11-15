import english from '../../locale/en.json';
import { TranslationFile, forAngular, translationsOf } from './translations';

describe('the translation files', () => {
  it('Italian, the language the app is written in, has nothing to load', async () => {
    expect(await translationsOf('it')).toBeNull();
  });

  it('English is loaded as Angular wants it: one sentence for each identifier', async () => {
    const translations = await translationsOf('en');
    const file = english as unknown as TranslationFile;

    expect(Object.keys(translations ?? {}).length).toBe(Object.keys(file.translations).length);
    expect(Object.keys(translations ?? {}).length).toBeGreaterThan(1000);
  });

  it('gives back to a translation the spaces around the Italian sentence', () => {
    const file: TranslationFile = {
      locale: 'en',
      translations: {
        a: { it: ' Nome ', en: 'Name' },
        b: { it: 'Salva', en: ' Save ' },
        c: { it: '\n      Cerca\n    ', en: 'Search' },
      },
    };

    // " Nome " prima di un campo: lo spazio è dell'impaginazione, e torna anche se chi traduce lo dimentica.
    expect(forAngular(file)).toEqual({ a: ' Name ', b: 'Save', c: '\n      Search\n    ' });
  });

  it('no English sentence is empty, and none is left in Italian by mistake', () => {
    const file = english as unknown as TranslationFile;
    const entries = Object.values(file.translations);

    expect(entries.filter((entry) => entry.en.trim() === '')).toEqual([]);
    // Parole che in inglese non esistono: se compaiono in una traduzione, è rimasta una frase italiana.
    const italian =
      /\b(movimenti|movimento|conto|conti|categoria|categorie|spesa|spese|della|degli|questo|questa)\b/i;
    expect(entries.filter((entry) => italian.test(entry.en)).map((entry) => entry.en)).toEqual([]);
  });
});
