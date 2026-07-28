package com.nexuslink.protocol.db;

import com.nexuslink.plugin.codegen.CodeGenRegistry;
import com.nexuslink.plugin.codegen.CodeGenTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlCodeGeneratorTest {

    private final SqlCodeGenerator gen = new SqlCodeGenerator();

    private String render(String targetId, SqlCodeGenerator.Request request) {
        return gen.generate(gen.targetById(targetId), request);
    }

    @Test
    @DisplayName("the JDBC snippet carries the URL and the statement")
    void javaSnippet() {
        String code = render("java", new SqlCodeGenerator.Request(
                "jdbc:postgresql://db:5432/app", "app_user", "SELECT id, name FROM users"));

        assertTrue(code.contains("jdbc:postgresql://db:5432/app"), code);
        assertTrue(code.contains("SELECT id, name FROM users"), code);
        assertTrue(code.contains("\"app_user\""), code);
    }

    @Test
    @DisplayName("a password is never inlined — every target reads it from the environment")
    void passwordComesFromTheEnvironment() {
        SqlCodeGenerator.Request request = new SqlCodeGenerator.Request(
                "jdbc:postgresql://db:5432/app", "app_user", "SELECT 1");

        assertTrue(render("java", request).contains("System.getenv(\"DB_PASSWORD\")"));
        assertTrue(render("python", request).contains("os.environ[\"DB_PASSWORD\"]"));
        assertTrue(render("cli", request).contains("PGPASSWORD"));
    }

    @Test
    @DisplayName("no credentials means no credential plumbing")
    void anonymousConnection() {
        String code = render("java", new SqlCodeGenerator.Request("jdbc:sqlite:app.db", "", "SELECT 1"));

        assertTrue(code.contains("getConnection(\"jdbc:sqlite:app.db\")"), code);
        assertFalse(code.contains("DB_PASSWORD"), code);
    }

    @Test
    @DisplayName("the SQLAlchemy URL is mapped per driver")
    void sqlAlchemyUrls() {
        assertTrue(render("python", new SqlCodeGenerator.Request("jdbc:postgresql://db/app", "", "SELECT 1"))
                .contains("postgresql+psycopg://db/app"));
        assertTrue(render("python", new SqlCodeGenerator.Request("jdbc:mysql://db/app", "", "SELECT 1"))
                .contains("mysql+pymysql://db/app"));
        assertTrue(render("python", new SqlCodeGenerator.Request("jdbc:sqlite:app.db", "", "SELECT 1"))
                .contains("sqlite:///app.db"));
    }

    @Test
    @DisplayName("the CLI target picks the client that matches the driver")
    void cliPerDriver() {
        assertTrue(render("cli", new SqlCodeGenerator.Request("jdbc:postgresql://db/app", "u", "SELECT 1"))
                .contains("psql"));
        assertTrue(render("cli", new SqlCodeGenerator.Request("jdbc:mysql://db/app", "u", "SELECT 1"))
                .contains("mysql -u u -p"));
        assertTrue(render("cli", new SqlCodeGenerator.Request("jdbc:sqlite:app.db", "", "SELECT 1"))
                .contains("sqlite3 app.db"));
        assertTrue(render("cli", new SqlCodeGenerator.Request("jdbc:oracle:thin:@db:1521/x", "", "SELECT 1"))
                .contains("no dedicated CLI mapped"), "an unmapped driver says so rather than guessing");
    }

    @Test
    @DisplayName("a multi-line statement is flattened for the CLI and quotes are escaped")
    void multilineStatementForCli() {
        String code = render("cli", new SqlCodeGenerator.Request("jdbc:sqlite:app.db", "",
                "SELECT *\nFROM t\nWHERE name = \"x\""));

        assertTrue(code.contains("SELECT * FROM t WHERE name = \\\"x\\\""), code);
        assertEquals(1, code.lines().count(), "the command must stay on one line: " + code);
    }

    @Test
    @DisplayName("blank inputs fall back to runnable defaults")
    void requestDefaults() {
        SqlCodeGenerator.Request request = new SqlCodeGenerator.Request(null, null, "  ");

        assertEquals("jdbc:sqlite:app.db", request.jdbcUrl());
        assertEquals("SELECT 1", request.sql());
        assertFalse(request.needsCredentials());
    }

    @Test
    @DisplayName("every target renders non-empty code and bad input is rejected")
    void targetsAndGuards() {
        SqlCodeGenerator.Request request = new SqlCodeGenerator.Request("jdbc:sqlite:a.db", "", "SELECT 1");

        assertEquals(3, gen.targets().size());
        for (CodeGenTarget target : gen.targets()) {
            assertFalse(gen.generate(target, request).isBlank(), target.id() + " rendered nothing");
        }
        assertThrows(IllegalArgumentException.class,
                () -> gen.generate(new CodeGenTarget("perl", "Perl", "text"), request));
        assertThrows(IllegalArgumentException.class, () -> gen.generate(gen.targets().get(0), "nope"));
    }

    @Test
    @DisplayName("the generator is discoverable through the SPI registry")
    void discoverableViaServiceLoader() {
        CodeGenRegistry registry = CodeGenRegistry.load(SqlCodeGenerator.class.getClassLoader());

        assertEquals("SQL", registry.byProtocol("sql").orElseThrow().displayName());
    }
}
