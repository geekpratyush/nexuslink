#!/usr/bin/env bash
# NexusLink bootstrap — downloads the app from your Artifactory once, caches it, and runs it.
#
# Users never see the source: they get this one script (or the .cmd on Windows), point it at the
# company repository, and run it. The jar is cached under ~/.nexuslink/runtime, so every run after
# the first is offline and instant; --update pulls a newer build when you want one.
#
# See --help for every option.
#
set -euo pipefail

GROUP_PATH="com/nexuslink"
ARTIFACT="nexuslink-app"
CLASSIFIER="all"          # the fat jar is deployed with this classifier
MIN_JAVA=21

usage() {
  cat <<'HELPTEXT'
NexusLink — downloads the app once from your repository, caches it, and runs it.

USAGE
  nexuslink.sh [options] [-- app arguments]

RUNNING
  (no options)          Run. Downloads only if this version is not already cached.
  --local               Run the build installed in ~/.m2 by dist/publish.sh — no repository needed.
  --offline             Never touch the network. Runs the newest cached build, or says there is none.
  --version <v>         Run a specific version (RELEASE, LATEST, or e.g. 1.2.0). Default: RELEASE.
  --repo <url>          Use this repository for one run, instead of NEXUSLINK_REPO_URL.

KEEPING IT UP TO DATE
  --update              Download this version again, replacing the cached copy.
  --fresh               Clear the cache first, then download and run. Use when a build looks wrong.

CACHE
  --list                Show what is cached, with sizes.
  --clean               Delete every cached build (the next run downloads again).
  --clean --version <v> Delete just that version.
  --where               Print the jar that would run, and exit.

OTHER
  --help                This text.
  -- <args>             Everything after -- is passed to the application.

CONFIGURATION  (environment, or KEY=VALUE lines in ~/.nexuslink/bootstrap.conf)
  NEXUSLINK_REPO_URL    Maven repository base, e.g. https://artifactory.corp/artifactory/libs-release
                        Optional: with Maven already set up, the repository and its credentials are
                        read from ~/.m2/settings.xml (a mirror of *, else the first profile
                        repository, with the matching <server> for credentials).
  NEXUSLINK_VERSION     Version to run, or RELEASE / LATEST          (default: RELEASE)
  NEXUSLINK_USER        Repository username                          (optional)
  NEXUSLINK_TOKEN       Repository password or API token             (optional)
  NEXUSLINK_HOME        Cache directory                              (default: ~/.nexuslink)
  NEXUSLINK_JAVA_OPTS   Extra JVM options, e.g. -Xmx2g
  JAVA_HOME             JDK to run with                              (default: java on PATH)

  To keep everything in the current folder instead of your home directory:
      NEXUSLINK_HOME=./nexuslink-cache ./nexuslink.sh

EXAMPLES
  NEXUSLINK_REPO_URL=https://artifactory.corp/artifactory/libs-release ./nexuslink.sh
  ./nexuslink.sh --update
  ./nexuslink.sh --version 1.2.0
  ./nexuslink.sh --fresh
  ./nexuslink.sh --clean
  ./nexuslink.sh --local

Requires Java 21 or newer.
HELPTEXT
}

log()  { printf '%s\n' "$*" >&2; }
die()  { printf 'nexuslink: %s\n' "$*" >&2; exit 1; }

# ---- configuration ---------------------------------------------------------------------------

NEXUSLINK_HOME="${NEXUSLINK_HOME:-$HOME/.nexuslink}"
CONF="$NEXUSLINK_HOME/bootstrap.conf"
if [[ -f "$CONF" ]]; then
  # A config file lets an admin ship one pre-pointed script; the environment still wins.
  while IFS='=' read -r key value; do
    [[ -z "${key// }" || "${key:0:1}" == "#" ]] && continue
    key="${key// }"
    [[ -z "${!key:-}" ]] && export "$key=${value}"
  done < "$CONF"
fi

REPO_URL="${NEXUSLINK_REPO_URL:-}"
VERSION="${NEXUSLINK_VERSION:-RELEASE}"
CACHE="$NEXUSLINK_HOME/runtime"

UPDATE=0
OFFLINE=0
LOCAL=0
ACTION="run"
APP_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --update)   UPDATE=1; shift ;;
    --offline)  OFFLINE=1; shift ;;
    --local)    LOCAL=1; shift ;;
    --version)  VERSION="${2:-}"; [[ -z "$VERSION" ]] && die "--version needs a value"; shift 2 ;;
    --repo)     REPO_URL="${2:-}"; [[ -z "$REPO_URL" ]] && die "--repo needs a URL"; shift 2 ;;
    --list)     ACTION="list"; shift ;;
    --where)    ACTION="where"; shift ;;
    --clean)    ACTION="clean"; shift ;;
    --fresh)    ACTION="fresh"; UPDATE=1; shift ;;
    --help|-h)  usage; exit 0 ;;
    --)         shift; APP_ARGS+=("$@"); break ;;
    *)          APP_ARGS+=("$1"); shift ;;
  esac
done

mkdir -p "$CACHE"

# ---- the repository, from Maven's own settings --------------------------------------------------
# Where a machine already builds with Maven, the repository and its credentials are configured once
# in settings.xml and nothing else should have to be set. Read them from there when neither --repo
# nor NEXUSLINK_REPO_URL says otherwise: a mirror that covers everything wins, then the first
# repository declared in a profile. Credentials come from the <server> whose id matches.

# Prints "<id> <url>" for the repository Maven would use, or nothing.
maven_settings_repo() {
  local file
  for file in "${MAVEN_SETTINGS:-$HOME/.m2/settings.xml}" "${M2_HOME:-/usr/share/maven}/conf/settings.xml"; do
    [[ -r "$file" ]] || continue
    local flat
    flat="$(tr -d '\n\r' < "$file" | sed 's/>[[:space:]]*</></g')"

    # a mirror of everything is what the machine is meant to go through
    local block id url of
    while IFS= read -r block; do
      [[ -z "$block" ]] && continue
      of="$(sed -n 's:.*<mirrorOf>\(.*\)</mirrorOf>.*:\1:p' <<< "$block")"
      case ",$of," in
        *,'*',*|*,external:'*',*|*,central,*) ;;
        *) continue ;;
      esac
      id="$(sed -n 's:.*<id>\([^<]*\)</id>.*:\1:p' <<< "$block" | head -1)"
      url="$(sed -n 's:.*<url>\([^<]*\)</url>.*:\1:p' <<< "$block" | head -1)"
      [[ -n "$url" ]] && { printf '%s %s' "$id" "$url"; return 0; }
    done < <(sed -e 's:<mirror>:\n<mirror>:g' <<< "$flat" | grep '<mirror>')

    # otherwise the first repository a profile declares
    while IFS= read -r block; do
      [[ -z "$block" ]] && continue
      id="$(sed -n 's:.*<id>\([^<]*\)</id>.*:\1:p' <<< "$block" | head -1)"
      url="$(sed -n 's:.*<url>\([^<]*\)</url>.*:\1:p' <<< "$block" | head -1)"
      [[ -n "$url" ]] && { printf '%s %s' "$id" "$url"; return 0; }
    done < <(sed -e 's:<repository>:\n<repository>:g' <<< "$flat" | grep '<repository>')
  done
  return 1
}

# Fills NEXUSLINK_USER / NEXUSLINK_TOKEN from the <server> with this id, if they are not already set.
maven_settings_credentials() {
  local want="$1" file flat block id
  [[ -z "$want" || -n "${NEXUSLINK_USER:-}" || -n "${NEXUSLINK_TOKEN:-}" ]] && return 0
  for file in "${MAVEN_SETTINGS:-$HOME/.m2/settings.xml}"; do
    [[ -r "$file" ]] || continue
    flat="$(tr -d '\n\r' < "$file" | sed 's/>[[:space:]]*</></g')"
    while IFS= read -r block; do
      id="$(sed -n 's:.*<id>\([^<]*\)</id>.*:\1:p' <<< "$block" | head -1)"
      [[ "$id" == "$want" ]] || continue
      NEXUSLINK_USER="$(sed -n 's:.*<username>\([^<]*\)</username>.*:\1:p' <<< "$block" | head -1)"
      NEXUSLINK_TOKEN="$(sed -n 's:.*<password>\([^<]*\)</password>.*:\1:p' <<< "$block" | head -1)"
      export NEXUSLINK_USER NEXUSLINK_TOKEN
      return 0
    done < <(sed -e 's:<server>:\n<server>:g' <<< "$flat" | grep '<server>')
  done
  return 0
}

REPO_SOURCE="NEXUSLINK_REPO_URL"
[[ -n "$REPO_URL" && "$REPO_URL" != "${NEXUSLINK_REPO_URL:-}" ]] && REPO_SOURCE="--repo"
if [[ -z "$REPO_URL" ]]; then
  if from_settings="$(maven_settings_repo)"; then
    maven_settings_credentials "${from_settings%% *}"
    REPO_URL="${from_settings#* }"
    REPO_SOURCE="~/.m2/settings.xml"
  fi
fi

# ---- local ~/.m2 --------------------------------------------------------------------------------
# The developer loop: dist/publish.sh installs the build into ~/.m2, and this runs that copy with no
# repository involved at all. Also the fallback when nothing else is configured, so a machine that
# has built the project can always run it.

M2_REPO="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}/$GROUP_PATH/$ARTIFACT"

local_jar() {   # local_jar [version] — echoes the path of a jar installed in ~/.m2, if there is one
  local want="${1:-}"
  [[ -d "$M2_REPO" ]] || return 1
  local candidate=""
  if [[ -n "$want" && "$want" != "RELEASE" && "$want" != "LATEST" ]]; then
    candidate="$M2_REPO/$want/$ARTIFACT-$want-$CLASSIFIER.jar"
    [[ -f "$candidate" ]] && { printf '%s' "$candidate"; return 0; }
    return 1
  fi
  # Newest by modification time — the one just installed is the one meant.
  local newest=""
  while IFS= read -r jar; do
    [[ -z "$newest" || "$jar" -nt "$newest" ]] && newest="$jar"
  done < <(find "$M2_REPO" -name "$ARTIFACT-*-$CLASSIFIER.jar" -type f 2>/dev/null)
  [[ -z "$newest" ]] && return 1
  printf '%s' "$newest"
}

if [[ $LOCAL -eq 1 ]]; then
  JAR="$(local_jar "$VERSION" || true)"
  if [[ -z "$JAR" ]]; then
    die "nothing installed in ${M2_REPO} — run ./dist/publish.sh --local first"
  fi
  if [[ "$ACTION" == "where" ]]; then printf '%s\n' "$JAR"; exit 0; fi
  if [[ "$ACTION" == "clean" || "$ACTION" == "fresh" ]]; then
  shopt -s nullglob
  removed=0
  for jar in "$CACHE"/$ARTIFACT-*.jar "$CACHE"/$ARTIFACT-*.jar.part; do
    # --clean --version X removes just that one; --clean alone removes every cached build.
    if [[ "$VERSION" != "RELEASE" && "$VERSION" != "LATEST" && "$(basename "$jar")" != "$ARTIFACT-$VERSION.jar"* ]]; then
      continue
    fi
    rm -f "$jar"
    removed=$((removed + 1))
  done
  rm -f "$CACHE/.metadata.xml" "$CACHE/.snapshot-metadata.xml"
  log "nexuslink: removed $removed cached file(s) from $CACHE"
  [[ "$ACTION" == "clean" ]] && exit 0
  ACTION="run"   # --fresh carries on and downloads again
fi

if [[ "$ACTION" == "list" ]]; then find "$M2_REPO" -name "$ARTIFACT-*-$CLASSIFIER.jar" -type f; exit 0; fi
  exec "${JAVA_BIN:-java}" ${NEXUSLINK_JAVA_OPTS:-} -jar "$JAR" "${APP_ARGS[@]}"
fi

# ---- cache listing ---------------------------------------------------------------------------

if [[ "$ACTION" == "clean" || "$ACTION" == "fresh" ]]; then
  shopt -s nullglob
  removed=0
  for jar in "$CACHE"/$ARTIFACT-*.jar "$CACHE"/$ARTIFACT-*.jar.part; do
    # --clean --version X removes just that one; --clean alone removes every cached build.
    if [[ "$VERSION" != "RELEASE" && "$VERSION" != "LATEST" && "$(basename "$jar")" != "$ARTIFACT-$VERSION.jar"* ]]; then
      continue
    fi
    rm -f "$jar"
    removed=$((removed + 1))
  done
  rm -f "$CACHE/.metadata.xml" "$CACHE/.snapshot-metadata.xml"
  log "nexuslink: removed $removed cached file(s) from $CACHE"
  [[ "$ACTION" == "clean" ]] && exit 0
  ACTION="run"   # --fresh carries on and downloads again
fi

if [[ "$ACTION" == "list" ]]; then
  shopt -s nullglob
  found=0
  for jar in "$CACHE"/$ARTIFACT-*.jar; do
    printf '%s  %s\n' "$(basename "$jar")" "$(du -h "$jar" | cut -f1)"
    found=1
  done
  [[ $found -eq 0 ]] && log "nothing cached in $CACHE"
  exit 0
fi

# ---- java ------------------------------------------------------------------------------------

JAVA_BIN="java"
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] && JAVA_BIN="$JAVA_HOME/bin/java"
command -v "$JAVA_BIN" >/dev/null 2>&1 || die "no Java found. NexusLink needs Java $MIN_JAVA or newer on PATH, or JAVA_HOME set."

java_major() {
  "$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}
MAJOR="$(java_major || echo 0)"
if [[ "$MAJOR" =~ ^[0-9]+$ ]] && (( MAJOR < MIN_JAVA )); then
  die "Java $MAJOR found, but NexusLink needs $MIN_JAVA or newer. Set JAVA_HOME to a newer JDK."
fi

# ---- download helpers --------------------------------------------------------------------------

fetch() {   # fetch <url> <destination> ; returns non-zero when the URL is not available
  local url="$1" dest="$2"
  local auth=()
  if [[ -n "${NEXUSLINK_USER:-}" ]]; then
    auth=(-u "${NEXUSLINK_USER}:${NEXUSLINK_TOKEN:-}")
  elif [[ -n "${NEXUSLINK_TOKEN:-}" ]]; then
    auth=(-H "Authorization: Bearer ${NEXUSLINK_TOKEN}")
  fi
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 2 "${auth[@]}" -o "$dest" "$url"
  elif command -v wget >/dev/null 2>&1; then
    local wgetauth=()
    [[ -n "${NEXUSLINK_USER:-}" ]] && wgetauth=(--user="${NEXUSLINK_USER}" --password="${NEXUSLINK_TOKEN:-}")
    wget -q "${wgetauth[@]}" -O "$dest" "$url"
  else
    die "neither curl nor wget is available to download from $url"
  fi
}

sha1_of() {
  if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 1 "$1" | cut -d' ' -f1
  else echo ""; fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum   >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else echo ""; fi
}

# The newest jar in the cache, or nothing. Used when the repository cannot be reached.
newest_cached() {
  shopt -s nullglob
  local newest="" jar
  for jar in "$CACHE"/$ARTIFACT-*.jar; do
    [[ -z "$newest" || "$jar" -nt "$newest" ]] && newest="$jar"
  done
  # Prints nothing when the cache is empty, and always succeeds: under `set -e` a non-zero return
  # here would kill the script instead of letting the caller decide what an empty cache means.
  printf '%s' "$newest"
}

# Resolves RELEASE / LATEST against the repository's maven-metadata.xml.
# Returns non-zero when the repository cannot be read, so the caller can fall back to the cache.
resolve_version() {
  local meta="$CACHE/.metadata.xml"
  fetch "$REPO_URL/$GROUP_PATH/$ARTIFACT/maven-metadata.xml" "$meta" 2>/dev/null || return 1
  local tag="release"
  [[ "$VERSION" == "LATEST" ]] && tag="latest"
  local resolved
  resolved="$(sed -n "s:.*<$tag>\(.*\)</$tag>.*:\1:p" "$meta" | head -1)"
  if [[ -z "$resolved" ]]; then
    # Some repositories publish no <release>/<latest>; fall back to the last <version> listed.
    resolved="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$meta" | tail -1)"
  fi
  [[ -z "$resolved" ]] && return 1
  printf '%s' "$resolved"
}

# A SNAPSHOT is not published under its own name: Maven writes a timestamped file
# (nexuslink-app-1.0.0-20260826.093113-1-all.jar) and records it in the version-level metadata.
# Reading that is the only way to build a working URL for a snapshot.
resolve_snapshot_file() {
  local version="$1"
  local meta="$CACHE/.snapshot-metadata.xml"
  fetch "$REPO_URL/$GROUP_PATH/$ARTIFACT/$version/maven-metadata.xml" "$meta" 2>/dev/null || return 1
  # <snapshotVersion><classifier>all</classifier>…<value>1.0.0-20260826.093113-1</value>
  local value
  value="$(tr -d '\n\r' < "$meta" \
    | sed -e 's:<snapshotVersion>:\n<snapshotVersion>:g' \
    | grep "<classifier>$CLASSIFIER</classifier>" \
    | sed -n 's:.*<value>\(.*\)</value>.*:\1:p' | head -1)"
  [[ -z "$value" ]] && return 1
  printf '%s' "$ARTIFACT-$value-$CLASSIFIER.jar"
}

# ---- resolve the version and the cached jar ----------------------------------------------------

if [[ "$VERSION" == "RELEASE" || "$VERSION" == "LATEST" ]]; then
  if [[ $OFFLINE -eq 1 ]]; then
    # Offline cannot ask the repository what "latest" means, so it uses the newest cached jar.
    newest="$(newest_cached)"
    [[ -z "$newest" ]] && die "nothing cached in $CACHE, and --offline was requested"
    JAR="$newest"
    VERSION="$(basename "$JAR" .jar)"; VERSION="${VERSION#"$ARTIFACT-"}"
  else
    if [[ -z "$REPO_URL" ]]; then
      # No repository, but this machine may have built the project — use that rather than failing.
      fallback="$(local_jar "" || true)"
      [[ -n "$fallback" ]] && { log "nexuslink: no repository configured — running the local ~/.m2 build"; JAR="$fallback"; }
      [[ -z "$fallback" ]] && die "no repository found in ~/.m2/settings.xml — set NEXUSLINK_REPO_URL or pass --repo, or run ./dist/publish.sh --local first (see --help)"
    fi
    if [[ -z "${JAR:-}" ]]; then
      if resolved="$(resolve_version)"; then
        VERSION="$resolved"
        JAR="$CACHE/$ARTIFACT-$VERSION.jar"
      else
        # The repository is unreachable — off the VPN, or down. A machine that has run before
        # already has the application; start it rather than refusing to work.
        newest="$(newest_cached)"
        [[ -z "$newest" ]] && die "could not read maven-metadata.xml from $REPO_URL (from $REPO_SOURCE), and nothing is cached in $CACHE — check the repository and your credentials"
        JAR="$newest"
        VERSION="$(basename "$JAR" .jar)"; VERSION="${VERSION#"$ARTIFACT-"}"
        log "nexuslink: $REPO_URL is unreachable — running the cached $VERSION"
      fi
    fi
  fi
else
  JAR="$CACHE/$ARTIFACT-$VERSION.jar"
fi

# ---- download when needed ----------------------------------------------------------------------

if [[ $UPDATE -eq 1 || ! -f "$JAR" ]]; then
  if [[ $OFFLINE -eq 1 ]]; then
    [[ -f "$JAR" ]] || die "$ARTIFACT $VERSION is not cached, and --offline was requested"
  else
    [[ -z "$REPO_URL" ]] && die "no repository found in ~/.m2/settings.xml — set NEXUSLINK_REPO_URL or pass --repo (see --help)"
    file="$ARTIFACT-$VERSION-$CLASSIFIER.jar"
    if [[ "$VERSION" == *-SNAPSHOT ]]; then
      snapshot_file="$(resolve_snapshot_file "$VERSION" || true)"
      [[ -n "$snapshot_file" ]] && file="$snapshot_file"
    fi
    base="$REPO_URL/$GROUP_PATH/$ARTIFACT/$VERSION/$file"
    tmp="$JAR.part"
    log "nexuslink: downloading $ARTIFACT $VERSION…"
    fetch "$base" "$tmp" || die "download failed: $base"

    # Verify against the repository's checksum when it publishes one. A corrupted or truncated
    # download that still 'succeeds' is the failure this catches; a repository with no checksum is
    # reported rather than silently trusted.
    verified=""
    if fetch "$base.sha256" "$tmp.sum" 2>/dev/null; then
      verified="sha256"
      expected="$(tr -d ' \n\r\t' < "$tmp.sum" | cut -c1-64)"
      actual="$(sha256_of "$tmp")"
    elif fetch "$base.sha1" "$tmp.sum" 2>/dev/null; then
      # Maven itself only writes .sha1; Artifactory adds .sha256 server-side. Accept either, so the
      # download is verified whichever repository it came from.
      verified="sha1"
      expected="$(tr -d ' \n\r\t' < "$tmp.sum" | cut -c1-40)"
      actual="$(sha1_of "$tmp")"
    fi
    if [[ -n "$verified" ]]; then
      rm -f "$tmp.sum"
      if [[ -n "$actual" && -n "$expected" && "$actual" != "$expected" ]]; then
        rm -f "$tmp"
        die "checksum mismatch for $ARTIFACT $VERSION — the download does not match the repository"
      fi
    else
      log "nexuslink: the repository publishes no checksum for this artifact — skipping verification"
    fi
    mv "$tmp" "$JAR"
    log "nexuslink: cached at $JAR"
  fi
fi

[[ -f "$JAR" ]] || die "$ARTIFACT $VERSION is not available"

if [[ "$ACTION" == "where" ]]; then
  printf '%s\n' "$JAR"
  exit 0
fi

exec "$JAVA_BIN" ${NEXUSLINK_JAVA_OPTS:-} -jar "$JAR" "${APP_ARGS[@]}"
