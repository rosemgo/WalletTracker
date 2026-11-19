package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/** La deduzione del controllo dei saldi (docs/saldi.md, parte Q), senza database. */
class BalanceCheckExplainsTest {

    private static BigDecimal eur(String amount) {
        return new BigDecimal(amount);
    }

    @Test
    void aDifferenceIsExplainedByOneOrMoreLateMovements() {
        assertThat(BalanceCheckService.explains(List.of(eur("30")), eur("30.00"))).isTrue();
        assertThat(BalanceCheckService.explains(List.of(eur("30"), eur("12.50"), eur("-4")), eur("26"))).isTrue();
        assertThat(BalanceCheckService.explains(List.of(eur("30"), eur("12.50")), eur("10"))).isFalse();
        assertThat(BalanceCheckService.explains(List.of(), eur("10"))).isFalse();
    }

    @Test
    void withManyLateMovementsOnlyAllOfThemTogetherExplainIt() {
        List<BigDecimal> many = Collections.nCopies(BalanceCheckService.MAX_LATE + 1, eur("1"));
        assertThat(BalanceCheckService.explains(many, eur("13"))).isTrue();
        assertThat(BalanceCheckService.explains(many, eur("2"))).isFalse();
    }
}
