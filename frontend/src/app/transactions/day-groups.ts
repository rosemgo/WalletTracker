import { Transaction } from '../api/models';
import { isoDate } from './period';

/** Un giorno dell'elenco: il titolo, i movimenti e il totale delle spese e delle entrate vere. */
export interface DayGroup {
  date: string; // "2026-10-25"
  label: string; // "Oggi · domenica 25 ottobre"
  items: Transaction[];
  total: number | null; // null se nel giorno non ci sono né spese né entrate (solo giroconti, per esempio)
}

const WEEKDAYS = ['domenica', 'lunedì', 'martedì', 'mercoledì', 'giovedì', 'venerdì', 'sabato'];
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

/**
 * Il titolo di un giorno: "Oggi · domenica 25 ottobre", "Ieri · sabato 24 ottobre", "Venerdì 23 ottobre"; l'anno
 * solo se non è quello di oggi. Funzione pura: `today` è un parametro, così i test scelgono il giorno.
 */
export function dayLabel(date: string, today: Date): string {
  const [year, month, day] = date.split('-').map(Number);
  const when = new Date(year, month - 1, day);
  const text = `${WEEKDAYS[when.getDay()]} ${day} ${MONTHS[month - 1]}${year === today.getFullYear() ? '' : ' ' + year}`;
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  if (date === isoDate(today)) {
    return `Oggi · ${text}`;
  }
  if (date === isoDate(yesterday)) {
    return `Ieri · ${text}`;
  }
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * I movimenti raggruppati per giorno, nell'ordine in cui arrivano (dal più recente). Il totale del giorno somma solo
 * spese ed entrate: un giroconto o il saldo della carta non sono soldi spesi né guadagnati (docs/18).
 */
export function groupByDay(items: Transaction[], today: Date): DayGroup[] {
  const groups: DayGroup[] = [];
  for (const item of items) {
    let group = groups[groups.length - 1];
    if (group === undefined || group.date !== item.bookingDate) {
      group = {
        date: item.bookingDate,
        label: dayLabel(item.bookingDate, today),
        items: [],
        total: null,
      };
      groups.push(group);
    }
    group.items.push(item);
    if (item.type === 'EXPENSE' || item.type === 'INCOME') {
      // In centesimi, per non accumulare errori di arrotondamento (0,1 + 0,2 non fa 0,3 in virgola mobile).
      group.total = ((group.total ?? 0) * 100 + Math.round(item.amount * 100)) / 100;
    }
  }
  return groups;
}

/**
 * Il nome di un movimento nell'elenco: quello deciso dal backend (la controparte, o il nome scritto nella causale), o
 * la causale; spazi ripetuti ridotti a uno.
 */
export function nameOf(transaction: Transaction): string {
  const text = transaction.name || transaction.counterparty || transaction.description || '';
  return text.replace(/\s+/g, ' ').trim() || '(nessuna descrizione)';
}
