package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Test della migrazione V37: la categoria delle ritenute riceve il ruolo WITHHOLDING (docs/investimenti.md, parte T).
 * Un database vero alla V36, com'è oggi sul computer di chi usa l'app, poi la V37. Dati inventati.
 */
class V37RuoloDelleCategorieTest {

    private static final String WITH_ROLE = "SELECT name FROM category WHERE role IS NOT NULL";

    @Test
    void theStarterCategoryGetsTheRole() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, "SELECT name || ' ' || role FROM category WHERE role IS NOT NULL"))
                        .containsExactly("Ritenute WITHHOLDING");
            }
        }
    }

    @Test
    void aRenamedCategoryIsFoundThroughTheStarterRules() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "36").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                // L'utente l'aveva rinominata: il nome "Ritenute" non c'è più, ma le regole "Rit.ced" la usano ancora.
                sql.execute("UPDATE category SET name = 'Ritenute fiscali' WHERE name = 'Ritenute'");
            }

            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, WITH_ROLE)).containsExactly("Ritenute fiscali");
            }
        }
    }

    @Test
    void theNameWinsOverTheRulesAndCaseDoesNotMatter() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "36").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                // Le regole di partenza spostate su un'altra categoria: fino alla V36 il codice guardava il nome, e
                // la V37 tiene la stessa scelta.
                sql.execute("INSERT INTO category (name, kind) VALUES ('Tasse sulle cedole', 'INVESTMENT')");
                sql.execute("UPDATE classification_rule SET category_id = (SELECT id FROM category WHERE name = "
                        + "'Tasse sulle cedole') WHERE builtin AND pattern LIKE 'Rit.%'");
                sql.execute("UPDATE category SET name = 'RITENUTE' WHERE name = 'Ritenute'");
            }

            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres)) {
                assertThat(rows(connection, WITH_ROLE)).containsExactly("RITENUTE");
            }
        }
    }

    @Test
    void withoutTheCategoryNoOneGetsTheRoleAndARoleBelongsToOneCategoryOnly() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")) {
            postgres.start();
            flyway(postgres, "36").migrate();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                // Eliminata: movimenti e regole sono rimasti senza categoria (ON DELETE SET NULL di V13).
                sql.execute("DELETE FROM category WHERE name = 'Ritenute'");
            }

            flyway(postgres, "37").migrate();

            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                assertThat(rows(connection, WITH_ROLE)).isEmpty();
                // Il vincolo UNIQUE: un ruolo, una categoria sola. E solo i ruoli che il codice conosce (CHECK).
                sql.execute("UPDATE category SET role = 'WITHHOLDING' WHERE name = 'Cedole'");
                assertThatThrownBy(() -> sql.execute("UPDATE category SET role = 'WITHHOLDING' WHERE name = "
                        + "'Dividendi'")).isInstanceOf(SQLException.class).hasMessageContaining("uq_category_role");
                assertThatThrownBy(() -> sql.execute("UPDATE category SET role = 'BOH' WHERE name = 'Dividendi'"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("category_role_check");
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
