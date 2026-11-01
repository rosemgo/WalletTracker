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
     * @param bought             acquisti di titoli del mese (positivo)
     * @param sold               quanto è rientrato dalle vendite di titoli nel mese (positivo)
     * @param investedCumulative l'investito netto (acquisti meno vendite) dall'inizio della storia fino alla fine di
     *                           questo mese: la linea del grafico
     * @param transferred        bonifici dai conti di tutti i giorni verso i conti di investimento, meno quelli tornati
     *                           indietro (può essere negativo): le barre del secondo grafico
     * @param income             cedole, dividendi e interessi incassati nel mese
     * @param taxes              tasse sugli investimenti del mese (ritenute, bollo): di solito negativo
     */
    public record Month(YearMonth month, BigDecimal bought, BigDecimal sold, BigDecimal investedCumulative,
            BigDecimal transferred, BigDecimal income, BigDecimal taxes) {
    }

    /** Una voce di un elenco: "Cedole", o un titolo; l'importo e quanti movimenti. */
    public record Group(String name, BigDecimal amount, int movements) {
    }

    /**
     * Tutto quello che mostrano la scheda del Riepilogo e la pagina Investimenti.
     *
     * @param investedTotal       l'investito netto in titoli (acquisti meno vendite) di tutta la storia, fino a oggi
     * @param investedThisYear    l'investito netto dal 1° gennaio
     * @param investedThisMonth   l'investito netto del mese in corso
     * @param transferredTotal    i bonifici netti verso i conti di investimento di tutta la storia
     * @param incomeThisYear      le rendite dal 1° gennaio
     * @param boughtInPeriod      gli acquisti di titoli nei mesi del periodo
     * @param soldInPeriod        quanto è rientrato dalle vendite nei mesi del periodo
     * @param transferredInPeriod i bonifici netti verso i conti di investimento nei mesi del periodo
     * @param incomeInPeriod      le rendite nei mesi del periodo
     * @param taxesInPeriod       le tasse nei mesi del periodo
     * @param incomeByType        le rendite del periodo per categoria (Cedole, Dividendi, Interessi)
     * @param incomeBySource      le rendite del periodo per titolo o esercente
     * @param trades              gli acquisti e le vendite del periodo, dal più recente
     * @param transfers           i bonifici del periodo che fanno le barre (il lato dei conti di tutti i giorni), dal più
     *                            recente
     * @param onlyTransfers       i conti di investimento di cui si vedono bonifici ma, in tutta la storia, nessun acquisto
     *                            né vendita: per loro l'investito non si vede
     */
    public record Overview(String currency, List<String> currencies, Period period, YearMonth from, YearMonth to,
            BigDecimal investedTotal, BigDecimal investedThisYear, BigDecimal investedThisMonth,
            BigDecimal transferredTotal, BigDecimal incomeThisYear, BigDecimal boughtInPeriod,
            BigDecimal soldInPeriod, BigDecimal transferredInPeriod, BigDecimal incomeInPeriod,
            BigDecimal taxesInPeriod, List<Month> months, List<Group> incomeByType, List<Group> incomeBySource,
            List<TransactionDto> trades, List<TransactionDto> transfers, List<String> onlyTransfers) {
    }
}
