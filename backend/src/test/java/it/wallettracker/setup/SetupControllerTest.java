package it.wallettracker.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingCredentials;
import it.wallettracker.bank.enablebanking.EnableBankingJwtFactory;
import it.wallettracker.bank.enablebanking.EnableBankingSettingsRepository;
import it.wallettracker.bank.enablebanking.TestKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Test della configurazione guidata. Enable Banking è finto: checkCredentials() non fa nulla (= credenziali
 * accettate), a meno che un test non gli dica di lanciare un errore.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class SetupControllerTest {

    private static final String NEW_APPLICATION_ID = "0b6f1d2e-aaaa-bbbb-cccc-123456789abc";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    EnableBankingClient client;

    @Autowired
    EnableBankingSettingsRepository settingsRepository;

    @Autowired
    EnableBankingCredentials credentials;

    @Autowired
    EnableBankingJwtFactory jwtFactory;

    @AfterEach
    void backToTheEnvCredentials() {
        settingsRepository.deleteAll();
        credentials.clearCache();
    }

    @Test
    void withTheEnvFileTheStatusSaysWhereTheCredentialsComeFrom() {
        // Nei test le credenziali arrivano dalle proprietà (come dal .env): vedi IntegrationTestConfiguration.
        assertThat(mvc.get().uri("/api/setup"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.configured").isEqualTo(true);
                    json.assertThat().extractingPath("$.source").isEqualTo("ENV");
                    json.assertThat().extractingPath("$.applicationId").isEqualTo(TestKeys.APPLICATION_ID);
                    json.assertThat().extractingPath("$.redirectUrl").isEqualTo("https://localhost:8443/callback");
                    json.assertThat().doesNotHavePath("$.privateKeyPem"); // la chiave non esce mai
                });
    }

    @Test
    void validCredentialsAreCheckedSavedAndUsedFromNowOn() {
        assertThat(save(NEW_APPLICATION_ID, TestKeys.pem(TestKeys.generateKeyPair())))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.source").isEqualTo("DASHBOARD");
                    json.assertThat().extractingPath("$.applicationId").isEqualTo(NEW_APPLICATION_ID);
                });

        // La prova con Enable Banking è stata fatta con un JWT delle credenziali NUOVE...
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(client).checkCredentials(token.capture());
        assertThat(kidOf(token.getValue())).isEqualTo(NEW_APPLICATION_ID);
        // ...e da ora in poi tutti i JWT usano le credenziali salvate dalla dashboard.
        assertThat(kidOf(jwtFactory.createToken())).isEqualTo(NEW_APPLICATION_ID);
    }

    @Test
    void aFileThatIsNotAPrivateKeyIsRejectedWithoutCallingEnableBanking() {
        assertThat(save(NEW_APPLICATION_ID, "questo non è un file .pem"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("PKCS#8");

        verify(client, never()).checkCredentials(any());
        assertThat(settingsRepository.count()).isZero();
    }

    @Test
    void credentialsRejectedByEnableBankingAreNotSaved() {
        doThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                new byte[0], null)).when(client).checkCredentials(any());

        assertThat(save(NEW_APPLICATION_ID, TestKeys.pem(TestKeys.generateKeyPair())))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("rifiutato");

        assertThat(settingsRepository.count()).isZero();
    }

    private MvcTestResult save(String applicationId, String pem) {
        String body = "{\"applicationId\": \"" + applicationId + "\", \"privateKeyPem\": \""
                + pem.replace("\n", "\\n") + "\"}";
        return mvc.put().uri("/api/setup/enable-banking").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** Il "kid" nell'header di un JWT: l'Application ID con cui è stato firmato. */
    private static String kidOf(String jwt) {
        String header = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
        return header.replaceAll(".*\"kid\":\"([^\"]+)\".*", "$1");
    }
}
