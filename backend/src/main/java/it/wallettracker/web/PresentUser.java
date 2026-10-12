package it.wallettracker.web;

import it.wallettracker.bank.enablebanking.PsuHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Costruisce gli header PSU ("l'utente è presente") per una richiesta della dashboard.
 *
 * <p>Si usano SOLO quando l'utente ha appena fatto un'azione (collegare una banca, premere "Aggiorna ora"):
 * è lì che è davvero presente. La sincronizzazione automatica non li usa mai (vedi PsuHeaders).
 */
@Component
public class PresentUser {

    private final PublicIpAddress publicIpAddress;

    public PresentUser(PublicIpAddress publicIpAddress) {
        this.publicIpAddress = publicIpAddress;
    }

    /**
     * Gli header PSU per l'utente che ha fatto questa richiesta: il suo IP pubblico e il suo browser
     * (l'header User-Agent che il browser manda a ogni richiesta). Restituisce null se l'IP non è noto:
     * in quel caso la lettura avviene come se fosse in background.
     */
    public PsuHeaders headersFor(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "WalletTracker";
        return publicIpAddress.get()
                .map(ip -> new PsuHeaders(ip, userAgent))
                .orElse(null);
    }
}
