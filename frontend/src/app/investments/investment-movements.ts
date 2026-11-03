import { DatePipe } from '@angular/common';
import { Component, computed, input, linkedSignal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Transaction } from '../api/models';
import { MoneyPipe } from '../privacy/money.pipe';
import { nameOf } from '../transactions/day-groups';
import { Avatar } from '../transactions/avatar';

/** Quanti movimenti si vedono prima di "Mostra tutti". */
export const FIRST_MOVEMENTS = 8;

/**
 * Un elenco di movimenti sotto i grafici della pagina Investimenti (docs/investimenti.md, parte I): gli acquisti e le
 * vendite, oppure i bonifici verso i conti di investimento. Sono **tutti** i movimenti del periodo, quelli che fanno
 * il grafico: si vedono i primi {@link FIRST_MOVEMENTS}, gli altri con "Mostra tutti". Ogni riga porta al dettaglio.
 */
@Component({
  selector: 'app-investment-movements',
  imports: [Avatar, DatePipe, MoneyPipe, RouterLink],
  template: `
    @if (movements().length > 0) {
      <h2 class="days-title">{{ title() }}</h2>
      <section class="day">
        <ul>
          @for (t of shown(); track t.id) {
            <li>
              <a class="movement" [routerLink]="['/movimenti', t.id]">
                <span class="avatar" [appAvatar]="t"></span>
                <span class="movement-text">
                  <span class="name">{{ nameOf(t) }}</span>
                  <span class="secondary">{{ t.bookingDate | date: 'd MMMM y' }}</span>
                </span>
                <span class="amount" [class.positive]="t.amount > 0">{{
                  t.amount | money: t.currency
                }}</span>
              </a>
            </li>
          }
        </ul>
        @if (hidden() > 0) {
          <button type="button" class="link show-all" (click)="all.set(true)">
            Mostra tutti ({{ movements().length }})
          </button>
        }
      </section>
    }
  `,
})
export class InvestmentMovements {
  readonly title = input.required<string>();
  readonly movements = input.required<Transaction[]>();

  protected readonly nameOf = nameOf;

  /** true dopo "Mostra tutti"; torna false quando arrivano movimenti nuovi (un altro periodo). */
  protected readonly all = linkedSignal({ source: this.movements, computation: () => false });

  protected readonly shown = computed(() =>
    this.all() ? this.movements() : this.movements().slice(0, FIRST_MOVEMENTS),
  );

  /** Quanti movimenti restano nascosti. */
  protected readonly hidden = computed(() => this.movements().length - this.shown().length);
}
