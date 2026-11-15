import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { WalletApi } from '../api/wallet-api';
import { AuthState, AuthStore } from '../auth/auth-store';
import { LANGUAGE_KEY } from './language';
import { LanguageStore, RELOAD_PAGE } from './language-store';

/**
 * La lingua di questa pagina e quella dell'installazione, tenute allineate (docs/lingue-2-inglese.md, parte D). Nei
 * test la pagina è in italiano: è la lingua in cui l'app è scritta.
 */
describe('LanguageStore', () => {
  let state: ReturnType<typeof signal<AuthState>>;
  let serverLanguage: string | null;
  let chosen: string[];
  let reloads: number;
  let chooseAnswer: () => Observable<unknown>;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    state = signal<AuthState>('checking');
    serverLanguage = 'it';
    chosen = [];
    reloads = 0;
    chooseAnswer = () => of(null);
    TestBed.configureTestingModule({
      providers: [
        {
          provide: WalletApi,
          useValue: {
            language: () => of({ language: serverLanguage, supported: ['it', 'en'] }),
            chooseLanguage: (language: string) => {
              chosen.push(language);
              return chooseAnswer();
            },
          },
        },
        { provide: AuthStore, useValue: { state } },
        { provide: RELOAD_PAGE, useValue: () => reloads++ },
      ],
    });
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  function open(as: AuthState): LanguageStore {
    const store = TestBed.inject(LanguageStore);
    state.set(as);
    TestBed.tick();
    return store;
  }

  it('does nothing while it is not known how to get in, or the server does not answer', () => {
    serverLanguage = 'en';
    open('checking');
    open('unreachable');

    expect(reloads).toBe(0);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBeNull();
  });

  it('when the installation has the language of this page, it only remembers it', () => {
    const store = open('inside');

    expect(store.server()).toBe('it');
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('it');
    expect(reloads).toBe(0);
    expect(chosen).toEqual([]);
  });

  it('when the language was changed from another device, remembers the new one and reloads the page, once', () => {
    serverLanguage = 'en';
    open('login'); // anche dalla pagina del PIN: la lingua si legge senza essere dentro

    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('en');
    expect(reloads).toBe(1);

    // Se dopo la ricarica la pagina fosse ancora in italiano (la traduzione non si è caricata), non ricarica più.
    state.set('inside');
    TestBed.tick();
    expect(reloads).toBe(1);
  });

  it('on a new installation, once inside, hands the language of this page to the server', () => {
    serverLanguage = null;
    const store = open('inside');

    expect(chosen).toEqual(['it']);
    expect(store.server()).toBe('it');
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('it');
    expect(reloads).toBe(0);
  });

  it('on a new installation, before getting in, waits: the server accepts no changes yet', () => {
    serverLanguage = null;
    open('choose');

    expect(chosen).toEqual([]);

    // Fatta la scelta del PIN si è dentro: adesso sì.
    state.set('inside');
    TestBed.tick();
    expect(chosen).toEqual(['it']);
  });

  it('choosing a language saves it on the server, then reloads the page in the new language', () => {
    const store = open('inside');

    store.choose('en').subscribe();

    expect(chosen).toEqual(['en']);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('en');
    expect(reloads).toBe(1);
  });

  it('if the server refuses the change, the page stays as it is', () => {
    const store = open('inside');
    chooseAnswer = () => throwError(() => new Error('no'));

    store.choose('en').subscribe({ error: () => undefined });

    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('it');
    expect(reloads).toBe(0);
  });

  it('on the first screen the language changes only for this page', () => {
    serverLanguage = null;
    const store = open('choose');

    store.chooseHere('en');

    expect(chosen).toEqual([]);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe('en');
    expect(reloads).toBe(1);
  });
});
