package it.wallettracker.transaction;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Il codice dell'operazione che la banca manda con un movimento (docs/codice-della-banca.md).
 *
 * <p>Enable Banking lo passa nel campo {@code bank_transaction_code} del JSON originale, con tre parti facoltative:
 * {@code code}, {@code sub_code} e {@code description}. Ogni banca riempie quelle che vuole:
 * <ul>
 *   <li>Fineco: {@code {"code": "I18-Interessi Passivi"}};</li>
 *   <li>ING: {@code {"description": "Accredito Stipendio/Pensione"}};</li>
 *   <li>Revolut: {@code {"code": "CARD_PAYMENT"}}.</li>
 * </ul>
 * Il codice dice <b>che operazione è</b> (pagamento con carta, bonifico, prelievo, stipendio), e a volte la causale
 * non lo dice: lo stipendio di ING arriva come "Bonifico N. … Note: STIPENDIO MESE …". Le regole possono guardare
 * qui invece che nel testo (ClassificationRule.MatchField.BANK_CODE).
 *
 * <p>Non lo salviamo in una colonna: si legge dal JSON originale, che c'è già e non cambia. Così vale subito anche per
 * i movimenti arrivati prima di questa versione.
 */
public final class BankCode {

    /** Il campo del JSON di Enable Banking. */
    static final String FIELD = "bank_transaction_code";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BankCode() {
    }

    /**
     * Le parti del codice che la banca ha riempito, in quest'ordine (code, sub_code, description), separate da " · ";
     * null se il movimento non ha un codice (i movimenti dei file CSV, e molte banche).
     */
    public static String of(String rawJson) {
        // Quasi tutti i movimenti non ce l'hanno: senza il nome del campo non serve nemmeno leggere il JSON.
        if (rawJson == null || !rawJson.contains(FIELD)) {
            return null;
        }
        JsonNode code;
        try {
            code = JSON.readTree(rawJson).path(FIELD);
        } catch (JacksonException e) {
            return null; // un JSON rovinato non deve fermare la classificazione
        }
        if (!code.isObject()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String name : List.of("code", "sub_code", "description")) {
            JsonNode part = code.path(name);
            if (part.isString() && !part.asString().isBlank()) {
                parts.add(part.asString().trim());
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
