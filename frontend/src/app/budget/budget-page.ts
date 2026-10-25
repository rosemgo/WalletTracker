import { CurrencyPipe, formatCurrency, formatDate } from '@angular/common';
import { Component, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Budget, BudgetStatus, Category } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Come si presenta ogni stato: un'icona e un'etichetta, mai solo il colore. */
const STATUS: Record<BudgetStatus, { icon: string; label: string }> = {
  OK: { icon: '✓', label: 'Nei limiti' },
  WARNING: { icon: '!', label: 'Quasi esaurito' },
  OVER: { icon: '✕', label: 'Superato' },
};

/** L'ordine delle schede: prima quello che chiede attenzione (superati, poi quasi esauriti). */
const URGENCY: Record<BudgetStatus, number> = { OVER: 0, WARNING: 1, OK: 2 };

/** Sotto questa differenza (in euro) si è "in linea con il ritmo". */
const SAME_PACE = 5;

/**
 * I budget mensili (docs/39): per ogni categoria, quanto hai speso nel mese rispetto al budget e al **ritmo** (quanto
 * avresti speso finora spendendo in modo regolare). In cima il totale; le schede da guardare per prime vengono prima.
 *
 * Il modulo per creare o modificare un budget usa i Reactive Forms, come la pagina delle regole.
 */
@Component({
  selector: 'app-budget-page',
  imports: [CurrencyPipe, ReactiveFormsModule, RouterLink],
  templateUrl: './budget-page.html',
})
export class BudgetPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly locale = inject(LOCALE_ID);

  protected readonly statusInfo = STATUS;
  protected readonly Math = Math; // per Math.min nel template
  protected readonly thisMonth = monthOf(new Date());

  protected readonly month = signal(this.thisMonth);
  protected readonly budgets = signal<Budget[]>([]);
  protected readonly categories = signal<Category[]>([]);
  /** Le categorie di spesa: le uniche che hanno senso per un budget. */
  protected readonly expenseCategories = computed(() =>
    this.categories().filter((c) => c.kind === 'EXPENSE'),
  );
  protected readonly currencies = signal<string[]>(['EUR']); // le valute dei conti
  protected readonly editing = signal<Budget | null>(null);
  protected readonly loading = signal(true);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  /** Il mese in corso è quello mostrato? Allora ha senso il segno "oggi" sulle barre. */
  protected readonly isCurrentMonth = computed(() => this.month() === this.thisMonth);

  /** "Ottobre 2026". */
  protected readonly monthTitle = computed(() => {
    const text = formatDate(`${this.month()}-01`, 'MMMM y', this.locale);
    return text.charAt(0).toUpperCase() + text.slice(1);
  });

  /** Le schede in ordine: superati, quasi esauriti, nei limiti; a parità, per nome. */
  protected readonly sorted = computed(() =>
    [...this.budgets()].sort(
      (a, b) => URGENCY[a.status] - URGENCY[b.status] || a.category.localeCompare(b.category),
    ),
  );

  /**
   * Il totale di tutti i budget del mese, se sono nella stessa valuta (sommare euro e dollari non avrebbe senso):
   * speso, budget, e dove dovresti essere oggi.
   */
  protected readonly total = computed(() => {
    const budgets = this.budgets();
    if (budgets.length === 0 || new Set(budgets.map((b) => b.currency)).size > 1) {
      return null;
    }
    const sum = (pick: (b: Budget) => number) => budgets.reduce((t, b) => t + pick(b), 0);
    const total = {
      currency: budgets[0].currency,
      spent: sum((b) => b.spent),
      monthlyAmount: sum((b) => b.monthlyAmount),
      expectedSoFar: sum((b) => b.expectedSoFar),
      kept: budgets.filter((b) => b.status !== 'OVER').length,
      count: budgets.length,
    };
    return {
      ...total,
      sentence: this.isCurrentMonth()
        ? paceSentence(total.spent, total.expectedSoFar, (v) => this.euros(v, total.currency))
        : `Rispettati ${total.kept} su ${total.count}.`,
    };
  });

  /**
   * Il modulo. L'importo accetta al massimo due decimali, come i centesimi: lo stesso vincolo del backend
   * (BudgetRequest.java), ripetuto qui per avvisare subito.
   */
  protected readonly form = this.fb.group({
    categoryId: [null as number | null, [Validators.required]], // l'id della categoria
    monthlyAmount: [
      null as number | null,
      [
        Validators.required,
        Validators.min(0.01),
        Validators.max(1_000_000_000),
        Validators.pattern(/^\d+([.,]\d{1,2})?$/),
      ],
    ],
    currency: ['EUR'],
  });

  ngOnInit(): void {
    this.load();
    this.api.categories().subscribe({ next: (categories) => this.categories.set(categories) });
    this.api.accounts().subscribe({
      next: (accounts) => {
        const currencies = [...new Set(accounts.map((a) => a.currency))].sort();
        if (currencies.length > 0) {
          this.currencies.set(currencies);
          this.form.controls.currency.setValue(currencies.includes('EUR') ? 'EUR' : currencies[0]);
        }
      },
    });
  }

  /** "‹" e "›": il mese prima o dopo. Oltre il mese in corso non si va: non ci sono ancora spese. */
  changeMonth(delta: number): void {
    this.month.set(addMonths(this.month(), delta));
    this.load();
  }

  /** La lunghezza della barra: la parte del budget già spesa, al massimo il 100%. */
  protected barPercent(budget: Budget): number {
    return Math.min(Math.max(budget.spent / budget.monthlyAmount, 0), 1) * 100;
  }

  /** Dove mettere il segno "oggi": quanto avresti speso finora spendendo in modo regolare. */
  protected expectedPercent(budget: Budget): number {
    return Math.min(budget.expectedSoFar / budget.monthlyAmount, 1) * 100;
  }

  /** La frase del ritmo di un budget, o per un mese passato la media dei tre mesi prima. */
  protected budgetNote(budget: Budget): string {
    const euros = (v: number) => this.euros(v, budget.currency);
    return this.isCurrentMonth()
      ? paceSentence(budget.spent, budget.expectedSoFar, euros)
      : `Nei tre mesi prima, in media ${euros(budget.averageLast3Months)}.`;
  }

  edit(budget: Budget): void {
    this.editing.set(budget);
    this.message.set(null);
    this.form.setValue({
      categoryId: budget.categoryId,
      monthlyAmount: budget.monthlyAmount,
      currency: budget.currency,
    });
    // Il modulo è in fondo alla pagina: sul telefono lo portiamo in vista (se il browser sa farlo).
    document.getElementById('budget-form')?.scrollIntoView?.({ behavior: 'smooth' });
  }

  cancel(): void {
    this.editing.set(null);
    this.form.reset({
      categoryId: null,
      monthlyAmount: null,
      currency: this.form.controls.currency.value,
    });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { categoryId, monthlyAmount, currency } = this.form.getRawValue();
    // Chi scrive "12,50" (con la virgola italiana) intende 12.50.
    const request = {
      categoryId: categoryId as number, // il validatore "required" garantisce che c'è
      currency,
      monthlyAmount: Number(String(monthlyAmount).replace(',', '.')),
    };
    const editing = this.editing();
    const saved$ = editing
      ? this.api.updateBudget(editing.id, request)
      : this.api.createBudget(request);

    this.error.set(null);
    saved$.subscribe({
      next: (budget) => {
        this.message.set(`Budget "${budget.category}" salvato.`);
        this.cancel();
        this.load();
      },
      error: (response) =>
        this.error.set(response.error?.detail ?? 'Il budget non è stato salvato.'),
    });
  }

  remove(budget: Budget): void {
    this.api.deleteBudget(budget.id).subscribe({
      next: () => {
        this.message.set(`Budget "${budget.category}" eliminato.`);
        this.load();
      },
      error: () => this.error.set('Il budget non è stato eliminato.'),
    });
  }

  protected showError(field: 'categoryId' | 'monthlyAmount'): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }

  private euros(value: number, currency: string): string {
    return formatCurrency(
      value,
      this.locale,
      currency === 'EUR' ? '€' : currency,
      currency,
      '1.0-0',
    );
  }

  private load(): void {
    this.loading.set(true);
    this.api.budgets(this.month()).subscribe({
      next: (budgets) => {
        this.budgets.set(budgets);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossibile caricare i budget: il backend è avviato?');
        this.loading.set(false);
      },
    });
  }
}

/** Il mese di una data, nel formato "2026-10". */
export function monthOf(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/** Il mese `delta` mesi dopo (o prima, se negativo): addMonths("2026-01", -1) = "2025-12". */
export function addMonths(month: string, delta: number): string {
  const [year, monthNumber] = month.split('-').map(Number);
  return monthOf(new Date(year, monthNumber - 1 + delta, 1));
}

/**
 * Il ritmo in parole: quanto hai speso rispetto a quanto avresti speso finora spendendo in modo regolare. Esportata
 * per i test.
 */
export function paceSentence(
  spent: number,
  expected: number,
  euros: (v: number) => string,
): string {
  const difference = spent - expected;
  if (Math.abs(difference) < SAME_PACE) {
    return 'In linea con il ritmo di oggi.';
  }
  return difference > 0
    ? `${euros(difference)} sopra il ritmo di oggi: rallenta per restare nel budget.`
    : `${euros(-difference)} sotto il ritmo di oggi.`;
}
