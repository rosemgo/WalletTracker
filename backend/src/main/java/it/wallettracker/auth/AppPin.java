package it.wallettracker.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Come si entra nell'app (V29, V30): al massimo una riga, id = 1.
 * <ul>
 *   <li>nessuna riga: l'app non è ancora stata aperta dal PC, e alla prima apertura si sceglie;</li>
 *   <li>{@code pinHash} null: "senza PIN", si entra solo dal PC ({@link #isWithoutPin()});</li>
 *   <li>{@code pinHash} presente: il PIN si chiede su ogni dispositivo.</li>
 * </ul>
 *
 * <p>Tiene l'hash del PIN e quello del codice di recupero, mai i valori veri, e il conteggio dei tentativi sbagliati
 * (PIN o codice: lo stesso). La regola del blocco sta qui, vicino ai dati che usa: dopo {@value #FREE_ATTEMPTS}
 * errori di fila, ogni altro errore blocca i tentativi per un tempo che cresce (1 minuto, 5, 15, poi sempre un'ora).
 * Un PIN di 6 cifre ha un milione di combinazioni: con 5 tentativi liberi e poi uno all'ora, provarle tutte
 * richiederebbe più di cent'anni.
 */
@Entity
@Table(name = "app_pin")
public class AppPin {

    /** L'unica riga possibile. */
    static final int ID = 1;

    /** Gli errori concessi prima di cominciare ad aspettare. */
    static final int FREE_ATTEMPTS = 5;

    /** Le attese dopo il quinto errore, il sesto, il settimo...; dall'ultima in poi resta quella. */
    static final List<Duration> WAITS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1));

    @Id
    private Integer id;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "recovery_hash")
    private String recoveryHash;

    @Column(name = "generation", nullable = false)
    private int generation;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppPin() {
    }

    /** La scelta "senza PIN", alla prima apertura dal PC. */
    static AppPin withoutPin(Instant now) {
        AppPin pin = new AppPin();
        pin.id = ID;
        pin.generation = 1;
        pin.createdAt = now;
        pin.updatedAt = now;
        return pin;
    }

    /** Un PIN, con il suo codice di recupero (entrambi già trasformati in hash). */
    static AppPin withPin(String pinHash, String recoveryHash, Instant now) {
        AppPin pin = withoutPin(now);
        pin.pinHash = pinHash;
        pin.recoveryHash = recoveryHash;
        return pin;
    }

    boolean isWithoutPin() {
        return pinHash == null;
    }

    String pinHash() {
        return pinHash;
    }

    String recoveryHash() {
        return recoveryHash;
    }

    int generation() {
        return generation;
    }

    int failedAttempts() {
        return failedAttempts;
    }

    /**
     * Un PIN nuovo (aggiunto a chi era senza, o scelto con il codice di recupero), con un codice di recupero nuovo.
     * Il numero di generazione cresce: le sessioni aperte prima non valgono più.
     */
    void changePin(String newPinHash, String newRecoveryHash, Instant now) {
        pinHash = newPinHash;
        recoveryHash = newRecoveryHash;
        generation++;
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Si torna "senza PIN": niente PIN, niente codice, e le sessioni aperte (anche sul telefono) non valgono più. */
    void removePin(Instant now) {
        pinHash = null;
        recoveryHash = null;
        generation++;
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Quanto manca alla fine del blocco; zero se i tentativi non sono bloccati. */
    Duration lockedFor(Instant now) {
        return lockedUntil == null || !lockedUntil.isAfter(now) ? Duration.ZERO : Duration.between(now, lockedUntil);
    }

    /** Il PIN (o il codice) giusto: il conteggio ricomincia da zero. */
    void succeeded(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Un PIN (o un codice) sbagliato: dal quinto in poi, i tentativi si bloccano per un po'. */
    void failed(Instant now) {
        failedAttempts++;
        if (failedAttempts >= FREE_ATTEMPTS) {
            int step = Math.min(failedAttempts - FREE_ATTEMPTS, WAITS.size() - 1);
            lockedUntil = now.plus(WAITS.get(step));
        }
        updatedAt = now;
    }

    /** Quanti errori restano prima del primo blocco (0 se si è già oltre). */
    int attemptsBeforeLock() {
        return Math.max(0, FREE_ATTEMPTS - failedAttempts);
    }
}
