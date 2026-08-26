# Running NexusLink (dev)

NexusLink is a JavaFX desktop app. Runnable today: the **workspace shell** (menu, connection
tree, tabs, log panel, status bar, dark/light theming), the **Help system** (F1), the
**credential vault** (master-password unlock, auto-lock), the **certificate manager**
(Tools ▸ Certificate Manager…), the **environment-variable manager** (Tools ▸ Environments…),
and a broad set of protocol clients — **REST, WebSocket, SSE, GraphQL, gRPC, SQL/JDBC, MongoDB,
Redis, Kafka, MQTT, SFTP, FTP/FTPS, S3/Azure/GCS object storage, the MCP Inspector, and the
AI/LLM tester.** Some need live infrastructure or credentials for end-to-end use (Kafka needs a
broker; Azure/GCS need accounts; the LLM tester needs `ANTHROPIC_API_KEY`). See `TASKS.md` for
exact status.

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

From the **repo root**, target the app module (the `-am` also rebuilds changed modules first):
```bash
mvn -pl nexuslink-app -am javafx:run
```

Or from inside the app module:
```bash
cd nexuslink-app
mvn javafx:run
```

> **Note:** `javafx:run` only works on the `nexuslink-app` module — that's where the
> `mainClass` (`com.nexuslink.app.NexusLinkLauncher`) is configured. Running a bare
> `mvn javafx:run` from the repo root fails with *"mainClass … missing or invalid"*
> because the goal hits the aggregator `nexuslink-parent` first. Use `-pl nexuslink-app`
> (above) or `cd nexuslink-app`.

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
- The **workspace shell**: menu bar + global search, connection tree (left) with **Saved**
  and **Samples (public)** groups, tabbed workspace, collapsible log/history panel (bottom),
  status bar with a vault 🔒/🔓 toggle.
- **REST client**: pick a method, type a URL, hit **Send** (or Ctrl+Enter). You get a
  color-coded status, timing (total/TTFB/download), size + HTTP version, and the response
  body (JSON auto–pretty-printed) and headers. Every call is logged + saved to history.
- **Other protocols** open from the **File** / **AI** menus or the sidebar buttons — each as
  its own tab (SQL grid, Mongo views, object-storage trees, the MCP Inspector, etc.). The
  bundled public **samples** open prefilled so you can try a protocol with one click.
- Toggle **dark/light theme** with **Ctrl+Shift+T**; open **View ▸ Protocols…** to show only
  the connection types you use.
- Press **F1** (or Help menu) for the 3-pane searchable, Markdown/Mermaid Help dialog.

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

## Console messages you can ignore

### `Error in glXCreateNewContext, remote GLX is likely disabled`

Not an application error, and nothing is broken. JavaFX tries its hardware-accelerated **ES2**
pipeline first; on a display where GLX cannot hand out a hardware context (a remote X session, a
VM without 3D, some Wayland/XWayland setups) that probe fails and JavaFX falls back to the software
renderer. Verified with `-Dprism.verbose=true`:

```
Prism pipeline init order: es2 sw
GLFactory ... could not be initialized. ES2Pipeline not available.
*** Fallback to Prism SW pipeline
Prism pipeline name = com.sun.prism.sw.SWPipeline
```

The message is printed by the native GL library before JavaFX runs any of its own code, so the app
cannot catch or silence it. To skip the probe (and the message) entirely, ask for the software
pipeline up front:

```bash
JAVA_TOOL_OPTIONS=-Dprism.order=sw mvn javafx:run
```

Only do that where the probe is known to fail — on a machine with working hardware acceleration it
would give up the GPU for nothing.

### `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` — **fixed 2026-08-26**

This meant the embedded drivers' own diagnostics were being **discarded**: every library NexusLink
uses (Kafka, Mongo, Lettuce, the JDBC drivers, the AWS/Azure SDKs) logs through SLF4J, and with no
binding on the classpath SLF4J installs a no-op logger and throws everything away. A poor trade for a
connectivity tool — "SASL authentication failed", "leader not available", "connection reset by peer"
are exactly the sentences that explain a failure.

`nexuslink-app` now ships the `slf4j-jdk14` binding, and `LibraryLogBridge` forwards library
**warnings and above** into the in-app **Activity log**, where the rest of the session's activity
already is. Driver INFO chatter stays out (Kafka alone logs its whole producer config on every send).

## Known quirk (dev environments)
When the process is launched **detached/backgrounded** from a non-interactive shell
(e.g. `nohup … &` from a CI/agent shell), the GTK event loop can exit immediately and
`stop()` fires right after the window shows — the window flashes and closes. Running in a
normal **foreground** terminal (Option A or B above) keeps it open as expected. This is an
environment/TTY interaction, not an app bug.
