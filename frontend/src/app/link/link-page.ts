import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { BrowserLocation } from '../api/browser-location';
import { Bank } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/** I paesi proposti nel menu: quelli dove si trovano più spesso i conti degli utenti italiani. */
const COUNTRIES = [
  { code: 'IT', name: 'Italia' },
  { code: 'DE', name: 'Germania' },
  { code: 'ES', name: 'Spagna' },
  { code: 'FR', name: 'Francia' },
  { code: 'NL', name: 'Paesi Bassi' },
  { code: 'BE', name: 'Belgio' },
  { code: 'AT', name: 'Austria' },
  { code: 'IE', name: 'Irlanda' },
  { code: 'LT', name: 'Lituania' },
  { code: 'LU', name: 'Lussemburgo' },
  { code: 'PT', name: 'Portogallo' },
];

/**
 * "Collega una banca": l'utente sceglie paese e banca, e il browser va sulla pagina di login della banca.
 * Al ritorno, la banca manda il browser alla pagina /callback (vedi CallbackPage).
 */
@Component({
  selector: 'app-link-page',
  templateUrl: './link-page.html',
})
export class LinkPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly location = inject(BrowserLocation);
  private readonly route = inject(ActivatedRoute);

  protected readonly countries = COUNTRIES;
  protected readonly country = signal('IT');
  protected readonly banks = signal<Bank[]>([]);
  protected readonly search = signal('');
  protected readonly loading = signal(true);
  protected readonly starting = signal<string | null>(null); // la banca che si sta collegando
  protected readonly error = signal<string | null>(null);
  /** La banca da ricollegare, se si arriva da un avviso di consenso in scadenza (/collega?bank=ING&country=IT). */
  protected readonly renewing = signal<string | null>(null);

  /** Le banche che contengono il testo cercato: si ricalcola da solo quando cambiano banks() o search(). */
  protected readonly filteredBanks = computed(() => {
    const text = this.search().trim().toLowerCase();
    return this.banks().filter((bank) => bank.name.toLowerCase().includes(text));
  });

  ngOnInit(): void {
    // Dall'avviso "consenso in scadenza": paese e banca già scelti. Il paese lo accettiamo solo se è
    // nel nostro elenco; il nome della banca diventa solo il testo della ricerca (vedi docs/19 sui
    // parametri dell'indirizzo, che chiunque può scrivere).
    const params = this.route.snapshot.queryParamMap;
    const country = params.get('country');
    const bank = params.get('bank')?.trim().slice(0, 100);
    if (country && COUNTRIES.some((c) => c.code === country)) {
      this.country.set(country);
    }
    if (bank) {
      this.search.set(bank);
      this.renewing.set(bank);
    }
    this.loadBanks();
  }

  onCountry(event: Event): void {
    this.country.set((event.target as HTMLSelectElement).value);
    this.loadBanks();
  }

  onSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  /** "Collega": chiediamo al backend l'indirizzo del login e ci mandiamo il browser. */
  connect(bank: Bank): void {
    this.starting.set(bank.name);
    this.error.set(null);
    this.api.startLink(bank.name, bank.country).subscribe({
      next: ({ url }) => this.location.go(url),
      error: (response) => {
        this.starting.set(null);
        // Il backend spiega il motivo nel campo "detail" (Problem Details, vedi docs/09).
        this.error.set(response.error?.detail ?? 'Non è stato possibile avviare il collegamento.');
      },
    });
  }

  private loadBanks(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.banks(this.country()).subscribe({
      next: (banks) => {
        this.banks.set(banks);
        this.loading.set(false);
      },
      error: (response) => {
        this.banks.set([]);
        this.loading.set(false);
        this.error.set(
          response.error?.detail ?? 'Impossibile caricare le banche: il backend è avviato?',
        );
      },
    });
  }
}
