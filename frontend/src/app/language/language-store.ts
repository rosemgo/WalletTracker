import { Injectable, InjectionToken, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { WalletApi } from '../api/wallet-api';
import { AuthStore } from '../auth/auth-store';
import { APP_LANGUAGE } from '../text/locale';
import { Language, isLanguage, rememberLanguage } from './language';

/** Ricarica la pagina: è così che si cambia lingua. Un "gettone" a parte, perché i test lo sostituiscono. */
export const RELOAD_PAGE = new InjectionToken<() => void>('RELOAD_PAGE', {
  providedIn: 'root',
  factory: () => () => location.reload(),
});

/** Perché una pagina ricaricata per una lingua non si ricarichi una seconda volta (vedi {@link LanguageStore}). */
const RELOADED_FOR_KEY = 'wallettracker.language-reloaded-for';

/**
 * La lingua dell'app, vista dalla dashboard (docs/lingue-2-inglese.md, parte D).
 *
 * La lingua è dell'installazione e sta nel server; ogni browser ne tiene una copia, per aprirsi subito. Questo
 * servizio le tiene allineate:
 *
 * - se il server ne ha una diversa (l'hai cambiata dal computer, e questo è il telefono), si ricorda quella nuova e
 *   ricarica la pagina: una pagina non può cambiare lingua da aperta, perché le sue frasi sono già scritte;
 * - se il server non ne ha ancora una (un'installazione nuova) e si è dentro, gli consegna quella di questa pagina:
 *   da quel momento è la lingua dell'installazione, e le categorie di partenza prendono i nomi in quella lingua.
 */
@Injectable({ providedIn: 'root' })
export class LanguageStore {
  private readonly api = inject(WalletApi);
  private readonly auth = inject(AuthStore);
  private readonly reload = inject(RELOAD_PAGE);

  /** La lingua in cui questa pagina è aperta. */
  readonly current: Language = APP_LANGUAGE;

  /** La lingua dell'installazione secondo il server, quando lo si è saputo. */
  readonly server = signal<Language | null>(null);

  /** L'ultimo stato dell'accesso per cui ci siamo allineati: una volta per stato, non a ogni ridisegno. */
  private alignedFor: string | null = null;

  constructor() {
    // Appena si sa come si entra (la pagina del PIN, o dentro), e di nuovo quando si entra.
    effect(() => {
      const state = this.auth.state();
      if (state !== 'checking' && state !== 'unreachable' && state !== this.alignedFor) {
        this.alignedFor = state;
        this.align(state === 'inside');
      }
    });
  }

  /** Cambia la lingua dell'installazione, e ricarica la pagina nella lingua nuova. */
  choose(language: Language): Observable<unknown> {
    return this.api.chooseLanguage(language).pipe(tap(() => this.open(language)));
  }

  /**
   * Cambia la lingua solo di questa pagina: per la prima schermata di un'installazione nuova, dove non si è ancora
   * dentro e il server non accetta modifiche. Diventerà la lingua dell'installazione appena si entra.
   */
  chooseHere(language: Language): void {
    this.open(language);
  }

  private align(inside: boolean): void {
    this.api.language().subscribe({
      next: ({ language }) => {
        if (!isLanguage(language)) {
          if (inside) {
            // Nessuno ha ancora scelto: la lingua dell'installazione diventa quella di questa pagina.
            this.api.chooseLanguage(this.current).subscribe({
              next: () => {
                rememberLanguage(this.current);
                this.server.set(this.current);
              },
              error: () => undefined, // ci si riprova alla prossima apertura
            });
          }
          return;
        }
        this.server.set(language);
        rememberLanguage(language);
        if (language !== this.current && !this.alreadyReloadedFor(language)) {
          this.markReloadedFor(language);
          this.reload();
        }
      },
      error: () => undefined, // senza rete la lingua resta quella ricordata
    });
  }

  private open(language: Language): void {
    rememberLanguage(language);
    this.markReloadedFor(language);
    this.reload();
  }

  /**
   * Una difesa contro i giri a vuoto: se dopo la ricarica la pagina è ancora nella lingua vecchia (la traduzione non
   * si è caricata), non si ricarica di nuovo per la stessa lingua. Vale finché la scheda resta aperta.
   */
  private alreadyReloadedFor(language: Language): boolean {
    try {
      return sessionStorage.getItem(RELOADED_FOR_KEY) === language;
    } catch {
      return true; // senza sessionStorage non si può sapere: meglio non ricaricare che ricaricare all'infinito
    }
  }

  private markReloadedFor(language: Language): void {
    try {
      sessionStorage.setItem(RELOADED_FOR_KEY, language);
    } catch {
      // vedi alreadyReloadedFor
    }
  }
}
