import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';

import { BackupStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { BackupSection } from './backup-section';

/** Impostazioni → Backup (docs/48, parte Q), con un backend finto. */
describe('BackupSection', () => {
  const READY: BackupStatus = {
    available: true,
    serviceRunning: true,
    automaticEnabled: true,
    backupTime: '03:15',
    keep: 14,
    inProgress: false,
    lastErrorAt: null,
    lastError: null,
    lastSuccessAt: '2026-10-31T02:15:00Z',
    attention: false,
    backups: [
      {
        name: 'wallettracker-automatico-20261031-031500.backup',
        type: 'automatico',
        createdAt: '2026-10-31T02:15:00Z',
        sizeBytes: 40 * 1024,
      },
      {
        name: 'wallettracker-manuale-20261030-101500.backup',
        type: 'manuale',
        createdAt: '2026-10-30T09:15:00Z',
        sizeBytes: 3 * 1024 * 1024,
      },
    ],
    canDownload: true,
  };

  let answers: BackupStatus[];
  let requested: Subject<BackupStatus>;

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(BackupSection);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    answers = [READY];
    requested = new Subject<BackupStatus>();
    const fakeApi: Partial<WalletApi> = {
      backupStatus: () => of(answers.length > 1 ? answers.shift()! : answers[0]),
      requestBackup: () => requested,
      backupDownloadUrl: (name: string) => `/api/backups/${name}`,
    };
    TestBed.configureTestingModule({
      imports: [BackupSection],
      providers: [{ provide: WalletApi, useValue: fakeApi }],
    });
  });

  it('shows the state and the backups, with the download link from the server', async () => {
    const page = await render();

    expect(page.textContent).toContain('Backup automatico acceso: uno al giorno, dalle 03:15');
    const rows = Array.from(page.querySelectorAll('.backup-list li'));
    expect(rows.map((row) => row.querySelector('.backup-type')?.textContent)).toEqual([
      'Automatico',
      'Fatto a mano',
    ]);
    expect(rows[1].textContent).toContain('3 MB');
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe(
      '/api/backups/wallettracker-automatico-20261031-031500.backup',
    );
  });

  it('from a remote device there is no download link, and it says why', async () => {
    answers = [{ ...READY, canDownload: false }];
    const page = await render();

    expect(page.querySelector('.backup-list a')).toBeNull();
    expect(page.textContent).toContain('si scaricano solo dal server');
  });

  it('without the password the automatic backup is off and the button is disabled', async () => {
    answers = [{ ...READY, automaticEnabled: false, attention: true }];
    const page = await render();

    expect(page.textContent).toContain('Backup automatico spento');
    expect((page.querySelector('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('"Fai un backup adesso" asks the backend, then checks every 3 seconds until it is done', async () => {
    vi.useFakeTimers();
    try {
      const fixture = TestBed.createComponent(BackupSection);
      fixture.detectChanges();
      const page = fixture.nativeElement as HTMLElement;
      const button = () => page.querySelector('button') as HTMLButtonElement;
      expect(button().textContent?.trim()).toBe('Fai un backup adesso');

      button().click();
      requested.next({ ...READY, inProgress: true });
      fixture.detectChanges();
      expect(button().textContent?.trim()).toBe('Backup in corso…');
      expect(button().disabled).toBe(true);

      // Dopo 3 secondi lo stato si richiede: il backup è finito, ed è in cima all'elenco.
      const done = {
        name: 'wallettracker-manuale-20261031-120000.backup',
        type: 'manuale' as const,
        createdAt: '2026-10-31T11:00:00Z',
        sizeBytes: 41 * 1024,
      };
      answers = [{ ...READY, backups: [done, ...READY.backups] }];
      vi.advanceTimersByTime(3000);
      fixture.detectChanges();
      expect(button().textContent?.trim()).toBe('Fai un backup adesso');
      expect(page.querySelectorAll('.backup-list li')).toHaveLength(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it('without Docker (in development) it only says where the backups are made', async () => {
    answers = [{ ...READY, available: false }];
    const page = await render();

    expect(page.textContent).toContain("I backup li fa l'app installata con Docker");
    expect(page.querySelector('button')).toBeNull();
  });
});
