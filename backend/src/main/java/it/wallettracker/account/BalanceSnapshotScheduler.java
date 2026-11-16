package it.wallettracker.account;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Il timer delle fotografie dei saldi (docs/saldi.md, parte L): ogni ora, il saldo di oggi di ogni conto. Ogni ora e
 * non una volta al giorno perché l'app gira su un computer che può essere spento: così basta che sia acceso un momento
 * perché il giorno abbia il suo valore vero, e il saldo arrivato con l'ultimo aggiornamento sostituisce quello di
 * prima. Nei test è spento ({@code wallettracker.balance-history.enabled}), come la sincronizzazione.
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty("wallettracker.balance-history.enabled")
public class BalanceSnapshotScheduler {

    private final BalanceHistoryService historyService;

    public BalanceSnapshotScheduler(BalanceHistoryService historyService) {
        this.historyService = historyService;
    }

    @Scheduled(initialDelayString = "${wallettracker.balance-history.initial-delay}",
            fixedDelayString = "${wallettracker.balance-history.every}")
    public void recordToday() {
        historyService.recordToday(LocalDate.now());
    }
}
