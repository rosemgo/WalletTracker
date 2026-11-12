import { Transaction } from '../api/models';
import { dayLabel, groupByDay, nameOf } from './day-groups';

const TODAY = new Date(2026, 9, 25); // domenica 25 ottobre 2026

function t(
  id: number,
  bookingDate: string,
  amount: number,
  type: Transaction['type'],
): Transaction {
  return {
    id,
    accountId: 1,
    status: 'BOOKED',
    bookingDate,
    valueDate: null,
    amount,
    currency: 'EUR',
    counterparty: null,
    description: 'X',
    type,
    categoryId: null,
    category: null,
    manualType: null,
    manualCategoryId: null,
    transferPeerId: null,
    note: null,
  };
}

describe('day groups', () => {
  it('names today, yesterday, the other days and other years', () => {
    expect(dayLabel('2026-10-25', TODAY)).toBe('Oggi · domenica 25 ottobre');
    expect(dayLabel('2026-10-24', TODAY)).toBe('Ieri · sabato 24 ottobre');
    expect(dayLabel('2026-10-23', TODAY)).toBe('Venerdì 23 ottobre');
    expect(dayLabel('2025-12-31', TODAY)).toBe('Mercoledì 31 dicembre 2025');
    // Il primo del mese: "ieri" anche a cavallo di due mesi.
    expect(dayLabel('2026-09-30', new Date(2026, 9, 1))).toBe('Ieri · mercoledì 30 settembre');
  });

  it('groups in order and sums only expenses and income, in cents', () => {
    const groups = groupByDay(
      [
        t(1, '2026-10-25', -0.1, 'EXPENSE'),
        t(2, '2026-10-25', -0.2, 'EXPENSE'),
        t(3, '2026-10-25', -500, 'INTERNAL_TRANSFER'),
        t(4, '2026-10-24', -100, 'CARD_SETTLEMENT'),
      ],
      TODAY,
    );

    expect(groups.map((g) => g.items.map((i) => i.id))).toEqual([[1, 2, 3], [4]]);
    expect(groups[0].total).toBe(-0.3); // non -0.30000000000000004
    expect(groups[1].total).toBeNull(); // niente spese né entrate: nessun totale
  });

  it('a movement is named by its name, counterparty or description, with single spaces', () => {
    expect(nameOf({ ...t(1, '2026-10-25', -1, 'EXPENSE'), counterparty: 'MAR  GROUP   SPA' })).toBe(
      'Mar Group SPA',
    );
    // Il nome deciso dal backend vince sulla causale lunga.
    const transfer = {
      ...t(1, '2026-10-25', 12, 'INCOME'),
      description: 'Bonifico N. 1 BIC Ordinante …',
    };
    expect(nameOf({ ...transfer, name: 'ACME SRL' })).toBe('Acme SRL');
    expect(nameOf({ ...t(1, '2026-10-25', -1, 'EXPENSE'), description: '  ' })).toBe(
      '(nessuna descrizione)',
    );
  });
});
