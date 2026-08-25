# Code style

The goal is that new code is indistinguishable from the code already here.

## Structure

- **Pure logic lives in a `nexuslink-protocol-*` or `nexuslink-core` module; JavaFX lives in
  `nexuslink-ui`.** A parser, builder, signer or planner must be constructible and assertable
  without a toolkit, a socket, or a container. This is why the test suite runs in seconds.
- **A dialog performs no blocking I/O.** It receives data and hands back a result; the calling view
  runs the work on a `Task` off the FX thread.
- **A protocol module exposes a small service** (connect + operations), an explorer mapping it to
  `ResourceNode`s, and a `*View` in the UI module. See `docs/ARCHITECTURE.md`.

## Naming and shape

- Records for immutable value types; `final class` with a private constructor for pure static
  helpers; `final class` for services.
- Methods say what they do from the caller's point of view — `ensureLoaded`, `cachedJar`,
  `withoutLiteralSecrets` — not how they do it.
- Prefer a small named method over a comment explaining a block.

## Comments and Javadoc

This repository comments **why**, never **what**. A comment restating the code is noise; a comment
recording a decision, a constraint or a trap is the most valuable line in the file.

Worth writing:

```java
// PowerShell parses a bare -Dkey=value inconsistently across versions; quoting the whole
// argument is the form that works everywhere.
```

Not worth writing:

```java
// Loop over the drivers
for (DriverInfo d : drivers) {
```

Every public type gets a Javadoc block that states what it is **and why it exists** — especially
when a simpler-looking alternative was rejected. `DriverShim` and `MavenCommandHelp` are the
reference examples: both explain a non-obvious design in the class comment, so the next reader
doesn't "simplify" them back into being broken.

## Errors

An error message is a piece of user interface. It names what failed, what was tried, and what the
user can do — including the specific setting, environment variable or file. `ExternalDriverLoader`'s
download failure is the template.

Never swallow an exception silently. If a failure is genuinely recoverable, say why in a comment on
the catch block.

## User-facing text

- Sentence case, no exclamation marks, no blame.
- Say what to do next, not just what went wrong.
- Prefer the user's vocabulary ("driver", "connection") over ours ("classloader", "shim").
