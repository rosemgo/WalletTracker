/**
 * I periodi del filtro dei movimenti, come nelle app delle banche: "Questo mese", "Mese scorso"...
 * Funzioni "pure" (niente Angular), provate da period.spec.ts.
 *
 * Le date viaggiano come testo "2026-10-01" (il formato ISO che usano il backend e il campo
 * <input type="date">). Attenzione a non usare `toISOString()`: converte in ora UTC, e a mezzanotte in
 * Italia darebbe il giorno prima.
 */
export type Period = 'ALL' | 'THIS_MONTH' | 'LAST_MONTH' | 'LAST_3_MONTHS' | 'THIS_YEAR' | 'CUSTOM';

export const PERIOD_LABELS: Record<Period, string> = {
  ALL: 'Tutto',
  THIS_MONTH: 'Questo mese',
  LAST_MONTH: 'Mese scorso',
  LAST_3_MONTHS: 'Ultimi 3 mesi',
  THIS_YEAR: "Quest'anno",
  CUSTOM: 'Scegli le date',
};

export const PERIODS = Object.keys(PERIOD_LABELS) as Period[];

/** Un giorno nel formato "2026-10-01", con il fuso orario del computer. */
export function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * Le date di un periodo, a partire da oggi. "Tutto" non ha date (null = nessun limite). "Scegli le date"
 * non si calcola: le sceglie l'utente.
 */
export function periodRange(
  period: Period,
  today: Date,
): { from: string | null; to: string | null } {
  const year = today.getFullYear();
  const month = today.getMonth();
  switch (period) {
    case 'THIS_MONTH':
      // new Date(anno, mese + 1, 0): il giorno 0 del mese dopo è l'ultimo giorno di questo mese.
      return { from: isoDate(new Date(year, month, 1)), to: isoDate(new Date(year, month + 1, 0)) };
    case 'LAST_MONTH':
      return { from: isoDate(new Date(year, month - 1, 1)), to: isoDate(new Date(year, month, 0)) };
    case 'LAST_3_MONTHS':
      return { from: isoDate(monthsBefore(today, 3)), to: isoDate(today) };
    case 'THIS_YEAR':
      return { from: isoDate(new Date(year, 0, 1)), to: isoDate(new Date(year, 11, 31)) };
    default:
      return { from: null, to: null };
  }
}

/**
 * Lo stesso giorno, N mesi prima. Se quel giorno non esiste (il 31 maggio meno 3 mesi: 31 febbraio?),
 * l'ultimo giorno di quel mese. Senza questo controllo, `new Date(2026, 1, 31)` diventerebbe il 3 marzo.
 */
function monthsBefore(date: Date, months: number): Date {
  const target = new Date(date.getFullYear(), date.getMonth() - months, 1);
  const lastDay = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
  return new Date(target.getFullYear(), target.getMonth(), Math.min(date.getDate(), lastDay));
}
