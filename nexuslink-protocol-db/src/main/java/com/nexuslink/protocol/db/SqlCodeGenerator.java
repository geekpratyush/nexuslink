package com.nexuslink.protocol.db;

import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;

import java.util.List;

/**
 * Renders a "run this query against this database" snippet — the SQL side of the cross-protocol
 * code-generation SPI.
 *
 * <p>A password is never written into a snippet: each target reads it from an environment variable
 * so the copied code is safe to paste into a repo or a ticket.
 */
public final class SqlCodeGenerator implements CodeGenerator {

    /**
     * What to generate for.
     *
     * @param jdbcUrl  the JDBC URL the view is connected with (blank → a SQLite placeholder)
     * @param username optional database user
     * @param sql      the statement to run (blank → {@code SELECT 1})
     */
    public record Request(String jdbcUrl, String username, String sql) {

        public Request {
            jdbcUrl = (jdbcUrl == null || jdbcUrl.isBlank()) ? "jdbc:sqlite:app.db" : jdbcUrl.trim();
            username = username == null ? "" : username.trim();
            sql = (sql == null || sql.isBlank()) ? "SELECT 1" : sql.trim();
        }

        /** {@code true} when the URL names a driver that needs credentials. */
        public boolean needsCredentials() {
            return !username.isBlank();
        }
    }

    private static final CodeGenTarget JAVA =
            new CodeGenTarget("java", "Java (JDBC)", "java");
    private static final CodeGenTarget PYTHON =
            new CodeGenTarget("python", "Python (SQLAlchemy)", "python");
    private static final CodeGenTarget CLI =
            new CodeGenTarget("cli", "CLI (psql / mysql / sqlite3)", "shell");

    private static final List<CodeGenTarget> TARGETS = List.of(JAVA, PYTHON, CLI);

    @Override
    public String protocolId() {
        return "sql";
    }

    @Override
    public String displayName() {
        return "SQL";
    }

    @Override
    public boolean supports(Object request) {
        return request instanceof Request;
    }

    @Override
    public List<CodeGenTarget> targets() {
        return TARGETS;
    }

    @Override
    public String generate(CodeGenTarget target, Object request) {
        if (!(request instanceof Request r)) {
            throw new IllegalArgumentException("not a SQL code-gen request: " + request);
        }
        if (JAVA.id().equals(target.id())) return java(r);
        if (PYTHON.id().equals(target.id())) return python(r);
        if (CLI.id().equals(target.id())) return cli(r);
        throw new IllegalArgumentException("unknown SQL code-gen target: " + target.id());
    }

    // ------------------------------------------------------------------ renderers

    private static String java(Request r) {
        String connect = r.needsCredentials()
                ? "DriverManager.getConnection(\"%s\", \"%s\", System.getenv(\"DB_PASSWORD\"))"
                        .formatted(r.jdbcUrl(), r.username())
                : "DriverManager.getConnection(\"%s\")".formatted(r.jdbcUrl());
        return """
                // JDBC — add the driver for this URL to the classpath
                String sql = ""\"
                        %s""\";
                try (Connection conn = %s;
                     PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            System.out.print(md.getColumnLabel(i) + "=" + rs.getString(i) + " ");
                        }
                        System.out.println();
                    }
                }
                """.formatted(indent(r.sql()), connect);
    }

    private static String python(Request r) {
        String url = sqlAlchemyUrl(r);
        return """
                # pip install sqlalchemy  (plus the DBAPI driver for this database)
                import os
                from sqlalchemy import create_engine, text

                engine = create_engine("%s")
                with engine.connect() as conn:
                    for row in conn.execute(text(""\"%s""\")):
                        print(row)
                """.formatted(url, "\n" + indent(r.sql()) + "\n");
    }

    private static String cli(Request r) {
        String url = r.jdbcUrl();
        String user = r.needsCredentials() ? " -U " + r.username() : "";
        if (url.startsWith("jdbc:postgresql:")) {
            return """
                    # password from the environment, never on the command line
                    export PGPASSWORD="$DB_PASSWORD"
                    psql "%s"%s -c "%s"
                    """.formatted(url.substring("jdbc:".length()), user, oneLine(r.sql()));
        }
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
            String mysqlUser = r.needsCredentials() ? " -u " + r.username() + " -p" : "";
            return """
                    mysql%s -e "%s"
                    # connection details from: %s
                    """.formatted(mysqlUser, oneLine(r.sql()), url);
        }
        if (url.startsWith("jdbc:sqlite:")) {
            return """
                    sqlite3 %s "%s"
                    """.formatted(url.substring("jdbc:sqlite:".length()), oneLine(r.sql()));
        }
        return """
                # no dedicated CLI mapped for this driver — use your database's client with:
                #   URL:  %s
                #   user: %s
                %s
                """.formatted(url, r.username().isBlank() ? "(none)" : r.username(), r.sql());
    }

    /** Maps a JDBC URL onto the SQLAlchemy dialect URL, keeping the credentials in the environment. */
    private static String sqlAlchemyUrl(Request r) {
        String url = r.jdbcUrl();
        String creds = r.needsCredentials() ? r.username() + ":\" + os.environ[\"DB_PASSWORD\"] + \"@" : "";
        if (url.startsWith("jdbc:postgresql://")) {
            return "postgresql+psycopg://" + creds + url.substring("jdbc:postgresql://".length());
        }
        if (url.startsWith("jdbc:mysql://")) {
            return "mysql+pymysql://" + creds + url.substring("jdbc:mysql://".length());
        }
        if (url.startsWith("jdbc:mariadb://")) {
            return "mariadb+pymysql://" + creds + url.substring("jdbc:mariadb://".length());
        }
        if (url.startsWith("jdbc:sqlite:")) {
            return "sqlite:///" + url.substring("jdbc:sqlite:".length());
        }
        return url.startsWith("jdbc:") ? url.substring("jdbc:".length()) : url;
    }

    private static String indent(String sql) {
        return sql.lines().map(l -> "        " + l).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String oneLine(String sql) {
        return sql.replace("\"", "\\\"").lines()
                .map(String::trim)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
