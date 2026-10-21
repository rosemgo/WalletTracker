package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Quali esercenti chiedere all'IA, e con quale domanda: funzioni pure, senza database né Ollama. */
class AiQuestionsTest {

    static final Category SPESA = category(1L, "Spesa alimentare", CategoryKind.EXPENSE);
    static final Category RISTORANTI = category(2L, "Ristoranti", CategoryKind.EXPENSE);
    static final Category STIPENDIO = category(3L, "Stipendio", CategoryKind.INCOME);
    static final List<Category> CATEGORIES = List.of(SPESA, RISTORANTI, STIPENDIO);
    static final List<Set<String>> OWNERS = List.of(Set.of("MARIO", "ROSSI"));

    int counter;

    static Category category(Long id, String name, CategoryKind kind) {
        Category category = new Category(name, kind);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    BankTransaction transaction(String day, String amount, String counterparty, String description,
            TransactionType type, Category manualCategory) {
        BankTransaction t = new BankTransaction(null, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(day), null, new BigDecimal(amount), "EUR", counterparty, description, "{}");
        t.setManualCategory(manualCategory);
        t.classify(type, null, null, null);
        return t;
    }

    BankTransaction uncategorized(String day, String counterparty, TransactionType type) {
        return transaction(day, type == TransactionType.INCOME ? "100" : "-10", counterparty, null, type, null);
    }

    @Test
    void asksOnlyMerchantsYouNeverChoseAndNotAskedYet() {
        List<BankTransaction> manual = List.of(
                transaction("2026-10-01", "-20", "ESSELUNGA", null, TransactionType.EXPENSE, SPESA));
        List<BankTransaction> uncategorized = List.of(
                uncategorized("2026-10-05", "ESSELUNGA", TransactionType.EXPENSE),   // lo hai scelto tu
                uncategorized("2026-10-06", "CONAD", TransactionType.EXPENSE),
                uncategorized("2026-10-07", "BAR CENTRALE", TransactionType.EXPENSE)); // già chiesto

        List<AiQuestions.Ask> asks = AiQuestions.build(uncategorized, manual, CATEGORIES, OWNERS,
                List.of("BAR CENTRALE"), 20);

        assertThat(asks).extracting(AiQuestions.Ask::merchantKey).containsExactly("CONAD");
    }

    @Test
    void theMerchantsWithMoreMovementsFirstAndAtMostTheLimit() {
        List<BankTransaction> uncategorized = new ArrayList<>(List.of(
                uncategorized("2026-10-01", "CONAD", TransactionType.EXPENSE),
                uncategorized("2026-10-02", "BAR CENTRALE", TransactionType.EXPENSE),
                uncategorized("2026-10-03", "BAR CENTRALE", TransactionType.EXPENSE),
                uncategorized("2026-10-04", "ZARA", TransactionType.EXPENSE)));

        List<AiQuestions.Ask> asks = AiQuestions.build(uncategorized, List.of(), CATEGORIES, OWNERS, List.of(), 2);

        assertThat(asks).extracting(AiQuestions.Ask::merchantKey, AiQuestions.Ask::transactions)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("BAR CENTRALE", 2),
                        org.assertj.core.groups.Tuple.tuple("CONAD", 1)); // a parità, in ordine alfabetico
    }

    @Test
    void theQuestionHasTheRightCategoriesYourChoicesAsExamplesAndNoNumbers() {
        List<BankTransaction> manual = List.of(
                transaction("2026-10-01", "-20", "ESSELUNGA", null, TransactionType.EXPENSE, SPESA),
                transaction("2026-10-02", "1500", "ACME SPA", null, TransactionType.INCOME, STIPENDIO));
        List<BankTransaction> uncategorized = List.of(
                // la controparte è il titolare: l'esercente è nella causale, senza numero di carta
                transaction("2026-10-05", "-30", "ROSSI MARIO", "PAGAMENTO CARTA 4521 CONAD", TransactionType.EXPENSE,
                        null),
                uncategorized("2026-10-06", "INPS", TransactionType.INCOME));

        List<AiQuestions.Ask> asks = AiQuestions.build(uncategorized, manual, CATEGORIES, OWNERS, List.of(), 20);

        CategoryQuestion conad = asks.stream().filter(a -> a.merchantKey().contains("CONAD")).findFirst().orElseThrow()
                .question();
        assertThat(conad.merchant()).isEqualTo("PAGAMENTO CARTA CONAD");
        assertThat(conad.expense()).isTrue();
        assertThat(conad.categories()).extracting(CategoryQuestion.Option::name)
                .containsExactly("Ristoranti", "Spesa alimentare");
        assertThat(conad.examples()).extracting(CategoryQuestion.Example::merchant)
                .containsExactly("ACME SPA", "ESSELUNGA"); // le tue scelte, la più recente per prima

        CategoryQuestion inps = asks.stream().filter(a -> a.merchantKey().equals("INPS")).findFirst().orElseThrow()
                .question();
        assertThat(inps.expense()).isFalse();
        assertThat(inps.categories()).extracting(CategoryQuestion.Option::name).containsExactly("Stipendio");
    }

    @Test
    void transfersAndInvestmentsAreNotAsked() {
        List<BankTransaction> uncategorized = List.of(
                uncategorized("2026-10-01", "ROSSI MARIO", TransactionType.INTERNAL_TRANSFER),
                uncategorized("2026-10-02", "ISHARES", TransactionType.SECURITIES_BUY));

        assertThat(AiQuestions.build(uncategorized, List.of(), CATEGORIES, OWNERS, List.of(), 20)).isEmpty();
    }
}
