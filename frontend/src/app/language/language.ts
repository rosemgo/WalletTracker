/**
 * La lingua dell'app, prima di Angular (docs/lingue-2-inglese.md, parte D).
 *
 * Questo file non importa niente dell'app, e deve restare così: lo usa main.ts per decidere la lingua PRIMA di
 * caricare il resto. Le frasi dell'app nascono nel momento in cui il loro file viene caricato: se la traduzione non
 * fosse già al suo posto, resterebbero in italiano.
 */

/** Le lingue dell'app. L'italiano è quella in cui è scritta: le altre sono traduzioni (src/locale). */
export const LANGUAGES = ['it', 'en'] as const;
export type Language = (typeof LANGUAGES)[number];

/** La lingua in cui l'app è scritta: non ha un file di traduzione. */
export const SOURCE_LANGUAGE: Language = 'it';

/** Il nome di ogni lingua, nella lingua stessa: così chi non capisce quella della pagina riconosce la sua. */
export const LANGUAGE_NAMES: Record<Language, string> = { it: 'Italiano', en: 'English' };

/**
 * La lingua con il paese, per i formati di date e numeri (i "locale" di Angular). L'inglese è quello d'Europa: l'app
 * lavora con banche europee, quindi 31/12/2026 (il giorno prima del mese) e l'orologio a 24 ore.
 */
export const LOCALES: Record<Language, string> = { it: 'it-IT', en: 'en-GB' };

/** Dove questo browser ricorda la lingua, per non doverla chiedere al server a ogni apertura. */
export const LANGUAGE_KEY = 'wallettracker.language';

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (LANGUAGES as readonly string[]).includes(value);
}

/** La lingua ricordata da questo browser, o null. */
export function rememberedLanguage(): Language | null {
  try {
    const value = localStorage.getItem(LANGUAGE_KEY);
    return isLanguage(value) ? value : null;
  } catch {
    return null; // localStorage vietato (navigazione privata di alcuni browser)
  }
}

export function rememberLanguage(language: Language): void {
  try {
    localStorage.setItem(LANGUAGE_KEY, language);
  } catch {
    // pazienza: la prossima volta la si chiede di nuovo al server
  }
}

/**
 * La lingua del browser, tra le nostre: la prima delle sue preferite che conosciamo ("it-IT" e "it-CH" sono
 * l'italiano). Se non ne conosciamo nessuna, l'inglese: chi ha il browser in tedesco capisce più facilmente quello.
 */
export function browserLanguage(
  preferred: readonly string[] = navigator.languages ?? [],
): Language {
  for (const tag of preferred) {
    const language = tag.toLowerCase().split('-')[0];
    if (isLanguage(language)) {
      return language;
    }
  }
  return 'en';
}

/**
 * Che cosa dice il server della lingua (GET /api/language).
 *
 * - `chosen`: la lingua dell'installazione, o null se nessuno l'ha ancora scelta (o il server non risponde);
 * - `suggested`: solo in un'installazione nuova, la lingua scelta nello script di installazione (la domanda
 *   "Lingua / Language" del primo avvio: docs/lingue-3-installazione.md), o null se non c'è.
 */
export interface ServerLanguage {
  chosen: Language | null;
  suggested: Language | null;
}

export async function serverLanguage(timeoutMs: number): Promise<ServerLanguage> {
  const nothing: ServerLanguage = { chosen: null, suggested: null };
  try {
    const response = await fetch('/api/language', { signal: AbortSignal.timeout(timeoutMs) });
    if (!response.ok) {
      return nothing;
    }
    const body = (await response.json()) as { language?: unknown; suggested?: unknown } | null;
    return {
      chosen: isLanguage(body?.language) ? body.language : null,
      suggested: isLanguage(body?.suggested) ? body.suggested : null,
    };
  } catch {
    return nothing;
  }
}

/**
 * In che lingua aprire l'app.
 *
 * 1. Quella ricordata da questo browser: subito, senza aspettare la rete. Se nel frattempo è stata cambiata da un
 *    altro dispositivo, se ne accorge LanguageStore appena l'app è aperta, e ricarica la pagina.
 * 2. Altrimenti (la prima volta da questo browser) quella dell'installazione, chiesta al server: chi usa già l'app
 *    la trova in italiano anche con il browser in inglese. Si aspetta poco: se il server tarda, si passa oltre.
 * 3. Altrimenti (un'installazione nuova, dove nessuno ha ancora scelto dall'app) quella scelta installando, se il
 *    server la dice: chi ha risposto "English" allo script di installazione trova la prima schermata in inglese,
 *    anche con il browser in italiano. Non la si ricorda: è una proposta, e la prima schermata la può cambiare.
 * 4. Altrimenti quella del browser.
 */
export async function startLanguage(): Promise<Language> {
  const remembered = rememberedLanguage();
  if (remembered !== null) {
    return remembered;
  }
  const { chosen, suggested } = await serverLanguage(1500);
  if (chosen !== null) {
    rememberLanguage(chosen);
    return chosen;
  }
  return suggested ?? browserLanguage();
}
