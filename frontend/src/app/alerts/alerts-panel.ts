import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Alert } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * Il pannello degli avvisi, in cima al Riepilogo. Se non c'è niente da segnalare, non mostra niente.
 *
 * Ogni avviso ha un'azione: un link alla pagina dove si risolve il problema. Per un consenso scaduto o in
 * scadenza, il link apre "Collega una banca" con la banca già scelta (/collega?bank=ING&country=IT).
 */
@Component({
  selector: 'app-alerts-panel',
  imports: [DatePipe, RouterLink],
  templateUrl: './alerts-panel.html',
})
export class AlertsPanel implements OnInit {
  private readonly api = inject(WalletApi);

  protected readonly alerts = signal<Alert[]>([]);

  ngOnInit(): void {
    // Se gli avvisi non arrivano non mostriamo un errore: il resto della pagina segnala già se il backend
    // non risponde.
    this.api.alerts().subscribe({ next: (alerts) => this.alerts.set(alerts) });
  }

  /** "tra 5 giorni", "domani", "oggi". */
  protected when(daysLeft: number | null): string {
    if (daysLeft === null || daysLeft <= 0) {
      return 'oggi';
    }
    return daysLeft === 1 ? 'domani' : `tra ${daysLeft} giorni`;
  }
}
