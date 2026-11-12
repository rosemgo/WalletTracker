import { Component, DestroyRef, OnInit, computed, inject, output, signal } from '@angular/core';

import { AiSuggestionsStatus, CategorySuggestion } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { ReadablePipe } from '../text/readable';

/** Ogni quanto chiedere lo stato mentre l'IA risponde. */
const POLL_MS = 2000;

/**
 * I suggerimenti di categoria "dalla tua storia", in cima alla pagina Movimenti (Fase 3, passo 7a).
 *
 * Per ogni esercente a cui hai già dato una categoria a mano, propone la stessa categoria per i suoi
 * altri movimenti ancora senza. "Accetta" la assegna a tutti in un clic; "no" non la propone più.
 * Se non ci sono suggerimenti, il pannello non mostra niente.
 *
 * `output()` è un evento del componente: quando accetti, la pagina Movimenti lo riceve
 * ((accepted)="load()") e ricarica l'elenco, perché alcuni movimenti hanno ora una categoria.
 *
 * Passo 7b.3: con l'IA accesa c'è anche "Chiedi all'IA", per gli esercenti che non hai mai scelto. Le domande
 * girano nel backend (qualche secondo l'una): il pannello chiede lo stato ogni 2 secondi finché non hanno
 * finito, poi ricarica i suggerimenti. Quelli dell'IA hanno il segno "IA" e il nome del modello.
 */
@Component({
  imports: [ReadablePipe],
  selector: 'app-suggestions-panel',
  templateUrl: './suggestions-panel.html',
})
export class SuggestionsPanel implements OnInit {
  private readonly api = inject(WalletApi);

  readonly accepted = output<void>();

  protected readonly suggestions = signal<CategorySuggestion[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  /**
   * Gli esercenti in lavorazione: i loro due pulsanti restano disattivati finché il backend risponde, così un doppio
   * clic non manda due richieste. Solo quelli: prima si disattivavano tutti, e sembravano premuti tutti insieme.
   */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());
  protected readonly ai = signal<AiSuggestionsStatus | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  /** Il riquadro si vede se ci sono suggerimenti, o se c'è qualcosa da chiedere all'IA. */
  protected readonly visible = computed(() => {
    const ai = this.ai();
    return (
      this.suggestions().length > 0 ||
      (ai !== null && ai.enabled && (ai.toAsk > 0 || ai.job?.running === true))
    );
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  ngOnInit(): void {
    this.load();
  }

  /** True se l'esercente è in lavorazione. */
  protected isBusy(suggestion: CategorySuggestion): boolean {
    return this.busy().has(suggestion.merchantKey);
  }

  accept(suggestion: CategorySuggestion): void {
    this.start(suggestion);
    this.api.acceptCategorySuggestion(suggestion.merchantKey, suggestion.categoryId).subscribe({
      next: ({ updated }) => {
        this.done(
          suggestion,
          `${suggestion.merchantName}: "${suggestion.category}" assegnata a ` +
            (updated === 1 ? '1 movimento.' : `${updated} movimenti.`) +
            ' Per i movimenti futuri puoi creare una regola dal movimento, qui sotto.',
        );
        this.accepted.emit();
      },
      error: (response) => this.fail(response, suggestion),
    });
  }

  dismiss(suggestion: CategorySuggestion): void {
    this.start(suggestion);
    this.api.dismissCategorySuggestion(suggestion.merchantKey, suggestion.categoryId).subscribe({
      next: () =>
        this.done(
          suggestion,
          `${suggestion.merchantName}: "${suggestion.category}" non verrà più suggerita.`,
        ),
      error: (response) => this.fail(response, suggestion),
    });
  }

  askAi(): void {
    this.message.set(null);
    this.error.set(null);
    this.api.askAi().subscribe({
      next: (status) => this.showAi(status),
      error: (response) => this.fail(response),
    });
  }

  private start(suggestion: CategorySuggestion): void {
    this.busy.update((keys) => new Set(keys).add(suggestion.merchantKey));
  }

  private finish(suggestion: CategorySuggestion | null): void {
    if (suggestion !== null) {
      this.busy.update((keys) => {
        const next = new Set(keys);
        next.delete(suggestion.merchantKey);
        return next;
      });
    }
  }

  private done(suggestion: CategorySuggestion, message: string): void {
    this.finish(suggestion);
    this.error.set(null);
    this.message.set(message);
    this.load();
  }

  private fail(
    response: { error?: { detail?: string } },
    suggestion: CategorySuggestion | null = null,
  ): void {
    this.finish(suggestion);
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
    this.loadAi();
  }

  private loadAi(): void {
    this.api.aiSuggestionsStatus().subscribe({ next: (status) => this.showAi(status) });
  }

  /**
   * Mostra lo stato dell'IA. Mentre risponde, lo richiede dopo 2 secondi; quando ha appena finito, ricarica i
   * suggerimenti (ci sono quelli nuovi) e dice com'è andata.
   */
  private showAi(status: AiSuggestionsStatus): void {
    const wasRunning = this.ai()?.job?.running === true;
    this.ai.set(status);
    clearTimeout(this.timer);
    const job = status.job;
    if (job?.running) {
      this.timer = setTimeout(() => this.loadAi(), POLL_MS);
    } else if (wasRunning && job && !job.error) {
      const unsure = job.done - job.found;
      this.message.set(
        `L'IA ha guardato ${job.done === 1 ? '1 esercente' : job.done + ' esercenti'}: ` +
          (job.found === 1 ? '1 suggerimento' : `${job.found} suggerimenti`) +
          (unsure > 0 ? `, per ${unsure === 1 ? '1' : unsure} non sa.` : '.'),
      );
      this.api.categorySuggestions().subscribe({ next: (list) => this.suggestions.set(list) });
    }
  }
}
