import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { SetupStatus } from '../api/models';
import { SetupStore } from '../api/setup-store';
import { WalletApi } from '../api/wallet-api';
import { SettingsPage } from './settings-page';

const NOT_CONFIGURED: SetupStatus = {
  configured: false,
  source: null,
  applicationId: null,
  redirectUrl: 'https://localhost:8443/callback',
};
const APP_ID = 'cf589be3-3755-465b-a8df-a90a16a31403';
const PEM = '-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n';

describe('SettingsPage', () => {
  const saved: [string, string][] = [];
  let rejected = false;

  beforeEach(() => {
    saved.length = 0;
    rejected = false;
    const fakeApi: Partial<WalletApi> = {
      saveEnableBanking: (applicationId: string, pem: string) => {
        saved.push([applicationId, pem]);
        return rejected
          ? throwError(() => ({
              error: { detail: 'Enable Banking ha rifiutato queste credenziali' },
            }))
          : of<SetupStatus>({
              ...NOT_CONFIGURED,
              configured: true,
              source: 'DASHBOARD',
              applicationId,
            });
      },
    };
    TestBed.configureTestingModule({
      imports: [SettingsPage],
      providers: [provideRouter([]), { provide: WalletApi, useValue: fakeApi }],
    });
    TestBed.inject(SetupStore).status.set(NOT_CONFIGURED); // come se il backend avesse già risposto
  });

  /** Simula la scelta di un file nel campo <input type="file">. */
  async function chooseFile(page: HTMLElement, name: string, content: string): Promise<void> {
    const input = page.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [new File([content], name)] });
    input.dispatchEvent(new Event('change'));
    await new Promise((resolve) => setTimeout(resolve, 10)); // il tempo di leggere il file
  }

  it('shows the redirect URL to register on Enable Banking', async () => {
    const fixture = TestBed.createComponent(SettingsPage);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Non ancora configurato');
    expect(text).toContain('https://localhost:8443/callback');
  });

  it('fills the Application ID from the file name and saves the key', async () => {
    const fixture = TestBed.createComponent(SettingsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    await chooseFile(page, `${APP_ID}.pem`, PEM);
    await fixture.whenStable();
    expect((page.querySelectorAll('input')[1] as HTMLInputElement).value).toBe(APP_ID);

    (
      Array.from(page.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Verifica e salva'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(saved).toEqual([[APP_ID, PEM]]);
    expect(page.textContent).toContain('Credenziali verificate e salvate');
    expect(page.textContent).toContain('Configurato');
    expect(TestBed.inject(SetupStore).status()?.configured).toBe(true); // lo stato condiviso è aggiornato
  });

  it('shows why Enable Banking rejected the credentials', async () => {
    rejected = true;
    const fixture = TestBed.createComponent(SettingsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    await chooseFile(page, `${APP_ID}.pem`, PEM);
    (
      Array.from(page.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Verifica e salva'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(page.textContent).toContain('Enable Banking ha rifiutato queste credenziali');
  });

  it('asks for the file before saving', async () => {
    const fixture = TestBed.createComponent(SettingsPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    (
      Array.from(page.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Verifica e salva'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(saved).toEqual([]);
    expect(page.textContent).toContain('scegli il file .pem');
  });
});
