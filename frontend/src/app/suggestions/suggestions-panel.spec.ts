import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { CategorySuggestion } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { SuggestionsPanel } from './suggestions-panel';

const PIZZA: CategorySuggestion = {
  merchantKey: 'PIZZERIA DA GINO',
  merchantName: 'Pizzeria da Gino',
  categoryId: 5,
  category: 'Bar e ristoranti',
  transactions: 3,
  examples: 1,
};

/** Test del pannello con un WalletApi finto (vedi accounts-page.spec.ts). */
describe('SuggestionsPanel', () => {
  let suggestions: CategorySuggestion[];
  let accepted: [string, number][];
  let dismissed: [string, number][];
  let acceptResponse: Observable<{ updated: number }>;

  beforeEach(() => {
    suggestions = [PIZZA];
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
    expect(page.querySelector('table')).toBeNull(); // niente più da suggerire
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
});
