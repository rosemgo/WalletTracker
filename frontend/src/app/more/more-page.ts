import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { MORE_PAGES } from './more-pages';

/** "Altro" sul telefono: l'elenco delle pagine di servizio, ognuna con la riga che dice a che cosa serve. */
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
  `,
})
export class MorePage {
  protected readonly pages = MORE_PAGES;
}
