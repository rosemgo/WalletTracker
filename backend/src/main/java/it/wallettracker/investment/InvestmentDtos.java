package it.wallettracker.investment;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.transaction.TransactionDto;

/** I record dell'API degli investimenti (docs/investimenti.md), in un file solo perché sono piccoli e vanno insieme. */
public final class InvestmentDtos {

    private InvestmentDtos() {
    }

    /** Quanto indietro guardare: 12 mesi, 24 mesi, tutta la storia (al massimo 10 anni). */
    public enum Period {
        YEAR, TWO_YEARS, ALL
    }

    /**
     * Un mese.
     *
     * @param contributed quanto è andato dai conti normali agli investimenti, meno quanto è tornato (può essere
     *                    negativo: un mese in cui hai ripreso più di quanto hai versato)
     * @param cumulative  il versato netto dall'inizio della storia fino alla fine di questo mese: la linea del grafico
     * @param income      cedole, dividendi e interessi incassati nel mese
     * @param taxes       tasse sugli investimenti del mese (ritenute, bollo): di solito negativo
     */
    public record Month(YearMonth month, BigDecimal contributed, BigDecimal cumulative, BigDecimal income,
            BigDecimal taxes) {
    }

    /** Una voce di un elenco: "Cedole", o un titolo; l'importo e quanti movimenti. */
    public record Group(String name, BigDecimal amount, int movements) {
    }

    /**
     * Tutto quello che mostrano la scheda del Riepilogo e la pagina Investimenti.
     *
     * @param contributedTotal    il versato netto di tutta la storia, fino a oggi
     * @param contributedThisYear il versato netto dal 1° gennaio
     * @param contributedThisMonth il versato netto del mese in corso
     * @param incomeThisYear      le rendite dal 1° gennaio
     * @param contributedInPeriod il versato netto nei mesi del periodo
     * @param incomeInPeriod      le rendite nei mesi del periodo
     * @param taxesInPeriod       le tasse nei mesi del periodo
     * @param incomeByType        le rendite del periodo per categoria (Cedole, Dividendi, Interessi)
     * @param incomeBySource      le rendite del periodo per titolo o esercente
     * @param recentContributions gli ultimi versamenti e prelievi (al massimo {@link InvestmentService#RECENT})
     */
    public record Overview(String currency, List<String> currencies, Period period, YearMonth from, YearMonth to,
            BigDecimal contributedTotal, BigDecimal contributedThisYear, BigDecimal contributedThisMonth,
            BigDecimal incomeThisYear, BigDecimal contributedInPeriod, BigDecimal incomeInPeriod,
            BigDecimal taxesInPeriod, List<Month> months, List<Group> incomeByType, List<Group> incomeBySource,
            List<TransactionDto> recentContributions) {
    }
}
