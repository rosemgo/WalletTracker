import { Pipe, PipeTransform } from '@angular/core';

/**
 * La prima lettera maiuscola: "giovedì 5 novembre" → "Giovedì 5 novembre". Serve dove il testo lo scrive Angular (le
 * date in italiano hanno i giorni in minuscolo) e sta all'inizio di una riga: in tutta l'app una riga che si legge da
 * sola comincia con la maiuscola.
 */
@Pipe({ name: 'upperFirst' })
export class UpperFirstPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return value ? value.charAt(0).toUpperCase() + value.slice(1) : '';
  }
}
