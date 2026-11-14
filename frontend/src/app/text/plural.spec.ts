import { plural } from './plural';

describe('plural', () => {
  const movements = (n: number) => plural(n, { one: '1 movimento', other: `${n} movimenti` });

  it('picks the singular only for one', () => {
    expect(movements(1)).toBe('1 movimento');
    expect(movements(0)).toBe('0 movimenti');
    expect(movements(2)).toBe('2 movimenti');
    expect(movements(21)).toBe('21 movimenti');
  });

  it('falls back to "other" when the language asks for a form the sentence does not have', () => {
    // In italiano un milione tondo è "many" (un milione DI movimenti): la frase non ha quella forma.
    expect(movements(1_000_000)).toBe('1000000 movimenti');
  });

  it('follows the rules of the language it is given', () => {
    const files = (n: number, locale: string) =>
      plural(n, { one: 'plik', few: 'pliki', many: 'plików', other: 'pliku' }, locale);
    // Il polacco ha quattro forme: è il motivo per cui la scelta non si scrive con "n === 1".
    expect(files(1, 'pl')).toBe('plik');
    expect(files(3, 'pl')).toBe('pliki');
    expect(files(5, 'pl')).toBe('plików');
    expect(files(22, 'pl')).toBe('pliki');
  });
});
