package it.spesetracker.bank.enablebanking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Utilità per i test: genera chiavi RSA usa-e-getta, così non serve una vera chiave di Enable Banking. */
public final class TestKeys {

    public static final String APPLICATION_ID = "cf589be3-3755-465b-a8df-a90a16a31403";

    private TestKeys() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Salva la chiave privata in un file PEM (formato PKCS#8, come quello di Enable Banking). */
    public static Path writePrivateKey(KeyPair keyPair, Path directory) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        return Files.writeString(directory.resolve("test-key.pem"), pem);
    }

    public static EnableBankingProperties properties(Path privateKey) {
        return new EnableBankingProperties("https://api.enablebanking.test", APPLICATION_ID, privateKey.toString(),
                "https://localhost:8443/callback");
    }
}
