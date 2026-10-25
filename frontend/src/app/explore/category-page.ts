import { DecimalPipe, formatCurrency, formatDate } from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { CategoryOverview } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MoneyPipe } from '../privacy/money.pipe';
import { PrivacyStore } from '../privacy/privacy-store';
import { BarChart } from './bar-chart';

/** Sotto questa differenza (in euro) si è "in linea con il solito", come nel Riepilogo. */
const SAME_AS_USUAL = 5;

const MONTH_INITIALS = ['G', 'F', 'M', 'A', 'M', 'G', 'L', 'A', 'S', 'O', 'N', 'D'];

/**
 * La pagina di una categoria (docs/38, parte F): quanto nel mese rispetto al solito, il budget con la tacca del ritmo,
 * gli ultimi 12 mesi con la media, gli esercenti del mese; e i link ai movimenti e al budget.
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

  protected readonly overview = signal<CategoryOverview | null>(null);
  protected readonly error = signal<string | null>(null);

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

  protected readonly monthValues = computed(
    () => this.overview()?.months.map((m) => m.value) ?? [],
  );
  protected readonly monthLabels = computed(
    () => this.overview()?.months.map((m) => MONTH_INITIALS[Number(m.month.slice(5, 7)) - 1]) ?? [],
  );

  protected readonly chartDescription = computed(() => {
    const o = this.overview();
    if (o === null) {
      return '';
    }
    const usual =
      o.usualMonth === null ? '' : `, di solito ${this.euros(o.usualMonth, o.currency)} al mese`;
    return `${o.name} negli ultimi 12 mesi${usual}; ${this.monthName()} ${this.euros(o.amount, o.currency)}`;
  });

  protected readonly averageLabel = computed(() => {
    const o = this.overview();
    return o?.usualMonth == null ? '' : `di solito ${this.euros(o.usualMonth, o.currency)}`;
  });

  ngOnInit(): void {
    const month = this.route.snapshot.queryParamMap.get('mese');
    this.route.paramMap.subscribe((params) => {
      this.api
        .categoryOverview(
          Number(params.get('id')),
          month && /^\d{4}-\d{2}$/.test(month) ? month : null,
        )
        .subscribe({
          next: (overview) => this.overview.set(overview),
          error: (response) =>
            this.error.set(
              response.status === 404
                ? 'Questa categoria non esiste più.'
                : 'Impossibile caricare la categoria: il backend è avviato?',
            ),
        });
    });
  }

  private euros(value: number, currency: string): string {
    // Con la modalità discreta, le cifre nascoste (docs/42): anche nelle frasi costruite qui.
    return this.privacy.hide(
      formatCurrency(value, this.locale, currency === 'EUR' ? '€' : currency, currency, '1.0-0'),
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
