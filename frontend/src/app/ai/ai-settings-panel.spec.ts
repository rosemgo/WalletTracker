import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { AiProvider, AiSettingsChange, AiStatus, ModelEvaluation } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AiSettingsPanel } from './ai-settings-panel';

const OFF: AiStatus = {
  provider: 'NONE',
  ollamaModel: 'qwen2.5:3b',
  ollamaReachable: true,
  installedModels: [],
  modelInstalled: false,
  download: null,
  evaluation: null,
  gemini: { model: 'gemini-flash-latest', keySet: false, keyHint: null, consent: false },
};

/** Una misura: 5 esercenti, `done` fatti. */
function evaluation(
  done: number,
  right: number,
  extra: Partial<ModelEvaluation> = {},
): ModelEvaluation {
  return {
    model: 'llama3.2:3b',
    running: done < 5,
    done,
    total: 5,
    right,
    wrong: done - right,
    unsure: 0,
    seconds: 4.25,
    mistakes: [],
    error: null,
    ...extra,
  };
}

/** Test del pannello con un WalletApi finto (vedi accounts-page.spec.ts). */
describe('AiSettingsPanel', () => {
  let status: AiStatus;
  let changes: [AiProvider, string][];
  let downloads: number;
  let afterDownload: AiStatus[];
  let evaluations: [AiProvider, string, number][];
  let evaluationResponse: () => Observable<AiStatus>;

  beforeEach(() => {
    status = OFF;
    changes = [];
    downloads = 0;
    afterDownload = [];
    evaluations = [];
    evaluationResponse = () => of({ ...status, evaluation: evaluation(0, 0) });
    const fakeApi: Partial<WalletApi> = {
      aiStatus: () => of(afterDownload.length > 0 ? afterDownload.shift()! : status),
      changeAi: (change: AiSettingsChange): Observable<AiStatus> => {
        changes.push([change.provider, change.ollamaModel]);
        status = { ...status, provider: change.provider, ollamaModel: change.ollamaModel };
        return of(status);
      },
      downloadAiModel: () => {
        downloads++;
        return of({
          ...status,
          download: {
            model: 'qwen2.5:3b',
            running: true,
            status: 'downloading',
            completed: 512 * 1024 ** 2,
            total: 2 * 1024 ** 3,
            error: null,
          },
        });
      },
      evaluateAi: (provider: AiProvider, model: string, samples: number) => {
        evaluations.push([provider, model, samples]);
        return evaluationResponse();
      },
    };
    TestBed.configureTestingModule({
      imports: [AiSettingsPanel],
      providers: [{ provide: WalletApi, useValue: fakeApi }],
    });
  });

  afterEach(() => vi.useRealTimers());

  function button(page: HTMLElement, text: string): HTMLButtonElement {
    return Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().startsWith(text),
    ) as HTMLButtonElement;
  }

  it('is off by default, and turning it on saves the choice and the model', async () => {
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const select = page.querySelector('select') as HTMLSelectElement;
    expect(select.value).toBe('NONE');

    select.value = 'OLLAMA';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const model = page.querySelector('input') as HTMLInputElement;
    expect(model.value).toBe('qwen2.5:3b');
    model.value = ' llama3.2 ';
    model.dispatchEvent(new Event('input'));
    button(page, 'Salva').click();
    await fixture.whenStable();

    expect(changes).toEqual([['OLLAMA', 'llama3.2']]);
    expect(page.textContent).toContain("Suggerimenti con l'IA accesi, con il modello llama3.2");
  });

  it('says when Ollama does not answer, with how to start it', async () => {
    status = { ...OFF, provider: 'OLLAMA', ollamaReachable: false };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent!;

    expect(text).toContain('✗ Ollama non risponde');
    expect(text).toContain('docker start ollama'); // di solito basta riavviarlo
    expect(text).toContain('--restart unless-stopped'); // e la prima volta, crearlo così
    expect(text).not.toContain('Scarica il modello');
  });

  it('downloads the missing model and follows the progress until it is there', async () => {
    vi.useFakeTimers();
    status = { ...OFF, provider: 'OLLAMA' };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    expect(page.textContent).toContain('✗ Il modello qwen2.5:3b non è scaricato');

    // Le risposte che il polling riceverà dopo l'avvio: ancora in corso, poi finito.
    afterDownload = [
      {
        ...status,
        download: {
          model: 'qwen2.5:3b',
          running: true,
          status: 'downloading',
          completed: 1024 ** 3,
          total: 2 * 1024 ** 3,
          error: null,
        },
      },
      {
        ...status,
        installedModels: ['qwen2.5:3b'],
        modelInstalled: true,
        download: {
          model: 'qwen2.5:3b',
          running: false,
          status: 'success',
          completed: null,
          total: null,
          error: null,
        },
      },
    ];
    button(page, 'Scarica il modello').click();
    fixture.detectChanges();
    expect(downloads).toBe(1);
    expect(page.textContent).toContain('25% (512 MB di 2,0 GB)');

    vi.advanceTimersByTime(1000); // il polling: ancora in corso
    fixture.detectChanges();
    expect(page.textContent).toContain('50% (1,0 GB di 2,0 GB)');

    vi.advanceTimersByTime(1000); // finito: il polling si ferma
    fixture.detectChanges();
    expect(page.textContent).toContain('✓ Il modello qwen2.5:3b è scaricato');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('an old download error is not shown when the model is there', async () => {
    status = {
      ...OFF,
      provider: 'OLLAMA',
      installedModels: ['qwen2.5:3b'],
      modelInstalled: true,
      download: {
        model: 'qwen2.5:3b',
        running: false,
        status: 'errore',
        completed: null,
        total: null,
        error: 'Ollama ha finito senza scaricare il modello.',
      },
    };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent!;

    expect(text).toContain('✓ Il modello qwen2.5:3b è scaricato');
    expect(text).not.toContain('Lo scaricamento non è riuscito');
  });

  it('explains a failed download', async () => {
    status = {
      ...OFF,
      provider: 'OLLAMA',
      download: {
        model: 'qwen2.5:3b',
        running: false,
        status: 'errore',
        completed: null,
        total: null,
        error: 'pull model manifest: Forbidden',
      },
    };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('.error')?.textContent).toContain(
      'pull model manifest: Forbidden',
    );
  });

  it('a recommended model fills the model field', async () => {
    status = { ...OFF, provider: 'OLLAMA' };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('gemma3:4b');
    expect(page.textContent).toContain('llama3.2:3b');

    // La prima riga della tabella è gemma3:4b: "Usa questo" lo scrive nel campo.
    const row = page.querySelector('table[aria-label="Modelli consigliati"] tbody tr')!;
    (row.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect((page.querySelector('input') as HTMLInputElement).value).toBe('gemma3:4b');
    expect((row.querySelector('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('measures the chosen model and follows the progress until it is done', async () => {
    vi.useFakeTimers();
    status = {
      ...OFF,
      provider: 'OLLAMA',
      ollamaModel: 'gemma3:4b',
      installedModels: ['gemma3:4b', 'llama3.2:3b'],
      modelInstalled: true,
    };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    // Il modello proposto per la misura è quello scelto; ne scegliamo un altro, su 5 esercenti.
    const selects = Array.from(page.querySelectorAll('select'));
    const modelSelect = selects[selects.length - 1];
    expect(modelSelect.value).toBe('OLLAMA|gemma3:4b');
    modelSelect.value = 'OLLAMA|llama3.2:3b';
    modelSelect.dispatchEvent(new Event('change'));
    const samples = page.querySelector('input[type="number"]') as HTMLInputElement;
    samples.value = '5';
    samples.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    afterDownload = [
      { ...status, evaluation: evaluation(2, 2) },
      {
        ...status,
        evaluation: evaluation(5, 4, {
          mistakes: [
            { merchant: 'NETFLIX.COM', expected: 'Intrattenimento', answer: 'Multimedia' },
          ],
        }),
      },
    ];
    button(page, 'Misura').click();
    fixture.detectChanges();
    expect(evaluations).toEqual([['OLLAMA', 'llama3.2:3b', 5]]);
    expect(page.textContent).toContain('in corso: 0 di 5');
    expect(button(page, 'Misura').disabled).toBe(true);

    vi.advanceTimersByTime(1000); // il polling: ancora in corso
    fixture.detectChanges();
    expect(page.textContent).toContain('in corso: 2 di 5');
    expect(page.textContent).toContain('100% giuste');

    vi.advanceTimersByTime(1000); // finita: il polling si ferma
    fixture.detectChanges();
    expect(page.textContent).toContain('Misura di llama3.2:3b: 5 esercenti.');
    expect(page.textContent).toContain('80% giuste');
    expect(page.textContent).toContain('4,3 secondi a domanda');
    const mistake = page.querySelector('table[aria-label="Errori del modello"] tbody tr')!;
    expect(mistake.textContent).toContain('NETFLIX.COM');
    expect(mistake.textContent).toContain('Multimedia');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('says why the measurement did not start', async () => {
    status = { ...OFF, provider: 'OLLAMA', installedModels: ['gemma3:4b'], modelInstalled: true };
    evaluationResponse = () =>
      throwError(() => ({ error: { detail: "Non c'è niente da misurare: ..." } }));
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    button(page, 'Misura').click();
    await fixture.whenStable();

    expect(page.querySelector('.error')?.textContent).toContain("Non c'è niente da misurare");
  });

  it('without downloaded models there is nothing to measure', async () => {
    status = { ...OFF, provider: 'OLLAMA' };
    const fixture = TestBed.createComponent(AiSettingsPanel);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    const headings = Array.from(page.querySelectorAll('h3')).map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Stato']);
    expect(button(page, 'Misura')).toBeUndefined();
  });

  describe('with a cloud service', () => {
    it('choosing Gemini shows its section, and saving sends models and consent', async () => {
      status = { ...OFF, gemini: { ...OFF.gemini, keySet: true, keyHint: '…XYZ9' } };
      let sent: AiSettingsChange | null = null;
      TestBed.overrideProvider(WalletApi, {
        useValue: {
          aiStatus: () => of(status),
          changeAi: (change: AiSettingsChange) => {
            sent = change;
            return of({
              ...status,
              provider: change.provider,
              gemini: { ...status.gemini, consent: true },
            });
          },
        },
      });
      const fixture = TestBed.createComponent(AiSettingsPanel);
      await fixture.whenStable();
      const page = fixture.nativeElement as HTMLElement;
      const select = page.querySelector('select') as HTMLSelectElement;

      select.value = 'GEMINI';
      select.dispatchEvent(new Event('change'));
      await fixture.whenStable();
      expect(page.querySelector('app-cloud-ai-section')?.textContent).toContain('Google Gemini');
      const box = page.querySelector(
        'app-cloud-ai-section input[type="checkbox"]',
      ) as HTMLInputElement;
      box.checked = true;
      box.dispatchEvent(new Event('change'));
      button(page, 'Salva').click();
      await fixture.whenStable();

      expect(sent).toEqual({
        provider: 'GEMINI',
        ollamaModel: 'qwen2.5:3b',
        geminiModel: 'gemini-flash-latest',
        consent: true,
      });
      expect(page.textContent).toContain(
        "Suggerimenti con l'IA accesi: Google Gemini, modello gemini-flash-latest.",
      );
    });

    it('a ready cloud service can be measured', async () => {
      status = {
        ...OFF,
        provider: 'GEMINI',
        ollamaReachable: false,
        gemini: { model: 'gemini-flash-latest', keySet: true, keyHint: '…1234', consent: true },
      };
      const fixture = TestBed.createComponent(AiSettingsPanel);
      await fixture.whenStable();
      const page = fixture.nativeElement as HTMLElement;
      const selects = Array.from(page.querySelectorAll('select'));
      const target = selects[selects.length - 1];

      expect(Array.from(target.options).map((o) => o.textContent?.trim())).toEqual([
        'Google Gemini · gemini-flash-latest',
      ]);
      button(page, 'Misura').click();
      await fixture.whenStable();
      expect(evaluations).toEqual([['GEMINI', 'gemini-flash-latest', 20]]);
    });
  });
});
