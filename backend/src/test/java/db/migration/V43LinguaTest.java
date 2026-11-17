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
 * Test della migrazione V43 (docs/lingue-2-inglese.md): chi usa già l'app resta in italiano, un'installazione nuova
 * non ha ancora una lingua; le categorie di partenza ricevono la loro chiave, anche quelle già rinominate.
 */
class V43LinguaTest {

    @Test
    void anInstallationInUseStaysItalian() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "42").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO account (external_key, name, currency, role, source, bank_name) VALUES "
                        + "('m-1', 'Conto', 'EUR', 'MAIN', 'MANUAL', 'Banca Alfa')");
            }

            flyway(postgres, "43").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT language FROM app_settings")).containsExactly("it");
            }
        }
    }

    @Test
    void aNewInstallationHasNoLanguageYet() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "43").migrate();

            try (Connection connection = connect(postgres)) {
                // La riga c'è, la lingua no: la sceglierà la prima schermata.
                assertThat(rows(connection, "SELECT COALESCE(language, '-') FROM app_settings")).containsExactly("-");
            }
        }
    }

    @Test
    void theStarterCategoriesGetTheirKeyEvenIfRenamedAndTheOthersDoNot() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "42").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                sql.execute("UPDATE category SET name = 'Supermercato' WHERE name = 'Spesa alimentare'");
                sql.execute("INSERT INTO category (name, kind) VALUES ('Animali domestici', 'EXPENSE')");
            }

            flyway(postgres, "43").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT name FROM category WHERE builtin_key = 'GROCERIES'"))
                        .containsExactly("Supermercato");
                assertThat(rows(connection, "SELECT name FROM category WHERE builtin_key = 'CARD_SETTLEMENT'"))
                        .containsExactly("Saldo carta");
                assertThat(rows(connection, "SELECT COALESCE(builtin_key, '-') FROM category "
                        + "WHERE name = 'Animali domestici'")).containsExactly("-");
                // Tutte le categorie di partenza hanno una chiave, e ognuna la sua.
                assertThat(rows(connection, "SELECT count(*)::text FROM category WHERE builtin AND builtin_key IS NULL"))
                        .containsExactly("0");
                assertThat(rows(connection, "SELECT count(DISTINCT builtin_key)::text FROM category"))
                        .containsExactly("29");
            }
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
