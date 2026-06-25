package com.nexuslink.ui.cert;

import com.nexuslink.security.cert.CertificateGenerator;
import com.nexuslink.security.cert.CertificateInfo;
import com.nexuslink.security.cert.CertificateParser;
import com.nexuslink.security.cert.CertificateStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/**
 * Certificate Manager tab — generate self-signed certificates (RSA/ECDSA), import certs from
 * PEM/DER files, view their parsed X.509 details with a colour-coded validity status, export to
 * PEM, and persist the working set to a password-protected PKCS12/JKS keystore.
 */
public final class CertificateManagerView extends BorderPane {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private CertificateStore store;
    private final ListView<String> aliasList = new ListView<>();
    private final TextArea details = new TextArea();
    private final Label statusLabel = new Label();

    private Consumer<String> logger = s -> {};

    public CertificateManagerView() {
        getStyleClass().add("cert-view");
        try {
            store = CertificateStore.createEmpty("PKCS12", storePassword());
        } catch (Exception e) {
            store = null;
        }
        setTop(buildToolbar());
        setCenter(buildBody());
        refresh();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Session keystore password — the store lives in memory unless saved to disk. */
    private static char[] storePassword() {
        return "nexuslink".toCharArray();
    }

    private VBox buildToolbar() {
        Button genBtn = new Button("Generate Self-Signed…");
        genBtn.getStyleClass().add("btn-primary");
        genBtn.setOnAction(e -> generateDialog());

        Button importBtn = new Button("Import…");
        importBtn.getStyleClass().add("btn-secondary");
        importBtn.setOnAction(e -> importCertificate());

        Button exportBtn = new Button("Export PEM…");
        exportBtn.getStyleClass().add("btn-secondary");
        exportBtn.setOnAction(e -> exportSelected());

        Button deleteBtn = new Button("Delete");
        deleteBtn.getStyleClass().add("btn-secondary");
        deleteBtn.setOnAction(e -> deleteSelected());

        Button openBtn = new Button("Open Store…");
        openBtn.getStyleClass().add("btn-secondary");
        openBtn.setOnAction(e -> openStore());

        Button saveBtn = new Button("Save Store…");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> saveStore());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("certificate-manager"));

        HBox row = new HBox(8, genBtn, importBtn, exportBtn, deleteBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), openBtn, saveBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        aliasList.setMinWidth(220);
        aliasList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String alias, boolean empty) {
                super.updateItem(alias, empty);
                getStyleClass().removeAll("status-2xx", "status-4xx", "status-err");
                if (empty || alias == null) { setText(null); return; }
                setText(alias);
                try {
                    CertificateInfo info = store.info(alias);
                    if (info != null) getStyleClass().add(statusClass(info.status()));
                } catch (Exception ignored) {
                    // leave uncoloured if it can't be read
                }
            }
        });
        aliasList.getSelectionModel().selectedItemProperty().addListener((o, ov, alias) -> showDetails(alias));

        details.getStyleClass().add("code-area");
        details.setEditable(false);
        details.setPromptText("Select a certificate to view its details, or generate/import one.");

        SplitPane sp = new SplitPane(aliasList, details);
        sp.setDividerPositions(0.3);
        return sp;
    }

    private static String statusClass(CertificateInfo.Status status) {
        return switch (status) {
            case VALID -> "status-2xx";
            case EXPIRING_SOON, NOT_YET_VALID -> "status-4xx";
            case EXPIRED -> "status-err";
        };
    }

    private void refresh() {
        try {
            String selected = aliasList.getSelectionModel().getSelectedItem();
            aliasList.getItems().setAll(store.aliases());
            statusLabel.setText(store.size() + " certificate(s) in the working store");
            if (selected != null && aliasList.getItems().contains(selected)) {
                aliasList.getSelectionModel().select(selected);
            }
        } catch (Exception e) {
            statusLabel.setText("Store error: " + e.getMessage());
        }
    }

    private void showDetails(String alias) {
        if (alias == null) { details.clear(); return; }
        try {
            CertificateInfo info = store.info(alias);
            boolean hasKey = store.hasKey(alias);
            if (info == null) { details.setText("(no certificate under '" + alias + "')"); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("Alias            : ").append(alias).append(hasKey ? "  (has private key)" : "  (trusted cert)").append('\n');
            sb.append("Status           : ").append(info.status())
                    .append("  (").append(info.daysUntilExpiry()).append(" days until expiry)\n");
            sb.append("Common Name      : ").append(info.commonName()).append('\n');
            sb.append("Subject          : ").append(info.subject()).append('\n');
            sb.append("Issuer           : ").append(info.issuer())
                    .append(info.selfSigned() ? "  (self-signed)" : "").append('\n');
            sb.append("Serial           : ").append(info.serialNumber()).append('\n');
            sb.append("Valid From       : ").append(STAMP.format(info.notBefore())).append('\n');
            sb.append("Valid Until      : ").append(STAMP.format(info.notAfter())).append('\n');
            sb.append("Key              : ").append(info.keyAlgorithm()).append(' ').append(info.keySize()).append("-bit\n");
            sb.append("Signature        : ").append(info.signatureAlgorithm()).append('\n');
            sb.append("CA Certificate   : ").append(info.certAuthority() ? "yes" : "no").append('\n');
            if (!info.subjectAltNames().isEmpty()) {
                sb.append("Subject Alt Names: ").append(String.join(", ", info.subjectAltNames())).append('\n');
            }
            sb.append("SHA-256          : ").append(info.sha256Fingerprint()).append('\n');
            details.setText(sb.toString());
        } catch (Exception e) {
            details.setText("Error reading '" + alias + "': " + e.getMessage());
        }
    }

    private void generateDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Generate Self-Signed Certificate");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField cn = new TextField("localhost");
        TextField org = new TextField("NexusLink");
        ComboBox<CertificateGenerator.KeyType> keyType = new ComboBox<>();
        keyType.getItems().addAll(CertificateGenerator.KeyType.values());
        keyType.setValue(CertificateGenerator.KeyType.RSA_2048);
        TextField days = new TextField("365");
        TextField sans = new TextField("localhost, IP:127.0.0.1");

        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(8); g.setPadding(new Insets(12));
        g.addRow(0, new Label("Common Name:"), cn);
        g.addRow(1, new Label("Organization:"), org);
        g.addRow(2, new Label("Key type:"), keyType);
        g.addRow(3, new Label("Validity (days):"), days);
        g.addRow(4, new Label("SANs (comma-sep):"), sans);
        dialog.getDialogPane().setContent(g);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            long validDays = Long.parseLong(days.getText().trim());
            List<String> sanList = java.util.Arrays.stream(sans.getText().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            var gen = CertificateGenerator.generate(cn.getText().trim(), org.getText().trim(),
                    keyType.getValue(), Duration.ofDays(validDays), sanList);
            String alias = uniqueAlias(cn.getText().trim().isEmpty() ? "cert" : cn.getText().trim());
            store.importKeyPair(alias, gen.privateKey(), gen.certificate());
            logger.accept("Generated self-signed certificate '" + alias + "' (" + keyType.getValue() + ")");
            refresh();
            aliasList.getSelectionModel().select(alias);
        } catch (Exception e) {
            error("Generate failed", e.getMessage());
        }
    }

    private void importCertificate() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import Certificate (PEM/DER)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Certificates", "*.pem", "*.crt", "*.cer", "*.der"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File file = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            X509Certificate cert = CertificateParser.load(data);
            String alias = uniqueAlias(CertificateParser.toInfo(cert).commonName());
            store.importCertificate(alias, cert);
            logger.accept("Imported certificate '" + alias + "' from " + file.getName());
            refresh();
            aliasList.getSelectionModel().select(alias);
        } catch (Exception e) {
            error("Import failed", e.getMessage());
        }
    }

    private void exportSelected() {
        String alias = aliasList.getSelectionModel().getSelectedItem();
        if (alias == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Certificate as PEM");
        fc.setInitialFileName(alias + ".pem");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PEM", "*.pem"));
        java.io.File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            String pem = CertificateGenerator.toPem(store.getCertificate(alias));
            Files.writeString(file.toPath(), pem);
            logger.accept("Exported '" + alias + "' → " + file.getName());
            statusLabel.setText("Exported '" + alias + "' to " + file.getName());
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    private void deleteSelected() {
        String alias = aliasList.getSelectionModel().getSelectedItem();
        if (alias == null) return;
        try {
            store.delete(alias);
            logger.accept("Deleted certificate '" + alias + "'");
            details.clear();
            refresh();
        } catch (Exception e) {
            error("Delete failed", e.getMessage());
        }
    }

    private void openStore() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Keystore");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Keystore", "*.p12", "*.pfx", "*.jks"));
        java.io.File file = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        passwordPrompt("Open Keystore", "Keystore password:").ifPresent(pw -> {
            try {
                String type = file.getName().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
                store = CertificateStore.load(file.toPath(), type, pw.toCharArray());
                logger.accept("Opened keystore " + file.getName());
                refresh();
            } catch (Exception e) {
                error("Open failed", e.getMessage());
            }
        });
    }

    private void saveStore() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Keystore");
        fc.setInitialFileName("certificates.p12");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PKCS12", "*.p12"));
        java.io.File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        passwordPrompt("Save Keystore", "Set a keystore password:").ifPresent(pw -> {
            try {
                // Re-key the in-memory entries into a store with the chosen password, then save.
                // Key entries keep their private key + chain; trusted certs save as-is.
                CertificateStore out = CertificateStore.createEmpty("PKCS12", pw.toCharArray());
                for (String alias : store.aliases()) {
                    if (store.hasKey(alias)) {
                        out.importKeyPair(alias, store.getKey(alias), store.getChain(alias));
                    } else {
                        out.importCertificate(alias, store.getCertificate(alias));
                    }
                }
                out.save(file.toPath());
                logger.accept("Saved keystore → " + file.getName());
                statusLabel.setText("Saved " + store.aliases().size() + " certificate(s) to " + file.getName());
            } catch (Exception e) {
                error("Save failed", e.getMessage());
            }
        });
    }

    private java.util.Optional<String> passwordPrompt(String title, String label) {
        TextInputDialog d = new TextInputDialog();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(label);
        return d.showAndWait();
    }

    private String uniqueAlias(String base) throws Exception {
        String candidate = base.isBlank() ? "cert" : base;
        int n = 1;
        java.util.List<String> existing = store.aliases();
        String alias = candidate;
        while (existing.contains(alias)) alias = candidate + "-" + (++n);
        return alias;
    }

    private void error(String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        if (getScene() != null) a.initOwner(getScene().getWindow());
        a.setHeaderText(title);
        a.showAndWait();
    }
}
