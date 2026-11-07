import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AccountsStore } from '../accounts/accounts-store';
import { Account } from '../api/models';
import { MoneyPipe } from '../privacy/money.pipe';

/** Un conto della scheda, con il suo saldo. */
interface Row {
  account: Account;
  amount: number;
  date: string;
}

/**
 * La scheda "Liquidità" del Riepilogo: quanti soldi ci sono adesso sui conti, in tutto e conto per conto (docs/saldi.md,
 * parte K). Conta i conti di tutti i giorni e anche quelli di investimento (i soldi fermi lì, non investiti in titoli);
 * non conta le carte di credito (il loro è un plafond, non soldi tuoi) né i conti esclusi.
 *
 * I saldi sono quelli di adesso: la scheda non cambia guardando un mese passato. Un conto senza saldo non entra nel
 * totale, e la scheda lo dice. Senza nessun saldo la scheda non compare.
 */
@Component({
  selector: 'app-liquidity-card',
  imports: [DatePipe, MoneyPipe, RouterLink],
  template: `
    @if (rows().length > 0) {
      <a class="card liquidity-card" routerLink="/conti">
        <div class="card-head">
          <h2>Liquidità</h2>
          <span aria-hidden="true">Vedi i conti ›</span>
        </div>
        <strong class="liquidity-total" data-testid="liquidity-total">{{
          total() | money: currency() : 'symbol' : '1.0-0'
        }}</strong>
        <span class="secondary">
          Sui tuoi conti, adesso{{ rows().length > 1 ? ', in ' + rows().length + ' conti' : '' }}
        </span>
        <ul class="rows">
          @for (r of rows(); track r.account.id) {
            <li>
              <span>
                {{ r.account.label }}
                <span class="secondary">al {{ r.date | date: 'd MMM' }}</span>
              </span>
              <strong>{{ r.amount | money: currency() }}</strong>
            </li>
          }
        </ul>
        @if (withoutBalance() > 0) {
          <p class="secondary missing">
            {{
              withoutBalance() === 1
                ? '1 conto senza saldo non è nel totale'
                : withoutBalance() + ' conti senza saldo non sono nel totale'
            }}
          </p>
        }
      </a>
    }
  `,
})
export class LiquidityCard implements OnInit {
  private readonly store = inject(AccountsStore);

  /** La valuta del Riepilogo: si sommano solo i conti in quella valuta. */
  readonly currency = input.required<string>();

  /** I conti che contano: niente carte di credito né conti esclusi, solo nella valuta scelta. */
  private readonly counted = computed(() =>
    this.store
      .accounts()
      .filter(
        (a) => a.role !== 'CREDIT_CARD' && a.role !== 'EXCLUDED' && a.currency === this.currency(),
      ),
  );

  /** I conti con un saldo, dal più ricco. */
  protected readonly rows = computed<Row[]>(() =>
    this.counted()
      .filter((a) => a.balance !== null)
      .map((a) => ({ account: a, amount: a.balance!.amount, date: a.balance!.date }))
      .sort((x, y) => y.amount - x.amount),
  );

  protected readonly total = computed(() => this.rows().reduce((sum, r) => sum + r.amount, 0));

  /** Quanti conti non hanno ancora un saldo (la banca non lo dà, o un conto manuale senza saldo scritto). */
  protected readonly withoutBalance = computed(() => this.counted().length - this.rows().length);

  ngOnInit(): void {
    // A ogni apertura del Riepilogo: i saldi cambiano con ogni aggiornamento dalla banca o importazione.
    this.store.refresh();
  }
}
