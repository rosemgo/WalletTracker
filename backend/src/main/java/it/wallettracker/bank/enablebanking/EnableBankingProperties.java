package it.wallettracker.bank.enablebanking;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
 * configurare dalla dashboard (vedi {@link EnableBankingCredentials}). Anche gli indirizzi di ritorno
 * ({@code redirectUrl}) possono mancare: chi installa dallo ZIP li ha solo dopo aver preparato Tailscale.
 */
@Validated
@ConfigurationProperties(prefix = "enable-banking")
public record EnableBankingProperties(
        @NotBlank String baseUrl,
        String applicationId,
        String privateKeyPath,
        String redirectUrl) {

    /** I nomi che vogliono dire "questo computer": un indirizzo di ritorno così si apre solo dal server. */
    private static final Set<String> THIS_COMPUTER = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /**
     * Gli indirizzi di ritorno, separati da virgole nel {@code .env}: uno per ogni indirizzo con cui si apre l'app
     * (docs/47, parte I). Ognuno va registrato, uguale, nell'applicazione Enable Banking. Può essere vuoto: chi installa
     * dallo ZIP non ne ha finché non prepara Tailscale (docs/47, parte Q).
     */
    public List<String> redirectUrls() {
        if (redirectUrl == null) {
            return List.of();
        }
        return Arrays.stream(redirectUrl.split(",")).map(String::trim).filter(url -> !url.isEmpty()).toList();
    }

    /**
     * L'indirizzo di ritorno per un collegamento avviato da una pagina con questo {@code origin} (l'header Origin del
     * browser: schema, nome e porta, es. {@code http://localhost:8000}). Dopo il consenso, è il browser che apre
     * questo indirizzo: lì qualcuno deve rispondere con la pagina /callback (docs/47, parte Q). In ordine:
     * <ol>
     *   <li>quello con <b>lo stesso indirizzo</b> della pagina (schema, nome e porta): {@code ng serve} su
     *       https://localhost:8443, o Tailscale dal telefono. La pagina di ritorno è proprio quella da cui si è partiti;</li>
     *   <li>altrimenti, il primo <b>https che non è "questo computer"</b> (l'indirizzo di Tailscale): risponde da ogni
     *       dispositivo, server compreso. È il caso del server su http://localhost:8000, che Enable Banking non accetta
     *       perché non è https;</li>
     *   <li>altrimenti <b>nessuno</b>: un indirizzo dove non risponde nessuno farebbe finire il collegamento in una pagina
     *       di errore, dopo il consenso alla banca.</li>
     * </ol>
     * Senza origin (un client che non lo manda), dopo il punto 2 vale il primo dell'elenco, come prima.
     */
    public Optional<String> redirectUrlFor(String origin) {
        List<String> urls = redirectUrls();
        String page = originOf(origin);
        if (page != null) {
            Optional<String> same = urls.stream().filter(url -> page.equals(originOf(url))).findFirst();
            if (same.isPresent()) {
                return same;
            }
        }
        Optional<String> everywhere = urls.stream().filter(EnableBankingProperties::reachableFromEveryDevice).findFirst();
        if (everywhere.isPresent() || page != null) {
            return everywhere;
        }
        return urls.stream().findFirst();
    }

    /** Un indirizzo https con un nome che non è "questo computer" (in pratica quello di Tailscale). */
    static boolean reachableFromEveryDevice(String url) {
        URI uri = parse(url);
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && !THIS_COMPUTER.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * "schema://nome:porta", in minuscolo e con la porta di serie scritta (443 per https, 80 per http): così
     * "HTTPS://PC-CASA.ts.net" e "https://pc-casa.ts.net:443/callback" sono lo stesso indirizzo. Null se non lo è.
     */
    static String originOf(String url) {
        URI uri = parse(url);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank() || "null".equals(url)) {
            return null; // "null" è l'origin che il browser manda dalle pagine senza un indirizzo vero
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
