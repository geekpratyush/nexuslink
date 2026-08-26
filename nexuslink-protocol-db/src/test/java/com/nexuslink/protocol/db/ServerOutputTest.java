package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerOutputTest {

    @Test
    void oracleUrlsUseDbmsOutput() {
        assertEquals(ServerOutput.DBMS_OUTPUT, ServerOutput.forUrl("jdbc:oracle:thin:@//host:1521/orcl"));
        assertEquals(ServerOutput.DBMS_OUTPUT, ServerOutput.forUrl("JDBC:ORACLE:OCI:@orcl"));
    }

    @Test
    void everyOtherUrlFallsBackToJdbcWarnings() {
        assertEquals(ServerOutput.WARNINGS, ServerOutput.forUrl("jdbc:postgresql://host/db"));
        assertEquals(ServerOutput.WARNINGS, ServerOutput.forUrl("jdbc:h2:mem:x"));
        assertEquals(ServerOutput.WARNINGS, ServerOutput.forUrl(null));
    }

    @Test
    void onlyOracleNeedsSwitchingOn() {
        assertTrue(ServerOutput.DBMS_OUTPUT.needsEnabling());
        assertFalse(ServerOutput.WARNINGS.needsEnabling());
    }

    @Test
    void dbmsOutputRendersItsEnableDisableAndFetchCalls() {
        assertEquals("begin dbms_output.enable(null); end;", ServerOutput.DBMS_OUTPUT.enableSql());
        assertEquals("begin dbms_output.disable; end;", ServerOutput.DBMS_OUTPUT.disableSql());
        assertEquals("{call dbms_output.get_line(?, ?)}", ServerOutput.DBMS_OUTPUT.fetchLineSql());
    }

    @Test
    void warningsModeHasNoSqlOfItsOwn() {
        assertThrows(IllegalStateException.class, ServerOutput.WARNINGS::enableSql);
        assertThrows(IllegalStateException.class, ServerOutput.WARNINGS::disableSql);
        assertThrows(IllegalStateException.class, ServerOutput.WARNINGS::fetchLineSql);
    }

    @Test
    void labelsNameTheMechanism() {
        assertEquals("DBMS_OUTPUT", ServerOutput.DBMS_OUTPUT.label());
        assertEquals("server notices", ServerOutput.WARNINGS.label());
    }

    @Test
    void drainingIsEmptyUntilOutputIsSwitchedOn() throws Exception {
        try (JdbcService service = new JdbcService()) {
            service.connect("jdbc:h2:mem:serveroutput;DB_CLOSE_DELAY=-1", "sa", "");
            assertEquals(ServerOutput.WARNINGS, service.serverOutputMode());
            assertFalse(service.isServerOutputEnabled());
            service.execute("SELECT 1");
            assertTrue(service.drainServerOutput().isEmpty());

            service.setServerOutputEnabled(true);
            assertTrue(service.isServerOutputEnabled());
            service.execute("SELECT 1");
            assertNotNull(service.drainServerOutput(), "a quiet statement drains to an empty list");
        }
    }

    @Test
    void closingAConnectionSwitchesOutputBackOff() throws Exception {
        JdbcService service = new JdbcService();
        service.connect("jdbc:h2:mem:serveroutput2;DB_CLOSE_DELAY=-1", "sa", "");
        service.setServerOutputEnabled(true);
        service.close();
        assertFalse(service.isServerOutputEnabled());
    }
}
