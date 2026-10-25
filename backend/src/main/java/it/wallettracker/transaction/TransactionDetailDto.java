package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Tutto quello che serve al dettaglio di un movimento (docs/37), in una risposta.
 *
 * @param transaction    il movimento, come nell'elenco
 * @param rawJson        i dati originali, come sono arrivati (dalla banca, o le celle del file)
 * @param importedAt     quando è arrivato nell'app
 * @param origin         da dove è arrivato
 * @param categoryOrigin perché ha quella categoria
 * @param ruleName       la regola che ha deciso, se è stata una regola (null altrimenti)
 * @param merchant       l'esercente e i suoi movimenti; null se il movimento non ha un testo da cui riconoscerlo
 * @param suggestion     la categoria suggerita, se il movimento non ne ha una e c'è un suggerimento
 */
public record TransactionDetailDto(TransactionDto transaction, String rawJson, Instant importedAt, Origin origin,
        CategoryOrigin categoryOrigin, String ruleName, Merchant merchant, Suggestion suggestion) {

    /** Da dove è arrivato un movimento. */
    public enum Origin {
        /** Dalla banca, con la sincronizzazione (Enable Banking). */
        BANK,
        /** Da un file CSV, in un conto aggiunto a mano (docs/25). */
        CSV,
        /** Dal file di Trade Republic, per i mesi che la banca non dà (docs/31). */
        TRADE_REPUBLIC_FILE
    }

    /** Perché un movimento ha la sua categoria. */
    public enum CategoryOrigin {
        /** Scelta da te (anche accettando un suggerimento): vince su tutto. */
        MANUAL,
        /** Messa da una regola. */
        RULE,
        /** Nessuna categoria. */
        NONE
    }

    /**
     * L'esercente del movimento e i suoi movimenti negli ultimi 12 mesi (questo compreso).
     *
     * @param name       il testo da cui è riconosciuto (la controparte o la causale)
     * @param movements  quanti movimenti
     * @param total      la loro somma (negativa se sono spese)
     * @param categories in quante categorie diverse sono: con più di una, una regola li metterebbe tutti insieme
     * @param recent     gli altri movimenti più recenti, al massimo cinque
     */
    public record Merchant(String key, String name, int movements, BigDecimal total, int categories,
            List<TransactionDto> recent) {
    }

    /** Una categoria suggerita per il movimento (dalla tua storia o dall'IA, docs/26 e docs/29). */
    public record Suggestion(String merchantKey, Long categoryId, String category, String model) {
    }
}
