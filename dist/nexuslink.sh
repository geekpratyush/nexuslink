#!/usr/bin/env bash
# NexusLink bootstrap — downloads the app from your Artifactory once, caches it, and runs it.
#
# Users never see the source: they get this one script (or the .cmd on Windows), point it at the
# company repository, and run it. The jar is cached under ~/.nexuslink/runtime, so every run after
# the first is offline and instant; --update pulls a newer build when you want one.
#
#   ./nexuslink.sh                     run (downloading on the first run)
#   ./nexuslink.sh --update            force a re-download, even if a cached copy exists
#   ./nexuslink.sh --offline           never touch the network; fail if nothing is cached
#   ./nexuslink.sh --version 1.2.0     run a specific version
#   ./nexuslink.sh --list              show what is cached
#   ./nexuslink.sh --where             print the jar that would run, and exit
#
# Configuration (environment, or a ~/.nexuslink/bootstrap.conf of KEY=VALUE lines):
#   NEXUSLINK_REPO_URL   Maven repository base, e.g. https://artifactory.corp/artifactory/libs-release
#   NEXUSLINK_VERSION    version to run, or LATEST / RELEASE  (default: RELEASE)
#   NEXUSLINK_USER       repository username           (optional)
#   NEXUSLINK_TOKEN      repository password or API token (optional)
#   NEXUSLINK_HOME       cache directory              (default: ~/.nexuslink)
#   JAVA_HOME            JDK to run with              (default: whatever java is on PATH)
set -euo pipefail

GROUP_PATH="com/nexuslink"
ARTIFACT="nexuslink-app"
CLASSIFIER="all"          # the fat jar is deployed with this classifier
MIN_JAVA=21

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
ACTION="run"
APP_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --update)   UPDATE=1; shift ;;
    --offline)  OFFLINE=1; shift ;;
    --version)  VERSION="${2:-}"; [[ -z "$VERSION" ]] && die "--version needs a value"; shift 2 ;;
    --repo)     REPO_URL="${2:-}"; [[ -z "$REPO_URL" ]] && die "--repo needs a URL"; shift 2 ;;
    --list)     ACTION="list"; shift ;;
    --where)    ACTION="where"; shift ;;
    --help|-h)  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --)         shift; APP_ARGS+=("$@"); break ;;
    *)          APP_ARGS+=("$1"); shift ;;
  esac
done

mkdir -p "$CACHE"

# ---- cache listing ---------------------------------------------------------------------------

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

# Resolves RELEASE / LATEST against the repository's maven-metadata.xml.
resolve_version() {
  local meta="$CACHE/.metadata.xml"
  fetch "$REPO_URL/$GROUP_PATH/$ARTIFACT/maven-metadata.xml" "$meta" \
    || die "could not read maven-metadata.xml from $REPO_URL — check NEXUSLINK_REPO_URL and your credentials"
  local tag="release"
  [[ "$VERSION" == "LATEST" ]] && tag="latest"
  local resolved
  resolved="$(sed -n "s:.*<$tag>\(.*\)</$tag>.*:\1:p" "$meta" | head -1)"
  if [[ -z "$resolved" ]]; then
    # Some repositories publish no <release>/<latest>; fall back to the last <version> listed.
    resolved="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$meta" | tail -1)"
  fi
  [[ -z "$resolved" ]] && die "the repository lists no versions of $ARTIFACT"
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
    shopt -s nullglob
    newest=""
    for jar in "$CACHE"/$ARTIFACT-*.jar; do
      [[ -z "$newest" || "$jar" -nt "$newest" ]] && newest="$jar"
    done
    [[ -z "$newest" ]] && die "nothing cached in $CACHE, and --offline was requested"
    JAR="$newest"
    VERSION="$(basename "$JAR" .jar)"; VERSION="${VERSION#"$ARTIFACT-"}"
  else
    [[ -z "$REPO_URL" ]] && die "set NEXUSLINK_REPO_URL to your Artifactory repository (see --help)"
    VERSION="$(resolve_version)"
    JAR="$CACHE/$ARTIFACT-$VERSION.jar"
  fi
else
  JAR="$CACHE/$ARTIFACT-$VERSION.jar"
fi

# ---- download when needed ----------------------------------------------------------------------

if [[ $UPDATE -eq 1 || ! -f "$JAR" ]]; then
  if [[ $OFFLINE -eq 1 ]]; then
    [[ -f "$JAR" ]] || die "$ARTIFACT $VERSION is not cached, and --offline was requested"
  else
    [[ -z "$REPO_URL" ]] && die "set NEXUSLINK_REPO_URL to your Artifactory repository (see --help)"
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
