package it.wallettracker.logo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Scarica il logo di una banca dall'indirizzo che dà Enable Banking (docs/loghi.md, parte B). Solo https, al massimo
 * {@link LogoImage#MAX_BYTES}: oltre, si smette di leggere. Un componente a parte per poterlo sostituire nei test.
 */
@Component
public class LogoDownloader {

    private final RestClient restClient;

    public LogoDownloader() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** I byte del logo, o vuoto se l'indirizzo non è https o la risposta è troppo grande. */
    public Optional<byte[]> download(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        byte[] data = restClient.get().uri(uri).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                return null;
            }
            try (InputStream body = response.getBody()) {
                return readAtMost(body, LogoImage.MAX_BYTES + 1);
            }
        });
        return data == null || data.length > LogoImage.MAX_BYTES ? Optional.empty() : Optional.of(data);
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        return in.readNBytes(limit);
    }
}
