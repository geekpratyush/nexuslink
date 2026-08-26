package com.nexuslink.ui.diagnostics;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Routes the protocol libraries' own warnings into the app's Activity log.
 *
 * <p>Every driver NexusLink embeds — the Kafka client, the Mongo driver, Lettuce, the JDBC drivers,
 * the AWS and Azure SDKs — logs through SLF4J. With no SLF4J binding on the classpath those messages
 * go nowhere at all (SLF4J prints its "Defaulting to no-operation (NOP) logger implementation" notice
 * and discards everything), which is a poor trade for a connectivity tool: "SASL authentication
 * failed", "leader not available", "connection reset by peer" are exactly the sentences that explain
 * a failure, and they were being thrown away.
 *
 * <p>The binding sends them to {@code java.util.logging}; this bridge forwards the ones worth reading
 * to the Activity log. The default threshold is {@link Level#WARNING}, because driver INFO chatter
 * (Kafka alone logs its full producer config on every send) would bury the user's own activity.
 */
public final class LibraryLogBridge {

    private static Handler installed;

    private LibraryLogBridge() {}

    /**
     * Sends library warnings to {@code activityLog}.
     *
     * @param activityLog where a message goes — {@code MainWindow::log} in the app
     * @param threshold   the lowest level to forward; {@link Level#WARNING} is the sensible default
     */
    public static synchronized void install(Consumer<String> activityLog, Level threshold) {
        if (activityLog == null) return;
        uninstall();

        Logger root = LogManager.getLogManager().getLogger("");
        // The console handler stays as it is — this adds a destination rather than hijacking one, so
        // running from a terminal still shows what it always did.
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null || record.getLevel().intValue() < threshold.intValue()) return;
                activityLog.accept(format(record));
            }

            @Override public void flush() { }

            @Override public void close() { }
        };
        handler.setLevel(threshold);
        root.addHandler(handler);
        // Without raising the logger's own level the handler never sees anything below INFO.
        if (root.getLevel() == null || root.getLevel().intValue() > threshold.intValue()) {
            root.setLevel(threshold);
        }
        installed = handler;
    }

    /** Installs with the default threshold. */
    public static void install(Consumer<String> activityLog) {
        install(activityLog, Level.WARNING);
    }

    /** Removes the bridge — used when the window closes, and by the tests. */
    public static synchronized void uninstall() {
        if (installed == null) return;
        Logger root = LogManager.getLogManager().getLogger("");
        if (root != null) root.removeHandler(installed);
        installed = null;
    }

    /** {@code true} while library warnings are being forwarded. */
    public static synchronized boolean isInstalled() { return installed != null; }

    /**
     * One log record as a line for the Activity log: the level, the short logger name and the
     * message, with the exception's own message appended when there is one — the cause is usually
     * more informative than the message it was wrapped in.
     */
    static String format(LogRecord record) {
        String source = record.getLoggerName() == null ? "library" : shortName(record.getLoggerName());
        StringBuilder sb = new StringBuilder(record.getLevel().getName().toLowerCase(java.util.Locale.ROOT))
                .append("  ").append(source).append("  ").append(record.getMessage());
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            String message = thrown.getMessage();
            sb.append("  — ").append(thrown.getClass().getSimpleName())
              .append(message == null ? "" : ": " + message);
        }
        return sb.toString();
    }

    /** {@code org.apache.kafka.clients.NetworkClient} → {@code o.a.k.c.NetworkClient}. */
    static String shortName(String loggerName) {
        String[] parts = loggerName.split("\\.");
        if (parts.length <= 1) return loggerName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) sb.append(parts[i].charAt(0)).append('.');
        }
        return sb.append(parts[parts.length - 1]).toString();
    }
}
