package com.nexuslink.app;

import com.nexuslink.ui.main.MainWindow;
import javafx.application.Application;
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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
