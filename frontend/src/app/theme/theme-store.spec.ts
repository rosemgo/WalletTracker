import { TestBed } from '@angular/core/testing';

import { ThemeSection } from './theme-section';
import { THEME_KEY, ThemeStore } from './theme-store';

describe('ThemeStore', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('starts automatic: no attribute, the system decides', () => {
    const store = TestBed.inject(ThemeStore);

    expect(store.choice()).toBe('auto');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('applies and remembers a choice; automatic forgets it', () => {
    const store = TestBed.inject(ThemeStore);

    store.choose('notte');
    expect(document.documentElement.getAttribute('data-theme')).toBe('notte');
    expect(localStorage.getItem(THEME_KEY)).toBe('notte');

    store.choose('auto');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    expect(localStorage.getItem(THEME_KEY)).toBeNull();
  });

  it('a remembered choice is applied at start, an unknown value is ignored', () => {
    localStorage.setItem(THEME_KEY, 'giorno');
    expect(TestBed.inject(ThemeStore).choice()).toBe('giorno');
    expect(document.documentElement.getAttribute('data-theme')).toBe('giorno');

    TestBed.resetTestingModule();
    localStorage.setItem(THEME_KEY, '<rosa>');
    expect(TestBed.inject(ThemeStore).choice()).toBe('auto');
  });

  it('works even when the browser storage cannot be used', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloccato');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloccato');
    });
    const store = TestBed.inject(ThemeStore);

    expect(store.choice()).toBe('auto');
    store.choose('notte');
    expect(document.documentElement.getAttribute('data-theme')).toBe('notte');

    getItem.mockRestore();
    setItem.mockRestore();
  });

  it('the settings section changes the theme with a radio button', async () => {
    const fixture = TestBed.createComponent(ThemeSection);
    await fixture.whenStable();
    const radios = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('input[type=radio]'),
    ) as HTMLInputElement[];

    expect(radios.map((r) => r.value)).toEqual(['auto', 'giorno', 'notte']);
    expect(radios[0].checked).toBe(true);
    radios[2].click();
    await fixture.whenStable();

    expect(document.documentElement.getAttribute('data-theme')).toBe('notte');
    expect(radios[2].checked).toBe(true);
  });
});
