import { TestBed } from '@angular/core/testing';

import { LocalDatePipe, capitalized, formatLocalDate } from './dates';

describe('formatLocalDate', () => {
  it('writes a date in the named ways of the app, in Italian', () => {
    const day = '2026-10-10';
    expect(formatLocalDate(day, 'numeric')).toBe('10/10/2026');
    expect(formatLocalDate(day, 'dayMonth')).toBe('10 ott');
    expect(formatLocalDate(day, 'medium')).toBe('10 ott 2026');
    expect(formatLocalDate(day, 'long')).toBe('10 ottobre 2026');
    expect(formatLocalDate(day, 'weekday')).toBe('sabato 10 ottobre');
    expect(formatLocalDate(day, 'full')).toBe('sabato 10 ottobre 2026');
    expect(formatLocalDate(day, 'monthYear')).toBe('ottobre 2026');
    expect(formatLocalDate(day, 'monthInitial')).toBe('O');
    expect(formatLocalDate(day, 'weekdayInitial')).toBe('S');
    expect(formatLocalDate(new Date(2026, 9, 10, 8, 30), 'longTime')).toBe('10 ottobre 2026, 08:30');
  });

  it('capitalizes the names that the language writes in lower case', () => {
    expect(capitalized(formatLocalDate('2026-01-01', 'month'))).toBe('Gennaio');
  });
});

describe('LocalDatePipe', () => {
  it('writes nothing without a date', () => {
    const pipe = TestBed.runInInjectionContext(() => new LocalDatePipe());
    expect(pipe.transform(null, 'numeric')).toBeNull();
    expect(pipe.transform('2026-10-10', 'numeric')).toBe('10/10/2026');
  });
});
