import { formatDate } from '@angular/common';
import { inject, LOCALE_ID, Pipe, PipeTransform } from '@angular/core';

import { APP_LOCALE } from './locale';

/**
 * I modi in cui l'app scrive una data, per nome. Nelle pagine si dice CHE COSA si vuole ("la data corta, in numeri"),
 * non come è fatta ('dd/MM/yyyy'): il come sta scritto qui, una volta sola.
 *
 * A destra il formato di Angular. Dove Angular ne ha uno suo per la lingua ('mediumDate', 'longDate', 'fullDate') si
 * usa quello: in italiano dà "10 ott 2026", in inglese quello che un inglese si aspetta. Gli altri sono scritti con le
 * lettere (d = giorno, M = mese, y = anno, E = giorno della settimana), con il giorno prima del mese: vale per
 * l'italiano e per l'inglese d'Europa. Una lingua che mette prima il mese si sistema qui, senza toccare le pagine.
 */
export const DATE_FORMATS = {
  /** 10/10/2026: nelle tabelle, dove le date stanno in colonna. */
  numeric: 'dd/MM/yyyy',
  /** 10/10/2026 08:30 */
  numericTime: 'dd/MM/yyyy HH:mm',
  /** 10 ott */
  dayMonth: 'd MMM',
  /** 10 ott 2026 */
  medium: 'mediumDate',
  /** 10 ott 2026, 08:30 */
  mediumTime: 'd MMM y, HH:mm',
  /** 10 ottobre 2026 */
  long: 'longDate',
  /** 10 ottobre 2026, 08:30 */
  longTime: 'd MMMM y, HH:mm',
  /** sabato 10 ottobre */
  weekday: 'EEEE d MMMM',
  /** sabato 10 ottobre 2026 */
  full: 'fullDate',
  /** sabato 10 */
  weekdayDay: 'EEEE d',
  /** S: l'iniziale del giorno della settimana, sotto una barra stretta. */
  weekdayInitial: 'EEEEE',
  /** ottobre */
  month: 'MMMM',
  /** ott */
  monthShort: 'MMM',
  /** O: l'iniziale del mese, sotto una barra stretta. */
  monthInitial: 'MMMMM',
  /** ottobre 2026 */
  monthYear: 'MMMM y',
} as const;

export type DateStyle = keyof typeof DATE_FORMATS;

/**
 * Una data scritta in uno dei modi dell'app, nella lingua dell'app. Accetta quello che accetta la pipe date di Angular:
 * una Date, un numero di millisecondi o un testo ISO ("2026-10-10": il giorno, senza ora, nel fuso di chi guarda).
 * I nomi di mesi e giorni vengono dai dati della lingua: nessun elenco scritto a mano.
 */
export function formatLocalDate(
  value: string | number | Date,
  style: DateStyle,
  locale: string = APP_LOCALE,
): string {
  return formatDate(value, DATE_FORMATS[style], locale);
}

/** La prima lettera maiuscola: "ottobre 2026" → "Ottobre 2026". I nomi di mesi e giorni, in italiano, sono minuscoli. */
export function capitalized(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Lo stesso, nei template: {{ movimento.bookingDate | localDate: 'numeric' }}. Prende il posto di
 * {{ ... | date: 'dd/MM/yyyy' }}. Senza una data (null) non scrive niente.
 */
@Pipe({ name: 'localDate' })
export class LocalDatePipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: string | number | Date | null | undefined, style: DateStyle): string | null {
    return value === null || value === undefined || value === ''
      ? null
      : formatLocalDate(value, style, this.locale);
  }
}
