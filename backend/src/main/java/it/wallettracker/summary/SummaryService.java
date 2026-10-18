package it.wallettracker.summary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.summary.CurrencySummary.CategoryExpense;
import it.wallettracker.transaction.BankTransactionRepository.CategoryTotal;
import it.wallettracker.transaction.BankTransactionRepository.MonthlyTotal;
import it.wallettracker.transaction.TransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il riepilogo mese per mese: entrate, spese, risparmio.
 *
 * <p>Qui si vede perché la classificazione è il cuore del progetto: il riepilogo non guarda i conti né le
 * banche, guarda solo il <b>tipo</b> di ogni movimento (già corretto a mano, se l'utente l'ha fatto).
 * <ul>
 *   <li>INCOME → entrate; EXPENSE → spese;</li>
 *   <li>INVESTMENT_INCOME e INVESTMENT_TAX → rendite nette degli investimenti, tenute a parte;</li>
 *   <li>TO_REVIEW (o non ancora classificato) → contato, perché i numeri di quel mese sono incompleti;</li>
 *   <li>tutto il resto (trasferimenti tra conti propri, saldo carta, compravendita titoli, ignorati) → non
 *       conta: sono soldi che si spostano, non che entrano o escono.</li>
 * </ul>
 * Contano anche i movimenti in attesa (PENDING): sono spese vere, solo non ancora definitive.
 */
@Service
public class SummaryService {

    /** Il periodo più lungo che si può chiedere: 10 anni bastano, e una richiesta enorme non serve a nessuno. */
    static final int MAX_MONTHS = 120;

    private final BankTransactionRepository repository;

    public SummaryService(BankTransactionRepository repository) {
        this.repository = repository;
    }

    /** Il riepilogo da {@code from} a {@code to}, entrambi inclusi. */
    @Transactional(readOnly = true)
    public SummaryDto monthly(YearMonth from, YearMonth to) {
        long months = ChronoUnit.MONTHS.between(from, to) + 1;
        if (months < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il mese iniziale viene dopo quello finale.");
        }
        if (months > MAX_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Puoi chiedere al massimo " + MAX_MONTHS + " mesi alla volta.");
        }

        // 1. Le righe già sommate dal database, divise per valuta. TreeMap tiene le valute in ordine alfabetico.
        LocalDate start = from.atDay(1);
        LocalDate end = to.plusMonths(1).atDay(1); // escluso: il primo giorno del mese dopo
        Map<String, List<MonthlyTotal>> rowsByCurrency = new TreeMap<>();
        for (MonthlyTotal row : repository.sumByMonthAndType(start, end)) {
            rowsByCurrency.computeIfAbsent(row.getCurrency(), currency -> new ArrayList<>()).add(row);
            //La riga di sopra sarebbe queste:
            //List<MonthlyTotal> list = rowsByCurrency.computeIfAbsent(row.getCurrency(),  currency ->new ArrayList<>());
            //list.add(row);

        }
        Map<String, List<CategoryExpense>> categoriesByCurrency = new TreeMap<>();
        for (CategoryTotal row : repository.sumExpensesByCategory(start, end)) {
            categoriesByCurrency.computeIfAbsent(row.getCurrency(), currency -> new ArrayList<>())
                    .add(new CategoryExpense(row.getCategoryId(), row.getCategory(), row.getTotal().negate(),
                            row.getMovements()));
        }

        // 2. Per ogni valuta, un elemento per OGNI mese del periodo: anche i mesi vuoti, così il grafico
        //    non salta i mesi senza movimenti.
        List<CurrencySummary> currencies = new ArrayList<>();
        rowsByCurrency.forEach((currency, rows) -> {
            List<MonthSummary> monthSummaries = new ArrayList<>();
            for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                monthSummaries.add(summarize(month, rowsOf(rows, month)));
            }
            List<CategoryExpense> categories = new ArrayList<>(categoriesByCurrency.getOrDefault(currency, List.of()));
            categories.sort(Comparator.comparing(CategoryExpense::amount).reversed()); // dalla spesa più alta
            currencies.add(new CurrencySummary(currency, monthSummaries, totals(monthSummaries), categories));
        });
        return new SummaryDto(from, to, currencies);
    }

    private static List<MonthlyTotal> rowsOf(List<MonthlyTotal> rows, YearMonth month) {
        return rows.stream()
                .filter(row -> row.getBookingYear() == month.getYear() && row.getBookingMonth() == month.getMonthValue())
                .toList();
    }

    /** Trasforma le righe di un mese (una per tipo) nei numeri del mese. */
    private static MonthSummary summarize(YearMonth month, List<MonthlyTotal> rows) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        BigDecimal investmentNet = BigDecimal.ZERO;
        long toReview = 0;
        for (MonthlyTotal row : rows) {
            TransactionType type = row.getType();
            if (type == null || type == TransactionType.TO_REVIEW) {
                toReview += row.getMovements();
                continue;
            }
            switch (type) {
                case INCOME -> income = income.add(row.getTotal());
                // Le spese sono importi negativi: negate() le rende positive. Un'eventuale "spesa" positiva
                // (un rimborso) diventa negativa e quindi riduce le spese del mese, che è quello che vogliamo.
                case EXPENSE -> expenses = expenses.add(row.getTotal().negate());
                case INVESTMENT_INCOME, INVESTMENT_TAX -> investmentNet = investmentNet.add(row.getTotal());
                default -> {
                    // Trasferimenti, saldo carta, titoli, ignorati: non sono né entrate né spese.
                }
            }
        }
        return new MonthSummary(month, income, expenses, income.subtract(expenses), investmentNet, toReview);
    }

    private static CurrencySummary.Totals totals(List<MonthSummary> months) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        BigDecimal investmentNet = BigDecimal.ZERO;
        long toReview = 0;
        for (MonthSummary month : months) {
            income = income.add(month.income());
            expenses = expenses.add(month.expenses());
            investmentNet = investmentNet.add(month.investmentNet());
            toReview += month.toReview();
        }
        return new CurrencySummary.Totals(income, expenses, income.subtract(expenses), investmentNet, toReview);
    }
}
