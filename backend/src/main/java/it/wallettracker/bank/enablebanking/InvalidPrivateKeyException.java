package it.wallettracker.bank.enablebanking;

/**
 * Il testo di un file .pem non è una chiave privata utilizzabile.
 *
 * <p>Il messaggio dell'eccezione è per il log (in italiano, con il nome del file). Per chi usa l'app conta il
 * {@link #problem() motivo}: chi riceve l'errore sceglie da quello il messaggio da mostrare (SetupService).
 */
public class InvalidPrivateKeyException extends IllegalStateException {

    /** Che cosa non va nella chiave. */
    public enum Problem {
        /** Non è nel formato PKCS#8: manca la riga "-----BEGIN PRIVATE KEY-----". */
        NOT_PKCS8,
        /** Il contenuto non è una chiave privata RSA valida. */
        NOT_RSA
    }

    private final Problem problem;

    public InvalidPrivateKeyException(Problem problem, String logMessage, Throwable cause) {
        super(logMessage, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
