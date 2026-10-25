import {
  CurrencyPipe,
  DatePipe,
  NgTemplateOutlet,
  formatCurrency,
  formatDate,
} from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AlertsPanel } from '../alerts/alerts-panel';
import { Overview, OverviewCategory } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MonthChart } from './month-chart';

/** Quante categorie mostrare in "Dove vanno": le altre sono in Analisi. */
export const TOP_CATEGORIES = 5;

/** Sotto questa differenza (in euro) si è "in linea con il solito": 3 € di scarto non sono una notizia. */
export const SAME_AS_USUAL = 5;

/** "2026-10" ± n mesi, sempre nel formato "2026-10". */
export function addMonths(month: string, n: number): string {
  const [year, m] = month.split('-').map(Number);
  const date = new Date(year, m - 1 + n, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/** Il mese di una data, nel formato "2026-10" (dell'ora locale, non di Greenwich). */
export function monthOf(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/** La prima lettera maiuscola: "ottobre 2026" → "Ottobre 2026". */
function capitalized(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Il Riepilogo (docs/36): il mese in corso a colpo d'occhio. Una cifra grande (quanto hai speso finora) con la frase
 * che la spiega rispetto al solito, il grafico del mese, dove vanno i soldi, che cosa arriva nei prossimi giorni e
 * che cosa c'è da sistemare. Con le frecce si guardano i mesi passati.
 *
 * I numeri li calcola il backend (GET /api/overview): la pagina li mette in parole.
 */
@Component({
  selector: 'app-overview-page',
  imports: [AlertsPanel, CurrencyPipe, DatePipe, MonthChart, NgTemplateOutlet, RouterLink],
  templateUrl: './overview-page.html',
})
export class OverviewPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly locale = inject(LOCALE_ID);

  /** Il mese di oggi: non si va oltre. */
  protected readonly currentMonth = monthOf(new Date());

  protected readonly month = signal(this.currentMonth);
  /** La valuta scelta; null = quella con più spese (la decide il backend). */
  protected readonly currency = signal<string | null>(null);
  protected readonly overview = signal<Overview | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Il numero dell'ultima richiesta: con le frecce premute in fretta conta solo l'ultima risposta. */
  private lastRequest = 0;

  protected readonly isCurrent = computed(() => this.month() === this.currentMonth);

  /** "Ottobre 2026". */
  protected readonly monthTitle = computed(() =>
    capitalized(formatDate(`${this.month()}-01`, 'MMMM y', this.locale)),
  );

  /** "ott", per le date sotto il grafico. */
  protected readonly monthShort = computed(() =>
    formatDate(`${this.month()}-01`, 'MMM', this.locale).replace('.', ''),
  );

  /** Sopra il titolo: "Domenica 25" per il mese in corso, "Tutto il mese" per uno passato. */
  protected readonly dayLabel = computed(() => {
    const o = this.overview();
    if (!this.isCurrent() || o === null) {
      return 'Tutto il mese';
    }
    return capitalized(formatDate(o.day, 'EEEE d', this.locale));
  });

  /** Il giorno su cui si scorre nel grafico (null = nessuno): la cifra grande mostra quel giorno (docs/41, parte G). */
  protected readonly scrubDay = signal<number | null>(null);

  /** La cifra grande e la sua etichetta: il mese fino a oggi, o il giorno scelto scorrendo il grafico. */
  protected readonly figure = computed(() => {
    const o = this.overview();
    if (o === null) {
      return null;
    }
    const day = this.scrubDay();
    if (day !== null && day <= o.spentByDay.length) {
      return { label: `Speso fino al ${day} ${this.monthShort()}`, value: o.spentByDay[day - 1] };
    }
    return { label: this.isCurrent() ? 'Speso finora' : 'Speso nel mese', value: o.spent };
  });

  /** La frase sotto la cifra grande: com'è il mese rispetto al solito. */
  protected readonly comparison = computed(() => {
    const o = this.overview();
    return o === null
      ? ''
      : comparisonSentence(o, this.isCurrent(), (v) => this.euros(v, o.currency));
  });

  protected readonly topCategories = computed(() =>
    (this.overview()?.categories ?? []).slice(0, TOP_CATEGORIES),
  );

  /** La lunghezza delle barre: la categoria più alta è lunga tutta la riga. */
  protected readonly highest = computed(() =>
    Math.max(1, ...this.topCategories().map((c) => c.amount)),
  );

  /** La descrizione del grafico per chi usa un lettore di schermo. */
  protected readonly chartDescription = computed(() => {
    const o = this.overview();
    if (o === null) {
      return '';
    }
    const spent = `Spesa accumulata nel mese: ${this.euros(o.spent, o.currency)} al giorno ${o.spentByDay.length}`;
    return o.usualSoFar === null
      ? spent
      : `${spent}, contro ${this.euros(o.usualSoFar, o.currency)} di un mese tipico allo stesso giorno`;
  });

  protected readonly toReviewLabel = computed(() => {
    const n = this.overview()?.toReview ?? 0;
    return n === 1 ? '1 movimento da verificare' : `${n} movimenti da verificare`;
  });

  protected readonly suggestionsLabel = computed(() => {
    const n = this.overview()?.suggestions ?? 0;
    return n === 1 ? '1 categoria suggerita' : `${n} categorie suggerite`;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    const request = ++this.lastRequest;
    this.api.overview(this.month(), this.currency()).subscribe({
      next: (overview) => {
        if (request === this.lastRequest) {
          this.overview.set(overview);
          this.loading.set(false);
        }
      },
      error: (response) => {
        if (request === this.lastRequest) {
          this.error.set(
            response.status === 400
              ? (response.error?.detail ?? 'Richiesta non valida.')
              : 'Impossibile caricare il riepilogo: il backend è avviato?',
          );
          this.loading.set(false);
        }
      },
    });
  }

  previous(): void {
    this.month.set(addMonths(this.month(), -1));
    this.load();
  }

  next(): void {
    if (!this.isCurrent()) {
      this.month.set(addMonths(this.month(), 1));
      this.load();
    }
  }

  onCurrency(event: Event): void {
    this.currency.set((event.target as HTMLSelectElement).value);
    this.load();
  }

  /** La riga sotto una categoria: la differenza dal solito, o quante volte se non c'è un solito. */
  protected categoryNote(category: OverviewCategory, currency: string): string {
    return categoryNote(category, (v) => this.euros(v, currency));
  }

  protected categoryName(category: OverviewCategory): string {
    return category.name ?? 'Senza categoria';
  }

  /** Un importo senza decimali: "1.284 €". */
  private euros(value: number, currency: string): string {
    return formatCurrency(
      value,
      this.locale,
      currency === 'EUR' ? '€' : currency,
      currency,
      '1.0-0',
    );
  }
}

/**
 * La frase sul mese rispetto al solito. Esportata per i test: è la parte della pagina con più casi.
 *
 * @param euros come scrivere un importo ("126 €")
 */
export function comparisonSentence(
  o: Overview,
  isCurrent: boolean,
  euros: (v: number) => string,
): string {
  if (o.usualSoFar === null) {
    return 'Il confronto con il solito comincia dopo il primo mese completo di movimenti.';
  }
  const when = isCurrent ? ' a questo punto del mese' : '';
  const difference = o.spent - o.usualSoFar;
  if (Math.abs(difference) < SAME_AS_USUAL) {
    return `In linea con il solito${when}.`;
  }
  return difference < 0
    ? `${euros(-difference)} in meno del solito${when}.`
    : `${euros(difference)} più del solito${when}.`;
}

/** La riga sotto una categoria. */
export function categoryNote(category: OverviewCategory, euros: (v: number) => string): string {
  if (category.usualSoFar === null) {
    return category.movements === 1 ? '1 movimento' : `${category.movements} movimenti`;
  }
  const difference = category.amount - category.usualSoFar;
  if (Math.abs(difference) < SAME_AS_USUAL) {
    return 'come al solito';
  }
  return `${difference > 0 ? '+' : '−'}${euros(Math.abs(difference))} sul solito`;
}
