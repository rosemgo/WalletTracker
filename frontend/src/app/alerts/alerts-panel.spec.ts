import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Alert } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AlertsPanel, alertKey } from './alerts-panel';

const NONE: Omit<Alert, 'kind' | 'severity'> = {
  bank: null,
  country: null,
  account: null,
  date: null,
  daysLeft: null,
  items: [],
  message: null,
};

describe('AlertsPanel', () => {
  let alerts: Alert[];

  afterEach(() => localStorage.clear());

  beforeEach(() => {
    registerLocaleData(localeIt);
    localStorage.clear();
    alerts = [];
    TestBed.configureTestingModule({
      imports: [AlertsPanel],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: { alerts: () => of(alerts) } },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(AlertsPanel);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows nothing when there is nothing to report', async () => {
    expect((await render()).querySelector('.alerts')).toBeNull();
  });

  it('writes each kind of alert in plain words, with the action to solve it', async () => {
    alerts = [
      {
        ...NONE,
        kind: 'CONSENT_EXPIRED',
        severity: 'CRITICAL',
        bank: 'BBVA',
        country: 'ES',
        date: '2026-10-14T10:00:00Z',
        daysLeft: -3,
      },
      {
        ...NONE,
        kind: 'CONSENT_EXPIRING',
        severity: 'WARNING',
        bank: 'ING',
        country: 'IT',
        date: '2026-10-22T10:00:00Z',
        daysLeft: 5,
      },
      {
        ...NONE,
        kind: 'SYNC_FAILED',
        severity: 'WARNING',
        bank: 'ING',
        account: 'ING ··4521', // l'etichetta del conto, come la calcola il backend
        message: 'HTTP 429 troppe letture',
      },
      { ...NONE, kind: 'BUDGET_OVER', severity: 'WARNING', items: ['Ristoranti', 'Viaggi'] },
    ];
    const page = await render();
    const rows = Array.from(page.querySelectorAll('.alert'));

    expect(rows.length).toBe(4);
    expect(rows[0].classList).toContain('critical');
    expect(rows[0].textContent).toContain('Il collegamento con BBVA è scaduto');
    expect(rows[0].textContent).toContain('14/10/2026');
    expect(rows[1].textContent).toContain('scade tra 5 giorni');
    expect(rows[2].textContent).toContain('Aggiornamento non riuscito: ING ··4521.');
    expect(rows[2].textContent).toContain('HTTP 429 troppe letture');
    expect(rows[3].textContent).toContain('Ristoranti, Viaggi');

    // "Ricollega" apre "Collega una banca" con banca e paese già scelti.
    const renew = new URL((rows[0].querySelector('a') as HTMLAnchorElement).href);
    expect(renew.pathname).toBe('/collega');
    expect(Object.fromEntries(renew.searchParams)).toEqual({ bank: 'BBVA', country: 'ES' });
    expect(new URL((rows[3].querySelector('a') as HTMLAnchorElement).href).pathname).toBe(
      '/budget',
    );
  });

  it('an alert can be closed, and stays closed until the situation changes', async () => {
    alerts = [
      { ...NONE, kind: 'BUDGET_OVER', severity: 'WARNING', items: ['Ristoranti'] },
      {
        ...NONE,
        kind: 'SYNC_FAILED',
        severity: 'WARNING',
        account: 'ING ··4521',
        message: 'HTTP 429',
      },
    ];
    const fixture = TestBed.createComponent(AlertsPanel);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    const close = page.querySelectorAll('.alert button')[0] as HTMLButtonElement;
    expect(close.getAttribute('aria-label')).toBe('Chiudi questo avviso');
    close.click();
    await fixture.whenStable();

    const rows = page.querySelectorAll('.alert');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('ING ··4521');
    // Riaperto il Riepilogo, resta chiuso; una categoria in più è un avviso nuovo.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AlertsPanel],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: { alerts: () => of(alerts) } },
        { provide: LOCALE_ID, useValue: 'it-IT' },
      ],
    });
    expect((await render()).querySelectorAll('.alert').length).toBe(1);
    alerts[0] = { ...alerts[0], items: ['Ristoranti', 'Viaggi'] };
    expect((await render()).querySelectorAll('.alert').length).toBe(2);
  });

  it('the key of an alert describes the situation', () => {
    const expiring = (daysLeft: number): Alert => ({
      ...NONE,
      kind: 'CONSENT_EXPIRING',
      severity: 'WARNING',
      bank: 'ING',
      date: '2026-11-20T10:00:00Z',
      daysLeft,
    });
    // Due settimane prima e a 10 giorni: lo stesso promemoria. Negli ultimi 3 giorni: uno nuovo.
    expect(alertKey(expiring(13))).toBe(alertKey(expiring(10)));
    expect(alertKey(expiring(3))).not.toBe(alertKey(expiring(10)));
    // Il budget: il mese dopo è un avviso nuovo.
    const budget: Alert = { ...NONE, kind: 'BUDGET_OVER', severity: 'WARNING', items: ['Viaggi'] };
    expect(alertKey(budget, new Date(2026, 10, 12))).toBe('alert:BUDGET_OVER:2026-11:Viaggi');
    expect(alertKey(budget, new Date(2026, 11, 1))).toBe('alert:BUDGET_OVER:2026-12:Viaggi');
  });

  it('says "domani" and "oggi" for the last days', async () => {
    alerts = [
      { ...NONE, kind: 'CONSENT_EXPIRING', severity: 'WARNING', bank: 'A', daysLeft: 1 },
      { ...NONE, kind: 'CONSENT_EXPIRING', severity: 'WARNING', bank: 'B', daysLeft: 0 },
    ];
    const rows = (await render()).querySelectorAll('.alert');

    expect(rows[0].textContent).toContain('scade domani');
    expect(rows[1].textContent).toContain('scade oggi');
  });
});
