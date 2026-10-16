package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un movimento come lo vede la dashboard (vedi {@code AccountDto} per il perché dei DTO).
 * Il JSON originale della banca ({@code rawJson}) non c'è: è grande e alla lista non serve.
 *
 * @param type           il tipo deciso dalla classificazione (tiene già conto della correzione manuale)
 * @param category       la categoria (tiene già conto di quella scelta a mano)
 * @param manualType     la correzione manuale, null se non c'è
 * @param manualCategory la categoria scelta a mano, null se non c'è
 * @param transferPeerId per i trasferimenti: l'id del movimento sull'altro conto
 */
public record TransactionDto(
        Long id,
        Long accountId,
        TransactionStatus status,
        LocalDate bookingDate,
        LocalDate valueDate,
        BigDecimal amount,
        String currency,
        String counterparty,
        String description,
        TransactionType type,
        String category,
        TransactionType manualType,
        String manualCategory,
        Long transferPeerId) {

    public static TransactionDto from(BankTransaction transaction) {
        return new TransactionDto(
                transaction.getId(),
                transaction.getAccount().getId(),
                transaction.getStatus(),
                transaction.getBookingDate(),
                transaction.getValueDate(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getCounterparty(),
                transaction.getDescription(),
                transaction.getType(),
                transaction.getCategory(),
                transaction.getManualType(),
                transaction.getManualCategory(),
                transaction.getTransferPeerId());
    }
}
