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
 * Test della migrazione V39: le "Ritenuta Fiscale … Qta/Val.nom." diventano trasferimenti tra i tuoi conti (giro
 * titoli) e la categoria "Ritenute sui ratei" della V38 si elimina (docs/investimenti.md, parte W). Un database vero
 * alla V38, com'è sul computer di chi ha provato la V38, poi la V39. Testi come quelli di Fineco, importi inventati.
 */
class V39GiroTitoliTest {

    private static final String RULE =
            "(SELECT id FROM classification_rule WHERE builtin AND pattern = 'Ritenuta Fiscale Qta/Val.nom')";

    private static final String MOVEMENTS = """
            SELECT t.dedup_key || ' ' || t.type || ' ' || COALESCE(c.name, '-')
            FROM bank_transaction t LEFT JOIN category c ON c.id = t.category_id ORDER BY t.dedup_key
            """;

    @Test
    void theTransfersOfSecuritiesBecomeTransfersAndTheCategoryGoes() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "38").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) VALUES "
                        + "('m-1', 'Cede', 'EUR', 'INVESTMENT', 'MANUAL', 'Banca Alfa'), "
                        + "('m-2', 'Riceve', 'EUR', 'INVESTMENT', 'MANUAL', 'Banca Alfa')");
                // o-1 e i-1: i due lati di un giro titoli; s-1: un lato solo (l'altro è prima della storia nell'app);
                // m-1: con il tipo scelto a mano; c-1: con una categoria scelta a mano; b-1: il bollo, che non c'entra.
                sql.execute(movement("m-1", "o-1", "-24.36", "Ritenuta Fiscale BTP-1OT53 4,5% Qta/Val.nom. 60000,000000",
                        RULE, "NULL", "NULL"));
                sql.execute(movement("m-2", "i-1", "24.36", "Ritenuta Fiscale BTP-1OT53 4,5% Qta/Val.nom. 60000,000000",
                        RULE, "NULL", "NULL"));
                sql.execute(movement("m-1", "s-1", "-151.79", "Ritenuta Fiscale BTP-1OT53 4,5% Qta/Val.nom. 55000,000000",
                        RULE, "NULL", "NULL"));
                sql.execute(movement("m-1", "m-1", "-4.79", "Ritenuta Fiscale POLAND-18MZ54 5,5% Qta/Val.nom. 24000,000000",
                        RULE, "'INVESTMENT_TAX'", "NULL"));
                sql.execute(movement("m-1", "c-1", "-0.63", "Ritenuta Fiscale HUNGARY-22MZ40 4,875 Qta/Val.nom. 10000,000000",
                        RULE, "NULL", "(SELECT id FROM category WHERE name = 'Imposta di bollo')"));
                sql.execute(movement("m-1", "b-1", "-22.10", "Addebito imposta di bollo Dossier: 0000000",
                        "(SELECT id FROM classification_rule WHERE builtin AND pattern = 'imposta di bollo Dossier')",
                        "NULL", "(SELECT id FROM category WHERE name = 'Imposta di bollo')"));
            }

            flyway(postgres, "39").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, MOVEMENTS)).containsExactly(
                        "b-1 INVESTMENT_TAX Imposta di bollo",
                        // la categoria scelta a mano resta, ma è un trasferimento
                        "c-1 INTERNAL_TRANSFER Imposta di bollo",
                        "i-1 INTERNAL_TRANSFER -",
                        // il tipo scelto a mano vince
                        "m-1 INVESTMENT_TAX Ritenute sui ratei",
                        "o-1 INTERNAL_TRANSFER -",
                        "s-1 INTERNAL_TRANSFER -");
                assertThat(rows(connection, "SELECT name || ' ' || result_type || ' ' || COALESCE(category_id::text, '-') "
                        + "FROM classification_rule WHERE pattern = 'Ritenuta Fiscale Qta/Val.nom'"))
                        .containsExactly("Ritenuta sul rateo nel giro titoli tra i tuoi conti INTERNAL_TRANSFER -");
                // m-1 usa ancora la categoria (la scelta a mano ha tenuto il tipo, e con lui la categoria): resta
                assertThat(rows(connection, "SELECT name FROM category WHERE name = 'Ritenute sui ratei'"))
                        .containsExactly("Ritenute sui ratei");
            }
        }
    }

    @Test
    void anUnusedCategoryIsDeletedButNotOneWithABudgetNorOneOfTheUser() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "38").migrate();

            flyway(postgres, "39").migrate();

            try (Connection connection = connect(postgres)) {
                // Nessun movimento la usa e la regola non più: la categoria della V38 se ne va.
                assertThat(rows(connection, "SELECT name FROM category WHERE lower(name) = 'ritenute sui ratei'"))
                        .isEmpty();
            }
        }
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "38").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO budget (category_id, currency, monthly_amount) VALUES ("
                        + "(SELECT id FROM category WHERE name = 'Ritenute sui ratei'), 'EUR', 10)");
            }

            flyway(postgres, "39").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT name FROM category WHERE name = 'Ritenute sui ratei'"))
                        .containsExactly("Ritenute sui ratei"); // con un budget resta
            }
        }
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "37").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                // l'utente l'aveva già creata: la V38 l'ha riusata, e la V39 non la tocca
                sql.execute("INSERT INTO category (name, kind) VALUES ('Ritenute sui ratei', 'INVESTMENT')");
            }

            flyway(postgres, "39").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT name FROM category WHERE name = 'Ritenute sui ratei'"))
                        .containsExactly("Ritenute sui ratei");
            }
        }
    }

    @Test
    void aRuleChangedByTheUserStaysAsItIs() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "38").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE classification_rule SET result_type = 'EXPENSE' WHERE id = " + RULE);
            }

            flyway(postgres, "39").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT result_type FROM classification_rule WHERE id = " + RULE))
                        .containsExactly("EXPENSE");
            }
        }
    }

    /** Un movimento classificato come lo lasciava la V38 (altre tasse, categoria della regola o scelta a mano). */
    private static String movement(String account, String key, String amount, String description, String rule,
            String manualType, String manualCategory) {
        return """
                INSERT INTO bank_transaction (account_id, dedup_key, status, booking_date, amount, currency, description,
                                              raw_json, type, category_id, rule_id, manual_type, manual_category_id)
                SELECT a.id, '%s', 'BOOKED', DATE '2026-10-23', %s, 'EUR', '%s', '{}', 'INVESTMENT_TAX',
                       COALESCE(%s, (SELECT category_id FROM classification_rule WHERE id = %s)), %s, %s, %s
                FROM account a WHERE a.external_key = '%s'
                """.formatted(key, amount, description, manualCategory, rule, rule, manualType, manualCategory,
                account);
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
