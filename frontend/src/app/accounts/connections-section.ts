import { Component, LOCALE_ID, OnInit, inject, output, signal } from '@angular/core';

import { BankConnection, LinkedAccount } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { formatLocalDate } from '../text/dates';
import { plural } from '../text/plural';

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
    <!-- Chiusa di serie: serve di rado, e la pagina resta quella dei conti. Si apre con un clic. -->
    <details class="card connections" data-testid="connections">
      <summary>
        <h2>Collegamenti alle banche <span class="chevron" aria-hidden="true">›</span></h2>
        <span class="secondary">
          {{ connectionsLabel() }} · vedi quali conti hanno, o rimuovine uno
        </span>
      </summary>
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
        <ul class="connection-list">
          @for (c of connections(); track c.id) {
            <li>
              <div class="connection-head">
                <span>
                  <strong>{{ c.bank }}</strong>
                  <span class="secondary"> · {{ statusOf(c) }}</span>
                </span>
              </div>
              @if (c.accountList.length > 0) {
                <ul class="linked-accounts">
                  @for (a of c.accountList; track $index) {
                    <li>{{ accountLabel(a) }}</li>
                  }
                </ul>
              } @else {
                <p class="secondary no-accounts" data-testid="no-accounts">
                  Nessun conto: Enable Banking non ne ha passato nessuno (la banca non era nella
                  lista della tua applicazione). Puoi rimuoverlo.
                </p>
              }
              @if (!c.removedAt && confirming() !== c.id) {
                <button type="button" class="link danger" (click)="confirming.set(c.id)">
                  Rimuovi collegamento
                </button>
              }
              @if (!c.removedAt && confirming() === c.id) {
                <div class="confirm" data-testid="confirm-removal">
                  <p>
                    @if (c.accounts > 0) {
                      L'app smetterà di leggere
                      <ng-container i18n>{c.accounts, plural,
                        =1 {il conto}
                        other {tutti i conti}
                      }</ng-container>
                      di
                      {{ c.bank }} (<strong>{{ accountsText(c) }}</strong
                      >) e chiuderà il consenso su Enable Banking.
                      <strong i18n>{c.accounts, plural,
                        =1 {Il conto e i movimenti restano}
                        other {I conti e i movimenti restano}
                      }</strong>
                      come storia. Se un giorno ricolleghi la banca, tornano gli stessi, senza
                      doppioni.
                    } @else {
                      Il collegamento non ha conti: si cancella, e il consenso su Enable Banking si
                      chiude.
                    }
                  </p>
                  @if (c.accounts > 1) {
                    <p class="hint" data-testid="exclude-hint">
                      Vuoi smettere di leggere un solo conto? Non serve rimuovere il collegamento:
                      dagli il ruolo <strong>Escluso</strong> nella tabella qui sopra.
                    </p>
                  }
                  <button type="button" class="danger" [disabled]="removing()" (click)="remove(c)">
                    {{ removing() ? 'Rimozione...' : 'Rimuovi' }}
                  </button>
                  <button type="button" class="link" (click)="confirming.set(null)">Annulla</button>
                </div>
              }
            </li>
          }
        </ul>
      }
    </details>
  `,
  styles: `
    /* Staccata dalla tabella dei conti, sopra, e dal modulo del conto manuale, sotto. */
    .connections {
      display: block;
      margin: 24px 0;
    }
    summary {
      cursor: pointer;
      list-style: none; /* niente triangolo del browser: la freccia è la nostra, dentro la scheda */
    }
    summary::-webkit-details-marker {
      display: none;
    }
    summary h2 {
      margin: 0;
    }
    .chevron {
      display: inline-block;
      margin-left: 4px;
      color: var(--acc);
      transition: transform 0.15s;
    }
    .connections[open] .chevron {
      transform: rotate(90deg);
    }
    summary .secondary {
      display: block;
      margin-top: 4px;
      color: var(--acc);
    }
    .connections[open] summary {
      margin-bottom: 16px;
    }
    .connection-list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .connection-list > li {
      padding: 12px 0;
      border-top: 1px solid var(--line);
    }
    .connection-head {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: baseline;
      gap: 8px;
    }
    .linked-accounts {
      margin: 6px 0 0;
      padding-left: 1.25rem;
    }
    .no-accounts {
      margin: 6px 0 0;
    }
    .connection-list > li > button {
      margin-top: 6px;
    }
    .confirm {
      margin-top: 10px;
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
  private readonly locale = inject(LOCALE_ID);

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

  /** "7 collegamenti", per il titolo della sezione chiusa. */
  protected connectionsLabel(): string {
    const n = this.connections().length;
    return plural(n, { one: '1 collegamento', other: `${n} collegamenti` });
  }

  /** "Credit Lombard ··0033", o solo il nome se il conto non ha IBAN (una carta). */
  protected accountLabel(a: LinkedAccount): string {
    return a.ibanEnd ? `${a.name} ··${a.ibanEnd}` : a.name;
  }

  /** I conti di un collegamento, per la conferma: "Conto corrente ··0001, Conto deposito ··0002". */
  protected accountsText(c: BankConnection): string {
    return c.accountList.map((a) => this.accountLabel(a)).join(', ');
  }

  /** "valido fino al 10/05/2027", "consenso scaduto il …", "rimosso il …: i conti restano come storia". */
  protected statusOf(c: BankConnection): string {
    const date = (iso: string) => formatLocalDate(iso, 'numeric', this.locale);
    if (c.removedAt) {
      const kept = plural(c.accounts, { one: 'il conto resta', other: 'i conti restano' });
      return `rimosso il ${date(c.removedAt)}: ${kept} come storia`;
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
        const kept = plural(r.accountsKept, {
          one: 'Il conto resta con i suoi movimenti.',
          other: 'I conti restano con i loro movimenti.',
        });
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
