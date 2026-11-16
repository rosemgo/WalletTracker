import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { clearTranslations, loadTranslations } from '@angular/localize';

import english from '../../locale/en.json';
import { TranslationFile, forAngular } from './translations';

/**
 * L'app aperta in inglese, come la apre main.ts: prima la lingua della pagina e la traduzione, poi (import() dentro
 * ogni test) i file dell'app, che scrivono le loro frasi nel momento in cui vengono caricati.
 *
 * Gli altri test girano tutti in italiano, la lingua in cui l'app è scritta. Questi controllano che il meccanismo
 * funzioni davvero da un capo all'altro: frasi dei template e del TypeScript, plurali, date, importi.
 */
describe('the app in English', () => {
  beforeAll(() => {
    document.documentElement.lang = 'en';
    loadTranslations(forAngular(english as unknown as TranslationFile));
  });

  afterAll(() => {
    clearTranslations();
    document.documentElement.lang = '';
  });

  it('the language of the page decides the formats: European English', async () => {
    const { APP_LANGUAGE, APP_LOCALE } = await import('../text/locale');

    expect(APP_LANGUAGE).toBe('en');
    expect(APP_LOCALE).toBe('en-GB');
  });

  it('the labels written in the TypeScript are in English', async () => {
    const { ROLE_LABELS, TYPE_LABELS, MATCH_MODE_LABELS, investmentGroupName } =
      await import('../api/labels');

    expect(TYPE_LABELS.EXPENSE).toBe('Expense');
    expect(TYPE_LABELS.TO_REVIEW).toBe('To review');
    expect(ROLE_LABELS.CREDIT_CARD).toBe('Credit card');
    expect(MATCH_MODE_LABELS.ALL_WORDS).toBe('contains all the words');
    expect(investmentGroupName({ name: null, kind: 'OTHER_INCOME', amount: 1, movements: 1 })).toBe(
      'Other income',
    );
    // Un nome scritto dall'utente o dalla banca resta com'è.
    expect(investmentGroupName({ name: 'Cedole', kind: 'NAMED', amount: 1, movements: 1 })).toBe(
      'Cedole',
    );
  });

  it('dates are written the English way, with the day before the month', async () => {
    const { formatLocalDate } = await import('../text/dates');

    expect(formatLocalDate('2026-10-10', 'numeric')).toBe('10/10/2026');
    expect(formatLocalDate('2026-12-31', 'numeric')).toBe('31/12/2026');
    expect(formatLocalDate('2026-10-10', 'medium')).toBe('10 Oct 2026');
    expect(formatLocalDate('2026-10-10', 'long')).toBe('10 October 2026');
    expect(formatLocalDate('2026-10-10', 'weekday')).toBe('Saturday 10 October');
    expect(formatLocalDate('2026-10-10', 'monthYear')).toBe('October 2026');
    expect(formatLocalDate(new Date(2026, 9, 10, 18, 5), 'longTime')).toBe(
      '10 October 2026, 18:05',
    );
  });

  it('the titles of the days and the labels of the periods follow', async () => {
    const { dayLabel } = await import('../transactions/day-groups');
    const { barName, perUnit } = await import('../explore/explore-labels');
    const { yearLabel } = await import('../overview/overview-page');
    const today = new Date(2026, 9, 25);

    expect(dayLabel('2026-10-25', today)).toBe('Today · Sunday 25 October');
    expect(dayLabel('2026-10-24', today)).toBe('Yesterday · Saturday 24 October');
    expect(dayLabel('2025-12-31', today)).toMatch(/^Wednesday,? 31 December 2025$/);
    expect(barName({ from: '2026-10-01', to: '2026-10-31' } as never, 'MONTH')).toBe(
      'October 2026',
    );
    expect(perUnit('WEEK')).toBe('a week');
    expect(yearLabel('2025-03', false, 'Mar')).toBe('Jan–Mar 2025');
    expect(yearLabel('2026-11', true, 'Nov')).toBe('Since the start of 2026');
  });

  it('amounts take the symbol in front and the point for the decimals', async () => {
    const { MoneyPipe } = await import('../privacy/money.pipe');
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: 'en-GB' }] });
    const pipe = TestBed.runInInjectionContext(() => new MoneyPipe());

    expect(pipe.transform(-630.39, 'EUR')).toBe('-€630.39');
    expect(pipe.transform(1234.5, 'EUR')).toBe('€1,234.50');
  });

  it('a page is in English: the first screen, with its plurals and its choice of language', async () => {
    const { PinPage } = await import('../auth/pin-page');
    const { AuthStore } = await import('../auth/auth-store');
    const { LanguageStore } = await import('./language-store');
    TestBed.configureTestingModule({
      imports: [PinPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: LanguageStore,
          useValue: { current: 'en', chooseHere: vi.fn(), choose: vi.fn() },
        },
      ],
    });
    const auth = TestBed.inject(AuthStore);
    auth.state.set('choose');
    auth.status.set({ pinSet: false, withoutPin: false, authenticated: false, local: true });
    const fixture = TestBed.createComponent(PinPage);
    await fixture.whenStable();
    const page: HTMLElement = fixture.nativeElement;

    expect(page.querySelector('h1')?.textContent).toBe('How do you want to protect WalletTracker?');
    const buttons = Array.from(page.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['Create the PIN', 'Continue without a PIN']);
    expect(page.textContent).toContain('From 6 to 10 digits');
    // Le lingue hanno sempre il loro nome, e quella della pagina è selezionata.
    const select = page.querySelector('select')!;
    expect(select.value).toBe('en');
    // Niente è rimasto in italiano.
    expect(page.textContent).not.toMatch(/\b(il|la|con|senza|crea|continua)\b/i);
  });

  it('plurals in a page follow the number, in English', async () => {
    const { LiquidityCard } = await import('../overview/liquidity-card');
    const { WalletApi } = await import('../api/wallet-api');
    const { provideRouter } = await import('@angular/router');
    const { of } = await import('rxjs');
    const account = (id: number, amount: number | null) => ({
      id,
      label: `Account ${id}`,
      currency: 'EUR',
      role: 'MAIN',
      balance:
        amount === null ? null : { amount, date: '2026-10-10', source: 'BANK', startDate: null },
    });
    const render = async (accounts: unknown[]) => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          {
            provide: WalletApi,
            useValue: {
              accounts: () => of(accounts),
              // Lo storico della liquidità: un giorno stimato e due salvati di fila, per il grafico e la sua legenda.
              balanceHistory: (currency: string) =>
                of({
                  currency,
                  accounts: 2,
                  points: [
                    { date: '2026-10-08', amount: 140, estimated: true },
                    { date: '2026-10-09', amount: 145, estimated: false },
                    { date: '2026-10-10', amount: 150, estimated: false },
                  ],
                }),
            },
          },
          { provide: LOCALE_ID, useValue: 'en-GB' },
        ],
      });
      const fixture = TestBed.createComponent(LiquidityCard);
      await fixture.whenStable();
      fixture.detectChanges();
      const card = fixture.nativeElement as HTMLElement;
      const chart = card.querySelector('[aria-label]')?.getAttribute('aria-label') ?? '';
      return (card.textContent ?? '') + ' ' + chart;
    };

    const one = await render([account(1, 100)]);
    const many = await render([account(1, 100), account(2, 50), account(3, null)]);

    expect(one).toContain('On your account, now');
    expect(many).toContain('On 2 accounts, now');
    expect(many).toContain('1 account without a balance is not in the total');
    expect(many).toContain('on 10 Oct');
    expect(many).toMatch(/€150/);
    // Il grafico dello storico, e la sua descrizione per chi legge con un lettore di schermo.
    expect(many).toContain('Saved balances');
    expect(many).toContain('Estimated from the transactions');
    expect(many).toContain('Cash in EUR day by day, from 8 October 2026 to today.');
  });
});
