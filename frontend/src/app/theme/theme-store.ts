import { DOCUMENT, Injectable, inject, signal } from '@angular/core';

/** Il tema scelto: come il telefono (o il computer), sempre chiaro, sempre scuro. */
export type ThemeChoice = 'auto' | 'giorno' | 'notte';

export const THEME_CHOICES: readonly ThemeChoice[] = ['auto', 'giorno', 'notte'];

/** Dove il browser ricorda la scelta (vale solo su questo dispositivo). */
export const THEME_KEY = 'wallettracker.theme';

/**
 * Il tema dell'app (docs/35, parte C). I colori sono variabili CSS in styles.css: questa classe sceglie solo
 * QUALI valori valgono, con l'attributo {@code data-theme} sull'elemento {@code <html>}:
 *
 * <ul>
 *   <li>nessun attributo ("auto"): decide il sistema, con la media query {@code prefers-color-scheme};</li>
 *   <li>{@code data-theme="giorno"} o {@code "notte"}: vince sulla scelta del sistema.</li>
 * </ul>
 *
 * La scelta si ricorda nel {@code localStorage} del browser. Se non si può leggere o scrivere (navigazione
 * privata, archiviazione bloccata) l'app funziona lo stesso, in automatico.
 */
@Injectable({ providedIn: 'root' })
export class ThemeStore {
  private readonly document = inject(DOCUMENT);

  /** La scelta attuale; si cambia con {@link choose}. */
  readonly choice = signal<ThemeChoice>(this.saved());

  constructor() {
    this.apply(this.choice());
  }

  choose(choice: ThemeChoice): void {
    this.choice.set(choice);
    this.apply(choice);
    try {
      if (choice === 'auto') {
        localStorage.removeItem(THEME_KEY);
      } else {
        localStorage.setItem(THEME_KEY, choice);
      }
    } catch {
      // Non ricordata: vale fino alla chiusura della pagina.
    }
  }

  private apply(choice: ThemeChoice): void {
    const root = this.document.documentElement;
    if (choice === 'auto') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', choice);
    }
  }

  /** La scelta ricordata; un valore sconosciuto (scritto a mano, o da una versione vecchia) vale "auto". */
  private saved(): ThemeChoice {
    try {
      const value = localStorage.getItem(THEME_KEY);
      return value === 'giorno' || value === 'notte' ? value : 'auto';
    } catch {
      return 'auto';
    }
  }
}
