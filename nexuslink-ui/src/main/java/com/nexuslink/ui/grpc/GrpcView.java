package com.nexuslink.ui.grpc;

import com.nexuslink.protocol.grpc.GrpcService;
import com.nexuslink.ui.env.Env;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.util.List;
import java.util.function.Consumer;

/**
 * gRPC client tab — connect to a server with <b>reflection</b> enabled, pick a service + method,
 * edit the request as JSON, and invoke (unary). No {@code .proto} file needed.
 */
public final class GrpcView extends BorderPane {

    private final GrpcService service = new GrpcService();

    private final TextField hostField = new TextField("grpcb.in");
    private final TextField portField = new TextField("9000");
    private final CheckBox tlsBox = new CheckBox("TLS");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // TLS / mTLS material (shown when TLS is enabled)
    private final TextField tlsTrustStore = new TextField();
    private final PasswordField tlsTrustStorePw = new PasswordField();
    private final TextField tlsKeyStore = new TextField();
    private final PasswordField tlsKeyStorePw = new PasswordField();
    private final CheckBox tlsTrustAll = new CheckBox("Trust all certificates (insecure)");

    private final ComboBox<String> serviceCombo = new ComboBox<>();
    private final ComboBox<GrpcService.MethodInfo> methodCombo = new ComboBox<>();
    private final TextArea requestEditor = new TextArea();
    private final org.fxmisc.richtext.CodeArea responseArea = com.nexuslink.ui.util.JsonView.plainArea(false);
    private final Label callStatus = new Label();

    private Consumer<String> logger = s -> {};

    public GrpcView() {
        getStyleClass().add("grpc-view");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills host:port (used when opening a saved/sample connection). */
    public void prefill(String target) {
        if (target == null || target.isBlank()) return;
        String t = target.replaceFirst("^grpc[s]?://", "");
        int colon = t.lastIndexOf(':');
        if (colon > 0) {
            hostField.setText(t.substring(0, colon));
            portField.setText(t.substring(colon + 1));
        } else {
            hostField.setText(t);
        }
    }

    private VBox buildBar() {
        hostField.getStyleClass().add("nl-field");
        HBox.setHgrow(hostField, Priority.ALWAYS);
        portField.getStyleClass().add("nl-field");
        portField.setPrefWidth(90);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("rest-client"));

        Label h = new Label("Host:"); h.getStyleClass().add("meta-label");
        Label p = new Label("Port:"); p.getStyleClass().add("meta-label");
        HBox row = new HBox(8, h, hostField, p, portField, tlsBox, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));

        // TLS / mTLS material — only relevant when TLS is on.
        for (TextField f : new TextField[]{tlsTrustStore, tlsKeyStore}) { f.getStyleClass().add("nl-field"); HBox.setHgrow(f, Priority.ALWAYS); }
        for (PasswordField f : new PasswordField[]{tlsTrustStorePw, tlsKeyStorePw}) { f.getStyleClass().add("nl-field"); f.setPrefWidth(140); }
        tlsTrustStore.setPromptText("CA trust store (.p12/.jks) — verifies the server");
        tlsKeyStore.setPromptText("client key store (.p12/.jks) — for mutual TLS");
        tlsTrustStorePw.setPromptText("password");
        tlsKeyStorePw.setPromptText("password");
        Label tsl = new Label("Trust store:"); tsl.getStyleClass().add("meta-label");
        Label ksl = new Label("Client key store:"); ksl.getStyleClass().add("meta-label");
        HBox tsRow = new HBox(8, tsl, tlsTrustStore, browseStore(tlsTrustStore), tlsTrustStorePw);
        HBox ksRow = new HBox(8, ksl, tlsKeyStore, browseStore(tlsKeyStore), tlsKeyStorePw);
        tsRow.setAlignment(Pos.CENTER_LEFT); ksRow.setAlignment(Pos.CENTER_LEFT);
        VBox tlsBoxPane = new VBox(6, tsRow, ksRow, tlsTrustAll);
        tlsBoxPane.setPadding(new Insets(0, 10, 6, 10));
        tlsBoxPane.visibleProperty().bind(tlsBox.selectedProperty());
        tlsBoxPane.managedProperty().bind(tlsBox.selectedProperty());
        return new VBox(row, tlsBoxPane, statusRow);
    }

    /** A "Browse…" button that fills {@code target} with a chosen keystore file path. */
    private Button browseStore(TextField target) {
        Button b = new Button("Browse…");
        b.getStyleClass().add("btn-secondary");
        b.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Choose keystore");
            fc.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter("Keystore", "*.p12", "*.pfx", "*.jks"),
                    new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"));
            java.io.File f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) target.setText(f.getAbsolutePath());
        });
        return b;
    }

    /** Builds a {@link com.nexuslink.security.tls.TlsConfig} from the TLS fields (paths ${VAR}-resolved). */
    private com.nexuslink.security.tls.TlsConfig tlsConfig() {
        return new com.nexuslink.security.tls.TlsConfig(
                blankToNull(Env.resolve(tlsTrustStore.getText().trim())), pw(tlsTrustStorePw), null,
                blankToNull(Env.resolve(tlsKeyStore.getText().trim())), pw(tlsKeyStorePw), null,
                tlsTrustAll.isSelected());
    }

    private static char[] pw(PasswordField f) {
        String t = f.getText();
        return t == null || t.isEmpty() ? null : t.toCharArray();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private SplitPane buildBody() {
        serviceCombo.setMaxWidth(Double.MAX_VALUE);
        serviceCombo.valueProperty().addListener((o, ov, svc) -> { if (svc != null) loadMethods(svc); });
        methodCombo.setMaxWidth(Double.MAX_VALUE);
        methodCombo.setConverter(new StringConverter<>() {
            @Override public String toString(GrpcService.MethodInfo m) {
                return m == null ? "" : m.name() + (m.isUnary() ? "" : "  (streaming)");
            }
            @Override public GrpcService.MethodInfo fromString(String s) { return null; }
        });
        methodCombo.valueProperty().addListener((o, ov, m) -> { if (m != null) requestEditor.setText(m.requestTemplate()); });

        Button invokeBtn = new Button("Invoke");
        invokeBtn.getStyleClass().add("btn-primary");
        invokeBtn.setOnAction(e -> invoke());
        callStatus.getStyleClass().add("meta-label");

        VBox left = new VBox(6,
                section("SERVICE"), serviceCombo,
                section("METHOD"), methodCombo,
                section("REQUEST (JSON)"), requestEditor,
                new HBox(8, invokeBtn, callStatus));
        left.setPadding(new Insets(8));
        requestEditor.getStyleClass().add("code-area");
        requestEditor.setPromptText("{ }");
        VBox.setVgrow(requestEditor, Priority.ALWAYS);

        org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> responseScroll =
                new org.fxmisc.flowless.VirtualizedScrollPane<>(responseArea);
        VBox right = new VBox(6, section("RESPONSE"), responseScroll);
        right.setPadding(new Insets(8));
        VBox.setVgrow(responseScroll, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setOrientation(Orientation.HORIZONTAL);
        sp.setDividerPositions(0.45);
        return sp;
    }

    private Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-title");
        return l;
    }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        int port = parsePort();
        String host = Env.resolve(hostField.getText().trim());   // resolve ${VAR} against active environment
        logger.accept("gRPC connect → " + host + ":" + port);
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception {
                service.connect(host, port, tlsBox.isSelected(), tlsBox.isSelected() ? tlsConfig() : null);
                return service.listServices();
            }
        };
        task.setOnSucceeded(e -> {
            List<String> services = task.getValue();
            serviceCombo.setItems(FXCollections.observableArrayList(services));
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + services.size() + " service(s) via reflection");
            logger.accept("gRPC connected — " + services.size() + " services");
            if (!services.isEmpty()) serviceCombo.setValue(services.get(0));
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("gRPC connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    private void loadMethods(String svc) {
        Task<List<GrpcService.MethodInfo>> task = new Task<>() {
            @Override protected List<GrpcService.MethodInfo> call() throws Exception { return service.listMethods(svc); }
        };
        task.setOnSucceeded(e -> {
            methodCombo.setItems(FXCollections.observableArrayList(task.getValue()));
            if (!task.getValue().isEmpty()) methodCombo.setValue(task.getValue().get(0));
        });
        task.setOnFailed(e -> callStatus.setText("✖ " + task.getException().getMessage()));
        runBg(task);
    }

    private void invoke() {
        String svc = serviceCombo.getValue();
        GrpcService.MethodInfo method = methodCombo.getValue();
        if (svc == null || method == null) { callStatus.setText("Pick a service and method"); return; }
        if (!method.isUnary()) {
            callStatus.getStyleClass().setAll("status-4xx");
            callStatus.setText("Streaming methods are not supported yet");
            return;
        }
        callStatus.getStyleClass().setAll("meta-label");
        callStatus.setText("Invoking…");
        String json = Env.resolve(requestEditor.getText());   // resolve ${VAR} in the request body
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return service.invokeUnary(svc, method.name(), json); }
        };
        task.setOnSucceeded(e -> {
            com.nexuslink.ui.util.JsonView.setSmart(responseArea, task.getValue());
            callStatus.getStyleClass().setAll("status-2xx");
            callStatus.setText("OK");
            logger.accept("gRPC " + svc + "/" + method.name() + " ok");
        });
        task.setOnFailed(e -> {
            callStatus.getStyleClass().setAll("status-err");
            callStatus.setText("✖ " + task.getException().getMessage());
            com.nexuslink.ui.util.JsonView.setSmart(responseArea, "Error: " + task.getException().getMessage());
        });
        runBg(task);
    }

    private int parsePort() {
        try { return Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { return tlsBox.isSelected() ? 443 : 80; }
    }

    private void runBg(Task<?> task) {
        Thread t = new Thread(task, "grpc-task");
        t.setDaemon(true);
        t.start();
    }
}
