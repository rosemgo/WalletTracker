import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';

import { AiProvider, AiStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Ogni quanto chiedere lo stato mentre il modello si scarica. */
const POLL_MS = 1000;

/**
 * Impostazioni → Suggerimenti con l'intelligenza artificiale (Fase 3, passo 7b).
 *
 * L'utente sceglie se usare l'IA (spenta di serie) e quale modello; vede se Ollama risponde e se il modello
 * è già scaricato; se manca, lo scarica da qui, senza aprire il terminale.
 *
 * Lo scaricamento dura minuti e avviene nel backend: questa pagina chiede lo stato ogni secondo finché
 * non è finito (polling). Il timer si ferma anche se si lascia la pagina (DestroyRef).
 */
@Component({
  selector: 'app-ai-settings-panel',
  templateUrl: './ai-settings-panel.html',
})
export class AiSettingsPanel implements OnInit {
  private readonly api = inject(WalletApi);
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected readonly status = signal<AiStatus | null>(null);
  protected readonly provider = signal<AiProvider>('NONE');
  protected readonly model = signal('');
  protected readonly saving = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  /** La percentuale della parte che si sta scaricando, o null se Ollama non la dice. */
  protected readonly percent = computed(() => {
    const download = this.status()?.download;
    if (!download?.running || !download.total || download.completed === null) {
      return null;
    }
    return Math.floor((download.completed / download.total) * 100);
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  ngOnInit(): void {
    this.load(true);
  }

  save(): void {
    this.saving.set(true);
    this.message.set(null);
    this.error.set(null);
    this.api.changeAi(this.provider(), this.model().trim()).subscribe({
      next: (status) => {
        this.saving.set(false);
        this.show(status, true);
        this.message.set(
          status.provider === 'NONE'
            ? "Suggerimenti con l'IA spenti."
            : `Suggerimenti con l'IA accesi, con il modello ${status.ollamaModel}.`,
        );
      },
      error: (response) => {
        this.saving.set(false);
        this.error.set(
          response.error?.detail ??
            'Impostazioni non salvate: controlla il nome del modello (es. qwen2.5:3b).',
        );
      },
    });
  }

  download(): void {
    this.error.set(null);
    this.api.downloadAiModel().subscribe({
      next: (status) => this.show(status, false),
      error: (response) =>
        this.error.set(response.error?.detail ?? 'Lo scaricamento non è partito.'),
    });
  }

  /** "2,1 GB": i byte in un formato leggibile. */
  protected size(bytes: number | null): string {
    if (bytes === null) {
      return '';
    }
    const gb = bytes / 1024 ** 3;
    return gb >= 1
      ? `${gb.toFixed(1).replace('.', ',')} GB`
      : `${Math.round(bytes / 1024 ** 2)} MB`;
  }

  /**
   * Mostra lo stato. Con `form` = true aggiorna anche il modulo (scelta e modello): lo facciamo all'apertura
   * e dopo il salvataggio, non durante il polling, per non cancellare quello che l'utente sta scrivendo.
   */
  private show(status: AiStatus, form: boolean): void {
    this.status.set(status);
    if (form) {
      this.provider.set(status.provider);
      this.model.set(status.ollamaModel);
    }
    clearTimeout(this.timer);
    if (status.download?.running) {
      this.timer = setTimeout(() => this.load(false), POLL_MS);
    }
  }

  private load(form: boolean): void {
    this.api.aiStatus().subscribe({
      next: (status) => this.show(status, form),
      error: () =>
        this.error.set("Impossibile leggere le impostazioni dell'IA: il backend è avviato?"),
    });
  }
}
