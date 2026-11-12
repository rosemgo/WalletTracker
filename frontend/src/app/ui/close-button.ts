import { Component, input, output } from '@angular/core';

/**
 * La crocetta che chiude un avviso. Il nome per i lettori di schermo dice QUALE avviso chiude ("Chiudi l'avviso del
 * backup"): una pagina può averne più di uno.
 */
@Component({
  selector: 'app-close-button',
  template: `<button
    type="button"
    class="close-button"
    [attr.aria-label]="label()"
    (click)="closed.emit()"
  >
    ×
  </button>`,
})
export class CloseButton {
  readonly label = input("Chiudi l'avviso");
  readonly closed = output<void>();
}
