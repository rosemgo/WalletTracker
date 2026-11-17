import { Component, inject } from '@angular/core';

import { THEME_CHOICES, ThemeChoice, ThemeStore } from './theme-store';

/** Le parole di ogni scelta, con la riga che dice che cosa fa. */
const LABELS: Record<ThemeChoice, { name: string; hint: string }> = {
  auto: {
    name: $localize`Automatico`,
    hint: $localize`chiaro o scuro come il telefono o il computer`,
  },
  giorno: { name: $localize`Giorno`, hint: $localize`sempre chiaro: si legge meglio al sole` },
  notte: { name: $localize`Notte`, hint: $localize`sempre scuro` },
};

/** La scelta del tema, nelle Impostazioni: tre pulsanti di opzione, si applica subito. */
@Component({
  selector: 'app-theme-section',
  template: `
    <fieldset class="choices">
      <legend i18n>Tema</legend>
      @for (choice of choices; track choice) {
        <label>
          <input
            type="radio"
            name="theme"
            [value]="choice"
            [checked]="theme.choice() === choice"
            (change)="theme.choose(choice)"
          />
          <span>
            {{ labels[choice].name }}
            <span class="secondary">{{ labels[choice].hint }}</span>
          </span>
        </label>
      }
    </fieldset>
  `,
})
export class ThemeSection {
  protected readonly theme = inject(ThemeStore);
  protected readonly choices = THEME_CHOICES;
  protected readonly labels = LABELS;
}
