package com.nexuslink.app;

import com.nexuslink.ui.help.HelpDialog;
import com.nexuslink.ui.main.MainWindow;
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
        stage.setScene(window.createScene());
        stage.show();

        // Demo/deep-link hooks (see RUN.md): open Help at a topic, or run a Help search.
        String autoHelp = System.getProperty("nexuslink.autohelp");
        String autoSearch = System.getProperty("nexuslink.autosearch");
        if (autoSearch != null) Platform.runLater(() -> HelpDialog.openWithSearch(autoSearch));
        else if (autoHelp != null) Platform.runLater(() -> HelpDialog.open(autoHelp));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
