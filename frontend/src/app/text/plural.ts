import { APP_LOCALE } from './locale';

/**
 * Le forme di una frase che cambia con un numero: "1 movimento", "3 movimenti".
 *
 * I nomi sono quelli dello standard Unicode (CLDR), gli stessi di Intl.PluralRules e dei plurali nei template di
 * Angular. In italiano e in inglese ne servono due: {@code one} (per 1) e {@code other} (tutti gli altri numeri, zero
 * compreso). Altre lingue ne hanno di più (il polacco: one, few, many): per questo la scelta non si scrive a mano
 * con "n === 1", ma si chiede alle regole della lingua.
 */
export interface PluralForms {
  zero?: string;
  one: string;
  two?: string;
  few?: string;
  many?: string;
  other: string;
}

/** Le regole dei plurali di una lingua: costruirle costa, quindi una volta per lingua. */
const rules = new Map<string, Intl.PluralRules>();

/**
 * La forma giusta per questo numero, nella lingua dell'app:
 *
 *   plural(n, { one: '1 movimento', other: `${n} movimenti` })
 *
 * Nei template si usa invece la forma di Angular, dentro un elemento con l'attributo i18n:
 *
 *   {n, plural, =1 {1 movimento} other {{{ n }} movimenti}}
 *
 * Se la lingua chiede una forma che la frase non ha (in italiano "many", per i milioni tondi), vale {@code other}.
 */
export function plural(count: number, forms: PluralForms, locale: string = APP_LOCALE): string {
  let rule = rules.get(locale);
  if (!rule) {
    rule = new Intl.PluralRules(locale);
    rules.set(locale, rule);
  }
  return forms[rule.select(count)] ?? forms.other;
}
