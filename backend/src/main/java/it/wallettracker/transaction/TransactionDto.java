package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

import it.wallettracker.subscription.MerchantKey;

/**
 * Un movimento come lo vede la dashboard (vedi {@code AccountDto} per il perché dei DTO).
 * Il JSON originale della banca ({@code rawJson}) non c'è: è grande e alla lista non serve.
 *
 * @param name           il nome da mostrare: la controparte, o il nome scritto nella causale dopo un'etichetta
 *                       ("Anagrafica Ordinante ACME SRL", vedi MerchantKey), o la causale intera
 * @param type           il tipo deciso dalla classificazione (tiene già conto della correzione manuale)
 * @param categoryId       l'id della categoria (tiene già conto di quella scelta a mano), null se non c'è
 * @param category         il nome della categoria, da mostrare
 * @param manualType       la correzione manuale, null se non c'è
 * @param manualCategoryId l'id della categoria scelta a mano, null se non c'è
 * @param transferPeerId per i trasferimenti: l'id del movimento sull'altro conto
 * @param note           la nota scritta dall'utente, null se non c'è
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
        String name,
        TransactionType type,
        Long categoryId,
        String category,
        TransactionType manualType,
        Long manualCategoryId,
        Long transferPeerId,
        String note) {

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
                nameOf(transaction.getCounterparty(), transaction.getDescription()),
                transaction.getType(),
                transaction.getCategory() != null ? transaction.getCategory().getId() : null,
                transaction.getCategory() != null ? transaction.getCategory().getName() : null,
                transaction.getManualType(),
                transaction.getManualCategory() != null ? transaction.getManualCategory().getId() : null,
                transaction.getTransferPeerId(),
                transaction.getNote());
    }

    static String nameOf(String counterparty, String description) {
        if (counterparty != null && !counterparty.isBlank()) {
            return counterparty;
        }
        if (description == null || description.isBlank()) {
            return null;
        }
        String name = MerchantKey.nameInDescription(description);
        return name != null ? name : description;
    }
}
