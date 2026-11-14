import { ExploreBar, ExplorePeriod, ExploreSeries } from '../api/models';
import { formatLocalDate } from '../text/dates';

// Le parole dell'esploratore (docs/38): funzioni pure, provate da explore-labels.spec.ts.

/** "2026-10-19" → la data, a mezzogiorno (così un cambio di ora legale non la sposta di giorno). */
function dateOf(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d, 12);
}

/** L'etichetta sotto una barra: "L" (un giorno), "1–7" (una settimana), "O" o "ott" (un mese). */
export function barLabel(
  bar: ExploreBar,
  unit: ExploreSeries['unit'],
  period: ExplorePeriod,
): string {
  const from = dateOf(bar.from);
  switch (unit) {
    case 'DAY':
      return formatLocalDate(from, 'weekdayInitial').toUpperCase();
    case 'WEEK':
      return `${from.getDate()}–${dateOf(bar.to).getDate()}`;
    case 'MONTH':
      // Fino a 12 barre (12 mesi, quest'anno): l'iniziale; con 6, le prime tre lettere.
      return period === 'YEAR' || period === 'YEAR_TO_DATE'
        ? formatLocalDate(from, 'monthInitial').toUpperCase()
        : formatLocalDate(from, 'monthShort').replace('.', '');
  }
}

/** Il nome del periodo di una barra: "lunedì 19 ottobre", "1–7 ottobre", "ottobre 2026". */
export function barName(bar: ExploreBar, unit: ExploreSeries['unit']): string {
  const from = dateOf(bar.from);
  switch (unit) {
    case 'DAY':
      return formatLocalDate(from, 'weekday');
    case 'WEEK':
      return `${from.getDate()}–${dateOf(bar.to).getDate()} ${formatLocalDate(from, 'month')}`;
    case 'MONTH':
      return formatLocalDate(from, 'monthYear');
  }
}

/** "al giorno", "a settimana", "al mese". */
export function perUnit(unit: ExploreSeries['unit']): string {
  return { DAY: 'al giorno', WEEK: 'a settimana', MONTH: 'al mese' }[unit];
}

/** La barra da mostrare all'inizio: l'ultima già successa (la settimana, il mese in corso). */
export function lastPastBar(bars: ExploreBar[]): number {
  for (let i = bars.length - 1; i >= 0; i--) {
    if (!bars[i].future) {
      return i;
    }
  }
  return 0;
}

/** Il mese di una data, "2026-10". */
export function monthOfDay(iso: string): string {
  return iso.slice(0, 7);
}
