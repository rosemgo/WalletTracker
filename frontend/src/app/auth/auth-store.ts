import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { AuthStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * Dove il browser ricorda l'ultima volta che il backend ha confermato "sei dentro" (un numero: i millisecondi dal
 * 1970). Serve solo senza rete: vedi {@link AuthStore}.
 */
export const SESSION_SEEN_KEY = 'wallettracker.session-seen';

/** Quanto vale quella conferma senza rete: come la sessione del backend, 12 ore (application.yml). */
export const SESSION_SEEN_FOR_MS = 12 * 60 * 60 * 1000;

/**
 * Che cosa mostrare:
 * - `checking`: si sta chiedendo al backend (all'apertura);
 * - `create`: il PIN non c'è ancora, e l'app è aperta dal PC: si crea;
 * - `create-elsewhere`: il PIN non c'è ancora, ma l'app è aperta da un altro dispositivo: si crea dal PC;
 * - `login`: il PIN c'è, e questo browser non l'ha ancora inserito (o la sessione è scaduta);
 * - `unreachable`: il backend non risponde, e non c'è una sessione recente;
 * - `inside`: si usa l'app.
 */
export type AuthState =
  'checking' | 'create' | 'create-elsewhere' | 'login' | 'unreachable' | 'inside';

/**
 * Lo stato del PIN, condiviso da tutta l'app (Fase 4, passo 3: docs/46, parte E).
 *
 * **Senza rete** (il PC spento, il telefono fuori copertura) il backend non può dire se la sessione è aperta. Allora
 * decidiamo così: se il backend ha confermato "sei dentro" nelle ultime 12 ore, l'app si apre con la copia dei dati
 * che il service worker tiene sul dispositivo (docs/42). Altrimenti chiede di collegarsi.
 *
 * **La copia dei dati** sul dispositivo si cancella quando si esce, quando la sessione scade e quando il backend
 * risponde "serve il PIN": così senza PIN non resta niente da vedere.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly api = inject(WalletApi);

  readonly state = signal<AuthState>('checking');

  /** Chiede lo stato al backend e decide che cosa mostrare. */
  refresh(): void {
    this.api.authStatus().subscribe({
      next: (status) => this.apply(status),
      error: () => this.state.set(recentlySeen() ? 'inside' : 'unreachable'),
    });
  }

  /** Crea il PIN ed entra. Gli errori (400, 403, 409) li mostra la pagina del PIN. */
  create(pin: string): Observable<void> {
    return this.api.createPin(pin).pipe(tap(() => this.enter()));
  }

  /** Inserisce il PIN ed entra. Gli errori (401 sbagliato, 429 bloccato) li mostra la pagina del PIN. */
  login(pin: string): Observable<void> {
    return this.api.login(pin).pipe(tap(() => this.enter()));
  }

  /** Esce: chiude la sessione nel backend e cancella dal dispositivo la copia dei dati. */
  logout(): void {
    const done = () => {
      void forget();
      this.state.set('login');
    };
    this.api.logout().subscribe({ next: done, error: done });
  }

  /** Il backend ha risposto 401 a una richiesta qualsiasi: la sessione è scaduta (o il backend è ripartito). */
  sessionLost(): void {
    if (this.state() === 'inside') {
      void forget();
      this.state.set('login');
    }
  }

  private apply(status: AuthStatus): void {
    if (status.authenticated) {
      this.enter();
    } else {
      void forget();
      this.state.set(status.pinSet ? 'login' : status.canCreatePin ? 'create' : 'create-elsewhere');
    }
  }

  private enter(): void {
    try {
      localStorage.setItem(SESSION_SEEN_KEY, String(Date.now()));
    } catch {
      // Non ricordata: senza rete l'app chiederà di collegarsi.
    }
    this.state.set('inside');
  }
}

/** Il backend ha confermato "sei dentro" nelle ultime 12 ore? */
function recentlySeen(): boolean {
  try {
    const seen = Number(localStorage.getItem(SESSION_SEEN_KEY));
    return seen > 0 && Date.now() - seen < SESSION_SEEN_FOR_MS;
  } catch {
    return false;
  }
}

/**
 * Dimentica la sessione sul dispositivo: la conferma ricordata e la copia dei dati del service worker.
 *
 * Le copie stanno nella Cache Storage del browser, in "cassetti" con un nome: le risposte del gruppo "dati" di
 * ngsw-config.json stanno in "ngsw:/:1:data:dati:cache" (il numero può cambiare). Il cassetto si **svuota**, non si
 * butta: il service worker acceso lo tiene aperto, e se lo buttassimo continuerebbe a usarlo, invisibile, finché non
 * si spegne (l'abbiamo visto provando, docs/46 parte L). Le risposte cancellate invece spariscono anche per lui. Gli
 * altri cassetti (i file dell'app) restano: servono per aprirla.
 */
export async function forget(): Promise<void> {
  try {
    localStorage.removeItem(SESSION_SEEN_KEY);
  } catch {
    // niente da dimenticare
  }
  if (typeof caches === 'undefined') {
    return; // senza service worker (ng serve, i test) non c'è nessuna copia
  }
  const names = (await caches.keys()).filter(
    (name) => name.includes(':data:dati:') && name.endsWith(':cache'),
  );
  for (const name of names) {
    const drawer = await caches.open(name);
    const saved = await drawer.keys();
    await Promise.all(saved.map((request) => drawer.delete(request)));
  }
}

/** Il motivo scritto dal backend in un errore (il campo "detail"), o una frase di riserva. */
export function reason(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse && typeof error.error?.detail === 'string') {
    return error.error.detail;
  }
  return fallback;
}
