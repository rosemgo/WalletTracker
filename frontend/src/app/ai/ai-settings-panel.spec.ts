import { TestBed } from '@angular/core/testing';
import { Observable, of } from 'rxjs';

import { AiProvider, AiStatus } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { AiSettingsPanel } from './ai-settings-panel';

const OFF: AiStatus = {
  provider: 'NONE',
  ollamaModel: 'qwen2.5:3b',
  ollamaReachable: true,
  installedModels: [],
  modelInstalled: false,
  download: null,
};

/** Test del pannello con un WalletApi finto (vedi accounts-page.spec.ts). */
describe('AiSettingsPanel', () => {
  let status: AiStatus;
  let changes: [AiProvider, string][];
  let downloads: number;
  let afterDownload: AiStatus[];

  beforeEach(() => {
    status = OFF;
    changes = [];
    downloads = 0;
    afterDownload = [];
    const fakeApi: Partial<WalletApi> = {
      aiStatus: () => of(afterDownload.length > 0 ? afterDownload.shift()! : status),
      changeAi: (provider: AiProvider, model: string): Observable<AiStatus> => {
        changes.push([provider, model]);
        status = { ...status, provider, ollamaModel: model };
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
    expect(text).toContain('docker run');
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
});
