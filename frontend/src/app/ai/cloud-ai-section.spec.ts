import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { AiCloudProvider, AiCloudStatus, AiConnectionTest, AiStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CloudAiSection } from './cloud-ai-section';

const NO_KEY: AiCloudStatus = {
  model: 'gemini-flash-latest',
  keySet: false,
  keyHint: null,
  consent: false,
};
const READY: AiCloudStatus = {
  model: 'gemini-flash-latest',
  keySet: true,
  keyHint: '…XYZ9',
  consent: true,
};

/** La sezione di un servizio cloud, con un WalletApi finto (vedi accounts-page.spec.ts). */
describe('CloudAiSection', () => {
  let savedKeys: [AiCloudProvider, string][];
  let deleted: AiCloudProvider[];
  let saveResponse: () => Observable<AiStatus>;
  let testResponse: Observable<AiConnectionTest>;

  beforeEach(() => {
    savedKeys = [];
    deleted = [];
    saveResponse = () => of({ gemini: READY } as AiStatus);
    testResponse = of({ ok: true, answer: 'Spesa alimentare', seconds: 1.234, error: null });
    const fakeApi: Partial<WalletApi> = {
      saveAiKey: (provider: AiCloudProvider, apiKey: string) => {
        savedKeys.push([provider, apiKey]);
        return saveResponse();
      },
      deleteAiKey: (provider: AiCloudProvider) => {
        deleted.push(provider);
        return of({ gemini: NO_KEY } as AiStatus);
      },
      testAiConnection: () => testResponse,
    };
    TestBed.configureTestingModule({
      imports: [CloudAiSection],
      providers: [{ provide: WalletApi, useValue: fakeApi }],
    });
  });

  async function render(cloud: AiCloudStatus, provider: AiCloudProvider = 'GEMINI') {
    const fixture = TestBed.createComponent(CloudAiSection);
    fixture.componentRef.setInput('provider', provider);
    fixture.componentRef.setInput('cloud', cloud);
    fixture.componentRef.setInput('aiModel', cloud.model);
    await fixture.whenStable();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  function button(page: HTMLElement, text: string): HTMLButtonElement {
    return Array.from(page.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  it('explains where to get the key and what is sent', async () => {
    const { page } = await render(NO_KEY);
    const text = page.textContent!.replace(/\s+/g, ' ');

    expect(text).toContain('Come avere la chiave di Google Gemini');
    expect(page.querySelector('a')?.getAttribute('href')).toBe('https://aistudio.google.com');
    expect(text).toContain('Non vengono inviati importi, date, IBAN, numeri di carta, il tuo nome');
  });

  it('Gemini warns about the free plan', async () => {
    const { page } = await render(NO_KEY);

    expect(page.textContent).toContain('con il piano gratuito Google può usare i dati inviati');
  });

  it('saves the key, clears the field and tells the panel', async () => {
    const { fixture, page } = await render(NO_KEY);
    const emitted: AiStatus[] = [];
    fixture.componentInstance.statusChange.subscribe((s) => emitted.push(s));
    const field = page.querySelector('input[type="password"]') as HTMLInputElement;
    expect(button(page, 'Salva la chiave').disabled).toBe(true); // vuota

    field.value = ' my-secret-key-123 ';
    field.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(page, 'Salva la chiave').click();
    await fixture.whenStable();

    expect(savedKeys).toEqual([['GEMINI', 'my-secret-key-123']]);
    expect(emitted.length).toBe(1);
    expect(field.value).toBe(''); // la chiave non resta nella pagina
  });

  it('a saved key is shown only by its last letters, and can be removed', async () => {
    const { fixture, page } = await render(READY);

    expect(page.textContent).toContain('✓ Chiave salvata (…XYZ9)');
    expect(page.querySelector('input[type="password"]')).toBeNull();
    button(page, 'togli').click();
    await fixture.whenStable();
    expect(deleted).toEqual(['GEMINI']);
  });

  it('a key with wrong characters is explained', async () => {
    saveResponse = () => throwError(() => ({ status: 400 }));
    const { fixture, page } = await render(NO_KEY);
    const field = page.querySelector('input[type="password"]') as HTMLInputElement;
    field.value = 'not a key at all';
    field.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    button(page, 'Salva la chiave').click();
    await fixture.whenStable();

    expect(page.querySelector('.error')?.textContent).toContain('Questa non sembra una chiave');
  });

  it('the consent checkbox changes the consent the panel will save', async () => {
    const { fixture, page } = await render({ ...NO_KEY, keySet: true, keyHint: '…XYZ9' });
    const box = page.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(fixture.componentInstance.consent()).toBe(false);
    expect(button(page, 'Prova la connessione').disabled).toBe(true); // prima chiave e consenso

    box.checked = true;
    box.dispatchEvent(new Event('change'));

    expect(fixture.componentInstance.consent()).toBe(true);
  });

  it('the connection test shows the answer, or why it failed', async () => {
    const { fixture, page } = await render(READY);

    button(page, 'Prova la connessione').click();
    await fixture.whenStable();
    expect(page.textContent).toContain(
      '✓ Google Gemini risponde: ESSELUNGA → Spesa alimentare (1,2 s)',
    );

    testResponse = of({
      ok: false,
      answer: null,
      seconds: 0.3,
      error: 'La chiave di Google Gemini non è valida.',
    });
    button(page, 'Prova la connessione').click();
    await fixture.whenStable();
    expect(page.querySelector('.error')?.textContent).toContain(
      'La chiave di Google Gemini non è valida',
    );
  });
});
