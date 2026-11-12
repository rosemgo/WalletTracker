package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test della migrazione V37: le ritenute diventano il tipo WITHHOLDING_TAX e la categoria "Ritenute" si elimina
 * (docs/investimenti.md, parte T). Un database vero alla V36, com'è oggi sul computer di chi usa l'app, con movimenti
 * già classificati; poi la V37. Dati inventati.
 */
class V37TipoRitenuteTest {

    /** Le regole delle ritenute e una tassa che non lo è: tipo e categoria (o "-"). */
    private static final String RULES = """
            SELECT r.pattern || ' ' || r.result_type || ' ' || COALESCE(c.name, '-')
            FROM classification_rule r LEFT JOIN category c ON c.id = r.category_id
            WHERE r.pattern LIKE 'Rit%' OR r.pattern = 'imposta di bollo Dossier' ORDER BY r.pattern
            """;

    /** I movimenti: chiave, tipo, tipo scelto a mano e categoria (o "-"). */
    private static final String MOVEMENTS = """
            SELECT t.dedup_key || ' ' || t.type || ' ' || COALESCE(t.manual_type, '-') || ' ' || COALESCE(c.name, '-')
                   || ' ' || COALESCE(mc.name, '-')
            FROM bank_transaction t LEFT JOIN category c ON c.id = t.category_id
            LEFT JOIN category mc ON mc.id = t.manual_category_id ORDER BY t.dedup_key
            """;

    @Test
    void onANewDatabaseTheStarterRulesGiveTheNewTypeAndTheCategoryIsGone() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, RULES)).containsExactly(
                        "Rit. Fisc WITHHOLDING_TAX -",
                        "Rit.ced WITHHOLDING_TAX -",
                        "Rit.div WITHHOLDING_TAX -",
                        "Ritenuta Fiscale WITHHOLDING_TAX -",
                        // il bollo non è una ritenuta: resta una tassa, con la sua categoria
                        "imposta di bollo Dossier INVESTMENT_TAX Imposta di bollo");
                assertThat(rows(connection, "SELECT name FROM category WHERE lower(name) LIKE 'ritenut%'")).isEmpty();
            }
        }
    }

    @Test
    void theMovementsTheManualChoicesAndTheUserRulesFollow() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "36").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) "
                        + "VALUES ('m-1', 'Deposito', 'EUR', 'INVESTMENT', 'MANUAL', 'Banca Alfa')");
                // Una regola dell'utente per le ritenute di un'altra banca.
                sql.execute("INSERT INTO classification_rule (name, match_mode, pattern, result_type, category_id) "
                        + "VALUES ('Ritenute Banca Beta', 'CONTAINS', 'WHT BETA', 'INVESTMENT_TAX', " + id("Ritenute")
                        + ")");
                // r-1: classificata da una regola di partenza; r-2: tipo e categoria scelti a mano; r-3: solo il tipo
                // scelto a mano, la categoria dalla regola; b-1: il bollo; e-1: una spesa con la categoria
                // "Ritenute" scelta per sbaglio.
                sql.execute(movement("r-1", "-15.63", "Rit.ced BTP ALFA", "INVESTMENT_TAX", "NULL", id("Ritenute"),
                        "NULL"));
                sql.execute(movement("r-2", "-3.90", "WHT BETA 0001", "INVESTMENT_TAX", "'INVESTMENT_TAX'",
                        id("Ritenute"), id("Ritenute")));
                sql.execute(movement("r-3", "-20.80", "Rit.div ACME SRL", "INVESTMENT_TAX", "'INVESTMENT_TAX'",
                        id("Ritenute"), "NULL"));
                sql.execute(movement("b-1", "-8.50", "Imposta di bollo Dossier", "INVESTMENT_TAX", "NULL",
                        id("Imposta di bollo"), "NULL"));
                sql.execute(movement("e-1", "-12.00", "LIBRERIA ROSSI", "EXPENSE", "NULL", id("Ritenute"),
                        id("Ritenute")));
            }

            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, MOVEMENTS)).containsExactly(
                        "b-1 INVESTMENT_TAX - Imposta di bollo -",
                        // la spesa resta una spesa: perde solo la categoria, che non c'è più
                        "e-1 EXPENSE - - -",
                        "r-1 WITHHOLDING_TAX - - -",
                        "r-2 WITHHOLDING_TAX WITHHOLDING_TAX - -",
                        "r-3 WITHHOLDING_TAX WITHHOLDING_TAX - -");
                assertThat(rows(connection, "SELECT result_type || ' ' || COALESCE(category_id::text, '-') "
                        + "FROM classification_rule WHERE name = 'Ritenute Banca Beta'"))
                        .containsExactly("WITHHOLDING_TAX -");
                assertThat(rows(connection, "SELECT name FROM category WHERE lower(name) = 'ritenute'")).isEmpty();
            }
        }
    }

    @Test
    void aRenamedCategoryIsFoundThroughTheStarterRules() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "36").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                // Rinominata: il nome "Ritenute" non c'è più, ma le regole "Rit.ced" la usano ancora.
                sql.execute("UPDATE category SET name = 'Ritenute fiscali' WHERE name = 'Ritenute'");
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) "
                        + "VALUES ('m-1', 'Deposito', 'EUR', 'INVESTMENT', 'MANUAL', 'Banca Alfa')");
                sql.execute(movement("r-1", "-15.63", "Rit.ced BTP ALFA", "INVESTMENT_TAX", "NULL",
                        id("Ritenute fiscali"), "NULL"));
            }

            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, MOVEMENTS)).containsExactly("r-1 WITHHOLDING_TAX - - -");
                assertThat(rows(connection, "SELECT name FROM category WHERE name = 'Ritenute fiscali'")).isEmpty();
            }
        }
    }

    @Test
    void aCategoryWithABudgetStaysButTheWithholdingsMoveToTheType() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "36").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO budget (category_id, currency, monthly_amount) VALUES (" + id("Ritenute")
                        + ", 'EUR', 50)");
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) "
                        + "VALUES ('m-1', 'Deposito', 'EUR', 'INVESTMENT', 'MANUAL', 'Banca Alfa')");
                sql.execute(movement("r-1", "-15.63", "Rit.ced BTP ALFA", "INVESTMENT_TAX", "NULL", id("Ritenute"),
                        "NULL"));
            }

            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                // Un budget non si perde senza che l'utente lo decida: la categoria resta, il budget anche.
                assertThat(rows(connection, "SELECT c.name FROM budget b JOIN category c ON c.id = b.category_id"))
                        .containsExactly("Ritenute");
                assertThat(rows(connection, MOVEMENTS)).containsExactly("r-1 WITHHOLDING_TAX - - -");
            }
        }
    }

    /** L'id di una categoria dal nome, come sottoquery. */
    private static String id(String category) {
        return "(SELECT id FROM category WHERE name = '" + category + "')";
    }

    private static String movement(String key, String amount, String description, String type, String manualType,
            String category, String manualCategory) {
        return """
                INSERT INTO bank_transaction (account_id, dedup_key, status, booking_date, amount, currency, description,
                                              raw_json, type, category_id, manual_type, manual_category_id)
                SELECT a.id, '%s', 'BOOKED', DATE '2026-10-01', %s, 'EUR', '%s', '{}', '%s', %s, %s, %s
                FROM account a
                """.formatted(key, amount, description, type, category, manualType, manualCategory);
    }

    private static Flyway flyway(PostgreSQLContainer postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(target)
                .load();
    }

    private static Connection connect(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> rows(Connection connection, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }
}
