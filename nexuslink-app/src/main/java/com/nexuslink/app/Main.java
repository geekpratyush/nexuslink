package com.nexuslink.app;

/**
 * Plain (non-JavaFX) entry point for the packaged application.
 *
 * <p>When a JavaFX app is launched from an executable/uber JAR via {@code java -jar}, the JVM
 * refuses to start if the {@code Main-Class} is itself a subclass of {@link javafx.application.Application}
 * — it reports <em>"JavaFX runtime components are missing"</em> because the JavaFX modules aren't on
 * the module path. Delegating from a class that does <b>not</b> extend {@code Application} sidesteps
 * that check, so the fat JAR (which carries the JavaFX classes + native libraries on the classpath)
 * starts by a simple double-click. {@link NexusLinkLauncher} remains the real JavaFX entry point and
 * is still used directly by {@code javafx:run} during development.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        NexusLinkLauncher.main(args);
    }
}
