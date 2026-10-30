package it.wallettracker.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Spring Security (Fase 4, passo 3: docs/46, parte B): le API si usano solo dopo aver inserito il PIN.
 *
 * <p>Spring Security è una catena di filtri che Spring esegue su ogni richiesta, prima dei controller, come il nostro
 * {@code LocalOnlyRequestFilter}. Qui diciamo che cosa deve fare:
 * <ul>
 *   <li><b>chi può entrare</b>: le richieste del PIN ({@code /api/auth/...}) e il controllo di salute di Docker
 *       sempre; tutto il resto solo "dentro": con una sessione aperta dal PIN, o senza PIN dal PC se il proprietario
 *       l'ha scelto ({@link PinAccessFilter}). Altrimenti: 401;</li>
 *   <li><b>la sessione</b>: dopo il PIN giusto, il backend ricorda "questo browser ha inserito il PIN" in una
 *       sessione, riconosciuta da un cookie (le regole del cookie sono in application.yml, {@code server.servlet.session});</li>
 *   <li><b>CSRF</b>: ogni richiesta che modifica qualcosa deve portare un "gettone" che solo le nostre pagine possono
 *       leggere (vedi la parte D della guida).</li>
 * </ul>
 * Niente pagina di login di Spring né finestra del browser con utente e password: il PIN lo chiede la dashboard, e lo
 * manda a {@link AuthController}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
            PinService pins) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        // Le pagine di errore interne di Spring (una richiesta già controllata che finisce in errore):
                        // senza questa riga, l'errore vero (es. 413 file troppo grande) diventerebbe un 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                // CSRF pensato per le app come la nostra, con il frontend separato (Single-Page Application): il
                // gettone arriva in un cookie (XSRF-TOKEN) che il JavaScript della pagina legge e rimanda in un
                // header (X-XSRF-TOKEN). Angular lo fa da solo.
                .csrf(csrf -> csrf.spa()) //spa() sta per Single-Page Application. Dice a Spring: “il gettone mettilo in un cookie leggibile, XSRF-TOKEN, e aspettalo indietro nell’intestazione X-XSRF-TOKEN”.
                .securityContext(context -> context.securityContextRepository(contextRepository))
                // Il nostro filtro (le sessioni di un PIN vecchio non valgono; "senza PIN" dal PC si entra), subito
                // prima di AuthorizationFilter, l'ultimo della catena, quello che decide "dentro o 401".
                .addFilterBefore(new PinAccessFilter(pins), AuthorizationFilter.class)
                // Spring offre una pagina di login, il login "Basic" del browser e un suo /logout: non li usiamo.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> problem(response, 401,
                                "Inserisci il PIN."))
                        .accessDeniedHandler((request, response, e) -> problem(response, 403,
                                "Richiesta rifiutata: ricarica la pagina e riprova.")));
        return http.build();
    }

    /** Dove si ricorda "questo browser ha inserito il PIN": nella sessione. Lo usa anche {@link AuthController}. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Come si calcola l'hash del PIN: bcrypt con "costo" 12, cioè 2¹² giri di calcolo, circa un quarto di secondo.
     * Per chi entra non si nota; per chi avesse rubato il database e volesse provare tutti i PIN è un muro. Il
     * {@link DelegatingPasswordEncoder} scrive davanti all'hash il nome dell'algoritmo ("{bcrypt}..."): se un giorno
     * cambiassimo algoritmo, i PIN già salvati si riconoscerebbero ancora.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    /** L'orologio: un bean, perché i test possano usarne uno fermo e far passare il tempo del blocco. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** Un errore nello stesso formato degli altri delle API (RFC 9457), con il motivo in "detail". */
    private static void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
