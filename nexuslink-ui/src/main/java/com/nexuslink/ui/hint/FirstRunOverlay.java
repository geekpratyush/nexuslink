package com.nexuslink.ui.hint;

import com.nexuslink.ui.help.HelpDialog;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * A step-by-step first-run onboarding overlay: a dimmed scrim with a centred card that walks the user
 * through the app in a few slides, with <b>Back / Next</b>, a <b>Skip</b> escape, and a
 * <b>Don't show this again</b> checkbox. Shown once (persisted via {@link OnboardingPrefs}); can be
 * re-opened on demand from Help ("Welcome tour").
 * <p>
 * It installs itself into a {@link StackPane} that sits over the app content and removes itself when
 * finished or skipped.
 */
public final class FirstRunOverlay extends StackPane {

    /** One onboarding slide. */
    public record Step(String title, String body, String helpTarget) {}

    private static final List<Step> DEFAULT_STEPS = List.of(
        new Step("Welcome to NexusLink",
                "The universal protocol workbench — REST, Kafka, gRPC, databases, files, cloud and "
                        + "more, in one place. This 30-second tour shows you around.", "getting-started"),
        new Step("Pick a protocol",
                "Use the File menu or the left sidebar buttons to open a new tab for any protocol. "
                        + "Only see what you use? View → Protocols… hides the rest.", "getting-started"),
        new Step("Connections & the Vault",
                "Save connection profiles in the left panel. Secrets (passwords, tokens, keys) go into "
                        + "the encrypted Vault automatically — unlock it from the status bar.", "security#credential-vault"),
        new Step("Environments & variables",
                "Define ${VAR} values per environment (dev/stage/prod) and reference them in any field. "
                        + "Switch environments to re-point every request at once.", "environment-vars"),
        new Step("Help is everywhere",
                "Press F1 on any field for contextual help, click the ? button on a panel, or open the "
                        + "full Help index from the Help menu. You're all set!", "getting-started"));

    private final OnboardingPrefs prefs;
    private final List<Step> steps;
    private final Runnable onDone;
    private int current = 0;

    private final Label stepTitle = new Label();
    private final Label stepBody = new Label();
    private final Label progress = new Label();
    private final CheckBox dontShow = new CheckBox("Don't show this again");
    private final Button backBtn = new Button("Back");
    private final Button nextBtn = new Button("Next");
    private final javafx.scene.control.Hyperlink learnMore = new javafx.scene.control.Hyperlink("Learn more →");

    private FirstRunOverlay(OnboardingPrefs prefs, List<Step> steps, Runnable onDone) {
        this.prefs = prefs;
        this.steps = steps;
        this.onDone = onDone;
        buildUi();
        render();
    }

    /**
     * Shows the onboarding overlay inside {@code container} <em>only</em> if it hasn't been dismissed
     * before. No-op otherwise. Returns the overlay if shown, else null.
     */
    public static FirstRunOverlay maybeShow(StackPane container) {
        OnboardingPrefs prefs = new OnboardingPrefs();
        if (!prefs.shouldShowOnStartup()) return null;
        return show(container, prefs, DEFAULT_STEPS);
    }

    /** Force-show the tour (from Help), regardless of the dismissed flag. */
    public static FirstRunOverlay showNow(StackPane container) {
        return show(container, new OnboardingPrefs(), DEFAULT_STEPS);
    }

    private static FirstRunOverlay show(StackPane container, OnboardingPrefs prefs, List<Step> steps) {
        FirstRunOverlay overlay = new FirstRunOverlay(prefs, steps, () -> {});
        container.getChildren().add(overlay);
        FadeTransition fade = new FadeTransition(Duration.millis(180), overlay);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
        return overlay;
    }

    private void buildUi() {
        getStyleClass().add("onboarding-scrim");
        setAlignment(Pos.CENTER);

        stepTitle.getStyleClass().add("onboarding-title");
        stepBody.getStyleClass().add("onboarding-body");
        stepBody.setWrapText(true);
        stepBody.setMaxWidth(420);
        progress.getStyleClass().add("onboarding-progress");

        Button skip = new Button("Skip");
        skip.getStyleClass().add("btn-secondary");
        skip.setOnAction(e -> finish());

        backBtn.getStyleClass().add("btn-secondary");
        backBtn.setOnAction(e -> { if (current > 0) { current--; render(); } });
        nextBtn.getStyleClass().add("btn-primary");
        nextBtn.setDefaultButton(true);
        nextBtn.setOnAction(e -> {
            if (current < steps.size() - 1) { current++; render(); }
            else finish();
        });

        HBox nav = new HBox(8, backBtn, nextBtn);
        nav.setAlignment(Pos.CENTER_RIGHT);
        Region navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);

        HBox footer = new HBox(8, dontShow, navSpacer, nav);
        footer.setAlignment(Pos.CENTER_LEFT);

        HBox topRow = new HBox(progress);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        topRow.getChildren().addAll(topSpacer, skip);
        topRow.setAlignment(Pos.CENTER_LEFT);

        learnMore.getStyleClass().add("onboarding-learn-more");
        learnMore.setFocusTraversable(false);
        learnMore.setOnAction(e -> { openHelpForCurrentStep(); learnMore.setVisited(false); });

        VBox card = new VBox(14, topRow, stepTitle, stepBody, learnMore, footer);
        card.getStyleClass().add("onboarding-card");
        card.setPadding(new Insets(24));
        card.setMaxWidth(480);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        getChildren().add(card);
    }

    private void render() {
        Step s = steps.get(current);
        stepTitle.setText(s.title());
        stepBody.setText(s.body());
        progress.setText("Step " + (current + 1) + " of " + steps.size());
        backBtn.setDisable(current == 0);
        nextBtn.setText(current == steps.size() - 1 ? "Get started" : "Next");
    }

    private void finish() {
        if (dontShow.isSelected() || current >= steps.size() - 1) {
            prefs.markDismissed();
        }
        FadeTransition fade = new FadeTransition(Duration.millis(150), this);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            if (getParent() instanceof javafx.scene.layout.Pane p) p.getChildren().remove(this);
            onDone.run();
        });
        fade.play();
    }

    /** Open the current step's deep help link (exposed for the ? button / tests). */
    void openHelpForCurrentStep() {
        HelpDialog.open(steps.get(current).helpTarget());
    }
}
