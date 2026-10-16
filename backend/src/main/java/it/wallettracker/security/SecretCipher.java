package it.wallettracker.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra e decifra i segreti salvati nel database (oggi: la chiave privata di Enable Banking).
 *
 * <p><b>L'idea ("cifratura a busta").</b> Nel database il segreto è cifrato; la <b>chiave di cifratura</b> sta
 * FUORI dal database, in un file ({@code .data/encryption.key}). Chi ottiene solo il database (o un suo backup)
 * vede testo illeggibile: per decifrarlo servono database E file della chiave.
 *
 * <p><b>L'algoritmo: AES-256-GCM.</b>
 * <ul>
 *   <li><b>AES-256</b>: lo standard per la cifratura simmetrica (la stessa chiave cifra e decifra), chiave da
 *       256 bit;</li>
 *   <li><b>GCM</b>: oltre a nascondere il testo, aggiunge un "sigillo" (tag di autenticazione da 128 bit). Se
 *       qualcuno modifica anche un solo byte del testo cifrato, la decifratura fallisce invece di restituire
 *       dati alterati;</li>
 *   <li><b>IV</b> (vettore di inizializzazione): 12 byte casuali, nuovi a ogni cifratura. Così lo stesso
 *       segreto cifrato due volte dà due risultati diversi. L'IV non è segreto: si salva accanto al testo
 *       cifrato;</li>
 *   <li><b>dati associati</b> (AAD): un'etichetta che dice "a cosa serve" il segreto (es.
 *       "enable_banking.private_key"). Non viene cifrata ma fa parte del sigillo: un valore cifrato per uno
 *       scopo non può essere spostato e usato per un altro.</li>
 * </ul>
 *
 * <p>Formato salvato: {@code v1:<Base64(IV + testo cifrato con il tag)>}. Il prefisso {@code v1:} permetterà, un
 * giorno, di cambiare algoritmo riconoscendo i valori vecchi.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String VERSION_PREFIX = "v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;   // 256 bit
    private static final int IV_BYTES = 12;    // la lunghezza raccomandata per GCM
    private static final int TAG_BITS = 128;

    /** SecureRandom: numeri casuali adatti alla crittografia (non prevedibili, a differenza di Random). */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    /**
     * Il percorso arriva come testo e diventa un Path con {@code Path.of}: lasciando a Spring la conversione
     * diretta in Path, un percorso relativo come "../.data/encryption.key" verrebbe trattato come una
     * "risorsa" e rifiutato.
     */
    public SecretCipher(@Value("${wallettracker.encryption.key-file}") String keyFile) {
        this.key = new SecretKeySpec(loadOrCreateKey(Path.of(keyFile)), "AES");
    }

    /** Cifra un segreto. {@code purpose} è l'etichetta (AAD): va usata uguale per decifrare. */
    public String encrypt(String secret, String purpose) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndEncrypted = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(ivAndEncrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cifratura non riuscita", e);
        }
    }

    /**
     * Decifra un segreto. Se la chiave di cifratura non è quella giusta, o il valore è stato alterato, o
     * l'etichetta non corrisponde, lancia {@link IllegalStateException}: non restituisce mai dati sbagliati.
     */
    public String decrypt(String stored, String purpose) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Valore cifrato in un formato sconosciuto");
        }
        try {
            byte[] ivAndEncrypted = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ivAndEncrypted, 0, IV_BYTES));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(ivAndEncrypted, IV_BYTES, ivAndEncrypted.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Impossibile decifrare il segreto: la chiave di cifratura è cambiata "
                    + "o il valore è stato alterato", e);
        }
    }

    /**
     * Legge la chiave di cifratura dal file; se il file non esiste (prima installazione), la genera.
     *
     * <p>Sui sistemi Linux/macOS il file viene reso leggibile solo dall'utente che esegue WalletTracker
     * (permessi 600). Su Windows i permessi dipendono dalla cartella: tienila nella tua cartella utente.
     */
    static byte[] loadOrCreateKey(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                byte[] key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
                if (key.length != KEY_BYTES) {
                    throw new IllegalStateException("Il file " + keyFile.toAbsolutePath()
                            + " non contiene una chiave di cifratura valida (32 byte in Base64)");
                }
                return key;
            }
            byte[] key = new byte[KEY_BYTES];
            RANDOM.nextBytes(key);
            if (keyFile.toAbsolutePath().getParent() != null) {
                Files.createDirectories(keyFile.toAbsolutePath().getParent());
            }
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            }
            log.info("Creata una nuova chiave di cifratura: {}. Includila nei backup, separata dal database.",
                    keyFile.toAbsolutePath());
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile leggere o creare la chiave di cifratura " + keyFile, e);
        }
    }
}
