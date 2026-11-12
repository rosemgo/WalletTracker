package it.wallettracker.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Un filtro di Spring Security fatto da noi (docs/46, parte B4), messo nella catena subito prima della decisione
 * finale ("chi può entrare?"). Fa due cose, a ogni richiesta:
 * <ol>
 *   <li><b>le sessioni vecchie non valgono</b>: ogni sessione ricorda il numero di generazione di quando è stata
 *       aperta ({@link #GENERATION}); se nel frattempo il PIN è cambiato (recuperato, tolto, azzerato), il numero non
 *       coincide più e la sessione si chiude. Così un telefono perso resta fuori dopo il recupero del PIN;</li>
 *   <li><b>"senza PIN" dal PC si entra subito</b>: se il proprietario ha scelto di non usare il PIN e la richiesta
 *       arriva dalla porta del PC ({@link RequestOrigin}), la richiesta vale come "dentro". Solo per questa richiesta:
 *       non si apre nessuna sessione, quindi niente da rubare.</li>
 * </ol>
 *
 * <p>Non è un {@code @Component}: Spring Boot lo registrerebbe anche come filtro normale, e girerebbe due volte. Lo
 * crea {@link SecurityConfig}.
 */
class PinAccessFilter extends OncePerRequestFilter {

    /** Il nome, nella sessione, del numero di generazione. */
    static final String GENERATION = "wallettracker.pin-generation";

    /** Il nome di chi è entrato senza PIN dal PC (in un log si distingue da chi ha inserito il PIN). */
    static final String LOCAL_OWNER = "proprietario-dal-pc";

    private final PinService pins;

    PinAccessFilter(PinService pins) {
        this.pins = pins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PinService.State state = pins.state();

        HttpSession session = request.getSession(false);
        Object opened = session == null ? null : session.getAttribute(GENERATION);
        if (opened instanceof Integer generation && generation != state.generation()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }

        if (state.mode() == PinService.Mode.WITHOUT_PIN && RequestOrigin.isLocal(request) && !isAuthenticated()) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(LOCAL_OWNER, null, List.of()));
            SecurityContextHolder.setContext(context);
        }

        chain.doFilter(request, response);
    }

    static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
