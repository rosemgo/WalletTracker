import { CurrencyPipe } from '@angular/common';
import { DEFAULT_CURRENCY_CODE, LOCALE_ID, Pipe, PipeTransform, inject } from '@angular/core';

import { PrivacyStore, hideAmounts } from './privacy-store';

/**
 * Come la pipe `currency` di Angular, ma rispetta la modalità discreta: {{ t.amount | money: t.currency }}.
 *
 * Dentro usa proprio una CurrencyPipe (si "compone", non si eredita): gli stessi parametri e lo stesso risultato
 * ("-76,29 €"), poi le cifre nascoste se serve ("••• €").
 *
 * `pure: false` ("impura"): Angular la ricalcola a ogni aggiornamento della pagina, non solo quando cambia l'importo.
 * Serve perché il risultato dipende anche dalla modalità, che non è un parametro. Formattare un numero costa poco.
 */
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly privacy = inject(PrivacyStore);
  private readonly currency = new CurrencyPipe(inject(LOCALE_ID), inject(DEFAULT_CURRENCY_CODE));

  transform(
    value: number | null | undefined,
    currencyCode?: string,
    display: 'code' | 'symbol' | 'symbol-narrow' = 'symbol',
    digitsInfo?: string,
  ): string | null {
    const text = this.currency.transform(value, currencyCode, display, digitsInfo);
    return text !== null && this.privacy.discreet() ? hideAmounts(text) : text;
  }
}
