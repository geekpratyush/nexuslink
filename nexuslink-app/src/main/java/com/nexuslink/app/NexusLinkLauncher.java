package com.nexuslink.app;

import com.nexuslink.ui.help.HelpDialog;
import com.nexuslink.ui.main.MainWindow;
import com.nexuslink.ui.util.AppIcons;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * Entry point for NexusLink — opens the main workspace shell.
 */
public class NexusLinkLauncher extends Application {

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow();
        stage.setTitle("NexusLink — Universal Connectivity Workbench");
        AppIcons.apply(stage);          // task bar, alt-tab and window decoration all read this
        stage.setScene(window.createScene());
        // Open maximized: the workbench is a multi-pane tool — sidebar, object tree, editor, results
        // — and the scene's own 1180×760 is the restore size when the window is un-maximized.
        stage.setMaximized(true);
        stage.show();

        // Screenshot hook (docs/RUN.md): -Dnexuslink.screenshot=<file.png> snapshots the scene itself
        // and exits. It reads the application's own pixels — never the desktop — so it can never
        // capture anything but NexusLink, and it needs no window manager, mouse or screen-grab tool.
        String shot = System.getProperty("nexuslink.screenshot");
        if (shot != null) scheduleScreenshot(stage, shot);

        // Demo/deep-link hooks (see docs/RUN.md): open Help at a topic, or run a Help search.
        String autoHelp = System.getProperty("nexuslink.autohelp");
        String autoSearch = System.getProperty("nexuslink.autosearch");
        if (autoSearch != null) Platform.runLater(() -> HelpDialog.openWithSearch(autoSearch));
        else if (autoHelp != null) Platform.runLater(() -> HelpDialog.open(autoHelp));
    }

    /**
     * Waits for the scene to settle, writes it to {@code path} as a PNG, and exits. The delay is
     * {@code -Dnexuslink.screenshot.delay} seconds (default 6) — long enough for views that populate
     * asynchronously to have drawn.
     */
    private void scheduleScreenshot(Stage stage, String path) {
        double delay = Double.parseDouble(System.getProperty("nexuslink.screenshot.delay", "6"));
        int frames = Integer.parseInt(System.getProperty("nexuslink.screenshot.frames", "1"));
        double interval = Double.parseDouble(System.getProperty("nexuslink.screenshot.interval", "0.2"));

        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.seconds(delay));
        wait.setOnFinished(e -> {
            if (frames <= 1) {
                shoot(stage, path, -1);
                Platform.exit();
                System.exit(0);
                return;
            }
            // A burst: the same snapshot taken repeatedly while the UI is still changing, which is
            // what turns a connect or a send into a few seconds of real motion.
            javafx.animation.Timeline burst = new javafx.animation.Timeline();
            for (int i = 0; i < frames; i++) {
                int index = i;
                burst.getKeyFrames().add(new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(interval * i), ev -> shoot(stage, path, index)));
            }
            burst.setOnFinished(ev -> {
                System.out.println("screenshot: " + frames + " frames -> " + path);
                Platform.exit();
                System.exit(0);
            });
            burst.play();
        });
        wait.play();
    }

    /** Writes one frame. {@code index} < 0 writes {@code path} as given; otherwise path-NNN.png. */
    private void shoot(Stage stage, String path, int index) {
        try {
            javafx.scene.image.WritableImage image = stage.getScene().snapshot(null);
            String out = index < 0 ? path
                    : path.replaceFirst("\\.png$", "") + String.format("-%03d.png", index);
            writePng(image, java.nio.file.Path.of(out));
            if (index < 0) {
                System.out.println("screenshot: " + out
                        + " (" + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
            }
        } catch (Exception ex) {
            System.err.println("screenshot failed: " + ex);
        }
    }

    /** Writes a JavaFX image as a PNG, pixel by pixel, so no JavaFX-Swing bridge is needed. */
    private static void writePng(javafx.scene.image.WritableImage image, java.nio.file.Path out)
            throws java.io.IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage buffer =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) buffer.setRGB(x, y, pixels.getArgb(x, y));
        }
        java.nio.file.Files.createDirectories(out.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(buffer, "png", out.toFile());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
