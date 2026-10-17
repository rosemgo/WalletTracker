import { categoryGroups, kindOf } from './category-groups';
import { Category } from './models';

const CATEGORIES: Category[] = [
  { id: 1, name: 'Spesa alimentare', kind: 'EXPENSE', builtin: true, uses: 0 },
  { id: 2, name: 'Stipendio e pensione', kind: 'INCOME', builtin: true, uses: 0 },
  { id: 3, name: 'Dividendi', kind: 'INVESTMENT', builtin: true, uses: 0 },
];

describe('category groups', () => {
  it('puts first the group that suits the movement, and leaves out empty groups', () => {
    expect(categoryGroups(CATEGORIES, 'INCOME').map((g) => g.label)).toEqual([
      'Categorie di entrata',
      'Categorie di spesa',
      'Categorie di investimento',
    ]);
  });

  it('knows which group suits each type, like the backend', () => {
    expect(kindOf('EXPENSE')).toBe('EXPENSE');
    expect(kindOf('INCOME')).toBe('INCOME');
    expect(kindOf('INVESTMENT_TAX')).toBe('INVESTMENT');
    expect(kindOf('INTERNAL_TRANSFER')).toBe('OTHER');
    expect(kindOf(null)).toBe('OTHER');
  });
});
