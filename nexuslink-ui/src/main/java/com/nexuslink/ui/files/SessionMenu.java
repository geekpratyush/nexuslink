package com.nexuslink.ui.files;

import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The connect bar's <b>Sessions</b> drop-down: save the filled-in connection form as a named profile,
 * quick-connect to a saved one, or forget it. Shared by every file-style connector — the view supplies
 * a {@code capture} that reads its own fields (SFTP has a key path, FTP has passive/TLS flags) and an
 * {@code open} that pre-fills those fields and connects, so this class stays protocol-agnostic.
 *
 * <p>Profiles persist per protocol under {@code ~/.nexuslink/sessions-<protocol>.txt} via the pure
 * {@link SavedSessions} model — which never writes a password, so quick-connect fills in everything
 * except the secret.
 */
public final class SessionMenu extends MenuButton {

    private final java.nio.file.Path file;
    private final SavedSessions sessions;
    private final Supplier<SavedSessions.Session> capture;
    private final Consumer<SavedSessions.Session> open;
    private Consumer<String> logger = s -> {};

    /** The session last saved or opened — the one {@link #rememberDirs} updates. */
    private String activeName = "";

    /**
     * @param protocol short id used for the file name, e.g. {@code "sftp"}
     * @param capture  builds a session from the connect form's current values (its name is ignored;
     *                 it should carry the current pane directories so saving records where you are)
     * @param open     pre-fills the form from a session and connects
     */
    public SessionMenu(String protocol, Supplier<SavedSessions.Session> capture,
                       Consumer<SavedSessions.Session> open) {
        super("Sessions ▾");
        this.file = SavedSessions.fileFor(protocol);
        this.sessions = SavedSessions.load(file);
        this.capture = capture;
        this.open = open;
        getStyleClass().add("btn-secondary");
        setTooltip(new Tooltip("Save this connection as a named session and quick-connect to it later"));
        setOnShowing(e -> rebuild());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /**
     * Records the directories both panes were left in against the active session and persists them, so
     * the next quick-connect lands back there. A no-op when nothing has been saved or opened yet, or
     * when the session has since been forgotten, so views can call this unconditionally on disconnect.
     */
    public void rememberDirs(String localDir, String remoteDir) {
        if (activeName.isEmpty()) return;
        if (sessions.rememberDirs(activeName, localDir, remoteDir)) persist();
    }

    /** Rebuilds the drop-down each time it opens, so it reflects the form's current values. */
    private void rebuild() {
        getItems().clear();

        MenuItem save = new MenuItem("Save current as session…");
        save.setOnAction(e -> saveCurrent());
        getItems().add(save);

        List<SavedSessions.Session> saved = sessions.list();
        if (!saved.isEmpty()) {
            getItems().add(new SeparatorMenuItem());
            for (SavedSessions.Session s : saved) {
                MenuItem mi = new MenuItem(s.name() + "  —  " + s.target());
                mi.setOnAction(e -> quickConnect(s));
                getItems().add(mi);
            }
            getItems().add(new SeparatorMenuItem());
            MenuItem forget = new MenuItem("Forget a session…");
            forget.setOnAction(e -> forget());
            getItems().add(forget);
        }
    }

    /** Names the current form and stores it, defaulting to the active name or the host. */
    private void saveCurrent() {
        SavedSessions.Session current = capture.get();
        String suggested = activeName.isEmpty() ? current.host() : activeName;
        TextInputDialog dialog = new TextInputDialog(suggested);
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle("Save session");
        dialog.setHeaderText("Save " + current.target() + " as a named session.");
        dialog.setContentText("Name:");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            sessions.add(current.withName(name));
            activeName = name;
            persist();
            logger.accept("Saved session '" + name + "' → " + current.target());
        });
    }

    /** Pre-fills the connect form from {@code s} and connects, landing in its remembered directories. */
    private void quickConnect(SavedSessions.Session s) {
        activeName = s.name();
        logger.accept("Opening session '" + s.name() + "' → " + s.target());
        open.accept(s);
    }

    private void forget() {
        List<String> names = sessions.list().stream().map(SavedSessions.Session::name).toList();
        if (names.isEmpty()) return;
        ChoiceDialog<String> dialog = new ChoiceDialog<>(names.get(0), names);
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle("Forget session");
        dialog.setHeaderText("Remove a saved session. This does not affect the server.");
        dialog.setContentText("Session:");
        dialog.showAndWait().ifPresent(name -> {
            if (!sessions.remove(name)) return;
            if (activeName.equalsIgnoreCase(name)) activeName = "";
            persist();
            logger.accept("Forgot session '" + name + "'");
        });
    }

    private void persist() {
        try {
            sessions.save(file);
        } catch (IOException e) {
            logger.accept("Could not save sessions: " + e.getMessage());
        }
    }
}
