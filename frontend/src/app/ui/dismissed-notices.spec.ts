import { TestBed } from '@angular/core/testing';

import { DISMISSED_KEY, DismissedNotices, MAX_REMEMBERED } from './dismissed-notices';

describe('DismissedNotices', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  function store(): DismissedNotices {
    TestBed.resetTestingModule();
    return TestBed.inject(DismissedNotices);
  }

  it('a dismissed notice stays dismissed after the app is opened again', () => {
    store().dismiss('only-transfers:Banca Alfa');

    const reopened = store();
    expect(reopened.isDismissed('only-transfers:Banca Alfa')).toBe(true);
    // Un'altra chiave (la situazione è cambiata) non è chiusa.
    expect(reopened.isDismissed('only-transfers:Banca Alfa, Banca Beta')).toBe(false);
  });

  it('dismissed for now is forgotten when the app is opened again', () => {
    const first = store();
    first.dismissForNow('offline');
    expect(first.isDismissed('offline')).toBe(true);

    expect(store().isDismissed('offline')).toBe(false);
  });

  it('remembers at most the latest keys, and ignores what it cannot read', () => {
    const notices = store();
    for (let i = 0; i <= MAX_REMEMBERED; i++) {
      notices.dismiss(`k${i}`);
    }
    const reopened = store();
    expect(reopened.isDismissed('k0')).toBe(false);
    expect(reopened.isDismissed(`k${MAX_REMEMBERED}`)).toBe(true);

    localStorage.setItem(DISMISSED_KEY, 'non è JSON');
    expect(store().isDismissed('k1')).toBe(false);
  });
});
