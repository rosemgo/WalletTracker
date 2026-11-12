package it.wallettracker.about;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * C'è una versione nuova? (docs/50, parte J). Una volta al giorno chiede a GitHub l'ultima versione pubblicata e la
 * confronta con quella installata. GitHub vede solo l'indirizzo IP di chi chiede: nessun dato dell'app esce.
 *
 * <p>Solo un avviso: aggiornare resta allo script ({@code installa --aggiorna}), perché il backend, apposta, non ha il
 * controllo di Docker (docs/17, riga 47). Con il codice sorgente ("sviluppo") non si chiede niente: lì si aggiorna con
 * Git.
 */
@Service
public class UpdateCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckService.class);

    /** MAGGIORE.MINORE.CORREZIONE: le versioni pubblicate (docs/50, parte A3). */
    static final Pattern SEMVER = Pattern.compile("(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");

    /** Le novità mostrate in Impostazioni: un testo, al massimo così lungo. */
    static final int MAX_NOTES = 4000;

    /** La pagina di una versione si apre solo se è davvero quella di WalletTracker su GitHub. */
    static final String RELEASES_PAGE = "https://github.com/rosemgo/WalletTracker/releases/";

    /** Quello che serve della risposta di GitHub (il resto si ignora). */
    @JsonNaming(SnakeCaseStrategy.class)
    record Release(String tagName, String htmlUrl, String body) {
    }

    /**
     * @param currentVersion la versione installata ("sviluppo" dal codice)
     * @param checkEnabled   false se il controllo è spento, o se l'app è costruita dal codice
     * @param available      true se c'è una versione più nuova di quella installata
     * @param latestVersion  l'ultima pubblicata (null finché GitHub non ha risposto)
     * @param releaseUrl     la sua pagina su GitHub (null se non è quella di WalletTracker)
     * @param notes          le sue novità, come testo (null se non ci sono)
     * @param checkedAt      l'ultima risposta di GitHub
     */
    public record UpdateStatus(String currentVersion, boolean checkEnabled, boolean available, String latestVersion,
            String releaseUrl, String notes, Instant checkedAt) {
    }

    private record Latest(String version, String releaseUrl, String notes, Instant checkedAt) {
    }

    private final UpdateProperties properties;
    private final String currentVersion;
    private final Clock clock;
    private final RestClient restClient;
    private volatile Latest latest;

    public UpdateCheckService(UpdateProperties properties, @Value("${wallettracker.version}") String currentVersion,
            Clock clock) {
        this.properties = properties;
        this.currentVersion = currentVersion;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Il controllo si fa solo se è acceso e se l'app è una versione pubblicata (non "sviluppo"). */
    boolean checkEnabled() {
        return properties.enabled() && SEMVER.matcher(currentVersion).matches();
    }

    @Scheduled(initialDelayString = "${wallettracker.update.initial-delay}",
            fixedDelayString = "${wallettracker.update.check-every}")
    void scheduledCheck() {
        check();
    }

    /** Chiede a GitHub l'ultima versione. Se GitHub non risponde, resta valida la risposta di prima. */
    public void check() {
        if (!checkEnabled()) {
            return;
        }
        try {
            Release release = restClient.get().uri(properties.url())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve().body(Release.class);
            String tag = release == null ? null : release.tagName();
            if (tag == null || !tag.startsWith("v") || !SEMVER.matcher(tag.substring(1)).matches()) {
                log.info("Controllo delle versioni: GitHub ha risposto una versione che non capisco ({})", tag);
                return;
            }
            String url = release.htmlUrl() != null && release.htmlUrl().startsWith(RELEASES_PAGE)
                    ? release.htmlUrl() : null;
            latest = new Latest(tag.substring(1), url, notes(release.body()), clock.instant());
        } catch (RestClientException e) {
            // Niente Internet, GitHub irraggiungibile: non è un problema dell'app. Si riprova domani.
            log.info("Controllo delle versioni non riuscito: {}", e.getMessage());
        }
    }

    public UpdateStatus status() {
        Latest l = latest;
        if (!checkEnabled() || l == null) {
            return new UpdateStatus(currentVersion, checkEnabled(), false, null, null, null, null);
        }
        return new UpdateStatus(currentVersion, true, isNewer(l.version(), currentVersion), l.version(),
                l.releaseUrl(), l.notes(), l.checkedAt());
    }

    /** true se {@code a} è più nuova di {@code b}: si confrontano i numeri uno per uno (1.10.0 > 1.9.0). */
    static boolean isNewer(String a, String b) {
        Matcher ma = SEMVER.matcher(a);
        Matcher mb = SEMVER.matcher(b);
        if (!ma.matches() || !mb.matches()) {
            return false;
        }
        for (int i = 1; i <= 3; i++) {
            int diff = Integer.compare(Integer.parseInt(ma.group(i)), Integer.parseInt(mb.group(i)));
            if (diff != 0) {
                return diff > 0;
            }
        }
        return false;
    }

    private static String notes(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String text = body.strip();
        return text.length() > MAX_NOTES ? text.substring(0, MAX_NOTES) + "…" : text;
    }
}
