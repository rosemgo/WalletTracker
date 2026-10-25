import { ExploreBar, ExplorePeriod, ExploreSeries } from '../api/models';

// Le parole dell'esploratore (docs/38): funzioni pure, provate da explore-labels.spec.ts.

const MONTHS = [
  'gennaio',
  'febbraio',
  'marzo',
  'aprile',
  'maggio',
  'giugno',
  'luglio',
  'agosto',
  'settembre',
  'ottobre',
  'novembre',
  'dicembre',
];
const WEEKDAYS = ['domenica', 'lunedì', 'martedì', 'mercoledì', 'giovedì', 'venerdì', 'sabato'];

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
      return WEEKDAYS[from.getDay()].charAt(0).toUpperCase();
    case 'WEEK':
      return `${from.getDate()}–${dateOf(bar.to).getDate()}`;
    case 'MONTH':
      return period === 'YEAR'
        ? MONTHS[from.getMonth()].charAt(0).toUpperCase()
        : MONTHS[from.getMonth()].slice(0, 3);
  }
}

/** Il nome del periodo di una barra: "lunedì 19 ottobre", "1–7 ottobre", "ottobre 2026". */
export function barName(bar: ExploreBar, unit: ExploreSeries['unit']): string {
  const from = dateOf(bar.from);
  const month = MONTHS[from.getMonth()];
  switch (unit) {
    case 'DAY':
      return `${WEEKDAYS[from.getDay()]} ${from.getDate()} ${month}`;
    case 'WEEK':
      return `${from.getDate()}–${dateOf(bar.to).getDate()} ${month}`;
    case 'MONTH':
      return `${month} ${from.getFullYear()}`;
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
