import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Alert } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CloseButton } from '../ui/close-button';
import { DismissedNotices } from '../ui/dismissed-notices';

/**
 * La chiave di un avviso, per ricordarlo chiuso (DismissedNotices): descrive la situazione, così un avviso chiuso
 * ricompare quando la situazione cambia.
 * - consenso in scadenza: la banca, la scadenza e la "fase" (gli ultimi 3 giorni sono una fase nuova: un promemoria
 *   chiuso due settimane prima ricompare a ridosso della scadenza);
 * - consenso scaduto: la banca e la scadenza (ricollegata, la scadenza cambia);
 * - aggiornamento non riuscito: il conto e il motivo;
 * - budget superati: il mese e le categorie (una categoria in più, o il mese dopo, è un avviso nuovo).
 */
export function alertKey(alert: Alert, today: Date = new Date()): string {
  switch (alert.kind) {
    case 'CONSENT_EXPIRING': {
      const phase = alert.daysLeft !== null && alert.daysLeft <= 3 ? 'last-days' : 'soon';
      return `alert:CONSENT_EXPIRING:${alert.bank}:${alert.date}:${phase}`;
    }
    case 'CONSENT_EXPIRED':
      return `alert:CONSENT_EXPIRED:${alert.bank}:${alert.date}`;
    case 'SYNC_FAILED':
      return `alert:SYNC_FAILED:${alert.account}:${alert.message}`;
    case 'BUDGET_OVER': {
      const month = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
      return `alert:BUDGET_OVER:${month}:${[...alert.items].sort().join(',')}`;
    }
    default:
      return `alert:${JSON.stringify(alert)}`;
  }
}

/**
 * Il pannello degli avvisi, in cima al Riepilogo. Se non c'è niente da segnalare, non mostra niente.
 *
 * Ogni avviso si può chiudere con la crocetta: resta chiuso finché la situazione non cambia (alertKey).
 *
 * Ogni avviso ha un'azione: un link alla pagina dove si risolve il problema. Per un consenso scaduto o in
 * scadenza, il link apre "Collega una banca" con la banca già scelta (/collega?bank=ING&country=IT).
 */
@Component({
  selector: 'app-alerts-panel',
  imports: [CloseButton, DatePipe, RouterLink],
  templateUrl: './alerts-panel.html',
})
export class AlertsPanel implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly dismissed = inject(DismissedNotices);

  private readonly all = signal<Alert[]>([]);
  /** Gli avvisi da mostrare: tutti tranne quelli chiusi con la crocetta. */
  protected readonly alerts = computed(() =>
    this.all().filter((alert) => !this.dismissed.isDismissed(alertKey(alert))),
  );

  ngOnInit(): void {
    // Se gli avvisi non arrivano non mostriamo un errore: il resto della pagina segnala già se il backend
    // non risponde.
    this.api.alerts().subscribe({ next: (alerts) => this.all.set(alerts) });
  }

  protected close(alert: Alert): void {
    this.dismissed.dismiss(alertKey(alert));
  }

  /** "tra 5 giorni", "domani", "oggi". */
  protected when(daysLeft: number | null): string {
    if (daysLeft === null || daysLeft <= 0) {
      return 'oggi';
    }
    return daysLeft === 1 ? 'domani' : `tra ${daysLeft} giorni`;
  }
}
