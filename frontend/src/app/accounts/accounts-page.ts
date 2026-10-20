import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ROLES, ROLE_LABELS } from '../api/labels';
import { Account, AccountRole } from '../api/models';
import { WalletApi } from '../api/wallet-api';

/**
 * La pagina dei conti: elenco dei conti, con il ruolo modificabile da un menu a tendina.
 *
 * I conti sono di due tipi: quelli collegati a una banca (Enable Banking, si aggiornano da soli) e quelli
 * "manuali", per le banche che Enable Banking non copre: li crei qui e ci carichi i movimenti da un file
 * CSV (pagina Importa CSV). Solo i conti manuali si possono eliminare da qui.
 *
 * È un componente "standalone": non serve dichiararlo in un NgModule. Le cose che il template usa
 * (qui la pipe `date`) si elencano direttamente in `imports`.
 */
@Component({
  selector: 'app-accounts-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  templateUrl: './accounts-page.html',
})
export class AccountsPage implements OnInit {
  private readonly api = inject(WalletApi);
  private readonly fb = inject(NonNullableFormBuilder);

  // Le costanti che il template deve vedere vanno esposte come proprietà del componente.
  protected readonly roles = ROLES;
  protected readonly roleLabels = ROLE_LABELS;

  // Lo stato della pagina, con i signals: quando un valore cambia (set/update), Angular aggiorna
  // da solo le parti del template che lo leggono. Nel template si leggono "chiamandoli": accounts().
  protected readonly accounts = signal<Account[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly syncing = signal(false);
  protected readonly confirmingDelete = signal<number | null>(null);
  protected readonly renaming = signal<number | null>(null); // il conto di cui si sta cambiando il nome // il conto da eliminare, in attesa di conferma

  /**
   * Il modulo del conto manuale. Gli stessi controlli del backend (ManualAccountRequest): qui servono a
   * dire subito che cosa non va, là a proteggere i dati.
   */
  protected readonly form = this.fb.group({
    bankName: ['', [Validators.required, Validators.maxLength(100)]],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    iban: ['', Validators.pattern(/^$|^[A-Za-z]{2}[0-9A-Za-z ]{13,40}$/)],
    currency: ['EUR', [Validators.required, Validators.pattern(/^[A-Za-z]{3}$/)]],
    role: ['UNASSIGNED' as AccountRole],
  });

  /** Eseguito una volta, quando il componente è pronto: come allora, è il posto dove caricare i dati. */
  ngOnInit(): void {
    this.loadAccounts();
  }

  /**
   * "Aggiorna ora": il backend legge subito tutti i conti, con gli header PSU (sei tu ad averlo chiesto,
   * quindi sei presente). Poi ricarichiamo i conti, per mostrare l'ora e gli eventuali errori.
   */
  syncNow(): void {
    this.syncing.set(true);
    this.message.set(null);
    this.error.set(null);
    this.api.syncNow().subscribe({
      next: (round) => {
        this.syncing.set(false);
        const failed = round.failed > 0 ? `, ${round.failed} con errori (vedi sotto)` : '';
        const updated =
          round.updated === 1 ? 'Aggiornato 1 conto' : `Aggiornati ${round.updated} conti`;
        this.message.set(`${updated}${failed}.`);
        this.loadAccounts();
      },
      error: () => {
        this.syncing.set(false);
        this.error.set("L'aggiornamento non è riuscito: il backend è avviato?");
      },
    });
  }

  private loadAccounts(): void {
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

  /** Crea il conto manuale descritto nel modulo. */
  addManual(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { bankName, name, iban, currency, role } = this.form.getRawValue();
    this.message.set(null);
    this.error.set(null);
    this.api
      .createManualAccount({
        bankName: bankName.trim(),
        name: name.trim(),
        iban: iban.trim() === '' ? null : iban.trim(),
        currency: currency.toUpperCase(),
        role,
      })
      .subscribe({
        next: (account) => {
          this.message.set(
            `Conto "${this.nameOf(account)}" creato: ora puoi importare un file CSV.`,
          );
          this.form.reset();
          this.loadAccounts();
        },
        error: (response) =>
          this.error.set(response.error?.detail ?? 'Il conto non è stato creato.'),
      });
  }

  /** Elimina un conto manuale con tutti i suoi movimenti (dopo la conferma). */
  remove(account: Account): void {
    this.api.deleteAccount(account.id).subscribe({
      next: () => {
        this.confirmingDelete.set(null);
        this.message.set(`Conto "${this.nameOf(account)}" eliminato, con i suoi movimenti.`);
        this.error.set(null);
        this.loadAccounts();
      },
      error: (response) => {
        this.confirmingDelete.set(null);
        this.error.set(response.error?.detail ?? 'Il conto non è stato eliminato.');
      },
    });
  }

  /** Il campo va segnalato come sbagliato? Solo dopo che l'utente ci è passato (touched). */
  protected invalid(field: 'bankName' | 'name' | 'iban' | 'currency'): boolean {
    const control = this.form.controls[field];
    return control.invalid && control.touched;
  }

  protected nameOf(account: Account): string {
    return account.label;
  }

  /**
   * Salva il nome scelto per il conto. Vuoto lo toglie: torna l'etichetta automatica (banca e IBAN).
   * Uguale a prima: non fa niente.
   */
  rename(account: Account, value: string): void {
    const name = value.trim();
    if (name === (account.displayName ?? '')) {
      this.renaming.set(null);
      return;
    }
    this.api.renameAccount(account.id, name).subscribe({
      next: (updated) => {
        this.renaming.set(null);
        this.accounts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
        this.message.set(
          name === ''
            ? `Nome tolto: ora il conto si vede come "${updated.label}".`
            : `Conto rinominato in "${name}".`,
        );
        this.error.set(null);
      },
      error: (response) => this.error.set(response.error?.detail ?? 'Il nome non è stato salvato.'),
    });
  }
}
