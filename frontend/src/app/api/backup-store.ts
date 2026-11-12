import { Injectable, inject, signal } from '@angular/core';

import { BackupStatus } from './models';
import { WalletApi } from './wallet-api';
import { reason } from '../auth/auth-store';

/**
 * Lo stato dei backup (docs/48, parte Q), CONDIVISO come quello di Enable Banking (SetupStore): lo leggono l'avviso in
 * cima all'app e la sezione Backup delle Impostazioni.
 *
 * "Fai un backup adesso" non aspetta il backup: il backend lascia una richiesta al container dei backup e risponde
 * subito (202). Allora qui si richiede lo stato ogni 3 secondi, finché il backup non è finito (al massimo 3 minuti).
 */
@Injectable({ providedIn: 'root' })
export class BackupStore {
  private readonly api = inject(WalletApi);

  /** Lo stato; null finché il backend non ha risposto (o se non risponde: niente avvisi). */
  readonly status = signal<BackupStatus | null>(null);
  /** L'errore dell'ultima richiesta di backup, da mostrare accanto al pulsante. */
  readonly error = signal<string | null>(null);

  private polls = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  refresh(): void {
    this.api.backupStatus().subscribe({
      next: (status) => {
        this.status.set(status);
        this.pollWhileInProgress(status);
      },
      error: () => this.status.set(null),
    });
  }

  backupNow(): void {
    this.error.set(null);
    this.api.requestBackup().subscribe({
      next: (status) => {
        this.status.set(status);
        this.polls = 0;
        this.pollWhileInProgress(status);
      },
      error: (error: unknown) => this.error.set(reason(error, 'Il server non risponde: riprova.')),
    });
  }

  /** Un altro giro fra 3 secondi, se il backup non è finito. Uno solo alla volta, e non per sempre. */
  private pollWhileInProgress(status: BackupStatus): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (status.inProgress && this.polls < 60) {
      this.polls++;
      this.timer = setTimeout(() => {
        this.timer = null;
        this.refresh();
      }, 3000);
    }
  }
}
