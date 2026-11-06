/**
 * Quale indirizzo di ritorno userà il backend per un collegamento avviato da questa pagina (docs/47, parte Q). È la
 * stessa regola di EnableBankingProperties.redirectUrlFor, ripetuta qui solo per DIRLO PRIMA all'utente ("al ritorno si
 * aprirà l'indirizzo di Tailscale", "serve Tailscale"): la scelta vera la fa il backend. I test (return-address.spec.ts)
 * hanno gli stessi casi di EnableBankingPropertiesTest, così le due regole non possono divergere senza che un test se
 * ne accorga.
 */

/** I nomi che vogliono dire "questo computer": un indirizzo di ritorno così si apre solo dal server. */
const THIS_COMPUTER = new Set(['localhost', '127.0.0.1', '[::1]']);

/** Dove tornerà la banca: l'indirizzo, e se è proprio quello della pagina da cui si parte. */
export interface ReturnAddress {
  url: string;
  samePage: boolean;
}

/** "schema://nome:porta", in minuscolo e con la porta di serie scritta; null se non è un indirizzo. */
export function originOf(url: string | null): string | null {
  if (!url || url === 'null') {
    return null;
  }
  try {
    const u = new URL(url.trim());
    const port = u.port || (u.protocol === 'https:' ? '443' : u.protocol === 'http:' ? '80' : '');
    return `${u.protocol}//${u.hostname.toLowerCase()}:${port}`;
  } catch {
    return null;
  }
}

/** Un indirizzo https con un nome che non è "questo computer" (in pratica quello di Tailscale). */
export function reachableFromEveryDevice(url: string): boolean {
  try {
    const u = new URL(url.trim());
    return u.protocol === 'https:' && !THIS_COMPUTER.has(u.hostname.toLowerCase());
  } catch {
    return false;
  }
}

/** L'indirizzo di ritorno per una pagina con questo origin, o null se non ce n'è uno che risponda. */
export function returnAddressFor(origin: string, urls: readonly string[]): ReturnAddress | null {
  const page = originOf(origin);
  const same = page === null ? undefined : urls.find((url) => originOf(url) === page);
  if (same !== undefined) {
    return { url: same, samePage: true };
  }
  const everywhere = urls.find(reachableFromEveryDevice);
  return everywhere === undefined ? null : { url: everywhere, samePage: false };
}

/** La guida, per i passi di Tailscale (capitolo "Tailscale"). */
export const GUIDE_URL = 'https://github.com/rosemgo/WalletTracker/blob/main/GUIDA.md';
