import { ExploreBar } from '../api/models';
import { barLabel, barName, lastPastBar, perUnit } from './explore-labels';

const bar = (from: string, to: string, future = false): ExploreBar => ({
  from,
  to,
  value: 0,
  future,
});

describe('explore labels', () => {
  it('names the bars of a week, a month, six months and a year', () => {
    expect(barLabel(bar('2026-10-19', '2026-10-19'), 'DAY', 'WEEK')).toBe('L');
    expect(barName(bar('2026-10-19', '2026-10-19'), 'DAY')).toBe('lunedì 19 ottobre');
    expect(barLabel(bar('2026-10-29', '2026-10-31'), 'WEEK', 'MONTH')).toBe('29–31');
    expect(barName(bar('2026-10-01', '2026-10-07'), 'WEEK')).toBe('1–7 ottobre');
    expect(barLabel(bar('2026-10-01', '2026-10-31'), 'MONTH', 'YEAR')).toBe('O');
    expect(barLabel(bar('2026-05-01', '2026-05-31'), 'MONTH', 'SIX_MONTHS')).toBe('mag');
    expect(barName(bar('2026-10-01', '2026-10-31'), 'MONTH')).toBe('ottobre 2026');
  });

  it('the average is per day, per week or per month', () => {
    expect(perUnit('DAY')).toBe('al giorno');
    expect(perUnit('WEEK')).toBe('a settimana');
    expect(perUnit('MONTH')).toBe('al mese');
  });

  it('starts from the last bar that already happened', () => {
    const bars = [
      bar('2026-10-01', '2026-10-07'),
      bar('2026-10-08', '2026-10-14'),
      bar('2026-10-29', '2026-10-31', true),
    ];
    expect(lastPastBar(bars)).toBe(1);
    expect(lastPastBar([bar('2026-11-01', '2026-11-07', true)])).toBe(0);
  });
});
