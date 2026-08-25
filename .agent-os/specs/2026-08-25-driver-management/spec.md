# Spec — Driver management in a locked-down network

**Date:** 2026-08-25 · **Status:** shipped · **Tracks:** `TASKS.md` §8.1.1

## Problem

On-demand JDBC drivers could only be installed by downloading them from a hard-coded
`repo1.maven.org` URL using a bare `HttpClient` — no proxy, no credentials, no alternative host. In
the environment NexusLink targets (see `product/mission.md`), that download can never succeed:
egress is blocked, Maven is mirrored through an internal Artifactory, and TLS is intercepted by a
corporate CA. Oracle, SQL Server, DB2 and the cloud warehouses were therefore unreachable for
exactly the users who need them most, and the failure surfaced as a bare connect error.

## Approach

Three independent routes to a working driver, so no single blocked path stops the user.

1. **Direct download, against a configurable repository.** `MavenRepositoryConfig` resolves the
   repository from system properties → environment → `~/.nexuslink/maven.properties` →
   an existing `~/.m2/settings.xml` mirror → Maven Central. Bearer token or basic auth; requests go
   through `ProxySelector.getDefault()`.
2. **The user's own Maven.** `MavenCommandHelp` generates the `mvn dependency:copy` command for the
   user's shell — bash, PowerShell or cmd, each with correct quoting and home-directory syntax.
   Maven then resolves through the org's mirror using credentials and a proxy that are already
   configured and known to work. This is the route that always works, and the reason it exists is
   recorded in `product/decisions.md` #14.
3. **Attach a jar from disk.** `JarDriverInspector` reads
   `META-INF/services/java.sql.Driver` (falling back to `*Driver` class names) so the user picks a
   file and confirms a name.

Resolution before any network call: `~/.nexuslink/drivers` → `~/.m2/repository` → remote.

Driver list state lives in `JdbcDriverRegistry.allIncludingUser()`, merging the built-in catalog
with `UserDriverStore` (`~/.nexuslink/user-drivers.json`). Adding registers the driver with
`DriverManager` immediately — no restart — and removing deregisters the shim.

## Out of scope

- **Bundling more drivers.** Decision #9 stands; licensing and size are unchanged.
- **Decrypting Maven's `settings-security.xml`.** Encrypted passwords are detected and ignored;
  the user configures those explicitly.
- **Managing the corporate CA.** Trust is a JVM concern (`-Djavax.net.ssl.trustStore`), documented
  rather than automated.
- **Driver version selection UI.** The catalog pins a version; a different one is installed by
  editing the `mvn` command and attaching the result.

## Verification

`MavenRepositoryConfigTest`, `MavenCommandHelpTest`, `JarDriverInspectorTest`, `UserDriverStoreTest`
— 29 tests, offline. App launch verified.
