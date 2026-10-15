import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { SetupStore } from './api/setup-store';

/**
 * Il componente principale: la barra in alto con i link alle pagine, e sotto la pagina corrente.
 * `<router-outlet>` è il "segnaposto" in cui il router mostra il componente della pagina scelta.
 *
 * All'avvio chiede al backend se Enable Banking è configurato: se no, mostra un avviso con il link
 * alla pagina Impostazioni.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
})
export class App implements OnInit {
  protected readonly setup = inject(SetupStore);

  ngOnInit(): void {
    this.setup.refresh();
  }
}
