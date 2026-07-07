package com.nexuslink.ui.secrets;

import com.nexuslink.protocol.secrets.ConjurService;
import com.nexuslink.protocol.secrets.SecretsManagerService;
import com.nexuslink.protocol.secrets.VaultService;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * External Secret Vaults tab — one sub-tab per backend: HashiCorp Vault (token / AppRole auth + KV v2
 * CRUD), AWS Secrets Manager (list / get / create / put / delete + versions), and CyberArk Conjur
 * (machine-identity authenticate + read variable). Each backend is driven by its service in
 * {@code nexuslink-protocol-secrets}. {@code ${VAR}} placeholders in every field are resolved against
 * the active environment before use, so credentials can live in an environment rather than in the UI.
 */
public final class SecretVaultsView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final VaultService vault = new VaultService();
    private final SecretsManagerService secretsManager = new SecretsManagerService();
    private final ConjurService conjur = new ConjurService();

    private final TextArea activity = new TextArea();
    private Consumer<String> logger = s -> {};

    public SecretVaultsView() {
        getStyleClass().add("secrets-view");
        TabPane tabs = new TabPane(
                closed("HashiCorp Vault", buildVaultTab()),
                closed("AWS Secrets Manager", buildSecretsManagerTab()),
                closed("CyberArk Conjur", buildConjurTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        activity.setEditable(false);
        activity.getStyleClass().add("code-area");
        activity.setPrefRowCount(6);
        TitledPane logPane = new TitledPane("Activity", activity);
        logPane.setExpanded(true);

        setCenter(tabs);
        setBottom(logPane);
    }

    public void setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> {} : logger; }

    // ======================================================================================
    //  HashiCorp Vault
    // ======================================================================================

    private final TextField vAddr = field("http://localhost:8200", "https://vault:8200");
    private final TextField vNamespace = field("", "namespace (Enterprise, optional)");
    private final ComboBox<String> vAuthMode = new ComboBox<>(FXCollections.observableArrayList("Token", "AppRole"));
    private final PasswordField vToken = new PasswordField();
    private final TextField vRoleId = field("", "role_id");
    private final PasswordField vSecretId = new PasswordField();
    private final Button vConnect = new Button("Connect");
    private final Label vStatus = meta("Not connected");

    private final TextField vMount = field("secret", "KV v2 mount");
    private final TextField vPath = field("", "path  (e.g. app/db  ·  ${SECRET_PATH})");
    private final ObservableList<Kv> vData = FXCollections.observableArrayList();

    private Node buildVaultTab() {
        vAuthMode.setValue("Token");
        vToken.setPromptText("vault token");
        vToken.getStyleClass().add("nl-field");
        vSecretId.setPromptText("secret_id");
        vSecretId.getStyleClass().add("nl-field");
        vConnect.getStyleClass().add("btn-primary");
        vConnect.setOnAction(e -> vaultConnect());

        // Auth fields swap with the mode.
        HBox tokenRow = new HBox(8, label("Token:"), grow(vToken));
        HBox approleRow = new HBox(8, label("Role ID:"), grow(vRoleId), label("Secret ID:"), grow(vSecretId));
        tokenRow.setAlignment(Pos.CENTER_LEFT);
        approleRow.setAlignment(Pos.CENTER_LEFT);
        approleRow.setVisible(false);
        approleRow.setManaged(false);
        vAuthMode.valueProperty().addListener((o, ov, nv) -> {
            boolean approle = "AppRole".equals(nv);
            tokenRow.setVisible(!approle); tokenRow.setManaged(!approle);
            approleRow.setVisible(approle); approleRow.setManaged(approle);
        });

        HBox row1 = new HBox(8, label("Address:"), grow(vAddr),
                new com.nexuslink.ui.hint.HelpButton("getting-started", "Secret Vaults help"));
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(8, label("Auth:"), vAuthMode, label("Namespace:"), grow(vNamespace), vConnect);
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox conn = new VBox(8, row1, row2, tokenRow, approleRow, vStatus);
        conn.setPadding(new Insets(12));

        // KV v2 CRUD panel
        Button read = secondary("Read", e -> vaultRead());
        Button write = primary("Write", e -> vaultWrite());
        Button list = secondary("List", e -> vaultList());
        Button del = secondary("Delete", e -> vaultDelete());
        HBox kvBar = new HBox(8, label("Mount:"), vMount, label("Path:"), grow(vPath), read, write, list, del);
        kvBar.setAlignment(Pos.CENTER_LEFT);
        vMount.setPrefWidth(110);

        TableView<Kv> table = kvTable(vData, "No data — Read a secret, or add rows and Write.");
        Button addRow = secondary("+ Row", e -> vData.add(new Kv("", "")));
        Button rmRow = secondary("Remove", e -> {
            Kv sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) vData.remove(sel);
        });
        HBox rowBar = new HBox(8, label("Data:"), addRow, rmRow);
        rowBar.setAlignment(Pos.CENTER_LEFT);

        VBox kv = new VBox(10, kvBar, rowBar, table);
        kv.setPadding(new Insets(0, 12, 12, 12));
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox box = new VBox(new Separator(), kv);
        BorderPane pane = new BorderPane(box);
        pane.setTop(conn);
        return pane;
    }

    private void vaultConnect() {
        if (vault.isConnected()) {
            vault.close();
            setStatus(vStatus, false, "Disconnected");
            vConnect.setText("Connect");
            return;
        }
        String addr = Env.resolve(vAddr.getText().trim());
        boolean approle = "AppRole".equals(vAuthMode.getValue());
        String ns = Env.resolve(vNamespace.getText().trim());
        vConnect.setDisable(true);
        setStatus(vStatus, true, "Connecting…");
        Task<String> task = new Task<>() {
            @Override protected String call() {
                if (approle) {
                    return "AppRole token " + shorten(vault.loginAppRole(addr,
                            Env.resolve(vRoleId.getText().trim()), Env.resolve(vSecretId.getText()), "approle", ns));
                }
                vault.connectToken(addr, Env.resolve(vToken.getText()), ns.isBlank() ? null : ns);
                var h = vault.health();
                return "sealed=" + h.path("sealed").asBoolean() + " v" + h.path("version").asText("?");
            }
        };
        task.setOnSucceeded(e -> {
            setStatus(vStatus, true, "Connected — " + addr + "  (" + task.getValue() + ")");
            vConnect.setText("Disconnect"); vConnect.setDisable(false);
            append("🔓 Vault connected → " + addr);
            logger.accept("Vault connected → " + addr);
        });
        task.setOnFailed(e -> {
            setStatus(vStatus, false, "Connect failed: " + msg(task));
            vConnect.setDisable(false);
            append("⚠ Vault connect failed: " + msg(task));
        });
        runBg(task, "vault-connect");
    }

    private void vaultRead() {
        if (!vault.isConnected()) { setStatus(vStatus, false, "Not connected"); return; }
        String mount = vMount.getText().trim(), path = Env.resolve(vPath.getText().trim());
        Task<VaultService.KvSecret> task = new Task<>() {
            @Override protected VaultService.KvSecret call() { return vault.readKv2(mount, path); }
        };
        task.setOnSucceeded(e -> {
            VaultService.KvSecret s = task.getValue();
            vData.setAll(toKvRows(s.data()));
            append("📖 read " + mount + "/" + path + "  v" + s.version() + "  (" + s.data().size() + " keys)");
        });
        task.setOnFailed(e -> append("⚠ read failed: " + msg(task)));
        runBg(task, "vault-read");
    }

    private void vaultWrite() {
        if (!vault.isConnected()) { setStatus(vStatus, false, "Not connected"); return; }
        String mount = vMount.getText().trim(), path = Env.resolve(vPath.getText().trim());
        Map<String, String> data = new LinkedHashMap<>();
        for (Kv kv : vData) if (!kv.getKey().isBlank()) data.put(Env.resolve(kv.getKey().trim()), Env.resolve(kv.getValue()));
        Task<Void> task = new Task<>() {
            @Override protected Void call() { vault.writeKv2(mount, path, data); return null; }
        };
        task.setOnSucceeded(e -> append("💾 wrote " + mount + "/" + path + "  (" + data.size() + " keys)"));
        task.setOnFailed(e -> append("⚠ write failed: " + msg(task)));
        runBg(task, "vault-write");
    }

    private void vaultList() {
        if (!vault.isConnected()) { setStatus(vStatus, false, "Not connected"); return; }
        String mount = vMount.getText().trim(), path = Env.resolve(vPath.getText().trim());
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return vault.listKv2(mount, path); }
        };
        task.setOnSucceeded(e -> append("📂 list " + mount + "/" + path + " → " + task.getValue()));
        task.setOnFailed(e -> append("⚠ list failed: " + msg(task)));
        runBg(task, "vault-list");
    }

    private void vaultDelete() {
        if (!vault.isConnected()) { setStatus(vStatus, false, "Not connected"); return; }
        String mount = vMount.getText().trim(), path = Env.resolve(vPath.getText().trim());
        Task<Void> task = new Task<>() {
            @Override protected Void call() { vault.deleteKv2(mount, path); return null; }
        };
        task.setOnSucceeded(e -> { vData.clear(); append("🗑 deleted " + mount + "/" + path); });
        task.setOnFailed(e -> append("⚠ delete failed: " + msg(task)));
        runBg(task, "vault-delete");
    }

    // ======================================================================================
    //  AWS Secrets Manager
    // ======================================================================================

    private final TextField smEndpoint = field("", "endpoint override (blank = real AWS)");
    private final TextField smRegion = field("us-east-1", "region");
    private final TextField smAccess = field("", "access key");
    private final PasswordField smSecret = new PasswordField();
    private final Button smConnect = new Button("Connect");
    private final Label smStatus = meta("Not connected");
    private final TextField smName = field("", "secret name or ARN");
    private final TextArea smValue = new TextArea();
    private final ObservableList<String> smSecrets = FXCollections.observableArrayList();
    private final ListView<String> smList = new ListView<>(smSecrets);

    private Node buildSecretsManagerTab() {
        smSecret.setPromptText("secret key"); smSecret.getStyleClass().add("nl-field");
        smRegion.setPrefWidth(120);
        smConnect.getStyleClass().add("btn-primary");
        smConnect.setOnAction(e -> smConnect());

        HBox row1 = new HBox(8, label("Endpoint:"), grow(smEndpoint), label("Region:"), smRegion);
        HBox row2 = new HBox(8, label("Access:"), grow(smAccess), label("Secret:"), grow(smSecret), smConnect);
        row1.setAlignment(Pos.CENTER_LEFT); row2.setAlignment(Pos.CENTER_LEFT);
        VBox conn = new VBox(8, row1, row2, smStatus);
        conn.setPadding(new Insets(12));

        Button list = secondary("List", e -> smListSecrets());
        Button get = secondary("Get", e -> smGet());
        Button create = primary("Create", e -> smCreate());
        Button put = secondary("Put (new version)", e -> smPut());
        Button versions = secondary("Versions", e -> smVersions());
        Button del = secondary("Delete", e -> smDelete());
        HBox bar = new HBox(8, label("Secret:"), grow(smName), get, create, put, versions, del);
        bar.setAlignment(Pos.CENTER_LEFT);

        smList.setPrefWidth(240);
        smList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> { if (nv != null) smName.setText(nv); });
        smList.setPlaceholder(com.nexuslink.ui.hint.EmptyState.of("s3", "No secrets listed",
                "Connect and press List to enumerate the account's secrets."));
        smValue.getStyleClass().add("code-area");
        smValue.setPromptText("Secret string value (JSON or plain). Get fills it; Create/Put send it.");

        HBox listBar = new HBox(8, label("Secrets:"), secondary("↻", e -> smListSecrets()));
        listBar.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(6, listBar, smList);
        VBox.setVgrow(smList, Priority.ALWAYS);
        SplitPane split = new SplitPane(left, smValue);
        split.setDividerPositions(0.32);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox body = new VBox(10, bar, split);
        body.setPadding(new Insets(0, 12, 12, 12));

        BorderPane pane = new BorderPane(new VBox(new Separator(), body));
        pane.setTop(conn);
        return pane;
    }

    private void smConnect() {
        smConnect.setDisable(true);
        setStatus(smStatus, true, "Connecting…");
        String ep = Env.resolve(smEndpoint.getText().trim()), region = Env.resolve(smRegion.getText().trim());
        String ak = Env.resolve(smAccess.getText().trim()), sk = Env.resolve(smSecret.getText());
        Task<Void> task = new Task<>() {
            @Override protected Void call() { secretsManager.connect(ep, region, ak, sk); return null; }
        };
        task.setOnSucceeded(e -> {
            setStatus(smStatus, true, "Connected — " + (ep.isBlank() ? "AWS " + region : ep));
            smConnect.setDisable(false); append("🔓 Secrets Manager connected");
        });
        task.setOnFailed(e -> { setStatus(smStatus, false, "Connect failed: " + msg(task)); smConnect.setDisable(false); });
        runBg(task, "sm-connect");
    }

    private void smListSecrets() {
        if (!secretsManager.isConnected()) { setStatus(smStatus, false, "Not connected"); return; }
        Task<List<SecretsManagerService.SecretSummary>> task = new Task<>() {
            @Override protected List<SecretsManagerService.SecretSummary> call() { return secretsManager.listSecrets(); }
        };
        task.setOnSucceeded(e -> {
            smSecrets.setAll(task.getValue().stream().map(SecretsManagerService.SecretSummary::name).toList());
            append("📂 " + smSecrets.size() + " secret(s)");
        });
        task.setOnFailed(e -> append("⚠ list failed: " + msg(task)));
        runBg(task, "sm-list");
    }

    private void smGet() {
        if (!smReady()) return;
        String id = Env.resolve(smName.getText().trim());
        Task<String> task = new Task<>() { @Override protected String call() { return secretsManager.getSecretValue(id); } };
        task.setOnSucceeded(e -> { smValue.setText(task.getValue()); append("📖 got " + id); });
        task.setOnFailed(e -> append("⚠ get failed: " + msg(task)));
        runBg(task, "sm-get");
    }

    private void smCreate() {
        if (!smReady()) return;
        String id = Env.resolve(smName.getText().trim()), val = smValue.getText();
        Task<String> task = new Task<>() { @Override protected String call() { return secretsManager.createSecret(id, val); } };
        task.setOnSucceeded(e -> { append("💾 created " + id + "  " + task.getValue()); smListSecrets(); });
        task.setOnFailed(e -> append("⚠ create failed: " + msg(task)));
        runBg(task, "sm-create");
    }

    private void smPut() {
        if (!smReady()) return;
        String id = Env.resolve(smName.getText().trim()), val = smValue.getText();
        Task<String> task = new Task<>() { @Override protected String call() { return secretsManager.putSecretValue(id, val); } };
        task.setOnSucceeded(e -> append("💾 put new version of " + id + "  " + task.getValue()));
        task.setOnFailed(e -> append("⚠ put failed: " + msg(task)));
        runBg(task, "sm-put");
    }

    private void smVersions() {
        if (!smReady()) return;
        String id = Env.resolve(smName.getText().trim());
        Task<List<SecretsManagerService.SecretVersion>> task = new Task<>() {
            @Override protected List<SecretsManagerService.SecretVersion> call() { return secretsManager.listVersions(id); }
        };
        task.setOnSucceeded(e -> {
            append("🕑 " + id + " versions:");
            task.getValue().forEach(v -> append("   " + v.versionId() + "  " + v.stages()));
        });
        task.setOnFailed(e -> append("⚠ versions failed: " + msg(task)));
        runBg(task, "sm-versions");
    }

    private void smDelete() {
        if (!smReady()) return;
        String id = Env.resolve(smName.getText().trim());
        Task<Void> task = new Task<>() { @Override protected Void call() { secretsManager.deleteSecret(id, true); return null; } };
        task.setOnSucceeded(e -> { append("🗑 deleted " + id); smListSecrets(); });
        task.setOnFailed(e -> append("⚠ delete failed: " + msg(task)));
        runBg(task, "sm-delete");
    }

    private boolean smReady() {
        if (!secretsManager.isConnected()) { setStatus(smStatus, false, "Not connected"); return false; }
        if (smName.getText().isBlank()) { append("⚠ enter a secret name"); return false; }
        return true;
    }

    // ======================================================================================
    //  CyberArk Conjur
    // ======================================================================================

    private final TextField cUrl = field("http://localhost:8083", "https://conjur.example.com");
    private final TextField cAccount = field("myConjurAccount", "account");
    private final TextField cLogin = field("admin", "login  (admin or host/…)");
    private final PasswordField cApiKey = new PasswordField();
    private final Button cAuth = new Button("Authenticate");
    private final Label cStatus = meta("Not authenticated");
    private final TextField cVar = field("", "variable id  (e.g. nexus/db/password)");
    private final TextArea cValue = new TextArea();

    private Node buildConjurTab() {
        cApiKey.setPromptText("API key"); cApiKey.getStyleClass().add("nl-field");
        cAccount.setPrefWidth(160); cLogin.setPrefWidth(160);
        cAuth.getStyleClass().add("btn-primary");
        cAuth.setOnAction(e -> conjurAuth());

        HBox row1 = new HBox(8, label("Appliance:"), grow(cUrl), label("Account:"), cAccount);
        HBox row2 = new HBox(8, label("Login:"), cLogin, label("API key:"), grow(cApiKey), cAuth);
        row1.setAlignment(Pos.CENTER_LEFT); row2.setAlignment(Pos.CENTER_LEFT);
        VBox conn = new VBox(8, row1, row2, cStatus);
        conn.setPadding(new Insets(12));

        Button get = primary("Get Secret", e -> conjurGet());
        HBox bar = new HBox(8, label("Variable:"), grow(cVar), get);
        bar.setAlignment(Pos.CENTER_LEFT);
        cValue.setEditable(false);
        cValue.getStyleClass().add("code-area");
        cValue.setPromptText("The variable's value appears here after Get.");

        VBox body = new VBox(10, bar, cValue);
        body.setPadding(new Insets(0, 12, 12, 12));
        VBox.setVgrow(cValue, Priority.ALWAYS);

        BorderPane pane = new BorderPane(new VBox(new Separator(), body));
        pane.setTop(conn);
        return pane;
    }

    private void conjurAuth() {
        String url = Env.resolve(cUrl.getText().trim()), account = Env.resolve(cAccount.getText().trim());
        String login = Env.resolve(cLogin.getText().trim()), key = Env.resolve(cApiKey.getText());
        cAuth.setDisable(true);
        setStatus(cStatus, true, "Authenticating…");
        Task<String> task = new Task<>() {
            @Override protected String call() { return conjur.authenticate(url, account, login, key); }
        };
        task.setOnSucceeded(e -> {
            setStatus(cStatus, true, "Authenticated as " + login + " @ " + account);
            cAuth.setDisable(false); append("🔓 Conjur authenticated as " + login);
            logger.accept("Conjur authenticated " + login + "@" + account);
        });
        task.setOnFailed(e -> { setStatus(cStatus, false, "Auth failed: " + msg(task)); cAuth.setDisable(false); });
        runBg(task, "conjur-auth");
    }

    private void conjurGet() {
        if (!conjur.isConnected()) { setStatus(cStatus, false, "Not authenticated"); return; }
        String var = Env.resolve(cVar.getText().trim());
        if (var.isBlank()) { append("⚠ enter a variable id"); return; }
        Task<String> task = new Task<>() { @Override protected String call() { return conjur.getSecret(var); } };
        task.setOnSucceeded(e -> { cValue.setText(task.getValue()); append("📖 read " + var); });
        task.setOnFailed(e -> append("⚠ get failed: " + msg(task)));
        runBg(task, "conjur-get");
    }

    // ======================================================================================
    //  shared helpers
    // ======================================================================================

    private TableView<Kv> kvTable(ObservableList<Kv> data, String placeholder) {
        TableView<Kv> table = new TableView<>(data);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(placeholder));
        TableColumn<Kv, String> k = new TableColumn<>("Key");
        k.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getKey()));
        k.setCellFactory(TextFieldTableCell.forTableColumn());
        k.setOnEditCommit(ev -> ev.getRowValue().setKey(ev.getNewValue()));
        TableColumn<Kv, String> v = new TableColumn<>("Value");
        v.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getValue()));
        v.setCellFactory(TextFieldTableCell.forTableColumn());
        v.setOnEditCommit(ev -> ev.getRowValue().setValue(ev.getNewValue()));
        table.getColumns().add(k);
        table.getColumns().add(v);
        return table;
    }

    private static java.util.List<Kv> toKvRows(Map<String, String> m) {
        java.util.List<Kv> out = new java.util.ArrayList<>();
        m.forEach((k, v) -> out.add(new Kv(k, v)));
        return out;
    }

    private Tab closed(String title, Node content) { Tab t = new Tab(title, content); t.setClosable(false); return t; }

    private TextField field(String value, String prompt) {
        TextField f = new TextField(value);
        f.setPromptText(prompt);
        f.getStyleClass().add("nl-field");
        return f;
    }

    private static Region grow(Region node) { HBox.setHgrow(node, Priority.ALWAYS); return node; }

    private Label label(String text) { Label l = new Label(text); l.getStyleClass().add("meta-label"); return l; }
    private Label meta(String text) { Label l = new Label(text); l.getStyleClass().add("meta-label"); return l; }

    private Button primary(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text); b.getStyleClass().add("btn-primary"); b.setOnAction(h); return b;
    }
    private Button secondary(String text, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text); b.getStyleClass().add("btn-secondary"); b.setOnAction(h); return b;
    }

    private void setStatus(Label status, boolean ok, String text) {
        status.getStyleClass().setAll(ok ? "status-2xx" : "status-err");
        status.setText(text);
    }

    private static String shorten(String s) { return s == null ? "" : (s.length() > 12 ? s.substring(0, 12) + "…" : s); }
    private static String msg(Task<?> t) { Throwable ex = t.getException(); return ex == null ? "error" : ex.getMessage(); }

    private void append(String line) {
        String stamped = LocalTime.now().format(TIME) + "  " + line + "\n";
        if (Platform.isFxApplicationThread()) activity.appendText(stamped);
        else Platform.runLater(() -> activity.appendText(stamped));
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name); t.setDaemon(true); t.start();
    }

    /** A mutable key/value row for the editable KV table. */
    public static final class Kv {
        private String key, value;
        public Kv(String key, String value) { this.key = key; this.value = value; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
