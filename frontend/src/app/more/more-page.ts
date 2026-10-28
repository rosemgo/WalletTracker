import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthStore } from '../auth/auth-store';
import { MORE_PAGES } from './more-pages';

/**
 * "Altro" sul telefono: l'elenco delle pagine di servizio, ognuna con la riga che dice a che cosa serve, e in fondo
 * "Esci".
 */
@Component({
  selector: 'app-more-page',
  imports: [RouterLink],
  template: `
    <h1>Altro</h1>
    <ul class="link-list">
      @for (page of pages; track page.path) {
        <li>
          <a [routerLink]="page.path">
            <span>
              {{ page.label }}
              <span class="secondary">{{ page.hint }}</span>
            </span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
          </a>
        </li>
      }
    </ul>
    <!-- Esci: chiude la sessione, e l'app chiederà di nuovo il PIN (docs/46). Sul PC è in fondo alla colonna. -->
    <button type="button" class="logout" (click)="auth.logout()">Esci</button>
  `,
})
export class MorePage {
  protected readonly pages = MORE_PAGES;
  protected readonly auth = inject(AuthStore);
}
