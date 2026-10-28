import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthStatus } from '../api/models';
import { AccessSection } from './access-section';
import { AuthStore } from './auth-store';

/** "Accesso" nelle Impostazioni (docs/46, parte F): aggiungere e togliere il PIN, solo dal PC. */
describe('AccessSection', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccessSection],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(status: Partial<AuthStatus>) {
    TestBed.inject(AuthStore).status.set({
      pinSet: false,
      withoutPin: false,
      authenticated: true,
      local: false,
      ...status,
    });
    const fixture = TestBed.createComponent(AccessSection);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const type = async (index: number, value: string) => {
      const input = page.querySelectorAll('input')[index] as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };
    return { fixture, page, type };
  }

  it('without a PIN, the PC can add one and sees the recovery code', async () => {
    const { fixture, page, type } = await open({ withoutPin: true, local: true });
    expect(page.textContent).toContain('senza PIN');

    await type(0, '246810');
    await type(1, '246810');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    http.expectOne('/api/auth/pin').flush({ recoveryCode: 'K7QM-2XPA-9RWD-4TNE' });
    await fixture.whenStable();

    expect(page.querySelector('.recovery-code')?.textContent).toBe('K7QM-2XPA-9RWD-4TNE');
  });

  it('with a PIN, the PC can remove it by writing it', async () => {
    const { fixture, page, type } = await open({ pinSet: true, local: true });
    expect(page.textContent).toContain('protetta da un PIN');

    await type(0, '246810');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    const request = http.expectOne('/api/auth/pin/remove');
    expect(request.request.body).toEqual({ pin: '246810' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/status').flush({
      pinSet: false,
      withoutPin: true,
      authenticated: true,
      local: true,
    });
    await fixture.whenStable();
    expect(page.textContent).toContain('senza PIN');
  });

  it('from another device nothing can be changed', async () => {
    const { page } = await open({ pinSet: true, local: false });
    expect(page.querySelector('input')).toBeNull();
    expect(page.textContent).toContain('solo dal PC');
  });
});
