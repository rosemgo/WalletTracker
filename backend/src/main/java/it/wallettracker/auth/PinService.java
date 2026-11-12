package it.wallettracker.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Come si entra: la scelta "senza PIN", il PIN, il codice di recupero, il conteggio degli errori (docs/46, parte D).
 *
 * <p>Il PIN e il codice non si salvano mai: si salva il loro <b>hash</b>, calcolato dal {@link PasswordEncoder}
 * (bcrypt, vedi {@link SecurityConfig}). Per controllarli non si "decifra" niente: si ricalcola l'hash di quello
 * scritto e lo si confronta con quello salvato ({@code matches}).
 */
@Service
public class PinService {

    /** Da 6 a 10 cifre, solo cifre. */
    private static final Pattern VALID_PIN = Pattern.compile("\\d{6,10}");

    /**
     * Le lettere del codice di recupero: 32 simboli, senza quelli che si confondono (I e 1, O e 0). 16 simboli scelti
     * tra 32 sono 2⁸⁰ combinazioni: impossibile da indovinare, anche senza le pause.
     */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_LENGTH = 16;
    private static final Pattern VALID_RECOVERY = Pattern.compile("[" + RECOVERY_ALPHABET + "]{" + RECOVERY_LENGTH + "}");

    private final AppPinRepository repository;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PinService(AppPinRepository repository, PasswordEncoder encoder, Clock clock) {
        this.repository = repository;
        this.encoder = encoder;
        this.clock = clock;
    }

    /** Le tre situazioni possibili (vedi {@link AppPin}). */
    public enum Mode {
        /** l'app non è ancora stata aperta dal PC: alla prima apertura si sceglie */
        UNDECIDED,
        /** "senza PIN": dal PC si entra subito, da un altro dispositivo no */
        WITHOUT_PIN,
        /** il PIN si chiede su ogni dispositivo */
        WITH_PIN
    }

    /** La situazione e il numero di generazione (0 se non c'è ancora la riga). */
    public record State(Mode mode, int generation) {
    }

    /** Il risultato di un tentativo (PIN o codice). */
    public sealed interface Attempt {
        /**
         * Giusto. {@code generation} va ricordato nella sessione; {@code recoveryCode} è il codice nuovo, solo dopo un
         * recupero (altrimenti null).
         */
        record Correct(int generation, String recoveryCode) implements Attempt {
        }

        /** Sbagliato; {@code attemptsLeft} = quanti errori restano prima del blocco. */
        record Wrong(int attemptsLeft) implements Attempt {
        }

        /** I tentativi sono bloccati per {@code retryAfter}: il PIN (o il codice) non è stato nemmeno controllato. */
        record Locked(Duration retryAfter) implements Attempt {
        }
    }

    /** Il PIN creato: il codice di recupero (da mostrare una volta sola) e la generazione per la sessione. */
    public record Created(String recoveryCode, int generation) {
    }

    /**
     * La situazione di adesso. Si legge dal database a ogni richiesta (la legge {@link PinAccessFilter}): una ricerca
     * per chiave primaria su una tabella di una riga costa pochissimo, e così non c'è nessuna copia in memoria che
     * potrebbe restare vecchia.
     */
    @Transactional(readOnly = true)
    public State state() {
        return repository.findById(AppPin.ID)
                .map(pin -> new State(pin.isWithoutPin() ? Mode.WITHOUT_PIN : Mode.WITH_PIN, pin.generation()))
                .orElse(new State(Mode.UNDECIDED, 0));
    }

    /** La scelta "senza PIN", alla prima apertura. Solo se non si è ancora scelto (409). */
    @Transactional
    public void chooseWithoutPin() {
        if (repository.existsById(AppPin.ID)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La scelta è già stata fatta.");
        }
        repository.save(AppPin.withoutPin(clock.instant()));
    }

    /**
     * Crea il PIN: alla prima apertura, o più tardi da Impostazioni per chi aveva scelto "senza PIN". Se il PIN c'è già,
     * 409: si cambia con il codice di recupero, o togliendolo e rimettendolo.
     */
    @Transactional
    public Created createPin(String pin) {
        requireValidPin(pin);
        String recoveryCode = newRecoveryCode();
        Optional<AppPin> saved = repository.findForUpdate();
        if (saved.isEmpty()) {
            AppPin created = repository.save(AppPin.withPin(encoder.encode(pin), encoder.encode(normalize(recoveryCode)),
                    clock.instant()));
            return new Created(recoveryCode, created.generation());
        }
        AppPin existing = saved.get();
        if (!existing.isWithoutPin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Il PIN c'è già.");
        }
        existing.changePin(encoder.encode(pin), encoder.encode(normalize(recoveryCode)), clock.instant());
        return new Created(recoveryCode, existing.generation());
    }

    /** Controlla il PIN, per entrare. */
    @Transactional
    public Attempt check(String pin) {
        requireValidPin(pin);
        return attempt(pin, AppPin::pinHash, (saved) -> {
        });
    }

    /**
     * Il PIN dimenticato: con il codice di recupero si sceglie un PIN nuovo. Il codice usato non vale più: se ne crea
     * un altro, da mostrare. La generazione cresce, quindi le altre sessioni (un telefono perso) si chiudono.
     */
    @Transactional
    public Attempt recover(String code, String newPin) {
        requireValidPin(newPin);
        String normalized = normalize(code);
        if (!VALID_RECOVERY.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il codice di recupero è fatto di 16 lettere e cifre, come K7QM-2XPA-9RWD-4TNE.");
        }
        String newCode = newRecoveryCode();
        Attempt result = attempt(normalized, AppPin::recoveryHash,
                (saved) -> saved.changePin(encoder.encode(newPin), encoder.encode(normalize(newCode)), clock.instant()));
        return result instanceof Attempt.Correct correct ? new Attempt.Correct(correct.generation(), newCode) : result;
    }

    /** Toglie il PIN (si torna "senza PIN"): serve il PIN di adesso. Le sessioni aperte si chiudono. */
    @Transactional
    public Attempt removePin(String pin) {
        requireValidPin(pin);
        return attempt(pin, AppPin::pinHash, (saved) -> saved.removePin(clock.instant()));
    }

    /**
     * Un tentativo con il PIN o con il codice: le stesse regole, e lo stesso conteggio degli errori. Se i tentativi
     * sono bloccati, il valore non viene nemmeno guardato: altrimenti chi prova saprebbe lo stesso, dalla risposta, se
     * ha indovinato.
     *
     * @param value       quello che è stato scritto
     * @param savedHash   quale hash confrontare (del PIN o del codice)
     * @param onSuccess   che cosa fare se è giusto (oltre ad azzerare il conteggio)
     */
    private Attempt attempt(String value, Function<AppPin, String> savedHash,
            Consumer<AppPin> onSuccess) {
        AppPin saved = repository.findForUpdate()
                .filter(pin -> !pin.isWithoutPin())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Il PIN non c'è."));
        // Un solo istante per tutto il tentativo: letto due volte, a qualche millisecondo di distanza, la pausa di
        // un minuto appena iniziata risulterebbe di 59 secondi.
        Instant now = clock.instant();
        Duration locked = saved.lockedFor(now);
        if (!locked.isZero()) {
            return new Attempt.Locked(locked);
        }
        if (encoder.matches(value, savedHash.apply(saved))) {
            saved.succeeded(now);
            onSuccess.accept(saved);
            return new Attempt.Correct(saved.generation(), null);
        }
        saved.failed(now);
        Duration nowLocked = saved.lockedFor(now);
        return nowLocked.isZero() ? new Attempt.Wrong(saved.attemptsBeforeLock()) : new Attempt.Locked(nowLocked);
    }

    /** Un codice di recupero nuovo, dal generatore casuale crittografico: "K7QM-2XPA-9RWD-4TNE". */
    private String newRecoveryCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < RECOVERY_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Il codice come si confronta: maiuscole, senza trattini e spazi ("k7qm 2xpa..." vale come "K7QM-2XPA-..."). */
    private static String normalize(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static void requireValidPin(String pin) {
        if (pin == null || !VALID_PIN.matcher(pin).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il PIN è fatto di cifre: da 6 a 10.");
        }
    }
}
