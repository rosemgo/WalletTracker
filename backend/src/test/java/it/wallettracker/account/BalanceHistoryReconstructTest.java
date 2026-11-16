package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import it.wallettracker.account.BalanceHistory.Point;
import org.junit.jupiter.api.Test;

/** Il calcolo dello storico della liquidità (docs/saldi.md, parte L), senza database. */
class BalanceHistoryReconstructTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private static LocalDate day(int d) {
        return LocalDate.of(2026, 11, d);
    }

    private static BigDecimal eur(String amount) {
        return new BigDecimal(amount);
    }

    @Test
    void withoutPhotosEveryDayButTodayIsTheBalanceMinusTheMovementsAfter() {
        // Oggi 1.000 €; il 15 è arrivato lo stipendio (+1.500), il 14 una spesa (-200).
        List<Point> points = BalanceHistoryService.reconstruct(day(12), TODAY, Map.of(1L, eur("1000")), Map.of(),
                Map.of(1L, Map.of(day(15), eur("1500"), day(14), eur("-200"))));

        assertThat(points).extracting(Point::date).containsExactly(day(12), day(13), day(14), day(15), day(16));
        // Il 14 sera: 1.000 − 1.500 = −500; prima della spesa del 14: −300.
        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(-300, -300, -500, 1000, 1000);
        assertThat(points).extracting(Point::estimated).containsExactly(true, true, true, true, false);
    }

    @Test
    void aPhotoIsTrueAndTheDaysBeforeStartFromIt() {
        // La fotografia del 14 dice 700, anche se i movimenti direbbero altro (ne manca uno nell'app): vince lei, e i
        // giorni prima si stimano da lì.
        List<Point> points = BalanceHistoryService.reconstruct(day(13), TODAY, Map.of(1L, eur("1000")),
                Map.of(1L, Map.of(day(14), eur("700"))),
                Map.of(1L, Map.of(day(14), eur("-50"), day(16), eur("100"))));

        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(750, 700, 900, 1000);
        assertThat(points).extracting(Point::estimated).containsExactly(true, false, true, false);
    }

    @Test
    void theAccountsAddUpAndADayIsTrueOnlyIfEveryAccountHasItsPhoto() {
        List<Point> points = BalanceHistoryService.reconstruct(day(15), TODAY,
                Map.of(1L, eur("1000"), 2L, eur("250")),
                Map.of(1L, Map.of(day(15), eur("900")), 2L, Map.of(day(15), eur("250"))),
                Map.of());
        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(1150, 1250);
        assertThat(points).extracting(Point::estimated).containsExactly(false, false);

        List<Point> onlyOne = BalanceHistoryService.reconstruct(day(15), TODAY,
                Map.of(1L, eur("1000"), 2L, eur("250")), Map.of(1L, Map.of(day(15), eur("900"))), Map.of());
        assertThat(onlyOne.getFirst().estimated()).isTrue();
    }
}
