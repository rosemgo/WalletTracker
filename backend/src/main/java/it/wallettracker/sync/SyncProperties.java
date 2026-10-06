package it.wallettracker.sync;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Le impostazioni della sincronizzazione automatica, lette da application.yml (sezione {@code wallettracker.sync}).
 *
 * <p>Spring converte da solo testi come {@code "6h"} o {@code "30m"} in un {@link Duration}.
 *
 * @param enabled      se false la sincronizzazione automatica non parte (es. con il profilo "poc")
 * @param interval     ogni quanto aggiornare <b>ciascun conto</b>: 6 ore = 4 letture al giorno, il limite
 *                     PSD2 delle letture in background
 * @param checkEvery   ogni quanto il programma controlla se c'è qualche conto da aggiornare
 * @param initialDelay quanto aspettare dopo l'avvio prima del primo controllo
 */
@Validated
@ConfigurationProperties(prefix = "wallettracker.sync")
public record SyncProperties(
        boolean enabled,
        @NotNull Duration interval,
        @NotNull Duration checkEvery,
        @NotNull Duration initialDelay) {
}
