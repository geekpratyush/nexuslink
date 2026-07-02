# Packaging NexusLink

NexusLink is a JavaFX desktop app. Because JavaFX ships per-OS **native libraries** and is **not**
part of the JDK, "make it double-clickable" has two distinct answers depending on whether the target
machine already has Java. Both are wired as opt-in Maven profiles on the `nexuslink-app` module, so
normal `mvn install` / `mvn test` builds are unaffected.

| Option | Artifact | Java needed on target? | One file for all OSes? |
|--------|----------|------------------------|------------------------|
| **Fat JAR — host-only** (`fatjar`) | `nexuslink.jar` (~212 MB) | **Yes — Java 21+** | No — runs on the build OS |
| **Fat JAR — all platforms** (`fatjar,fatjar-all-platforms`) | `nexuslink.jar` (~278 MB) | **Yes — Java 21+** | **Yes** |
| **Native app-image** (`jpackage`) | `dist/NexusLink/` (~430 MB) | **No (bundles a JRE)** | No — build per OS |

> **Size note.** The fat JAR defaults to **host-only** — it bundles only the build machine's JavaFX
> natives (~212 MB). Bundling all three desktop platforms (Windows + macOS Intel + macOS ARM) adds
> `fatjar-all-platforms` and pushes it to ~278 MB, because JavaFX's **WebKit** native library (used by
> the Mermaid diagram + Markdown help views) is ~80–104 MB *per platform*. Even host-only, that single
> WebKit `.so`/`.dll`/`.dylib` (~104 MB) plus the many protocol SDKs (Anthropic, gRPC, Google, Kafka,
> AWS, …) set the practical floor. Going materially below ~150 MB means dropping WebView or making the
> heavy SDKs load on demand.

> **Why not "any Java version"?** The app is compiled to **Java 21** bytecode, so a JAR needs a
> **Java 21 or newer** runtime present. The only way to need *no* Java at all is the `jpackage`
> route, which bundles its own runtime into the app (and is therefore OS-specific).

---

## 1. Double-clickable fat JAR — one file, every desktop OS

Builds a single uber-JAR containing every dependency and the JavaFX classes. By default it bundles
only the **build machine's** native libraries (host-only, ~212 MB) — the resulting jar runs on that
OS with a **Java 21+** runtime installed.

```bash
# Host-only (smaller, runs on the OS you build it on):
mvn -Pfatjar -pl nexuslink-app -am clean package
# →  nexuslink-app/target/nexuslink.jar   (~212 MB)

# All desktop platforms in one file (Windows + macOS Intel/ARM + Linux):
mvn -Pfatjar,fatjar-all-platforms -pl nexuslink-app -am clean package
# →  nexuslink-app/target/nexuslink.jar   (~278 MB)
```

Run it:

```bash
java -jar nexuslink-app/target/nexuslink.jar
```

…or double-click `nexuslink.jar` in a file manager (on most systems an installed JRE associates the
`.jar` extension with `java`). On Linux you may need to mark it runnable or create a `.desktop`
launcher.

**How it works** — two things make a JavaFX uber-JAR launch from `java -jar`:
1. The `Main-Class` is `com.nexuslink.app.Main`, a plain class that simply calls the real JavaFX
   `NexusLinkLauncher`. If `Main-Class` itself extended `javafx.application.Application`, the JVM would
   abort with *"JavaFX runtime components are missing"*.
2. The host's own JavaFX native classifier comes in transitively; the optional `fatjar-all-platforms`
   profile adds the `win` / `mac` / `mac-aarch64` classifiers for a cross-platform jar. `maven-shade-plugin`'s
   `ServicesResourceTransformer` merges `META-INF/services` so the `ProtocolConnector` and JDBC-driver
   SPIs keep working after shading.

---

## 2. Native app-image — no Java required (jpackage)

Wraps the fat JAR in a native application that bundles its **own** Java runtime, so end users need
nothing pre-installed — they just run the produced app. `jpackage` is part of the JDK and is
**OS-specific**: build it on each OS you want to ship.

```bash
mvn -Pfatjar,jpackage -pl nexuslink-app -am clean verify
# →  nexuslink-app/target/dist/NexusLink/        (an app-image directory)
#     run the launcher inside:  dist/NexusLink/bin/NexusLink   (Linux/macOS)
#                               dist\NexusLink\NexusLink.exe   (Windows)
```

The `jpackage` profile stages just the fat JAR into a clean input directory, then runs `jpackage`
(via the panteleyev plugin) with `--type APP_IMAGE`.

### Producing an installer instead of a folder

Change `<type>` in the `jpackage` profile (in `nexuslink-app/pom.xml`) to a platform installer:

| OS | `<type>` | Extra tooling |
|----|----------|---------------|
| Windows | `EXE` or `MSI` | [WiX Toolset](https://wixtoolset.org/) on `PATH` |
| macOS | `DMG` or `PKG` | Xcode command-line tools (signing for distribution) |
| Linux | `DEB` or `RPM` | `dpkg`/`rpmbuild` |

---

## Notes & next steps

- **Size:** the fat JAR is large because it bundles the AWS SDK, gRPC, Kafka, database drivers, JavaFX
  for four platforms, etc. A future `jlink` step (TASKS §9.6) can trim the runtime the app-image bundles.
- **Reproducibility:** both profiles are opt-in; CI / day-to-day `mvn install` does not build them.
- **Signing/notarization** (macOS Gatekeeper, Windows SmartScreen) is out of scope here and is
  handled at distribution time with platform certificates.
