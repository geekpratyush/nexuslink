# Shipping NexusLink through your Artifactory

The goal: **you publish once, your users run one command.** They never clone the repository, never
build anything, and never see the source — they get a small script, point it at the company
repository, and run it. After the first run the app is cached locally, so it starts offline and
refreshes only when they ask.

```
   you                          Artifactory                     a user's machine
 --------                      -------------                   ------------------
 publish.sh   ------------->   nexuslink-app-      <---------   nexuslink.sh / .bat / .ps1
 (fat jar)                     1.2.0-all.jar                    downloads once, caches in
                                                                ~/.nexuslink/runtime, then
                                                                runs offline every time after
```

---

## 1. Publish — one command, same on a laptop and in a pipeline

```bash
./dist/publish.sh                          # build -> install to ~/.m2 -> deploy if a repo is configured
./dist/publish.sh --local                  # local only, never deploy
./dist/publish.sh --deploy                 # require a deploy; fail if no repository is configured
./dist/publish.sh --version 1.2.0          # set the release version first
./dist/publish.sh --host-only              # smaller jar, runs on the build OS only
```

It **always installs to `~/.m2` first**. That is what makes `nexuslink.sh --local` work on a
developer machine, and in CI it proves the artifact builds before anything is published anywhere.
Then, if a repository is configured, it deploys; if not, it says so and stops rather than failing.

The repository is resolved from `--repo` / `--repo-id`, else `NEXUSLINK_REPO_URL` /
`NEXUSLINK_REPO_ID`. Credentials come from `~/.m2/settings.xml` — never the command line.

### In a pipeline

Write a `settings.xml` from your CI secrets, then call the same script:

```yaml
# GitHub Actions
- uses: actions/setup-java@v4
  with: { java-version: '21', distribution: 'temurin' }
- run: |
    mkdir -p ~/.m2
    cat > ~/.m2/settings.xml <<XML
    <settings><servers><server>
      <id>corp-artifactory</id>
      <username>${{ secrets.ARTIFACTORY_USER }}</username>
      <password>${{ secrets.ARTIFACTORY_TOKEN }}</password>
    </server></servers></settings>
    XML
- run: ./dist/publish.sh --deploy --version ${{ github.ref_name }}
  env:
    NEXUSLINK_REPO_ID: corp-artifactory
    NEXUSLINK_REPO_URL: https://artifactory.corp/artifactory/libs-release-local
```

```groovy
// Jenkins
withCredentials([usernamePassword(credentialsId: 'artifactory',
                 usernameVariable: 'U', passwordVariable: 'P')]) {
  writeFile file: "${env.HOME}/.m2/settings.xml", text: """
    <settings><servers><server>
      <id>corp-artifactory</id><username>${U}</username><password>${P}</password>
    </server></servers></settings>"""
  withEnv(["NEXUSLINK_REPO_ID=corp-artifactory",
           "NEXUSLINK_REPO_URL=https://artifactory.corp/artifactory/libs-release-local"]) {
    sh './dist/publish.sh --deploy'
  }
}
```

Under the hood it is still plain Maven, if you would rather call that directly:

```bash
mvn -Pfatjar,fatjar-all-platforms,publish -pl nexuslink-app -am clean deploy \
    -Dnexuslink.repo.id=corp-artifactory \
    -Dnexuslink.repo.url=https://artifactory.corp/artifactory/libs-release-local
```

- `fatjar` builds the single self-contained JAR; `fatjar-all-platforms` makes it run on Windows,
  macOS and Linux from the same file (~238 MB — see `PACKAGING.md` for why).
- `publish` attaches that JAR as the **`all` classifier** and deploys it, so the repository ends up
  with `com/nexuslink/nexuslink-app/<version>/nexuslink-app-<version>-all.jar` — the exact path the
  bootstrap scripts fetch.
- **Credentials go in `~/.m2/settings.xml`**, not on the command line:

```xml
<servers>
  <server>
    <id>corp-artifactory</id>          <!-- must match -Dnexuslink.repo.id -->
    <username>your-user</username>
    <password>your-api-token</password>
  </server>
</servers>
```

Release the version first (`1.2.0` rather than `1.2.0-SNAPSHOT`) if you want `RELEASE` resolution to
find it; snapshots work too, and the scripts resolve their timestamped filenames automatically.

## 2. Run (your users, no source code)

Hand them one launcher — or point them at the **GitHub Pages site** (`docs/index.html`), which
offers all three as downloads:

| File | Platform |
|---|---|
| `dist/nexuslink.sh` | Linux and macOS |
| `dist/nexuslink.bat` | Windows — command prompt or double-click |
| `dist/nexuslink.ps1` | Windows — PowerShell (the `.bat` hands over to this; keep them together) |

They need **Java 21+** and nothing else.

```bash
export NEXUSLINK_REPO_URL=https://artifactory.corp/artifactory/libs-release-local
./nexuslink.sh                 # downloads on the first run, then just runs
```

To avoid even that, drop a `~/.nexuslink/bootstrap.conf` next to the script (an admin can ship one
already filled in — the environment still overrides it):

```
NEXUSLINK_REPO_URL=https://artifactory.corp/artifactory/libs-release-local
NEXUSLINK_USER=svc-nexuslink
NEXUSLINK_TOKEN=...
```

| Command | What it does |
|---|---|
| `nexuslink.sh` | Run. Downloads only if the version is not already cached. |
| `nexuslink.sh --update` | Force a re-download of the resolved version. |
| `nexuslink.sh --offline` | Never touch the network; run the newest cached build, or fail saying so. |
| `nexuslink.sh --version 1.2.0` | Run one specific version (several can sit side by side). |
| `nexuslink.sh --list` | Show what is cached. |
| `nexuslink.sh --where` | Print the JAR that would run, without running it. |
| `nexuslink.sh --fresh` | Clear the cache, then download and run. For when a build looks wrong. |
| `nexuslink.sh --clean` | Delete every cached build (add `--version` for just one). |
| `nexuslink.sh --local` | Run the build installed in `~/.m2` by `publish.sh` — no repository needed. |
| `nexuslink.sh --help` | The full usage text, from the script itself. |

Everything is configurable by environment variable: `NEXUSLINK_REPO_URL`, `NEXUSLINK_VERSION`
(`RELEASE`, `LATEST`, or an exact version), `NEXUSLINK_USER` / `NEXUSLINK_TOKEN`, `NEXUSLINK_HOME`
(default `~/.nexuslink`), `NEXUSLINK_JAVA_OPTS`, and `JAVA_HOME`. Point `NEXUSLINK_HOME` at a
relative path — `NEXUSLINK_HOME=./nexuslink-cache` — to keep the download in the current folder
rather than the user's home directory.

## What the scripts do, and what they refuse to do

- **Version resolution.** `RELEASE` and `LATEST` are read from the repository's
  `maven-metadata.xml`, the same file Maven itself uses. A `-SNAPSHOT` version is resolved through
  the version-level metadata, because Maven publishes snapshots under a timestamped filename
  (`...-1.0.0-20260826.093113-1-all.jar`) rather than under the snapshot's own name.
- **Checksum verification.** The download is checked against the repository's `.sha256` if there is
  one, otherwise its `.sha1` — Maven writes `.sha1`, Artifactory adds `.sha256` server-side, so a
  download is verified either way. A mismatch deletes the file and stops with an error rather than
  running something that does not match the repository. If the repository publishes no checksum at
  all, the script says so instead of quietly trusting the bytes.
- **Partial downloads never win.** The file lands as `....jar.part` and is renamed only after it
  verifies, so an interrupted download cannot leave a broken JAR in the cache.
- **Java is checked up front.** A missing Java, or one older than 21, gets a sentence saying what to
  install or set `JAVA_HOME` to — not a stack trace.
- **`--offline` is honest.** It never resolves `RELEASE`/`LATEST` over the network; it runs the
  newest cached build and says plainly when there is nothing cached.

## Verified end to end

Both halves were exercised against a real Maven deployment, not mocked:

- `mvn -Pfatjar,publish ... deploy` to a repository, producing `nexuslink-app-...-all.jar` with its
  `.sha1`/`.md5` alongside.
- The bootstrap then resolved `RELEASE`, `LATEST`, an exact version and a `-SNAPSHOT` against that
  repository over HTTP; cached the real 238 MB JAR; verified its checksum; reused the cache on the
  second run; served `--offline` from the cache; and **launched the app from the downloaded JAR**
  (window confirmed at 1920x1012 via `wmctrl`).
- The failure paths were checked too: a tampered artifact is caught by the checksum and refused
  (exit 1, nothing cached), a missing version reports the URL it tried, `--offline` with an empty
  cache says so, and a missing `NEXUSLINK_REPO_URL` explains what to set.

## The GitHub Pages site

`docs/` is ready to serve as a Pages site (Settings ▸ Pages ▸ *Deploy from a branch* ▸ `/docs`):

- **`docs/.nojekyll`** — turns Jekyll off, so nothing is filtered or rewritten.
- **`docs/index.html`** — the landing page: a parallax hero over the logo and tagline, then the
  install steps, every launcher option, the settings table, what is inside, the documentation index,
  the fork-and-pull-request walkthrough and troubleshooting. Self-contained — no external CSS, fonts
  or JavaScript, so it loads on a locked-down network, and it degrades to a plain readable page with
  JavaScript off.
- **`docs/doc.html`** — renders the markdown documents in `docs/` in the site's own styling
  (`doc.html?d=ARCHITECTURE.md`), so a reader following a link never lands on raw markdown. It serves
  only the documents named in its `DOCS` map.
- **`docs/assets/`** — the logo system: the icon mark, the light and dark full lockups used by
  `README.md`, and the favicon. Plain SVG, no fonts to embed.
- **`docs/javadoc/`** — the aggregated API reference, committed so Pages can serve it. Regenerate
  with `mvn -Pjavadoc javadoc:aggregate`, which writes straight into this folder.
- **`docs/downloads/`** — copies of the three launchers, which the download buttons point at.
  `dist/publish.sh` refreshes them from `dist/` on every run and tells you if they had drifted, so
  the published scripts cannot silently fall behind the canonical ones.

Verified by serving `docs/` over HTTP: the page and all three downloads return 200, and the HTML
parses with no unclosed tags.

## Not done yet

- **No code signing.** The JAR is verified against the repository's checksum, which proves it
  arrived intact — not that your organisation published it. Signing (jarsigner, or Artifactory's own
  signing) would be the next step if that matters.
- **A JRE is still required** on the user's machine (Java 21+). The `jpackage` route in
  `PACKAGING.md` bundles one, at the cost of a per-OS build; that is the option to take if "no Java
  installed" is a hard requirement.
- **The Windows launchers have not been run on Windows** from here — `nexuslink.ps1` mirrors the bash
  script's logic and `nexuslink.bat` is a thin wrapper around it, but this machine is Linux, so
  neither is verified on the platform it targets.
