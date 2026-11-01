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
 * Test della migrazione V33: il cashback di Tinaba passa da spesa positiva in "Costi bancari" a entrata in "Rimborsi
 * fiscali e bonus" (docs/tinaba.md, parte J). Un database vero alla V32, movimenti già classificati, poi la V33.
 * Dati inventati.
 */
class V33CashbackTinabaEntrataTest {

    private static final String RULE = "(SELECT id FROM classification_rule WHERE name = 'Cashback delle commissioni (Tinaba)')";
    private static final String BANK_COSTS = "(SELECT id FROM category WHERE name = 'Costi bancari')";

    @Test
    void theCashbackBecomesAnIncomeAndTheMovementsFollowButNotTheManualChoices() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "32").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) "
                        + "VALUES ('m-1', 'Tinaba', 'EUR', 'INVESTMENT', 'MANUAL', 'Wallet')");
                // Tre cashback classificati dalla regola di V31: uno com'è, uno con il tipo scelto a mano, uno con la
                // categoria scelta a mano. E una ricarica Power, che deve restare com'è.
                sql.execute(movement("c-1", "46.74", "Tinaba Cashback", RULE, "NULL", "NULL"));
                sql.execute(movement("c-2", "38.08", "Tinaba Cashback", RULE, "'EXPENSE'", "NULL"));
                sql.execute(movement("c-3", "38.16", "Tinaba Cashback", RULE, "NULL",
                        "(SELECT id FROM category WHERE name = 'Altre entrate')"));
                sql.execute(movement("p-1", "-50.00", "Ricarica effettuata Tinaba Power",
                        "(SELECT id FROM classification_rule WHERE name = 'Credito Power (Tinaba)')", "NULL", "NULL"));
            }

            flyway(postgres, "33").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, """
                        SELECT r.result_type || ' ' || c.name FROM classification_rule r JOIN category c ON c.id = r.category_id
                        WHERE r.name IN ('Cashback delle commissioni (Tinaba)', 'Credito Power (Tinaba)') ORDER BY r.name
                        """)).containsExactly("INCOME Rimborsi fiscali e bonus", "EXPENSE Costi bancari");
                assertThat(rows(connection, """
                        SELECT t.dedup_key || ' ' || t.type || ' ' || c.name
                        FROM bank_transaction t JOIN category c ON c.id = t.category_id ORDER BY t.dedup_key
                        """)).containsExactly(
                        "c-1 INCOME Rimborsi fiscali e bonus",
                        // il tipo scelto a mano vince: il movimento resta com'era
                        "c-2 EXPENSE Costi bancari",
                        // la categoria scelta a mano resta; il tipo segue la regola
                        "c-3 INCOME Altre entrate",
                        "p-1 EXPENSE Costi bancari");
            }
        }
    }

    @Test
    void aRuleChangedByTheUserStaysAsItIs() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "32").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE classification_rule SET category_id = (SELECT id FROM category WHERE name = "
                        + "'Altre spese') WHERE id = " + RULE);
            }

            flyway(postgres, "33").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT r.result_type || ' ' || c.name FROM classification_rule r "
                        + "JOIN category c ON c.id = r.category_id WHERE r.id = " + RULE))
                        .containsExactly("EXPENSE Altre spese");
            }
        }
    }

    /** Un movimento del conto Tinaba, già classificato come lo lasciava la regola (spesa in "Costi bancari"). */
    private static String movement(String key, String amount, String description, String rule, String manualType,
            String manualCategory) {
        return """
                INSERT INTO bank_transaction (account_id, dedup_key, status, booking_date, amount, currency, description,
                                              raw_json, type, category_id, rule_id, manual_type, manual_category_id)
                SELECT a.id, '%s', 'BOOKED', DATE '2026-10-13', %s, 'EUR', '%s', '{}', COALESCE(%s, 'EXPENSE'),
                       COALESCE(%s, %s), %s, %s, %s
                FROM account a
                """.formatted(key, amount, description, manualType, manualCategory, BANK_COSTS, rule, manualType,
                manualCategory);
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
