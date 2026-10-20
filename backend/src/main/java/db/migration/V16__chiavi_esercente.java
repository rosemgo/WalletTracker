package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V16: ricalcola le chiavi esercente salvate, dopo il cambio della regola di {@code MerchantKey}.
 *
 * <p>La chiave esercente è salvata in due tabelle: {@code subscription} (abbonamenti confermati o rifiutati)
 * e {@code category_suggestion_dismissal} (suggerimenti rifiutati, V15). La regola nuova la calcola in modo
 * diverso: se la controparte è il titolare del conto usa la causale, e toglie le parole con cifre (codici
 * d'ordine come "BT9YM4924"). Senza questa migrazione le righe salvate non corrisponderebbero più ai movimenti.
 *
 * <p>È una migrazione <b>in Java</b> (Flyway le cerca nella stessa cartella delle SQL, {@code db/migration}):
 * in SQL sarebbe difficile riscrivere le due regole. Il procedimento:
 * <ol>
 *   <li>per ogni movimento calcola la chiave vecchia e quella nuova: così sa in quale chiave nuova si
 *       trasforma ogni chiave vecchia;</li>
 *   <li>ogni riga salvata con una chiave vecchia che diventa <b>una sola</b> chiave nuova viene aggiornata.
 *       Se diventa più chiavi (es. il nome del titolare, che copriva negozi diversi) non si può sapere quale:
 *       la riga resta com'è, e semplicemente non corrisponde più a niente;</li>
 *   <li>se due righe finiscono sulla stessa chiave (es. "AMAZON BT YM" e "AMAZON BT BJ" diventano entrambe
 *       "AMAZON"), ne resta una: per gli abbonamenti vince quella confermata, anche contro un "no" che
 *       aveva già la chiave nuova.</li>
 * </ol>
 *
 * <p>Le due regole sono <b>copiate qui</b>, non prese da {@code MerchantKey}: una migrazione deve fare sempre
 * la stessa cosa, anche se un giorno {@code MerchantKey} cambierà di nuovo.
 */
public class V16__chiavi_esercente extends BaseJavaMigration {

    private static final int MAX_LENGTH = 60;

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        List<Set<String>> owners = ownerNames(connection);
        Map<String, Set<String>> renames = renames(connection, owners);
        rekeySubscriptions(connection, renames);
        rekeyDismissals(connection, renames);
    }

    /** I nomi dei titolari (come ClassificationService.ownerNames): conti non manuali, almeno due parole. */
    private static List<Set<String>> ownerNames(Connection connection) throws SQLException {
        List<Set<String>> owners = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT name FROM account WHERE source <> 'MANUAL' AND name IS NOT NULL")) {
            while (rows.next()) {
                Set<String> name = words(rows.getString("name"));
                if (name.size() >= 2 && !owners.contains(name)) {
                    owners.add(name);
                }
            }
        }
        return owners;
    }

    /** Per ogni chiave vecchia, le chiavi nuove in cui si trasforma (di solito una). */
    private static Map<String, Set<String>> renames(Connection connection, List<Set<String>> owners)
            throws SQLException {
        Map<String, Set<String>> renames = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT counterparty, description FROM bank_transaction")) {
            while (rows.next()) {
                String counterparty = rows.getString("counterparty");
                String description = rows.getString("description");
                String oldKey = oldKey(counterparty, description);
                String newKey = newKey(counterparty, description, owners);
                if (oldKey != null && newKey != null) {
                    renames.computeIfAbsent(oldKey, key -> new HashSet<>()).add(newKey);
                }
            }
        }
        return renames;
    }

    private static void rekeySubscriptions(Connection connection, Map<String, Set<String>> renames)
            throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, merchant_key, currency, status FROM subscription ORDER BY id")) {
            while (result.next()) {
                rows.add(new Object[] {result.getLong("id"), result.getString("merchant_key"),
                        result.getString("currency"), result.getString("status")});
            }
        }
        for (Object[] row : rows) {
            long id = (Long) row[0];
            String target = target(renames, (String) row[1]);
            if (target == null) {
                continue;
            }
            Object[] existing = find(connection,
                    "SELECT id, status FROM subscription WHERE merchant_key = ? AND currency = ?", target, row[2]);
            if (existing == null) {
                update(connection, "subscription", id, target);
            } else if ("CONFIRMED".equals(row[3]) && !"CONFIRMED".equals(existing[1])) {
                // Sulla stessa chiave c'è già un "no": vince la conferma, che ha nome, frequenza e importo.
                delete(connection, "subscription", (Long) existing[0]);
                update(connection, "subscription", id, target);
            } else {
                delete(connection, "subscription", id); // la decisione su quella chiave c'è già
            }
        }
    }

    private static void rekeyDismissals(Connection connection, Map<String, Set<String>> renames)
            throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, merchant_key, category_id FROM category_suggestion_dismissal ORDER BY id")) {
            while (result.next()) {
                rows.add(new Object[] {result.getLong("id"), result.getString("merchant_key"), result.getLong("category_id")});
            }
        }
        for (Object[] row : rows) {
            String target = target(renames, (String) row[1]);
            if (target == null) {
                continue;
            }
            if (find(connection, "SELECT id, category_id FROM category_suggestion_dismissal "
                    + "WHERE merchant_key = ? AND category_id = ?", target, row[2]) != null) {
                delete(connection, "category_suggestion_dismissal", (Long) row[0]); // lo stesso "no", già salvato
            } else {
                update(connection, "category_suggestion_dismissal", (Long) row[0], target);
            }
        }
    }

    /** La chiave nuova di una chiave vecchia, se è una sola e diversa; altrimenti null (la riga resta com'è). */
    private static String target(Map<String, Set<String>> renames, String oldKey) {
        Set<String> targets = renames.get(oldKey);
        if (targets == null || targets.size() != 1) {
            return null;
        }
        String target = targets.iterator().next();
        return target.equals(oldKey) ? null : target;
    }

    /** La prima riga trovata (id e un secondo valore), o null. */
    private static Object[] find(Connection connection, String sql, String key, Object other) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setObject(2, other);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Object[] {result.getLong(1), result.getObject(2)} : null;
            }
        }
    }

    // Il nome della tabella viene da questa classe, mai dall'esterno: concatenarlo nella query è sicuro.
    private static void update(Connection connection, String table, long id, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET merchant_key = ? WHERE id = ?")) {
            statement.setString(1, key);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private static void delete(Connection connection, String table, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    // --- Le due regole, congelate ---

    /** La regola fino a V15: controparte o causale; tutto ciò che non è una lettera diventa spazio. */
    static String oldKey(String counterparty, String description) {
        String text = counterparty != null && !counterparty.isBlank() ? counterparty : description;
        if (text == null) {
            return null;
        }
        String key = text.toUpperCase(Locale.ROOT)
                .replaceAll("[^\\p{L} ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (key.length() > MAX_LENGTH) {
            key = key.substring(0, MAX_LENGTH).trim();
        }
        return key.length() >= 2 ? key : null;
    }

    /** La regola da V16: controparte se non è un titolare, altrimenti causale; via le parole con cifre. */
    static String newKey(String counterparty, String description, List<Set<String>> owners) {
        String text;
        if (counterparty != null && !counterparty.isBlank() && !owners.contains(words(counterparty))) {
            text = counterparty;
        } else if (description != null && !description.isBlank()) {
            text = description;
        } else {
            return null;
        }
        String key = Arrays.stream(text.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" "))
                .filter(token -> token.chars().noneMatch(Character::isDigit))
                .collect(Collectors.joining(" "))
                .trim();
        if (key.length() > MAX_LENGTH) {
            key = key.substring(0, MAX_LENGTH).trim();
        }
        return key.length() >= 2 ? key : null;
    }

    private static Set<String> words(String text) {
        return Arrays.stream(text.toUpperCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toSet());
    }
}
