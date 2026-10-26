import { NgTemplateOutlet, formatCurrency } from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import {
  CategoryKind,
  ExploreGroup,
  ExploreGroups,
  ExplorePeriod,
  ExploreSeries,
  GroupBy,
  Metric,
  Transaction,
} from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MoneyPipe } from '../privacy/money.pipe';
import { PrivacyStore } from '../privacy/privacy-store';
import { ReadablePipe } from '../text/readable';
import { avatarHue, groupByDay, initialOf, nameOf } from '../transactions/day-groups';
import { BarChart } from './bar-chart';
import { barLabel, barName, lastPastBar, monthOfDay, perUnit } from './explore-labels';

/** Sotto questa differenza (in euro) una categoria è "come al solito", come nel Riepilogo. */
const SAME_AS_USUAL = 5;

// Le scelte, con le parole che usa la pagina e quelle dell'indirizzo (/analisi?cosa=entrate&periodo=anno).
export const METRICS: { value: Metric; label: string; slug: string }[] = [
  { value: 'EXPENSES', label: 'Spese', slug: 'spese' },
  { value: 'INCOME', label: 'Entrate', slug: 'entrate' },
  { value: 'NET', label: 'Netto', slug: 'netto' },
];
export const PERIODS: { value: ExplorePeriod; label: string; slug: string }[] = [
  { value: 'WEEK', label: 'Settimana', slug: 'settimana' },
  { value: 'MONTH', label: 'Mese', slug: 'mese' },
  { value: 'SIX_MONTHS', label: '6 mesi', slug: '6mesi' },
  { value: 'YEAR', label: 'Anno', slug: 'anno' },
];
export const GROUPINGS: { value: GroupBy; label: string; slug: string }[] = [
  { value: 'CATEGORY', label: 'Categoria', slug: 'categoria' },
  { value: 'MERCHANT', label: 'Esercente', slug: 'esercente' },
  { value: 'ACCOUNT', label: 'Conto', slug: 'conto' },
];

/** Una riga dell'elenco, già messa in parole. */
interface Row {
  group: ExploreGroup;
  name: string;
  share: string; // "32%"
  note: string; // "+32 € sul solito", "4 movimenti"
  width: number; // la barra, in percentuale della più alta
}

/**
 * Analisi (docs/38): l'esploratore. Che cosa (spese, entrate, netto) × periodo (settimana, mese, 6 mesi, anno) ×
 * raggruppamento (categoria, esercente, conto). Si tocca una barra per guardare quel periodo, una categoria per
 * guardare solo lei (con i suoi movimenti, giorno per giorno). Le scelte stanno nell'indirizzo: ricaricando la pagina
 * o tornando indietro si ritrova la stessa vista.
 */
@Component({
  selector: 'app-explore-page',
  imports: [BarChart, MoneyPipe, NgTemplateOutlet, ReadablePipe, RouterLink],
  templateUrl: './explore-page.html',
})
export class ExplorePage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly locale = inject(LOCALE_ID);
  private readonly privacy = inject(PrivacyStore);

  protected readonly metrics = METRICS;
  protected readonly periods = PERIODS;
  protected readonly groupings = GROUPINGS;
  protected readonly nameOf = nameOf;
  protected readonly initialOf = initialOf;
  protected readonly avatarHue = avatarHue;

  protected readonly metric = signal<Metric>('EXPENSES');
  protected readonly period = signal<ExplorePeriod>('YEAR');
  protected readonly by = signal<GroupBy>('CATEGORY');
  protected readonly category = signal<{ id: number; name: string } | null>(null);
  /** Il tipo di ogni categoria (spesa, entrata...): una categoria di spesa non ha senso tra le entrate. */
  private readonly kinds = signal(new Map<number, CategoryKind>());
  protected readonly currency = signal<string | null>(null);
  /** La barra scelta; null = l'ultima già successa. */
  protected readonly selected = signal<number | null>(null);

  protected readonly series = signal<ExploreSeries | null>(null);
  protected readonly groups = signal<ExploreGroups | null>(null);
  protected readonly movements = signal<Transaction[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(true);
  /** I gruppi della barra stanno arrivando: l'elenco di prima è attenuato e non si tocca. */
  protected readonly groupsLoading = signal(false);

  private lastSeries = 0;
  private lastGroups = 0;

  /** La barra guardata. */
  protected readonly bar = computed(() => {
    const s = this.series();
    if (s === null) {
      return null;
    }
    const index = this.selected() ?? lastPastBar(s.bars);
    return { index, bar: s.bars[index] };
  });

  protected readonly barValues = computed(() => this.series()?.bars.map((b) => b.value) ?? []);
  protected readonly barFuture = computed(() => this.series()?.bars.map((b) => b.future) ?? []);

  protected readonly labels = computed(() => {
    const s = this.series();
    return s === null ? [] : s.bars.map((b) => barLabel(b, s.unit, s.period));
  });

  protected readonly barNames = computed(() => {
    const s = this.series();
    return s === null
      ? []
      : s.bars.map(
          (b) => `${barName(b, s.unit)}: ${this.euros(b.value, s.currency, s.metric === 'NET')}`,
        );
  });

  protected readonly metricLabel = computed(
    () => METRICS.find((m) => m.value === this.metric())?.label ?? '',
  );

  /** Sopra la cifra: "Spese · Bar e ristoranti · ottobre 2026". */
  protected readonly headLabel = computed(() => {
    const s = this.series();
    const b = this.bar();
    if (s === null || b === null) {
      return '';
    }
    const parts = [this.metricLabel()];
    if (this.category()) {
      parts.push(this.category()!.name);
    }
    parts.push(barName(b.bar, s.unit));
    return parts.join(' · ');
  });

  protected readonly headAmount = computed(() => {
    const s = this.series();
    const b = this.bar();
    return s === null || b === null ? '' : this.euros(b.bar.value, s.currency, s.metric === 'NET');
  });

  /** Sotto la cifra: la media di solito, o per il netto le entrate e le uscite. */
  protected readonly headNote = computed(() => {
    const s = this.series();
    const g = this.groups();
    if (s === null) {
      return '';
    }
    if (s.metric === 'NET') {
      if (g === null) {
        return '';
      }
      const sum = (list: ExploreGroup[]) => list.reduce((t, x) => t + x.amount, 0);
      return `Entrate ${this.euros(sum(g.income), s.currency)} · Uscite ${this.euros(sum(g.expenses), s.currency)}`;
    }
    return s.averagePerUnit === null
      ? ''
      : `Di solito ${this.euros(s.averagePerUnit, s.currency)} ${perUnit(s.unit)}`;
  });

  protected readonly averageLabel = computed(() => {
    const s = this.series();
    return s?.averagePerUnit == null ? '' : `media ${this.euros(s.averagePerUnit, s.currency)}`;
  });

  protected readonly chartDescription = computed(() => {
    const s = this.series();
    return s === null
      ? ''
      : `${this.metricLabel()}, ${PERIODS.find((p) => p.value === s.period)?.label}`;
  });

  /** Le righe dell'elenco (spese o entrate). */
  protected readonly rows = computed(() => this.toRows(this.groups()?.groups ?? []));
  protected readonly incomeRows = computed(() => this.toRows(this.groups()?.income ?? []));
  protected readonly expenseRows = computed(() => this.toRows(this.groups()?.expenses ?? []));
  protected readonly netSections = computed(() => [
    { title: 'Entrate', rows: this.incomeRows() },
    { title: 'Uscite', rows: this.expenseRows() },
  ]);

  /** I movimenti della categoria scelta, giorno per giorno. */
  protected readonly days = computed(() => groupByDay(this.movements(), new Date()));

  /** Una categoria si può scegliere toccandola: solo per spese o entrate, raggruppate per categoria. */
  protected readonly canPickCategory = computed(
    () => this.metric() !== 'NET' && this.by() === 'CATEGORY' && this.category() === null,
  );

  /**
   * "Senza categoria" tra le spese o le entrate (docs/43): non si può guardare come una categoria (non è una), ma
   * sono proprio i movimenti da sistemare. Il tocco apre Movimenti con quel filtro e il periodo della barra.
   */
  protected uncategorizedQuery(row: Row): Record<string, string> | null {
    const b = this.bar();
    const metric = this.metric();
    if (
      row.group.categoryId !== null ||
      !this.canPickCategory() ||
      b === null ||
      metric === 'NET'
    ) {
      return null;
    }
    return {
      uncategorized: 'true',
      type: metric === 'EXPENSES' ? 'EXPENSE' : 'INCOME',
      from: b.bar.from,
      to: b.bar.to,
    };
  }

  /** Il mese per la pagina della categoria: quello della barra, se è un mese; altrimenti quello in corso. */
  protected readonly categoryMonth = computed(() => {
    const s = this.series();
    const b = this.bar();
    return s !== null && b !== null && s.unit === 'MONTH' ? monthOfDay(b.bar.from) : null;
  });

  ngOnInit(): void {
    // Le scelte dall'indirizzo: /analisi?cosa=entrate&periodo=mese&per=esercente&categoria=7&nome=...&barra=3
    const q = this.route.snapshot.queryParamMap;
    const find = <T>(list: { value: T; slug: string }[], slug: string | null) =>
      list.find((x) => x.slug === slug)?.value;
    this.metric.set(find(METRICS, q.get('cosa')) ?? 'EXPENSES');
    this.period.set(find(PERIODS, q.get('periodo')) ?? 'YEAR');
    this.by.set(find(GROUPINGS, q.get('per')) ?? 'CATEGORY');
    const categoryId = Number(q.get('categoria'));
    if (Number.isInteger(categoryId) && categoryId > 0) {
      this.category.set({ id: categoryId, name: (q.get('nome') ?? 'Categoria').slice(0, 100) });
      if (this.by() === 'CATEGORY') {
        this.by.set('MERCHANT'); // come quando la si sceglie toccandola
      }
    }
    const bar = Number(q.get('barra'));
    if (q.has('barra') && Number.isInteger(bar) && bar >= 0) {
      this.selected.set(bar);
    }
    this.loadSeries();
    this.api.categories().subscribe({
      next: (categories) => {
        this.kinds.set(new Map(categories.map((c) => [c.id, c.kind])));
        // Un indirizzo con una categoria di spesa e "entrate" (scritto a mano, o vecchio): la si toglie.
        if (this.dropMismatchedCategory()) {
          this.loadSeries();
        }
      },
    });
  }

  chooseMetric(metric: Metric): void {
    this.metric.set(metric);
    this.dropMismatchedCategory();
    this.loadSeries();
  }

  /**
   * Toglie la categoria scelta se non va con quello che si guarda (docs/43): una categoria di spesa tra le entrate
   * darebbe sempre 0 €, e i suoi movimenti (spese) sotto il titolo "Entrate". Nel netto le categorie non si scelgono.
   * Restituisce true se l'ha tolta.
   */
  private dropMismatchedCategory(): boolean {
    const category = this.category();
    if (category === null) {
      return false;
    }
    const metric = this.metric();
    const kind = this.kinds().get(category.id);
    const expected = metric === 'EXPENSES' ? 'EXPENSE' : metric === 'INCOME' ? 'INCOME' : null;
    if (metric !== 'NET' && (kind === undefined || kind === expected)) {
      return false; // va bene (o non sappiamo ancora il tipo: lo controlliamo quando arriva)
    }
    this.category.set(null);
    if (this.by() === 'MERCHANT') {
      this.by.set('CATEGORY'); // si torna all'elenco delle categorie, come con la ✕
    }
    return true;
  }

  choosePeriod(period: ExplorePeriod): void {
    this.period.set(period);
    this.selected.set(null); // nel periodo nuovo si riparte dall'ultima barra
    this.loadSeries();
  }

  chooseBy(by: GroupBy): void {
    this.by.set(by);
    this.loadGroups();
  }

  pickBar(index: number): void {
    this.selected.set(index);
    this.loadGroups();
  }

  pickCategory(row: Row): void {
    if (!this.canPickCategory() || row.group.categoryId === null) {
      return;
    }
    this.category.set({ id: row.group.categoryId, name: row.name });
    this.by.set('MERCHANT'); // dentro una categoria, raggruppare per categoria non direbbe niente
    this.loadSeries();
  }

  clearCategory(): void {
    this.category.set(null);
    this.by.set('CATEGORY');
    this.loadSeries();
  }

  onCurrency(event: Event): void {
    this.currency.set((event.target as HTMLSelectElement).value);
    this.loadSeries();
  }

  private loadSeries(): void {
    this.remember();
    this.loading.set(true);
    this.error.set(null);
    const request = ++this.lastSeries;
    this.api
      .exploreSeries({
        metric: this.metric(),
        period: this.period(),
        categoryId: this.category()?.id ?? null,
        currency: this.currency(),
      })
      .subscribe({
        next: (series) => {
          if (request !== this.lastSeries) {
            return;
          }
          const selected = this.selected();
          if (
            selected !== null &&
            (selected >= series.bars.length || series.bars[selected].future)
          ) {
            this.selected.set(null);
          }
          this.series.set(series);
          this.loading.set(false);
          this.loadGroups();
        },
        error: (response) => {
          if (request === this.lastSeries) {
            this.error.set(
              response.status === 400
                ? (response.error?.detail ?? 'Richiesta non valida.')
                : "Impossibile caricare l'analisi: il backend è avviato?",
            );
            this.loading.set(false);
          }
        },
      });
  }

  private loadGroups(): void {
    this.remember();
    const s = this.series();
    const b = this.bar();
    if (s === null || b === null) {
      return;
    }
    const request = ++this.lastGroups;
    const categoryId = this.category()?.id ?? null;
    this.groupsLoading.set(true);
    this.api
      .exploreGroups({
        metric: this.metric(),
        from: b.bar.from,
        to: b.bar.to,
        by: this.by(),
        categoryId,
        currency: s.currency,
      })
      .subscribe({
        next: (groups) => {
          if (request === this.lastGroups) {
            this.groups.set(groups);
            this.groupsLoading.set(false);
          }
        },
        error: (response) => {
          if (request === this.lastGroups) {
            // Niente elenco "vecchio" sotto un titolo nuovo: si toglie, e si dice perché.
            this.groups.set(null);
            this.groupsLoading.set(false);
            this.error.set(
              response.error?.detail ?? 'Impossibile caricare i movimenti del periodo.',
            );
          }
        },
      });
    // Con una categoria scelta, anche i suoi movimenti della barra (al massimo 200: un mese ne ha molti meno).
    this.movements.set([]);
    if (categoryId !== null) {
      this.api
        .transactions({
          accountId: null,
          type: null,
          uncategorized: false,
          from: b.bar.from,
          to: b.bar.to,
          text: '',
          categoryId,
          page: 0,
          size: 200,
        })
        .subscribe({
          next: (page) => {
            if (request === this.lastGroups) {
              this.movements.set(page.items);
            }
          },
        });
    }
  }

  /** Le scelte nell'indirizzo, senza aggiungere un passo alla cronologia (replaceUrl). */
  private remember(): void {
    const slug = <T>(list: { value: T; slug: string }[], value: T) =>
      list.find((x) => x.value === value)?.slug;
    const category = this.category();
    this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: {
        cosa: slug(METRICS, this.metric()),
        periodo: slug(PERIODS, this.period()),
        per: slug(GROUPINGS, this.by()),
        categoria: category?.id ?? null,
        nome: category?.name ?? null,
        barra: this.selected(),
      },
    });
  }

  private toRows(groups: ExploreGroup[]): Row[] {
    const currency = this.series()?.currency ?? 'EUR';
    const total = groups.reduce((t, g) => t + Math.max(0, g.amount), 0);
    const highest = Math.max(1, ...groups.map((g) => g.amount));
    return groups.map((group) => ({
      group,
      name: group.name ?? (this.by() === 'CATEGORY' ? 'Senza categoria' : 'Senza descrizione'),
      share: total > 0 ? `${Math.round((Math.max(0, group.amount) / total) * 100)}%` : '',
      note: this.privacy.hide(groupNote(group, (v) => this.euros(v, currency))), // anche il segno (docs/42)
      width: Math.max(0, (group.amount / highest) * 100),
    }));
  }

  /** Un importo senza decimali: "1.284 €"; con `signed`, anche il segno più ("+829 €"). */
  private euros(value: number, currency: string, signed = false): string {
    const text = formatCurrency(
      Math.abs(value),
      this.locale,
      currency === 'EUR' ? '€' : currency,
      currency,
      '1.0-0',
    );
    const sign = value < 0 ? '−' : signed && value > 0 ? '+' : '';
    // Con la modalità discreta, le cifre (e il segno) nascoste (docs/42).
    return this.privacy.hide(sign + text);
  }
}

/** La riga sotto un gruppo: la differenza dal solito, o quanti movimenti. Esportata per i test. */
export function groupNote(group: ExploreGroup, euros: (v: number) => string): string {
  const count = group.movements === 1 ? '1 movimento' : `${group.movements} movimenti`;
  if (group.usual === null) {
    return count;
  }
  const difference = group.amount - group.usual;
  if (Math.abs(difference) < SAME_AS_USUAL) {
    return `${count} · come al solito`;
  }
  return `${count} · ${difference > 0 ? '+' : '−'}${euros(Math.abs(difference))} sul solito`;
}
