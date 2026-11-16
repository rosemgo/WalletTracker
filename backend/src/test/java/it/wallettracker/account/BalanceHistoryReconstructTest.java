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

    /** Il saldo di adesso, vero oggi. */
    private static BalanceHistoryService.Anchor now(String amount) {
        return new BalanceHistoryService.Anchor(TODAY, eur(amount));
    }

    @Test
    void withoutPhotosEveryDayButTodayIsTheBalanceMinusTheMovementsAfter() {
        // Oggi 1.000 €; il 15 è arrivato lo stipendio (+1.500), il 14 una spesa (-200).
        List<Point> points = BalanceHistoryService.reconstruct(day(12), TODAY, Map.of(1L, now("1000")), Map.of(),
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
        List<Point> points = BalanceHistoryService.reconstruct(day(13), TODAY, Map.of(1L, now("1000")),
                Map.of(1L, Map.of(day(14), eur("700"))),
                Map.of(1L, Map.of(day(14), eur("-50"), day(16), eur("100"))));

        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(750, 700, 900, 1000);
        assertThat(points).extracting(Point::estimated).containsExactly(true, false, true, false);
    }

    @Test
    void theAccountsAddUpAndADayIsTrueOnlyIfEveryAccountHasItsPhoto() {
        List<Point> points = BalanceHistoryService.reconstruct(day(15), TODAY,
                Map.of(1L, now("1000"), 2L, now("250")),
                Map.of(1L, Map.of(day(15), eur("900")), 2L, Map.of(day(15), eur("250"))),
                Map.of());
        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(1150, 1250);
        assertThat(points).extracting(Point::estimated).containsExactly(false, false);

        List<Point> onlyOne = BalanceHistoryService.reconstruct(day(15), TODAY,
                Map.of(1L, now("1000"), 2L, now("250")), Map.of(1L, Map.of(day(15), eur("900"))), Map.of());
        assertThat(onlyOne.getFirst().estimated()).isTrue();
    }

    @Test
    void afterTheLastTrueBalanceTheDaysGoForwardWithTheMovements() {
        // La banca ha dato il saldo il 14 (900 €); il 15 e oggi sono arrivati dei movimenti, il saldo non ancora.
        List<Point> points = BalanceHistoryService.reconstruct(day(13), TODAY,
                Map.of(1L, new BalanceHistoryService.Anchor(day(14), eur("900"))), Map.of(),
                Map.of(1L, Map.of(day(14), eur("-100"), day(15), eur("-50"), day(16), eur("1500"))));

        // Il 13 sera: 900 + 100; il 15: 900 − 50; oggi: 850 + 1.500. Vero solo il 14.
        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(1000, 900, 850, 2350);
        assertThat(points).extracting(Point::estimated).containsExactly(true, false, true, true);
    }

    @Test
    void aBankBalanceOlderThanTheStartIsBroughtForward() {
        List<Point> points = BalanceHistoryService.reconstruct(day(15), TODAY,
                Map.of(1L, new BalanceHistoryService.Anchor(day(10), eur("500"))), Map.of(),
                Map.of(1L, Map.of(day(12), eur("20"), day(16), eur("5"))));
        assertThat(points).extracting(p -> p.amount().intValue()).containsExactly(520, 525);
        assertThat(points).extracting(Point::estimated).containsExactly(true, true);
    }

    @Test
    void aBankBalanceIsTrueOnItsOwnDayAndAWrittenOneToday() {
        CurrentBalance bank = new CurrentBalance(eur("10"), day(14), BalanceSource.BANK, null);
        CurrentBalance written = new CurrentBalance(eur("10"), day(14), BalanceSource.MANUAL, day(1));
        CurrentBalance future = new CurrentBalance(eur("10"), day(20), BalanceSource.BANK, null);
        assertThat(BalanceHistoryService.trueOn(bank, TODAY)).isEqualTo(day(14));
        assertThat(BalanceHistoryService.trueOn(written, TODAY)).isEqualTo(TODAY);
        assertThat(BalanceHistoryService.trueOn(future, TODAY)).isEqualTo(TODAY);
    }
}
