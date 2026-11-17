import {
  LANGUAGE_KEY,
  browserLanguage,
  isLanguage,
  rememberLanguage,
  rememberedLanguage,
  startLanguage,
} from './language';

/** In che lingua si apre l'app (docs/lingue-2-inglese.md, parte D): la ricordata, quella del server, quella del browser. */
describe('the language the app opens in', () => {
  const realFetch = globalThis.fetch;

  beforeEach(() => localStorage.clear());
  afterEach(() => {
    localStorage.clear();
    globalThis.fetch = realFetch;
  });

  /** Il server risponde con questa lingua (o null: nessuno ha ancora scelto). Conta quante volte lo si chiede. */
  function server(language: string | null, suggested: string | null = null): { calls: number } {
    const seen = { calls: 0 };
    globalThis.fetch = (() => {
      seen.calls++;
      return Promise.resolve(
        new Response(JSON.stringify({ language, supported: ['it', 'en'], suggested })),
      );
    }) as typeof fetch;
    return seen;
  }

  it('knows only its own languages', () => {
    expect(isLanguage('it')).toBe(true);
    expect(isLanguage('en')).toBe(true);
    expect(isLanguage('de')).toBe(false);
    expect(isLanguage(null)).toBe(false);
  });

  it('takes from the browser the first language it knows, whatever the country', () => {
    expect(browserLanguage(['it-IT', 'it', 'en'])).toBe('it');
    expect(browserLanguage(['en-US', 'en'])).toBe('en');
    expect(browserLanguage(['de-DE', 'it-CH'])).toBe('it');
    // Nessuna delle nostre: l'inglese, che chi non è italiano capisce più facilmente.
    expect(browserLanguage(['de-DE', 'fr'])).toBe('en');
    expect(browserLanguage([])).toBe('en');
  });

  it('remembers a language only if it is one of ours', () => {
    expect(rememberedLanguage()).toBeNull();
    rememberLanguage('en');
    expect(rememberedLanguage()).toBe('en');
    localStorage.setItem(LANGUAGE_KEY, 'xx');
    expect(rememberedLanguage()).toBeNull();
  });

  it('opens at once in the remembered language, without asking the server', async () => {
    rememberLanguage('en');
    const seen = server('it');

    expect(await startLanguage()).toBe('en');
    expect(seen.calls).toBe(0);
  });

  it('the first time asks the server, so who already uses the app keeps Italian with an English browser', async () => {
    const seen = server('it');

    expect(await startLanguage()).toBe('it');
    expect(seen.calls).toBe(1);
    // Ricordata: dalla prossima apertura non si aspetta più il server.
    expect(rememberedLanguage()).toBe('it');
  });

  it('on a new installation, where nobody chose yet, follows the browser and remembers nothing', async () => {
    server(null);

    expect(await startLanguage()).toBe(browserLanguage());
    expect(rememberedLanguage()).toBeNull();
  });

  it('on a new installation opens in the language chosen in the installer, whatever the browser says', async () => {
    // docs/lingue-3-installazione.md: chi ha risposto "English" allo script trova la prima schermata in inglese.
    const other = browserLanguage() === 'en' ? 'it' : 'en';
    server(null, other);

    expect(await startLanguage()).toBe(other);
    // Una proposta, non una scelta: non si ricorda, e la prima schermata la può ancora cambiare.
    expect(rememberedLanguage()).toBeNull();
  });

  it('the language chosen in the app wins over the one chosen in the installer', async () => {
    server('it', 'en');

    expect(await startLanguage()).toBe('it');
  });

  it('an installer language we do not know is ignored', async () => {
    server(null, 'klingon');

    expect(await startLanguage()).toBe(browserLanguage());
  });

  it('if the server does not answer, or answers nonsense, follows the browser', async () => {
    globalThis.fetch = (() => Promise.reject(new Error('offline'))) as typeof fetch;
    expect(await startLanguage()).toBe(browserLanguage());

    globalThis.fetch = (() =>
      Promise.resolve(new Response('<html>', { status: 502 }))) as typeof fetch;
    expect(await startLanguage()).toBe(browserLanguage());

    server('klingon');
    expect(await startLanguage()).toBe(browserLanguage());
    expect(rememberedLanguage()).toBeNull();
  });
});
