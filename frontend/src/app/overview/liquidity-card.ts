import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject } from '@angular/core';
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

/** I conti di una valuta, con il loro totale. */
interface Group {
  currency: string;
  total: number;
  rows: Row[];
}

/**
 * La scheda "Liquidità" del Riepilogo: quanti soldi ci sono adesso sui conti, in tutto e conto per conto (docs/saldi.md,
 * parte K). Conta i conti di tutti i giorni e anche quelli di investimento (i soldi fermi lì, non investiti in titoli);
 * non conta le carte di credito (il loro è un plafond, non soldi tuoi) né i conti esclusi.
 *
 * Un totale per ogni valuta, senza conversioni: 100 € e 100 $ non si sommano, e un cambio servirebbe un servizio esterno
 * e darebbe una cifra che cambia ogni giorno anche senza movimenti. La scheda mostra sempre tutte le valute: il menu
 * "Valuta" del Riepilogo è per le spese del mese, e compare solo se nel mese ci sono movimenti in più valute (un conto
 * in dollari fermo sparirebbe).
 *
 * I saldi sono quelli di adesso: la scheda non cambia guardando un mese passato. Un conto senza saldo non entra nel
 * totale, e la scheda lo dice. Senza nessun saldo la scheda non compare.
 */
@Component({
  selector: 'app-liquidity-card',
  imports: [DatePipe, MoneyPipe, RouterLink],
  template: `
    @if (groups().length > 0) {
      <a class="card liquidity-card" routerLink="/conti">
        <div class="card-head">
          <h2>Liquidità</h2>
          <span aria-hidden="true">Vedi i conti ›</span>
        </div>
        <div class="liquidity-totals">
          @for (g of groups(); track g.currency) {
            <strong class="liquidity-total" data-testid="liquidity-total">{{
              g.total | money: g.currency : 'symbol' : '1.0-0'
            }}</strong>
          }
        </div>
        <span class="secondary">{{
          withBalance() === 1 ? 'Sul tuo conto, adesso' : 'Su ' + withBalance() + ' conti, adesso'
        }}</span>
        @for (g of groups(); track g.currency) {
          @if (groups().length > 1) {
            <!-- Con più valute, i conti di ognuna sotto il suo codice (EUR, USD...). -->
            <h3 class="liquidity-currency">{{ g.currency }}</h3>
          }
          <ul class="rows">
            @for (r of g.rows; track r.account.id) {
              <li>
                <span>
                  {{ r.account.label }}
                  <span class="secondary">al {{ r.date | date: 'd MMM' }}</span>
                </span>
                <strong>{{ r.amount | money: g.currency }}</strong>
              </li>
            }
          </ul>
        }
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

  /** I conti che contano: niente carte di credito né conti esclusi. */
  private readonly counted = computed(() =>
    this.store.accounts().filter((a) => a.role !== 'CREDIT_CARD' && a.role !== 'EXCLUDED'),
  );

  /**
   * I conti con un saldo, divisi per valuta. Prima la valuta con più conti (di solito l'euro), a parità in ordine
   * alfabetico; dentro ogni valuta, dal conto più ricco.
   */
  protected readonly groups = computed<Group[]>(() => {
    const byCurrency = new Map<string, Row[]>();
    for (const a of this.counted()) {
      if (a.balance === null) {
        continue;
      }
      const rows = byCurrency.get(a.currency) ?? [];
      rows.push({ account: a, amount: a.balance.amount, date: a.balance.date });
      byCurrency.set(a.currency, rows);
    }
    return [...byCurrency.entries()]
      .map(([currency, rows]) => ({
        currency,
        total: rows.reduce((sum, r) => sum + r.amount, 0),
        rows: rows.sort((x, y) => y.amount - x.amount),
      }))
      .sort((x, y) => y.rows.length - x.rows.length || x.currency.localeCompare(y.currency));
  });

  /** Quanti conti hanno un saldo, in tutte le valute. */
  protected readonly withBalance = computed(() =>
    this.groups().reduce((sum, g) => sum + g.rows.length, 0),
  );

  /** Quanti conti non hanno ancora un saldo (la banca non lo dà, o un conto manuale senza saldo scritto). */
  protected readonly withoutBalance = computed(() => this.counted().length - this.withBalance());

  ngOnInit(): void {
    // A ogni apertura del Riepilogo: i saldi cambiano con ogni aggiornamento dalla banca o importazione.
    this.store.refresh();
  }
}
