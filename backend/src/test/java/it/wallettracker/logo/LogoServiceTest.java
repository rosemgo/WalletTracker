package it.wallettracker.logo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** I loghi dei conti (docs/loghi.md), con un database vero; Enable Banking e lo scaricamento sono finti. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class LogoServiceTest {

    @MockitoBean
    EnableBankingClient client;

    @MockitoBean
    LogoDownloader downloader;

    @Autowired
    LogoService logoService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    AccountLogoRepository logoRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @AfterEach
    void cleanDatabase() {
        logoRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    private Account linked(String bank, String key) {
        BankConnection connection = connectionRepository.save(new BankConnection(bank, "IT", "s-" + key,
                Instant.now().plus(90, ChronoUnit.DAYS)));
        return accountRepository.save(new Account(connection, key, "u-" + key, null, "MARIO ROSSI", "EUR"));
    }

    @Test
    void theLogoOfTheBankIsDownloadedOnceAndServedFromHere() {
        Account ing = linked("Banca Alfa", "k-1");
        when(client.listAspsps("IT")).thenReturn(List.of(
                new Aspsp("Banca Alfa", "IT", List.of(), null, List.of(), "https://example.com/alfa.png")));
        when(downloader.download("https://example.com/alfa.png")).thenReturn(Optional.of(LogoImageTest.PNG));

        logoService.refreshBankLogos(Instant.now());

        assertThat(mvc.get().uri("/api/accounts/{id}/logo", ing.getId()))
                .hasStatusOk()
                .hasContentType("image/png")
                .hasHeader("X-Content-Type-Options", "nosniff")
                .satisfies(r -> assertThat(r.getResponse().getContentAsByteArray()).isEqualTo(LogoImageTest.PNG));
        // Nell'elenco dei conti, il numero da mettere nell'indirizzo dell'immagine.
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson()
                .extractingPath("$[0].logoVersion").isNotNull();

        // Una seconda volta non si scarica di nuovo.
        logoService.refreshBankLogos(Instant.now());
        verify(downloader, times(1)).download(any());
    }

    @Test
    void aBankWithoutLogoIsAskedAgainOnlyAfterAWeek() {
        Account account = linked("Banca Beta", "k-2");
        when(client.listAspsps("IT")).thenReturn(List.of(new Aspsp("Banca Beta", "IT", List.of(), null, List.of())));
        Instant now = Instant.now();

        logoService.refreshBankLogos(now);
        logoService.refreshBankLogos(now.plus(1, ChronoUnit.DAYS));

        verify(client, times(1)).listAspsps("IT");
        assertThat(mvc.get().uri("/api/accounts/{id}/logo", account.getId())).hasStatus(404);
        logoService.refreshBankLogos(now.plus(8, ChronoUnit.DAYS));
        verify(client, times(2)).listAspsps("IT");
        verify(downloader, never()).download(any());
    }

    @Test
    void aManualAccountGetsTheImageTheUserUploads() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR",
                AccountRole.INVESTMENT));

        assertThat(upload(wallet, LogoImageTest.JPEG)).hasStatus(204);
        assertThat(mvc.get().uri("/api/accounts/{id}/logo", wallet.getId()))
                .hasStatusOk().hasContentType("image/jpeg");

        // Un file che non è un'immagine (o un SVG) no.
        assertThat(upload(wallet, "<svg onload=alert(1)>".getBytes())).hasStatus(400);
        // Toglierlo.
        assertThat(mvc.delete().uri("/api/accounts/{id}/logo", wallet.getId())).hasStatus(204);
        assertThat(mvc.get().uri("/api/accounts/{id}/logo", wallet.getId())).hasStatus(404);
    }

    @Test
    void aLinkedAccountHasTheLogoOfTheBankAndNoUpload() {
        Account ing = linked("Banca Alfa", "k-3");

        assertThat(upload(ing, LogoImageTest.PNG)).hasStatus(409);
        assertThat(mvc.put().uri("/api/accounts/999999/logo").multipart()
                .file(new MockMultipartFile("file", "logo.png", "image/png", LogoImageTest.PNG))).hasStatus(404);
    }

    private MvcTestResult upload(Account account, byte[] data) {
        return mvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/accounts/{id}/logo", account.getId())
                .file(new MockMultipartFile("file", "logo", "application/octet-stream", data)));
    }
}
