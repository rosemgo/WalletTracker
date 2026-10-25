import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';

import { Frequency, SubscriptionOverview, SubscriptionSuggestion } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MoneyPipe } from '../privacy/money.pipe';
import { ReadablePipe, readable } from '../text/readable';

export const FREQUENCY_LABELS: Record<Frequency, string> = {
  MONTHLY: 'al mese',
  YEARLY: "all'anno",
};

/**
 * Gli abbonamenti: quelli confermati, con il costo totale, e i suggerimenti da confermare o ignorare.
 *
 * I suggerimenti li trova il backend (RecurringDetector): pagamenti allo stesso esercente, a intervalli
 * regolari, con importi simili. La decisione è sempre dell'utente.
 */
@Component({
  selector: 'app-subscriptions-page',
  imports: [DatePipe, MoneyPipe, ReadablePipe],
  templateUrl: './subscriptions-page.html',
})
export class SubscriptionsPage implements OnInit {
  private readonly api = inject(WalletApi);

  protected readonly frequencyLabels = FREQUENCY_LABELS;
  protected readonly overview = signal<SubscriptionOverview | null>(null);
  protected readonly suggestions = signal<SubscriptionSuggestion[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  /** "Sì, è un abbonamento", con il nome scritto (o lasciato) dall'utente. */
  confirm(suggestion: SubscriptionSuggestion, name: string): void {
    const chosenName = name.trim() || readable(suggestion.name);
    this.api
      .confirmSubscription({
        merchantKey: suggestion.merchantKey,
        currency: suggestion.currency,
        name: chosenName.slice(0, 100),
        frequency: suggestion.frequency,
        expectedAmount: suggestion.typicalAmount,
      })
      .subscribe({
        next: () => this.done(`"${chosenName}" aggiunto agli abbonamenti.`),
        error: (response) => this.fail(response),
      });
  }

  /**
   * "No": questa serie non verrà più suggerita. Con l'importo: lo stesso esercente può avere un altro
   * abbonamento con un importo diverso (Fastweb: SIM e linea di casa), che resta suggeribile.
   */
  dismiss(suggestion: SubscriptionSuggestion): void {
    this.api
      .dismissSubscription(suggestion.merchantKey, suggestion.currency, suggestion.typicalAmount)
      .subscribe({
        next: () => this.done(`"${readable(suggestion.name)}" non verrà più suggerito.`),
        error: (response) => this.fail(response),
      });
  }

  /** Toglie un abbonamento dall'elenco, o annulla un "non suggerire più". */
  remove(id: number, what: string): void {
    this.api.deleteSubscription(id).subscribe({
      next: () => this.done(`${what}: fatto.`),
      error: (response) => this.fail(response),
    });
  }

  private done(message: string): void {
    this.message.set(message);
    this.error.set(null);
    this.load();
  }

  private fail(response: { error?: { detail?: string } }): void {
    this.error.set(response.error?.detail ?? 'Operazione non riuscita.');
  }

  private load(): void {
    this.api.subscriptions().subscribe({
      next: (overview) => this.overview.set(overview),
      error: () => this.error.set('Impossibile caricare gli abbonamenti: il backend è avviato?'),
    });
    this.api.subscriptionSuggestions().subscribe({
      next: (suggestions) => this.suggestions.set(suggestions),
    });
  }
}
