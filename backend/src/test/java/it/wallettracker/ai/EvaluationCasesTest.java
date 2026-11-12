package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Dai movimenti categorizzati a mano ai casi della misura: funzioni pure, senza database né Ollama. */
class EvaluationCasesTest {

    static final Category SPESA = category(1L, "Spesa alimentare", CategoryKind.EXPENSE);
    static final Category RISTORANTI = category(2L, "Ristoranti", CategoryKind.EXPENSE);
    static final Category STIPENDIO = category(3L, "Stipendio", CategoryKind.INCOME);
    static final Category ETF = category(4L, "ETF", CategoryKind.INVESTMENT);
    static final List<Category> CATEGORIES = List.of(SPESA, RISTORANTI, STIPENDIO, ETF);

    /** Il titolare dei conti (vedi ClassificationService.ownerNames). */
    static final List<Set<String>> OWNERS = List.of(Set.of("MARIO", "ROSSI"));

    int counter;

    static Category category(Long id, String name, CategoryKind kind) {
        Category category = new Category(name, kind);
        ReflectionTestUtils.setField(category, "id", id); // senza database l'id lo mettiamo noi
        return category;
    }

    BankTransaction manual(String day, String amount, String counterparty, String description, TransactionType type,
            Category category) {
        BankTransaction t = new BankTransaction(null, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(day), null, new BigDecimal(amount), "EUR", counterparty, description, "{}");
        t.setManualCategory(category);
        t.classify(type, null, null, null);
        return t;
    }

    @Test
    void oneCasePerMerchantTheMostRecent() {
        List<ModelEvaluation.Case> cases = EvaluationCases.build(List.of(
                manual("2026-09-01", "-20", "ESSELUNGA", null, TransactionType.EXPENSE, SPESA),
                manual("2026-10-01", "-30", "ESSELUNGA", null, TransactionType.EXPENSE, RISTORANTI),
                manual("2026-09-15", "-15", "PIZZERIA DA GINO", null, TransactionType.EXPENSE, RISTORANTI)),
                CATEGORIES, OWNERS, 10);

        assertThat(cases).extracting(c -> c.question().merchant(), ModelEvaluation.Case::expected)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ESSELUNGA", "Ristoranti"), // il più recente vince
                        org.assertj.core.groups.Tuple.tuple("PIZZERIA DA GINO", "Ristoranti"));
    }

    @Test
    void theExamplesNeverContainTheMerchantToGuess() {
        List<ModelEvaluation.Case> cases = EvaluationCases.build(List.of(
                manual("2026-10-03", "-20", "ESSELUNGA", null, TransactionType.EXPENSE, SPESA),
                manual("2026-10-02", "-15", "PIZZERIA DA GINO", null, TransactionType.EXPENSE, RISTORANTI),
                manual("2026-10-01", "1500", "ACME SPA", null, TransactionType.INCOME, STIPENDIO)),
                CATEGORIES, OWNERS, 10);

        ModelEvaluation.Case esselunga = cases.get(0);
        assertThat(esselunga.question().examples()).extracting(CategoryQuestion.Example::merchant)
                .containsExactly("PIZZERIA DA GINO", "ACME SPA");
        assertThat(esselunga.question().examples()).noneMatch(e -> e.merchant().contains("ESSELUNGA"));
    }

    @Test
    void anExpenseChoosesAmongExpenseCategoriesAndAnIncomeAmongIncomeOnes() {
        List<ModelEvaluation.Case> cases = EvaluationCases.build(List.of(
                manual("2026-10-02", "-20", "ESSELUNGA", null, TransactionType.EXPENSE, SPESA),
                manual("2026-10-01", "1500", "ACME SPA", null, TransactionType.INCOME, STIPENDIO)),
                CATEGORIES, OWNERS, 10);

        assertThat(cases.get(0).question().expense()).isTrue();
        assertThat(cases.get(0).question().categories()).extracting(CategoryQuestion.Option::name)
                .containsExactly("Ristoranti", "Spesa alimentare"); // stessa posizione: in ordine di nome
        assertThat(cases.get(0).expectedId()).isEqualTo(1L);
        assertThat(cases.get(1).question().expense()).isFalse();
        assertThat(cases.get(1).question().categories()).extracting(CategoryQuestion.Option::name)
                .containsExactly("Stipendio");
    }

    @Test
    void skipsWhatTheModelCouldNeverGuess() {
        List<ModelEvaluation.Case> cases = EvaluationCases.build(List.of(
                // un investimento: non è né spesa né entrata
                manual("2026-10-03", "-500", "ISHARES MSCI", null, TransactionType.SECURITIES_BUY, ETF),
                // una spesa con una categoria di entrata: il modello sceglie solo tra le categorie di spesa
                manual("2026-10-02", "-20", "RIMBORSO STRANO", null, TransactionType.EXPENSE, STIPENDIO),
                // nessun testo: niente esercente
                manual("2026-10-01", "-20", null, "  ", TransactionType.EXPENSE, SPESA)),
                CATEGORIES, OWNERS, 10);

        assertThat(cases).isEmpty();
    }

    @Test
    void neverSendsTheOwnerNameNorNumbers() {
        List<ModelEvaluation.Case> cases = EvaluationCases.build(List.of(
                // la controparte è il titolare: l'esercente è nella causale (vedi MerchantKey)
                manual("2026-10-01", "-12.99", "ROSSI MARIO",
                        "PAGAMENTO CARTA 4521 DEL 03/10/2026 NETFLIX.COM IT60X0542811101000000123456",
                        TransactionType.EXPENSE, SPESA)),
                CATEGORIES, OWNERS, 10);

        String merchant = cases.get(0).question().merchant();
        assertThat(merchant).isEqualTo("PAGAMENTO CARTA DEL NETFLIX.COM");
        assertThat(merchant).doesNotContain("ROSSI", "4521", "IT60");
    }

    @Test
    void ofADirectDebitOnlyTheCreditorIsSentNotTheDebtor() {
        // Nella causale di un addebito SDD c'è anche il debitore, cioè il titolare: prima arrivava al modello.
        List<ModelEvaluation.Case> cases = EvaluationCases.build(List.of(
                manual("2026-10-05", "-45.10", null, "Addebito SDD CORE Scad. 05/10/2026 Imp. 45,10 Creditor id. "
                        + "IT00ZZZ0000000000 GAMMA LUCE E GAS S.P.A. Id Mandato 0000000001 Debitore MARIO ROSSI",
                        TransactionType.EXPENSE, SPESA)),
                CATEGORIES, OWNERS, 10);

        assertThat(cases.get(0).question().merchant()).isEqualTo("GAMMA LUCE E GAS S.P.A.");
    }

    @Test
    void respectsTheLimitAndTheNumberOfExamples() {
        List<BankTransaction> manual = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String merchant = "NEGOZIO " + (char) ('A' + i % 26) + (char) ('A' + i / 26);
            manual.add(manual("2026-10-01", "-10", merchant, null, TransactionType.EXPENSE, SPESA));
        }

        List<ModelEvaluation.Case> cases = EvaluationCases.build(manual, CATEGORIES, OWNERS, 5);

        assertThat(cases).hasSize(5);
        assertThat(cases).allSatisfy(c -> assertThat(c.question().examples()).hasSize(EvaluationCases.EXAMPLES));
    }
}
