import { Injectable, computed, inject, signal } from '@angular/core';

import { Account } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * I conti, caricati una volta e condivisi da chi mostra i movimenti (docs/loghi.md): il logo nell'angolo del cerchio
 * e il nome breve del conto. Chi cambia un conto (la pagina Conti) chiama {@link refresh}.
 */
@Injectable({ providedIn: 'root' })
export class AccountsStore {
  private readonly api = inject(WalletApi);
  private requested = false;

  readonly accounts = signal<Account[]>([]);
  private readonly byId = computed(() => new Map(this.accounts().map((a) => [a.id, a])));

  /** Carica i conti la prima volta che servono. */
  load(): void {
    if (!this.requested) {
      this.requested = true;
      this.refresh();
    }
  }

  refresh(): void {
    this.api.accounts().subscribe({
      next: (accounts) => this.accounts.set(accounts),
      error: () => (this.requested = false), // si riprova alla prossima richiesta
    });
  }

  /** L'indirizzo del logo del conto, o null se non ne ha uno. */
  logoUrl(accountId: number): string | null {
    const account = this.byId().get(accountId);
    return account?.logoVersion ? logoUrl(account) : null;
  }

  /** Il nome breve del conto (vedi {@link shortAccountName}). */
  shortName(accountId: number): string {
    const account = this.byId().get(accountId);
    return account ? shortAccountName(account, this.accounts()) : '';
  }
}

/** L'indirizzo dell'immagine: con la versione, così una nuova immagine non resta nascosta dalla cache. */
export function logoUrl(account: Account): string {
  return `/api/accounts/${account.id}/logo?v=${account.logoVersion}`;
}

/**
 * Il nome di un conto accanto a un movimento (docs/loghi.md, parte D): il nome scelto dall'utente, se c'è; altrimenti,
 * se è l'unico conto di quella banca, solo la banca ("Revolut"); se la banca ne ha più di uno, il nome per esteso
 * ("FinecoBank ··1234"), per distinguerli.
 */
export function shortAccountName(account: Account, all: Account[]): string {
  if (account.displayName) {
    return account.displayName;
  }
  if (!account.bank) {
    return account.label;
  }
  const sameBank = all.filter((a) => a.bank === account.bank).length;
  return sameBank === 1 ? account.bank : account.label;
}
