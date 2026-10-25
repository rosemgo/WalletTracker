import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AiSettingsPanel } from '../ai/ai-settings-panel';
import { SetupStore } from '../api/setup-store';
import { WalletApi } from '../api/wallet-api';
import { ThemeSection } from '../theme/theme-section';
import { FileDrop } from '../ui/file-drop';

/** Un Application ID di Enable Banking è un UUID, es. "cf589be3-3755-465b-a8df-a90a16a31403". */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Impostazioni: la configurazione guidata di Enable Banking.
 *
 * L'utente crea la sua applicazione sul sito di Enable Banking, poi qui scrive l'Application ID e sceglie
 * il file .pem con la chiave privata. Il backend verifica le credenziali con Enable Banking e, se vanno
 * bene, le salva.
 */
@Component({
  selector: 'app-settings-page',
  imports: [AiSettingsPanel, FileDrop, RouterLink, ThemeSection],
  templateUrl: './settings-page.html',
})
export class SettingsPage {
  private readonly api = inject(WalletApi);
  protected readonly setup = inject(SetupStore);

  protected readonly applicationId = signal('');
  protected readonly pem = signal<string | null>(null); // il contenuto del file .pem scelto
  protected readonly fileName = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly copied = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  /**
   * Chi ha scritto l'Application ID che c'è ora nel campo? true = l'abbiamo preso noi dal nome del file,
   * false = l'ha scritto l'utente (o il campo è vuoto). Serve a scegliere un file nuovo senza tenere l'ID
   * del file vecchio, e senza mai cancellare quello che l'utente ha scritto a mano.
   */
  private idFromFileName = false;

  onApplicationId(event: Event): void {
    this.applicationId.set((event.target as HTMLInputElement).value.trim());
    this.idFromFileName = false; // da qui in poi il valore è dell'utente
  }

  /**
   * L'utente ha scelto il file .pem. Lo leggiamo NEL BROWSER (`file.text()`): il contenuto parte verso il
   * backend solo quando l'utente preme "Verifica e salva".
   *
   * `async`/`await`: `file.text()` restituisce una Promise, cioè un valore che arriverà (leggere un file
   * richiede un attimo). `await` aspetta quel valore prima di continuare, senza bloccare la pagina.
   */
  async onFile(event: Event): Promise<void> {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    this.fileName.set(file.name);
    this.pem.set(await file.text());

    // Enable Banking chiama il file con l'Application ID (es. "cf589be3-....pem"). Se il nome ha quella
    // forma, è l'ID giusto per QUESTO file: lo mettiamo nel campo, anche al posto di un ID precedente.
    const nameWithoutExtension = file.name.replace(/\.pem$/i, '');
    if (UUID.test(nameWithoutExtension)) {
      this.applicationId.set(nameWithoutExtension);
      this.idFromFileName = true;
    } else if (this.idFromFileName) {
      // File rinominato (es. "chiave.pem"): non sappiamo il suo ID. Quello nel campo veniva dal file
      // precedente e sarebbe sbagliato, quindi lo togliamo: l'utente lo scriverà a mano.
      this.applicationId.set('');
      this.idFromFileName = false;
    }
    // Altrimenti il campo è vuoto o l'ha scritto l'utente: non lo tocchiamo.
  }

  /** Copia l'indirizzo di ritorno negli appunti, da incollare sul sito di Enable Banking. */
  copyRedirectUrl(url: string): void {
    navigator.clipboard?.writeText(url).then(() => this.copied.set(true));
  }

  save(): void {
    const pem = this.pem();
    if (this.applicationId() === '' || pem === null) {
      this.error.set("Scrivi l'Application ID e scegli il file .pem.");
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.message.set(null);
    this.api.saveEnableBanking(this.applicationId(), pem).subscribe({
      next: (status) => {
        this.saving.set(false);
        this.setup.status.set(status); // aggiorna lo stato condiviso: l'avviso in alto sparisce
        this.message.set('Credenziali verificate e salvate.');
      },
      error: (response) => {
        this.saving.set(false);
        this.error.set(response.error?.detail ?? 'Le credenziali non sono state salvate.');
      },
    });
  }
}
