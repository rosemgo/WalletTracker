package it.wallettracker.investment;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.account.CurrentBalance;
import it.wallettracker.transaction.TransactionDto;

/** I record dell'API degli investimenti (docs/investimenti.md), in un file solo perché sono piccoli e vanno insieme. */
public final class InvestmentDtos {

    private InvestmentDtos() {
    }

    /**
     * Quanto indietro guardare: 12 mesi, l'anno in corso (da gennaio a oggi), 24 mesi, tutta la storia (al massimo 10
     * anni).
     */
    public enum Period {
        YEAR, YEAR_TO_DATE, TWO_YEARS, ALL
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
     * @param income             cedole, dividendi e interessi incassati nel mese, lordi (come li paga la banca)
     * @param withholding        le ritenute sulle rendite del mese (categoria "Ritenute"): di solito negativo
     * @param otherTaxes         le altre tasse sugli investimenti del mese (bollo, capital gain...): di solito negativo
     */
    public record Month(YearMonth month, BigDecimal bought, BigDecimal sold, BigDecimal investedCumulative,
            BigDecimal transferred, BigDecimal income, BigDecimal withholding, BigDecimal otherTaxes) {
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
     * @param incomeInPeriod      le rendite lorde nei mesi del periodo
     * @param withholdingInPeriod le ritenute sulle rendite nel periodo: rendite più ritenute danno il netto
     * @param otherTaxesInPeriod  le altre tasse sugli investimenti nel periodo (bollo, capital gain...)
     * @param otherTaxesByType    le altre tasse del periodo per categoria
     * @param incomeByType        le rendite nette del periodo per categoria (Cedole, Dividendi, Interessi)
     * @param incomeBySource      le rendite nette del periodo per titolo (o per conto, se il titolo non ha nome)
     * @param trades              gli acquisti e le vendite del periodo, dal più recente
     * @param transfers           i bonifici del periodo che fanno le barre (il lato dei conti di tutti i giorni), dal più
     *                            recente
     * @param onlyTransfers       i conti di investimento di cui si vedono bonifici ma, in tutta la storia, nessun acquisto
     *                            né vendita: per loro l'investito non si vede
     * @param cash                la liquidità: il saldo di ogni conto di investimento nella valuta scelta (docs/saldi.md)
     */
    public record Overview(String currency, List<String> currencies, Period period, YearMonth from, YearMonth to,
            BigDecimal investedTotal, BigDecimal investedThisYear, BigDecimal investedThisMonth,
            BigDecimal transferredTotal, BigDecimal incomeThisYear, BigDecimal boughtInPeriod,
            BigDecimal soldInPeriod, BigDecimal transferredInPeriod, BigDecimal incomeInPeriod,
            BigDecimal withholdingInPeriod, BigDecimal otherTaxesInPeriod, List<Month> months,
            List<Group> incomeByType, List<Group> incomeBySource, List<Group> otherTaxesByType,
            List<TransactionDto> trades, List<TransactionDto> transfers, List<String> onlyTransfers,
            List<Cash> cash) {
    }

    /**
     * Il saldo di un conto di investimento: i soldi sul conto, non investiti in titoli (docs/saldi.md, parte D).
     *
     * @param balance il saldo, o null se la banca non lo dà e l'utente non l'ha scritto
     */
    public record Cash(Long accountId, String account, boolean manual, CurrentBalance balance) {
    }
}
