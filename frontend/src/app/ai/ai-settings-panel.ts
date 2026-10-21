import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';

import { AiProvider, AiStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Ogni quanto chiedere lo stato mentre il modello si scarica o la misura è in corso. */
const POLL_MS = 1000;

/** Un modello consigliato, con i numeri misurati (docs/28, Parte G). */
export interface RecommendedModel {
  name: string;
  note: string;
  download: string; // quanto pesa da scaricare
  memory: string; // quanta memoria usa mentre lavora
  precision: string; // risposte giuste sui 49 esercenti di prova
}

/**
 * I modelli consigliati: scelti misurando, non a occhio (docs/28, Parte G). Usano la stessa memoria; il secondo
 * è circa tre volte più veloce ma sbaglia di più. Più piccoli non conviene: gemma3:1b ha indovinato il 12%.
 * Più grandi nemmeno: qwen2.5:7b, con il doppio della memoria, non è stato più preciso di gemma3:4b.
 */
export const RECOMMENDED_MODELS: RecommendedModel[] = [
  {
    name: 'gemma3:4b',
    note: 'consigliato, per computer con almeno 8 GB di memoria',
    download: '3,3 GB',
    memory: 'circa 3 GB',
    precision: '76%, 82% con le tue scelte come esempi',
  },
  {
    name: 'llama3.2:3b',
    note: 'circa tre volte più veloce, ma sbaglia di più: per computer lenti',
    download: '2,0 GB',
    memory: 'circa 3 GB',
    precision: '57%',
  },
];

/**
 * Impostazioni → Suggerimenti con l'intelligenza artificiale (Fase 3, passo 7b).
 *
 * L'utente sceglie se usare l'IA (spenta di serie) e quale modello; vede se Ollama risponde e se il modello
 * è già scaricato; se manca, lo scarica da qui, senza aprire il terminale.
 *
 * Lo scaricamento dura minuti e avviene nel backend: questa pagina chiede lo stato ogni secondo finché
 * non è finito (polling). Il timer si ferma anche se si lascia la pagina (DestroyRef).
 *
 * "Misura la precisione" (passo 7b.2): il backend chiede al modello la categoria di alcuni esercenti che hai
 * già categorizzato a mano, senza dirgli la risposta, e conta quante volte indovina. Anche questa è in
 * background, con lo stesso polling.
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

  protected readonly recommended = RECOMMENDED_MODELS;
  /** Le scelte della misura: modello (tra quelli scaricati) e quanti esercenti. */
  protected readonly evaluationModel = signal('');
  protected readonly samples = signal(20);
  protected readonly evaluationError = signal<string | null>(null);

  /** La percentuale di risposte giuste della misura, su quelle fatte finora. */
  protected readonly precision = computed(() => {
    const evaluation = this.status()?.evaluation;
    return evaluation && evaluation.done > 0
      ? Math.round((evaluation.right / evaluation.done) * 100)
      : null;
  });

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
            'Impostazioni non salvate: controlla il nome del modello (es. gemma3:4b).',
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

  /** "Usa questo" accanto a un modello consigliato: lo scrive nel campo (poi si salva). */
  choose(model: string): void {
    this.model.set(model);
  }

  evaluate(): void {
    this.evaluationError.set(null);
    this.api.evaluateAi(this.evaluationModel(), this.samples()).subscribe({
      next: (status) => this.show(status, false),
      error: (response) =>
        this.evaluationError.set(response.error?.detail ?? 'La misura non è partita.'),
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
    // Il modello da misurare: quello scelto, se è scaricato, altrimenti il primo scaricato.
    if (!status.installedModels.includes(this.evaluationModel())) {
      this.evaluationModel.set(
        status.installedModels.find((m) => m === status.ollamaModel) ??
          status.installedModels[0] ??
          '',
      );
    }
    clearTimeout(this.timer);
    if (status.download?.running || status.evaluation?.running) {
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
