package it.wallettracker.auth;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
 * Le richieste per entrare (docs/46, parte D). Sono le uniche API raggiungibili senza sessione ({@link SecurityConfig}).
 *
 * <ul>
 *   <li>{@code GET  /api/auth/status}: com'è protetta l'app, se sei dentro, se arrivi dal PC;</li>
 *   <li>{@code POST /api/auth/no-pin}: la scelta "senza PIN", alla prima apertura (solo dal PC);</li>
 *   <li>{@code POST /api/auth/pin}: crea il PIN (solo dal PC) ed entra; risponde con il codice di recupero;</li>
 *   <li>{@code POST /api/auth/login}: controlla il PIN ed entra;</li>
 *   <li>{@code POST /api/auth/recover}: con il codice di recupero sceglie un PIN nuovo ed entra;</li>
 *   <li>{@code POST /api/auth/pin/remove}: toglie il PIN (solo dal PC, con il PIN di adesso);</li>
 *   <li>{@code POST /api/auth/logout}: esce.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Il nome di chi è entrato con il PIN. È uno solo, sempre lo stesso: l'app è per una persona sola. */
    private static final String OWNER = "proprietario";

    private final PinService pins;
    private final SecurityContextRepository contextRepository;

    public AuthController(PinService pins, SecurityContextRepository contextRepository) {
        this.pins = pins;
        this.contextRepository = contextRepository;
    }

    /**
     * @param pinSet        il PIN c'è
     * @param withoutPin    il proprietario ha scelto "senza PIN": si entra solo dal PC
     * @param authenticated questa richiesta è "dentro" (sessione aperta, o senza PIN dal PC)
     * @param local         la richiesta arriva dal PC (la porta 8000 di nginx)
     */
    public record AuthStatus(boolean pinSet, boolean withoutPin, boolean authenticated, boolean local) {
    }

    public record PinRequest(String pin) {
    }

    public record RecoverRequest(String code, String pin) {
    }

    /** Il codice di recupero, da mostrare una sola volta. */
    public record RecoveryCode(String recoveryCode) {
    }

    /**
     * Lo stato. Il parametro {@code csrfToken} non si usa qui, ma chiederlo fa sì che Spring prepari il gettone CSRF
     * e lo mandi nel cookie XSRF-TOKEN: è la prima richiesta della dashboard, e da lì in poi Angular ce l'ha.
     */
    @GetMapping("/status")
    public AuthStatus status(HttpServletRequest request, CsrfToken csrfToken) {
        csrfToken.getToken(); //Chiedere getToken() obbliga Spring a generare il gettone, un valore casuale, e a mandarlo nella risposta: "Set-Cookie: XSRF-TOKEN=8c1d...; Path=/" Senza HttpOnly, quindi leggibile dal JavaScript. Il valore restituito non lo usiamo: serve solo a far partire il cookie.
        PinService.Mode mode = pins.state().mode();
        return new AuthStatus(mode == PinService.Mode.WITH_PIN, mode == PinService.Mode.WITHOUT_PIN,
                PinAccessFilter.isAuthenticated(), RequestOrigin.isLocal(request));
    }

    @PostMapping("/no-pin")
    public ResponseEntity<Void> withoutPin(HttpServletRequest request) {
        requireLocal(request, "La scelta si fa solo dal server, il computer su cui è installata l'app.");
        pins.chooseWithoutPin();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pin")
    public RecoveryCode create(@RequestBody PinRequest body, HttpServletRequest request, HttpServletResponse response) {
        requireLocal(request, "Il PIN si crea solo dal server, il computer su cui è installata l'app (http://localhost:8000).");
        PinService.Created created = pins.createPin(body.pin());
        openSession(created.generation(), request, response);
        return new RecoveryCode(created.recoveryCode());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody PinRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return answer(pins.check(body.pin()), "PIN sbagliato", request, response);
    }

    /** Il PIN dimenticato. Da qualsiasi dispositivo: senza il codice non serve a niente, e conta come un errore. */
    @PostMapping("/recover")
    public ResponseEntity<?> recover(@RequestBody RecoverRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return answer(pins.recover(body.code(), body.pin()), "Codice di recupero sbagliato", request, response);
    }

    /** Toglie il PIN. Solo dal PC: dal telefono, senza PIN, non si entrerebbe più. */
    @PostMapping("/pin/remove")
    public ResponseEntity<?> remove(@RequestBody PinRequest body, HttpServletRequest request) {
        requireLocal(request, "Il PIN si toglie solo dal server, il computer su cui è installata l'app.");
        PinService.Attempt attempt = pins.removePin(body.pin());
        if (attempt instanceof PinService.Attempt.Correct) {
            closeSession(request); // dal PC si entra ora senza PIN; le altre sessioni le chiude la generazione
            return ResponseEntity.noContent().build();
        }
        return answer(attempt, "PIN sbagliato", request, null);
    }

    /** Esce: la sessione si chiude, e il cookie che la indicava non vale più niente. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        closeSession(request);
        return ResponseEntity.noContent().build();
    }

    /** La risposta a un tentativo: 200/204 dentro, 401 sbagliato (quanti ne restano), 429 in pausa (quanto manca). */
    private ResponseEntity<?> answer(PinService.Attempt attempt, String wrongMessage, HttpServletRequest request,
            HttpServletResponse response) {
        return switch (attempt) {
            case PinService.Attempt.Correct correct -> {
                if (response != null) {
                    openSession(correct.generation(), request, response);
                }
                yield correct.recoveryCode() == null ? ResponseEntity.noContent().build()
                        : ResponseEntity.ok(new RecoveryCode(correct.recoveryCode()));
            }
            case PinService.Attempt.Wrong wrong -> {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                        wrongMessage + ". " + (wrong.attemptsLeft() == 1 ? "Ancora 1 tentativo"
                                : "Ancora " + wrong.attemptsLeft() + " tentativi") + " prima di una pausa.");
                problem.setProperty("attemptsLeft", wrong.attemptsLeft());
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
            }
            case PinService.Attempt.Locked locked -> {
                long seconds = Math.max(1, locked.retryAfter().toSeconds());
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Troppi tentativi sbagliati: riprova tra " + readable(locked.retryAfter()) + ".");
                problem.setProperty("retryAfterSeconds", seconds);
                yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                        .body(problem);
            }
        };
    }

    /**
     * Apre la sessione: da qui in poi le richieste con il cookie di questo browser sono "dentro".
     *
     * <p>Se il browser aveva già una sessione, prima le si cambia il numero ({@code changeSessionId}). È la difesa
     * dalla <i>session fixation</i>: un attaccante che fosse riuscito a far usare al tuo browser una sessione scelta da
     * lui non se la ritroverebbe aperta dopo il tuo PIN. Nella sessione si scrive anche la generazione del PIN
     * ({@link PinAccessFilter}): se il PIN cambia, questa sessione si chiude.
     */
    private void openSession(int generation, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(OWNER, null, List.of()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response); //CREA LA SESSIONE E SETTA IL CONTESTO (SPRING_SECURITY_CONTEXT) NELLA SESSIONE
        request.getSession().setAttribute(PinAccessFilter.GENERATION, generation);
    }

    private static void closeSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private static void requireLocal(HttpServletRequest request, String message) {
        if (!RequestOrigin.isLocal(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
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
