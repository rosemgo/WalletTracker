import { Component, inject, input, model, output, signal } from '@angular/core';

import { AiCloudProvider, AiCloudStatus, AiConnectionTest, AiStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Quello che serve sapere di un servizio cloud, per spiegarlo a chi non è un informatico. */
interface CloudInfo {
  label: string;
  about: string;
  keyUrl: string;
  keySteps: string[];
  dataNote: string;
  /** I modelli consigliati, con che cosa cambia tra l'uno e l'altro: il campo resta libero per tutti gli altri. */
  models: { name: string; note: string }[];
}

/**
 * I testi del servizio. Le regole (piani, prezzi, uso dei dati) cambiano: per questo rimandiamo sempre al sito
 * del fornitore, invece di scriverle qui come se fossero fisse.
 */
export const CLOUD_INFO: Record<AiCloudProvider, CloudInfo> = {
  GEMINI: {
    label: 'Google Gemini',
    about: 'Gemini è il servizio di intelligenza artificiale di Google.',
    keyUrl: 'https://aistudio.google.com',
    keySteps: [
      'Apri aistudio.google.com ed entra con il tuo account Google.',
      'Premi "Get API key", poi "Create API key", e copia la chiave.',
      'Incollala qui sotto e premi "Salva la chiave".',
    ],
    dataNote:
      'Attenzione: con il piano gratuito Google può usare i dati inviati per migliorare i suoi prodotti. ' +
      'Leggi le condizioni sul sito prima di accettare.',
    // Misurati con "Prova la connessione" (docs/30, parte O6): con il piano gratuito il Flash più recente può
    // aspettare minuti in coda, il Lite risponde in pochi secondi.
    models: [
      {
        name: 'gemini-flash-lite-latest',
        note: 'veloce: di solito risponde in pochi secondi (è quello predefinito)',
      },
      {
        name: 'gemini-flash-latest',
        note: 'ragiona prima di rispondere, quindi può essere più preciso, ma con il piano gratuito può impiegare minuti',
      },
    ],
  },
};

/**
 * Un servizio cloud nelle Impostazioni (passo 7c): la chiave, il modello, il consenso, "Prova la connessione".
 * Il componente è scritto per un servizio qualunque (`provider`): oggi c'è solo Gemini.
 *
 * Come parla con il pannello che lo contiene:
 * - `provider` e `cloud` sono **input** (dal pannello al componente): quale servizio, e il suo stato;
 * - `aiModel` e `consent` sono **model**: un input che il componente può anche cambiare, e il pannello vede il
 *   valore nuovo (`[(aiModel)]="geminiModel"`, il "two-way binding"). Il pannello li usa quando salva;
 * - `statusChange` è un **output** (dal componente al pannello): dopo aver salvato o tolto la chiave, il backend
 *   restituisce lo stato nuovo, e il pannello lo mostra.
 *
 * La chiave si scrive in un campo password, va al backend una volta (che la cifra) e non torna più indietro: la
 * pagina vede solo le ultime 4 lettere.
 */
@Component({
  selector: 'app-cloud-ai-section',
  templateUrl: './cloud-ai-section.html',
})
export class CloudAiSection {
  private readonly api = inject(WalletApi);

  readonly provider = input.required<AiCloudProvider>();
  readonly cloud = input.required<AiCloudStatus>();
  readonly aiModel = model.required<string>();
  readonly consent = model(false);
  readonly statusChange = output<AiStatus>();

  protected readonly key = signal('');
  protected readonly editing = signal(false); // "Cambia": si mostra di nuovo il campo della chiave
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly test = signal<AiConnectionTest | null>(null);

  protected info(): CloudInfo {
    return CLOUD_INFO[this.provider()];
  }

  saveKey(): void {
    this.run(
      this.api.saveAiKey(this.provider(), this.key().trim()),
      'La chiave non è stata salvata.',
    );
  }

  deleteKey(): void {
    this.run(this.api.deleteAiKey(this.provider()), 'La chiave non è stata tolta.');
  }

  testConnection(): void {
    this.busy.set(true);
    this.test.set(null);
    this.error.set(null);
    this.api.testAiConnection(this.provider()).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.test.set(result);
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(response.error?.detail ?? 'La prova non è partita.');
      },
    });
  }

  /** Salva o toglie la chiave; poi il campo si svuota (la chiave non resta nella pagina). */
  private run(request: ReturnType<WalletApi['saveAiKey']>, failure: string): void {
    this.busy.set(true);
    this.error.set(null);
    this.test.set(null);
    request.subscribe({
      next: (status) => {
        this.busy.set(false);
        this.key.set('');
        this.editing.set(false);
        this.statusChange.emit(status);
      },
      error: (response) => {
        this.busy.set(false);
        this.error.set(
          response.status === 400
            ? 'Questa non sembra una chiave: controlla di averla copiata intera.'
            : failure,
        );
      },
    });
  }

  /** "1,2 s" */
  protected seconds(value: number): string {
    return value.toFixed(1).replace('.', ',') + ' s';
  }
}
