import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { Category, CategoryRequest } from '../api/models';
import { WalletApi } from '../api/wallet-api';
import { CategoriesPage } from './categories-page';

const CATEGORIES: Category[] = [
  { id: 1, name: 'Spesa alimentare', kind: 'EXPENSE', builtin: true, uses: 42 },
  { id: 2, name: 'Animali', kind: 'EXPENSE', builtin: false, uses: 3 },
  { id: 3, name: 'Stipendio e pensione', kind: 'INCOME', builtin: true, uses: 12 },
];

describe('CategoriesPage', () => {
  const created: CategoryRequest[] = [];
  const updated: [number, CategoryRequest][] = [];
  const deleted: number[] = [];
  let failDelete: boolean;

  beforeEach(() => {
    created.length = 0;
    updated.length = 0;
    deleted.length = 0;
    failDelete = false;
    const fakeApi: Partial<WalletApi> = {
      categories: () => of(CATEGORIES),
      createCategory: (c: CategoryRequest) => {
        created.push(c);
        return of({ ...c, id: 9, builtin: false, uses: 0 });
      },
      updateCategory: (id: number, c: CategoryRequest) => {
        updated.push([id, c]);
        return of({ ...CATEGORIES[0], ...c, id });
      },
      deleteCategory: (id: number) => {
        deleted.push(id);
        return failDelete
          ? throwError(() => ({
              error: { detail: 'C\'è un budget per "Animali": eliminalo prima.' },
            }))
          : of(undefined);
      },
    };
    TestBed.configureTestingModule({
      imports: [CategoriesPage],
      providers: [{ provide: WalletApi, useValue: fakeApi }],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(CategoriesPage);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  /** Il pulsante con questo testo nella riga di una categoria. */
  function rowButton(page: HTMLElement, category: string, text: string): HTMLButtonElement {
    const row = Array.from(page.querySelectorAll('tbody tr')).find((r) =>
      r.textContent?.includes(category),
    ) as HTMLElement;
    return Array.from(row.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;
  }

  it('lists the categories by group, with how many movements use them', async () => {
    const page = await render();

    expect(Array.from(page.querySelectorAll('h2.section-title')).map((h) => h.textContent)).toEqual(
      ['Categorie di spesa', 'Categorie di entrata'],
    );
    expect(page.querySelector('tbody tr')?.textContent).toContain('42 movimenti');
    expect(page.querySelector('tbody tr')?.textContent).toContain('di partenza');
  });

  it('adds a category in the chosen group', async () => {
    const page = await render();
    const name = page.querySelector('[formControlName="name"]') as HTMLInputElement;
    const kind = page.querySelector('[formControlName="kind"]') as HTMLSelectElement;

    name.value = '  Animali domestici ';
    name.dispatchEvent(new Event('input'));
    kind.value = 'EXPENSE';
    kind.dispatchEvent(new Event('change'));
    (page.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    expect(created).toEqual([{ name: 'Animali domestici', kind: 'EXPENSE' }]);
  });

  it('renames a category keeping its group', async () => {
    const fixture = TestBed.createComponent(CategoriesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    rowButton(page, 'Animali', 'rinomina').click();
    await fixture.whenStable();
    // Durante la rinomina il nome sta nel campo di testo, non nel testo della riga: la cerchiamo così.
    const editor = page.querySelector('.category-edit') as HTMLElement;
    (editor.querySelector('input') as HTMLInputElement).value = 'Animali domestici';
    (editor.querySelector('button') as HTMLButtonElement).click(); // "Salva"

    expect(updated).toEqual([[2, { name: 'Animali domestici', kind: 'EXPENSE' }]]);
  });

  it('asks for confirmation before deleting, and shows why it was refused', async () => {
    const fixture = TestBed.createComponent(CategoriesPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    rowButton(page, 'Animali', 'elimina').click();
    await fixture.whenStable();
    expect(deleted).toEqual([]); // non ancora: serve la conferma
    expect(page.textContent).toContain('3 movimenti resteranno senza categoria');

    failDelete = true;
    rowButton(page, 'Animali', 'Elimina').click();
    await fixture.whenStable();
    expect(deleted).toEqual([2]);
    expect(page.querySelector('.error')?.textContent).toContain('C\'è un budget per "Animali"');
  });
});
