package it.wallettracker.bank.enablebanking;

import org.springframework.http.HttpHeaders;

/**
 * Header che dicono alla banca: "l'utente è presente in questo momento".
 *
 * <p>La PSD2 distingue due tipi di accesso ai conti:
 * <ul>
 *   <li><b>in background</b> (nessun header PSU): l'app legge i dati da sola, per esempio di notte.
 *       Le banche li limitano: di solito 4 al giorno per conto, Trade Republic anche meno;</li>
 *   <li><b>con l'utente presente</b> (header PSU con il suo indirizzo IP): l'utente ha appena
 *       chiesto i dati, per esempio premendo "Aggiorna". Questi accessi non hanno il limite giornaliero.</li>
 * </ul>
 * Vanno usati solo quando l'utente è davvero presente: dichiararlo falsamente viola le regole PSD2.
 *
 * <p>PSU = Payment Service User, cioè il titolare del conto.
 */
public record PsuHeaders(String ipAddress, String userAgent) {

    void addTo(HttpHeaders headers) {
        headers.set("Psu-Ip-Address", ipAddress);
        headers.set("Psu-User-Agent", userAgent);
    }
}
