import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';

import { ROLES, ROLE_LABELS } from '../api/labels';
import { Account, AccountRole } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * La pagina dei conti: elenco dei conti collegati, con il ruolo modificabile da un menu a tendina.
 *
 * È un componente "standalone": non serve dichiararlo in un NgModule. Le cose che il template usa
 * (qui la pipe `date`) si elencano direttamente in `imports`.
 */
@Component({
  selector: 'app-accounts-page',
  imports: [DatePipe],
  templateUrl: './accounts-page.html',
})
export class AccountsPage implements OnInit {
  private readonly api = inject(WalletApi);

  // Le costanti che il template deve vedere vanno esposte come proprietà del componente.
  protected readonly roles = ROLES;
  protected readonly roleLabels = ROLE_LABELS;

  // Lo stato della pagina, con i signals: quando un valore cambia (set/update), Angular aggiorna
  // da solo le parti del template che lo leggono. Nel template si leggono "chiamandoli": accounts().
  protected readonly accounts = signal<Account[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);

  /** Eseguito una volta, quando il componente è pronto: come allora, è il posto dove caricare i dati. */
  ngOnInit(): void {
    this.api.accounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossibile caricare i conti: il backend è avviato?');
        this.loading.set(false);
      },
    });
  }

  /** L'utente ha scelto un nuovo ruolo dal menu a tendina di un conto. */
  onRoleChange(account: Account, event: Event): void {
    const role = (event.target as HTMLSelectElement).value as AccountRole;
    this.message.set(null);
    this.api.changeRole(account.id, role).subscribe({
      next: (updated) => {
        // Sostituiamo il conto aggiornato nella lista. update() riceve il valore attuale e restituisce
        // quello nuovo: creiamo un NUOVO array (map), non modifichiamo quello vecchio.
        this.accounts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
        this.message.set(
          `${this.nameOf(updated)}: ruolo "${ROLE_LABELS[role]}" salvato, movimenti riclassificati.`,
        );
      },
      error: () => this.error.set('Il ruolo non è stato salvato.'),
    });
  }

  protected nameOf(account: Account): string {
    return account.name ?? account.bank;
  }
}
