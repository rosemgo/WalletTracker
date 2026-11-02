package it.wallettracker.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import it.wallettracker.bank.enablebanking.EnableBankingApi.Amount;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Balance;
import org.junit.jupiter.api.Test;

/** Quale saldo mostrare, tra quelli che manda la banca (docs/saldi.md, parte B). Test puri. */
class BankBalanceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static Balance b(String type, String amount, String currency, String date) {
        return new Balance("Saldo", new Amount(new BigDecimal(amount), currency), type,
                date == null ? null : LocalDate.parse(date));
    }

    @Test
    void theBookedBalanceWinsOverTheAvailableOne() {
        // Il disponibile può comprendere un fido: non sono i soldi del conto.
        var chosen = BankBalance.choose(List.of(
                b("ITAV", "2500.00", "EUR", "2026-11-01"),
                b("CLBD", "1234.56", "EUR", "2026-10-31")), "EUR", TODAY);

        assertThat(chosen).hasValueSatisfying(c -> {
            assertThat(c.amount()).isEqualByComparingTo("1234.56");
            assertThat(c.date()).isEqualTo(LocalDate.of(2026, 10, 31));
            assertThat(c.type()).isEqualTo("CLBD");
        });
    }

    @Test
    void amongTheBookedOnesTheMostRecentAndOnTheSameDayTheIntraday() {
        assertThat(BankBalance.choose(List.of(
                b("CLBD", "100", "EUR", "2026-10-30"),
                b("ITBD", "120", "EUR", "2026-11-01")), "EUR", TODAY))
                .hasValueSatisfying(c -> assertThat(c.amount()).isEqualByComparingTo("120"));
        assertThat(BankBalance.choose(List.of(
                b("CLBD", "100", "EUR", "2026-11-01"),
                b("ITBD", "120", "EUR", "2026-11-01")), "EUR", TODAY))
                .hasValueSatisfying(c -> assertThat(c.type()).isEqualTo("ITBD"));
    }

    @Test
    void withoutBookedBalancesTheExpectedThenTheAvailableThenAnything() {
        assertThat(BankBalance.choose(List.of(b("ITAV", "1", "EUR", null), b("XPCD", "2", "EUR", null)), "EUR",
                TODAY)).hasValueSatisfying(c -> assertThat(c.type()).isEqualTo("XPCD"));
        // Senza giorno della banca, vale oggi.
        assertThat(BankBalance.choose(List.of(b("ITAV", "1", "EUR", null)), "EUR", TODAY))
                .hasValueSatisfying(c -> assertThat(c.date()).isEqualTo(TODAY));
        assertThat(BankBalance.choose(List.of(b("OTHR", "7", "EUR", "2026-10-01")), "EUR", TODAY))
                .hasValueSatisfying(c -> assertThat(c.amount()).isEqualByComparingTo("7"));
    }

    @Test
    void anotherCurrencyOrNothingUsableGivesNoBalance() {
        assertThat(BankBalance.choose(List.of(b("CLBD", "100", "USD", "2026-10-31")), "EUR", TODAY)).isEmpty();
        assertThat(BankBalance.choose(List.of(), "EUR", TODAY)).isEmpty();
        assertThat(BankBalance.choose(null, "EUR", TODAY)).isEmpty();
        assertThat(BankBalance.choose(List.of(new Balance("Saldo", null, "CLBD", null)), "EUR", TODAY)).isEmpty();
    }

    @Test
    void forACreditCardTheAvailableBalanceIsNeverUsed() {
        // Il disponibile di una carta è il plafond che resta: 2.500 € non sono il saldo della carta.
        List<Balance> card = List.of(b("ITAV", "2500.00", "EUR", "2026-11-02"));
        assertThat(BankBalance.choose(card, "EUR", TODAY, false)).isEmpty();
        assertThat(BankBalance.choose(List.of(b("ITAV", "2500.00", "EUR", null), b("CLBD", "-312.40", "EUR", null)),
                "EUR", TODAY, false)).hasValueSatisfying(c -> assertThat(c.amount()).isEqualByComparingTo("-312.40"));
        // Per gli altri conti il disponibile resta l'ultima scelta (Revolut ne dà solo uno).
        assertThat(BankBalance.choose(card, "EUR", TODAY)).isPresent();
    }

    @Test
    void theAvailableCreditOfACardIsTheMostRecentAvailableBalance() {
        assertThat(BankBalance.available(List.of(
                b("CLBD", "-312.40", "EUR", "2026-11-02"),
                b("CLAV", "2100.00", "EUR", "2026-11-01"),
                b("ITAV", "2187.60", "EUR", "2026-11-02")), "EUR", TODAY))
                .hasValueSatisfying(c -> assertThat(c.amount()).isEqualByComparingTo("2187.60"));
        assertThat(BankBalance.available(List.of(b("CLBD", "-312.40", "EUR", null)), "EUR", TODAY)).isEmpty();
        assertThat(BankBalance.available(null, "EUR", TODAY)).isEmpty();
    }
}
