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
import { LocalDatePipe, formatLocalDate } from '../text/dates';

/** Dove il browser ricorda il periodo scelto per il grafico. */
export const RANGE_KEY = 'wallettracker.liquidity-range';

/**
 * I periodi del grafico, in giorni: 1 mese, 3 mesi, 1 anno (lo storico non va più indietro di un anno). Sui pulsanti la
 * sigla delle app di finanza (1M, 3M, 1A): con le parole intere non stavano sul telefono accanto alla legenda.
 */
const RANGES = [
  { days: 30, label: $localize`:sigla di "1 mese":1M`, name: $localize`1 mese` },
  { days: 90, label: $localize`:sigla di "3 mesi":3M`, name: $localize`3 mesi` },
  { days: 365, label: $localize`:sigla di "1 anno":1A`, name: $localize`1 anno` },
];

/** Il periodo ricordato nel browser, se è uno dei RANGES; altrimenti 3 mesi. */
function rememberedRange(): number {
  try {
    const days = Number(localStorage.getItem(RANGE_KEY));
    return RANGES.some((r) => r.days === days) ? days : 90;
  } catch {
    return 90;
  }
}

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
 * mostra quel giorno. Di partenza gli ultimi 3 mesi (in un anno intero un giorno è largo meno di un pixel), con i
 * pulsanti 1 mese, 3 mesi, 1 anno; la scelta si ricorda nel browser. Cambiando conto o valuta il grafico di prima resta
 * (un poco più chiaro) finché non arriva il nuovo: la scheda non cambia altezza e la pagina non salta. Per il grafico la scheda non è più tutta un collegamento (un tocco per scorrere avrebbe aperto
 * Conti): ci porta "Vedi i conti" in alto.
 */
@Component({
  selector: 'app-liquidity-card',
  imports: [LocalDatePipe, MoneyPipe, RouterLink, TrendChart],
  template: `
    @if (groups().length > 0) {
      <section class="card liquidity-card">
        <div class="card-head">
          <h2 i18n>Liquidità</h2>
          <a i18n routerLink="/conti">Vedi i conti ›</a>
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
        <!-- Scorrendo il grafico di tutti i conti, il giorno scelto; altrimenti quanti conti ci sono nel totale. -->
        <span class="secondary liquidity-when">
          @if (!chartAccount() && scrubbed(); as s) {
            {{ s.label }}
          } @else {
            <ng-container i18n>{withBalance(), plural,
              =1 {Sul tuo conto, adesso}
              other {Su {{ withBalance() }} conti, adesso}
            }</ng-container>
          }
        </span>
        @if (groups().length > 1) {
          <!-- Con più valute, quale tracciare: una alla volta, ognuna con la sua scala, senza conversioni. -->
          <div
            class="segmented compact liquidity-currencies"
            role="group"
            aria-label="Valuta del grafico"
            i18n-aria-label
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
        @if (chartAccount() || slot() !== null) {
          <!--
            Sopra il grafico, due righe sempre: il conto scelto (o "Tutti i conti") e sotto, su una riga sola, il saldo del
            giorno scelto scorrendo, o quanto è cambiato nel periodo mostrato. Alta sempre uguale: scorrendo o cambiando
            conto il grafico non si sposta (prima il saldo stava accanto al nome e, se era lungo, andava a capo).
          -->
          <div
            class="liquidity-account"
            [attr.data-testid]="chartAccount() ? 'chart-account' : null"
          >
            <span>
              @if (chartAccount(); as a) {
                <strong>{{ a.account.label }}</strong>
              } @else {
                <strong i18n>Tutti i conti</strong>
              }
              <span class="secondary">
                @if (chartAccount() && scrubbed(); as s) {
                  {{ s.amount | money: chartAccount()!.account.currency : 'symbol' : '1.0-0' }} ·
                  {{ s.day }}
                } @else if (change(); as d) {
                  <ng-container i18n
                    >{{ d.sign }}{{ d.amount | money: d.currency : 'symbol' : '1.0-0' }} dal
                    {{ d.from }}</ng-container
                  >
                } @else {
                  <!-- Vuota (lo storico sta arrivando), ma con una riga di testo: alta come le altre. -->
                  &nbsp;
                }
              </span>
            </span>
            @if (chartAccount()) {
              <button i18n type="button" class="link" (click)="chosenAccount.set(null)">
                Tutti i conti
              </button>
            }
          </div>
        }
        @if (chart(); as c) {
          <app-trend-chart
            [class.loading]="!historyReady()"
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
            <div class="liquidity-legend">
              @if (c.someSaved) {
                <span i18n><span class="key"></span>Saldi salvati</span>
              }
              @if (c.someEstimated) {
                <span i18n><span class="key estimate"></span>Stima dai movimenti</span>
              }
            </div>
            @if (ranges().length > 1) {
              <!-- Quanti giorni mostrare: di partenza 3 mesi, dove un giorno è largo abbastanza da vedersi. -->
              <span
                class="segmented compact liquidity-range"
                role="group"
                aria-label="Periodo del grafico"
                i18n-aria-label
                data-testid="chart-range"
              >
                @for (r of ranges(); track r.days) {
                  <button
                    type="button"
                    [attr.aria-pressed]="r.days === shownRange()"
                    [attr.aria-label]="r.name"
                    [title]="r.name"
                    (click)="chooseRange(r.days)"
                  >
                    {{ r.label }}
                  </button>
                }
              </span>
            }
          </div>
        } @else if (slot() === 'empty') {
          <!--
            Al posto del grafico, grande quanto lui, e sotto una riga della legenda vuota, alta come quella vera: passando
            a un conto senza storico la pagina non salta.
          -->
          <div class="liquidity-chart-empty">
            <p i18n class="secondary history-note">
              Storico non ancora disponibile per
              {{ chartAccount()?.account?.label ?? chartCurrency() }}.
            </p>
          </div>
          <div class="chart-keys" aria-hidden="true"></div>
        } @else if (slot() === 'waiting') {
          <!-- Lo storico sta arrivando: il suo posto è già tenuto, e quello che c'è sotto non scende quando arriva. -->
          <div class="liquidity-chart-empty" aria-hidden="true"></div>
          <div class="chart-keys" aria-hidden="true"></div>
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
                    <span i18n class="secondary">al {{ r.date | localDate: 'dayMonth' }}</span>
                  </span>
                  <strong>{{ r.amount | money: g.currency }}</strong>
                </button>
              </li>
            }
          </ul>
        }
        @if (withoutBalance() > 0) {
          <p class="secondary missing" i18n>
            {withoutBalance(), plural,
              =1 {1 conto senza saldo non è nel totale}
              other {{{ withoutBalance() }} conti senza saldo non sono nel totale}
            }
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
  /** Il giorno su cui si scorre nel grafico (indice tra i giorni mostrati), o null. */
  protected readonly scrub = signal<number | null>(null);
  /** Quanti giorni mostrare (uno dei RANGES): l'ultima scelta, ricordata nel browser, o 3 mesi. */
  protected readonly range = signal<number>(rememberedRange());

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

  /** La richiesta dello storico non è riuscita: niente grafico, e niente posto tenuto per lui. */
  private readonly historyFailed = signal(false);

  /**
   * Che cosa c'è al posto del grafico: il grafico ("chart"), la frase "Storico non ancora disponibile" ("empty", con più
   * valute o un conto scelto), il posto vuoto mentre lo storico arriva ("waiting"), o niente (null). Le ultime due sono
   * grandi quanto il grafico: aprendo il Riepilogo o cambiando conto la pagina non salta.
   */
  protected readonly slot = computed<'chart' | 'empty' | 'waiting' | null>(() => {
    if (this.chart() !== null) {
      return 'chart';
    }
    if (this.historyFailed() || this.historyKey() === null) {
      return null;
    }
    if (!this.historyReady()) {
      return 'waiting';
    }
    return this.groups().length > 1 || this.chartAccount() !== null ? 'empty' : null;
  });

  /** Lo storico arrivato è quello da disegnare (non quello di una scelta di prima, ancora in viaggio). */
  protected readonly historyReady = computed(
    () => this.history() !== null && this.loadedKey() === this.historyKey(),
  );

  /** Una valuta: il grafico torna al totale di quella valuta. */
  protected chooseCurrency(currency: string): void {
    this.chosenAccount.set(null);
    this.chosenCurrency.set(currency);
  }

  /** Un periodo (1 mese, 3 mesi, 1 anno): il grafico mostra gli ultimi giorni, e la scelta si ricorda. */
  protected chooseRange(days: number): void {
    this.scrub.set(null);
    this.range.set(days);
    try {
      localStorage.setItem(RANGE_KEY, String(days));
    } catch {
      // navigazione privata o memoria del browser piena: la scelta vale solo fino alla prossima apertura
    }
  }

  /**
   * I periodi da offrire per lo storico arrivato: quelli più corti della storia, più il primo che la contiene tutta (un
   * conto con 2 mesi di storia: 1 mese e 3 mesi, perché "1 anno" mostrerebbe lo stesso). Con uno solo, nessuna scelta.
   */
  protected readonly ranges = computed(() => {
    const n = this.history()?.points.length ?? 0;
    const offered = RANGES.filter((r, i) => r.days < n || i === 0 || RANGES[i - 1].days < n);
    return offered.length > 1 ? offered : [];
  });

  /** Il periodo mostrato: quello scelto, o il più lungo offerto se la storia è più corta. */
  protected readonly shownRange = computed(() => {
    const offered = this.ranges();
    const chosen = this.range();
    return offered.some((r) => r.days === chosen) ? chosen : (offered.at(-1)?.days ?? chosen);
  });

  /** I giorni da disegnare: gli ultimi del periodo scelto; con il periodo più lungo offerto, tutta la storia. */
  private readonly visiblePoints = computed(() => {
    const points = this.history()?.points ?? [];
    const days = this.shownRange();
    return days === this.ranges().at(-1)?.days || points.length <= days
      ? points
      : points.slice(-days);
  });

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
    // Cambiando valuta o conto, finché lo storico nuovo non arriva resta quello di prima (più chiaro, la classe loading):
    // togliendolo, la scheda si accorciava e tutta la pagina saltava, due volte.
    const points = this.visiblePoints();
    if (h === null || points.length < 2) {
      return null;
    }
    const long = points.length > 120;
    const estimated = points.map((p) => p.estimated);
    const trueDays = estimated.filter((e) => !e).length;
    const values = points.map((p) => p.amount);
    return {
      currency: h.currency,
      values,
      labels: points.map((p) =>
        formatLocalDate(p.date, long ? 'monthShortYear' : 'dayMonth', this.locale).replace('.', ''),
      ),
      names: points.map((p) => this.dayName(p.date, p.estimated)),
      estimated,
      someEstimated: trueDays < points.length,
      // Un tratto pieno c'è solo tra due giorni salvati di fila: senza, la voce della legenda non avrebbe niente.
      someSaved: estimated.some((e, i) => !e && i + 1 < estimated.length && !estimated[i + 1]),
      description: this.description(
        h.currency,
        formatLocalDate(points[0].date, 'long', this.locale),
      ),
    };
  });

  /**
   * Quanto è cambiata la liquidità (o il conto scelto) nel periodo mostrato: "+1.250 € dal 22 agosto". Null senza
   * grafico, o mentre arriva lo storico di un'altra scelta.
   */
  protected readonly change = computed(() => {
    const points = this.visiblePoints();
    const h = this.history();
    if (h === null || points.length < 2 || !this.historyReady()) {
      return null;
    }
    const delta = points[points.length - 1].amount - points[0].amount;
    return {
      sign: delta > 0 ? '+' : delta < 0 ? '−' : '',
      amount: Math.abs(delta),
      currency: h.currency,
      from: formatLocalDate(points[0].date, 'dayMonth', this.locale).replace('.', ''),
    };
  });

  /**
   * Il giorno scelto scorrendo: la sua cifra, il giorno ("3 agosto 2026 · stima") e la riga sotto il totale ("Il 3
   * agosto 2026 · stima dai movimenti").
   */
  protected readonly scrubbed = computed(() => {
    const i = this.scrub();
    const point = i === null || !this.historyReady() ? undefined : this.visiblePoints()[i];
    if (point === undefined) {
      return null;
    }
    const day = formatLocalDate(point.date, 'long', this.locale);
    return {
      amount: point.amount,
      day: this.dayName(point.date, point.estimated),
      // Due frasi intere: in un'altra lingua "Il 3 agosto 2026" non si scrive mettendo "Il" davanti.
      label: point.estimated
        ? $localize`Il ${day}:day: · stima dai movimenti`
        : $localize`Il ${day}:day:`,
    };
  });

  private loadHistory(key: string | null): void {
    this.scrub.set(null);
    this.historyFailed.set(false);
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
      error: () => {
        // senza storico la scheda resta com'era, senza grafico
        this.history.set(null);
        this.historyFailed.set(true);
      },
    });
  }

  /** Il nome di un giorno del grafico: "3 agosto 2026", con " · stima" se è stimato dai movimenti. */
  private dayName(date: string, estimated: boolean): string {
    const day = formatLocalDate(date, 'long', this.locale);
    return estimated ? $localize`${day}:day: · stima` : day;
  }

  /** La descrizione del grafico per chi usa un lettore di schermo: di tutti i conti, o del conto scelto. */
  private description(currency: string, from: string): string {
    const account = this.chartAccount()?.account?.label;
    return account === undefined
      ? $localize`Liquidità in ${currency}:currency: giorno per giorno, dal ${from}:day: a oggi. Le parti tratteggiate sono stimate dai movimenti.`
      : $localize`${account}:account: in ${currency}:currency: giorno per giorno, dal ${from}:day: a oggi. Le parti tratteggiate sono stimate dai movimenti.`;
  }

  ngOnInit(): void {
    // A ogni apertura del Riepilogo: i saldi cambiano con ogni aggiornamento dalla banca o importazione.
    this.store.refresh();
  }
}
