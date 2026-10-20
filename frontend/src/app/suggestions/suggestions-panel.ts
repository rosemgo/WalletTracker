import { Component, OnInit, inject, output, signal } from '@angular/core';

import { CategorySuggestion } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * I suggerimenti di categoria "dalla tua storia", in cima alla pagina Movimenti (Fase 3, passo 7a).
 *
 * Per ogni esercente a cui hai già dato una categoria a mano, propone la stessa categoria per i suoi
 * altri movimenti ancora senza. "Accetta" la assegna a tutti in un clic; "no" non la propone più.
 * Se non ci sono suggerimenti, il pannello non mostra niente.
 *
 * `output()` è un evento del componente: quando accetti, la pagina Movimenti lo riceve
 * ((accepted)="load()") e ricarica l'elenco, perché alcuni movimenti hanno ora una categoria.
 */
@Component({
  selector: 'app-suggestions-panel',
  templateUrl: './suggestions-panel.html',
})
export class SuggestionsPanel implements OnInit {
  private readonly api = inject(WalletApi);

  readonly accepted = output<void>();

  protected readonly suggestions = signal<CategorySuggestion[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null); // l'esercente in lavorazione: niente doppi clic

  ngOnInit(): void {
    this.load();
  }

  accept(suggestion: CategorySuggestion): void {
    this.busy.set(suggestion.merchantKey);
    this.api.acceptCategorySuggestion(suggestion.merchantKey, suggestion.categoryId).subscribe({
      next: ({ updated }) => {
        this.done(
          `${suggestion.merchantName}: "${suggestion.category}" assegnata a ` +
            (updated === 1 ? '1 movimento.' : `${updated} movimenti.`) +
            ' Per i movimenti futuri puoi creare una regola dal movimento, qui sotto.',
        );
        this.accepted.emit();
      },
      error: (response) => this.fail(response),
    });
  }

  dismiss(suggestion: CategorySuggestion): void {
    this.busy.set(suggestion.merchantKey);
    this.api.dismissCategorySuggestion(suggestion.merchantKey, suggestion.categoryId).subscribe({
      next: () =>
        this.done(`${suggestion.merchantName}: "${suggestion.category}" non verrà più suggerita.`),
      error: (response) => this.fail(response),
    });
  }

  private done(message: string): void {
    this.busy.set(null);
    this.error.set(null);
    this.message.set(message);
    this.load();
  }

  private fail(response: { error?: { detail?: string } }): void {
    this.busy.set(null);
    this.message.set(null);
    this.error.set(response.error?.detail ?? 'Operazione non riuscita.');
  }

  /** Ricarica i suggerimenti: la pagina Movimenti lo chiama dopo che hai scelto a mano una categoria. */
  reload(): void {
    this.load();
  }

  private load(): void {
    // Se i suggerimenti non arrivano non mostriamo un errore: la pagina segnala già se il backend non risponde.
    this.api.categorySuggestions().subscribe({ next: (list) => this.suggestions.set(list) });
  }
}
