import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { Budget, BudgetStatus, Category } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** Come si presenta ogni stato: un'icona e un'etichetta, mai solo il colore. */
const STATUS: Record<BudgetStatus, { icon: string; label: string }> = {
  OK: { icon: '✓', label: 'Nei limiti' },
  WARNING: { icon: '!', label: 'Quasi esaurito' },
  OVER: { icon: '✕', label: 'Superato' },
};

/**
 * I budget mensili: per ogni categoria, quanto hai speso nel mese rispetto al budget.
 *
 * Il modulo per creare o modificare un budget usa i Reactive Forms, come la pagina delle regole.
 */
@Component({
  selector: 'app-budget-page',
  imports: [CurrencyPipe, DatePipe, ReactiveFormsModule],
  templateUrl: './budget-page.html',
})
export class BudgetPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly statusInfo = STATUS;
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

  edit(budget: Budget): void {
    this.editing.set(budget);
    this.message.set(null);
    this.form.setValue({
      categoryId: budget.categoryId,
      monthlyAmount: budget.monthlyAmount,
      currency: budget.currency,
    });
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
