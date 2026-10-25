import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { SetupStatus } from './api/models';
import { WalletApi } from './api/wallet-api';
import { App } from './app';

describe('App', () => {
  let configured = true;

  beforeEach(() => {
    const fakeApi: Partial<WalletApi> = {
      setupStatus: () =>
        of<SetupStatus>({
          configured,
          source: configured ? 'ENV' : null,
          applicationId: null,
          redirectUrl: '',
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
});
