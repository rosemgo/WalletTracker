package it.wallettracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test della cifratura dei segreti (AES-256-GCM). */
class SecretCipherTest {

    private static final String SECRET = "-----BEGIN PRIVATE KEY-----\nMIIEvQ...\n-----END PRIVATE KEY-----\n";
    private static final String PURPOSE = "enable_banking.private_key";

    @TempDir
    Path tempDir;

    @Test
    void whatIsEncryptedCanBeDecryptedAndDoesNotContainTheSecret() {
        SecretCipher cipher = new SecretCipher(tempDir.resolve("encryption.key").toString());

        String stored = cipher.encrypt(SECRET, PURPOSE);

        assertThat(stored).startsWith("v1:").doesNotContain("PRIVATE KEY");
        assertThat(cipher.decrypt(stored, PURPOSE)).isEqualTo(SECRET);
    }

    @Test
    void theSameSecretEncryptedTwiceGivesDifferentResults() {
        SecretCipher cipher = new SecretCipher(tempDir.resolve("encryption.key").toString());

        // Merito dell'IV casuale: chi guarda il database non può capire se due segreti sono uguali.
        assertThat(cipher.encrypt(SECRET, PURPOSE)).isNotEqualTo(cipher.encrypt(SECRET, PURPOSE));
    }

    @Test
    void theKeyFileIsCreatedOnceAndReused() throws Exception {
        Path keyFile = tempDir.resolve("sub/encryption.key");
        String stored = new SecretCipher(keyFile.toString()).encrypt(SECRET, PURPOSE);

        assertThat(Files.exists(keyFile)).isTrue();
        // Un nuovo avvio (nuova istanza) rilegge la stessa chiave e decifra.
        assertThat(new SecretCipher(keyFile.toString()).decrypt(stored, PURPOSE)).isEqualTo(SECRET);
    }

    @Test
    void withAnotherKeyNothingCanBeDecrypted() {
        String stored = new SecretCipher(tempDir.resolve("a.key").toString()).encrypt(SECRET, PURPOSE);

        assertThatThrownBy(() -> new SecretCipher(tempDir.resolve("b.key").toString()).decrypt(stored, PURPOSE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAlteredValueOrAnotherPurposeIsRejected() {
        SecretCipher cipher = new SecretCipher(tempDir.resolve("encryption.key").toString());
        String stored = cipher.encrypt(SECRET, PURPOSE);
        char last = stored.charAt(stored.length() - 3);
        String altered = stored.substring(0, stored.length() - 3) + (last == 'A' ? 'B' : 'A') + stored.substring(stored.length() - 2);

        assertThatThrownBy(() -> cipher.decrypt(altered, PURPOSE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, "altro.scopo")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRelativePathWorksToo() throws Exception {
        // Come in application.yml ("../.data/encryption.key"): un percorso relativo alla cartella di avvio.
        Path relative = Path.of("").toAbsolutePath().relativize(tempDir.resolve("rel/encryption.key"));
        SecretCipher cipher = new SecretCipher(relative.toString());

        assertThat(cipher.decrypt(cipher.encrypt(SECRET, PURPOSE), PURPOSE)).isEqualTo(SECRET);
        assertThat(Files.exists(tempDir.resolve("rel/encryption.key"))).isTrue();
    }
}
