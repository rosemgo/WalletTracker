import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Investments } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { smoothPath } from '../overview/month-chart';
import { MoneyPipe } from '../privacy/money.pipe';

/** La misura del piccolo grafico (viewBox): largo come la scheda, alto 48 pixel. */
const SPARK_WIDTH = 300;
const SPARK_HEIGHT = 48;

/**
 * La scheda "Investito" del Riepilogo (docs/investimenti.md, parte C): quanto hai versato quest'anno e quanto ti
 * hanno reso gli investimenti, con un piccolo grafico del versato degli ultimi 12 mesi. Tutta la scheda è un link
 * alla pagina Investimenti.
 *
 * Si carica da sola (non dipende dal mese guardato nel Riepilogo: parla dell'anno) e non compare se non c'è niente
 * da mostrare: chi non investe non deve vedere una scheda vuota.
 */
@Component({
  selector: 'app-investments-card',
  imports: [MoneyPipe, RouterLink],
  template: `
    @if (shown(); as d) {
      <a class="card investments-card" routerLink="/investimenti">
        <div class="card-head">
          <h2>Investito</h2>
          <span aria-hidden="true">Vedi tutto ›</span>
        </div>
        <div class="figures">
          <div>
            <span class="secondary">Versato quest'anno</span>
            <strong>{{ d.contributedThisYear | money: d.currency : 'symbol' : '1.0-0' }}</strong>
          </div>
          <div>
            <span class="secondary">Rendite quest'anno</span>
            <strong>{{ d.incomeThisYear | money: d.currency : 'symbol' : '1.0-0' }}</strong>
          </div>
        </div>
        <svg
          class="sparkline"
          [attr.viewBox]="'0 0 ' + width + ' ' + height"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <path class="spark-area" [attr.d]="spark().area" />
          <path class="spark-line" [attr.d]="spark().line" />
        </svg>
      </a>
    }
  `,
})
export class InvestmentsCard implements OnInit {
  private readonly api = inject(WalletApi);

  protected readonly width = SPARK_WIDTH;
  protected readonly height = SPARK_HEIGHT;

  private readonly data = signal<Investments | null>(null);

  /** I dati, se c'è qualcosa da mostrare in tutta la storia; altrimenti null (niente scheda). */
  protected readonly shown = computed(() => {
    const d = this.data();
    const anything =
      d !== null &&
      (d.contributedTotal !== 0 || d.months.some((m) => m.income !== 0 || m.contributed !== 0));
    return anything ? d : null;
  });

  /** La linea del versato (cumulato) dei 12 mesi, e l'area sotto. */
  protected readonly spark = computed(() => {
    const values = this.data()?.months.map((m) => m.cumulative) ?? [];
    const max = Math.max(1, ...values);
    const min = Math.min(0, ...values);
    const points = values.map((v, i) => ({
      x: (i / Math.max(1, values.length - 1)) * SPARK_WIDTH,
      y: SPARK_HEIGHT - 2 - ((v - min) / (max - min)) * (SPARK_HEIGHT - 6),
    }));
    const line = smoothPath(points);
    return {
      line,
      area: line === '' ? '' : `${line}L${SPARK_WIDTH} ${SPARK_HEIGHT}L0 ${SPARK_HEIGHT}Z`,
    };
  });

  ngOnInit(): void {
    // Senza la scheda il Riepilogo funziona lo stesso: un errore qui si ignora.
    this.api
      .investments('YEAR')
      .subscribe({ next: (d) => this.data.set(d), error: () => undefined });
  }
}
