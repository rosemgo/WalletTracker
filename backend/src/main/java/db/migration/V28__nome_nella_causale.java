package db.migration;

import java.math.BigDecimal;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V28: ricalcola le chiavi esercente salvate, dopo che {@code MerchantKey} ha imparato a leggere il nome scritto
 * nella causale dopo un'etichetta ("Anagrafica Ordinante ACME SRL", "Creditor id. ... ACME SRL Id Mandato"),
 * docs/40.
 *
 * <p>Lo stesso procedimento di V16 (vedi lì la spiegazione), su tre tabelle: {@code subscription},
 * {@code category_suggestion_dismissal} e {@code ai_category_answer} (V20, arrivata dopo V16). In breve:
 * <ol>
 *   <li>per ogni movimento, la chiave vecchia e quella nuova: in quali chiavi nuove si trasforma ogni vecchia;</li>
 *   <li>una riga la cui chiave vecchia diventa <b>una sola</b> chiave nuova viene aggiornata. Il caso tipico di
 *       questa migrazione è il contrario: "BONIFICO N BIC ORDINANTE ..." copriva bonifici di mittenti diversi, e
 *       diventa tante chiavi. Non si può sapere quale: la riga resta com'è e non corrisponde più a niente. È giusto
 *       così: un "no" o una risposta dell'IA su quel miscuglio non valevano per nessuno dei mittenti;</li>
 *   <li>se due righe finiscono sulla stessa chiave, ne resta una (per gli abbonamenti vince la conferma).</li>
 * </ol>
 *
 * <p>Le due regole sono <b>copiate qui</b>, non prese da {@code MerchantKey}: una migrazione deve fare sempre la
 * stessa cosa, anche se un giorno {@code MerchantKey} cambierà di nuovo.
 */
public class V28__nome_nella_causale extends BaseJavaMigration {

    private static final int MAX_LENGTH = 60;

    private static final Pattern NAME_IN_DESCRIPTION = Pattern.compile(
            "\\b(?:Anagrafica\\s+(?:Ordinante|Beneficiario)|Creditor\\s+id\\.?\\s+\\S+)\\s+"
                    + "(?!Note\\s*:|Id\\s+Mandato\\b)(.+?)"
                    + "(?=\\s+Note\\s*:|\\s+Id\\s+Mandato\\b|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        List<Set<String>> owners = ownerNames(connection);
        Map<String, Set<String>> renames = renames(connection, owners);
        rekeySubscriptions(connection, renames);
        rekeyDismissals(connection, renames);
        rekeyAnswers(connection, renames);
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

    /** Per ogni chiave vecchia, le chiavi nuove in cui si trasforma. */
    private static Map<String, Set<String>> renames(Connection connection, List<Set<String>> owners)
            throws SQLException {
        Map<String, Set<String>> renames = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT counterparty, description FROM bank_transaction")) {
            while (rows.next()) {
                String counterparty = rows.getString("counterparty");
                String description = rows.getString("description");
                String oldKey = oldKey(counterparty, description, owners);
                String newKey = newKey(counterparty, description, owners);
                if (oldKey != null && newKey != null) {
                    renames.computeIfAbsent(oldKey, key -> new HashSet<>()).add(newKey);
                }
            }
        }
        return renames;
    }

    /** Un abbonamento è unico per esercente, valuta e importo (V21). */
    private static void rekeySubscriptions(Connection connection, Map<String, Set<String>> renames)
            throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, merchant_key, currency, expected_amount, status FROM subscription ORDER BY id")) {
            while (result.next()) {
                rows.add(new Object[] {result.getLong("id"), result.getString("merchant_key"),
                        result.getString("currency"), result.getBigDecimal("expected_amount"),
                        result.getString("status")});
            }
        }
        for (Object[] row : rows) {
            long id = (Long) row[0];
            String target = target(renames, (String) row[1]);
            if (target == null) {
                continue;
            }
            Object[] existing = findSubscription(connection, target, (String) row[2], (BigDecimal) row[3]);
            if (existing == null) {
                update(connection, "subscription", id, target);
            } else if ("CONFIRMED".equals(row[4]) && !"CONFIRMED".equals(existing[1])) {
                delete(connection, "subscription", (Long) existing[0]); // vince la conferma
                update(connection, "subscription", id, target);
            } else {
                delete(connection, "subscription", id); // la decisione su quella chiave c'è già
            }
        }
    }

    private static void rekeyDismissals(Connection connection, Map<String, Set<String>> renames)
            throws SQLException {
        for (Object[] row : keyRows(connection, "category_suggestion_dismissal", "category_id")) {
            String target = target(renames, (String) row[1]);
            if (target == null) {
                continue;
            }
            if (exists(connection, "SELECT 1 FROM category_suggestion_dismissal WHERE merchant_key = ? "
                    + "AND category_id = ?", target, row[2])) {
                delete(connection, "category_suggestion_dismissal", (Long) row[0]); // lo stesso "no", già salvato
            } else {
                update(connection, "category_suggestion_dismissal", (Long) row[0], target);
            }
        }
    }

    /** Una risposta dell'IA per esercente (V20): se la chiave nuova ne ha già una, resta quella. */
    private static void rekeyAnswers(Connection connection, Map<String, Set<String>> renames) throws SQLException {
        for (Object[] row : keyRows(connection, "ai_category_answer", "id")) {
            String target = target(renames, (String) row[1]);
            if (target == null) {
                continue;
            }
            if (exists(connection, "SELECT 1 FROM ai_category_answer WHERE merchant_key = ? AND id <> ?",
                    target, row[0])) {
                delete(connection, "ai_category_answer", (Long) row[0]);
            } else {
                update(connection, "ai_category_answer", (Long) row[0], target);
            }
        }
    }

    // Il nome della tabella e della colonna vengono da questa classe, mai dall'esterno: concatenarli è sicuro.
    private static List<Object[]> keyRows(Connection connection, String table, String other) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, merchant_key, " + other + " FROM " + table + " ORDER BY id")) {
            while (result.next()) {
                rows.add(new Object[] {result.getLong(1), result.getString(2), result.getLong(3)});
            }
        }
        return rows;
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

    /** L'abbonamento con questa chiave, valuta e importo ({@code IS NOT DISTINCT FROM}: anche null = null). */
    private static Object[] findSubscription(Connection connection, String key, String currency, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, status FROM subscription "
                + "WHERE merchant_key = ? AND currency = ? AND expected_amount IS NOT DISTINCT FROM ?")) {
            statement.setString(1, key);
            statement.setString(2, currency);
            statement.setBigDecimal(3, amount);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Object[] {result.getLong(1), result.getString(2)} : null;
            }
        }
    }

    private static boolean exists(Connection connection, String sql, String key, Object other) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setObject(2, other);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

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

    /** La regola da V16 a V27: controparte se non è un titolare, altrimenti la causale intera. */
    static String oldKey(String counterparty, String description, List<Set<String>> owners) {
        if (counterparty != null && !counterparty.isBlank() && !owners.contains(words(counterparty))) {
            return keyOf(counterparty);
        }
        return description != null && !description.isBlank() ? keyOf(description) : null;
    }

    /** La regola da V28: come prima, ma della causale solo il nome dopo un'etichetta, se c'è. */
    static String newKey(String counterparty, String description, List<Set<String>> owners) {
        if (counterparty != null && !counterparty.isBlank() && !owners.contains(words(counterparty))) {
            return keyOf(counterparty);
        }
        if (description == null || description.isBlank()) {
            return null;
        }
        Matcher matcher = NAME_IN_DESCRIPTION.matcher(description);
        if (matcher.find()) {
            String name = matcher.group(1).strip();
            if (name.chars().anyMatch(Character::isLetter)) {
                return keyOf(name);
            }
        }
        return keyOf(description);
    }

    /** Maiuscole, solo lettere e cifre, via le parole con cifre, al massimo 60 caratteri. */
    private static String keyOf(String text) {
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
