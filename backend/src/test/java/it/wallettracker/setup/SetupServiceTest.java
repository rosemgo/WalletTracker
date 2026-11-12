package it.wallettracker.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingCredentials;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import org.junit.jupiter.api.Test;

/** Lo stato della configurazione quando non c'è nessun indirizzo di ritorno (installazione da ZIP, docs/47 parte Q). */
class SetupServiceTest {

    @Test
    void withoutReturnAddressesTheStatusSaysSoInsteadOfFailing() {
        EnableBankingCredentials credentials = mock(EnableBankingCredentials.class);
        when(credentials.current()).thenReturn(Optional.empty());
        SetupService service = new SetupService(credentials, mock(EnableBankingClient.class),
                new EnableBankingProperties("https://api.enablebanking.test", "", "", ""));

        SetupService.SetupStatus status = service.status();

        assertThat(status.configured()).isFalse();
        assertThat(status.redirectUrl()).isNull();
        assertThat(status.redirectUrls()).isEmpty();
    }
}
