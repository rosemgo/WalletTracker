import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { Component, LOCALE_ID, computed, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { MoneyPipe } from './money.pipe';
import { DISCREET_KEY, PrivacyStore, hideAmounts } from './privacy-store';

/** Una pagina di prova: un importo con la pipe e una frase costruita nel codice. */
@Component({
  imports: [MoneyPipe],
  template: `<p class="pipe">{{ -1284.5 | money: 'EUR' }}</p>
    <p class="sentence">{{ sentence() }}</p>`,
})
class Page {
  private readonly privacy = inject(PrivacyStore);
  protected readonly sentence = computed(() => this.privacy.hide('25 € in meno del solito'));
}

describe('discreet mode', () => {
  beforeEach(() => {
    localStorage.clear();
    registerLocaleData(localeIt);
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: 'it-IT' }] });
  });

  afterEach(() => localStorage.clear());

  it('hides the amounts, sign included, and leaves the rest of the text', () => {
    expect(hideAmounts('-1.284,50 €')).toBe('••• €');
    expect(hideAmounts('+829 € sopra il ritmo, 3 movimenti')).toBe(
      '••• € sopra il ritmo, ••• movimenti',
    );
    expect(hideAmounts('Spesa alimentare')).toBe('Spesa alimentare');
    expect(hideAmounts('+••• € sul solito')).toBe('••• € sul solito'); // il segno messo davanti dopo
  });

  it('the pipe and the sentences follow the switch, and the page updates by itself', async () => {
    const fixture = TestBed.createComponent(Page);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const text = (selector: string) => page.querySelector(selector)?.textContent?.trim();

    expect(text('.pipe')).toMatch(/^-1\.284,50\s€$/); // come la pipe currency
    expect(text('.sentence')).toBe('25 € in meno del solito');

    TestBed.inject(PrivacyStore).toggle();
    await fixture.whenStable();
    expect(text('.pipe')).toMatch(/^•••\s€$/);
    expect(text('.sentence')).toBe('••• € in meno del solito');
  });

  it('is remembered for the next opening, and works without storage', () => {
    TestBed.inject(PrivacyStore).toggle();
    expect(localStorage.getItem(DISCREET_KEY)).toBe('1');

    TestBed.resetTestingModule();
    const store = TestBed.inject(PrivacyStore); // una nuova apertura
    expect(store.discreet()).toBe(true);
    store.toggle();
    expect(localStorage.getItem(DISCREET_KEY)).toBeNull();

    // Archiviazione bloccata (navigazione privata): la modalità funziona lo stesso, solo non è ricordata.
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloccato');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloccato');
    });
    TestBed.resetTestingModule();
    const blocked = TestBed.inject(PrivacyStore);
    expect(blocked.discreet()).toBe(false);
    blocked.toggle();
    expect(blocked.discreet()).toBe(true);
    getItem.mockRestore();
    setItem.mockRestore();
  });
});
