import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';

/**
 * Lo stato dell'app installabile (docs/42, parte E): c'è la rete? È pronta una versione nuova?
 *
 * - **La rete.** Il browser dice se è collegato (navigator.onLine) e avvisa quando cambia (gli eventi "online" e
 *   "offline"). Senza rete il service worker mostra gli ultimi dati scaricati: la pagina lo dice, così nessuno li
 *   scambia per quelli di adesso.
 * - **Le versioni nuove.** Quando l'app si apre, il service worker controlla se sul server c'è una versione nuova e
 *   la scarica in sottofondo; quando è pronta (VERSION_READY) lo diciamo, e un tocco su "Aggiorna" ricarica la
 *   pagina con quella. Non la cambiamo da soli: chi sta scrivendo una nota perderebbe il testo. Controlliamo anche
 *   quando l'app torna in primo piano: sul telefono un'app installata resta aperta per giorni.
 *
 * SwUpdate c'è solo se il service worker è stato registrato (provideServiceWorker in app.config.ts), e funziona solo
 * nella build di produzione: con ng serve e nei test {@code isEnabled} è false e questa parte non fa niente.
 */
@Injectable({ providedIn: 'root' })
export class AppStatus {
  private readonly document = inject(DOCUMENT);
  private readonly updates = inject(SwUpdate, { optional: true });

  /** true = il browser è collegato alla rete. */
  readonly online = signal(this.document.defaultView?.navigator.onLine ?? true);
  /** true = è pronta una versione nuova dell'app: basta ricaricare. */
  readonly updateReady = signal(false);

  constructor() {
    const window = this.document.defaultView;
    window?.addEventListener('online', () => this.online.set(true));
    window?.addEventListener('offline', () => this.online.set(false));

    if (this.updates?.isEnabled) {
      this.updates.versionUpdates
        .pipe(filter((event) => event.type === 'VERSION_READY'))
        .subscribe(() => this.updateReady.set(true));
      // Un caso raro: la versione in memoria non si può più completare (file spariti dal server). Ricaricare la
      // pagina la sistema: lo proponiamo con lo stesso avviso.
      this.updates.unrecoverable.subscribe(() => this.updateReady.set(true));
      this.document.addEventListener('visibilitychange', () => {
        if (this.document.visibilityState === 'visible') {
          this.updates?.checkForUpdate().catch(() => undefined); // senza rete: si riproverà
        }
      });
    }
  }

  /** Ricarica la pagina: si apre con la versione nuova. */
  reload(): void {
    this.document.location.reload();
  }
}
