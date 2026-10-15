package it.wallettracker.bank.enablebanking;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configurazione di Enable Banking.
 *
 * <p>Spring riempie questo record con i valori che in {@code application.yml} stanno sotto
 * {@code enable-banking:} (per esempio {@code application-id} diventa {@code applicationId}).
 * Grazie a {@code @Validated}, se un valore obbligatorio manca l'applicazione non parte
 * e mostra il messaggio indicato.
 *
 * <p>{@code applicationId} e {@code privateKeyPath} sono facoltativi: dal passo 5 le credenziali si possono
 * configurare dalla dashboard (vedi {@link EnableBankingCredentials}).
 */
@Validated
@ConfigurationProperties(prefix = "enable-banking")
public record EnableBankingProperties(
        @NotBlank String baseUrl,
        String applicationId,
        String privateKeyPath,
        @NotBlank(message = "imposta ENABLE_BANKING_REDIRECT_URL nel file .env") String redirectUrl) {
}
