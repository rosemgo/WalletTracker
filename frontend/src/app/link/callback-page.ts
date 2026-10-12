import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { LinkResult } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * La pagina di ritorno dopo il login sulla banca: https://localhost:8443/callback?code=...&state=...
 *
 * È l'indirizzo registrato su Enable Banking come "redirect URL". La banca ci rimanda qui il browser con:
 * - `code` e `state` se l'utente ha autorizzato l'accesso;
 * - `error` (e a volte `error_description`) se ha annullato o qualcosa è andato storto.
 *
 * La pagina passa `code` e `state` al backend, che completa il collegamento e importa i movimenti.
 */
@Component({
  selector: 'app-callback-page',
  imports: [RouterLink],
  templateUrl: './callback-page.html',
})
export class CallbackPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(WalletApi);

  protected readonly working = signal(false);
  protected readonly result = signal<LinkResult | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    // snapshot: i parametri così come sono adesso, nell'indirizzo (?code=...&state=...).
    const params = this.route.snapshot.queryParamMap;
    const code = params.get('code');
    const state = params.get('state');
    const bankError = params.get('error');

    // Togliamo i parametri dalla barra degli indirizzi (replaceUrl: senza aggiungere una voce alla
    // cronologia). Così, ricaricando la pagina, non si riprova a usare un codice già usato.
    this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });

    if (bankError) {
      this.error.set(`La banca ha risposto: ${params.get('error_description') ?? bankError}`);
      return;
    }
    if (!code || !state) {
      this.error.set('In questo indirizzo mancano i dati del collegamento.');
      return;
    }

    this.working.set(true);
    this.api.completeLink(code, state).subscribe({
      next: (result) => {
        this.result.set(result);
        this.working.set(false);
      },
      error: (response) => {
        this.error.set(response.error?.detail ?? 'Il collegamento non è riuscito.');
        this.working.set(false);
      },
    });
  }
}
