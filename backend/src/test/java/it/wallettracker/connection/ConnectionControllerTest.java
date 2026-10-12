package it.wallettracker.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.web.PublicIpAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** Test delle API per collegare le banche e per "Aggiorna ora". */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class ConnectionControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    EnableBankingClient client;

    /** Niente chiamate a ipify durante i test: l'IP pubblico lo decidiamo noi. */
    @MockitoBean
    PublicIpAddress publicIpAddress;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void listsTheBanksOfACountryWithTheConsentDurationInDays() {
        when(client.listAspsps("IT")).thenReturn(List.of(
                new Aspsp("ING", "IT", List.of(), Duration.ofDays(90).toSeconds(), List.of())));

        assertThat(mvc.get().uri("/api/banks?country=it"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[0].name").isEqualTo("ING");
                    json.assertThat().extractingPath("$[0].maxConsentDays").isEqualTo(90);
                });
    }

    @Test
    void completingAnUnknownLinkIs400WithTheReason() {
        assertThat(mvc.post().uri("/api/connections/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"c\", \"state\": \"stato-inventato\"}"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("non è valido o è scaduto");
    }

    @Test
    void anEnableBankingErrorBecomes502WithTheReason() {
        when(client.listAspsps("IT")).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,
                "Unauthorized", HttpHeaders.EMPTY, "{\"error\":\"UNAUTHORIZED\"}".getBytes(), null));

        assertThat(mvc.get().uri("/api/banks"))
                .hasStatus(502)
                .bodyJson().extractingPath("$.detail").asString().contains("HTTP 401");
    }

    @Test
    void updateNowReadsEveryAccountWithTheUserPresent() {
        when(publicIpAddress.get()).thenReturn(Optional.of("1.2.3.4"));
        BankConnection connection = connectionRepository.save(new BankConnection("ING", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account account = accountRepository.save(new Account(connection, "k-1", "uid-1", null, "Conto", "EUR"));
        // Aggiornato un minuto fa: la sincronizzazione automatica lo salterebbe, "Aggiorna ora" no.
        accountRepository.recordSync(account.getId(), Instant.now().minus(1, ChronoUnit.MINUTES), null);

        assertThat(mvc.post().uri("/api/sync").header("User-Agent", "Firefox"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.updated").isEqualTo(1);

        verify(client).getTransactions(eq("uid-1"), any(), any(), eq(new PsuHeaders("1.2.3.4", "Firefox")));
    }
}
