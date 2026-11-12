/** Quante parole accetta la ricerca dei movimenti: lo stesso limite del backend (TransactionSearch.MAX_WORDS). */
export const MAX_SEARCH_WORDS = 10;

/**
 * Le parole da cercare per "Tutti i movimenti di questo esercente": quelle della sua chiave, in minuscolo, al massimo
 * le prime 10 intere. Una chiave lunga ("bonifico istantaneo da voi disposto n a favore di …") ne ha di più, e la
 * ricerca rispondeva "Troppe parole da cercare" senza mostrare niente. Le prime 10 bastano: ogni parola deve esserci,
 * quindi più parole restringono soltanto.
 *
 * In un file suo perché la usano anche le pagine che portano ai movimenti di un esercente (Analisi, una categoria,
 * Abbonamenti, Investimenti), senza caricare la scheda del movimento.
 */
export function merchantSearch(key: string): string {
  return key.toLowerCase().split(/\s+/).filter(Boolean).slice(0, MAX_SEARCH_WORDS).join(' ');
}
