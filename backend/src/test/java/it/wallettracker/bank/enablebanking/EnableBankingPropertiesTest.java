package it.wallettracker.bank.enablebanking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Quale indirizzo di ritorno per una pagina (docs/47, parti I e Q): dopo il consenso è il browser che lo apre, e lì
 * qualcuno deve rispondere. Niente Spring, niente rete.
 */
class EnableBankingPropertiesTest {

    private static final String NG_SERVE = "https://localhost:8443/callback";
    private static final String TAILSCALE = "https://pc-casa.tail0000.ts.net/callback";

    private static EnableBankingProperties withRedirect(String redirectUrl) {
        return new EnableBankingProperties("https://api.enablebanking.test", "", "", redirectUrl);
    }

    @Test
    void theListIsSplitByCommasAndMayBeEmpty() {
        assertThat(withRedirect(" " + NG_SERVE + " , " + TAILSCALE + ",").redirectUrls())
                .containsExactly(NG_SERVE, TAILSCALE);
        assertThat(withRedirect("").redirectUrls()).isEmpty();
        assertThat(withRedirect(null).redirectUrls()).isEmpty();
    }

    @Test
    void fromThePageWithTheSameAddressTheBankComesBackToIt() {
        EnableBankingProperties properties = withRedirect(NG_SERVE + "," + TAILSCALE);

        // Sviluppo: la dashboard di ng serve, su https://localhost:8443.
        assertThat(properties.redirectUrlFor("https://localhost:8443")).contains(NG_SERVE);
        // Dal telefono, con Tailscale (maiuscole e porta di serie non contano).
        assertThat(properties.redirectUrlFor("https://pc-casa.tail0000.ts.net")).contains(TAILSCALE);
        assertThat(properties.redirectUrlFor("HTTPS://PC-CASA.tail0000.ts.net:443")).contains(TAILSCALE);
    }

    @Test
    void fromTheServerInDockerTheReturnIsTheTailscaleAddress() {
        // http://localhost:8000: lo stesso nome di NG_SERVE ma un'altra porta, e lì ng serve non c'è.
        EnableBankingProperties properties = withRedirect(NG_SERVE + "," + TAILSCALE);
        assertThat(properties.redirectUrlFor("http://localhost:8000")).contains(TAILSCALE);
        assertThat(properties.redirectUrlFor("http://127.0.0.1:8000")).contains(TAILSCALE);
    }

    @Test
    void withoutAnAddressThatAnswersThereIsNone() {
        // Dal server, senza Tailscale: la 8443 non risponde, e Enable Banking non accetta http://localhost:8000.
        assertThat(withRedirect(NG_SERVE).redirectUrlFor("http://localhost:8000")).isEmpty();
        // Un indirizzo http con un altro nome non vale: Enable Banking vuole https.
        assertThat(withRedirect("http://pc-casa.lan/callback").redirectUrlFor("http://localhost:8000")).isEmpty();
        // Installazione da ZIP, prima di Tailscale: nessun indirizzo.
        assertThat(withRedirect("").redirectUrlFor("http://localhost:8000")).isEmpty();
    }

    @Test
    void withoutAnOriginTheTailscaleAddressComesFirstThenTheFirstOfTheList() {
        assertThat(withRedirect(NG_SERVE + "," + TAILSCALE).redirectUrlFor(null)).contains(TAILSCALE);
        assertThat(withRedirect(NG_SERVE).redirectUrlFor(null)).contains(NG_SERVE);
        // "null" è l'origin delle pagine senza un indirizzo vero: come se non ci fosse.
        assertThat(withRedirect(NG_SERVE).redirectUrlFor("null")).contains(NG_SERVE);
        assertThat(withRedirect("").redirectUrlFor(null)).isEmpty();
    }

    @Test
    void anOriginIsSchemeNameAndPort() {
        assertThat(EnableBankingProperties.originOf("https://Localhost:8443/callback")).isEqualTo("https://localhost:8443");
        assertThat(EnableBankingProperties.originOf("https://pc.ts.net/callback")).isEqualTo("https://pc.ts.net:443");
        assertThat(EnableBankingProperties.originOf("http://localhost")).isEqualTo("http://localhost:80");
        assertThat(EnableBankingProperties.originOf("non è un indirizzo")).isNull();
        assertThat(EnableBankingProperties.reachableFromEveryDevice(TAILSCALE)).isTrue();
        assertThat(EnableBankingProperties.reachableFromEveryDevice(NG_SERVE)).isFalse();
    }
}
