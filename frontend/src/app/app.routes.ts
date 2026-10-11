import { Routes } from '@angular/router';

import { AccountsPage } from './accounts/accounts-page';
import { TransactionsPage } from './transactions/transactions-page';

/**
 * Le pagine dell'applicazione: a ogni indirizzo corrisponde un componente.
 * `title` diventa il titolo della scheda del browser.
 */
export const routes: Routes = [
  { path: '', redirectTo: 'movimenti', pathMatch: 'full' },
  { path: 'movimenti', component: TransactionsPage, title: 'Movimenti · WalletTracker' },
  { path: 'conti', component: AccountsPage, title: 'Conti · WalletTracker' },
  { path: '**', redirectTo: 'movimenti' }, // qualsiasi altro indirizzo
];
