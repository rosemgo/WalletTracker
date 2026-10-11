import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

/**
 * Il componente principale: la barra in alto con i link alle pagine, e sotto la pagina corrente.
 * `<router-outlet>` è il "segnaposto" in cui il router mostra il componente della pagina scelta.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
})
export class App {}
