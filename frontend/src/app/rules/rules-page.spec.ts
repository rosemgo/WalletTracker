import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Rule, RulePreview, RuleRequest } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { RulesPage } from './rules-page';

const DIVIDEND: Rule = {
  id: 2,
  name: 'Dividendo',
  priority: 10,
  enabled: true,
  matchMode: 'STARTS_WITH',
  pattern: 'Div.su',
  amountSign: 'POSITIVE',
  resultType: 'INVESTMENT_INCOME',
  category: 'Dividendi',
  builtin: true,
  uses: 7,
};

describe('RulesPage', () => {
  const created: RuleRequest[] = [];
  const updated: [number, RuleRequest][] = [];
  let previews = 0;
  let previewed: RuleRequest[];
  let queryParams: Record<string, string>;

  beforeEach(() => {
    previewed = [];
    queryParams = {};
    created.length = 0;
    updated.length = 0;
    previews = 0;
    const fakeApi: Partial<WalletApi> = {
      rules: () => of([DIVIDEND]),
      createRule: (rule: RuleRequest) => {
        created.push(rule);
        return of({ ...DIVIDEND, ...rule, id: 99, builtin: false, uses: 3 });
      },
      updateRule: (id: number, rule: RuleRequest) => {
        updated.push([id, rule]);
        return of({ ...DIVIDEND, ...rule });
      },
      categories: () => of(['Dividendi']),
      previewRule: (rule: RuleRequest) => {
        previewed.push(rule);
        previews++;
        return of<RulePreview>({ matches: 4, examples: [] });
      },
    };
    TestBed.configureTestingModule({
      imports: [RulesPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: fakeApi },
        // L'indirizzo finto: queryParams viene letto quando la pagina parte, quindi un test può
        // cambiarlo prima di createComponent.
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              get queryParamMap() {
                return convertToParamMap(queryParams);
              },
            },
          },
        },
      ],
    });
  });

  /** Scrive in un campo del modulo come farebbe l'utente (l'evento "input" avvisa il Reactive Form). */
  function type(page: HTMLElement, field: string, value: string): void {
    const input = page.querySelector(`[formControlName="${field}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('lists the rules in plain language', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('inizia con «Div.su»');
    expect(text).toContain('solo entrate');
    expect(text).toContain('Cedole e dividendi');
    expect(text).toContain('regola di partenza');
  });

  it('saves a new rule with the values typed in the form', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    type(page, 'name', 'Supermercato');
    type(page, 'pattern', 'ESSELUNGA');
    type(page, 'category', 'Spesa alimentare');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(created).toEqual([
      {
        name: 'Supermercato',
        pattern: 'ESSELUNGA',
        matchMode: 'CONTAINS',
        amountSign: 'ANY',
        resultType: 'EXPENSE',
        category: 'Spesa alimentare',
        priority: 100,
        enabled: true,
      },
    ]);
    expect(page.textContent).toContain('ora classifica 3 movimenti');
  });

  it('does not save or try an incomplete rule, and shows what is missing', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(created).toEqual([]);
    expect(page.textContent).toContain('Scrivi un nome');
  });

  it('"Modifica" fills the form and saving updates the same rule', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    (
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Modifica',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    expect((page.querySelector('[formControlName="pattern"]') as HTMLInputElement).value).toBe(
      'Div.su',
    );

    type(page, 'priority', '5');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(updated.length).toBe(1);
    expect(updated[0][0]).toBe(2);
    expect(updated[0][1].priority).toBe(5);
  });

  it('"Prova" shows how many transactions the rule would match', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    type(page, 'name', 'Supermercato');
    type(page, 'pattern', 'ESSELUNGA');
    (
      Array.from(page.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Prova',
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();

    expect(previews).toBe(1);
    expect(page.textContent).toMatch(/corrisponde a\s*4\s*movimenti/);
  });

  it('fills the form from a correction (address parameters) and tries the rule right away', async () => {
    queryParams = {
      pattern: 'NETFLIX.COM',
      resultType: 'EXPENSE',
      amountSign: 'NEGATIVE',
      category: 'Abbonamenti',
    };
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(previewed).toEqual([
      {
        name: 'Movimenti con "NETFLIX.COM"',
        pattern: 'NETFLIX.COM',
        matchMode: 'CONTAINS',
        amountSign: 'NEGATIVE',
        resultType: 'EXPENSE',
        category: 'Abbonamenti',
        priority: 100,
        enabled: true,
      },
    ]);
    expect(page.textContent).toContain('Modulo compilato dalla tua correzione');
    expect(created).toEqual([]); // salva solo l'utente
  });

  it('ignores unknown values in the address', async () => {
    queryParams = { pattern: 'BAR', resultType: 'BOH', amountSign: '<script>' };
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();

    expect(previewed[0].resultType).toBe('EXPENSE'); // il valore predefinito
    expect(previewed[0].amountSign).toBe('ANY');
  });
});
