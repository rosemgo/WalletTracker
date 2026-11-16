import { DatePipe, formatDate } from '@angular/common';
import {
  Component,
  LOCALE_ID,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { AccountsStore } from '../accounts/accounts-store';
import { Account, BalanceHistory } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { TrendChart } from '../investments/trend-chart';
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
 *
 * Sotto il totale, il grafico della liquidità giorno per giorno (docs/saldi.md, parte L), nella valuta principale; con più
 * valute, dei pulsanti scelgono quale tracciare (una alla volta, ognuna con la sua scala, senza conversioni). Toccando un
 * conto dell'elenco il grafico mostra solo lui, per confrontarlo con il suo estratto:
 * pieno dove ci sono i saldi salvati dall'app, tratteggiato dove è stimato dai movimenti. Scorrendolo, il totale
 * mostra quel giorno. Per il grafico la scheda non è più tutta un collegamento (un tocco per scorrere avrebbe aperto
 * Conti): ci porta "Vedi i conti" in alto.
 */
@Component({
  selector: 'app-liquidity-card',
  imports: [DatePipe, MoneyPipe, RouterLink, TrendChart],
  template: `
    @if (groups().length > 0) {
      <section class="card liquidity-card">
        <div class="card-head">
          <h2>Liquidità</h2>
          <a routerLink="/conti">Vedi i conti ›</a>
        </div>
        <div class="liquidity-totals">
          @for (g of groups(); track g.currency) {
            <!-- Scorrendo il grafico, il totale della valuta del grafico mostra quel giorno. -->
            <strong class="liquidity-total" data-testid="liquidity-total">{{
              (g.currency === chartCurrency() && !chartAccount()
                ? (scrubbed()?.amount ?? g.total)
                : g.total
              ) | money: g.currency : 'symbol' : '1.0-0'
            }}</strong>
          }
        </div>
        <span class="secondary">{{
          (chartAccount() ? null : scrubbed()?.label) ??
            (withBalance() === 1
              ? 'Sul tuo conto, adesso'
              : 'Su ' + withBalance() + ' conti, adesso')
        }}</span>
        @if (groups().length > 1) {
          <!-- Con più valute, quale tracciare: una alla volta, ognuna con la sua scala, senza conversioni. -->
          <div
            class="segmented compact liquidity-currencies"
            role="group"
            aria-label="Valuta del grafico"
            data-testid="chart-currency"
          >
            @for (g of groups(); track g.currency) {
              <button
                type="button"
                [attr.aria-pressed]="g.currency === chartCurrency()"
                (click)="chooseCurrency(g.currency)"
              >
                {{ g.currency }}
              </button>
            }
          </div>
        }
        @if (chartAccount(); as a) {
          <!-- Un conto solo: il suo nome, il saldo del giorno scelto scorrendo, e il ritorno al totale. -->
          <div class="liquidity-account" data-testid="chart-account">
            <span>
              <strong>{{ a.account.label }}</strong>
              @if (scrubbed(); as s) {
                <span class="secondary">
                  {{ s.amount | money: a.account.currency : 'symbol' : '1.0-0' }} ·
                  {{ s.day }}</span
                >
              }
            </span>
            <button type="button" class="link" (click)="chosenAccount.set(null)">
              Tutti i conti
            </button>
          </div>
        }
        @if (chart(); as c) {
          <app-trend-chart
            [values]="c.values"
            [labels]="c.labels"
            [names]="c.names"
            [description]="c.description"
            [currency]="c.currency"
            [estimated]="c.estimated"
            [zeroBased]="false"
            (scrubbed)="scrub.set($event)"
          />
          <div class="chart-keys">
            @if (c.someSaved) {
              <span><span class="key"></span>Saldi salvati</span>
            }
            @if (c.someEstimated) {
              <span><span class="key estimate"></span>Stima dai movimenti</span>
            }
          </div>
        } @else if (historyReady() && (groups().length > 1 || chartAccount())) {
          <p class="secondary history-note">
            Storico non ancora disponibile per
            {{ chartAccount()?.account?.label ?? chartCurrency() }}.
          </p>
        }
        @for (g of groups(); track g.currency) {
          @if (groups().length > 1) {
            <!-- Con più valute, i conti di ognuna sotto il suo codice (EUR, USD...). -->
            <h3 class="liquidity-currency">{{ g.currency }}</h3>
          }
          <ul class="rows">
            @for (r of g.rows; track r.account.id) {
              <li>
                <!-- Toccando un conto il grafico mostra solo lui; toccandolo di nuovo, tutti. -->
                <button
                  type="button"
                  class="liquidity-row"
                  [attr.aria-pressed]="r.account.id === chartAccount()?.account?.id"
                  (click)="chooseAccount(r.account.id)"
                >
                  <span>
                    {{ r.account.label }}
                    <span class="secondary">al {{ r.date | date: 'd MMM' }}</span>
                  </span>
                  <strong>{{ r.amount | money: g.currency }}</strong>
                </button>
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
      </section>
    }
  `,
})
export class LiquidityCard implements OnInit {
  private readonly store = inject(AccountsStore);
  private readonly api = inject(WalletApi);
  private readonly locale = inject(LOCALE_ID);

  /** Lo storico da disegnare (null finché non arriva, o se la richiesta non riesce: niente grafico). */
  protected readonly history = signal<BalanceHistory | null>(null);
  /** Il giorno su cui si scorre nel grafico (indice), o null. */
  protected readonly scrub = signal<number | null>(null);

  constructor() {
    // Lo storico si chiede quando si sa che cosa disegnare: la valuta e, se scelto, il conto. Dipende solo da questa
    // chiave (un computed: una stringa uguale non lo riaccende), non dalla lista dei conti, che si aggiorna più volte
    // (all'apertura del Riepilogo, a ogni refresh): lo storico non si richiede per niente.
    effect(() => {
      const key = this.historyKey();
      untracked(() => this.loadHistory(key));
    });
  }

  /** La valuta scelta con i pulsanti sopra il grafico (null = nessuna scelta: la principale). */
  protected readonly chosenCurrency = signal<string | null>(null);
  /** Il conto scelto toccandolo nell'elenco (null = tutti i conti della valuta). */
  protected readonly chosenAccount = signal<number | null>(null);

  /** Il conto del grafico: quello scelto, se ha ancora un saldo nella scheda; altrimenti nessuno (tutti). */
  protected readonly chartAccount = computed(() => {
    const id = this.chosenAccount();
    return id === null
      ? null
      : (this.groups()
          .flatMap((g) => g.rows)
          .find((r) => r.account.id === id) ?? null);
  });

  /**
   * La valuta del grafico: quella del conto scelto; altrimenti quella scelta, se c'è ancora un conto con un saldo in
   * quella valuta; altrimenti la prima della scheda, quella con più conti (null senza saldi).
   */
  protected readonly chartCurrency = computed(() => {
    const account = this.chartAccount();
    if (account !== null) {
      return account.account.currency;
    }
    const currencies = this.groups().map((g) => g.currency);
    const chosen = this.chosenCurrency();
    return chosen !== null && currencies.includes(chosen) ? chosen : (currencies[0] ?? null);
  });

  /** Che cosa disegnare: "EUR" (tutti i conti in euro) o "EUR|7" (il conto 7); null senza saldi. */
  private readonly historyKey = computed(() => {
    const currency = this.chartCurrency();
    const account = this.chartAccount();
    return currency === null
      ? null
      : account === null
        ? currency
        : `${currency}|${account.account.id}`;
  });

  /** La chiave dello storico arrivato: il grafico si mostra solo se è quella di adesso. */
  private readonly loadedKey = signal<string | null>(null);

  /** Lo storico arrivato è quello da disegnare (non quello di una scelta di prima, ancora in viaggio). */
  protected readonly historyReady = computed(
    () => this.history() !== null && this.loadedKey() === this.historyKey(),
  );

  /** Una valuta: il grafico torna al totale di quella valuta. */
  protected chooseCurrency(currency: string): void {
    this.chosenAccount.set(null);
    this.chosenCurrency.set(currency);
  }

  /** Un conto: il grafico mostra solo lui; toccandolo di nuovo, si torna a tutti. */
  protected chooseAccount(id: number): void {
    this.chosenAccount.set(this.chosenAccount() === id ? null : id);
  }

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

  /**
   * Il grafico: un valore per giorno, le date sotto (giorno e mese, o mese e anno se la storia è lunga), il nome di
   * ogni giorno per lo scorrimento ("3 agosto 2026 · stima"), quali giorni sono stimati. Null con meno
   * di due giorni: una linea di un punto non dice niente.
   */
  protected readonly chart = computed(() => {
    const h = this.history();
    // Cambiando valuta o conto, finché lo storico nuovo non arriva quello di prima non si mostra sotto la scelta nuova.
    if (h === null || !this.historyReady() || h.points.length < 2) {
      return null;
    }
    const points = h.points;
    const long = points.length > 62;
    const estimated = points.map((p) => p.estimated);
    const trueDays = estimated.filter((e) => !e).length;
    const values = points.map((p) => p.amount);
    return {
      currency: h.currency,
      values,
      labels: points.map((p) =>
        formatDate(p.date, long ? 'MMM yy' : 'd MMM', this.locale).replace('.', ''),
      ),
      names: points.map(
        (p) => formatDate(p.date, 'd MMMM y', this.locale) + (p.estimated ? ' · stima' : ''),
      ),
      estimated,
      someEstimated: trueDays < points.length,
      // Un tratto pieno c'è solo tra due giorni salvati di fila: senza, la voce della legenda non avrebbe niente.
      someSaved: estimated.some((e, i) => !e && i + 1 < estimated.length && !estimated[i + 1]),
      description:
        `${this.chartAccount()?.account?.label ?? 'Liquidità'} in ${h.currency} giorno per giorno, dal ` +
        `${formatDate(points[0].date, 'd MMMM y', this.locale)} a oggi. Le parti tratteggiate sono stimate dai movimenti.`,
    };
  });

  /**
   * Il giorno scelto scorrendo: la sua cifra, il giorno ("3 agosto 2026 · stima") e la riga sotto il totale ("Il 3
   * agosto 2026 · stima dai movimenti").
   */
  protected readonly scrubbed = computed(() => {
    const i = this.scrub();
    const point = i === null || !this.historyReady() ? undefined : this.history()?.points[i];
    if (point === undefined) {
      return null;
    }
    const day = formatDate(point.date, 'd MMMM y', this.locale);
    return {
      amount: point.amount,
      day: day + (point.estimated ? ' · stima' : ''),
      label: `Il ${day}` + (point.estimated ? ' · stima dai movimenti' : ''),
    };
  });

  private loadHistory(key: string | null): void {
    this.scrub.set(null);
    if (key === null) {
      this.history.set(null);
      return;
    }
    const [currency, account] = key.split('|');
    this.api.balanceHistory(currency, account ? Number(account) : null).subscribe({
      next: (history) => {
        this.history.set(history);
        this.loadedKey.set(key);
      },
      error: () => this.history.set(null), // senza storico la scheda resta com'era
    });
  }

  ngOnInit(): void {
    // A ogni apertura del Riepilogo: i saldi cambiano con ogni aggiornamento dalla banca o importazione.
    this.store.refresh();
  }
}
