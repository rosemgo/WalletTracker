package it.wallettracker.bank.enablebanking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Più indirizzi di ritorno, uno per ogni nome dell'app (docs/47, parte I): niente Spring, niente rete. */
class EnableBankingPropertiesTest {

    private static EnableBankingProperties withRedirect(String redirectUrl) {
        return new EnableBankingProperties("https://api.enablebanking.test", "", "", redirectUrl);
    }

    @Test
    void aSingleAddressWorksAsBefore() {
        EnableBankingProperties properties = withRedirect("https://localhost:8443/callback");

        assertThat(properties.redirectUrls()).containsExactly("https://localhost:8443/callback");
        assertThat(properties.redirectUrlFor("pc-casa.tail0000.ts.net")).isEqualTo("https://localhost:8443/callback");
    }

    @Test
    void theAddressWithTheSameNameAsTheRequestIsChosen() {
        EnableBankingProperties properties = withRedirect(
                " http://localhost:8000/callback , https://pc-casa.tail0000.ts.net/callback,");

        assertThat(properties.redirectUrls())
                .containsExactly("http://localhost:8000/callback", "https://pc-casa.tail0000.ts.net/callback");
        assertThat(properties.redirectUrlFor("PC-CASA.tail0000.ts.net"))
                .isEqualTo("https://pc-casa.tail0000.ts.net/callback");
        assertThat(properties.redirectUrlFor("localhost")).isEqualTo("http://localhost:8000/callback");
        // Un nome che non c'è (o nessun nome): il primo, come quando l'indirizzo era uno solo.
        assertThat(properties.redirectUrlFor("127.0.0.1")).isEqualTo("http://localhost:8000/callback");
        assertThat(properties.redirectUrlFor(null)).isEqualTo("http://localhost:8000/callback");
    }
}
