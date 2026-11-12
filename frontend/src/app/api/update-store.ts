import { Injectable, computed, inject, signal } from '@angular/core';

import { UpdateStatus } from './models';
import { WalletApi } from './wallet-api';

/**
 * C'è una versione nuova di WalletTracker? (docs/50, parte J). CONDIVISO come i backup (BackupStore): lo leggono la
 * striscia in cima all'app e la voce Informazioni delle Impostazioni.
 *
 * La striscia si può chiudere; "chiusa" resta solo in memoria, apposta: a ogni nuovo ingresso nell'app (la pagina
 * riaperta o ricaricata) ricompare, finché non si aggiorna.
 */
@Injectable({ providedIn: 'root' })
export class UpdateStore {
  private readonly api = inject(WalletApi);

  /** L'ultima risposta; null finché il backend non ha risposto (o se non risponde: niente avvisi). */
  readonly status = signal<UpdateStatus | null>(null);
  private readonly dismissed = signal(false);

  /** La versione nuova da annunciare nella striscia, o null. */
  readonly bannerVersion = computed(() => {
    const status = this.status();
    return status?.available && !this.dismissed() ? status.latestVersion : null;
  });

  refresh(): void {
    this.api.updateStatus().subscribe({
      next: (status) => this.status.set(status),
      error: () => this.status.set(null),
    });
  }

  dismiss(): void {
    this.dismissed.set(true);
  }
}
