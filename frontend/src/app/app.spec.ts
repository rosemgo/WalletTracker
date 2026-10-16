import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
      providers: [provideRouter([]), { provide: WalletApi, useValue: fakeApi }],
    });
  });

  it('shows the links to the pages', async () => {
    configured = true;
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('nav a'));

    expect(links.map((a) => a.textContent?.trim())).toEqual([
      'Riepilogo',
      'Movimenti',
      'Conti',
      'Regole',
      'Collega una banca',
      'Impostazioni',
    ]);
    expect((fixture.nativeElement as HTMLElement).querySelector('.banner')).toBeNull();
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
