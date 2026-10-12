package it.wallettracker.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * L'indirizzo IP pubblico di questa installazione, da mandare alla banca negli header PSU.
 *
 * <p>Il backend vede le richieste della dashboard arrivare da 127.0.0.1 (lo stesso PC): non è un indirizzo
 * utile per la banca. Chiediamo quindi il nostro IP pubblico a un servizio esterno (ipify), come faceva il
 * programma della Fase 0. Con WalletTracker installato a casa, è anche l'IP da cui navighi tu.
 *
 * <p>Il risultato resta in memoria per 10 minuti, così non chiamiamo ipify a ogni clic.
 */
@Component
public class PublicIpAddress {

    private static final Logger log = LoggerFactory.getLogger(PublicIpAddress.class);
    private static final Duration CACHE_DURATION = Duration.ofMinutes(10);

    private final RestClient restClient;
    private String cachedIp;
    private Instant cachedAt;

    public PublicIpAddress(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("https://api.ipify.org").build();
    }

    /**
     * L'IP pubblico, oppure vuoto se ipify non risponde.
     *
     * <p>{@code synchronized}: se arrivano due richieste insieme, una aspetta l'altra. Così non leggono e
     * scrivono la cache nello stesso momento.
     */
    public synchronized Optional<String> get() {
        if (cachedIp != null && cachedAt.plus(CACHE_DURATION).isAfter(Instant.now())) {
            return Optional.of(cachedIp);
        }
        try {
            String ip = restClient.get().retrieve().body(String.class);
            if (ip == null || ip.isBlank()) {
                return Optional.empty();
            }
            cachedIp = ip.trim();
            cachedAt = Instant.now();
            return Optional.of(cachedIp);
        } catch (RestClientException e) {
            log.warn("Impossibile trovare l'IP pubblico: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
