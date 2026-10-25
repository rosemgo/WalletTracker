package it.wallettracker.explore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.budget.BudgetDto;

/** I record delle API di Analisi (docs/38), raccolti in un file solo perché sono piccoli e vanno insieme. */
public final class ExploreDtos {

    private ExploreDtos() {
    }

    /** Che cosa si guarda: le spese, le entrate, o il netto (entrate meno spese). */
    public enum Metric {
        EXPENSES, INCOME, NET
    }

    /** Il periodo delle barre: una settimana (barre = giorni), un mese (settimane), 6 mesi o un anno (mesi). */
    public enum Period {
        WEEK, MONTH, SIX_MONTHS, YEAR
    }

    /** Come raggruppare i movimenti di un periodo. */
    public enum By {
        CATEGORY, MERCHANT, ACCOUNT
    }

    /** L'unità di una barra, per la media ("al giorno", "a settimana", "al mese"). */
    public enum Unit {
        DAY, WEEK, MONTH
    }

    /** Una barra: un giorno, una settimana o un mese, con il suo valore. {@code future}: non è ancora successa. */
    public record Bar(LocalDate from, LocalDate to, BigDecimal value, boolean future) {
    }

    /**
     * {@code GET /api/explore/series}: le barre del grafico.
     *
     * @param averagePerUnit la media di solito per barra (al giorno, a settimana, al mese): la linea del grafico; null
     *                       senza mesi completi
     * @param usualMonths    da quanti mesi è calcolata la media
     */
    public record Series(Metric metric, Period period, Long categoryId, String currency, List<String> currencies,
            Unit unit, List<Bar> bars, BigDecimal averagePerUnit, int usualMonths) {
    }

    /**
     * Un gruppo di movimenti: una categoria, un esercente o un conto.
     *
     * @param key        la chiave del gruppo (l'id della categoria o del conto, la chiave dell'esercente); null per i
     *                   movimenti senza categoria o senza testo
     * @param categoryId l'id della categoria, se i gruppi sono categorie
     * @param usual      di solito, allo stesso giorno del mese (solo per le categorie, e per un mese)
     */
    public record Group(String key, Long categoryId, String name, BigDecimal amount, int movements,
            BigDecimal usual) {
    }

    /**
     * {@code GET /api/explore/groups}: i movimenti di un periodo (una barra) raggruppati. Per il netto, le entrate e le
     * spese in due elenchi; altrimenti uno solo, in {@code groups}.
     */
    public record Groups(Metric metric, By by, LocalDate from, LocalDate to, String currency, BigDecimal total,
            List<Group> groups, List<Group> income, List<Group> expenses) {
    }

    /** Un mese nel grafico di una categoria. */
    public record MonthValue(YearMonth month, BigDecimal value) {
    }

    /**
     * {@code GET /api/categories/{id}/overview}: la pagina di una categoria.
     *
     * @param amount     spese (o entrate, per una categoria di entrata) del mese, fino a {@code day}
     * @param usualSoFar di solito, allo stesso giorno
     * @param usualMonth di solito, in un mese intero: la linea del grafico
     * @param months     gli ultimi 12 mesi, fino a quello guardato
     * @param merchants  dove, nel mese: gli esercenti
     * @param budget     il budget della categoria nel mese, con il ritmo; null se non c'è
     */
    public record CategoryOverview(Long categoryId, String name, Metric metric, YearMonth month, LocalDate day,
            String currency, BigDecimal amount, BigDecimal usualSoFar, BigDecimal usualMonth, int usualMonths,
            List<MonthValue> months, List<Group> merchants, BudgetDto budget) {
    }
}
