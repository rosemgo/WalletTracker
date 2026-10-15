package it.wallettracker.bank.enablebanking;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code enable_banking_settings} (una sola riga). */
public interface EnableBankingSettingsRepository extends JpaRepository<EnableBankingSettings, Integer> {
}
