import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthStatus } from '../api/models';
import { authInterceptor } from './auth-interceptor';
import { AuthStore, SESSION_SEEN_FOR_MS, SESSION_SEEN_KEY } from './auth-store';

/**
 * Come si entra, e l'intercettore delle sessioni scadute (docs/46, parte F), con il finto server di HttpClient.
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

  function answerStatus(status: Partial<AuthStatus>): void {
    auth.refresh();
    http.expectOne('/api/auth/status').flush({
      pinSet: false,
      withoutPin: false,
      authenticated: false,
      local: false,
      ...status,
    });
  }

  it('decides what to show from the status', () => {
    expect(auth.state()).toBe('checking');
    answerStatus({ local: true });
    expect(auth.state()).toBe('choose');
    answerStatus({ local: false });
    expect(auth.state()).toBe('elsewhere');
    answerStatus({ withoutPin: true, local: false });
    expect(auth.state()).toBe('elsewhere');
    answerStatus({ withoutPin: true, authenticated: true, local: true });
    expect(auth.state()).toBe('inside');
    answerStatus({ pinSet: true });
    expect(auth.state()).toBe('login');
    answerStatus({ pinSet: true, authenticated: true });
    expect(auth.state()).toBe('inside');
    expect(Number(localStorage.getItem(SESSION_SEEN_KEY))).toBeGreaterThan(0);
  });

  it('creating the PIN gives the recovery code and waits before entering', () => {
    answerStatus({ local: true });
    let code = '';
    auth.create('135792').subscribe((answer) => (code = answer));

    const request = http.expectOne('/api/auth/pin');
    expect(request.request.body).toEqual({ pin: '135792' });
    request.flush({ recoveryCode: 'K7QM-2XPA-9RWD-4TNE' });

    expect(code).toBe('K7QM-2XPA-9RWD-4TNE');
    expect(auth.state()).toBe('choose'); // prima si salva il codice
    expect(auth.status()?.pinSet).toBe(true);
    auth.enter();
    expect(auth.state()).toBe('inside');
  });

  it('choosing "without PIN" asks the status again, and the PC is inside', () => {
    answerStatus({ local: true });
    auth.chooseWithoutPin().subscribe();
    http.expectOne('/api/auth/no-pin').flush(null, { status: 204, statusText: 'No Content' });
    http
      .expectOne('/api/auth/status')
      .flush({ pinSet: false, withoutPin: true, authenticated: true, local: true });
    expect(auth.state()).toBe('inside');
  });

  it('logging in sends the PIN and then shows the app', () => {
    answerStatus({ pinSet: true });
    auth.login('135792').subscribe();
    const request = http.expectOne('/api/auth/login');
    expect(request.request.body).toEqual({ pin: '135792' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    expect(auth.state()).toBe('inside');
  });

  it('the recovery sends the code and the new PIN, and gives the new code', () => {
    answerStatus({ pinSet: true });
    let code = '';
    auth.recover('k7qm 2xpa 9rwd 4tne', '112233').subscribe((answer) => (code = answer));
    const request = http.expectOne('/api/auth/recover');
    expect(request.request.body).toEqual({ code: 'k7qm 2xpa 9rwd 4tne', pin: '112233' });
    request.flush({ recoveryCode: 'NEWC-ODEA-BCDE-FGHJ' });
    expect(code).toBe('NEWC-ODEA-BCDE-FGHJ');
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

  it('a 401 from any API means the session is over: the device forgets it and asks again', () => {
    answerStatus({ pinSet: true, authenticated: true });

    let failed = false;
    let completed = false;
    TestBed.inject(HttpClient)
      .get('/api/accounts')
      .subscribe({ error: () => (failed = true), complete: () => (completed = true) });
    http.expectOne('/api/accounts').flush({}, { status: 401, statusText: 'Unauthorized' });

    // Il 401 lo gestisce l'intercettore: la pagina non riceve un errore (che finirebbe nella console), la richiesta
    // si chiude e basta.
    expect(failed).toBe(false);
    expect(completed).toBe(true);

    expect(localStorage.getItem(SESSION_SEEN_KEY)).toBeNull();
    http.expectOne('/api/auth/status').flush({
      pinSet: true,
      withoutPin: false,
      authenticated: false,
      local: false,
    });
    expect(auth.state()).toBe('login');
  });

  it('a 401 from the PIN itself is a wrong PIN, not a lost session', () => {
    answerStatus({ pinSet: true, authenticated: true });

    auth.login('000000').subscribe({ error: () => undefined });
    http.expectOne('/api/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(auth.state()).toBe('inside');
  });

  it('logging out closes the session and forgets it on the device', () => {
    answerStatus({ pinSet: true, authenticated: true });

    auth.logout();
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });

    expect(auth.state()).toBe('login');
    expect(localStorage.getItem(SESSION_SEEN_KEY)).toBeNull();
  });
});
