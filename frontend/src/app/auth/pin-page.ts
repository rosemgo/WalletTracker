import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';

import { AuthStore, reason } from './auth-store';
import { LanguageSection } from '../language/language-section';
import { RecoveryCodeView } from './recovery-code';

/** Da 6 a 10 cifre: la stessa regola del backend (PinService), controllata qui per dirlo subito. */
export const VALID_PIN = /^\d{6,10}$/;

/**
 * La pagina per entrare (docs/46, parte F): al posto dell'app finché non si entra. Un solo componente per tutti i casi
 * dell'{@link AuthStore}:
 * - la prima apertura dal server (il computer su cui gira l'app): con un PIN, o senza;
 * - inserisci il PIN, o "Ho dimenticato il PIN" con il codice di recupero;
 * - il codice di recupero da salvare, dopo averlo creato o usato;
 * - perché da qui non si entra (un dispositivo remoto, il server che non risponde).
 *
 * I campi del PIN sono `type="password"` (le cifre non si vedono, e il browser non le ricorda nei suggerimenti) con
 * `inputmode="numeric"`: sul telefono si apre la tastierina dei numeri.
 */
@Component({
  selector: 'app-pin-page',
  imports: [FormsModule, LanguageSection, RecoveryCodeView],
  template: `
    <main class="pin-page">
      <span class="pin-brand"
        ><img class="brand-mark" src="icons/icon.svg" alt="" /><ng-container i18n
          >WalletTracker</ng-container
        ></span
      >

      @if (newCode(); as code) {
        <h1 i18n>Salva il codice di recupero</h1>
        <app-recovery-code [code]="code" (saved)="auth.enter()" />
      } @else {
        @switch (auth.state()) {
          @case ('choose') {
            <!-- La prima schermata di un'installazione nuova: qui si sceglie anche la lingua. -->
            <div class="card">
              <app-language-section [firstScreen]="true" />
            </div>
            <h1 i18n>Come vuoi proteggere WalletTracker?</h1>
            <form class="card" (ngSubmit)="create()">
              <h2 i18n>Con un PIN</h2>
              <p i18n class="hint">
                Lo chiederemo per aprire l'app, su ogni dispositivo. Serve se userai l'app da un
                dispositivo remoto (il telefono, un portatile, un altro computer). Da 6 a 10 cifre:
                non usare il PIN del bancomat né una data di nascita.
              </p>
              <label
                ><ng-container i18n> PIN </ng-container
                ><input
                  type="password"
                  inputmode="numeric"
                  autocomplete="new-password"
                  maxlength="10"
                  name="pin"
                  [(ngModel)]="pin"
                />
              </label>
              <label
                ><ng-container i18n> Ripeti il PIN </ng-container
                ><input
                  type="password"
                  inputmode="numeric"
                  autocomplete="new-password"
                  maxlength="10"
                  name="repeat"
                  [(ngModel)]="repeat"
                />
              </label>
              @if (error(); as message) {
                <p class="error" role="alert">{{ message }}</p>
              }
              <button i18n type="submit" [disabled]="busy()">Crea il PIN</button>
            </form>
            <div class="card">
              <h2 i18n>Senza PIN, solo dal server</h2>
              <p i18n class="hint">
                Chi usa questo computer, il server su cui è installata l'app, entra subito. Dai
                dispositivi remoti, invece, non si potrà entrare finché non crei un PIN (lo puoi
                fare quando vuoi, da Impostazioni).
              </p>
              <button i18n type="button" [disabled]="busy()" (click)="withoutPin()">
                Continua senza PIN
              </button>
            </div>
          }
          @case ('login') {
            @if (recovering()) {
              <h1 i18n>Ho dimenticato il PIN</h1>
              <form class="card" (ngSubmit)="recover()">
                <p i18n class="hint">
                  Scrivi il codice di recupero che hai salvato quando hai creato il PIN, e scegli un
                  PIN nuovo.
                </p>
                <label
                  ><ng-container i18n> Codice di recupero </ng-container
                  ><input
                    autocomplete="off"
                    autocapitalize="characters"
                    spellcheck="false"
                    placeholder="XXXX-XXXX-XXXX-XXXX"
                    i18n-placeholder
                    name="code"
                    [(ngModel)]="code"
                  />
                </label>
                <label
                  ><ng-container i18n> PIN nuovo </ng-container
                  ><input
                    type="password"
                    inputmode="numeric"
                    autocomplete="new-password"
                    maxlength="10"
                    name="pin"
                    [(ngModel)]="pin"
                  />
                </label>
                <label
                  ><ng-container i18n> Ripeti il PIN nuovo </ng-container
                  ><input
                    type="password"
                    inputmode="numeric"
                    autocomplete="new-password"
                    maxlength="10"
                    name="repeat"
                    [(ngModel)]="repeat"
                  />
                </label>
                @if (error(); as message) {
                  <p class="error" role="alert">{{ message }}</p>
                }
                <button type="submit" [disabled]="busy() || waiting()">
                  <ng-container i18n>{!!waiting(), select,
                    true {Aspetta {{ waitLabel() }}}
                    other {Scegli il PIN nuovo}
                  }</ng-container>
                </button>
              </form>
              <button i18n type="button" class="link" (click)="showRecovery(false)">
                Torna al PIN
              </button>
              <p i18n class="hint">
                Hai perso anche il codice? Dal server, il computer su cui è installata l'app, lo
                script di installazione azzera il PIN (la guida dice come).
              </p>
            } @else {
              <h1 i18n>Inserisci il PIN</h1>
              <form class="card" (ngSubmit)="login()">
                <label
                  ><ng-container i18n> PIN </ng-container
                  ><input
                    type="password"
                    inputmode="numeric"
                    autocomplete="current-password"
                    maxlength="10"
                    name="pin"
                    [(ngModel)]="pin"
                  />
                </label>
                @if (error(); as message) {
                  <p class="error" role="alert">{{ message }}</p>
                }
                <button type="submit" [disabled]="busy() || waiting()">
                  <ng-container i18n>{!!waiting(), select,
                    true {Aspetta {{ waitLabel() }}}
                    other {Entra}
                  }</ng-container>
                </button>
              </form>
              <button i18n type="button" class="link" (click)="showRecovery(true)">
                Ho dimenticato il PIN
              </button>
            }
          }
          @case ('elsewhere') {
            @if (auth.status()?.withoutPin) {
              <h1 i18n>Da qui serve un PIN</h1>
              <p i18n>
                WalletTracker è aperto senza PIN, ma solo dal server, il computer su cui è
                installato. Per usarlo da questo dispositivo, crea un PIN dal server:
                <strong>Impostazioni → Accesso</strong>.
              </p>
            } @else {
              <h1 i18n>Crea il PIN dal server</h1>
              <p i18n>
                WalletTracker non è ancora protetto. Per sicurezza la scelta si fa solo dal server,
                il computer su cui è installata l'app: aprila lì, su
                <strong>http://localhost:8000</strong>.
              </p>
            }
          }
          @case ('unreachable') {
            <h1 i18n>Il server non risponde</h1>
            <p i18n>
              Per entrare serve il collegamento con il server, il computer su cui gira
              WalletTracker. Controlla che sia acceso e che tu sia in rete (da un dispositivo
              remoto, con Tailscale acceso), poi riprova.
            </p>
            <button i18n type="button" (click)="auth.refresh()">Riprova</button>
          }
          @default {
            <p i18n class="hint" role="status">Un attimo…</p>
          }
        }
      }
    </main>
  `,
})
export class PinPage implements OnDestroy {
  protected readonly auth = inject(AuthStore);

  protected pin = '';
  protected repeat = '';
  protected code = '';
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** "Ho dimenticato il PIN": il modulo con il codice di recupero. */
  protected readonly recovering = signal(false);
  /** Il codice di recupero appena ricevuto, da mostrare una volta (poi si entra). */
  protected readonly newCode = signal<string | null>(null);
  /** Secondi di attesa dopo troppi tentativi sbagliati (dal backend: retryAfterSeconds). */
  protected readonly secondsLeft = signal(0);
  protected readonly waiting = computed(() => this.secondsLeft() > 0);
  protected readonly waitLabel = computed(() => {
    const seconds = this.secondsLeft();
    return seconds >= 60 ? `${Math.ceil(seconds / 60)} min` : `${seconds} s`;
  });

  private timer: ReturnType<typeof setInterval> | undefined;

  protected create(): void {
    if (this.checkNewPin()) {
      this.send(this.auth.create(this.pin), (code) => this.newCode.set(code));
    }
  }

  protected withoutPin(): void {
    this.send(this.auth.chooseWithoutPin(), () => undefined);
  }

  protected login(): void {
    if (!VALID_PIN.test(this.pin)) {
      this.error.set($localize`Il PIN è fatto di cifre: da 6 a 10.`);
      return;
    }
    this.send(this.auth.login(this.pin), () => undefined);
  }

  protected recover(): void {
    if (this.checkNewPin()) {
      this.send(this.auth.recover(this.code, this.pin), (code) => this.newCode.set(code));
    }
  }

  protected showRecovery(show: boolean): void {
    this.recovering.set(show);
    this.error.set(null);
    this.pin = '';
    this.repeat = '';
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  /** Un PIN nuovo va scritto due volte, uguale, di 6-10 cifre: lo diciamo prima di mandarlo. */
  private checkNewPin(): boolean {
    if (!VALID_PIN.test(this.pin)) {
      this.error.set($localize`Il PIN è fatto di cifre: da 6 a 10.`);
      return false;
    }
    if (this.pin !== this.repeat) {
      this.error.set($localize`I due PIN non sono uguali: riscrivili.`);
      return false;
    }
    return true;
  }

  private send<T>(request: Observable<T>, done: (value: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: (value) => {
        this.busy.set(false);
        done(value);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        // Un PIN sbagliato non deve restare scritto: si riparte da zero.
        this.pin = '';
        this.repeat = '';
        this.error.set(
          reason(error, $localize`Il server non risponde: controlla che sia acceso e riprova.`),
        );
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
