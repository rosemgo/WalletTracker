package it.wallettracker.bank.enablebanking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Crea i JWT con cui la nostra applicazione si presenta a Enable Banking.
 *
 * <p>Un JWT è una stringa in tre parti separate da punti: {@code header.payload.firma}.
 * <ul>
 *   <li>header e payload sono JSON codificati in Base64URL;</li>
 *   <li>la firma è calcolata con la nostra <b>chiave privata</b> RSA (algoritmo RS256).</li>
 * </ul>
 * Enable Banking verifica la firma con la chiave pubblica ricevuta quando abbiamo registrato
 * l'applicazione: così dimostriamo chi siamo senza mai inviare la chiave privata.
 *
 * <p>Attenzione: il JWT identifica solo l'<em>applicazione</em>. Il permesso di leggere i conti
 * di una persona arriva dal consenso che quella persona dà alla propria banca.
 */
@Component
public class EnableBankingJwtFactory {

    /** Durata di ogni token: 1 ora (Enable Banking accetta al massimo 24 ore). */
    private static final long TOKEN_VALIDITY_SECONDS = 3600;

    private final String applicationId;
    private final PrivateKey privateKey;

    public EnableBankingJwtFactory(EnableBankingProperties properties) {
        this.applicationId = properties.applicationId();
        this.privateKey = loadPrivateKey(Path.of(properties.privateKeyPath()));
    }

    public String createToken() {
        long now = Instant.now().getEpochSecond();

        // "kid" (key id) dice a Enable Banking quale chiave pubblica usare per verificare la firma.
        String header = "{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"" + applicationId + "\"}";
        String payload = "{\"iss\":\"enablebanking.com\",\"aud\":\"api.enablebanking.com\","
                + "\"iat\":" + now + ",\"exp\":" + (now + TOKEN_VALIDITY_SECONDS) + "}";

        String unsignedToken = base64Url(header.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return unsignedToken + "." + base64Url(sign(unsignedToken));
    }

    private byte[] sign(String data) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Impossibile firmare il JWT", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Legge la chiave privata da un file PEM in formato PKCS#8, cioè un file di testo così:
     * <pre>
     * -----BEGIN PRIVATE KEY-----
     * MIIEvQIBADANBgkqhkiG9w0BAQEFAASC...
     * -----END PRIVATE KEY-----
     * </pre>
     * Il contenuto tra le due righe è la chiave in binario, codificata in Base64.
     */
    static PrivateKey loadPrivateKey(Path pemFile) {
        String pem;
        try {
            pem = Files.readString(pemFile);
        } catch (IOException e) {
            throw new IllegalStateException("Non riesco a leggere la chiave privata: " + pemFile.toAbsolutePath(), e);
        }

        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalStateException("Il file " + pemFile + " non è una chiave in formato PKCS#8 "
                    + "('-----BEGIN PRIVATE KEY-----'). Se inizia con 'BEGIN RSA PRIVATE KEY' convertila con: "
                    + "openssl pkcs8 -topk8 -nocrypt -in vecchia.pem -out enable-banking.pem");
        }

        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);

        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Il file " + pemFile + " non contiene una chiave privata RSA valida", e);
        }
    }
}
