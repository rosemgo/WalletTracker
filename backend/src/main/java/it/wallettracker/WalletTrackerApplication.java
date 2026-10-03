package it.wallettracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Punto di ingresso dell'applicazione.
 *
 * <p>{@code @SpringBootApplication} dice a Spring di cercare i componenti ({@code @Component})
 * in questo package e nei sotto-package, crearli e collegarli tra loro (dependency injection).
 * {@code @ConfigurationPropertiesScan} fa lo stesso per le classi di configurazione
 * annotate con {@code @ConfigurationProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WalletTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletTrackerApplication.class, args);
    }
}
