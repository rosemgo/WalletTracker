package it.spesetracker;

import java.nio.file.Files;
import java.nio.file.Path;

import it.spesetracker.bank.enablebanking.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifica che l'applicazione Spring parta: tutti i componenti vengono creati e collegati correttamente. */
@SpringBootTest
class SpeseTrackerApplicationTests {

    /** Per il test usiamo un application id finto e una chiave generata al volo. */
    @DynamicPropertySource
    static void enableBankingProperties(DynamicPropertyRegistry registry) throws Exception {
        Path privateKey = TestKeys.writePrivateKey(TestKeys.generateKeyPair(), Files.createTempDirectory("spese-test"));
        registry.add("enable-banking.application-id", () -> TestKeys.APPLICATION_ID);
        registry.add("enable-banking.private-key-path", privateKey::toString);
    }

    @Test
    void contextLoads() {
    }
}
