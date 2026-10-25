import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { AiAnswerJob, AiSuggestionsStatus, CategorySuggestion } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { SuggestionsPanel } from './suggestions-panel';

const PIZZA: CategorySuggestion = {
  merchantKey: 'PIZZERIA DA GINO',
  merchantName: 'Pizzeria da Gino',
  categoryId: 5,
  category: 'Bar e ristoranti',
  transactions: 3,
  examples: 1,
  model: null,
};

const CONAD_BY_AI: CategorySuggestion = {
  merchantKey: 'CONAD SUPERSTORE',
  merchantName: 'CONAD SUPERSTORE',
  categoryId: 1,
  category: 'Spesa alimentare',
  transactions: 2,
  examples: 0,
  model: 'gemma3:4b',
};

const AI_OFF: AiSuggestionsStatus = { enabled: false, model: 'gemma3:4b', toAsk: 0, job: null };

function job(done: number, found: number, extra: Partial<AiAnswerJob> = {}): AiAnswerJob {
  return { model: 'gemma3:4b', running: done < 3, done, total: 3, found, error: null, ...extra };
}

/** Test del pannello con un WalletApi finto (vedi accounts-page.spec.ts). */
describe('SuggestionsPanel', () => {
  let suggestions: CategorySuggestion[];
  let accepted: [string, number][];
  let dismissed: [string, number][];
  let acceptResponse: Observable<{ updated: number }>;
  let ai: AiSuggestionsStatus[]; // le risposte di GET /api/ai/suggestions, in ordine (l'ultima resta)
  let asked: number;
  let askResponse: () => Observable<AiSuggestionsStatus>;

  beforeEach(() => {
    suggestions = [PIZZA];
    ai = [AI_OFF];
    asked = 0;
    askResponse = () => of({ ...ai[0], toAsk: 0, job: job(0, 0) });
    accepted = [];
    dismissed = [];
    acceptResponse = of({ updated: 3 });
    const fakeApi: Partial<WalletApi> = {
      categorySuggestions: () => of(suggestions),
      acceptCategorySuggestion: (merchantKey: string, categoryId: number) => {
        accepted.push([merchantKey, categoryId]);
        suggestions = [];
        return acceptResponse;
      },
      dismissCategorySuggestion: (merchantKey: string, categoryId: number) => {
        dismissed.push([merchantKey, categoryId]);
        suggestions = [];
        return of(undefined);
      },
      aiSuggestionsStatus: () => of(ai.length > 1 ? ai.shift()! : ai[0]),
      askAi: () => {
        asked++;
        return askResponse();
      },
    };
    TestBed.configureTestingModule({
      imports: [SuggestionsPanel],
      providers: [{ provide: WalletApi, useValue: fakeApi }],
    });
  });

  function button(page: HTMLElement, text: string): HTMLButtonElement {
    return Array.from(page.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  it('shows each suggestion with the merchant, the category and why', async () => {
    const fixture = TestBed.createComponent(SuggestionsPanel);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');

    expect(text).toContain('Pizzeria da Gino');
    expect(text).toContain('Bar e ristoranti');
    expect(text).toContain('3 movimenti senza categoria');
    expect(text).toContain("l'hai scelta per 1 movimento");
  });

  it('accepting assigns the category and tells the page to reload', async () => {
    const fixture = TestBed.createComponent(SuggestionsPanel);
    let reloads = 0;
    fixture.componentInstance.accepted.subscribe(() => reloads++);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    button(page, 'Accetta').click();
    await fixture.whenStable();

    expect(accepted).toEqual([['PIZZERIA DA GINO', 5]]);
    expect(reloads).toBe(1);
    expect(page.textContent).toContain('"Bar e ristoranti" assegnata a 3 movimenti');
    expect(page.querySelector('.suggestion-list')).toBeNull(); // niente più da suggerire
  });

  it('"no" remembers the refusal without telling the page to reload', async () => {
    const fixture = TestBed.createComponent(SuggestionsPanel);
    let reloads = 0;
    fixture.componentInstance.accepted.subscribe(() => reloads++);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    button(page, 'no').click();
    await fixture.whenStable();

    expect(dismissed).toEqual([['PIZZERIA DA GINO', 5]]);
    expect(reloads).toBe(0);
    expect(page.textContent).toContain('non verrà più suggerita');
  });

  it('shows nothing when there is nothing to suggest', async () => {
    suggestions = [];
    const fixture = TestBed.createComponent(SuggestionsPanel);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
  });

  it('shows the reason when accepting fails', async () => {
    acceptResponse = throwError(() => ({ error: { detail: 'La categoria 5 non esiste' } }));
    const fixture = TestBed.createComponent(SuggestionsPanel);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    button(page, 'Accetta').click();
    await fixture.whenStable();

    expect(page.querySelector('.error')?.textContent).toContain('La categoria 5 non esiste');
  });

  describe('with the AI', () => {
    afterEach(() => vi.useRealTimers());

    it('marks the suggestions of the AI and says which model', async () => {
      suggestions = [PIZZA, CONAD_BY_AI];
      ai = [{ enabled: true, model: 'gemma3:4b', toAsk: 0, job: null }];
      const fixture = TestBed.createComponent(SuggestionsPanel);
      await fixture.whenStable();
      const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.suggestion-list li');

      expect(rows[0].querySelector('.ai-tag')).toBeNull(); // dalla tua storia
      expect(rows[1].querySelector('.ai-tag')?.textContent).toBe('IA');
      expect(rows[1].textContent).toContain("suggerita dall'IA (gemma3:4b)");
    });

    it('without suggestions it still offers to ask the AI', async () => {
      suggestions = [];
      ai = [{ enabled: true, model: 'gemma3:4b', toAsk: 4, job: null }];
      const fixture = TestBed.createComponent(SuggestionsPanel);
      await fixture.whenStable();
      const page = fixture.nativeElement as HTMLElement;

      expect(page.textContent).toContain(
        "4 esercenti senza categoria non sono ancora stati chiesti all'IA",
      );
      expect(page.querySelector('.suggestion-list')).toBeNull();
      expect(button(page, "Chiedi all'IA")).toBeTruthy();
    });

    it('asks, follows the progress and then shows the new suggestions', async () => {
      vi.useFakeTimers();
      suggestions = [];
      ai = [
        { enabled: true, model: 'gemma3:4b', toAsk: 3, job: null },
        { enabled: true, model: 'gemma3:4b', toAsk: 0, job: job(2, 1) }, // il polling: ancora in corso
        { enabled: true, model: 'gemma3:4b', toAsk: 0, job: job(3, 2) }, // finito
      ];
      const fixture = TestBed.createComponent(SuggestionsPanel);
      const page = fixture.nativeElement as HTMLElement;
      fixture.detectChanges();

      button(page, "Chiedi all'IA").click();
      fixture.detectChanges();
      expect(asked).toBe(1);
      expect(page.textContent).toContain('sta guardando gli esercenti: 0 di 3');
      expect(page.textContent).toContain('La prima risposta può richiedere di più');

      vi.advanceTimersByTime(2000);
      fixture.detectChanges();
      expect(page.textContent).toContain('2 di 3');
      expect(page.textContent).not.toContain('La prima risposta');

      suggestions = [CONAD_BY_AI]; // le risposte salvate sono diventate suggerimenti
      vi.advanceTimersByTime(2000);
      fixture.detectChanges();
      expect(page.textContent).toContain(
        "L'IA ha guardato 3 esercenti: 2 suggerimenti, per 1 non sa.",
      );
      expect(page.textContent).toContain('Conad Superstore');
      expect(vi.getTimerCount()).toBe(0);
    });

    it('says why it could not ask', async () => {
      suggestions = [];
      ai = [{ enabled: true, model: 'gemma3:4b', toAsk: 2, job: null }];
      askResponse = () =>
        throwError(() => ({ error: { detail: 'Ollama non risponde: è avviato?' } }));
      const fixture = TestBed.createComponent(SuggestionsPanel);
      await fixture.whenStable();
      const page = fixture.nativeElement as HTMLElement;

      button(page, "Chiedi all'IA").click();
      await fixture.whenStable();

      expect(page.querySelector('.error')?.textContent).toContain('Ollama non risponde');
    });

    it('with the AI off there is no button', async () => {
      ai = [AI_OFF];
      const fixture = TestBed.createComponent(SuggestionsPanel);
      await fixture.whenStable();

      expect(button(fixture.nativeElement as HTMLElement, "Chiedi all'IA")).toBeUndefined();
    });
  });
});
