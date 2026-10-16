import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { MonthSummary } from '../api/models';
import { MonthlyChart, niceStep } from './monthly-chart';

function month(month: string, income: number, expenses: number): MonthSummary {
  return { month, income, expenses, savings: income - expenses, investmentNet: 0, toReview: 0 };
}

describe('MonthlyChart', () => {
  beforeEach(() => {
    registerLocaleData(localeIt);
    // come in app.config.ts: importi e mesi in italiano
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: 'it-IT' }] });
  });

  /** Crea il grafico come se un componente padre gli passasse i dati: setInput imposta gli input(). */
  async function render(months: MonthSummary[]) {
    const fixture = TestBed.createComponent(MonthlyChart);
    fixture.componentRef.setInput('months', months);
    fixture.componentRef.setInput('currency', 'EUR');
    await fixture.whenStable();
    return fixture;
  }

  it('draws one income and one expense column per month, and none for zero', async () => {
    const fixture = await render([month('2026-09', 2000, 1500), month('2026-10', 0, 800)]);
    const svg = (fixture.nativeElement as HTMLElement).querySelector('svg')!;

    expect(svg.querySelectorAll('path.bar.income').length).toBe(1); // ottobre non ha entrate
    expect(svg.querySelectorAll('path.bar.expenses').length).toBe(2);
    expect(svg.textContent).toContain('set 26');
    expect(svg.textContent).toContain('2.000'); // la linea più alta della scala
  });

  it('shows the values of a month on hover and with the keyboard', async () => {
    const fixture = await render([month('2026-09', 2000, 1500), month('2026-10', 1000, 800)]);
    const page = fixture.nativeElement as HTMLElement;
    const areas = page.querySelectorAll('rect.hit');

    areas[0].dispatchEvent(new Event('pointerenter'));
    await fixture.whenStable();
    expect(page.querySelector('.tooltip')?.textContent).toContain('settembre 2026');
    expect(page.querySelector('.tooltip')?.textContent).toContain('500,00');

    areas[0].dispatchEvent(new Event('pointerleave'));
    (areas[1] as SVGElement).dispatchEvent(new Event('focus'));
    await fixture.whenStable();
    expect(page.querySelector('.tooltip')?.textContent).toContain('ottobre 2026');
  });

  it('rounds the scale to clean steps', () => {
    expect(niceStep(450)).toBe(500);
    expect(niceStep(525)).toBe(1000);
    expect(niceStep(180)).toBe(200);
    expect(niceStep(2400)).toBe(2500);
    expect(niceStep(0)).toBe(100);
  });
});
