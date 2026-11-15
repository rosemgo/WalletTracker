import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';

import { LanguageSection } from './language-section';
import { LanguageStore } from './language-store';

describe('LanguageSection', () => {
  let store: {
    current: string;
    choose: ReturnType<typeof vi.fn>;
    chooseHere: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    store = { current: 'it', choose: vi.fn(() => of(null)), chooseHere: vi.fn() };
    TestBed.configureTestingModule({
      imports: [LanguageSection],
      providers: [{ provide: LanguageStore, useValue: store }],
    });
  });

  async function open(firstScreen = false) {
    const fixture = TestBed.createComponent(LanguageSection);
    fixture.componentRef.setInput('firstScreen', firstScreen);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const select = element.querySelector('select')!;
    const pick = async (language: string) => {
      select.value = language;
      select.dispatchEvent(new Event('change'));
      await fixture.whenStable();
    };
    return { element, select, pick };
  }

  it('shows every language with its own name, and the current one selected', async () => {
    const { element, select } = await open();

    const options = Array.from(select.options).map((o) => `${o.value}=${o.textContent?.trim()}`);
    expect(options).toEqual(['it=Italiano', 'en=English']);
    expect(select.value).toBe('it');
    // L'etichetta è nelle due lingue: la capisce anche chi non sa quella della pagina.
    expect(element.querySelector('label')?.textContent).toContain('Lingua · Language');
    expect(element.textContent).toContain('Le categorie di partenza che non hai rinominato');
  });

  it('in the settings, picking a language changes the one of the installation', async () => {
    const { pick } = await open();

    await pick('en');

    expect(store.choose).toHaveBeenCalledWith('en');
    expect(store.chooseHere).not.toHaveBeenCalled();
  });

  it('picking the current language again does nothing', async () => {
    const { pick } = await open();

    await pick('it');

    expect(store.choose).not.toHaveBeenCalled();
  });

  it('while the change is on its way the choice is locked', async () => {
    store.choose = vi.fn(() => NEVER);
    const { select, pick } = await open();

    await pick('en');

    expect(select.disabled).toBe(true);
  });

  it('if the change fails it says why, with the words of the backend, and unlocks the choice', async () => {
    store.choose = vi.fn(() =>
      throwError(
        () =>
          new HttpErrorResponse({ status: 400, error: { detail: 'Lingua non disponibile: en' } }),
      ),
    );
    const { element, select, pick } = await open();

    await pick('en');

    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'Lingua non disponibile: en',
    );
    expect(select.disabled).toBe(false);
  });

  it('on the first screen it changes only this page, and does not explain the categories yet', async () => {
    const { element, pick } = await open(true);

    await pick('en');

    expect(store.chooseHere).toHaveBeenCalledWith('en');
    expect(store.choose).not.toHaveBeenCalled();
    expect(element.textContent).not.toContain('categorie di partenza');
  });
});
