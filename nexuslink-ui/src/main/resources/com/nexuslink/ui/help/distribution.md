# Installing and updating NexusLink

If your organisation publishes NexusLink to an internal repository, you do not need the source code,
a build tool, or an installer. You need **Java 21 or newer** and one script.

## First run

Your admin gives you `nexuslink.sh` (Linux/macOS) or `nexuslink.cmd` (Windows) and a repository URL.

```bash
export NEXUSLINK_REPO_URL=https://artifactory.corp/artifactory/libs-release-local
./nexuslink.sh
```

The first run downloads the application into `~/.nexuslink/runtime` and starts it. Every run after
that uses the cached copy — no network, no wait.

If your admin has shipped a `~/.nexuslink/bootstrap.conf`, even the URL is already set and you just
run the script.

## Day-to-day

| Command | What it does |
|---|---|
| `nexuslink.sh` | Run the current version. Downloads only if it is not already cached. |
| `nexuslink.sh --update` | Fetch a newer build now. |
| `nexuslink.sh --offline` | Never touch the network — run the newest cached build. |
| `nexuslink.sh --version 1.2.0` | Run one specific version. Several can sit side by side. |
| `nexuslink.sh --list` | Show what is cached. |
| `nexuslink.sh --where` | Print which file would run, without running it. |
| `nexuslink.sh --help` | The same summary from the script itself. |

On Windows use `nexuslink.cmd` with the same options.

## Settings

All optional except the repository URL:

| Variable | Meaning |
|---|---|
| `NEXUSLINK_REPO_URL` | The Maven repository to fetch from |
| `NEXUSLINK_VERSION` | `RELEASE` (default), `LATEST`, or an exact version |
| `NEXUSLINK_USER` / `NEXUSLINK_TOKEN` | Repository credentials, if it needs them |
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

**"set NEXUSLINK_REPO_URL…"** — the script does not know where to fetch from. Export the variable, or
ask your admin for the `bootstrap.conf`.

**"checksum mismatch"** — what arrived does not match the repository. Usually a proxy interfering;
retry, and if it persists tell whoever maintains the repository.

**"nothing cached … and --offline was requested"** — you have never run online on this machine. Run
once with network access first.

**"Java N found, but NexusLink needs 21 or newer"** — install a newer JDK, or point `JAVA_HOME` at
one you already have.

For publishing (the other side of this), see `DISTRIBUTION.md` in the repository.
