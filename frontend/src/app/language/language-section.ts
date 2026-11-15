import { Component, inject, input, signal } from '@angular/core';

import { reason } from '../auth/auth-store';
import { LANGUAGES, LANGUAGE_NAMES, Language, isLanguage } from './language';
import { LanguageStore } from './language-store';

/**
 * La scelta della lingua: nelle Impostazioni, e nella prima schermata di un'installazione nuova.
 *
 * I nomi delle lingue non si traducono: ognuna è scritta nella sua lingua ("English", non "Inglese"), così la
 * riconosce anche chi non capisce quella della pagina. Per lo stesso motivo l'etichetta è in tutte e due.
 */
@Component({
  selector: 'app-language-section',
  template: `
    <label class="language-choice">
      <span>Lingua · Language</span>
      <select [disabled]="busy()" (change)="choose($event)">
        @for (language of languages; track language) {
          <option [value]="language" [selected]="language === store.current">
            {{ names[language] }}
          </option>
        }
      </select>
    </label>
    @if (!firstScreen()) {
      <p class="hint" i18n>
        Vale per tutta l'installazione: il computer e il telefono. Le categorie di partenza che non
        hai rinominato prendono il nome nella lingua nuova; quelle che hai rinominato tu, i testi
        dei movimenti e le regole restano come sono.
      </p>
    }
    @if (error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
  `,
  styles: `
    .language-choice {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
    }
  `,
})
export class LanguageSection {
  protected readonly store = inject(LanguageStore);
  protected readonly languages = LANGUAGES;
  protected readonly names = LANGUAGE_NAMES;

  /**
   * Nella prima schermata (non si è ancora dentro): la lingua cambia solo per questa pagina, e diventa quella
   * dell'installazione quando si entra. Nelle Impostazioni si cambia subito quella dell'installazione.
   */
  readonly firstScreen = input(false);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected choose(event: Event): void {
    const language = (event.target as HTMLSelectElement).value;
    if (!isLanguage(language) || language === this.store.current) {
      return;
    }
    if (this.firstScreen()) {
      this.store.chooseHere(language);
      return;
    }
    this.change(language);
  }

  private change(language: Language): void {
    this.busy.set(true);
    this.error.set(null);
    this.store.choose(language).subscribe({
      // Se va bene la pagina si ricarica: non c'è altro da fare.
      error: (response: unknown) => {
        this.busy.set(false);
        this.error.set(reason(response, $localize`La lingua non è stata cambiata: riprova.`));
      },
    });
  }
}
