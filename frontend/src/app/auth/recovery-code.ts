import { Component, input, output, signal } from '@angular/core';

/**
 * Il codice di recupero, mostrato **una sola volta** (docs/46, parte E): quando si crea il PIN, e dopo un recupero.
 * Il backend non lo conserva in chiaro (solo il suo hash), quindi non lo potrà mai più mostrare: per questo la pagina
 * chiede di salvarlo prima di andare avanti.
 */
@Component({
  selector: 'app-recovery-code',
  template: `
    <div class="card recovery">
      <h2>Il tuo codice di recupero</h2>
      <p class="recovery-code" aria-label="Codice di recupero">{{ code() }}</p>
      <button type="button" class="link" (click)="copy()">
        {{ copied() ? 'Copiato ✓' : 'Copia il codice' }}
      </button>
      <p class="hint">
        Se dimentichi il PIN, con questo codice ne scegli uno nuovo, anche dal telefono. Salvalo
        adesso dove salvi le cose importanti (un gestore di password, un foglio in un cassetto):
        <strong>non lo vedrai più</strong>. Vale una volta sola: dopo l'uso, l'app te ne dà uno
        nuovo.
      </p>
      <button type="button" class="primary" (click)="saved.emit()">L'ho salvato, continua</button>
    </div>
  `,
})
export class RecoveryCodeView {
  readonly code = input.required<string>();
  /** "L'ho salvato": chi mostra il codice decide che cosa fare dopo (entrare, chiudere il riquadro). */
  readonly saved = output<void>();

  protected readonly copied = signal(false);

  protected copy(): void {
    navigator.clipboard
      ?.writeText(this.code())
      .then(() => this.copied.set(true))
      .catch(() => undefined); // senza permesso, il codice resta da copiare a mano
  }
}
