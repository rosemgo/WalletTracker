package it.wallettracker.logo;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Che immagine è, dai primi byte del file e non dal nome o dall'intestazione HTTP, che si possono falsificare
 * (docs/loghi.md, parte C). Una funzione "pura", provata da LogoImageTest.
 */
public final class LogoImage {

    /** Un logo è piccolo: oltre questa misura non si scarica e non si accetta. */
    public static final int MAX_BYTES = 256 * 1024;

    private LogoImage() {
    }

    /**
     * Il tipo dell'immagine ("image/png"...), o vuoto se non è un'immagine che accettiamo.
     *
     * @param svg true per accettare anche SVG: solo per i loghi delle banche, che arrivano da Enable Banking. Un SVG
     *            caricato dall'utente no: è un file di testo che può contenere script (in un {@code <img>} non
     *            partono, ma meglio non tenerne)
     */
    public static Optional<String> typeOf(byte[] data, boolean svg) {
        if (data == null || data.length < 4 || data.length > MAX_BYTES) {
            return Optional.empty();
        }
        if (startsWith(data, 0x89, 'P', 'N', 'G')) {
            return Optional.of("image/png");
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (data.length >= 12 && startsWith(data, 'R', 'I', 'F', 'F') && data[8] == 'W' && data[9] == 'E'
                && data[10] == 'B' && data[11] == 'P') {
            return Optional.of("image/webp");
        }
        if (startsWith(data, 'G', 'I', 'F', '8')) {
            return Optional.of("image/gif");
        }
        if (svg) {
            String start = new String(data, 0, Math.min(data.length, 512), StandardCharsets.UTF_8)
                    .stripLeading().toLowerCase(Locale.ROOT);
            if (start.startsWith("<svg") || (start.startsWith("<?xml") && start.contains("<svg"))) {
                return Optional.of("image/svg+xml");
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
