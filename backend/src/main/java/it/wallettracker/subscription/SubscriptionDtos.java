package it.wallettracker.subscription;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** I record delle API degli abbonamenti, raccolti in un file solo perché sono piccoli e vanno insieme. */
public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    /**
     * Un abbonamento suggerito: si ripete a intervalli regolari con importi simili.
     *
     * @param merchantKey   la chiave dell'esercente (va rimandata indietro per confermare o ignorare)
     * @param name          un nome proposto: il testo dell'ultimo pagamento
     * @param typicalAmount l'importo tipico (la mediana)
     * @param occurrences   quanti pagamenti sono stati trovati
     */
    public record Suggestion(String merchantKey, String currency, String name, Frequency frequency,
            BigDecimal typicalAmount, int occurrences, LocalDate lastDate, LocalDate nextDate) {
    }

    /**
     * Un abbonamento confermato, con i dati aggiornati dai movimenti.
     *
     * @param lastAmount    l'importo dell'ultimo pagamento (null se non ce ne sono negli ultimi 13 mesi)
     * @param monthlyCost   quanto costa al mese, calcolato dall'ultimo importo (per un annuale: diviso 12)
     * @param priceChanged  l'ultimo importo è diverso da quello al momento della conferma
     * @param late          il pagamento previsto non è arrivato (con qualche giorno di margine): forse disdetto?
     */
    public record Confirmed(Long id, String merchantKey, String currency, String name, Frequency frequency,
            BigDecimal expectedAmount, BigDecimal lastAmount, LocalDate lastDate, LocalDate nextDate,
            BigDecimal monthlyCost, boolean priceChanged, boolean late) {
    }

    /** Il costo totale degli abbonamenti confermati in una valuta. */
    public record Total(String currency, BigDecimal monthly, BigDecimal yearly) {
    }

    /** Un esercente che l'utente ha detto di non suggerire più (si può annullare). */
    public record Dismissed(Long id, String merchantKey, String currency) {
    }

    /** La risposta di {@code GET /api/subscriptions}. */
    public record Overview(List<Confirmed> subscriptions, List<Total> totals, List<Dismissed> dismissed) {
    }

    /** Il corpo di {@code POST /api/subscriptions}: "sì, è un abbonamento". */
    public record ConfirmRequest(
            @NotNull @Pattern(regexp = "[\\p{L} ]{2,60}") String merchantKey,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 100) String name,
            @NotNull Frequency frequency,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal expectedAmount) {
    }

    /** Il corpo di {@code POST /api/subscriptions/dismissed}: "no, non suggerirlo più". */
    public record DismissRequest(
            @NotNull @Pattern(regexp = "[\\p{L} ]{2,60}") String merchantKey,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }
}
