import { TestBed } from '@angular/core/testing';

import { FileDrop } from './file-drop';

describe('FileDrop', () => {
  it('is a real file field inside a big area, and shows the name of the chosen file', async () => {
    const fixture = TestBed.createComponent(FileDrop);
    fixture.componentRef.setInput('title', 'Scegli il file CSV');
    fixture.componentRef.setInput('accept', '.csv');
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const events: Event[] = [];
    fixture.componentInstance.picked.subscribe((e) => events.push(e));

    const input = element.querySelector('label.file-drop input[type="file"]') as HTMLInputElement;
    expect(input.getAttribute('accept')).toBe('.csv');
    expect(element.textContent).toContain('Scegli il file CSV');
    expect(element.textContent).toContain('o trascinalo qui');

    const file = new File(['x'], 'estratto.csv', { type: 'text/csv' });
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(events.map((e) => e.target)).toEqual([input]); // l'evento originale, come con (change)
    expect(element.textContent).toContain('estratto.csv');
    expect(element.querySelector('.file-drop.chosen')).not.toBeNull();
  });
});
