import { isoDate, periodRange } from './period';

/** Test delle date dei periodi: funzioni pure, nessun componente. */
describe('periodRange', () => {
  // I mesi in JavaScript partono da 0: new Date(2026, 9, 20) è il 20 ottobre 2026.
  const today = new Date(2026, 9, 20);

  it('this month and last month go from the first to the last day', () => {
    expect(periodRange('THIS_MONTH', today)).toEqual({ from: '2026-10-01', to: '2026-10-31' });
    expect(periodRange('LAST_MONTH', today)).toEqual({ from: '2026-09-01', to: '2026-09-30' });
  });

  it('last month in January is December of the year before', () => {
    expect(periodRange('LAST_MONTH', new Date(2026, 0, 15))).toEqual({
      from: '2025-12-01',
      to: '2025-12-31',
    });
  });

  it('the last 3 months end today, and a missing day becomes the last of the month', () => {
    expect(periodRange('LAST_3_MONTHS', today)).toEqual({ from: '2026-07-20', to: '2026-10-20' });
    // 31 maggio meno 3 mesi: non c'è il 31 febbraio, quindi il 28.
    expect(periodRange('LAST_3_MONTHS', new Date(2026, 4, 31)).from).toBe('2026-02-28');
  });

  it('this year, and "all" without dates', () => {
    expect(periodRange('THIS_YEAR', today)).toEqual({ from: '2026-01-01', to: '2026-12-31' });
    expect(periodRange('ALL', today)).toEqual({ from: null, to: null });
  });

  it('isoDate uses the local day, not UTC', () => {
    // A mezzanotte in Italia, toISOString() darebbe ancora il giorno prima.
    expect(isoDate(new Date(2026, 9, 1, 0, 30))).toBe('2026-10-01');
  });
});
