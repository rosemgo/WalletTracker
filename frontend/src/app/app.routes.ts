import { Routes } from '@angular/router';

import { AccountsPage } from './accounts/accounts-page';
import { BudgetPage } from './budget/budget-page';
import { CallbackPage } from './link/callback-page';
import { LinkPage } from './link/link-page';
import { RulesPage } from './rules/rules-page';
import { SettingsPage } from './settings/settings-page';
import { SubscriptionsPage } from './subscriptions/subscriptions-page';
import { SummaryPage } from './summary/summary-page';
import { TransactionsPage } from './transactions/transactions-page';

/**
 * Le pagine dell'applicazione: a ogni indirizzo corrisponde un componente.
 * `title` diventa il titolo della scheda del browser.
 */
export const routes: Routes = [
  { path: '', redirectTo: 'riepilogo', pathMatch: 'full' },
  { path: 'riepilogo', component: SummaryPage, title: 'Riepilogo · WalletTracker' },
  { path: 'budget', component: BudgetPage, title: 'Budget · WalletTracker' },
  { path: 'abbonamenti', component: SubscriptionsPage, title: 'Abbonamenti · WalletTracker' },
  { path: 'movimenti', component: TransactionsPage, title: 'Movimenti · WalletTracker' },
  { path: 'conti', component: AccountsPage, title: 'Conti · WalletTracker' },
  { path: 'regole', component: RulesPage, title: 'Regole · WalletTracker' },
  { path: 'collega', component: LinkPage, title: 'Collega una banca · WalletTracker' },
  { path: 'impostazioni', component: SettingsPage, title: 'Impostazioni · WalletTracker' },
  // L'indirizzo di ritorno dopo il login sulla banca: deve coincidere con il "redirect URL"
  // registrato su Enable Banking (https://localhost:8443/callback).
  { path: 'callback', component: CallbackPage, title: 'Collegamento · WalletTracker' },
  { path: '**', redirectTo: 'riepilogo' }, // qualsiasi altro indirizzo
];
