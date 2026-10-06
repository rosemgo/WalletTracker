package it.wallettracker.bank.enablebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test del client senza chiamare davvero Enable Banking: {@link MockRestServiceServer}
 * intercetta le richieste HTTP, controlla che siano quelle attese e risponde con JSON di esempio.
 */
class EnableBankingClientTest {

    @TempDir
    Path tempDir;

    private MockRestServiceServer server;
    private EnableBankingClient client;

    @BeforeEach
    void setUp() throws Exception {
        Path privateKey = TestKeys.writePrivateKey(TestKeys.generateKeyPair(), tempDir);
        EnableBankingProperties properties = TestKeys.properties(privateKey);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EnableBankingClient(builder, properties, new EnableBankingJwtFactory(properties), new JsonMapper());
    }

    @Test
    void listsBanksAndSendsTheJwt() {
        server.expect(requestTo("https://api.enablebanking.test/aspsps?country=IT"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess("""
                        {"aspsps": [
                          {"name": "ING", "country": "IT", "psu_types": ["personal"],
                           "maximum_consent_validity": 15552000, "required_psu_headers": ["Psu-Ip-Address"],
                           "logo": "https://example.com/ing.png"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<Aspsp> banks = client.listAspsps("IT");

        assertThat(banks).containsExactly(new Aspsp("ING", "IT", List.of("personal"), 15552000L,
                List.of("Psu-Ip-Address")));
        server.verify();
    }

    @Test
    void readsAllPagesOfTransactions() {
        server.expect(requestTo("https://api.enablebanking.test/accounts/acc-1/transactions"
                        + "?date_from=2026-09-01&date_to=2026-09-30"))
                .andRespond(withSuccess("""
                        {"transactions": [
                          {"entry_reference": "t1", "transaction_amount": {"amount": "12.50", "currency": "EUR"},
                           "credit_debit_indicator": "DBIT", "status": "BOOK", "booking_date": "2026-09-10",
                           "creditor": {"name": "ESSELUNGA"}, "remittance_information": ["PAGAMENTO POS"]}
                        ],
                         "continuation_key": "pag+2/="}
                        """, MediaType.APPLICATION_JSON));
        // La continuation_key deve arrivare codificata correttamente nell'URL ('+' → %2B, '/' → %2F, '=' → %3D).
        server.expect(requestTo("https://api.enablebanking.test/accounts/acc-1/transactions"
                        + "?date_from=2026-09-01&date_to=2026-09-30&continuation_key=pag%2B2%2F%3D"))
                .andRespond(withSuccess("""
                        {"transactions": [
                          {"entry_reference": "t2", "transaction_amount": {"amount": "1500.00", "currency": "EUR"},
                           "credit_debit_indicator": "CRDT", "status": "BOOK", "booking_date": "2026-09-27",
                           "debtor": {"name": "DATORE DI LAVORO SRL"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<RawTransaction> transactions = client.getTransactions("acc-1",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(transactions).extracting(raw -> raw.transaction().entryReference()).containsExactly("t1", "t2");
        assertThat(transactions.get(0).transaction().signedAmount()).isEqualByComparingTo(new BigDecimal("-12.50"));
        assertThat(transactions.get(0).transaction().creditor().name()).isEqualTo("ESSELUNGA");
        assertThat(transactions.get(1).transaction().signedAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        // Il JSON originale viene conservato per intero.
        assertThat(transactions.get(0).json()).contains("\"entry_reference\":\"t1\"", "PAGAMENTO POS");
        server.verify();
    }

    @Test
    void sendsPsuHeadersOnlyWhenTheUserIsPresent() {
        String balancesJson = """
                {"balances": [{"balance_amount": {"amount": "10.00", "currency": "EUR"}, "balance_type": "ITAV"}]}
                """;
        // Utente presente: gli header PSU devono esserci.
        server.expect(requestTo("https://api.enablebanking.test/accounts/acc-1/balances"))
                .andExpect(header("Psu-Ip-Address", "203.0.113.7"))
                .andExpect(header("Psu-User-Agent", "WalletTracker-test"))
                .andRespond(withSuccess(balancesJson, MediaType.APPLICATION_JSON));
        // Lettura in background: niente header PSU.
        server.expect(requestTo("https://api.enablebanking.test/accounts/acc-1/balances"))
                .andExpect(headerDoesNotExist("Psu-Ip-Address"))
                .andRespond(withSuccess(balancesJson, MediaType.APPLICATION_JSON));

        client.getBalances("acc-1", new PsuHeaders("203.0.113.7", "WalletTracker-test"));
        client.getBalances("acc-1");

        server.verify();
    }
}
