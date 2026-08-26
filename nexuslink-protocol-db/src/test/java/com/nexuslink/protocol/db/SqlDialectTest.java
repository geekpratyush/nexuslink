package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlDialectTest {

    @Test
    void theUrlPicksTheDialect() {
        assertEquals(SqlDialect.ORACLE, SqlDialect.forUrl("jdbc:oracle:thin:@//h:1521/orcl"));
        assertEquals(SqlDialect.SQLSERVER, SqlDialect.forUrl("jdbc:sqlserver://h;databaseName=x"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.forUrl("jdbc:mysql://h/db"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.forUrl("jdbc:mariadb://h/db"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.forUrl("jdbc:postgresql://h/db"));
        assertEquals(SqlDialect.SQLITE, SqlDialect.forUrl("jdbc:sqlite:/tmp/x.db"));
        assertEquals(SqlDialect.H2, SqlDialect.forUrl("jdbc:h2:mem:x"));
        assertEquals(SqlDialect.DB2, SqlDialect.forUrl("jdbc:db2://h:50000/db"));
        assertEquals(SqlDialect.GENERIC, SqlDialect.forUrl("jdbc:something:else"));
        assertEquals(SqlDialect.GENERIC, SqlDialect.forUrl(null));
    }

    @Test
    void oracleCapsRowsWithFetchFirstNotLimit() {
        String sql = SqlDialect.ORACLE.selectAll("bad_precompute", 100);
        assertEquals("SELECT * FROM \"bad_precompute\" FETCH FIRST 100 ROWS ONLY;", sql);
        assertFalse(sql.contains("LIMIT"), "LIMIT is a syntax error on Oracle");
    }

    @Test
    void sqlServerCapsRowsWithTop() {
        assertEquals("SELECT TOP (100) * FROM [orders];", SqlDialect.SQLSERVER.selectAll("orders", 100));
    }

    @Test
    void theLimitEnginesKeepLimit() {
        assertEquals("SELECT * FROM `orders` LIMIT 100;", SqlDialect.MYSQL.selectAll("orders", 100));
        assertEquals("SELECT * FROM \"orders\" LIMIT 100;", SqlDialect.POSTGRES.selectAll("orders", 100));
        assertEquals("SELECT * FROM \"orders\" LIMIT 100;", SqlDialect.SQLITE.selectAll("orders", 100));
    }

    @Test
    void db2CapsRowsLikeOracle() {
        assertTrue(SqlDialect.DB2.selectAll("t", 50).contains("FETCH FIRST 50 ROWS ONLY"));
    }

    @Test
    void identifiersAreQuotedPerEngine() {
        assertEquals("`t`", SqlDialect.MYSQL.quote("t"));
        assertEquals("[t]", SqlDialect.SQLSERVER.quote("t"));
        assertEquals("\"t\"", SqlDialect.ORACLE.quote("t"));
    }

    @Test
    void embeddedQuoteCharactersAreDoubled() {
        assertEquals("`we``ird`", SqlDialect.MYSQL.quote("we`ird"));
        assertEquals("[we]]ird]", SqlDialect.SQLSERVER.quote("we]ird"));
        assertEquals("\"we\"\"ird\"", SqlDialect.POSTGRES.quote("we\"ird"));
    }

    @Test
    void aQualifiedNameIsQuotedPartByPart() {
        assertEquals("\"public\".\"orders\"", SqlDialect.POSTGRES.quote("public.orders"));
        assertEquals("[dbo].[orders]", SqlDialect.SQLSERVER.quote("dbo.orders"));
    }

    @Test
    void sqliteEmptiesATableWithDeleteBecauseItHasNoTruncate() {
        assertFalse(SqlDialect.SQLITE.supportsTruncate());
        assertEquals("DELETE FROM \"orders\";", SqlDialect.SQLITE.truncateTable("orders"));
        assertEquals("TRUNCATE TABLE \"orders\";", SqlDialect.POSTGRES.truncateTable("orders"));
    }

    @Test
    void db2NeedsTheImmediateKeywordOnTruncate() {
        assertEquals("TRUNCATE TABLE \"t\" IMMEDIATE;", SqlDialect.DB2.truncateTable("t"));
        assertEquals("TRUNCATE TABLE \"t\";", SqlDialect.ORACLE.truncateTable("t"));
    }

    @Test
    void renamingUsesSpRenameOnSqlServerAndRenameTableOnMysql() {
        assertEquals("EXEC sp_rename 'orders', 'sales';", SqlDialect.SQLSERVER.renameTable("orders", "sales"));
        assertEquals("RENAME TABLE `orders` TO `sales`;", SqlDialect.MYSQL.renameTable("orders", "sales"));
        assertEquals("ALTER TABLE \"orders\" RENAME TO \"sales\";",
                SqlDialect.ORACLE.renameTable("orders", "sales"));
    }

    @Test
    void renamingAColumnAlsoGoesThroughSpRenameOnSqlServer() {
        assertEquals("EXEC sp_rename 'orders.qty', 'quantity', 'COLUMN';",
                SqlDialect.SQLSERVER.renameColumn("orders", "qty", "quantity"));
        assertEquals("ALTER TABLE \"orders\" RENAME COLUMN \"qty\" TO \"quantity\";",
                SqlDialect.POSTGRES.renameColumn("orders", "qty", "quantity"));
    }

    @Test
    void addColumnSyntaxDiffersOnOracleAndSqlServer() {
        assertEquals("ALTER TABLE \"t\" ADD (\"c\" NUMBER);", SqlDialect.ORACLE.addColumn("t", "c", "NUMBER"));
        assertEquals("ALTER TABLE [t] ADD [c] INT;", SqlDialect.SQLSERVER.addColumn("t", "c", "INT"));
        assertEquals("ALTER TABLE `t` ADD COLUMN `c` INT;", SqlDialect.MYSQL.addColumn("t", "c", "INT"));
    }

    @Test
    void viewReplacementFollowsTheEngine() {
        assertEquals("CREATE OR ALTER VIEW [v] AS\nSELECT 1;",
                SqlDialect.SQLSERVER.createOrReplaceView("v", "SELECT 1;"));
        assertEquals("CREATE VIEW \"v\" AS\nSELECT 1;", SqlDialect.SQLITE.createOrReplaceView("v", "SELECT 1"));
        assertTrue(SqlDialect.ORACLE.createOrReplaceView("v", "SELECT 1").startsWith("CREATE OR REPLACE VIEW"));
        assertFalse(SqlDialect.SQLITE.supportsCreateOrReplaceView());
    }

    @Test
    void theRowCapIsAClauseOrAPrefixDependingOnTheEngine() {
        assertEquals(" LIMIT 10", SqlDialect.POSTGRES.limitClause(10));
        assertEquals("", SqlDialect.POSTGRES.topPrefix(10));
        assertEquals("", SqlDialect.SQLSERVER.limitClause(10));
        assertEquals("TOP (10) ", SqlDialect.SQLSERVER.topPrefix(10));
        assertEquals(" FETCH FIRST 10 ROWS ONLY", SqlDialect.ORACLE.limitClause(10));
        assertEquals("", SqlDialect.ORACLE.limitClause(null));
    }

    @Test
    void stringLiteralsEscapeQuotes() {
        assertEquals("'O''Hara'", SqlDialect.GENERIC.stringLiteral("O'Hara"));
        assertEquals("NULL", SqlDialect.GENERIC.stringLiteral(null));
    }

    @Test
    void everyDialectHasAReadableLabel() {
        for (SqlDialect d : SqlDialect.values()) assertFalse(d.label().isBlank(), d.name());
    }
}
