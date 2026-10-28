package it.wallettracker.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Il PIN per aprire l'app (V29): al massimo una riga, id = 1. Nessuna riga = il PIN non c'è ancora.
 *
 * <p>Tiene l'hash del PIN, mai il PIN, e il conteggio dei tentativi sbagliati. La regola del blocco sta qui,
 * vicino ai dati che usa: dopo {@value #FREE_ATTEMPTS} PIN sbagliati di fila, ogni altro errore blocca i tentativi
 * per un tempo che cresce (1 minuto, 5, 15, poi sempre un'ora). Un PIN di 6 cifre ha un milione di combinazioni:
 * con 5 tentativi liberi e poi uno all'ora, provarle tutte richiederebbe più di cent'anni.
 */
@Entity
@Table(name = "app_pin")
public class AppPin {

    /** L'unica riga possibile. */
    static final int ID = 1;

    /** I PIN sbagliati concessi prima di cominciare ad aspettare. */
    static final int FREE_ATTEMPTS = 5;

    /** Le attese dopo il quinto errore, il sesto, il settimo...; dall'ultima in poi resta quella. */
    static final List<Duration> WAITS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1));

    @Id
    private Integer id;

    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

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

    AppPin(String pinHash, Instant now) {
        this.id = ID;
        this.pinHash = pinHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    String pinHash() {
        return pinHash;
    }

    int failedAttempts() {
        return failedAttempts;
    }

    /** Quanto manca alla fine del blocco; zero se i tentativi non sono bloccati. */
    Duration lockedFor(Instant now) {
        return lockedUntil == null || !lockedUntil.isAfter(now) ? Duration.ZERO : Duration.between(now, lockedUntil);
    }

    /** Il PIN giusto: il conteggio ricomincia da zero. */
    void succeeded(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Un PIN sbagliato: dal quinto in poi, i tentativi si bloccano per un po'. */
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
