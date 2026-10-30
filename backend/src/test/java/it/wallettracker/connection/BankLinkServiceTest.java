package it.wallettracker.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Access;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Account;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AccountId;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspRef;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationRequest;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationResponse;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import it.wallettracker.connection.BankLinkService.LinkResult;
import it.wallettracker.transaction.BankTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Test del collegamento di una banca dalla dashboard, con Enable Banking finto. */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class BankLinkServiceTest {

    private static final long NINETY_DAYS = Duration.ofDays(90).toSeconds();
    private static final PsuHeaders PSU = new PsuHeaders("1.2.3.4", "Firefox");

    @MockitoBean
    EnableBankingClient client;

    @Autowired
    BankLinkService bankLinkService;

    @Autowired
    EnableBankingProperties properties;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @BeforeEach
    void bankList() {
        when(client.listAspsps("IT")).thenReturn(List.of(
                new Aspsp("Revolut", "IT", List.of("personal"), null, List.of()),
                new Aspsp("ING", "IT", List.of("personal"), NINETY_DAYS, List.of())));
        when(client.startAuthorization(any())).thenReturn(new AuthorizationResponse("https://banca.example/login", "a-1"));
    }

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void banksAreSortedByName() {
        assertThat(bankLinkService.banks("IT")).extracting(Aspsp::name).containsExactly("ING", "Revolut");
    }

    @Test
    void startAsksForTheLongestConsentTheBankAllowsAndReturnsTheLoginPage() {
        String url = bankLinkService.start("ING", "IT", "localhost");

        assertThat(url).isEqualTo("https://banca.example/login");
        AuthorizationRequest request = sentAuthorization();
        assertThat(request.redirectUrl()).isEqualTo("https://localhost:8443/callback");
        assertThat(request.aspsp()).isEqualTo(new AspspRef("ING", "IT"));
        // ING concede al massimo 90 giorni: chiediamo 90 giorni meno 5 minuti di margine.
        assertThat(request.access().validUntil())
                .isBetween(Instant.now().plus(90, ChronoUnit.DAYS).minus(6, ChronoUnit.MINUTES),
                        Instant.now().plus(90, ChronoUnit.DAYS));
    }

    @Test
    void withoutABankLimitTheConsentLasts180Days() {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        Aspsp noLimit = new Aspsp("Revolut", "IT", List.of(), null, List.of());

        assertThat(BankLinkService.consentValidUntil(noLimit, now))
                .isEqualTo(now.plus(180, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES));
    }

    @Test
    void completeSavesTheAccountsAndImportsWithTheUserPresent() {
        bankLinkService.start("ING", "IT", "localhost");
        String state = sentAuthorization().state();
        when(client.createSession("code-1")).thenReturn(new Session("session-1",
                List.of(new Account("uid-a", new AccountId("IT60X001"), "Conto Arancio", "EUR", "hash-a")),
                new AspspRef("ING", "IT"), null));
        when(client.getTransactions(eq("uid-a"), any(), any(), eq(PSU))).thenReturn(List.of(raw(
                "{\"transaction_amount\":{\"amount\":\"9.90\",\"currency\":\"EUR\"},\"credit_debit_indicator\":\"DBIT\","
                        + "\"status\":\"BOOK\",\"booking_date\":\"2026-10-10\",\"remittance_information\":[\"NETFLIX\"]}")));

        LinkResult result = bankLinkService.complete("code-1", state, PSU);

        assertThat(result).isEqualTo(new LinkResult("ING", 1, 1, List.of()));
        assertThat(accountRepository.findByExternalKey("hash-a")).isPresent();
        // Il movimento è stato importato con gli header PSU (utente presente) e classificato.
        verify(client).getTransactions(eq("uid-a"), any(), any(), eq(PSU));
        assertThat(transactionRepository.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getType()).isNotNull());
    }

    @Test
    void anUnknownStateIsRejectedWithoutCallingTheBank() {
        assertThatThrownBy(() -> bankLinkService.complete("code-1", "stato-inventato", PSU))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non è valido o è scaduto");
        verify(client, never()).createSession(any());
    }

    @Test
    void aStateCanBeUsedOnlyOnce() {
        bankLinkService.start("ING", "IT", "localhost");
        String state = sentAuthorization().state();
        when(client.createSession("code-1")).thenReturn(new Session("session-1", List.of(),
                new AspspRef("ING", "IT"), new Access(Instant.now().plus(90, ChronoUnit.DAYS))));

        bankLinkService.complete("code-1", state, PSU);

        assertThatThrownBy(() -> bankLinkService.complete("code-1", state, PSU))
                .isInstanceOf(ResponseStatusException.class);
    }

    /** La richiesta che il nostro codice ha mandato al finto Enable Banking con startAuthorization(). */
    private AuthorizationRequest sentAuthorization() {
        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(client).startAuthorization(captor.capture());
        return captor.getValue();
    }

    private static RawTransaction raw(String json) {
        return new RawTransaction(new JsonMapper().readValue(json, Transaction.class), json);
    }
}
