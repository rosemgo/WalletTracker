package it.wallettracker.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Test "puri" della chiave dell'esercente. */
class MerchantKeyTest {

    @Test
    void datesTimesAndNumbersDisappearSoTheSameMerchantHasTheSameKey() {
        String october = MerchantKey.of(null, "Pagamento carta del 03/10/2026 alle 10:12 presso NETFLIX.COM 4521");
        String november = MerchantKey.of(null, "Pagamento carta del 03/11/2026 alle 09:58 presso Netflix.com 4521");

        assertThat(october).isEqualTo("PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM");
        assertThat(november).isEqualTo(october);
    }

    @Test
    void theCounterpartyIsPreferredToTheDescription() {
        assertThat(MerchantKey.of("Spotify AB", "Pagamento 123")).isEqualTo("SPOTIFY AB");
        assertThat(MerchantKey.of("  ", "Palestra Fit")).isEqualTo("PALESTRA FIT");
    }

    @Test
    void accentedLettersAreKept() {
        assertThat(MerchantKey.of("Caffè Città", null)).isEqualTo("CAFFÈ CITTÀ");
    }

    @Test
    void withoutUsefulTextThereIsNoKey() {
        assertThat(MerchantKey.of(null, null)).isNull();
        assertThat(MerchantKey.of(null, "12/10/2026 4521")).isNull();
    }

    @Test
    void longTextsAreCut() {
        assertThat(MerchantKey.of("A".repeat(100), null)).hasSize(MerchantKey.MAX_LENGTH);
    }

    @Test
    void theProposedNameHasNoDatesOrNumbers() {
        assertThat(MerchantKey.displayName("Pagamento carta del 14/10/2026 alle 10:10 presso NETFLIX.COM 4521"))
                .isEqualTo("Pagamento carta del alle presso NETFLIX.COM");
        assertThat(MerchantKey.displayName("Spotify AB")).isEqualTo("Spotify AB");
        assertThat(MerchantKey.displayName("12345")).isEqualTo("12345"); // se resta vuoto, meglio il testo intero
    }
}
