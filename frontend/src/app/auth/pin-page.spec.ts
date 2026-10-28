import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthState, AuthStore } from './auth-store';
import { PinPage } from './pin-page';

/** La pagina del PIN (docs/46, parte E): crearlo, inserirlo, i messaggi del backend, la pausa dopo troppi errori. */
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

  async function open(state: AuthState) {
    TestBed.inject(AuthStore).state.set(state);
    const fixture = TestBed.createComponent(PinPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const type = async (index: number, value: string) => {
      const input = page.querySelectorAll('input')[index] as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };
    const submit = async () => {
      (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
      await fixture.whenStable();
    };
    return { fixture, page, type, submit };
  }

  it('the first time asks for the PIN twice and checks it before sending', async () => {
    const { page, type, submit } = await open('create');
    expect(page.querySelector('h1')?.textContent).toBe('Crea il tuo PIN');
    const inputs = page.querySelectorAll('input');
    expect(inputs.length).toBe(2);
    expect(inputs[0].type).toBe('password');
    expect(inputs[0].getAttribute('inputmode')).toBe('numeric');

    await type(0, '12345');
    await type(1, '12345');
    await submit();
    expect(page.querySelector('.error')?.textContent).toContain('da 6 a 10');

    await type(0, '246810');
    await type(1, '246811');
    await submit();
    expect(page.querySelector('.error')?.textContent).toContain('non sono uguali');

    await type(1, '246810');
    await submit();
    const request = http.expectOne('/api/auth/pin');
    expect(request.request.body).toEqual({ pin: '246810' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    expect(TestBed.inject(AuthStore).state()).toBe('inside');
  });

  it('a wrong PIN shows the reason from the backend and is cleared', async () => {
    const { fixture, page, type, submit } = await open('login');
    expect(page.querySelector('h1')?.textContent).toBe('Inserisci il PIN');

    await type(0, '000000');
    await submit();
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
    const { fixture, page, type, submit } = await open('login');

    await type(0, '000000');
    await submit();
    http
      .expectOne('/api/auth/login')
      .flush(
        { detail: 'Troppi PIN sbagliati: riprova tra 1 minuto.', retryAfterSeconds: 60 },
        { status: 429, statusText: 'Too Many Requests' },
      );
    await fixture.whenStable();

    const button = page.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Aspetta 1 min');
    expect(page.querySelector('.error')?.textContent).toContain('riprova tra 1 minuto');
    fixture.destroy(); // ferma il conto alla rovescia
  });

  it('from another device it explains that the PIN is created on the PC', async () => {
    const { page } = await open('create-elsewhere');
    expect(page.querySelector('h1')?.textContent).toBe('Crea il PIN dal PC');
    expect(page.querySelector('input')).toBeNull();
  });
});
