package com.nexuslink.ui.ldap;

import com.nexuslink.protocol.ldap.Dn;
import com.nexuslink.protocol.ldap.LdapEntry;
import com.nexuslink.protocol.ldap.LdapService;
import com.nexuslink.protocol.ldap.LdifReader;
import com.nexuslink.protocol.ldap.LdifWriter;
import com.nexuslink.ui.env.Env;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LDAP / Active Directory browser tab — connect (plain or LDAPS) and optionally bind, then run
 * subtree/one-level/base searches with an RFC-4515 filter. Results list by DN on the left; selecting
 * one shows its decoded attributes on the right. On connect the directory's naming contexts pre-fill
 * the search base. Built on the UnboundID LDAP SDK; {@code ${VAR}} is resolved in every field.
 */
public final class LdapView extends BorderPane {

    private final LdapService service = new LdapService();

    private final TextField hostField = new TextField();
    private final TextField portField = new TextField("389");
    private final TextField bindDnField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final CheckBox sslCheck = new CheckBox("LDAPS");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final TextField baseDnField = new TextField();
    private final TextField filterField = new TextField("(objectClass=*)");
    private final ComboBox<String> scopeCombo = new ComboBox<>();
    private final TextField sizeLimitField = new TextField("200");
    private final Button searchBtn = new Button("Search");

    private final ListView<LdapService.Entry> results = new ListView<>();
    private final TreeView<DitNode> ditTree = new TreeView<>();
    private final TextArea detail = new TextArea();

    private Consumer<String> logger = s -> {};

    public LdapView() {
        getStyleClass().add("ldap-view");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills host (and optional bind credentials) when opening a saved connection. */
    public void prefill(String host, String bindDn, String password) {
        if (host != null && !host.isBlank()) hostField.setText(host);
        if (bindDn != null && !bindDn.isBlank()) bindDnField.setText(bindDn);
        if (password != null && !password.isBlank()) passwordField.setText(password);
    }

    private VBox buildBar() {
        hostField.getStyleClass().add("nl-field");
        hostField.setPromptText("ldap host, e.g. ldap.example.com");
        HBox.setHgrow(hostField, Priority.ALWAYS);
        portField.getStyleClass().add("nl-field");
        portField.setPrefWidth(70);
        bindDnField.getStyleClass().add("nl-field");
        bindDnField.setPromptText("bind DN (blank = anonymous)");
        bindDnField.setPrefWidth(240);
        passwordField.getStyleClass().add("nl-field");
        passwordField.setPromptText("password");
        passwordField.setPrefWidth(140);

        sslCheck.setOnAction(e -> {
            if (portField.getText().isBlank() || portField.getText().equals("389") || portField.getText().equals("636")) {
                portField.setText(sslCheck.isSelected() ? "636" : "389");
            }
        });

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("ldap"));

        HBox row1 = new HBox(8, label("Host:"), hostField, label("Port:"), portField, sslCheck, connectBtn, helpBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Bind:"), bindDnField, passwordField);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, statusRow);
    }

    private VBox buildBody() {
        baseDnField.getStyleClass().add("nl-field");
        baseDnField.setPromptText("search base DN, e.g. ou=people,dc=example,dc=com");
        HBox.setHgrow(baseDnField, Priority.ALWAYS);
        filterField.getStyleClass().add("nl-field");
        filterField.setPromptText("LDAP filter, e.g. (uid=alice)");
        HBox.setHgrow(filterField, Priority.ALWAYS);
        scopeCombo.getItems().addAll("sub", "one", "base");
        scopeCombo.setValue("sub");
        sizeLimitField.getStyleClass().add("nl-field");
        sizeLimitField.setPrefWidth(70);
        searchBtn.getStyleClass().add("btn-primary");
        searchBtn.setOnAction(e -> search());

        Button importBtn = new Button("Import LDIF…");
        importBtn.getStyleClass().add("btn-secondary");
        importBtn.setOnAction(e -> importLdif());
        Button exportBtn = new Button("Export LDIF…");
        exportBtn.getStyleClass().add("btn-secondary");
        exportBtn.setOnAction(e -> exportLdif());

        HBox searchRow = new HBox(8, label("Base:"), baseDnField);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox filterRow = new HBox(8, label("Filter:"), filterField, label("Scope:"), scopeCombo,
                label("Limit:"), sizeLimitField, searchBtn, importBtn, exportBtn);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        results.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(LdapService.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.dn());
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> showEntry(nv));

        ditTree.setShowRoot(false);
        ditTree.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                showEntry(nv == null || nv.getValue() == null ? null : nv.getValue().entry));

        Tab listTab = new Tab("List", results);
        listTab.setClosable(false);
        Tab treeTab = new Tab("Tree (DIT)", ditTree);
        treeTab.setClosable(false);
        TabPane leftTabs = new TabPane(listTab, treeTab);
        leftTabs.setPrefWidth(360);
        leftTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        detail.getStyleClass().add("code-area");
        detail.setEditable(false);
        detail.setPromptText("Select an entry to see its attributes…");

        SplitPane split = new SplitPane(leftTabs, detail);
        split.setDividerPositions(0.4);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox box = new VBox(8, searchRow, filterRow, new Separator(), split);
        box.setPadding(new Insets(10));
        return box;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void toggleConnect() {
        if (service.isConnected()) {
            service.close();
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("Disconnected");
            connectBtn.setText("Connect");
            return;
        }
        String host = Env.resolve(hostField.getText().trim());   // resolve ${VAR} against active environment
        if (host.isEmpty()) { statusLabel.setText("Enter a host"); return; }
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException ex) { statusLabel.setText("Port must be a number"); return; }
        boolean ssl = sslCheck.isSelected();
        String bindDn = Env.resolve(bindDnField.getText().trim());
        String password = Env.resolve(passwordField.getText());

        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("LDAP connect → " + host + ":" + port + (ssl ? " (LDAPS)" : ""));

        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception {
                service.connect(host, port, bindDn, password, ssl);
                return service.namingContexts();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + host + ":" + port);
            connectBtn.setText("Disconnect");
            connectBtn.setDisable(false);
            List<String> contexts = task.getValue();
            if (!contexts.isEmpty() && baseDnField.getText().isBlank()) baseDnField.setText(contexts.get(0));
            logger.accept("LDAP connected — naming contexts: " + contexts);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("LDAP connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task, "ldap-connect");
    }

    private void search() {
        if (!service.isConnected()) { statusLabel.setText("Connect first"); return; }
        String base = Env.resolve(baseDnField.getText().trim());   // resolve ${VAR} in base + filter
        if (base.isEmpty()) { statusLabel.setText("Enter a search base DN"); return; }
        String filter = Env.resolve(filterField.getText().trim());
        String scope = scopeCombo.getValue();
        int limit;
        try { limit = Integer.parseInt(sizeLimitField.getText().trim()); }
        catch (NumberFormatException ex) { limit = 0; }
        final int sizeLimit = Math.max(0, limit);

        searchBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Searching…");
        logger.accept("LDAP search " + scope + " " + base + " " + filter);

        Task<List<LdapService.Entry>> task = new Task<>() {
            @Override protected List<LdapService.Entry> call() throws Exception {
                return service.search(base, filter, scope, sizeLimit);
            }
        };
        task.setOnSucceeded(e -> {
            List<LdapService.Entry> entries = task.getValue();
            setEntries(entries);
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText(entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies") + " found");
            logger.accept("LDAP search → " + entries.size() + " entries");
            searchBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Search failed: " + task.getException().getMessage());
            logger.accept("LDAP search FAILED: " + task.getException().getMessage());
            searchBtn.setDisable(false);
        });
        runBg(task, "ldap-search");
    }

    private void showEntry(LdapService.Entry entry) {
        if (entry == null) { detail.clear(); return; }
        StringBuilder sb = new StringBuilder("dn: ").append(entry.dn()).append("\n\n");
        for (Map.Entry<String, List<String>> attr : entry.attributes().entrySet()) {
            for (String value : attr.getValue()) {
                sb.append(attr.getKey()).append(": ").append(value).append("\n");
            }
        }
        detail.setText(sb.toString());
    }

    // --- DIT tree + LDIF (offline) ------------------------------------------------------------

    /** Replace the result set shown in both the list and the DIT tree, clearing the detail pane. */
    private void setEntries(List<LdapService.Entry> entries) {
        results.getItems().setAll(entries);
        rebuildTree(entries);
        detail.clear();
    }

    /**
     * Build a hierarchical TreeView from the entries already returned, deriving parent/child links
     * from each DN via {@link Dn#parent()}. Nodes whose parent is also present are nested and labelled
     * by their RDN; entries with no parent in the set become top-level nodes labelled by their full DN.
     */
    private void rebuildTree(List<LdapService.Entry> entries) {
        TreeItem<DitNode> root = new TreeItem<>(null);
        Map<Dn, TreeItem<DitNode>> byDn = new LinkedHashMap<>();
        List<TreeItem<DitNode>> ordered = new ArrayList<>();

        for (LdapService.Entry e : entries) {
            Dn dn = Dn.parse(e.dn());
            if (byDn.containsKey(dn)) continue;             // ignore duplicate DNs
            String rdnLabel = dn.rdn() == null ? e.dn() : dn.rdn().toString();
            TreeItem<DitNode> item = new TreeItem<>(new DitNode(dn, e, rdnLabel));
            byDn.put(dn, item);
            ordered.add(item);
        }
        for (TreeItem<DitNode> item : ordered) {
            DitNode node = item.getValue();
            TreeItem<DitNode> parent = node.dn.isEmpty() ? null : byDn.get(node.dn.parent());
            if (parent != null && parent != item) {
                parent.getChildren().add(item);
                parent.setExpanded(true);
            } else {                                        // top-level: show the full DN
                item.setValue(new DitNode(node.dn, node.entry, node.dn.toString()));
                root.getChildren().add(item);
            }
        }
        root.setExpanded(true);
        ditTree.setRoot(root);
    }

    /** Read a .ldif file via {@link LdifReader} and display the parsed entries — works fully offline. */
    private void importLdif() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import LDIF");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("LDIF files", "*.ldif"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(window());
        if (file == null) return;

        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Importing LDIF…");
        logger.accept("LDIF import ← " + file.getAbsolutePath());

        Task<List<LdapService.Entry>> task = new Task<>() {
            @Override protected List<LdapService.Entry> call() throws Exception {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                List<LdapEntry> parsed = new LdifReader().read(text);
                List<LdapService.Entry> out = new ArrayList<>(parsed.size());
                for (LdapEntry le : parsed) out.add(fromModel(le));
                return out;
            }
        };
        task.setOnSucceeded(e -> {
            List<LdapService.Entry> entries = task.getValue();
            setEntries(entries);
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Imported " + entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies"));
            logger.accept("LDIF import → " + entries.size() + " entries from " + file.getName());
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Import failed: " + task.getException().getMessage());
            logger.accept("LDIF import FAILED: " + task.getException().getMessage());
        });
        runBg(task, "ldap-ldif-import");
    }

    /**
     * Write the current entries to a .ldif file via {@link LdifWriter} — works fully offline. When a
     * DIT tree node is selected, only that node's subtree is exported; otherwise all results are.
     */
    private void exportLdif() {
        List<LdapService.Entry> toExport = exportSelection();
        if (toExport.isEmpty()) {
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("No entries to export");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export LDIF");
        chooser.setInitialFileName("export.ldif");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("LDIF files", "*.ldif"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showSaveDialog(window());
        if (file == null) return;

        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Exporting LDIF…");

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                List<LdapEntry> model = new ArrayList<>(toExport.size());
                for (LdapService.Entry e : toExport) model.add(toModel(e));
                Files.writeString(file.toPath(), new LdifWriter().write(model), StandardCharsets.UTF_8);
                return model.size();
            }
        };
        task.setOnSucceeded(e -> {
            int count = task.getValue();
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Exported " + count + " entr" + (count == 1 ? "y" : "ies"));
            logger.accept("LDIF export → " + count + " entries to " + file.getName());
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Export failed: " + task.getException().getMessage());
            logger.accept("LDIF export FAILED: " + task.getException().getMessage());
        });
        runBg(task, "ldap-ldif-export");
    }

    /** Entries to export: the selected DIT subtree if a node is selected, else all current results. */
    private List<LdapService.Entry> exportSelection() {
        TreeItem<DitNode> selected = ditTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null) {
            Dn base = selected.getValue().dn;
            List<LdapService.Entry> subtree = new ArrayList<>();
            for (LdapService.Entry e : results.getItems()) {
                if (Dn.parse(e.dn()).isDescendantOf(base)) subtree.add(e);
            }
            if (!subtree.isEmpty()) return subtree;
        }
        return new ArrayList<>(results.getItems());
    }

    private static LdapEntry toModel(LdapService.Entry entry) {
        LdapEntry le = new LdapEntry(Dn.parse(entry.dn()));
        entry.attributes().forEach((name, values) -> values.forEach(v -> le.add(name, v)));
        return le;
    }

    private static LdapService.Entry fromModel(LdapEntry entry) {
        return new LdapService.Entry(entry.dn().toString(), entry.attributes());
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    /** A node in the directory information tree: its DN, the backing entry (if any), and a display label. */
    private static final class DitNode {
        final Dn dn;
        final LdapService.Entry entry;
        final String label;

        DitNode(Dn dn, LdapService.Entry entry, String label) {
            this.dn = dn;
            this.entry = entry;
            this.label = label;
        }

        @Override public String toString() {
            return label;     // the default TreeCell renders this
        }
    }
}
