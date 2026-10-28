import { NgTemplateOutlet } from '@angular/common';
import { Component, OnInit, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { SetupStore } from './api/setup-store';
import { AuthStore } from './auth/auth-store';
import { PinPage } from './auth/pin-page';
import { MORE_PAGES, NavItem } from './more/more-pages';
import { PrivacyStore } from './privacy/privacy-store';
import { AppStatus } from './pwa/app-status';
import { ThemeStore } from './theme/theme-store';

/**
 * Le sezioni principali, nella barra in basso del telefono e in cima alla colonna del PC (docs/35, parte D).
 * `icon` è il disegno dell'icona: il tracciato di un SVG 24×24, disegnato con una linea (niente riempimenti).
 */
export const MAIN_SECTIONS: NavItem[] = [
  {
    path: '/riepilogo',
    label: 'Riepilogo',
    icon: 'M4 11.5 12 5l8 6.5V19a1 1 0 0 1-1 1h-4v-5H9v5H5a1 1 0 0 1-1-1z',
  },
  { path: '/movimenti', label: 'Movimenti', icon: 'M4 7h16M4 12h16M4 17h10' },
  { path: '/analisi', label: 'Analisi', icon: 'M5 20V11M12 20V5M19 20v-6' },
  {
    path: '/budget',
    label: 'Budget',
    icon: 'M6 5h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM4 10h16',
  },
];

/** "Altro": sul telefono porta all'elenco delle pagine di servizio; sul PC quelle pagine sono già nella colonna. */
export const MORE_SECTION: NavItem = {
  path: '/altro',
  label: 'Altro',
  icon: 'M5.5 12h.01M12 12h.01M18.5 12h.01',
};

/**
 * Il componente principale: la navigazione e, accanto o sopra, la pagina corrente. `<router-outlet>` è il
 * "segnaposto" in cui il router mostra il componente della pagina scelta.
 *
 * La navigazione è una sola, e il CSS la dispone in due modi (docs/35, parte D): sul telefono una barra in basso
 * con le sezioni principali e "Altro"; sul PC una colonna a sinistra con tutte le pagine.
 *
 * All'avvio chiede al backend lo stato del PIN (docs/46): finché non si entra, mostra la pagina del PIN. Una volta
 * dentro, chiede se Enable Banking è configurato: se no, mostra un avviso con il link alla pagina Impostazioni.
 */
@Component({
  selector: 'app-root',
  imports: [NgTemplateOutlet, RouterOutlet, RouterLink, RouterLinkActive, PinPage],
  templateUrl: './app.html',
})
export class App implements OnInit {
  /** Il PIN: si è dentro? (docs/46) */
  protected readonly auth = inject(AuthStore);
  protected readonly setup = inject(SetupStore);
  /** La modalità discreta: l'occhio in alto (docs/42, parte B). */
  protected readonly privacy = inject(PrivacyStore);
  /** La rete e le versioni nuove dell'app installabile (docs/42, parte E). */
  protected readonly status = inject(AppStatus);
  protected readonly sections = MAIN_SECTIONS;
  protected readonly more = MORE_SECTION;
  protected readonly morePages = MORE_PAGES;

  private readonly router = inject(Router);

  /** L'indirizzo della pagina corrente, aggiornato a ogni cambio di pagina. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /** Sul telefono "Altro" è acceso anche quando si è in una delle sue pagine (Regole, Conti...). */
  protected readonly inMore = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    return [MORE_SECTION, ...MORE_PAGES].some(
      (page) => path === page.path || path.startsWith(page.path + '/'),
    );
  });

  /** La rete c'era all'ultimo controllo? Per accorgersi di quando torna. */
  private wasOnline = this.status.online();

  constructor() {
    // Applica subito il tema ricordato (la classe lo fa quando viene creata).
    inject(ThemeStore);

    // Appena si entra (PIN giusto, o sessione ancora aperta) si chiede lo stato di Enable Banking: prima il backend
    // risponderebbe 401.
    effect(() => {
      if (this.auth.state() === 'inside') {
        untracked(() => this.setup.refresh());
      }
    });
    // Quando la rete torna, si richiede lo stato del PIN: senza rete l'app poteva essere aperta sulla copia dei dati,
    // o ferma su "Il PC non risponde".
    effect(() => {
      const online = this.status.online();
      if (online && !this.wasOnline) {
        untracked(() => this.auth.refresh());
      }
      this.wasOnline = online;
    });
  }

  ngOnInit(): void {
    this.auth.refresh();
  }
}
