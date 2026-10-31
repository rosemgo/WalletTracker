import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Category, Rule, RulePreview, RuleRequest } from '../api/models';
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
  categoryId: 3,
  category: 'Dividendi',
  builtin: true,
  uses: 7,
};

const CATEGORIES: Category[] = [
  { id: 1, name: 'Spesa alimentare', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 2, name: 'Intrattenimento e tempo libero', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 3, name: 'Dividendi', kind: 'INVESTMENT', builtin: true, uses: 7 },
];

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
      categories: () => of(CATEGORIES),
      previewRule: (rule: RuleRequest) => {
        previewed.push(rule);
        previews++;
        // 4 corrispondono; 3 li prende una regola che viene prima, 1 è stato scelto a mano.
        return of<RulePreview>({
          matches: 4,
          applies: 0,
          blockers: [
            {
              reason: 'RULE',
              rule: 'Trasferimento a un proprio conto',
              priority: 40,
              movements: 3,
            },
            { reason: 'MANUAL', rule: null, priority: null, movements: 1 },
          ],
          examples: [],
        });
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

  /**
   * Sceglie in un menu del modulo la voce con questo testo, come farebbe l'utente. Cerchiamo per testo
   * perché con [ngValue] il valore HTML di un'opzione non è l'id (Angular ci mette un suo codice).
   */
  function choose(page: HTMLElement, field: string, text: string): void {
    const select = page.querySelector(`[formControlName="${field}"]`) as HTMLSelectElement;
    select.selectedIndex = Array.from(select.options).findIndex(
      (o) => o.textContent?.trim() === text,
    );
    select.dispatchEvent(new Event('change'));
  }

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
    choose(page, 'categoryId', 'Spesa alimentare');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(created).toEqual([
      {
        name: 'Supermercato',
        pattern: 'ESSELUNGA',
        matchMode: 'CONTAINS',
        amountSign: 'ANY',
        resultType: 'EXPENSE',
        categoryId: 1, // Spesa alimentare
        priority: 100,
        enabled: true,
      },
    ]);
    expect(page.textContent).toContain('ora classifica 3 movimenti');
  });

  it('offers "contiene tutte le parole" and explains each mode', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const patternLabel = () =>
      (page.querySelector('[formControlName="pattern"]') as HTMLElement).closest('label')!
        .textContent;

    expect(patternLabel()).toContain("in quest'ordine e una dopo l'altra");
    type(page, 'name', 'Decò');
    type(page, 'pattern', 'MAR GROUP FOGLIANISE');
    choose(page, 'matchMode', 'contiene tutte le parole');
    await fixture.whenStable();
    expect(patternLabel()).toContain('parole intere, in qualsiasi ordine');

    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(created[0].matchMode).toBe('ALL_WORDS');
    expect(created[0].pattern).toBe('MAR GROUP FOGLIANISE');
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
    // jsdom non sa scorrere: lo finge, per vedere che "Modifica" porta il modulo in vista.
    const scrolled: Element[] = [];
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this);
    };
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
    expect(scrolled.map((e) => e.id)).toEqual(['rule-form']);

    type(page, 'priority', '5');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(updated.length).toBe(1);
    expect(updated[0][0]).toBe(2);
    expect(updated[0][1].priority).toBe(5);
  });

  it('after saving, warns when another rule wins on some movements', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    type(page, 'name', 'PAC');
    type(page, 'pattern', 'IT00X0000000000000000000009');
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    // La prova rifatta da sola dopo il salvataggio, per la regola salvata.
    expect(previews).toBe(1);
    const warning = page.querySelector('.card.warning')?.textContent?.replace(/\s+/g, ' ') ?? '';
    expect(warning).toContain('la regola «PAC» è salvata, ma');
    expect(warning).toContain(
      'vince la regola «Trasferimento a un proprio conto», che ha priorità 40',
    );
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
    const text = page.textContent?.replace(/\s+/g, ' ') ?? '';
    expect(text).toMatch(/corrisponde a 4 movimenti/);
    // Corrispondere non basta: quanti ne classificherebbe davvero, e chi vince sugli altri.
    expect(text).toMatch(/La regola ne classificherebbe 0/);
    expect(text).toContain(
      '3 movimenti: vince la regola «Trasferimento a un proprio conto», che ha priorità 40',
    );
    expect(text).toContain('dalle una priorità più bassa di 40');
    expect(text).toMatch(/1 movimento: il tipo è stato scelto a mano/);
  });

  it('fills the form from a correction (address parameters) and tries the rule right away', async () => {
    queryParams = {
      pattern: 'NETFLIX.COM',
      resultType: 'EXPENSE',
      amountSign: 'NEGATIVE',
      categoryId: '2', // nell'indirizzo tutto è testo
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
        categoryId: 2, // Intrattenimento e tempo libero
        priority: 100,
        enabled: true,
      },
    ]);
    expect(page.textContent).toContain('Modulo compilato dalla tua correzione');
    expect(created).toEqual([]); // salva solo l'utente
  });

  it('ignores unknown values in the address', async () => {
    queryParams = { pattern: 'BAR', resultType: 'BOH', amountSign: '<script>', categoryId: '-1' };
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();

    expect(previewed[0].resultType).toBe('EXPENSE'); // il valore predefinito
    expect(previewed[0].amountSign).toBe('ANY');
    expect(previewed[0].categoryId).toBeNull();
  });

  it('shows your rules first and keeps the built-in ones in a section to open', async () => {
    const fixture = TestBed.createComponent(RulesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    // Solo la regola di partenza dei dividendi: le tue sono zero, e lo dice.
    expect(page.textContent).toContain('Nessuna regola tua');
    const builtin = page.querySelector('details.builtin-rules') as HTMLDetailsElement;
    expect(builtin.open).toBe(false);
    expect(builtin.querySelector('summary')?.textContent).toContain('Regole di partenza (1)');
    expect(builtin.querySelectorAll('tbody tr').length).toBe(1);
  });
});
