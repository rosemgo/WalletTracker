import { Component } from '@angular/core';

/** Il pannello di Enable Banking, dove si trova l'applicazione e il pulsante "Link accounts". */
export const ENABLE_BANKING_PANEL = 'https://enablebanking.com/cp/applications';

/**
 * Come aggiungere un conto alla lista dell'applicazione Enable Banking (docs/modalita-ristretta.md). L'applicazione creata
 * con la guida è in "modalità ristretta": gratuita, per uso personale, ma legge solo i conti della sua lista. Un conto
 * nuovo va aggiunto lì UNA volta, poi si collega dall'app come sempre. Lo usano "Collega una banca" e la pagina di
 * ritorno quando Enable Banking non passa nessun conto.
 */
@Component({
  selector: 'app-linked-accounts-steps',
  template: `
    <ol class="linked-steps">
      <li>
        Apri il <a [href]="panel" target="_blank" rel="noopener">pannello di Enable Banking</a> e
        accedi.
      </li>
      <li>Nella tua applicazione premi <strong>Link accounts</strong>.</li>
      <li>
        Scegli il paese e la banca, accedi alla banca e seleziona i conti da aggiungere (anche
        quelli che c'erano già, se la banca li propone insieme).
      </li>
      <li>
        Torna qui, premi <strong>Collega</strong> sulla banca e seleziona
        <strong>gli stessi conti</strong>.
      </li>
    </ol>
    <p class="secondary">
      Serve una volta sola per ogni conto. Il motivo: la tua applicazione Enable Banking è in
      <strong>modalità ristretta</strong> (gratuita, per uso personale) e passa all'app solo i conti
      della sua lista; gli altri li toglie, anche se la banca li ha autorizzati.
    </p>
  `,
})
export class LinkedAccountsSteps {
  protected readonly panel = ENABLE_BANKING_PANEL;
}
