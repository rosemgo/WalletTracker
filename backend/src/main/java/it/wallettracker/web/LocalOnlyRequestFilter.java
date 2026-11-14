package it.wallettracker.web;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import it.wallettracker.message.Message;
import it.wallettracker.message.Messages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Un "buttafuori" davanti a tutte le API: lascia passare solo le richieste che arrivano davvero da questo PC.
 *
 * <p>Il server ascolta solo su 127.0.0.1, quindi un altro computer non può raggiungerlo. Restano però due
 * attacchi che passano <b>attraverso il tuo browser</b>, mentre visiti un sito malevolo:
 * <ol>
 *   <li><b>DNS rebinding</b>: il sito fa puntare un suo dominio (es. {@code evil.example}) a 127.0.0.1. Per il
 *       browser la pagina e il nostro backend sono allora "lo stesso sito", e la pagina potrebbe leggere i tuoi
 *       movimenti. Difesa: ogni richiesta deve essere indirizzata (header {@code Host}) a un nome consentito,
 *       {@code localhost} o {@code 127.0.0.1}. Con il trucco del DNS rebinding l'header contiene
 *       {@code evil.example}, e la richiesta viene rifiutata;</li>
 *   <li><b>CSRF</b> (richiesta da un altro sito): una pagina qualsiasi può far partire dal tuo browser un
 *       {@code POST} verso {@code https://localhost:8443/api/sync}. Non può leggere la risposta, ma l'azione
 *       avverrebbe. Difesa: per le richieste che modificano qualcosa (POST, PUT, DELETE...), se il browser
 *       dichiara da quale sito parte (header {@code Origin}), quel sito deve essere uno dei nostri.</li>
 * </ol>
 *
 * <p>Un {@link OncePerRequestFilter} è codice che Spring esegue su ogni richiesta HTTP <b>prima</b> dei
 * controller: può lasciarla passare ({@code chain.doFilter}) o rispondere subito con un errore.
 */
@Component
// Prima di Spring Security (che lavora a -100, docs/46): una richiesta con un nome falso si rifiuta prima di tutto il
// resto, anche prima di dire "serve il PIN".
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class LocalOnlyRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalOnlyRequestFilter.class);

    /** I metodi HTTP che leggono soltanto: non serve controllare l'origine. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowedHosts;
    private final Messages messages;

    public LocalOnlyRequestFilter(@Value("${wallettracker.allowed-hosts}") List<String> allowedHosts,
            Messages messages) {
        this.messages = messages;
        this.allowedHosts = Set.copyOf(allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getServerName() è il nome nell'header Host, senza porta (es. "localhost").
        //Legge il nome scritto nell’header Host, cioè il nome che hai digitato nel browser. Se non è nell’elenco, risponde 403. Questo serve contro il DNS rebinding;
        String host = request.getServerName();
        if (!isAllowed(host)) {
            // messaggio: Richiesta rifiutata: indirizzo {0} non consentito.
            reject(request, response, Message.of("request.host-not-allowed", host));
            return;
        }

        //Per le richieste che modificano dati (POST, PUT, DELETE…), anche il sito di partenza (header Origin) deve avere un nome dell’elenco. Questo serve contro il CSRF.
        String origin = request.getHeader("Origin");
        if (!SAFE_METHODS.contains(request.getMethod()) && origin != null && !isAllowed(hostOf(origin))) {
            // messaggio: Richiesta rifiutata: proviene da un altro sito ({0}).
            reject(request, response, Message.of("request.other-site", origin));
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAllowed(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /** Il nome del sito da un'origine come "https://localhost:8443"; null se non è un indirizzo valido. */
    private static String hostOf(String origin) {
        try {
            return URI.create(origin).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 403 Forbidden, con un corpo JSON nello stesso formato degli altri errori (Problem Details). */
    private void reject(HttpServletRequest request, HttpServletResponse response, Message message)
            throws IOException {
        log.warn("Richiesta rifiutata: {}", message);
        // Siamo prima di tutto il resto di Spring: la lingua si legge direttamente dalla richiesta.
        String detail = messages.text(message, request.getLocale());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\""
                + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
