import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, input, output, signal } from '@angular/core';

import { Account, Transaction } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { MoneyPipe } from '../privacy/money.pipe';
import { nameOf } from '../transactions/day-groups';

/**
 * Scrivere il saldo di un conto (docs/saldi.md, parti C ed E): un conto manuale, o un collegato di cui la banca non dà
 * il saldo. Mostra l'ultimo movimento nell'app: l'utente apre
 * l'app della banca, controlla che quel movimento ci sia, e scrive il saldo che vede **dopo** di lui. Da lì il saldo si
 * aggiorna da solo con i movimenti importati dopo.
 *
 * Si usa nella pagina Conti e, dopo un'importazione, nella pagina Importa.
 */
@Component({
  selector: 'app-balance-form',
  imports: [DatePipe, MoneyPipe],
  template: `
    <div class="balance-form">
      @if (loaded()) {
        @if (last(); as t) {
          <p class="hint">
            Apri l'app della banca e cerca l'ultimo movimento che c'è qui:
            <strong
              >{{ t.bookingDate | date: 'd MMMM y' }} · {{ nameOf(t) }} ·
              {{ t.amount | money: t.currency }}</strong
            >. Scrivi il saldo che la banca mostra <strong>dopo</strong> quel movimento.
          </p>
        } @else {
          <p class="hint">Il conto non ha ancora movimenti: scrivi il saldo di oggi.</p>
        }
        <div class="balance-row">
          <label>
            Saldo ({{ account().currency }})
            <input
              #amount
              inputmode="decimal"
              autocomplete="off"
              placeholder="es. 1.234,56"
              [value]="initial()"
              (keydown.enter)="save(amount.value)"
              (keydown.escape)="cancelled.emit()"
            />
          </label>
          <button type="button" [disabled]="saving()" (click)="save(amount.value)">
            Salva il saldo
          </button>
          <button type="button" class="link" (click)="cancelled.emit()">annulla</button>
        </div>
        @if (error()) {
          <p class="error">{{ error() }}</p>
        }
      } @else {
        <p class="hint">Caricamento...</p>
      }
    </div>
  `,
})
export class BalanceForm implements OnInit {
  private readonly api = inject(WalletApi);

  readonly account = input.required<Account>();
  /** Il conto con il saldo nuovo. */
  readonly saved = output<Account>();
  readonly cancelled = output<void>();

  protected readonly nameOf = nameOf;
  protected readonly last = signal<Transaction | null>(null);
  protected readonly loaded = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api
      .transactions({
        accountId: this.account().id,
        type: null,
        uncategorized: false,
        from: null,
        to: null,
        text: '',
        page: 0,
        size: 1,
      })
      .subscribe({
        next: (page) => {
          this.last.set(page.items[0] ?? null);
          this.loaded.set(true);
        },
        error: () => this.loaded.set(true),
      });
  }

  /** Il saldo di oggi nel campo, se c'è già ("1234,56"): così si corregge invece di riscriverlo. */
  protected initial(): string {
    const amount = this.account().balance?.amount;
    return amount === undefined ? '' : amount.toFixed(2).replace('.', ',');
  }

  save(text: string): void {
    const value = parseBalance(text);
    if (value === null) {
      this.error.set('Scrivi un importo, come 1.234,56 (con il meno davanti se è negativo).');
      return;
    }
    this.error.set(null);
    this.saving.set(true);
    this.api.changeBalance(this.account().id, value).subscribe({
      next: (account) => {
        this.saving.set(false);
        this.saved.emit(account);
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Impossibile salvare il saldo: il backend è avviato?');
      },
    });
  }
}

/**
 * Un importo scritto all'italiana o all'inglese: "1.234,56", "1234,56", "1234.56", "-12,5", "€ 300". Un punto seguito
 * da esattamente tre cifre, senza virgola, separa le migliaia ("1.234" è milleduecentotrentaquattro). Null se non è un
 * importo, o se ha più di due decimali.
 */
export function parseBalance(text: string): number | null {
  let s = text.replace(/[\s€]/g, '');
  if (s.includes(',')) {
    s = s.replace(/\./g, '').replace(',', '.');
  } else if (/^-?\d{1,3}(\.\d{3})+$/.test(s)) {
    s = s.replace(/\./g, '');
  }
  if (!/^-?\d+(\.\d{1,2})?$/.test(s)) {
    return null;
  }
  return Number(s);
}
