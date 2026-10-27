package it.wallettracker.auth;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Le richieste del PIN (docs/46, parte C). Sono le uniche API raggiungibili senza sessione ({@link SecurityConfig}).
 *
 * <ul>
 *   <li>{@code GET  /api/auth/status}: c'è il PIN? Sei dentro? Puoi crearlo da qui?</li>
 *   <li>{@code POST /api/auth/pin}: crea il PIN (solo se non c'è, e solo dal PC) ed entra;</li>
 *   <li>{@code POST /api/auth/login}: controlla il PIN ed entra;</li>
 *   <li>{@code POST /api/auth/logout}: esce.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * I nomi con cui si apre l'app dal PC stesso. Il PIN si crea solo da qui: quando l'app sarà raggiungibile dal
     * telefono (Tailscale, passo 4) nessuno potrà "arrivare prima" a crearlo da un altro dispositivo.
     */
    private static final Set<String> LOCAL_NAMES = Set.of("localhost", "127.0.0.1");

    /** Il nome di chi è entrato. È uno solo, sempre lo stesso: l'app è per una persona sola. */
    private static final String OWNER = "proprietario";

    private final PinService pins;
    private final SecurityContextRepository contextRepository;

    public AuthController(PinService pins, SecurityContextRepository contextRepository) {
        this.pins = pins;
        this.contextRepository = contextRepository;
    }

    /**
     * @param pinSet        il PIN c'è già
     * @param authenticated questo browser ha inserito il PIN (la sessione è aperta)
     * @param canCreatePin  il PIN non c'è, e la richiesta arriva dal PC: la pagina può proporre di crearlo
     */
    public record AuthStatus(boolean pinSet, boolean authenticated, boolean canCreatePin) {
    }

    public record PinRequest(String pin) {
    }

    /**
     * Lo stato. Il parametro {@code csrfToken} non si usa qui, ma chiederlo fa sì che Spring prepari il gettone CSRF
     * e lo mandi nel cookie XSRF-TOKEN: è la prima richiesta della dashboard, e da lì in poi Angular ce l'ha.
     */
    @GetMapping("/status")
    public AuthStatus status(HttpServletRequest request, CsrfToken csrfToken) {
        csrfToken.getToken();
        boolean pinSet = pins.isSet();
        return new AuthStatus(pinSet, isAuthenticated(), !pinSet && isLocal(request));
    }

    @PostMapping("/pin")
    public ResponseEntity<Void> create(@RequestBody PinRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        if (!isLocal(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Il PIN si crea solo dal PC su cui è installata l'app (http://localhost).");
        }
        pins.create(body.pin());
        openSession(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody PinRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return switch (pins.check(body.pin())) {
            case PinService.Attempt.Correct correct -> {
                openSession(request, response);
                yield ResponseEntity.noContent().build();
            }
            case PinService.Attempt.Wrong wrong -> {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                        "PIN sbagliato. " + (wrong.attemptsLeft() == 1 ? "Ancora 1 tentativo"
                                : "Ancora " + wrong.attemptsLeft() + " tentativi") + " prima di una pausa.");
                problem.setProperty("attemptsLeft", wrong.attemptsLeft());
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
            }
            case PinService.Attempt.Locked locked -> {
                long seconds = Math.max(1, locked.retryAfter().toSeconds());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Troppi PIN sbagliati: riprova tra " + readable(locked.retryAfter()) + ".");
                problem.setProperty("retryAfterSeconds", seconds);
                yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                        .body(problem);
            }
        };
    }

    /** Esce: la sessione si chiude, e il cookie che la indicava non vale più niente. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * Apre la sessione: da qui in poi le richieste con il cookie di questo browser sono "dentro".
     *
     * <p>Se il browser aveva già una sessione, prima le si cambia il numero ({@code changeSessionId}). È la difesa
     * dalla <i>session fixation</i>: un attaccante che fosse riuscito a far usare al tuo browser una sessione scelta da
     * lui non se la ritroverebbe aperta dopo il tuo PIN.
     */
    private void openSession(HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(OWNER, null, List.of()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    private static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** La richiesta arriva dal PC stesso? (Il nome è quello che ha usato il browser: nginx lo passa così com'è.) */
    private static boolean isLocal(HttpServletRequest request) {
        return LOCAL_NAMES.contains(request.getServerName().toLowerCase(Locale.ROOT));
    }

    /** "45 secondi", "1 minuto", "15 minuti", "1 ora". */
    private static String readable(Duration wait) {
        long seconds = Math.max(1, wait.toSeconds());
        if (seconds < 60) {
            return seconds == 1 ? "1 secondo" : seconds + " secondi";
        }
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minuto" : minutes + " minuti";
        }
        return "1 ora";
    }
}
