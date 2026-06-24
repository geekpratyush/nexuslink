# Running NexusLink (dev)

NexusLink is a JavaFX desktop app. Runnable today (Session 2): the **workspace shell**
(menu, connection tree, tabs, log panel, status bar), a **working REST client**
(live HTTP/2 requests with color-coded status, timing, headers/body viewers), and the
**Help system** (F1). More protocols land in later phases (see `TASKS.md`).

## Prerequisites
- Java 21 (`java -version` → 21.x)
- Maven 3.9+
- A graphical display (X11/Wayland). This is a GUI app — it will not run headless.

## Build everything
```bash
cd /home/pratyush/software/nexuslink
mvn -DskipTests install
```

## Run it

### Option A — Maven plugin (simplest, run in your own terminal)
```bash
cd nexuslink-app
mvn javafx:run
```

### Option B — Direct java (most reliable)
```bash
cd nexuslink-app
JFX=$(find ~/.m2/repository/org/openjfx -name "*.jar" | grep linux | tr '\n' ':')
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/nl_cp.txt
java --module-path "$JFX" \
     --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base \
     -cp "target/classes:$(cat /tmp/nl_cp.txt)" \
     com.nexuslink.app.NexusLinkLauncher
```

## What you'll see
- The **workspace shell**: menu bar + search, connection tree (left), a "REST 1" tab,
  collapsible log panel (bottom), status bar.
- **REST client**: pick a method, type a URL, hit **Send** (or Ctrl+Enter). You get a
  color-coded status, timing (total/TTFB/download), size + HTTP version, and the response
  body (JSON auto–pretty-printed) and headers. Every call is logged in the bottom panel.
- Press **F1** (or Help menu) for the 3-pane searchable Help dialog.

## Smoke-test the REST engine (no GUI)
```bash
# Auto-send the seeded httpbin request on startup:
cd nexuslink-app && NEXUSLINK_AUTOSEND=1 mvn javafx:run
```

## Demo / deep-link flags (handy for screenshots)
```bash
# Auto-open Help at a topic on startup
java ... com.nexuslink.app.NexusLinkLauncher -Dnexuslink.autohelp=getting-started
# Auto-open Help and run a search
java ... com.nexuslink.app.NexusLinkLauncher -Dnexuslink.autosearch=oauth
```
(Pass `-D…` before `-cp`/main class as a JVM arg.)

## Known quirk (dev environments)
When the process is launched **detached/backgrounded** from a non-interactive shell
(e.g. `nohup … &` from a CI/agent shell), the GTK event loop can exit immediately and
`stop()` fires right after the window shows — the window flashes and closes. Running in a
normal **foreground** terminal (Option A or B above) keeps it open as expected. This is an
environment/TTY interaction, not an app bug.
