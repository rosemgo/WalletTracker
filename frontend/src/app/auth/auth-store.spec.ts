import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { authInterceptor } from './auth-interceptor';
import { AuthStore, SESSION_SEEN_FOR_MS, SESSION_SEEN_KEY } from './auth-store';

/**
 * Lo stato del PIN e l'intercettore delle sessioni scadute (docs/46, parte E), con il finto server di HttpClient.
 */
describe('AuthStore', () => {
  let auth: AuthStore;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  function answerStatus(pinSet: boolean, authenticated: boolean, canCreatePin: boolean): void {
    auth.refresh();
    http.expectOne('/api/auth/status').flush({ pinSet, authenticated, canCreatePin });
  }

  it('decides what to show from the status', () => {
    expect(auth.state()).toBe('checking');
    answerStatus(false, false, true);
    expect(auth.state()).toBe('create');
    answerStatus(false, false, false);
    expect(auth.state()).toBe('create-elsewhere');
    answerStatus(true, false, false);
    expect(auth.state()).toBe('login');
    answerStatus(true, true, false);
    expect(auth.state()).toBe('inside');
    expect(Number(localStorage.getItem(SESSION_SEEN_KEY))).toBeGreaterThan(0);
  });

  it('logging in sends the PIN and then shows the app', () => {
    let done = false;
    auth.login('135792').subscribe(() => (done = true));

    const request = http.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ pin: '135792' });
    request.flush(null, { status: 204, statusText: 'No Content' });

    expect(done).toBe(true);
    expect(auth.state()).toBe('inside');
  });

  it('without the backend, opens on the saved data only after a recent confirmation', () => {
    auth.refresh();
    http.expectOne('/api/auth/status').error(new ProgressEvent('error')); // nessuna rete
    expect(auth.state()).toBe('unreachable');

    localStorage.setItem(SESSION_SEEN_KEY, String(Date.now() - 60_000));
    auth.refresh();
    http.expectOne('/api/auth/status').error(new ProgressEvent('error'));
    expect(auth.state()).toBe('inside');

    localStorage.setItem(SESSION_SEEN_KEY, String(Date.now() - SESSION_SEEN_FOR_MS - 1));
    auth.refresh();
    http.expectOne('/api/auth/status').error(new ProgressEvent('error'));
    expect(auth.state()).toBe('unreachable');
  });

  it('a 401 from any API means the session is over, and the device forgets it', () => {
    answerStatus(true, true, false);

    TestBed.inject(HttpClient)
      .get('/api/accounts')
      .subscribe({ error: () => undefined });
    http.expectOne('/api/accounts').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(auth.state()).toBe('login');
    expect(localStorage.getItem(SESSION_SEEN_KEY)).toBeNull();
  });

  it('a 401 from the PIN itself is a wrong PIN, not a lost session', () => {
    answerStatus(true, true, false);

    auth.login('000000').subscribe({ error: () => undefined });
    http.expectOne('/api/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(auth.state()).toBe('inside');
  });

  it('logging out closes the session and forgets it on the device', () => {
    answerStatus(true, true, false);

    auth.logout();
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });

    expect(auth.state()).toBe('login');
    expect(localStorage.getItem(SESSION_SEEN_KEY)).toBeNull();
  });
});
