import { TestBed } from '@angular/core/testing';
import { SwUpdate, VersionEvent } from '@angular/service-worker';
import { Subject } from 'rxjs';

import { AppStatus } from './app-status';

describe('AppStatus', () => {
  it('follows the network: offline and back online', () => {
    const status = TestBed.inject(AppStatus);
    expect(status.online()).toBe(true);

    window.dispatchEvent(new Event('offline'));
    expect(status.online()).toBe(false);
    window.dispatchEvent(new Event('online'));
    expect(status.online()).toBe(true);
  });

  it('without a service worker (ng serve, tests) there is never a new version', () => {
    expect(TestBed.inject(AppStatus).updateReady()).toBe(false);
  });

  it('says when a new version is ready, and checks again when the app comes back to the front', () => {
    const versionUpdates = new Subject<VersionEvent>();
    let checks = 0;
    const fakeUpdates = {
      isEnabled: true,
      versionUpdates,
      unrecoverable: new Subject(),
      checkForUpdate: () => {
        checks++;
        return Promise.resolve(false);
      },
    };
    TestBed.configureTestingModule({ providers: [{ provide: SwUpdate, useValue: fakeUpdates }] });
    const status = TestBed.inject(AppStatus);

    // Scaricata in sottofondo: non ancora pronta.
    versionUpdates.next({ type: 'VERSION_DETECTED', version: { hash: 'b' } });
    expect(status.updateReady()).toBe(false);
    versionUpdates.next({
      type: 'VERSION_READY',
      currentVersion: { hash: 'a' },
      latestVersion: { hash: 'b' },
    });
    expect(status.updateReady()).toBe(true);

    document.dispatchEvent(new Event('visibilitychange')); // in jsdom la pagina è "visible"
    expect(checks).toBe(1);
  });
});
