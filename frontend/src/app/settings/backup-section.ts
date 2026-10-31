import { DatePipe } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';

import { BackupStore } from '../api/backup-store';
import { BackupFile } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** I tipi di backup, come li vede chi usa l'app. */
const TYPES: Record<BackupFile['type'], string> = {
  manuale: 'Fatto a mano',
  automatico: 'Automatico',
  'prima-del-ripristino': 'Prima di un ripristino',
};

/**
 * "Backup", nelle Impostazioni (docs/48, parte Q): lo stato, "Fai un backup adesso" e l'elenco, con "Scarica" solo dal
 * server. Il ripristino e la password dei backup restano allo script di installazione: qui lo si dice e basta.
 */
@Component({
  selector: 'app-backup-section',
  imports: [DatePipe],
  template: `
    @if (backups.status(); as status) {
      @if (!status.available) {
        <p class="hint">
          I backup li fa l'app installata con Docker (lo script di installazione, docs/48): qui non
          ci sono.
        </p>
      } @else {
        @if (!status.serviceRunning) {
          <p class="error">
            Il servizio dei backup non risponde: dal server, controlla con
            <code>docker compose ps</code>.
          </p>
        }
        @if (status.automaticEnabled) {
          <p class="message">
            ✓ Backup automatico acceso: uno al giorno, dalle {{ status.backupTime }}; si tengono gli
            ultimi {{ status.keep }}.
          </p>
        } @else {
          <p class="error">
            Backup automatico spento: manca la password dei backup. Dal server, lancia lo script di
            installazione con <code>-Backup</code> (Windows) o <code>--backup</code> (docs/48).
          </p>
        }
        <p data-testid="last-backup">
          Ultimo backup:
          @if (status.lastSuccessAt) {
            <strong>{{ status.lastSuccessAt | date: 'd MMMM y, HH:mm' }}</strong>
          } @else {
            <strong>nessuno</strong>
          }
        </p>
        @if (status.lastError) {
          <p class="error" role="alert">{{ status.lastError }}</p>
        }
        <div class="form-button">
          <button
            type="button"
            [disabled]="status.inProgress || !status.serviceRunning || !status.automaticEnabled"
            (click)="backups.backupNow()"
          >
            {{ status.inProgress ? 'Backup in corso…' : 'Fai un backup adesso' }}
          </button>
        </div>
        @if (backups.error(); as message) {
          <p class="error" role="alert">{{ message }}</p>
        }

        @if (status.backups.length > 0) {
          <ul class="backup-list">
            @for (file of status.backups; track file.name) {
              <li>
                <span class="backup-type">{{ type(file) }}</span>
                <span>{{ file.createdAt | date: 'd MMM y, HH:mm' }}</span>
                <span class="hint">{{ size(file.sizeBytes) }}</span>
                @if (status.canDownload) {
                  <a [href]="url(file)" download>Scarica</a>
                }
              </li>
            }
          </ul>
          @if (!status.canDownload) {
            <p class="hint">
              I backup si scaricano solo dal server (<code>http://localhost:8000</code>): sono
              cifrati, ma sono tutti i tuoi dati.
            </p>
          }
        }
        <p class="hint">
          I backup stanno nella cartella <code>backup</code> del server (o in quella scelta nel
          .env): copiali ogni tanto anche fuori. Il ripristino si fa dal server, con lo script di
          installazione (<code>-Ripristina</code>): docs/48.
        </p>
      }
    } @else {
      <p class="hint">Caricamento…</p>
    }
  `,
})
export class BackupSection implements OnInit {
  protected readonly backups = inject(BackupStore);
  private readonly api = inject(WalletApi);

  ngOnInit(): void {
    this.backups.refresh();
  }

  protected type(file: BackupFile): string {
    return TYPES[file.type];
  }

  protected url(file: BackupFile): string {
    return this.api.backupDownloadUrl(file.name);
  }

  /** "40 kB", "2,3 MB": i byte in una misura che si legge. */
  protected size(bytes: number): string {
    if (bytes < 1024 * 1024) {
      return `${Math.max(1, Math.round(bytes / 1024))} kB`;
    }
    return `${(bytes / (1024 * 1024)).toLocaleString('it-IT', { maximumFractionDigits: 1 })} MB`;
  }
}
