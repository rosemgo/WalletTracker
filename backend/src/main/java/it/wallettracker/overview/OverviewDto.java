package it.wallettracker.overview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * La risposta di {@code GET /api/overview}: tutto quello che serve alla schermata Riepilogo, già calcolato (docs/36).
 * Gli importi sono positivi ("hai speso 170 €"). I valori del "solito" sono null quando non c'è nessun mese completo
 * da cui calcolarlo (un conto appena collegato).
 *
 * @param month          il mese, in JSON "2026-10"
 * @param day            il giorno fino a cui contano i numeri: oggi per il mese in corso, l'ultimo giorno per un mese
 *                       passato
 * @param daysInMonth    i giorni del mese (per disegnare il grafico fino in fondo)
 * @param currency       la valuta dei numeri
 * @param currencies     le valute con movimenti nel periodo: con più di una, la pagina fa scegliere
 * @param spent          le spese dal giorno 1 a {@code day}
 * @param income         le entrate dal giorno 1 a {@code day}
 * @param spentByDay     la spesa accumulata, un valore per giorno dal giorno 1 a {@code day}
 * @param usualMonths    da quanti mesi è calcolato il solito (0 = nessun confronto possibile)
 * @param usualSoFar     di solito, quanto avevi speso al giorno {@code day}
 * @param usualMonth     di solito, quanto spendi in un mese intero
 * @param usualByDay     la spesa accumulata di un mese tipico, un valore per ogni giorno del mese (vuota senza solito)
 * @param categories     le categorie con spese nel mese, dalla più alta
 * @param upcoming       gli abbonamenti confermati attesi nei prossimi giorni (solo per il mese in corso)
 * @param toReview       quanti movimenti sono da verificare (in tutto, non solo nel mese): fuori dai totali finché non
 *                       li classifichi
 * @param suggestions    quanti esercenti hanno una categoria suggerita
 */
public record OverviewDto(
        YearMonth month,
        LocalDate day,
        int daysInMonth,
        String currency,
        List<String> currencies,
        BigDecimal spent,
        BigDecimal income,
        List<BigDecimal> spentByDay,
        int usualMonths,
        BigDecimal usualSoFar,
        BigDecimal usualMonth,
        List<BigDecimal> usualByDay,
        List<CategoryRow> categories,
        List<Upcoming> upcoming,
        long toReview,
        int suggestions) {

    /**
     * Una categoria del mese.
     *
     * @param categoryId null = spese senza categoria
     * @param usualSoFar di solito, quanto avevi speso in questa categoria allo stesso giorno (null senza solito)
     * @param usualMonth di solito, in un mese intero (null senza solito)
     */
    public record CategoryRow(Long categoryId, String name, BigDecimal amount, long movements, BigDecimal usualSoFar,
            BigDecimal usualMonth) {
    }

    /** Un abbonamento atteso a breve: l'importo è quello dell'ultimo pagamento. */
    public record Upcoming(Long subscriptionId, String name, LocalDate date, BigDecimal amount) {
    }
}
