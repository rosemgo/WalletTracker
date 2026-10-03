package it.spesetracker.bank.enablebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnableBankingJwtFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsTokenWithHeaderAndClaimsRequiredByEnableBanking() throws Exception {
        KeyPair keyPair = TestKeys.generateKeyPair();
        Path privateKey = TestKeys.writePrivateKey(keyPair, tempDir);
        EnableBankingJwtFactory factory = new EnableBankingJwtFactory(TestKeys.properties(privateKey));

        String[] parts = factory.createToken().split("\\.");

        assertThat(parts).hasSize(3);
        assertThat(decode(parts[0])).isEqualTo(
                "{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"" + TestKeys.APPLICATION_ID + "\"}");
        assertThat(decode(parts[1])).contains(
                "\"iss\":\"enablebanking.com\"", "\"aud\":\"api.enablebanking.com\"", "\"iat\":", "\"exp\":");
    }

    @Test
    void signatureCanBeVerifiedWithThePublicKey() throws Exception {
        KeyPair keyPair = TestKeys.generateKeyPair();
        Path privateKey = TestKeys.writePrivateKey(keyPair, tempDir);
        EnableBankingJwtFactory factory = new EnableBankingJwtFactory(TestKeys.properties(privateKey));

        String[] parts = factory.createToken().split("\\.");

        // È quello che fa Enable Banking: verifica la firma con la chiave pubblica.
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void explainsHowToConvertAKeyInPkcs1Format() throws Exception {
        Path wrongFormat = Files.writeString(tempDir.resolve("old.pem"),
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        assertThatThrownBy(() -> EnableBankingJwtFactory.loadPrivateKey(wrongFormat))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8");
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
