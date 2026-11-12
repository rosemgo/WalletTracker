import { readable } from './readable';

describe('readable', () => {
  it('turns shouting bank texts into names', () => {
    expect(readable('PIZZERIA DA GINO')).toBe('Pizzeria da Gino');
    expect(readable('ESSELUNGA MILANO')).toBe('Esselunga Milano');
    expect(readable('GAMMA LUCE E GAS S.P.A.')).toBe('Gamma Luce e Gas S.P.A.');
  });

  it('keeps acronyms and web addresses as they are read', () => {
    expect(readable('ACME SRL STIPENDIO')).toBe('Acme SRL Stipendio');
    expect(readable('PAGAMENTO POS AMAZON.IT')).toBe('Pagamento POS Amazon.it');
    expect(readable('NETFLIX.COM')).toBe('Netflix.com');
    expect(readable('CED.SU BTP ITALIA')).toBe('Ced.su BTP Italia'); // anche le sigle dei titoli
  });

  it('handles apostrophes and the first word', () => {
    expect(readable("BAR DELL'ACQUA")).toBe("Bar Dell'Acqua");
    expect(readable('DA GINO')).toBe('Da Gino');
  });

  it('leaves alone a text that already has lowercase letters', () => {
    expect(readable('Spotify AB')).toBe('Spotify AB');
    expect(readable('regalo per Luca')).toBe('regalo per Luca');
  });
  it('keeps the codes with letters and digits in capitals: ISIN, the names of the bonds', () => {
    expect(readable('IT0005436693')).toBe('IT0005436693');
    expect(readable('CED.SU BTP ITALIA IT0005436693')).toBe('Ced.su BTP Italia IT0005436693');
    expect(readable('ROMANIA-7OT45 6,5%')).toBe('Romania-7OT45 6,5%');
  });
});
