# Installing and updating NexusLink

If your organisation publishes NexusLink to an internal repository, you do not need the source code,
a build tool, or an installer. You need **Java 21 or newer** and one script.

## First run

Your admin gives you a launcher — `nexuslink.sh` (Linux/macOS), or `nexuslink.bat` and
`nexuslink.ps1` together (Windows). They may also point you at an internal web page that offers all
three as downloads.

```bash
./nexuslink.sh
```

The first run downloads the application into `~/.nexuslink/runtime` and starts it. Every run after
that uses the cached copy — no network, no wait.

**Usually there is nothing to configure.** If Maven works on this machine, the launcher reads the
repository and its credentials from `~/.m2/settings.xml` — the mirror that covers everything, else
the first repository a profile declares, with the matching `<server>` for credentials. It only needs
telling when there is no Maven set-up: `./nexuslink.sh --repo <url>`, an exported
`NEXUSLINK_REPO_URL`, or a `~/.nexuslink/bootstrap.conf` your admin ships.

## Day-to-day

| Command | What it does |
|---|---|
| `nexuslink.sh` | Run the current version. Downloads only if it is not already cached. |
| `nexuslink.sh --update` | Fetch a newer build now. |
| `nexuslink.sh --offline` | Never touch the network — run the newest cached build. |
| `nexuslink.sh --version 1.2.0` | Run one specific version. Several can sit side by side. |
| `nexuslink.sh --list` | Show what is cached. |
| `nexuslink.sh --where` | Print which file would run, without running it. |
| `nexuslink.sh --fresh` | Clear the cache, then download and run — for when a build looks wrong. |
| `nexuslink.sh --clean` | Delete every cached build. Add `--version` to delete just one. |
| `nexuslink.sh --local` | Run a build installed in `~/.m2` on this machine, with no repository. |
| `nexuslink.sh --help` | The full usage text from the script itself. |

On Windows use `nexuslink.bat` (or `nexuslink.ps1` from PowerShell) with exactly the same options.

To keep the download in the current folder rather than your home directory, point `NEXUSLINK_HOME`
at a relative path: `NEXUSLINK_HOME=./nexuslink-cache ./nexuslink.sh`.

## Settings

All optional — including the repository, which falls back to `~/.m2/settings.xml`:

| Variable | Meaning |
|---|---|
| `NEXUSLINK_REPO_URL` | The Maven repository to fetch from. Without it, `~/.m2/settings.xml` is read. |
| `NEXUSLINK_VERSION` | `RELEASE` (default), `LATEST`, or an exact version |
| `NEXUSLINK_USER` / `NEXUSLINK_TOKEN` | Repository credentials, if it needs them. Without them, the `<server>` in `settings.xml` is used. |
| `NEXUSLINK_HOME` | Where the cache lives (default `~/.nexuslink`) |
| `NEXUSLINK_JAVA_OPTS` | Extra JVM options, e.g. `-Xmx2g` |
| `JAVA_HOME` | The JDK to run with, if not the one on `PATH` |

## What it checks

- The download is verified against the repository's `.sha256`, or its `.sha1` when that is what the
  repository publishes. A mismatch is refused and nothing is cached.
- A download in progress is a `.part` file and is only renamed once it verifies, so an interrupted
  download cannot leave a broken copy behind.
- Java is checked before anything else: too old or missing gets a sentence telling you what to
  install, not a stack trace.

## Troubleshooting

**"no repository found in ~/.m2/settings.xml"** — this machine has no Maven repository configured and
no `NEXUSLINK_REPO_URL`. Pass `--repo <url>`, export the variable, or ask your admin for the
`bootstrap.conf`.

**"checksum mismatch"** — what arrived does not match the repository. Usually a proxy interfering;
retry, and if it persists tell whoever maintains the repository.

**"nothing cached … and --offline was requested"** — you have never run online on this machine. Run
once with network access first.

**"Java N found, but NexusLink needs 21 or newer"** — install a newer JDK, or point `JAVA_HOME` at
one you already have.

For publishing (the other side of this), see `DISTRIBUTION.md` in the repository.
