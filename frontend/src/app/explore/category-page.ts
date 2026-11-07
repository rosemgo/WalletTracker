import { DecimalPipe, formatCurrency, formatDate } from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';

import { CategoryOverview, ExploreGroup } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MoneyPipe } from '../privacy/money.pipe';
import { PrivacyStore } from '../privacy/privacy-store';
import { merchantSearch } from '../transactions/merchant-search';
import { BarChart } from './bar-chart';

/** Sotto questa differenza (in euro) si è "in linea con il solito", come nel Riepilogo. */
const SAME_AS_USUAL = 5;

const MONTH_INITIALS = ['G', 'F', 'M', 'A', 'M', 'G', 'L', 'A', 'S', 'O', 'N', 'D'];

/**
 * La pagina di una categoria (docs/38, parte F): quanto nel mese rispetto al solito, il budget con la tacca del ritmo,
 * gli ultimi 12 mesi con la media (o solo l'anno, da gennaio: "Quest'anno"), gli esercenti del mese; e i link ai
 * movimenti e al budget.
 */
@Component({
  selector: 'app-category-page',
  imports: [BarChart, DecimalPipe, MoneyPipe, RouterLink],
  templateUrl: './category-page.html',
})
export class CategoryPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly route = inject(ActivatedRoute);
  private readonly locale = inject(LOCALE_ID);
  private readonly privacy = inject(PrivacyStore);

  private readonly router = inject(Router);

  /** Il mese guardato: la cifra grande, il budget e "Dove". Cambia toccando una barra. */
  protected readonly overview = signal<CategoryOverview | null>(null);
  /**
   * I mesi del grafico: quelli del primo caricamento, che restano fermi quando si tocca una barra (così si confrontano
   * i mesi). Se ogni tocco ricaricasse anche il grafico, i 12 mesi finirebbero con quello toccato e le barre
   * "scapperebbero".
   */
  private readonly chart = signal<CategoryOverview | null>(null);
  /** Si sta caricando il mese toccato. */
  protected readonly loadingMonth = signal(false);
  private categoryId = 0;
  protected readonly error = signal<string | null>(null);
  /** Il grafico: gli ultimi 12 mesi, o l'anno del mese guardato da gennaio (YTD, docs/38 aggiunta "Quest'anno"). */
  protected readonly chartPeriod = signal<'YEAR' | 'YEAR_TO_DATE'>('YEAR_TO_DATE'); // di serie: Quest'anno

  /** "ottobre": il mese guardato, in parole. */
  protected readonly monthName = computed(() => {
    const o = this.overview();
    return o === null ? '' : formatDate(`${o.month}-01`, 'MMMM', this.locale);
  });

  /** Il mese è quello in corso? (allora i numeri sono "finora") */
  protected readonly isCurrent = computed(() => {
    const o = this.overview();
    return o !== null && o.day !== lastDayOf(o.month);
  });

  /** La frase sotto la cifra. */
  protected readonly comparison = computed(() => {
    const o = this.overview();
    if (o === null) {
      return '';
    }
    const when = this.isCurrent() ? `a ${this.monthName()}, finora` : `a ${this.monthName()}`;
    if (o.usualSoFar === null) {
      return `${when}.`;
    }
    const difference = o.amount - o.usualSoFar;
    const usual = this.euros(o.usualSoFar, o.currency);
    if (Math.abs(difference) < SAME_AS_USUAL) {
      return `${when} · come al solito (${usual}).`;
    }
    const delta = this.euros(Math.abs(difference), o.currency);
    return `${when} · ${delta} ${difference > 0 ? 'più' : 'meno'} del solito (${usual}).`;
  });

  /** Il budget, già pronto per la barra con la tacca. */
  protected readonly budget = computed(() => {
    const o = this.overview();
    const b = o?.budget;
    if (!o || !b) {
      return null;
    }
    const daysLeft = this.isCurrent() ? daysInMonth(o.month) - Number(o.day.slice(8, 10)) : 0;
    const remaining =
      b.remaining >= 0
        ? `${this.euros(b.remaining, o.currency)} rimasti`
        : `superato di ${this.euros(-b.remaining, o.currency)}`;
    return {
      amount: b.monthlyAmount,
      used: Math.min(100, (b.spent / b.monthlyAmount) * 100),
      // La tacca: dove dovresti essere oggi spendendo in modo regolare (solo per il mese in corso).
      pace: this.isCurrent() ? Math.min(100, (b.expectedSoFar / b.monthlyAmount) * 100) : null,
      over: b.status === 'OVER',
      text:
        daysLeft > 0
          ? `${remaining} · ${daysLeft === 1 ? '1 giorno' : daysLeft + ' giorni'}`
          : remaining,
    };
  });

  /**
   * I mesi del grafico. L'API dà i 12 che finiscono con il mese guardato: da gennaio ci sono sempre tutti, quindi per
   * "Quest'anno" basta tenere quelli dello stesso anno.
   */
  private readonly chartMonths = computed(() => {
    const o = this.chart();
    if (o === null) {
      return [];
    }
    const year = o.month.slice(0, 4);
    return this.chartPeriod() === 'YEAR'
      ? o.months
      : o.months.filter((m) => m.month.startsWith(year));
  });

  protected readonly monthValues = computed(() => this.chartMonths().map((m) => m.value));
  protected readonly monthLabels = computed(() =>
    this.chartMonths().map((m) => MONTH_INITIALS[Number(m.month.slice(5, 7)) - 1]),
  );

  /** La barra in evidenza: il mese guardato (null se con "Quest'anno" non è tra le barre). */
  protected readonly selectedBar = computed(() => {
    const month = this.overview()?.month;
    const i = this.chartMonths().findIndex((m) => m.month === month);
    return i < 0 ? null : i;
  });

  /** Sopra il grafico: "ottobre 2026: 360,00 €", il mese in evidenza e la sua cifra. */
  protected readonly barText = computed(() => {
    const o = this.overview();
    const i = this.selectedBar();
    if (o === null || i === null) {
      return '';
    }
    const m = this.chartMonths()[i];
    return `${formatDate(`${m.month}-01`, 'MMMM y', this.locale)}: ${this.euros(m.value, o.currency, 2)}`;
  });

  /** Il nome di ogni barra per il lettore di schermo: "ottobre 2026, 360 €". */
  protected readonly barNames = computed(() => {
    const o = this.chart();
    return this.chartMonths().map(
      (m) =>
        `${formatDate(`${m.month}-01`, 'MMMM y', this.locale)}, ${this.euros(m.value, o?.currency ?? 'EUR')}`,
    );
  });

  /** Il titolo del grafico: "Ultimi 12 mesi", "Quest'anno", o "Gennaio–ottobre 2025" per un anno passato. */
  protected readonly chartTitle = computed(() => {
    const o = this.chart();
    if (this.chartPeriod() === 'YEAR' || o === null) {
      return 'Ultimi 12 mesi';
    }
    if (o.month.slice(0, 4) === String(new Date().getFullYear())) {
      return "Quest'anno";
    }
    return o.month.endsWith('-01')
      ? formatDate(`${o.month}-01`, 'MMMM y', this.locale).replace(/^./, (c) => c.toUpperCase())
      : `Gennaio–${formatDate(`${o.month}-01`, 'MMMM y', this.locale)}`;
  });

  protected readonly chartDescription = computed(() => {
    const o = this.overview();
    if (o === null) {
      return '';
    }
    const usual =
      o.usualMonth === null ? '' : `, di solito ${this.euros(o.usualMonth, o.currency)} al mese`;
    const when = this.chartPeriod() === 'YEAR' ? 'negli ultimi 12 mesi' : 'da gennaio';
    return `${o.name} ${when}${usual}; ${this.monthName()} ${this.euros(o.amount, o.currency)}`;
  });

  /** La linea "di solito" del grafico: quella del primo caricamento, come le barre (non cambia toccandole). */
  protected readonly chartAverage = computed(() => this.chart()?.usualMonth ?? null);

  protected readonly averageLabel = computed(() => {
    const o = this.chart();
    return o?.usualMonth == null ? '' : `di solito ${this.euros(o.usualMonth, o.currency)}`;
  });

  /** I filtri di Movimenti per un esercente della categoria, nel mese guardato. */
  protected merchantQuery(m: ExploreGroup): Record<string, string> {
    const o = this.overview()!;
    const query: Record<string, string> = {
      categoryId: String(o.categoryId),
      from: `${o.month}-01`,
      to: o.day,
    };
    if (m.key) {
      query['text'] = merchantSearch(m.key);
    }
    return query;
  }

  ngOnInit(): void {
    const param = this.route.snapshot.queryParamMap.get('mese');
    const month = param && /^\d{4}-\d{2}$/.test(param) ? param : null;
    this.route.paramMap.subscribe((params) => {
      this.categoryId = Number(params.get('id'));
      // Il grafico arriva sempre fino al mese in corso (la richiesta senza mese), e la barra accesa è quella del mese
      // aperto. Solo un mese più vecchio delle 12 barre ha il grafico che finisce con lui: altrimenti non ci sarebbe.
      const chartMonth = month !== null && monthsBefore(month, currentMonth()) < 12 ? null : month;
      const chart$ = this.api.categoryOverview(this.categoryId, chartMonth);
      // Il mese aperto, se non è già quello del grafico (null: niente seconda richiesta).
      const detail$ =
        month === null || chartMonth === month || month === currentMonth()
          ? of(null)
          : this.api.categoryOverview(this.categoryId, month);
      forkJoin({ chart: chart$, detail: detail$ }).subscribe({
        next: ({ chart, detail }) => {
          this.chart.set(chart);
          this.overview.set(detail ?? chart);
        },
        error: (response) =>
          this.error.set(
            response.status === 404
              ? 'Questa categoria non esiste più.'
              : 'Impossibile caricare la categoria: il backend è avviato?',
          ),
      });
    });
  }

  /**
   * Una barra toccata: la cifra, il budget e "Dove" passano a quel mese; il grafico resta com'è. Anche l'indirizzo
   * (?mese=) cambia, così ricaricando la pagina si torna allo stesso mese.
   */
  pickMonth(index: number): void {
    const month = this.chartMonths()[index]?.month;
    const chart = this.chart();
    if (!month || chart === null || month === this.overview()?.month) {
      return;
    }
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { mese: month },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    if (month === chart.month) {
      this.overview.set(chart);
      return;
    }
    this.loadingMonth.set(true);
    this.api.categoryOverview(this.categoryId, month).subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.loadingMonth.set(false);
      },
      error: () => {
        this.loadingMonth.set(false);
        this.error.set('Impossibile caricare quel mese: il backend è avviato?');
      },
    });
  }

  private euros(value: number, currency: string, decimals = 0): string {
    // Con la modalità discreta, le cifre nascoste (docs/42): anche nelle frasi costruite qui.
    const digits = decimals === 0 ? '1.0-0' : `1.${decimals}-${decimals}`;
    return this.privacy.hide(
      formatCurrency(value, this.locale, currency === 'EUR' ? '€' : currency, currency, digits),
    );
  }
}

function daysInMonth(month: string): number {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m, 0).getDate(); // il giorno 0 del mese dopo è l'ultimo di questo
}

function lastDayOf(month: string): string {
  return `${month}-${String(daysInMonth(month)).padStart(2, '0')}`;
}

/** Il mese in corso, "2026-11". */
function currentMonth(): string {
  const today = new Date();
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
}

/** Quanti mesi da {@code month} a {@code later} ("2026-10", "2026-11" → 1). */
function monthsBefore(month: string, later: string): number {
  const [y1, m1] = month.split('-').map(Number);
  const [y2, m2] = later.split('-').map(Number);
  return (y2 - y1) * 12 + (m2 - m1);
}
