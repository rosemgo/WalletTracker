import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AuthStatus, SetupStatus } from './api/models';
import { WalletApi } from './api/wallet-api';
import { App } from './app';

describe('App', () => {
  let configured = true;
  let authenticated = true;

  beforeEach(() => {
    const fakeApi: Partial<WalletApi> = {
      // Il PIN è già stato inserito: si vede l'app (la pagina del PIN ha i suoi test, pin-page.spec.ts).
      authStatus: () =>
        of<AuthStatus>({ pinSet: true, withoutPin: false, authenticated, local: true }),
      setupStatus: () =>
        of<SetupStatus>({
          configured,
          source: configured ? 'ENV' : null,
          applicationId: null,
          redirectUrl: '',
          redirectUrls: [],
        }),
    };
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: WalletApi, useValue: fakeApi },
      ],
    });
  });

  it('without the PIN shows only the PIN page, not the app', async () => {
    authenticated = false;
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('h1')?.textContent).toBe('Inserisci il PIN');
    expect(page.querySelector('nav')).toBeNull();
    authenticated = true;
  });

  it('shows the main sections, "Altro" and, for the PC column, the service pages', async () => {
    configured = true;
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const nav = (fixture.nativeElement as HTMLElement).querySelector('nav') as HTMLElement;
    const texts = (selector: string) =>
      Array.from(nav.querySelectorAll(selector)).map((a) => a.textContent?.trim());

    expect(nav.getAttribute('aria-label')).toBe('Sezioni');
    expect(texts('a.section')).toEqual(['Riepilogo', 'Movimenti', 'Analisi', 'Budget', 'Altro']);
    expect(texts('.more-pages a')).toEqual([
      'Abbonamenti',
      'Investimenti',
      'Conti',
      'Collega una banca',
      'Importa',
      'Regole',
      'Categorie',
      'Impostazioni',
    ]);
    // Ogni sezione principale ha la sua icona, nascosta ai lettori di schermo (c'è già la scritta).
    expect(nav.querySelectorAll('a.section svg[aria-hidden="true"] path').length).toBe(5);
    expect((fixture.nativeElement as HTMLElement).querySelector('.banner')).toBeNull();
  });

  it('"Altro" is the current section also in its pages, and only there', async () => {
    configured = true;
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    const more = () =>
      (fixture.nativeElement as HTMLElement).querySelector('a.section.more') as HTMLElement;

    for (const [url, current] of [
      ['/regole', true],
      ['/importa/completa', true],
      ['/altro', true],
      ['/movimenti', false],
      ['/riepilogo', false],
    ] as const) {
      await router.navigateByUrl(url);
      await fixture.whenStable();
      expect(more().classList.contains('active')).toBe(current);
      expect(more().getAttribute('aria-current')).toBe(current ? 'page' : null);
    }
  });

  it('invites to open the settings when Enable Banking is not configured', async () => {
    configured = false;
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('.banner')?.textContent).toContain(
      'Impostazioni',
    );
  });

  it('the eye switches the discreet mode, and says whether it is on', async () => {
    localStorage.clear();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const eye = (fixture.nativeElement as HTMLElement).querySelector(
      'button.eye',
    ) as HTMLButtonElement;

    expect(eye.getAttribute('aria-label')).toContain('Modalità discreta');
    expect(eye.getAttribute('role')).toBe('switch');
    expect(eye.getAttribute('aria-checked')).toBe('false');
    eye.click();
    await fixture.whenStable();
    expect(eye.getAttribute('aria-checked')).toBe('true');
    expect(eye.title).toBe('Mostra gli importi');
    // L'interruttore della colonna del PC segue la stessa scelta.
    const navEye = (fixture.nativeElement as HTMLElement).querySelector('button.nav-eye');
    expect(navEye?.getAttribute('aria-checked')).toBe('true');
    eye.click(); // spenta di nuovo: il prossimo test parte da zero
    localStorage.clear();
  });
});
