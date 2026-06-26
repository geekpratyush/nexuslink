package com.nexuslink.ui.rest;

import com.nexuslink.protocol.http.rest.OAuth2AuthorizationCode;
import com.nexuslink.ui.env.Env;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.awt.Desktop;
import java.net.URI;
import java.util.Optional;

/**
 * Interactive OAuth 2.0 <em>Authorization Code + PKCE</em> dialog. The user fills in the endpoints
 * and client details, clicks <b>Open authorization URL</b> (NexusLink generates the PKCE pair +
 * state and launches the browser), approves access, pastes the redirect URL back, and clicks
 * <b>Exchange for token</b>. On success the dialog returns the access token, which the REST client
 * applies as a Bearer token. All crypto/URL logic lives in {@link OAuth2AuthorizationCode}.
 */
public final class OAuth2AuthCodeDialog {

    private final Dialog<String> dialog = new Dialog<>();

    private final TextField authEndpoint = new TextField();
    private final TextField tokenEndpoint = new TextField();
    private final TextField clientId = new TextField();
    private final PasswordField clientSecret = new PasswordField();
    private final TextField redirectUri = new TextField("http://localhost:8080/callback");
    private final TextField scope = new TextField();
    private final TextArea authUrlArea = new TextArea();
    private final TextField redirectResult = new TextField();
    private final Label status = new Label();

    private OAuth2AuthorizationCode.Pkce pkce;
    private String state;

    public OAuth2AuthCodeDialog(String tokenUrl, String clientIdValue, String clientSecretValue, String scopeValue) {
        tokenEndpoint.setText(nullToEmpty(tokenUrl));
        clientId.setText(nullToEmpty(clientIdValue));
        clientSecret.setText(nullToEmpty(clientSecretValue));
        scope.setText(nullToEmpty(scopeValue));
        build();
    }

    /** Shows the dialog modally; returns the access token if the exchange succeeded. */
    public Optional<String> showAndWait() {
        return dialog.showAndWait();
    }

    private void build() {
        dialog.setTitle("OAuth 2.0 — Authorization Code + PKCE");
        dialog.setHeaderText("Authorize in the browser, then paste the redirect URL to get a token.");
        dialog.setResizable(true);

        ButtonType exchangeType = new ButtonType("Exchange for token", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exchangeType, ButtonType.CANCEL);

        for (TextField f : new TextField[]{authEndpoint, tokenEndpoint, clientId, redirectUri, scope, redirectResult}) {
            f.getStyleClass().add("nl-field");
            f.setPrefWidth(420);
        }
        clientSecret.getStyleClass().add("nl-field");
        clientSecret.setPrefWidth(420);
        clientSecret.setPromptText("blank for a public (PKCE-only) client");
        authEndpoint.setPromptText("https://auth.example.com/authorize");
        tokenEndpoint.setPromptText("https://auth.example.com/oauth/token");
        scope.setPromptText("optional, space-separated");
        redirectResult.setPromptText("paste the full redirect URL here after approving");

        authUrlArea.getStyleClass().add("code-area");
        authUrlArea.setEditable(false);
        authUrlArea.setWrapText(true);
        authUrlArea.setPrefRowCount(2);
        authUrlArea.setPromptText("the authorization URL appears here…");

        Button openBtn = new Button("Open authorization URL");
        openBtn.getStyleClass().add("btn-secondary");
        openBtn.setOnAction(e -> openAuthorization());

        status.getStyleClass().add("meta-label");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int r = 0;
        grid.addRow(r++, meta("Authorization URL:"), authEndpoint);
        grid.addRow(r++, meta("Token URL:"), tokenEndpoint);
        grid.addRow(r++, meta("Client ID:"), clientId);
        grid.addRow(r++, meta("Client secret:"), clientSecret);
        grid.addRow(r++, meta("Redirect URI:"), redirectUri);
        grid.addRow(r++, meta("Scope:"), scope);
        grid.add(openBtn, 1, r++);
        grid.addRow(r++, meta("Auth URL:"), authUrlArea);
        grid.addRow(r++, meta("Redirect URL:"), redirectResult);
        grid.add(status, 1, r);
        GridPane.setHgrow(authEndpoint, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        // Intercept the exchange button so we can run network I/O without closing on failure.
        final Button exchangeBtn = (Button) dialog.getDialogPane().lookupButton(exchangeType);
        exchangeBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();   // never close via the button; we close ourselves on success
            exchange();
        });
    }

    private void openAuthorization() {
        String auth = Env.resolve(authEndpoint.getText().trim());
        String client = Env.resolve(clientId.getText().trim());
        if (auth.isEmpty() || client.isEmpty()) {
            setError("Enter the authorization URL and client ID first");
            return;
        }
        pkce = OAuth2AuthorizationCode.createPkce();
        state = Long.toHexString(System.nanoTime());
        String url = OAuth2AuthorizationCode.buildAuthorizationUrl(
                auth, client, Env.resolve(redirectUri.getText().trim()),
                Env.resolve(scope.getText().trim()), state, pkce);
        authUrlArea.setText(url);
        setInfo("Opening browser… approve access, then paste the redirect URL below.");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                setInfo("Copy the Auth URL into a browser, approve, then paste the redirect URL below.");
            }
        } catch (Exception ex) {
            setInfo("Couldn't auto-open a browser — copy the Auth URL manually. (" + ex.getMessage() + ")");
        }
    }

    private void exchange() {
        if (pkce == null) { setError("Click \"Open authorization URL\" first"); return; }
        String redirect = redirectResult.getText().trim();
        if (redirect.isEmpty()) { setError("Paste the redirect URL you were sent to"); return; }

        OAuth2AuthorizationCode.AuthCodeResult parsed = OAuth2AuthorizationCode.parseRedirect(redirect);
        if (parsed.isError()) {
            setError("Authorization failed: " + parsed.error()
                    + (parsed.errorDescription() == null ? "" : " — " + parsed.errorDescription()));
            return;
        }
        if (parsed.code() == null || parsed.code().isBlank()) { setError("No authorization code in that URL"); return; }
        if (state != null && parsed.state() != null && !state.equals(parsed.state())) {
            setError("State mismatch — possible CSRF; restart the flow");
            return;
        }

        setInfo("Exchanging code for a token…");
        String tokenUrl = Env.resolve(tokenEndpoint.getText().trim());
        String client = Env.resolve(clientId.getText().trim());
        String secret = Env.resolve(clientSecret.getText());
        String redirectUriValue = Env.resolve(redirectUri.getText().trim());
        String code = parsed.code();
        String verifier = pkce.verifier();

        Task<OAuth2AuthorizationCode.TokenResponse> task = new Task<>() {
            @Override protected OAuth2AuthorizationCode.TokenResponse call() throws Exception {
                return OAuth2AuthorizationCode.exchangeCode(tokenUrl, client, secret, redirectUriValue, code, verifier);
            }
        };
        task.setOnSucceeded(e -> {
            OAuth2AuthorizationCode.TokenResponse token = task.getValue();
            dialog.setResult(token.accessToken());
            dialog.close();
        });
        task.setOnFailed(e -> setError("Token exchange failed: " + task.getException().getMessage()));
        Thread t = new Thread(task, "oauth-exchange");
        t.setDaemon(true);
        t.start();
    }

    private Label meta(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void setError(String msg) { status.getStyleClass().setAll("status-err"); status.setText(msg); }
    private void setInfo(String msg) { status.getStyleClass().setAll("meta-label"); status.setText(msg); }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
