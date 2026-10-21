package it.wallettracker.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import it.wallettracker.subscription.RecurringDetector.Pattern;
import it.wallettracker.subscription.RecurringDetector.Payment;
import org.junit.jupiter.api.Test;

/**
 * Test "puri" del riconoscimento: niente Spring, niente database, niente orologio. Girano in pochi
 * millisecondi, quindi possiamo provare tanti casi.
 */
class RecurringDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Test
    void aRegularMonthlyPaymentIsASubscription() {
        Optional<Pattern> pattern = RecurringDetector.detect(
                payments("2026-07-03:12.99", "2026-08-03:12.99", "2026-09-03:12.99", "2026-10-03:12.99"), TODAY);

        assertThat(pattern).isPresent();
        assertThat(pattern.get().frequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(pattern.get().typicalAmount()).isEqualByComparingTo("12.99");
        assertThat(pattern.get().occurrences()).isEqualTo(4);
        assertThat(pattern.get().nextDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void anotherBillFromTheSameCreditorDoesNotHideTheSubscription() {
        // I dati veri di Fastweb: la SIM ogni mese, e una bolletta di casa (l'ultima) pochi giorni dopo.
        List<Payment> fastweb = payments("2026-07-30:7.99", "2026-08-31:7.99", "2026-09-04:23.95", "2026-09-30:7.99");

        assertThat(RecurringDetector.detect(fastweb, TODAY)).isEmpty(); // mescolati: 4 giorni tra due addebiti
        List<Pattern> found = RecurringDetector.detectAll(fastweb, TODAY);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().typicalAmount()).isEqualByComparingTo("7.99");
        assertThat(found.getFirst().occurrences()).isEqualTo(3);
    }

    @Test
    void twoSubscriptionsFromTheSameCreditorAreBothFound() {
        List<Pattern> found = RecurringDetector.detectAll(payments(
                "2026-07-30:7.99", "2026-08-31:7.99", "2026-09-30:7.99",
                "2026-07-15:23.95", "2026-08-15:23.95", "2026-09-15:23.95"), TODAY);

        assertThat(found).extracting(Pattern::typicalAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("7.99"), new BigDecimal("23.95"));
    }

    @Test
    void aSmallPriceRiseStaysOneSubscription() {
        // 12,99 → 13,99: la serie intera è già un abbonamento, non la dividiamo.
        assertThat(RecurringDetector.detectAll(
                payments("2026-07-03:12.99", "2026-08-03:12.99", "2026-09-03:13.99", "2026-10-03:13.99"), TODAY))
                .hasSize(1);
    }

    @Test
    void amountsAreGroupedWithinTwentyPercentOfTheSmallest() {
        assertThat(RecurringDetector.amountGroups(payments(
                "2026-07-01:7.99", "2026-08-01:23.95", "2026-09-01:9.50", "2026-10-01:7.99")))
                .extracting(List::size)
                .containsExactly(3, 1); // {7,99 7,99 9,50} e {23,95}
    }

    @Test
    void theOrderOfThePaymentsDoesNotMatter() {
        assertThat(RecurringDetector.detect(
                payments("2026-10-03:9.99", "2026-08-03:9.99", "2026-09-03:9.99"), TODAY)).isPresent();
    }

    @Test
    void aPriceIncreaseOrASkippedMonthDoNotHideASubscription() {
        // Settembre saltato (61 giorni) e un rincaro da 12,99 a 13,99: resta un abbonamento.
        Optional<Pattern> pattern = RecurringDetector.detect(payments("2026-05-05:12.99", "2026-06-05:12.99",
                "2026-07-05:12.99", "2026-08-05:13.99", "2026-10-05:13.99"), TODAY);

        assertThat(pattern).isPresent();
        assertThat(pattern.get().lastAmount()).isEqualByComparingTo("13.99");
    }

    @Test
    void theSupermarketEveryMonthIsNotASubscription() {
        // Ogni mese, ma con importi sempre diversi.
        assertThat(RecurringDetector.detect(payments("2026-07-04:85.40", "2026-08-03:120.10",
                "2026-09-05:42.00", "2026-10-04:96.70"), TODAY)).isEmpty();
    }

    @Test
    void irregularIntervalsAreNotASubscription() {
        assertThat(RecurringDetector.detect(payments("2026-06-01:20.00", "2026-06-09:20.00",
                "2026-08-20:20.00", "2026-09-02:20.00", "2026-10-15:20.00"), TODAY)).isEmpty();
    }

    @Test
    void twoMonthlyPaymentsAreNotEnough() {
        assertThat(RecurringDetector.detect(payments("2026-09-03:12.99", "2026-10-03:12.99"), TODAY)).isEmpty();
    }

    @Test
    void aYearlyPaymentIsRecognisedWithTwoOccurrences() {
        Optional<Pattern> pattern = RecurringDetector.detect(
                payments("2025-10-10:49.00", "2026-10-09:49.00"), TODAY);

        assertThat(pattern).isPresent();
        assertThat(pattern.get().frequency()).isEqualTo(Frequency.YEARLY);
        assertThat(pattern.get().nextDate()).isEqualTo(LocalDate.of(2027, 10, 9));
    }

    @Test
    void aSubscriptionThatStoppedLongAgoIsNotSuggested() {
        // Regolarissimo, ma l'ultimo pagamento è di marzo: probabilmente disdetto.
        assertThat(RecurringDetector.detect(
                payments("2026-01-03:12.99", "2026-02-03:12.99", "2026-03-03:12.99"), TODAY)).isEmpty();
    }

    @Test
    void theMedianIsNotMovedByOneStrangeValue() {
        assertThat(RecurringDetector.median(List.of(30L, 31L, 29L, 300L))).isEqualTo(30L);
        assertThat(RecurringDetector.median(List.of(28L, 31L, 30L))).isEqualTo(30L);
    }

    /** "2026-07-03:12.99" → un pagamento del 3 luglio da 12,99. */
    private static List<Payment> payments(String... values) {
        List<Payment> payments = new ArrayList<>();
        for (String value : values) {
            String[] parts = value.split(":");
            payments.add(new Payment(LocalDate.parse(parts[0]), new BigDecimal(parts[1])));
        }
        return payments;
    }
}
