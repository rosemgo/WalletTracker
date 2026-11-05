package it.wallettracker.about;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Il controllo delle versioni nuove (docs/50, parte J): {@code wallettracker.update} in {@code application.yml}.
 *
 * @param enabled      false = l'app non chiede mai niente a GitHub (WALLETTRACKER_UPDATE_CHECK=false nel .env)
 * @param url          l'indirizzo dell'ultima versione pubblicata; si cambia solo per le prove
 * @param checkEvery   ogni quanto chiedere (di serie una volta al giorno)
 * @param initialDelay quanto aspettare dopo l'avvio per la prima domanda
 */
@Validated
@ConfigurationProperties(prefix = "wallettracker.update")
public record UpdateProperties(boolean enabled, @NotBlank String url, @NotNull Duration checkEvery,
        @NotNull Duration initialDelay) {
}
