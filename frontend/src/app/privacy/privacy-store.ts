import { Injectable, signal } from '@angular/core';

/** Dove il browser ricorda la modalità discreta (vale solo su questo dispositivo). */
export const DISCREET_KEY = 'wallettracker.discreet';

/** Che cosa prende il posto di una cifra nascosta. */
export const HIDDEN = '•••';

/**
 * La modalità discreta (docs/42, parte B): un tocco sull'occhio nasconde gli importi, per quando qualcuno può vedere
 * lo schermo (in metro, in ufficio). La prossima apertura la ricorda.
 *
 * Gli importi non vengono sfocati ma **sostituiti**: "1.284 €" diventa "••• €". Un testo sfocato a volte si legge
 * lo stesso, e resterebbe nella pagina (per esempio copiandolo). Lo fanno due strumenti che leggono {@link discreet}:
 * la pipe `money` per i template e {@link hide} per le frasi scritte nel codice.
 */
@Injectable({ providedIn: 'root' })
export class PrivacyStore {
  /** true = gli importi sono nascosti. */
  readonly discreet = signal(this.saved());

  toggle(): void {
    const discreet = !this.discreet();
    this.discreet.set(discreet);
    try {
      if (discreet) {
        localStorage.setItem(DISCREET_KEY, '1');
      } else {
        localStorage.removeItem(DISCREET_KEY);
      }
    } catch {
      // Non ricordata: vale fino alla chiusura della pagina.
    }
  }

  /**
   * Un testo con degli importi, con le cifre nascoste se la modalità è attiva: "25 € in meno del solito" diventa
   * "••• € in meno del solito". Chiamata dentro un computed(), lo fa ricalcolare quando la modalità cambia (legge
   * il signal discreet).
   */
  hide(text: string): string {
    return this.discreet() ? hideAmounts(text) : text;
  }

  private saved(): boolean {
    try {
      return localStorage.getItem(DISCREET_KEY) === '1';
    } catch {
      return false;
    }
  }
}

/**
 * Le cifre di un testo sostituite da "•••", segno compreso (il segno dice già qualcosa: entrata o uscita). Una
 * cifra, seguita da cifre, punti e virgole: "1.284", "-76,29", "+829". Toglie anche un segno rimasto davanti a cifre
 * già nascoste: una frase come "+17 € sul solito" a volte si compone mettendo il segno davanti a un importo già
 * nascosto ("+" e "••• €").
 */
export function hideAmounts(text: string): string {
  return text.replace(/[-+−]?\d[\d.,]*/g, HIDDEN).replace(/[-+−](?=•)/g, '');
}
