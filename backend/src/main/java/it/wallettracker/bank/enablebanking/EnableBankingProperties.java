package it.wallettracker.bank.enablebanking;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configurazione di Enable Banking.
 *
 * <p>Spring riempie questo record con i valori che in {@code application.yml} stanno sotto
 * {@code enable-banking:} (per esempio {@code application-id} diventa {@code applicationId}).
 * Grazie a {@code @Validated}, se un valore obbligatorio manca l'applicazione non parte
 * e mostra il messaggio indicato.
 *
 * <p>{@code applicationId} e {@code privateKeyPath} sono facoltativi: dal passo 5 le credenziali si possono
 * configurare dalla dashboard (vedi {@link EnableBankingCredentials}).
 */
@Validated
@ConfigurationProperties(prefix = "enable-banking")
public record EnableBankingProperties(
        @NotBlank String baseUrl,
        String applicationId,
        String privateKeyPath,
        @NotBlank(message = "imposta ENABLE_BANKING_REDIRECT_URL nel file .env") String redirectUrl) {

    /**
     * Gli indirizzi di ritorno, separati da virgole nel {@code .env}: uno per ogni nome con cui si apre l'app. Dal PC
     * si usa {@code localhost}, dal telefono il nome Tailscale del PC (docs/47, parte I), e la banca deve rimandare
     * il browser proprio dove era partito. Ognuno va registrato, uguale, nell'applicazione Enable Banking.
     */
    public List<String> redirectUrls() {
        return Arrays.stream(redirectUrl.split(",")).map(String::trim).filter(url -> !url.isEmpty()).toList();
    }

    /**
     * L'indirizzo di ritorno per chi apre l'app con questo nome (l'header {@code Host}, senza porta): quello con lo
     * stesso nome; se nessuno coincide, il primo dell'elenco, come prima che gli indirizzi diventassero più d'uno.
     */
    public String redirectUrlFor(String host) {
        List<String> urls = redirectUrls();
        return urls.stream().filter(url -> sameHost(url, host)).findFirst().orElse(urls.getFirst());
    }

    private static boolean sameHost(String url, String host) {
        if (host == null) {
            return false;
        }
        try {
            String urlHost = URI.create(url).getHost();
            return urlHost != null && urlHost.toLowerCase(Locale.ROOT).equals(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
