package it.wallettracker.backup;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dove sono i backup, per la dashboard (docs/48, parte Q): {@code wallettracker.backup} in {@code application.yml}.
 *
 * @param directory      la cartella dei backup (nel container: montata in sola lettura)
 * @param stateDirectory la cartella dove il container "backup" scrive lo stato e legge le richieste
 */
@Validated
@ConfigurationProperties(prefix = "wallettracker.backup")
public record BackupProperties(@NotBlank String directory, @NotBlank String stateDirectory) {
}
