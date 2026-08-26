# Bringing NexusLink into your organisation

This is the handover document. It covers the whole path: take this code, put it in your
organisation's GitHub, build and publish it from your library pipeline, and give your users a single
script that downloads it once and runs it from then on.

It is written to be handed to whoever wires up the pipeline — a colleague, or an assistant such as
GitHub Copilot. Everything it needs to decide is stated here; nothing is left as "and then configure
the usual things".

---

## 0. The shape of it, in one picture

```
   your pipeline                    your Artifactory                  a user's machine
 -----------------                 ------------------               -------------------
 mvn build (JDK 21)                nexuslink-app-                    nexuslink.sh / .bat / .ps1
   -> one fat JAR       deploy ->  1.2.0-all.jar        download ->  caches in ~/.nexuslink/runtime
   (every dep inside)              + maven-metadata.xml              then runs offline every time
```

Three facts that shape everything below:

1. **The deliverable is one ordinary Maven artifact** — a fat JAR with every dependency inside it,
   published under a classifier. Your pipeline treats it exactly like any other library it deploys.
2. **There is no installer and no native packaging.** `jpackage` exists in this repository as an
   optional profile; nobody's install path uses it. Users get a JAR and a JVM.
3. **The launcher is not special either.** It reads the same `~/.m2/settings.xml` your developers
   already have, fetches the JAR over plain HTTP(S), verifies its checksum, caches it, and runs
   `java -jar`. If Maven can reach your Artifactory, so can it.

---

## 1. What you must have

| | Requirement | Why |
|---|---|---|
| Build agent | **JDK 21+** and Maven 3.9+ | The project is compiled at source/target 21 |
| Artifactory | A **release** Maven repository you can deploy to | The launcher resolves `RELEASE` from `maven-metadata.xml` |
| Credentials | A service account with deploy rights | Supplied to Maven as a `<server>` in `settings.xml` |
| Users | **JDK 21+** on their machine | The launcher refuses to start on anything older |

### About Java 17

**Java 17 is not enough — for the build or for users.** The launcher checks the JVM version before
anything else and stops with a plain sentence if it is below 21, and the bytecode would not load
anyway. If your organisation standardises on 17, that is a project change, not a configuration flag:
someone has to retarget `maven.compiler.source`/`target`, replace the Java 21 language constructs the
code uses, and pin a JavaFX release that supports 17. Budget it as work, and until it is done, ship
users a JDK 21 runtime alongside the launcher or point `JAVA_HOME` at one they already have.

---

## 2. Import the code into your organisation

You are copying a codebase, not forking a dependency — there is no upstream to track.

```bash
# a clean copy, with no history from the original repository
git clone --depth 1 <this-repository-url> nexuslink-import
rm -rf nexuslink-import/.git

# put it in your org repo
git clone <your-org-repository-url> nexuslink
cp -r nexuslink-import/. nexuslink/
cd nexuslink
git add -A
git commit -m "Import NexusLink"
git push
```

Keep the history instead by pushing this repository's `main` to the new remote — either is fine; the
build does not depend on it.

### What to change after the import

Most of it works untouched. These are the decisions that are yours:

| Decision | Where | Note |
|---|---|---|
| **Group id** | `pom.xml` `<groupId>com.nexuslink</groupId>` | If you change it, change `GROUP_PATH` in `dist/nexuslink.sh` and `$GroupPath` in `dist/nexuslink.ps1` to match, or the launcher will look in the wrong place |
| **Version scheme** | `pom.xml` `<version>`, or `publish.sh --version` | Publish **releases**, not `-SNAPSHOT` — see §4 |
| **Repository id and URL** | `NEXUSLINK_REPO_ID` / `NEXUSLINK_REPO_URL` in the pipeline | The id must match a `<server>` in the agent's `settings.xml` |
| **The published site** | `docs/` | GitHub Pages, if your org allows it — otherwise host `docs/` anywhere, or delete it |
| **Vendor name** | `nexuslink-app/pom.xml` `<vendor>` | Only used by the optional `jpackage` profile |

Everything else — module layout, dependencies, the help browser — is meant to be left alone.

---

## 3. Build and publish

One script does it, and behaves identically on a laptop and on an agent:

```bash
./dist/publish.sh                          # build -> install to ~/.m2 -> deploy if a repo is configured
./dist/publish.sh --deploy                 # require a deploy; fail if none is configured  <-- use this in CI
./dist/publish.sh --version 1.2.0          # stamp the release version first
./dist/publish.sh --host-only              # smaller JAR that runs on the build OS only
```

It always installs to `~/.m2` before deploying anything, which in a pipeline is a free proof that the
artifact actually builds. Then it deploys, or says clearly that no repository is configured and stops.

If you would rather call Maven directly, this is what the script runs:

```bash
mvn -Pfatjar,fatjar-all-platforms,publish -pl nexuslink-app -am -DskipTests clean deploy \
    -Dnexuslink.repo.id=corp-artifactory \
    -Dnexuslink.repo.url=https://artifactory.example.com/artifactory/libs-release-local
```

**What lands in Artifactory:**

| | |
|---|---|
| Coordinates | `com.nexuslink:nexuslink-app:<version>:all` |
| File | `nexuslink-app-<version>-all.jar` — around 240 MB for a `--host-only` build, larger when it carries every platform's JavaFX natives |
| Alongside it | the POM, `.sha1`/`.sha256` checksums, and the artifact-level `maven-metadata.xml` |

The launcher needs three of those: the JAR, a checksum, and `maven-metadata.xml`. Artifactory writes
all of them for a normal Maven deploy — nothing extra to configure.

### Why `fatjar-all-platforms`

JavaFX ships native code per operating system. The default `fatjar,fatjar-all-platforms` build
bundles Windows, macOS (Intel and Apple Silicon) and Linux in one JAR, so one artifact serves
everyone. `--host-only` produces a noticeably smaller JAR that runs **only** on the build agent's
operating system — use it only if you publish one artifact per platform.

---

## 4. Versioning — the one thing that will bite you

**Publish release versions. Do not publish `-SNAPSHOT` as the thing users run.**

The launcher's default is `RELEASE`, which it resolves from `<release>` in the artifact's
`maven-metadata.xml`. Snapshots are not listed there: Maven writes them as timestamped files
(`nexuslink-app-1.2.0-20260826.093113-1-all.jar`) recorded in a second, version-level metadata file.
The launcher does handle a snapshot when it is asked for one explicitly
(`--version 1.2.0-SNAPSHOT`), but `RELEASE` will never find it.

A workable scheme:

- Tag the repository `v1.2.0`, and let the pipeline call `./dist/publish.sh --deploy --version 1.2.0`.
- Publish to a **release** repository (or a virtual repository whose default deployment target is one).
- Users get the newest release automatically; `--version 1.1.0` pins an older one, and several can
  sit side by side in the cache.

---

## 5. The pipeline

### GitHub Actions

```yaml
name: publish
on:
  push:
    tags: ['v*']            # publishing is a tag event, not every commit
  workflow_dispatch:

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      # Credentials belong in settings.xml, never on the command line.
      - name: Configure Maven
        run: |
          mkdir -p ~/.m2
          cat > ~/.m2/settings.xml <<'XML'
          <settings>
            <servers>
              <server>
                <id>corp-artifactory</id>
                <username>${env.ARTIFACTORY_USER}</username>
                <password>${env.ARTIFACTORY_TOKEN}</password>
              </server>
            </servers>
          </settings>
          XML
        env:
          ARTIFACTORY_USER: ${{ secrets.ARTIFACTORY_USER }}
          ARTIFACTORY_TOKEN: ${{ secrets.ARTIFACTORY_TOKEN }}

      - name: Build and publish
        run: ./dist/publish.sh --deploy --version "${GITHUB_REF_NAME#v}"
        env:
          ARTIFACTORY_USER: ${{ secrets.ARTIFACTORY_USER }}
          ARTIFACTORY_TOKEN: ${{ secrets.ARTIFACTORY_TOKEN }}
          NEXUSLINK_REPO_ID: corp-artifactory
          NEXUSLINK_REPO_URL: https://artifactory.example.com/artifactory/libs-release-local

      # Users need the launcher, not the JAR. Attach it to the release.
      - name: Attach the launchers to the release
        uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/nexuslink.sh
            dist/nexuslink.bat
            dist/nexuslink.ps1
```

`${env.…}` inside `settings.xml` is Maven's own environment interpolation, which is why the secrets
are also exported to the build step — the file itself never contains the token.

### Jenkins

```groovy
pipeline {
  agent any
  tools { jdk 'jdk-21'; maven 'maven-3.9' }
  environment {
    NEXUSLINK_REPO_ID  = 'corp-artifactory'
    NEXUSLINK_REPO_URL = 'https://artifactory.example.com/artifactory/libs-release-local'
  }
  stages {
    stage('Publish') {
      steps {
        configFileProvider([configFile(fileId: 'maven-settings', variable: 'MAVEN_SETTINGS')]) {
          sh 'mkdir -p ~/.m2 && cp "$MAVEN_SETTINGS" ~/.m2/settings.xml'
        }
        sh './dist/publish.sh --deploy --version ${TAG_NAME#v}'
      }
    }
  }
}
```

### If your pipeline template will not run a script

Some library pipelines only call Maven goals. Then skip `publish.sh` and give the template the
command from §3 — profiles `fatjar,fatjar-all-platforms,publish`, module `nexuslink-app -am`, goal
`deploy`, and the two `-Dnexuslink.repo.*` properties. The script adds convenience, not capability.

---

## 6. Giving it to users

Users need **one file** — the launcher for their platform. They never clone, never build, never see
a Maven coordinate.

| Platform | File | Note |
|---|---|---|
| Linux, macOS | `dist/nexuslink.sh` | `chmod +x` it once |
| Windows | `dist/nexuslink.bat` **and** `dist/nexuslink.ps1` | Keep them together; the `.bat` hands over to the `.ps1` |

Publish them wherever your users already look — an internal page, a shared drive, the GitHub release,
or `docs/` served as a site (the landing page in this repository already offers all three as
downloads). Then the instruction is one line:

```bash
./nexuslink.sh          # Linux / macOS
nexuslink.bat           # Windows
```

**On a machine where Maven already works, that is the whole set-up.** The launcher finds the
repository in this order:

1. `--repo <url>` on the command line
2. `NEXUSLINK_REPO_URL` in the environment
3. `~/.nexuslink/bootstrap.conf`, if you ship one
4. **`~/.m2/settings.xml`** — the `<mirror>` whose `mirrorOf` covers everything (`*`, `external:*`,
   `central`), else the first `<repository>` a profile declares, with credentials from the matching
   `<server>`

For users who have no Maven set-up, ship a `bootstrap.conf` and they still type nothing:

```
# ~/.nexuslink/bootstrap.conf  (%USERPROFILE%\.nexuslink\bootstrap.conf on Windows)
NEXUSLINK_REPO_URL=https://artifactory.example.com/artifactory/libs-release-local
NEXUSLINK_USER=svc-nexuslink
NEXUSLINK_TOKEN=...
```

### What caching actually does

- The first run downloads the JAR into `~/.nexuslink/runtime` and starts it.
- **Every run after that starts from the cache.** The launcher only asks the repository what
  `RELEASE` means; if that answer names a version already cached, nothing is downloaded.
- A download in progress is a `.part` file, renamed only after its checksum verifies — an
  interrupted download cannot leave a broken copy behind.
- If the repository cannot be reached at all — off the VPN, or down — the launcher says so and
  **runs the newest cached build** rather than refusing to start. It fails only when the cache is
  empty too.
- `--update` re-downloads the current version, `--fresh` clears the cache first, `--list` shows what
  is cached with sizes, `--clean` removes it.
- `NEXUSLINK_HOME=./nexuslink-cache` keeps everything in the current folder instead of the user's
  home — useful on locked-down or shared machines.

To move everyone to a new version, publish it. The next launch picks it up, downloads once, and
caches it. Nothing needs to be pushed to a desktop.

---

## 7. Verifying it before you announce it

Do not test against production Artifactory first. This proves the whole path locally in about ten
minutes, and it is exactly how the launcher was verified:

```bash
# 1. build the fat JAR and install it locally
./dist/publish.sh --local --host-only

# 2. deploy it into a throwaway file-based repository, as a release version
mvn deploy:deploy-file -Durl=file:///tmp/fakerepo -DrepositoryId=fake \
    -DgroupId=com.nexuslink -DartifactId=nexuslink-app -Dversion=1.2.0 \
    -Dclassifier=all -Dpackaging=jar -DgeneratePom=true \
    -Dfile="$(ls $HOME/.m2/repository/com/nexuslink/nexuslink-app/*/nexuslink-app-*-all.jar | head -1)"

# 3. serve it as if it were Artifactory
(cd /tmp/fakerepo && python3 -m http.server 8799 &)

# 4. point a settings.xml at it, with no NexusLink variables set at all
cat > /tmp/settings.xml <<'XML'
<settings><mirrors><mirror>
  <id>corp-artifactory</id><url>http://localhost:8799</url><mirrorOf>*</mirrorOf>
</mirror></mirrors></settings>
XML

# 5. run as a user would
MAVEN_SETTINGS=/tmp/settings.xml NEXUSLINK_HOME=/tmp/userhome ./dist/nexuslink.sh --where
```

Expect: `downloading nexuslink-app 1.2.0…`, then the cached path. Run it again — no download. Stop
the HTTP server and run it again — it reports the repository unreachable and starts anyway. Drop
`--where` to launch the application.

Then repeat step 5 against the real Artifactory from a machine that has never run NexusLink.

---

## 8. Handover checklist

Everything a pipeline author has to do, in order. Tick these off and it works.

- [ ] Repository created in the org, code imported (§2).
- [ ] `groupId` decided; if changed, `GROUP_PATH` in `dist/nexuslink.sh` and `$GroupPath` in
      `dist/nexuslink.ps1` changed to match.
- [ ] Build agent has **JDK 21+** and Maven 3.9+.
- [ ] Deploy target chosen: a **release** Maven repository, and its `<id>` and URL noted.
- [ ] Service-account credentials stored as pipeline secrets, written into `settings.xml` at build
      time, never into the repository.
- [ ] Pipeline runs on tags, stamps the version from the tag, and calls
      `./dist/publish.sh --deploy --version <v>` (or the raw Maven command in §3).
- [ ] One publish completed; `nexuslink-app-<version>-all.jar`, its checksums and
      `maven-metadata.xml` are visible in Artifactory.
- [ ] The three launcher files are published where users can get them.
- [ ] The §7 verification passed against the real repository, from a clean machine.
- [ ] Users told: install JDK 21, download the launcher, run it. Nothing else.

---

## 9. When something goes wrong

| Symptom | Cause |
|---|---|
| `no repository found in ~/.m2/settings.xml` | The machine has no Maven repository configured and no `NEXUSLINK_REPO_URL`. Pass `--repo <url>`, or ship a `bootstrap.conf`. |
| `could not read maven-metadata.xml … and nothing is cached` | The repository is unreachable *and* this machine has never downloaded the app. Check the URL, the VPN and the credentials. |
| `… is unreachable — running the cached 1.2.0` | Not an error. The repository was not reachable, so the cached copy started. |
| `the repository lists no versions` | Nothing has been published yet, or it was published as a `-SNAPSHOT` and the launcher asked for `RELEASE` (§4). |
| `checksum mismatch` | What arrived does not match the repository — usually a proxy rewriting responses. Nothing is cached; retry, then talk to whoever runs the repository. |
| `Java N found, but … needs 21` | A JDK older than 21. Install one, or point `JAVA_HOME` at one already installed. |
| `Error in glXCreateNewContext` | Not an error. JavaFX fell back to software rendering; the application runs. |

---

## Related documents

| | |
|---|---|
| [`DISTRIBUTION.md`](DISTRIBUTION.md) | The launcher and the publish script in full detail, option by option |
| [`PACKAGING.md`](PACKAGING.md) | The fat JAR, the trimmed runtime, and the optional native app image |
| [`RUN.md`](RUN.md) | Building and running from source, for developers |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Fork, branch, test and pull-request flow |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module layout and how to add a protocol |
