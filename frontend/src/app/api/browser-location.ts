import { DOCUMENT, Injectable, inject } from '@angular/core';

/**
 * Manda il browser a un altro sito (qui: la pagina di login della banca).
 *
 * Perché un servizio per una riga sola? Perché nei test non vogliamo che il browser simulato provi
 * davvero a lasciare la pagina: al suo posto diamo un servizio finto che si limita a ricordare l'indirizzo.
 */
@Injectable({ providedIn: 'root' })
export class BrowserLocation {
  private readonly document = inject(DOCUMENT);

  go(url: string): void {
    this.document.location.assign(url);
  }
}
