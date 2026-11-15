import { formatNumber } from '@angular/common';
import { Component, LOCALE_ID, OnInit, inject, signal } from '@angular/core';

import { BackupStore } from '../api/backup-store';
import { BackupFile } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { LocalDatePipe } from '../text/dates';

/** I tipi di backup, come li vede chi usa l'app. */
const TYPES: Record<BackupFile['type'], string> = {
  manuale: $localize`Fatto a mano`,
  automatico: $localize`Automatico`,
  'prima-del-ripristino': $localize`Prima di un ripristino`,
  'prima-dell-aggiornamento': $localize`Prima di un aggiornamento`,
};

/**
 * "Backup", nelle Impostazioni (docs/48, parte Q): lo stato, "Fai un backup adesso" e l'elenco, con "Scarica" solo dal
 * server. Il ripristino e la password dei backup restano allo script di installazione: qui lo si dice e basta.
 */
@Component({
  selector: 'app-backup-section',
  imports: [LocalDatePipe],
  // Sul telefono: un percorso lungo (D:/Backup/...) va a capo dove serve; un'opzione (-CartellaBackup) mai al trattino.
  styles: '.path { overflow-wrap: anywhere; } .option { white-space: nowrap; }',
  template: `
    @if (backups.status(); as status) {
      @if (!status.available) {
        <p i18n class="hint">
          I backup li fa l'app installata con Docker (lo script di installazione, docs/48): qui non
          ci sono.
        </p>
      } @else {
        @if (!status.serviceRunning) {
          <p i18n class="error">
            Il servizio dei backup non risponde: dal server, controlla con
            <code>docker compose ps</code>.
          </p>
        }
        @if (status.automaticEnabled) {
          <p i18n class="message">
            ✓ Backup automatico acceso: uno al giorno, dalle {{ status.backupTime }}.
          </p>
        } @else {
          <p i18n class="error">
            Backup automatico spento: manca la password dei backup. Dal server, lancia lo script di
            installazione con <code>-Backup</code> (Windows) o <code>--backup</code> (docs/48).
          </p>
        }
        <p i18n data-testid="last-backup">
          Ultimo backup:
          @if (status.lastSuccessAt) {
            <strong>{{ status.lastSuccessAt | localDate: 'longTime' }}</strong>
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
            <ng-container i18n>{!!status.inProgress, select,
              true {Backup in corso…}
              other {Fai un backup adesso}
            }</ng-container>
          </button>
        </div>
        @if (backups.error(); as message) {
          <p class="error" role="alert">{{ message }}</p>
        }

        @if (status.backups.length > 0) {
          <ul class="backup-list">
            @for (file of shown(status.backups); track file.name) {
              <li>
                <span class="backup-type">{{ type(file) }}</span>
                <span>{{ file.createdAt | localDate: 'mediumTime' }}</span>
                <span class="hint">{{ size(file.sizeBytes) }}</span>
                @if (status.canDownload) {
                  <a i18n class="action" [href]="url(file)" download>Scarica</a>
                }
              </li>
            }
          </ul>
          @if (status.backups.length >= shortenFrom) {
            <button
              type="button"
              class="link show-all"
              [attr.aria-expanded]="showAll()"
              (click)="showAll.set(!showAll())"
            >
              <ng-container i18n>{!!showAll(), select,
                true {Mostra meno}
                other {Mostra tutti ({{ status.backups.length }})}
              }</ng-container>
            </button>
          }
          <!-- Quanti se ne tengono (docs/48, parte G2): li cancella il container dei backup, non l'app. -->
          <p class="hint" data-testid="backup-keep">
            @if (status.keepManual > 0) {
              <ng-container i18n>
                Si tengono gli ultimi {{ status.keep }} automatici, {{ status.keepManual }} fatti a
                mano e {{ status.keepBefore }} di prima di ogni aggiornamento o ripristino: i più
                vecchi si cancellano da soli. Per tenerne uno per sempre, scaricalo.
              </ng-container>
            } @else {
              <ng-container i18n>
                Si tengono gli ultimi {{ status.keep }} backup automatici.
              </ng-container>
            }
          </p>
          @if (!status.canDownload) {
            <p i18n class="hint">
              I backup si scaricano solo dal server (<code>http://localhost:8000</code>): sono
              cifrati, ma sono tutti i tuoi dati.
            </p>
          }
        }
        <p i18n data-testid="backup-folder">
          I backup sono in:
          @if (status.serverDirectory) {
            <code class="path">{{ status.serverDirectory }}</code>
          } @else {
            la cartella <code>backup</code> di WalletTracker, sul server
          }
        </p>
        <p i18n class="hint">
          Per cambiarla (un disco esterno, Google Drive) lancia dal server lo script di
          installazione con <code class="option">-CartellaBackup</code> (Windows) o
          <code class="option">--cartella-backup</code>. Il ripristino si fa dal server, con
          <code class="option">-Ripristina</code>: docs/48.
        </p>
      }
    } @else {
      <p i18n class="hint">Caricamento…</p>
    }
  `,
})
export class BackupSection implements OnInit {
  protected readonly backups = inject(BackupStore);

  /** Come gli elenchi di Investimenti: i primi 5, e "Mostra tutti" solo da 8 in su (per una o due righe non serve). */
  protected readonly shortenFrom = 8;
  private readonly firstRows = 5;
  protected readonly showAll = signal(false);
  private readonly api = inject(WalletApi);
  private readonly locale = inject(LOCALE_ID);

  ngOnInit(): void {
    this.backups.refresh();
  }

  /** I backup da mostrare: tutti se sono pochi o se l'elenco è aperto, altrimenti i 5 più nuovi. */
  protected shown(files: BackupFile[]): BackupFile[] {
    return files.length < this.shortenFrom || this.showAll()
      ? files
      : files.slice(0, this.firstRows);
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
    // '1.0-1': almeno una cifra intera, al massimo un decimale; la virgola o il punto li mette la lingua.
    return `${formatNumber(bytes / (1024 * 1024), this.locale, '1.0-1')} MB`;
  }
}
