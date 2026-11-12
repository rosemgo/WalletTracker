import { Component, computed, inject, input } from '@angular/core';

import { AccountsStore } from '../accounts/accounts-store';
import { Transaction } from '../api/models';
import { avatarHue, initialOf } from './day-groups';

/**
 * Il cerchio di un movimento (docs/loghi.md): l'iniziale dell'esercente, con il suo colore, e nell'angolo in basso il
 * logo piccolo della banca del conto, se c'è. Si usa su uno {@code <span class="avatar">}: è lui il componente.
 */
@Component({
  selector: 'span[appAvatar]',
  host: { '[style.--hue]': 'hue()', 'aria-hidden': 'true' },
  template: `{{ initial() }}
    @if (logo(); as url) {
      <img class="avatar-bank" [src]="url" alt="" loading="lazy" decoding="async" />
    }`,
})
export class Avatar {
  private readonly store = inject(AccountsStore);

  readonly transaction = input.required<Transaction>({ alias: 'appAvatar' });

  protected readonly initial = computed(() => initialOf(this.transaction()));
  protected readonly hue = computed(() => avatarHue(this.transaction()));
  protected readonly logo = computed(() => this.store.logoUrl(this.transaction().accountId));

  constructor() {
    this.store.load();
  }
}
