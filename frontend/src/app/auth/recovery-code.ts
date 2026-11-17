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
      <h2 i18n>Il tuo codice di recupero</h2>
      <p class="recovery-code" aria-label="Codice di recupero" i18n-aria-label>{{ code() }}</p>
      <button type="button" class="link" (click)="copy()">
        <ng-container i18n>{!!copied(), select,
          true {Copiato ✓}
          other {Copia il codice}
        }</ng-container>
      </button>
      <p i18n class="hint">
        Se dimentichi il PIN, con questo codice ne scegli uno nuovo, anche da un dispositivo remoto.
        Salvalo adesso dove salvi le cose importanti (un gestore di password, un foglio in un
        cassetto):
        <strong>non lo vedrai più</strong>. Vale una volta sola: dopo l'uso, l'app te ne dà uno
        nuovo.
      </p>
      <button i18n type="button" class="primary" (click)="saved.emit()">
        L'ho salvato, continua
      </button>
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
