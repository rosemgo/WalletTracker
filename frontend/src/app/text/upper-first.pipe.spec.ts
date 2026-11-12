import { UpperFirstPipe } from './upper-first.pipe';

describe('UpperFirstPipe', () => {
  const pipe = new UpperFirstPipe();

  it('capitalizes only the first letter', () => {
    expect(pipe.transform('giovedì 5 novembre 2026')).toBe('Giovedì 5 novembre 2026');
    expect(pipe.transform('Già maiuscolo')).toBe('Già maiuscolo');
    expect(pipe.transform('')).toBe('');
    expect(pipe.transform(null)).toBe('');
  });
});
