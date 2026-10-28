package it.wallettracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** La regola del blocco, da sola: niente database, niente Spring. */
class AppPinTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void fiveWrongPinsAreFreeThenTheWaitGrowsUpToAnHour() {
        AppPin pin = new AppPin("{bcrypt}hash-finto", NOW);

        for (int i = 1; i <= 4; i++) {
            pin.failed(NOW);
            assertThat(pin.lockedFor(NOW)).as("errore n. %d", i).isZero();
        }
        assertThat(pin.attemptsBeforeLock()).isEqualTo(1);

        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(1)};
        for (int i = 0; i < expected.length; i++) {
            pin.failed(NOW);
            assertThat(pin.lockedFor(NOW)).as("errore n. %d", 5 + i).isEqualTo(expected[i]);
        }
        assertThat(pin.attemptsBeforeLock()).isZero();
    }

    @Test
    void theLockEndsByItselfAndTheRightPinStartsTheCountAgain() {
        AppPin pin = new AppPin("{bcrypt}hash-finto", NOW);
        for (int i = 0; i < 5; i++) {
            pin.failed(NOW);
        }
        assertThat(pin.lockedFor(NOW.plusSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(pin.lockedFor(NOW.plusSeconds(60))).isZero();

        pin.succeeded(NOW.plusSeconds(61));

        assertThat(pin.failedAttempts()).isZero();
        assertThat(pin.attemptsBeforeLock()).isEqualTo(5);
        assertThat(pin.lockedFor(NOW.plusSeconds(61))).isZero();
    }
}
