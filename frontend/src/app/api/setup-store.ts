import { Injectable, inject, signal } from '@angular/core';

import { SetupStatus } from './models';
import { WalletApi } from './wallet-api';

/**
 * Lo stato della configurazione di Enable Banking, CONDIVISO da tutta l'applicazione.
 *
 * Due componenti diversi ne hanno bisogno: la barra in alto (App), che mostra un avviso se Enable Banking
 * non è configurato, e la pagina Impostazioni, che lo configura. Mettendo il signal in un servizio
 * `providedIn: 'root'` (una sola istanza), entrambi leggono lo STESSO signal: quando la pagina Impostazioni
 * salva le credenziali, l'avviso nella barra sparisce da solo.
 */
@Injectable({ providedIn: 'root' })
export class SetupStore {
  private readonly api = inject(WalletApi);

  /** Lo stato attuale; null finché non è arrivata la risposta del backend. */
  readonly status = signal<SetupStatus | null>(null);

  /** Chiede lo stato al backend. Se il backend non risponde, lascia lo stato a null (nessun avviso). */
  refresh(): void {
    this.api.setupStatus().subscribe({
      next: (status) => this.status.set(status),
      error: () => this.status.set(null),
    });
  }
}
