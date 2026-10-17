import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { KINDS, KIND_LABELS, categoryGroups } from '../api/category-groups';
import { Category, CategoryKind } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * L'elenco delle categorie: si parte da quello proposto dal progetto, e ognuno lo adatta a sé.
 *
 * - Rinominare una categoria aggiorna da solo movimenti, regole e budget (lo fa il database).
 * - Eliminarla lascia movimenti e regole senza categoria; per questo chiediamo conferma.
 */
@Component({
  selector: 'app-categories-page',
  imports: [ReactiveFormsModule],
  templateUrl: './categories-page.html',
})
export class CategoriesPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly kinds = KINDS;
  protected readonly kindLabels = KIND_LABELS;

  protected readonly categories = signal<Category[]>([]);
  /** Le categorie divise per gruppo, nell'ordine dei menu. */
  protected readonly groups = computed(() => categoryGroups(this.categories(), 'EXPENSE'));
  protected readonly renaming = signal<number | null>(null); // la categoria che si sta rinominando
  protected readonly confirmingDelete = signal<number | null>(null); // quella da eliminare, in attesa di conferma
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    kind: ['EXPENSE' as CategoryKind],
  });

  ngOnInit(): void {
    this.load();
  }

  add(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, kind } = this.form.getRawValue();
    this.api.createCategory({ name: name.trim(), kind }).subscribe({
      next: (category) => {
        this.done(`Categoria "${category.name}" aggiunta.`);
        this.form.reset({ name: '', kind });
      },
      error: (response) => this.fail(response),
    });
  }

  /** Salva il nuovo nome (stesso gruppo). Un nome vuoto o uguale non fa niente. */
  rename(category: Category, value: string): void {
    const name = value.trim();
    if (name === '' || name === category.name) {
      this.renaming.set(null);
      return;
    }
    this.api.updateCategory(category.id, { name, kind: category.kind }).subscribe({
      next: () => {
        this.renaming.set(null);
        this.done(
          `"${category.name}" ora si chiama "${name}": movimenti, regole e budget sono aggiornati.`,
        );
      },
      error: (response) => this.fail(response),
    });
  }

  /** Sposta una categoria in un altro gruppo. */
  changeKind(category: Category, kind: CategoryKind): void {
    this.api.updateCategory(category.id, { name: category.name, kind }).subscribe({
      next: () => this.done(`"${category.name}" spostata in "${KIND_LABELS[kind]}".`),
      error: (response) => this.fail(response),
    });
  }

  remove(category: Category): void {
    this.api.deleteCategory(category.id).subscribe({
      next: () => {
        this.confirmingDelete.set(null);
        this.done(`Categoria "${category.name}" eliminata.`);
      },
      error: (response) => {
        this.confirmingDelete.set(null);
        this.fail(response);
      },
    });
  }

  protected showNameError(): boolean {
    return this.form.controls.name.invalid && this.form.controls.name.touched;
  }

  private done(message: string): void {
    this.message.set(message);
    this.error.set(null);
    this.load();
  }

  private fail(response: { error?: { detail?: string } }): void {
    this.message.set(null);
    this.error.set(response.error?.detail ?? 'Operazione non riuscita.');
  }

  private load(): void {
    this.api.categories().subscribe({
      next: (categories) => this.categories.set(categories),
      error: () => this.error.set('Impossibile caricare le categorie: il backend è avviato?'),
    });
  }
}
