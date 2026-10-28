import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthStatus } from '../api/models';
import { AuthState, AuthStore } from './auth-store';
import { PinPage } from './pin-page';

/**
 * La pagina per entrare (docs/46, parte F): la scelta alla prima apertura, il codice di recupero, il PIN, i messaggi
 * del backend, la pausa dopo troppi errori.
 */
describe('PinPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [PinPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  async function open(state: AuthState, status: Partial<AuthStatus> = {}) {
    const auth = TestBed.inject(AuthStore);
    auth.state.set(state);
    auth.status.set({
      pinSet: false,
      withoutPin: false,
      authenticated: false,
      local: false,
      ...status,
    });
    const fixture = TestBed.createComponent(PinPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const type = async (index: number, value: string) => {
      const input = page.querySelectorAll('input')[index] as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };
    const click = async (text: string) => {
      const button = Array.from(page.querySelectorAll('button')).find((b) =>
        b.textContent?.includes(text),
      ) as HTMLButtonElement;
      button.click();
      await fixture.whenStable();
    };
    return { fixture, page, type, click, auth };
  }

  it('the first time offers a PIN or no PIN, and checks the PIN before sending it', async () => {
    const { page, type, click, fixture, auth } = await open('choose', { local: true });
    expect(page.querySelector('h1')?.textContent).toContain('Come vuoi proteggere');
    expect(page.textContent).toContain('Senza PIN, solo da questo PC');
    const inputs = page.querySelectorAll('input');
    expect(inputs[0].type).toBe('password');
    expect(inputs[0].getAttribute('inputmode')).toBe('numeric');

    await type(0, '12345');
    await type(1, '12345');
    await click('Crea il PIN');
    expect(page.querySelector('.error')?.textContent).toContain('da 6 a 10');

    await type(0, '246810');
    await type(1, '246811');
    await click('Crea il PIN');
    expect(page.querySelector('.error')?.textContent).toContain('non sono uguali');

    await type(1, '246810');
    await click('Crea il PIN');
    http.expectOne('/api/auth/pin').flush({ recoveryCode: 'K7QM-2XPA-9RWD-4TNE' });
    await fixture.whenStable();

    // Il codice di recupero, una volta sola, prima di entrare.
    expect(page.querySelector('h1')?.textContent).toContain('Salva il codice di recupero');
    expect(page.querySelector('.recovery-code')?.textContent).toBe('K7QM-2XPA-9RWD-4TNE');
    expect(auth.state()).toBe('choose');
    await click("L'ho salvato");
    expect(auth.state()).toBe('inside');
  });

  it('"Continua senza PIN" makes the choice', async () => {
    const { click } = await open('choose', { local: true });
    await click('Continua senza PIN');
    http.expectOne('/api/auth/no-pin').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/status').flush({
      pinSet: false,
      withoutPin: true,
      authenticated: true,
      local: true,
    });
    expect(TestBed.inject(AuthStore).state()).toBe('inside');
  });

  it('a wrong PIN shows the reason from the backend and is cleared', async () => {
    const { fixture, page, type, click } = await open('login', { pinSet: true });
    expect(page.querySelector('h1')?.textContent).toBe('Inserisci il PIN');

    await type(0, '000000');
    await click('Entra');
    http
      .expectOne('/api/auth/login')
      .flush(
        { detail: 'PIN sbagliato. Ancora 4 tentativi prima di una pausa.', attemptsLeft: 4 },
        { status: 401, statusText: 'Unauthorized' },
      );
    await fixture.whenStable();

    expect(page.querySelector('.error')?.textContent).toContain('Ancora 4 tentativi');
    expect((page.querySelector('input') as HTMLInputElement).value).toBe('');
  });

  it('after too many wrong PINs the button waits, and says how long', async () => {
    const { fixture, page, type, click } = await open('login', { pinSet: true });

    await type(0, '000000');
    await click('Entra');
    http
      .expectOne('/api/auth/login')
      .flush(
        { detail: 'Troppi tentativi sbagliati: riprova tra 1 minuto.', retryAfterSeconds: 60 },
        { status: 429, statusText: 'Too Many Requests' },
      );
    await fixture.whenStable();

    const button = page.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Aspetta 1 min');
    fixture.destroy(); // ferma il conto alla rovescia
  });

  it('"Ho dimenticato il PIN" asks for the code and a new PIN, then shows the new code', async () => {
    const { fixture, page, type, click } = await open('login', { pinSet: true });
    await click('Ho dimenticato il PIN');
    expect(page.querySelector('h1')?.textContent).toBe('Ho dimenticato il PIN');

    await type(0, 'k7qm-2xpa-9rwd-4tne');
    await type(1, '112233');
    await type(2, '112233');
    await click('Scegli il PIN nuovo');
    const request = http.expectOne('/api/auth/recover');
    expect(request.request.body).toEqual({ code: 'k7qm-2xpa-9rwd-4tne', pin: '112233' });
    request.flush({ recoveryCode: 'NEWC-ODEA-BCDE-FGHJ' });
    await fixture.whenStable();

    expect(page.querySelector('.recovery-code')?.textContent).toBe('NEWC-ODEA-BCDE-FGHJ');
  });

  it('from another device it explains that the choice is made on the PC', async () => {
    const first = await open('elsewhere', { local: false });
    expect(first.page.querySelector('h1')?.textContent).toBe('Crea il PIN dal PC');
    expect(first.page.querySelector('input')).toBeNull();
    first.fixture.destroy();

    const without = await open('elsewhere', { withoutPin: true, local: false });
    expect(without.page.querySelector('h1')?.textContent).toBe('Da qui serve un PIN');
    expect(without.page.textContent).toContain('Impostazioni → Accesso');
  });
});
