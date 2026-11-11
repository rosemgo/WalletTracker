import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, output, signal } from '@angular/core';

import { BankConnection } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * "Collegamenti alle banche", nella pagina Conti (docs/rimuovere-un-collegamento.md): ogni collegamento, anche
 * scaduto, rimosso o senza conti, e "Rimuovi collegamento".
 *
 * Rimuovere non cancella la storia: i conti e i movimenti restano, l'app smette di leggerli, e il consenso su
 * Enable Banking si chiude. Ricollegando la banca, i conti tornano gli stessi, senza movimenti doppi. Un
 * collegamento senza conti (un tentativo in modalità ristretta) invece si cancella: non c'è niente da tenere.
 */
@Component({
  selector: 'app-connections-section',
  template: `
    <section class="card connections">
      <h2>Collegamenti alle banche</h2>
      @if (message()) {
        <p class="message" role="status">{{ message() }}</p>
      }
      @if (warning()) {
        <p class="error" data-testid="consent-not-closed">{{ warning() }}</p>
      }
      @if (error()) {
        <p class="error">{{ error() }}</p>
      }
      @if (connections().length === 0) {
        <p class="hint">Nessun collegamento: le banche si collegano da "Collega una banca".</p>
      } @else {
        <ul class="rows">
          @for (c of connections(); track c.id) {
            <li>
              <span>
                <strong>{{ c.bank }}</strong>
                <span class="secondary">
                  · {{ accountsLabel(c.accounts) }} · {{ statusOf(c) }}
                </span>
              </span>
              @if (!c.removedAt) {
                @if (confirming() === c.id) {
                  <div class="confirm" data-testid="confirm-removal">
                    <p>
                      @if (c.accounts > 0) {
                        L'app smetterà di leggere
                        {{ c.accounts === 1 ? 'il conto' : 'tutti i conti' }} di
                        {{ c.bank }} (<strong>{{ c.accountNames.join(', ') }}</strong
                        >) e chiuderà il consenso su Enable Banking.
                        <strong
                          >{{ c.accounts === 1 ? 'Il conto' : 'I conti' }} e i movimenti
                          restano</strong
                        >
                        come storia. Se un giorno ricolleghi la banca, tornano gli stessi, senza
                        doppioni.
                      } @else {
                        Il collegamento non ha conti: si cancella, e il consenso su Enable Banking
                        si chiude.
                      }
                    </p>
                    @if (c.accounts > 1) {
                      <p class="hint" data-testid="exclude-hint">
                        Vuoi smettere di leggere un solo conto? Non serve rimuovere il collegamento:
                        dagli il ruolo <strong>Escluso</strong> nella tabella qui sopra.
                      </p>
                    }
                    <button
                      type="button"
                      class="danger"
                      [disabled]="removing()"
                      (click)="remove(c)"
                    >
                      {{ removing() ? 'Rimozione...' : 'Rimuovi' }}
                    </button>
                    <button type="button" class="link" (click)="confirming.set(null)">
                      Annulla
                    </button>
                  </div>
                } @else {
                  <button type="button" class="link danger" (click)="confirming.set(c.id)">
                    Rimuovi collegamento
                  </button>
                }
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .connections .rows li {
      flex-wrap: wrap;
      gap: 8px;
    }
    .confirm {
      flex-basis: 100%;
    }
    .confirm p {
      margin: 0 0 8px;
    }
    .confirm button + button {
      margin-left: 12px;
    }
  `,
})
export class ConnectionsSection implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly datePipe = new DatePipe('it-IT');

  /** Dopo una rimozione: la pagina ricarica i conti (lo stato "collegamento rimosso"). */
  readonly changed = output<void>();

  protected readonly connections = signal<BankConnection[]>([]);
  protected readonly confirming = signal<number | null>(null);
  protected readonly removing = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly warning = signal<string | null>(null); // il consenso su Enable Banking non si è chiuso

  ngOnInit(): void {
    this.load();
  }

  protected accountsLabel(n: number): string {
    return n === 0 ? 'nessun conto' : n === 1 ? '1 conto' : `${n} conti`;
  }

  /** "valido fino al 10/05/2027", "consenso scaduto il …", "rimosso il …: i conti restano come storia". */
  protected statusOf(c: BankConnection): string {
    const date = (iso: string) => this.datePipe.transform(iso, 'dd/MM/yyyy') ?? '';
    if (c.removedAt) {
      return `rimosso il ${date(c.removedAt)}: ${c.accounts === 1 ? 'il conto resta' : 'i conti restano'} come storia`;
    }
    if (new Date(c.validUntil).getTime() <= Date.now()) {
      return `consenso scaduto il ${date(c.validUntil)}`;
    }
    return `valido fino al ${date(c.validUntil)}`;
  }

  protected remove(c: BankConnection): void {
    this.removing.set(true);
    this.message.set(null);
    this.error.set(null);
    this.warning.set(null);
    this.api.removeConnection(c.id).subscribe({
      next: (r) => {
        this.removing.set(false);
        this.confirming.set(null);
        const kept =
          r.accountsKept === 1
            ? 'Il conto resta con i suoi movimenti.'
            : 'I conti restano con i loro movimenti.';
        const what =
          r.accountsKept === 0
            ? `${r.bank}: collegamento cancellato.`
            : `${r.bank}: collegamento rimosso. ${kept}`;
        this.message.set(what);
        if (!r.revoked) {
          this.warning.set(
            'Non è stato possibile chiudere il consenso su Enable Banking: chiudilo dal suo pannello.',
          );
        }
        this.load();
        this.changed.emit();
      },
      error: () => {
        this.removing.set(false);
        this.error.set('La rimozione non è riuscita: il backend è avviato?');
      },
    });
  }

  private load(): void {
    this.api.connections().subscribe({
      next: (list) => this.connections.set(list),
      error: () => this.error.set('Impossibile caricare i collegamenti: il backend è avviato?'),
    });
  }
}
