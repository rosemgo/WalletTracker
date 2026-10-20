package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test della migrazione V16, che ricalcola le chiavi esercente salvate.
 *
 * <p>Come si prova una migrazione: un database vero (Testcontainers) portato alla V15 con Flyway, dati
 * scritti con le chiavi <b>vecchie</b>, poi la V16, e si controlla ogni riga. Niente Spring: solo Flyway e JDBC.
 */
class V16ChiaviEsercenteTest {

    @Test
    void theSavedKeysFollowTheNewRule() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "15").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("""
                        INSERT INTO bank_connection (aspsp_name, aspsp_country, session_id, valid_until)
                            VALUES ('Banca', 'IT', 's-1', now() + interval '90 days');
                        INSERT INTO account (connection_id, external_key, provider_uid, name, currency)
                            SELECT id, 'k-1', 'u-1', 'MARIO ROSSI', 'EUR' FROM bank_connection;
                        INSERT INTO bank_transaction (account_id, dedup_key, status, booking_date, amount, currency,
                                                      counterparty, description, raw_json)
                        SELECT a.id, t.key, 'BOOKED', DATE '2026-10-01', -10, 'EUR', t.counterparty, t.description, '{}'
                        FROM account a, (VALUES
                            ('t1', NULL,          'AMAZON* BT9YM4924 LUXEMBOURG LUX'),
                            ('t2', NULL,          'AMAZON* BT2BJ4CA4 LUXEMBOURG LUX'),
                            ('t3', 'MARIO ROSSI', 'amazon.it'),
                            ('t4', 'MARIO ROSSI', 'ESSELUNGA MILANO'),
                            ('t5', 'NETFLIX.COM', NULL),
                            ('t6', NULL,          'NETFLIX.COM XY12'),
                            ('t7', 'FINDOMESTIC BANCA', NULL)
                        ) AS t (key, counterparty, description);
                        """);
                sql.execute("""
                        INSERT INTO subscription (merchant_key, currency, status, name, frequency, expected_amount) VALUES
                            ('AMAZON BT YM LUXEMBOURG LUX',    'EUR', 'CONFIRMED', 'Amazon Prime', 'MONTHLY', 4.99),
                            ('AMAZON BT BJ CA LUXEMBOURG LUX', 'EUR', 'DISMISSED', NULL, NULL, NULL),
                            ('NETFLIX COM',                    'EUR', 'DISMISSED', NULL, NULL, NULL),
                            ('NETFLIX COM XY',                 'EUR', 'CONFIRMED', 'Netflix', 'MONTHLY', 12.99),
                            ('MARIO ROSSI',                    'EUR', 'CONFIRMED', 'Boh', 'MONTHLY', 1.00),
                            ('FINDOMESTIC BANCA',              'EUR', 'CONFIRMED', 'Findomestic', 'MONTHLY', 100.00);
                        INSERT INTO category_suggestion_dismissal (merchant_key, category_id)
                        SELECT k.key, c.id FROM (VALUES
                            ('AMAZON BT YM LUXEMBOURG LUX', 'Shopping'),
                            ('AMAZON BT BJ CA LUXEMBOURG LUX', 'Shopping'),
                            ('AMAZON BT BJ CA LUXEMBOURG LUX', 'Spesa alimentare')
                        ) AS k (key, category) JOIN category c ON c.name = k.category;
                        """);
            }

            flyway(postgres, "16").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT merchant_key || ' ' || status || ' ' || coalesce(name, '-') "
                        + "FROM subscription ORDER BY merchant_key")).containsExactly(
                        // i due codici Amazon diventano la stessa chiave: resta la conferma, sparisce il "no"
                        "AMAZON LUXEMBOURG LUX CONFIRMED Amazon Prime",
                        // invariata: era già la chiave giusta
                        "FINDOMESTIC BANCA CONFIRMED Findomestic",
                        // il titolare copriva negozi diversi: non si sa quale, la riga resta com'è
                        "MARIO ROSSI CONFIRMED Boh",
                        // la conferma vince anche contro un "no" che aveva già la chiave nuova
                        "NETFLIX COM CONFIRMED Netflix");
                assertThat(rows(connection, "SELECT d.merchant_key || ' ' || c.name FROM category_suggestion_dismissal d "
                        + "JOIN category c ON c.id = d.category_id ORDER BY 1")).containsExactly(
                        "AMAZON LUXEMBOURG LUX Shopping",              // i due "no" uguali sono diventati uno
                        "AMAZON LUXEMBOURG LUX Spesa alimentare");
            }
        }
    }

    @Test
    void theTwoFrozenRulesGiveTheExpectedKeys() {
        List<Set<String>> owner = List.of(Set.of("MARIO", "ROSSI"));
        assertThat(V16__chiavi_esercente.oldKey(null, "AMAZON* BT9YM4924 LUXEMBOURG LUX"))
                .isEqualTo("AMAZON BT YM LUXEMBOURG LUX");
        assertThat(V16__chiavi_esercente.newKey(null, "AMAZON* BT9YM4924 LUXEMBOURG LUX", owner))
                .isEqualTo("AMAZON LUXEMBOURG LUX");
        assertThat(V16__chiavi_esercente.oldKey("MARIO ROSSI", "amazon.it")).isEqualTo("MARIO ROSSI");
        assertThat(V16__chiavi_esercente.newKey("MARIO ROSSI", "amazon.it", owner)).isEqualTo("AMAZON IT");
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
