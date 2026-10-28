package it.wallettracker.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il PIN: crearlo, controllarlo, contare gli errori (docs/46, parte C).
 *
 * <p>Il PIN non si salva mai: si salva il suo <b>hash</b>, calcolato dal {@link PasswordEncoder} (bcrypt, vedi
 * {@link SecurityConfig}). Per controllare un PIN non si "decifra" niente: si ricalcola l'hash del PIN scritto e lo
 * si confronta con quello salvato ({@code matches}).
 */
@Service
public class PinService {

    /** Da 6 a 10 cifre, solo cifre. */
    private static final Pattern VALID_PIN = Pattern.compile("\\d{6,10}");

    private final AppPinRepository repository;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public PinService(AppPinRepository repository, PasswordEncoder encoder, Clock clock) {
        this.repository = repository;
        this.encoder = encoder;
        this.clock = clock;
    }

    /** Il risultato di un tentativo. */
    public sealed interface Attempt {
        /** Il PIN giusto. */
        record Correct() implements Attempt {
        }

        /** Un PIN sbagliato; {@code attemptsLeft} = quanti errori restano prima del blocco. */
        record Wrong(int attemptsLeft) implements Attempt {
        }

        /** I tentativi sono bloccati per {@code retryAfter}: il PIN non è stato nemmeno controllato. */
        record Locked(Duration retryAfter) implements Attempt {
        }
    }

    @Transactional(readOnly = true)
    public boolean isSet() {
        return repository.existsById(AppPin.ID);
    }

    /** Crea il PIN: solo se non c'è ancora (409), e solo un PIN valido (400). */
    @Transactional
    public void create(String pin) {
        requireValid(pin);
        if (repository.existsById(AppPin.ID)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Il PIN c'è già.");
        }
        repository.save(new AppPin(encoder.encode(pin), clock.instant()));
    }

    /**
     * Controlla un PIN. Se i tentativi sono bloccati, il PIN non viene nemmeno guardato: altrimenti chi prova i PIN
     * saprebbe lo stesso, dalla risposta, se ha indovinato.
     */
    @Transactional
    public Attempt check(String pin) {
        requireValid(pin);
        AppPin saved = repository.findForUpdate()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Il PIN non c'è ancora."));
        // Un solo istante per tutto il tentativo: letto due volte, a qualche millisecondo di distanza, la pausa di
        // un minuto appena iniziata risulterebbe di 59 secondi.
        Instant now = clock.instant();
        Duration locked = saved.lockedFor(now);
        if (!locked.isZero()) {
            return new Attempt.Locked(locked);
        }
        if (encoder.matches(pin, saved.pinHash())) {
            saved.succeeded(now);
            return new Attempt.Correct();
        }
        saved.failed(now);
        Duration nowLocked = saved.lockedFor(now);
        return nowLocked.isZero() ? new Attempt.Wrong(saved.attemptsBeforeLock()) : new Attempt.Locked(nowLocked);
    }

    private static void requireValid(String pin) {
        if (pin == null || !VALID_PIN.matcher(pin).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il PIN è fatto di cifre: da 6 a 10.");
        }
    }
}
