import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AuthStore, reason } from './auth-store';
import { VALID_PIN } from './pin-page';
import { RecoveryCodeView } from './recovery-code';

/**
 * "Accesso", nelle Impostazioni (docs/46, parte F): com'è protetta l'app, e come cambiarlo.
 * - senza PIN: "Proteggi con un PIN" (poi il codice di recupero, da salvare);
 * - con il PIN: "Togli il PIN" (serve il PIN di adesso).
 * Tutte e due si fanno solo dal server: da un dispositivo remoto la sezione lo spiega e basta.
 */
@Component({
  selector: 'app-access-section',
  imports: [FormsModule, RecoveryCodeView],
  template: `
    @if (newCode(); as code) {
      <app-recovery-code [code]="code" (saved)="newCode.set(null)" />
    } @else if (auth.status(); as status) {
      @if (status.pinSet) {
        <p class="message">✓ L'app è protetta da un PIN: lo chiede su ogni dispositivo.</p>
        @if (status.local) {
          <form class="form-grid" (ngSubmit)="remove()">
            <label>
              PIN di adesso
              <input
                type="password"
                inputmode="numeric"
                autocomplete="current-password"
                maxlength="10"
                name="current"
                [(ngModel)]="pin"
              />
            </label>
            <div class="form-button">
              <button type="submit" [disabled]="busy()">Togli il PIN</button>
            </div>
          </form>
          <p class="hint">
            Senza PIN si entra solo dal server; dai dispositivi remoti non si entrerà più, finché
            non ne crei uno nuovo.
          </p>
        } @else {
          <p class="hint">
            Il PIN si toglie solo dal server, il computer su cui è installata l'app.
          </p>
        }
      } @else {
        <p>L'app è aperta senza PIN, solo dal server. Dai dispositivi remoti non si entra.</p>
        @if (status.local) {
          <form class="form-grid" (ngSubmit)="add()">
            <label>
              PIN
              <input
                type="password"
                inputmode="numeric"
                autocomplete="new-password"
                maxlength="10"
                name="pin"
                [(ngModel)]="pin"
              />
            </label>
            <label>
              Ripeti il PIN
              <input
                type="password"
                inputmode="numeric"
                autocomplete="new-password"
                maxlength="10"
                name="repeat"
                [(ngModel)]="repeat"
              />
            </label>
            <div class="form-button">
              <button type="submit" [disabled]="busy()">Proteggi con un PIN</button>
            </div>
          </form>
          <p class="hint">
            Da 6 a 10 cifre. Da quel momento lo chiederemo su ogni dispositivo, anche sul server.
          </p>
        }
      }
      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
    }
  `,
})
export class AccessSection {
  protected readonly auth = inject(AuthStore);

  protected pin = '';
  protected repeat = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly newCode = signal<string | null>(null);

  protected add(): void {
    if (!VALID_PIN.test(this.pin)) {
      this.error.set('Il PIN è fatto di cifre: da 6 a 10.');
      return;
    }
    if (this.pin !== this.repeat) {
      this.error.set('I due PIN non sono uguali: riscrivili.');
      return;
    }
    this.run(() =>
      this.auth.create(this.pin).subscribe({
        next: (code) => this.finish(() => this.newCode.set(code)),
        error: (error: unknown) => this.fail(error),
      }),
    );
  }

  protected remove(): void {
    if (!VALID_PIN.test(this.pin)) {
      this.error.set('Il PIN è fatto di cifre: da 6 a 10.');
      return;
    }
    this.run(() =>
      this.auth.removePin(this.pin).subscribe({
        next: () => this.finish(() => undefined),
        error: (error: unknown) => this.fail(error),
      }),
    );
  }

  private run(start: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    start();
  }

  private finish(then: () => void): void {
    this.busy.set(false);
    this.pin = '';
    this.repeat = '';
    then();
  }

  private fail(error: unknown): void {
    this.busy.set(false);
    this.pin = '';
    this.repeat = '';
    this.error.set(reason(error, 'Il server non risponde: riprova.'));
  }
}
