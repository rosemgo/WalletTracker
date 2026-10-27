import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AuthStore, reason } from './auth-store';

/** Da 6 a 10 cifre: la stessa regola del backend (PinService), controllata qui per dirlo subito. */
const VALID_PIN = /^\d{6,10}$/;

/**
 * La pagina del PIN (docs/46, parte E): al posto dell'app finché non si entra. Un solo componente per tutti i casi
 * dell'{@link AuthStore}: crea il PIN (la prima volta, dal PC), inseriscilo, o spiega perché non si può entrare.
 *
 * I campi sono `type="password"` (le cifre non si vedono, e il browser non le ricorda nei suggerimenti) con
 * `inputmode="numeric"`: sul telefono si apre la tastierina dei numeri.
 */
@Component({
  selector: 'app-pin-page',
  imports: [FormsModule],
  template: `
    <main class="pin-page">
      <span class="pin-brand"
        ><span class="brand-mark" aria-hidden="true">W</span>WalletTracker</span
      >

      @switch (auth.state()) {
        @case ('create') {
          <h1>Crea il tuo PIN</h1>
          <p class="hint">
            Lo chiederemo per aprire l'app, su ogni dispositivo. Da 6 a 10 cifre. Non usare il PIN
            del bancomat né una data di nascita.
          </p>
          <form class="card" (ngSubmit)="create()">
            <label>
              PIN
              <input
                type="password"
                inputmode="numeric"
                autocomplete="new-password"
                maxlength="10"
                name="pin"
                [(ngModel)]="pin"
                required
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
                required
              />
            </label>
            @if (error(); as message) {
              <p class="error" role="alert">{{ message }}</p>
            }
            <button type="submit" [disabled]="busy()">Crea il PIN ed entra</button>
          </form>
          <p class="hint">
            Se un giorno lo dimentichi, si azzera solo da questo PC, con lo script di installazione
            (la guida dice come).
          </p>
        }
        @case ('login') {
          <h1>Inserisci il PIN</h1>
          <form class="card" (ngSubmit)="login()">
            <label>
              PIN
              <input
                type="password"
                inputmode="numeric"
                autocomplete="current-password"
                maxlength="10"
                name="pin"
                [(ngModel)]="pin"
                required
              />
            </label>
            @if (error(); as message) {
              <p class="error" role="alert">{{ message }}</p>
            }
            <button type="submit" [disabled]="busy() || waiting()">
              {{ waiting() ? 'Aspetta ' + waitLabel() : 'Entra' }}
            </button>
          </form>
        }
        @case ('create-elsewhere') {
          <h1>Crea il PIN dal PC</h1>
          <p>
            WalletTracker non ha ancora un PIN. Per sicurezza si crea solo dal PC su cui è
            installata l'app: aprila lì, su <strong>http://localhost:8000</strong>.
          </p>
        }
        @case ('unreachable') {
          <h1>Il PC non risponde</h1>
          <p>
            Per entrare serve il collegamento con il PC su cui gira WalletTracker. Controlla che sia
            acceso e che tu sia in rete, poi riprova.
          </p>
          <button type="button" (click)="auth.refresh()">Riprova</button>
        }
        @default {
          <p class="hint" role="status">Un attimo…</p>
        }
      }
    </main>
  `,
})
export class PinPage implements OnDestroy {
  protected readonly auth = inject(AuthStore);

  protected pin = '';
  protected repeat = '';
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** Secondi di attesa dopo troppi PIN sbagliati (dal backend: retryAfterSeconds). */
  protected readonly secondsLeft = signal(0);
  protected readonly waiting = computed(() => this.secondsLeft() > 0);
  protected readonly waitLabel = computed(() => {
    const seconds = this.secondsLeft();
    return seconds >= 60 ? `${Math.ceil(seconds / 60)} min` : `${seconds} s`;
  });

  private timer: ReturnType<typeof setInterval> | undefined;

  protected create(): void {
    if (!VALID_PIN.test(this.pin)) {
      this.error.set('Il PIN è fatto di cifre: da 6 a 10.');
      return;
    }
    if (this.pin !== this.repeat) {
      this.error.set('I due PIN non sono uguali: riscrivili.');
      return;
    }
    this.send(this.auth.create(this.pin));
  }

  protected login(): void {
    if (!VALID_PIN.test(this.pin)) {
      this.error.set('Il PIN è fatto di cifre: da 6 a 10.');
      return;
    }
    this.send(this.auth.login(this.pin));
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private send(request: ReturnType<AuthStore['login']>): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: () => this.busy.set(false), // l'AuthStore mostra l'app
      error: (error: unknown) => {
        this.busy.set(false);
        // Un PIN sbagliato non deve restare scritto: si riparte da zero.
        this.pin = '';
        this.repeat = '';
        this.error.set(reason(error, 'Il PC non risponde: controlla che sia acceso e riprova.'));
        if (error instanceof HttpErrorResponse && error.status === 429) {
          this.countDown(Number(error.error?.retryAfterSeconds) || 60);
        }
      },
    });
  }

  /** Il pulsante resta spento fino alla fine della pausa, e dice quanto manca. */
  private countDown(seconds: number): void {
    clearInterval(this.timer);
    this.secondsLeft.set(seconds);
    this.timer = setInterval(() => {
      const left = this.secondsLeft() - 1;
      this.secondsLeft.set(Math.max(0, left));
      if (left <= 0) {
        clearInterval(this.timer);
        this.error.set(null);
      }
    }, 1000);
  }
}
