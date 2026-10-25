import { Routes } from '@angular/router';

import { OverviewPage } from './overview/overview-page';

/**
 * Le pagine dell'applicazione: a ogni indirizzo corrisponde un componente.
 * `title` diventa il titolo della scheda del browser.
 *
 * `loadComponent` carica una pagina solo quando serve (lazy loading, docs/36): `import('...')` dice alla build di
 * metterla in un file a parte, che il browser scarica la prima volta che si apre quella pagina. All'avvio arriva solo
 * il Riepilogo, la pagina che si apre per prima: l'app parte prima, soprattutto sul telefono con rete lenta.
 */
export const routes: Routes = [
  { path: '', redirectTo: 'riepilogo', pathMatch: 'full' },
  { path: 'riepilogo', component: OverviewPage, title: 'Riepilogo · WalletTracker' },
  {
    path: 'analisi',
    loadComponent: () => import('./summary/summary-page').then((m) => m.SummaryPage),
    title: 'Analisi · WalletTracker',
  },
  {
    path: 'budget',
    loadComponent: () => import('./budget/budget-page').then((m) => m.BudgetPage),
    title: 'Budget · WalletTracker',
  },
  {
    path: 'abbonamenti',
    loadComponent: () =>
      import('./subscriptions/subscriptions-page').then((m) => m.SubscriptionsPage),
    title: 'Abbonamenti · WalletTracker',
  },
  {
    path: 'movimenti',
    loadComponent: () => import('./transactions/transactions-page').then((m) => m.TransactionsPage),
    title: 'Movimenti · WalletTracker',
  },
  {
    path: 'conti',
    loadComponent: () => import('./accounts/accounts-page').then((m) => m.AccountsPage),
    title: 'Conti · WalletTracker',
  },
  {
    path: 'importa',
    loadComponent: () => import('./import/import-page').then((m) => m.ImportPage),
    title: 'Importa CSV · WalletTracker',
  },
  {
    path: 'importa/completa',
    loadComponent: () => import('./import/completion-page').then((m) => m.CompletionPage),
    title: 'Completa i movimenti · WalletTracker',
  },
  {
    path: 'regole',
    loadComponent: () => import('./rules/rules-page').then((m) => m.RulesPage),
    title: 'Regole · WalletTracker',
  },
  {
    path: 'categorie',
    loadComponent: () => import('./categories/categories-page').then((m) => m.CategoriesPage),
    title: 'Categorie · WalletTracker',
  },
  {
    path: 'collega',
    loadComponent: () => import('./link/link-page').then((m) => m.LinkPage),
    title: 'Collega una banca · WalletTracker',
  },
  {
    path: 'impostazioni',
    loadComponent: () => import('./settings/settings-page').then((m) => m.SettingsPage),
    title: 'Impostazioni · WalletTracker',
  },
  {
    path: 'altro',
    loadComponent: () => import('./more/more-page').then((m) => m.MorePage),
    title: 'Altro · WalletTracker',
  },
  // L'indirizzo di ritorno dopo il login sulla banca: deve coincidere con il "redirect URL"
  // registrato su Enable Banking (https://localhost:8443/callback).
  {
    path: 'callback',
    loadComponent: () => import('./link/callback-page').then((m) => m.CallbackPage),
    title: 'Collegamento · WalletTracker',
  },
  { path: '**', redirectTo: 'riepilogo' }, // qualsiasi altro indirizzo
];
