package com.nexuslink.protocol.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end {@link CallableStatement} coverage against in-memory H2, which can turn a static Java
 * method into a callable routine with {@code CREATE ALIAS} — enough to prove the escape syntax,
 * the IN binding, the OUT read-back and the metadata-driven parameter form all line up.
 */
public class JdbcCallTest {

    /** Backing method for the H2 alias used as a function. */
    public static int doubled(int n) { return n * 2; }

    /** Backing method for the H2 alias used as a procedure returning rows. */
    public static java.sql.ResultSet rows(java.sql.Connection c, String role) throws java.sql.SQLException {
        return c.createStatement().executeQuery("SELECT name FROM people WHERE role = '" + role + "'");
    }

    private JdbcService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new JdbcService();
        service.connect("jdbc:h2:mem:calltest;DB_CLOSE_DELAY=-1", "sa", "");
        service.execute("DROP ALL OBJECTS");
        service.execute("CREATE TABLE people (name VARCHAR, role VARCHAR)");
        service.execute("INSERT INTO people VALUES ('ada', 'admin'), ('bob', 'user')");
        service.execute("CREATE ALIAS doubled FOR \""
                + JdbcCallTest.class.getName() + ".doubled\"");
        service.execute("CREATE ALIAS people_by_role FOR \""
                + JdbcCallTest.class.getName() + ".rows\"");
    }

    @AfterEach
    void tearDown() { service.close(); }

    @Test
    void functionCallBindsInputAndReadsTheReturnValue() {
        CallableSpec spec = CallableSpec.function("doubled", List.of(
                CallableSpec.Param.out("return", java.sql.Types.INTEGER, "int"),
                CallableSpec.Param.in("n", "21")));
        CallResult result = service.call(spec);
        assertFalse(result.failed(), String.valueOf(result.errorMessage()));
        assertEquals("42", result.outputs().get("return"));
    }

    @Test
    void aRoutineReturningRowsSurfacesThemAsAResultSet() {
        CallableSpec spec = CallableSpec.procedure("people_by_role",
                List.of(CallableSpec.Param.in("role", "admin")));
        CallResult result = service.call(spec);
        assertFalse(result.failed(), String.valueOf(result.errorMessage()));
        assertNotNull(result.resultSet());
        assertEquals(List.of(List.of("ada")), result.resultSet().rows());
    }

    @Test
    void describeProcedureBuildsTheParameterFormFromDatabaseMetadata() throws Exception {
        CallableSpec spec = service.describeProcedure("DOUBLED");
        assertEquals("DOUBLED", spec.routine());
        assertTrue(spec.isFunction(), "an alias with a return value should render as a function");
        assertEquals("{? = call DOUBLED(?)}", spec.sql());
        assertEquals(1, spec.inputs().size());
    }

    @Test
    void describeProcedureStripsTheListingSuffix() throws Exception {
        assertEquals("DOUBLED", service.describeProcedure("DOUBLED  (function)").routine());
    }

    @Test
    void aFailedCallIsReportedNotThrown() {
        CallResult result = service.call(CallableSpec.procedure("no_such_routine", List.of()));
        assertTrue(result.failed());
        assertNotNull(result.errorMessage());
    }

    @Test
    void aBlankInputValueBindsSqlNull() {
        CallableSpec spec = CallableSpec.procedure("people_by_role",
                List.of(CallableSpec.Param.in("role", null)));
        CallResult result = service.call(spec);
        assertFalse(result.failed(), String.valueOf(result.errorMessage()));
        assertNotNull(result.resultSet());
        assertTrue(result.resultSet().rows().isEmpty(), "no role is NULL, so nothing matches");
    }
}
