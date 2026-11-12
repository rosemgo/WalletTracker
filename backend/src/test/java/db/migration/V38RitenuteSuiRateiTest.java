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
 * Test della migrazione V38: le ritenute sul rateo ("Ritenuta Fiscale … Qta/Val.nom.") diventano "Altre tasse
 * investimenti" con la categoria "Ritenute sui ratei" (docs/investimenti.md, parte U). Un database vero alla V37, com'è
 * oggi con la 1.2.6, poi la V38. Testi come quelli di Fineco, importi inventati.
 */
class V38RitenuteSuiRateiTest {

    private static final String OLD_RULE =
            "(SELECT id FROM classification_rule WHERE builtin AND pattern = 'Ritenuta Fiscale')";

    /** I movimenti: chiave, tipo, categoria (o "-"), e se la regola è quella nuova. */
    private static final String MOVEMENTS = """
            SELECT t.dedup_key || ' ' || t.type || ' ' || COALESCE(c.name, '-') || ' '
                   || CASE WHEN r.pattern = 'Ritenuta Fiscale Qta/Val.nom' THEN 'nuova' ELSE COALESCE(r.pattern, '-') END
            FROM bank_transaction t LEFT JOIN category c ON c.id = t.category_id
            LEFT JOIN classification_rule r ON r.id = t.rule_id ORDER BY t.dedup_key
            """;

    @Test
    void onANewDatabaseTheNewRuleComesFirstAndTheOldOneStays() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "38").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, """
                        SELECT r.priority || ' ' || r.match_mode || ' ' || r.pattern || ' ' || r.result_type || ' '
                               || COALESCE(c.name, '-')
                        FROM classification_rule r LEFT JOIN category c ON c.id = r.category_id
                        WHERE r.pattern LIKE 'Ritenuta Fiscale%' ORDER BY r.priority
                        """)).containsExactly(
                        "9 ALL_WORDS Ritenuta Fiscale Qta/Val.nom INVESTMENT_TAX Ritenute sui ratei",
                        // la regola di V4 resta, per gli altri testi con "Ritenuta Fiscale"
                        "10 CONTAINS Ritenuta Fiscale WITHHOLDING_TAX -");
                assertThat(rows(connection,
                        "SELECT kind || ' ' || builtin FROM category WHERE name = 'Ritenute sui ratei'"))
                        .containsExactly("INVESTMENT true");
            }
        }
    }

    @Test
    void theMovementsOfTheOldRuleMoveButNotTheManualChoicesNorTheOtherTexts() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "37").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) "
                        + "VALUES ('m-1', 'Deposito', 'EUR', 'INVESTMENT', 'MANUAL', 'Banca Alfa')");
                // a-1: rateo di un acquisto (positivo); v-1: di una vendita (negativo); m-1: lo stesso testo con il tipo
                // scelto a mano; c-1: con una categoria scelta a mano; o-1: "Ritenuta Fiscale" senza quantità;
                // r-1: una ritenuta su una cedola, che non c'entra.
                sql.execute(movement("a-1", "16.47", "Ritenuta Fiscale POLAND-18MZ54 5,5% Qta/Val.nom. 24000,000000",
                        OLD_RULE, "NULL", "NULL"));
                sql.execute(movement("v-1", "-46.25", "Ritenuta Fiscale FRANCE-25MG57 4,4% Qta/Val.nom. 20000,000000",
                        OLD_RULE, "NULL", "NULL"));
                sql.execute(movement("m-1", "-24.36", "Ritenuta Fiscale BTP-1OT53 4,5% Qta/Val.nom. 60000,000000",
                        OLD_RULE, "'WITHHOLDING_TAX'", "NULL"));
                sql.execute(movement("c-1", "-10.00", "Ritenuta Fiscale BTP-1OT53 4,5% Qta/Val.nom. 10000,000000",
                        OLD_RULE, "NULL", "(SELECT id FROM category WHERE name = 'Imposta di bollo')"));
                sql.execute(movement("o-1", "-3.00", "Ritenuta Fiscale su fondo comune", OLD_RULE, "NULL", "NULL"));
                sql.execute(movement("r-1", "-65.00", "Rit.ced.su 16.000,000 ROMANIA-7OT45 6,5%",
                        "(SELECT id FROM classification_rule WHERE builtin AND pattern = 'Rit.ced')", "NULL", "NULL"));
            }

            flyway(postgres, "38").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, MOVEMENTS)).containsExactly(
                        "a-1 INVESTMENT_TAX Ritenute sui ratei nuova",
                        // la categoria scelta a mano resta
                        "c-1 INVESTMENT_TAX Imposta di bollo nuova",
                        // il tipo scelto a mano vince: resta com'era
                        "m-1 WITHHOLDING_TAX - Ritenuta Fiscale",
                        // senza "Qta/Val.nom" non è un rateo: resta una ritenuta sulle rendite
                        "o-1 WITHHOLDING_TAX - Ritenuta Fiscale",
                        "r-1 WITHHOLDING_TAX - Rit.ced",
                        "v-1 INVESTMENT_TAX Ritenute sui ratei nuova");
            }
        }
    }

    @Test
    void aCategoryWithTheSameNameIsReused() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "37").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO category (name, kind) VALUES ('RITENUTE SUI RATEI', 'INVESTMENT')");
            }

            flyway(postgres, "38").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT name FROM category WHERE lower(name) = 'ritenute sui ratei'"))
                        .containsExactly("RITENUTE SUI RATEI");
                assertThat(rows(connection, "SELECT c.name FROM classification_rule r JOIN category c "
                        + "ON c.id = r.category_id WHERE r.pattern = 'Ritenuta Fiscale Qta/Val.nom'"))
                        .containsExactly("RITENUTE SUI RATEI");
            }
        }
    }

    /** Un movimento di ritenuta, classificato come lo lasciava la V37 (ritenuta sulle rendite, senza categoria). */
    private static String movement(String key, String amount, String description, String rule, String manualType,
            String manualCategory) {
        return """
                INSERT INTO bank_transaction (account_id, dedup_key, status, booking_date, amount, currency, description,
                                              raw_json, type, category_id, rule_id, manual_type, manual_category_id)
                SELECT a.id, '%s', 'BOOKED', DATE '2026-10-26', %s, 'EUR', '%s', '{}', 'WITHHOLDING_TAX', %s, %s, %s, %s
                FROM account a
                """.formatted(key, amount, description, manualCategory, rule, manualType, manualCategory);
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
