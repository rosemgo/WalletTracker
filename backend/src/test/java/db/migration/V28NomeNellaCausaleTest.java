package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * Test della migrazione V28, che ricalcola le chiavi esercente salvate quando il nome è scritto nella causale.
 * Come per V16: un database vero alla V27, righe salvate con le chiavi vecchie, poi la V28. Dati inventati.
 */
class V28NomeNellaCausaleTest {

    private static final List<Set<String>> OWNER = List.of(Set.of("MARIO", "ROSSI"));

    /** Due bonifici dalla stessa banca, di mittenti diversi: la stessa chiave vecchia. */
    private static final String REFUND = "Bonifico N. 00000000000000000000000001IT BIC Ordinante ALFAITMMXXX "
            + "Codifica Ordinante IT00X0000000000000000000001 Anagrafica Ordinante ACME SRL Note: Rimborso";
    private static final String SALARY = "Bonifico N. 00000000000000000000000002IT BIC Ordinante ALFAITMMXXX "
            + "Codifica Ordinante IT00X0000000000000000000002 Anagrafica Ordinante BETA SPA Note: Stipendio";
    /** Un addebito diretto: la chiave vecchia diventa una sola chiave nuova. */
    private static final String DEBIT = "Addebito SDD CORE Scad. 01/10/2026 Imp. 45,00 Creditor id. IT00ZZZ0000000000 "
            + "GAMMA LUCE E GAS S.P.A. Id Mandato 0000000001 Debitore MARIO ROSSI";

    @Test
    void theSavedKeysFollowTheNameInTheDescription() throws SQLException {
        String bonifico = V28__nome_nella_causale.oldKey(null, REFUND, OWNER);
        String sdd = V28__nome_nella_causale.oldKey(null, DEBIT, OWNER);
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "27").migrate();
            try (Connection connection = connect(postgres)) {
                try (Statement sql = connection.createStatement()) {
                    sql.execute("""
                            INSERT INTO bank_connection (aspsp_name, aspsp_country, session_id, valid_until)
                                VALUES ('Banca Alfa', 'IT', 's-1', now() + interval '90 days');
                            INSERT INTO account (connection_id, external_key, provider_uid, name, currency)
                                SELECT id, 'k-1', 'u-1', 'MARIO ROSSI', 'EUR' FROM bank_connection;
                            """);
                }
                for (String[] t : List.of(new String[] {"t1", null, REFUND}, new String[] {"t2", null, SALARY},
                        new String[] {"t3", "MARIO ROSSI", DEBIT}, new String[] {"t4", "NETFLIX.COM", null},
                new String[] {"t5", null, DEBIT.replace("GAMMA", "DELTA")})) {
                    execute(connection, """
                            INSERT INTO bank_transaction (account_id, dedup_key, status, booking_date, amount, currency,
                                                          counterparty, description, raw_json)
                            SELECT id, ?, 'BOOKED', DATE '2026-10-01', -10, 'EUR', ?, ?, '{}' FROM account
                            """, t);
                }
                execute(connection, """
                        INSERT INTO subscription (merchant_key, currency, status, name, frequency, expected_amount)
                        VALUES (?, 'EUR', 'CONFIRMED', 'Luce e gas', 'MONTHLY', 45.00),
                               ('GAMMA LUCE E GAS S P A', 'EUR', 'DISMISSED', NULL, NULL, 45.00),
                               ('NETFLIX COM', 'EUR', 'CONFIRMED', 'Netflix', 'MONTHLY', 12.99)
                        """, sdd);
                execute(connection, """
                        INSERT INTO category_suggestion_dismissal (merchant_key, category_id)
                        SELECT ?, id FROM category WHERE name = 'Shopping'
                        """, bonifico);
                execute(connection, """
                        INSERT INTO ai_category_answer (merchant_key, category_id, model)
                        VALUES (?, NULL, 'vecchia-bonifici'), (?, NULL, 'vecchia-sdd'), ('NETFLIX COM', NULL, 'netflix'),
                               (?, NULL, 'vecchia-sdd-due')
                        """, bonifico, sdd, sdd.replace("GAMMA", "DELTA"));
                execute(connection, """
                        INSERT INTO ai_category_answer (merchant_key, category_id, model)
                        VALUES ('DELTA LUCE E GAS S P A', NULL, 'nuova')
                        """);
            }

            flyway(postgres, "28").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT merchant_key || ' ' || status || ' ' || coalesce(name, '-') "
                        + "FROM subscription ORDER BY merchant_key")).containsExactly(
                        // una chiave vecchia, una nuova: la conferma vince anche sul "no" che c'era già
                        "GAMMA LUCE E GAS S P A CONFIRMED Luce e gas",
                        "NETFLIX COM CONFIRMED Netflix");
                // La chiave dei bonifici copriva due mittenti: non si sa quale, la riga resta com'è.
                assertThat(rows(connection, "SELECT merchant_key FROM category_suggestion_dismissal"))
                        .containsExactly(bonifico);
                assertThat(rows(connection, "SELECT merchant_key || ' ' || model FROM ai_category_answer ORDER BY id"))
                        .containsExactly(bonifico + " vecchia-bonifici", "GAMMA LUCE E GAS S P A vecchia-sdd",
                                "NETFLIX COM netflix",
                                // la chiave nuova aveva già una risposta: resta quella, la vecchia sparisce
                                "DELTA LUCE E GAS S P A nuova");
            }
        }
    }

    @Test
    void theTwoFrozenRulesGiveTheExpectedKeys() {
        assertThat(V28__nome_nella_causale.oldKey(null, REFUND, OWNER))
                .isEqualTo(V28__nome_nella_causale.oldKey(null, SALARY, OWNER))
                .hasSize(60)
                .startsWith("BONIFICO N BIC ORDINANTE ALFAITMMXXX CODIFICA ORDINANTE");
        assertThat(V28__nome_nella_causale.newKey(null, REFUND, OWNER)).isEqualTo("ACME SRL");
        assertThat(V28__nome_nella_causale.newKey(null, SALARY, OWNER)).isEqualTo("BETA SPA");
        assertThat(V28__nome_nella_causale.newKey("MARIO ROSSI", DEBIT, OWNER)).isEqualTo("GAMMA LUCE E GAS S P A");
        assertThat(V28__nome_nella_causale.newKey("NETFLIX.COM", null, OWNER)).isEqualTo("NETFLIX COM");
        assertThat(V28__nome_nella_causale.newKey(null, "ESSELUNGA MILANO", OWNER)).isEqualTo("ESSELUNGA MILANO");
    }

    private static void execute(Connection connection, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
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
