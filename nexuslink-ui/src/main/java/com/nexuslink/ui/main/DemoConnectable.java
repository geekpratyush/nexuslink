package com.nexuslink.ui.main;

/**
 * A view that can connect itself for a demo or a screenshot, using the defaults already in its
 * connection bar. Implemented only by the views used in the marketing captures; the startup hook
 * (see {@code MainWindow}, {@code NEXUSLINK_DEMO_CONNECT}) calls it after opening the tab so a
 * screen can show real data without anyone driving the UI.
 */
public interface DemoConnectable {

    /** Connects using whatever is in the connection bar. Must be called on the FX thread. */
    void connectForDemo();
}
