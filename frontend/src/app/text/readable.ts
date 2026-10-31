import { Pipe, PipeTransform } from '@angular/core';

/** Le sigle che restano maiuscole: forme societarie e parole che si leggono lettera per lettera. */
const ACRONYMS = new Set([
  'SPA',
  'SRL',
  'SRLS',
  'SNC',
  'SAS',
  'SDD',
  'POS',
  'ATM',
  'IVA',
  'IBAN',
  'BIC',
  'SEPA',
  'ING',
  'BNL',
  'BPER',
  'BTP', // le sigle dei titoli di Stato e dei fondi (docs/investimenti.md)
  'BOT',
  'CCT',
  'CTZ',
  'ETF',
  'ETC',
  'UK',
  'USA',
  'EU',
  'TV',
  'PA',
  'INPS',
  'ACI',
  'ENI',
  'IP',
  'BP',
]);

/** Le parole piccole che in un nome restano minuscole (tranne la prima): "Pizzeria da Gino", "Luce e Gas". */
const SMALL_WORDS = new Set([
  'a',
  'ad',
  'al',
  'alla',
  'alle',
  'agli',
  'ai',
  'da',
  'dal',
  'dalla',
  'dai',
  'di',
  'del',
  'della',
  'dei',
  'delle',
  'degli',
  'e',
  'ed',
  'in',
  'con',
  'per',
  'su',
  'sul',
  'tra',
  'fra',
  'il',
  'la',
  'le',
  'lo',
  'gli',
  'un',
  'una',
]);

/**
 * Un testo della banca scritto tutto in MAIUSCOLO, reso leggibile (docs/41, parte E): "PIZZERIA DA GINO" diventa
 * "Pizzeria da Gino", "PAGAMENTO POS AMAZON.IT" diventa "Pagamento POS Amazon.it". Il maiuscolo continuo si legge
 * più lentamente (le parole perdono la loro "sagoma") e sembra gridato.
 *
 * Se il testo ha già delle minuscole lo lasciamo com'è: l'ha scritto una persona, o la banca con cura. È solo il modo
 * di mostrarlo: nel database e nella ricerca il testo resta quello originale.
 */
export function readable(text: string): string {
  if (/\p{Ll}/u.test(text)) {
    return text;
  }
  let first = true;
  // [\p{L}\p{N}']+ = una parola: lettere (di ogni alfabeto), cifre e apostrofi ("DELL'ACQUA" è una parola sola).
  return text.toLowerCase().replace(/[\p{L}\p{N}']+/gu, (word, offset: number) => {
    const isFirst = first;
    first = false;
    // Le sigle, e le lettere puntate di un'abbreviazione (S.P.A., S.R.L.): maiuscole.
    if (ACRONYMS.has(word.toUpperCase()) || (word.length === 1 && text[offset + 1] === '.')) {
      return word.toUpperCase();
    }
    // Dopo un punto attaccato (AMAZON.IT, NETFLIX.COM) è la fine di un indirizzo: resta minuscola.
    if (offset > 0 && text[offset - 1] === '.' && /\p{L}/u.test(text[offset - 2] ?? '')) {
      return word;
    }
    if (!isFirst && SMALL_WORDS.has(word)) {
      return word;
    }
    // "dell'acqua" → "Dell'Acqua": maiuscola la prima lettera e quella dopo l'apostrofo.
    return word.replace(
      /(^|')(\p{L})/gu,
      (_, before: string, letter: string) => before + letter.toUpperCase(),
    );
  });
}

/** Lo stesso, da usare nei template: {{ nome | readable }}. "Pura": ricalcola solo se il testo cambia. */
@Pipe({ name: 'readable' })
export class ReadablePipe implements PipeTransform {
  transform(text: string | null | undefined): string {
    return text ? readable(text) : '';
  }
}
