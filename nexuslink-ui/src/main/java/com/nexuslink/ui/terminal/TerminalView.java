package com.nexuslink.ui.terminal;

import com.nexuslink.protocol.ssh.SshTerminalService;
import com.nexuslink.protocol.ssh.VtScreen;
import com.nexuslink.ui.env.Env;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * An interactive SSH terminal tab: a connect bar (host/port/user + password or private-key auth, and an
 * optional local port forward) over a {@link Canvas} that renders a {@link VtScreen} in a monospace
 * font with xterm-256 colours. Remote output arrives on a reader thread, is queued, and is drained,
 * parsed, and repainted on the FX thread by an {@link AnimationTimer}; keystrokes are encoded to the
 * usual control sequences and sent to the remote shell. Resizing the pane reflows both the local screen
 * model and the remote PTY.
 */
public final class TerminalView extends BorderPane {

    private final SshTerminalService service = new SshTerminalService();

    private final TextField hostField = new TextField("localhost");
    private final TextField portField = new TextField("22");
    private final TextField userField = new TextField();
    private final ComboBox<String> authMode = new ComboBox<>();
    private final PasswordField passwordField = new PasswordField();
    private final TextField keyPathField = new TextField();
    private final TextField forwardField = new TextField();
    private final Button connectBtn = new Button("Connect");
    private final Button disconnectBtn = new Button("Disconnect");
    private final Label statusLabel = new Label("Not connected");

    private final Canvas canvas = new Canvas(800, 480);
    private VtScreen screen = new VtScreen(24, 80);
    private final ConcurrentLinkedQueue<byte[]> incoming = new ConcurrentLinkedQueue<>();

    private double charW = 8;
    private double charH = 16;
    private double ascent = 12;
    private Font font;
    private Font boldFont;
    private long lastRevision = -1;
    private long frame;
    private AnimationTimer timer;

    private Consumer<String> logger = s -> {};

    public TerminalView() {
        getStyleClass().add("terminal-view");
        computeFontMetrics(14);
        setTop(buildBar());
        setCenter(buildTerminalPane());
        updateAuthVisibility();
    }

    public void setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> {} : logger; }

    /** Pre-fills the host / user (used when opening a saved connection). */
    public void prefill(String host, String user) {
        if (host != null && !host.isBlank()) hostField.setText(host);
        if (user != null && !user.isBlank()) userField.setText(user);
    }

    // ---- connect bar ----

    private Region buildBar() {
        for (TextField f : new TextField[]{hostField, portField, userField, keyPathField, forwardField}) {
            f.getStyleClass().add("nl-field");
        }
        passwordField.getStyleClass().add("nl-field");
        portField.setPrefWidth(60);
        userField.setPromptText("user");
        keyPathField.setPromptText("~/.ssh/id_ed25519");
        forwardField.setPromptText("localPort:remoteHost:remotePort (optional)");
        HBox.setHgrow(forwardField, Priority.ALWAYS);

        authMode.getItems().setAll("Password", "Private key");
        authMode.getSelectionModel().select(0);
        authMode.valueProperty().addListener((o, a, b) -> updateAuthVisibility());

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());
        disconnectBtn.getStyleClass().add("btn-secondary");
        disconnectBtn.setOnAction(e -> disconnect());
        disconnectBtn.setDisable(true);
        statusLabel.getStyleClass().add("meta-label");

        HBox row1 = new HBox(8, label("Host:"), hostField, label("Port:"), portField,
                label("User:"), userField, authMode, passwordField, keyPathField,
                connectBtn, disconnectBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox.setHgrow(hostField, Priority.ALWAYS);
        HBox.setHgrow(userField, Priority.ALWAYS);
        HBox.setHgrow(passwordField, Priority.ALWAYS);
        HBox.setHgrow(keyPathField, Priority.ALWAYS);

        HBox row2 = new HBox(8, label("Local forward:"), forwardField, statusLabel);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2);
    }

    private void updateAuthVisibility() {
        boolean password = "Password".equals(authMode.getValue());
        passwordField.setVisible(password);
        passwordField.setManaged(password);
        keyPathField.setVisible(!password);
        keyPathField.setManaged(!password);
    }

    // ---- terminal canvas ----

    private Region buildTerminalPane() {
        Pane holder = new Pane(canvas);
        holder.setStyle("-fx-background-color: #1e1e1e;");
        holder.setPadding(new Insets(4));
        canvas.widthProperty().bind(holder.widthProperty().subtract(8));
        canvas.heightProperty().bind(holder.heightProperty().subtract(8));
        canvas.widthProperty().addListener((o, a, b) -> onCanvasResized());
        canvas.heightProperty().addListener((o, a, b) -> onCanvasResized());

        canvas.setFocusTraversable(true);
        canvas.setOnMouseClicked(e -> canvas.requestFocus());
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        canvas.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);

        timer = new AnimationTimer() {
            @Override public void handle(long now) { pump(); }
        };
        timer.start();
        return holder;
    }

    private void computeFontMetrics(double size) {
        font = Font.font("Monospaced", size);
        boldFont = Font.font("Monospaced", FontWeight.BOLD, size);
        Text probe = new Text("M");
        probe.setFont(font);
        charW = Math.ceil(probe.getLayoutBounds().getWidth());
        charH = Math.ceil(probe.getLayoutBounds().getHeight());
        ascent = probe.getBaselineOffset();
    }

    private void onCanvasResized() {
        int cols = Math.max(1, (int) (canvas.getWidth() / charW));
        int rows = Math.max(1, (int) (canvas.getHeight() / charH));
        if (cols == screen.cols() && rows == screen.rows()) { repaint(); return; }
        screen.resize(rows, cols);
        if (service.isConnected()) service.resize(cols, rows);
        repaint();
    }

    /** Drains queued remote output, feeds it to the screen model, and repaints if anything changed. */
    private void pump() {
        boolean changed = false;
        byte[] chunk;
        while ((chunk = incoming.poll()) != null) {
            screen.feed(chunk, chunk.length);
            changed = true;
        }
        frame++;
        if (changed || screen.revision() != lastRevision || frame % 30 == 0) {
            lastRevision = screen.revision();
            repaint();
        }
    }

    private void repaint() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(AnsiPalette.DEFAULT_BG);
        g.fillRect(0, 0, w, h);

        int rows = screen.rows(), cols = screen.cols();
        for (int r = 0; r < rows; r++) {
            double y = r * charH;
            for (int c = 0; c < cols; c++) {
                VtScreen.Cell cell = screen.cellAt(r, c);
                double x = c * charW;
                if (cell.bg() != VtScreen.DEFAULT_COLOR) {
                    g.setFill(AnsiPalette.background(cell.bg()));
                    g.fillRect(x, y, charW, charH);
                }
                char ch = cell.ch();
                if (ch != ' ' && ch != 0) {
                    g.setFont(cell.bold() ? boldFont : font);
                    g.setFill(AnsiPalette.foreground(cell.fg(), cell.bold()));
                    g.fillText(String.valueOf(ch), x, y + ascent);
                }
            }
        }
        // Cursor: a block that blinks about twice a second while focused.
        if (screen.cursorVisible() && (frame / 30) % 2 == 0) {
            double cx = screen.cursorCol() * charW;
            double cy = screen.cursorRow() * charH;
            g.setFill(AnsiPalette.CURSOR);
            g.setGlobalAlpha(0.6);
            g.fillRect(cx, cy, charW, charH);
            g.setGlobalAlpha(1.0);
        }
    }

    // ---- connect / disconnect ----

    private void connect() {
        String host = Env.resolve(hostField.getText().trim());
        String user = Env.resolve(userField.getText().trim());
        int port = parsePort(portField.getText());
        if (host.isBlank() || user.isBlank()) { fail("host and user are required"); return; }

        boolean usePassword = "Password".equals(authMode.getValue());
        String password = passwordField.getText();
        String keyPath = Env.resolve(keyPathField.getText().trim());
        int cols = screen.cols(), rows = screen.rows();

        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");

        service.setOnOutput(incoming::add);
        service.setOnClosed(() -> Platform.runLater(this::onRemoteClosed));

        Thread t = new Thread(() -> {
            try {
                if (usePassword) {
                    service.connect(host, port, user, password, cols, rows);
                } else {
                    service.connectWithKey(host, port, user, Path.of(expand(keyPath)), cols, rows);
                }
                String forwardInfo = applyForward();
                Platform.runLater(() -> onConnected(host, forwardInfo));
            } catch (Exception ex) {
                Platform.runLater(() -> { fail(ex.getMessage()); connectBtn.setDisable(false); });
            }
        }, "ssh-connect");
        t.setDaemon(true);
        t.start();
    }

    /** Parses and applies the optional {@code localPort:remoteHost:remotePort} forward. */
    private String applyForward() throws Exception {
        String spec = Env.resolve(forwardField.getText().trim());
        if (spec.isBlank()) return null;
        String[] parts = spec.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("forward must be localPort:remoteHost:remotePort");
        return service.startLocalForward(Integer.parseInt(parts[0].trim()), parts[1].trim(),
                Integer.parseInt(parts[2].trim()));
    }

    private void onConnected(String host, String forwardInfo) {
        statusLabel.getStyleClass().setAll("status-2xx");
        statusLabel.setText("Connected — " + host + (forwardInfo != null ? "  |  forward → " + forwardInfo : ""));
        disconnectBtn.setDisable(false);
        connectBtn.setDisable(true);
        setBarDisabled(true);
        canvas.requestFocus();
        logger.accept("SSH connected to " + host);
    }

    private void onRemoteClosed() {
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Session closed");
        disconnectBtn.setDisable(true);
        connectBtn.setDisable(false);
        setBarDisabled(false);
    }

    private void disconnect() {
        service.close();
        onRemoteClosed();
        logger.accept("SSH disconnected");
    }

    private void setBarDisabled(boolean disabled) {
        hostField.setDisable(disabled);
        portField.setDisable(disabled);
        userField.setDisable(disabled);
        authMode.setDisable(disabled);
        passwordField.setDisable(disabled);
        keyPathField.setDisable(disabled);
        forwardField.setDisable(disabled);
    }

    // ---- key handling ----

    private void onKeyPressed(KeyEvent e) {
        if (!service.isConnected()) return;
        String seq = null;
        if (e.isControlDown() && e.getCode().isLetterKey()) {
            // Ctrl+A..Z → control codes 0x01..0x1a
            seq = String.valueOf((char) (e.getCode().getChar().toUpperCase().charAt(0) - 'A' + 1));
        } else {
            seq = switch (e.getCode()) {
                case ENTER -> "\r";
                case BACK_SPACE -> "";
                case TAB -> "\t";
                case ESCAPE -> "";
                case UP -> "[A";
                case DOWN -> "[B";
                case RIGHT -> "[C";
                case LEFT -> "[D";
                case HOME -> "[H";
                case END -> "[F";
                case DELETE -> "[3~";
                case INSERT -> "[2~";
                case PAGE_UP -> "[5~";
                case PAGE_DOWN -> "[6~";
                case F1 -> "OP";
                case F2 -> "OQ";
                case F3 -> "OR";
                case F4 -> "OS";
                default -> null;
            };
        }
        if (seq != null) {
            send(seq);
            e.consume();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (!service.isConnected()) return;
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty()) return;
        char c0 = ch.charAt(0);
        // Control chars (Enter/Tab/Esc/Backspace/Ctrl-combos) are already sent from onKeyPressed.
        if (c0 < 0x20 || c0 == 0x7f) return;
        send(ch);
        e.consume();
    }

    private void send(String s) {
        try {
            service.write(s);
        } catch (Exception ex) {
            fail("write failed: " + ex.getMessage());
        }
    }

    // ---- helpers ----

    private static int parsePort(String text) {
        try { return Integer.parseInt(text.trim()); } catch (Exception e) { return 22; }
    }

    private static String expand(String path) {
        if (path.startsWith("~")) return System.getProperty("user.home") + path.substring(1);
        return path;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void fail(String message) {
        statusLabel.getStyleClass().setAll("status-err");
        statusLabel.setText("Error: " + message);
        logger.accept("SSH error: " + message);
    }

    /** Tears down the session (called by the shell when the tab closes). */
    public void dispose() {
        if (timer != null) timer.stop();
        service.close();
    }
}
