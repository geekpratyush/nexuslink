package com.nexuslink.ui.cert;

import com.nexuslink.security.cert.CertificateExporter;
import com.nexuslink.security.cert.CertificateGenerator;
import com.nexuslink.security.cert.CertificateStore;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Guided "Certificate Bundle Builder": pick certificates from the working store, order them
 * (leaf → intermediates → root), choose a target format with inline guidance on where each one is
 * used, and write the bundle. Reuses the tested {@link CertificateExporter} for the actual encoding.
 *
 * <p>Formats: a full-chain PEM (web servers), a PKCS#12 with the private key (Java/Windows/clients),
 * a CA trust bundle as concatenated PEM, or a PKCS#12 trust store.
 */
public final class CertificateBundleDialog {

    /** Output formats with human guidance shown live in the dialog. */
    public enum Format {
        FULLCHAIN_PEM("Full-chain PEM (server)",
                """
                A single .pem with your certificate first, then each intermediate, then the root.
                Use for nginx / Apache / HAProxy 'ssl_certificate'. Order matters — put the leaf
                (server) certificate at the top of the list and the root CA at the bottom.
                The private key is NOT included; servers reference it as a separate file."""),
        PKCS12_WITH_KEY("PKCS#12 with private key (.p12 / client)",
                """
                A password-protected .p12/.pfx holding ONE entry's private key plus its certificate
                chain. Use to import into a Java keystore, Windows cert store, a browser, or as a
                client certificate (mTLS). Exactly one selected item must have a private key (the
                leaf); the rest form its chain (leaf first)."""),
        CA_TRUST_PEM("CA trust bundle (PEM)",
                """
                Concatenated CA certificates as PEM — a trust bundle (a 'ca-bundle.crt' / cacerts
                style file). Use to tell a client which CAs to trust. Select the intermediate and
                root CA certificates (order is not significant for trust)."""),
        CA_TRUST_PKCS12("CA trust store (PKCS#12)",
                """
                A password-protected PKCS#12 of certificate-only entries — a trust store. Use where a
                tool wants a .p12 truststore of CAs to trust. Select the CA certificates to include.""");

        final String label;
        final String guidance;
        Format(String label, String guidance) { this.label = label; this.guidance = guidance; }
        @Override public String toString() { return label; }
    }

    private final CertificateStore store;
    private final Window owner;
    private final Consumer<String> logger;

    private final ListView<String> available = new ListView<>();
    private final ListView<String> chain = new ListView<>();
    private final ComboBox<Format> formatCombo = new ComboBox<>();
    private final TextArea guidance = new TextArea();
    private final Label status = new Label();

    public CertificateBundleDialog(CertificateStore store, Window owner, Consumer<String> logger) {
        this.store = store;
        this.owner = owner;
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Builds and shows the dialog. */
    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Build Certificate Bundle");
        dialog.setResizable(true);
        ButtonType buildType = new ButtonType("Build & Save…", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(buildType, ButtonType.CLOSE);

        try {
            available.setItems(FXCollections.observableArrayList(store.aliases()));
        } catch (Exception e) {
            available.setItems(FXCollections.observableArrayList());
        }
        available.setCellFactory(lv -> annotatedCell());
        available.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        chain.setCellFactory(lv -> annotatedCell());

        Button add = arrowButton("Add →", () -> {
            for (String a : new ArrayList<>(available.getSelectionModel().getSelectedItems())) {
                if (!chain.getItems().contains(a)) chain.getItems().add(a);
            }
        });
        Button remove = arrowButton("← Remove", () -> {
            String sel = chain.getSelectionModel().getSelectedItem();
            if (sel != null) chain.getItems().remove(sel);
        });
        Button up = arrowButton("↑ Up", () -> move(-1));
        Button down = arrowButton("↓ Down", () -> move(1));
        VBox buttons = new VBox(8, add, remove, new Separator(), up, down);
        buttons.setAlignment(Pos.CENTER);

        VBox left = new VBox(4, new Label("Available certificates:"), available);
        VBox right = new VBox(4, new Label("Bundle contents (leaf → root):"), chain);
        VBox.setVgrow(available, Priority.ALWAYS);
        VBox.setVgrow(chain, Priority.ALWAYS);
        HBox lists = new HBox(8, left, buttons, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        formatCombo.setItems(FXCollections.observableArrayList(Format.values()));
        formatCombo.setValue(Format.FULLCHAIN_PEM);
        formatCombo.valueProperty().addListener((o, ov, nv) -> updateGuidance());
        guidance.setEditable(false);
        guidance.setWrapText(true);
        guidance.setPrefRowCount(4);
        guidance.getStyleClass().add("meta-label");
        updateGuidance();
        status.getStyleClass().add("meta-label");

        VBox content = new VBox(10,
                new Label("Pick certificates, order them, choose a format, then Build & Save."),
                lists,
                new HBox(8, new Label("Format:"), formatCombo),
                guidance, status);
        content.setPadding(new Insets(12));
        VBox.setVgrow(lists, Priority.ALWAYS);
        content.setPrefSize(720, 520);
        dialog.getDialogPane().setContent(content);

        // Intercept Build so a validation failure keeps the dialog open.
        Button buildBtn = (Button) dialog.getDialogPane().lookupButton(buildType);
        buildBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> { ev.consume(); build(); });

        dialog.showAndWait();
    }

    private void updateGuidance() {
        guidance.setText(formatCombo.getValue().guidance);
    }

    private void move(int delta) {
        int i = chain.getSelectionModel().getSelectedIndex();
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= chain.getItems().size()) return;
        String item = chain.getItems().remove(i);
        chain.getItems().add(j, item);
        chain.getSelectionModel().select(j);
    }

    private void build() {
        List<String> aliases = chain.getItems();
        if (aliases.isEmpty()) { setError("Add at least one certificate to the bundle"); return; }
        Format format = formatCombo.getValue();
        try {
            List<X509Certificate> certs = new ArrayList<>();
            for (String a : aliases) certs.add(store.getCertificate(a));

            switch (format) {
                case FULLCHAIN_PEM, CA_TRUST_PEM -> {
                    java.io.File file = save(format == Format.FULLCHAIN_PEM ? "fullchain.pem" : "ca-bundle.pem",
                            new FileChooser.ExtensionFilter("PEM", "*.pem", "*.crt"));
                    if (file == null) return;
                    Files.writeString(file.toPath(), CertificateExporter.pemBundle(certs));
                    done(file.getName(), certs.size());
                }
                case PKCS12_WITH_KEY -> {
                    String keyAlias = firstWithKey(aliases);
                    if (keyAlias == null) {
                        setError("PKCS#12 with key needs one selected entry that has a private key (the leaf).");
                        return;
                    }
                    java.io.File file = save(safe(keyAlias) + ".p12",
                            new FileChooser.ExtensionFilter("PKCS#12", "*.p12", "*.pfx"));
                    if (file == null) return;
                    var pw = password("Set a password for the PKCS#12 bundle:");
                    if (pw.isEmpty()) return;
                    PrivateKey key = store.getKey(keyAlias);
                    byte[] p12 = CertificateExporter.toPkcs12(keyAlias, key, pw.get().toCharArray(), certs);
                    Files.write(file.toPath(), p12);
                    done(file.getName(), certs.size());
                }
                case CA_TRUST_PKCS12 -> {
                    java.io.File file = save("truststore.p12",
                            new FileChooser.ExtensionFilter("PKCS#12", "*.p12"));
                    if (file == null) return;
                    var pw = password("Set a password for the trust store:");
                    if (pw.isEmpty()) return;
                    byte[] p12 = CertificateExporter.toPkcs12TrustStore("ca", pw.get().toCharArray(), certs);
                    Files.write(file.toPath(), p12);
                    done(file.getName(), certs.size());
                }
            }
        } catch (Exception e) {
            setError("Bundle failed: " + e.getMessage());
        }
    }

    private String firstWithKey(List<String> aliases) throws Exception {
        for (String a : aliases) if (store.hasKey(a)) return a;
        return null;
    }

    private java.io.File save(String suggestedName, FileChooser.ExtensionFilter filter) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Bundle");
        fc.setInitialFileName(suggestedName);
        fc.getExtensionFilters().add(filter);
        return fc.showSaveDialog(owner);
    }

    private java.util.Optional<String> password(String prompt) {
        TextInputDialog d = new TextInputDialog();
        if (owner != null) d.initOwner(owner);
        d.setTitle("Bundle password");
        d.setHeaderText(prompt);
        return d.showAndWait().filter(s -> !s.isBlank());
    }

    private void done(String fileName, int count) {
        setOk("Built bundle with " + count + " certificate(s) → " + fileName);
        logger.accept("Built certificate bundle (" + formatCombo.getValue().label + ", "
                + count + " certs) → " + fileName);
    }

    private ListCell<String> annotatedCell() {
        return new ListCell<>() {
            @Override protected void updateItem(String alias, boolean empty) {
                super.updateItem(alias, empty);
                if (empty || alias == null) { setText(null); return; }
                String suffix = "";
                try { suffix = store.hasKey(alias) ? "  (has key)" : "  (cert)"; } catch (Exception ignored) {}
                setText(alias + suffix);
            }
        };
    }

    private Button arrowButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-secondary");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void setError(String msg) { status.getStyleClass().setAll("status-err"); status.setText(msg); }
    private void setOk(String msg) { status.getStyleClass().setAll("status-2xx"); status.setText(msg); }

    private static String safe(String name) {
        return name == null ? "bundle" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
