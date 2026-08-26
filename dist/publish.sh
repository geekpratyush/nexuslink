#!/usr/bin/env bash
# Publish NexusLink — one command that does the right thing wherever it runs.
#
#   ./dist/publish.sh                 build, install to ~/.m2, and deploy to Artifactory if one is
#                                     configured (otherwise stop after the local install and say so)
#   ./dist/publish.sh --local         local only: never deploy, even if a repository is configured
#   ./dist/publish.sh --deploy        require a deploy: fail if no repository is configured
#   ./dist/publish.sh --version 1.2.0 set the release version before building
#   ./dist/publish.sh --host-only     build the host-only fat jar (smaller, runs on this OS only)
#
# The repository comes from, in order:
#   1. --repo <url> / --repo-id <id>
#   2. NEXUSLINK_REPO_URL / NEXUSLINK_REPO_ID
#   3. a <server> in ~/.m2/settings.xml whose id matches NEXUSLINK_REPO_ID (credentials only)
#
# Credentials always live in ~/.m2/settings.xml, never on the command line — which is also how a CI
# agent supplies them (write a settings.xml from a secret, then run this script unchanged).
set -euo pipefail

cd "$(dirname "$0")/.."

REPO_ID="${NEXUSLINK_REPO_ID:-artifactory}"
REPO_URL="${NEXUSLINK_REPO_URL:-}"
MODE="auto"          # auto | local | deploy
PLATFORMS="fatjar,fatjar-all-platforms"
SET_VERSION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --local)     MODE="local"; shift ;;
    --deploy)    MODE="deploy"; shift ;;
    --repo)      REPO_URL="${2:-}"; shift 2 ;;
    --repo-id)   REPO_ID="${2:-}"; shift 2 ;;
    --version)   SET_VERSION="${2:-}"; shift 2 ;;
    --host-only) PLATFORMS="fatjar"; shift ;;
    --help|-h)   sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)           echo "publish: unknown option $1" >&2; exit 2 ;;
  esac
done

# The GitHub Pages site serves copies of the launcher scripts; refresh them from the canonical ones
# here so the download links can never drift from what is in dist/.
sync_docs_downloads() {
  local out="docs/downloads"
  mkdir -p "$out"
  local drifted=0
  for f in nexuslink.sh nexuslink.bat nexuslink.ps1; do
    if [[ -f "$out/$f" ]] && ! cmp -s "dist/$f" "$out/$f"; then drifted=1; fi
    cp "dist/$f" "$out/$f"
  done
  [[ $drifted -eq 1 ]] && echo "publish: refreshed docs/downloads from dist/ (they had drifted — commit the change)"
  return 0
}
sync_docs_downloads

MVN="${MAVEN_CMD:-mvn}"
command -v "$MVN" >/dev/null 2>&1 || { echo "publish: no mvn on PATH" >&2; exit 1; }

# ---- version ---------------------------------------------------------------------------------

if [[ -n "$SET_VERSION" ]]; then
  echo "publish: setting the project version to $SET_VERSION"
  "$MVN" -q versions:set -DnewVersion="$SET_VERSION" -DgenerateBackupPoms=false
fi
VERSION="$("$MVN" -q -Dexec.executable=echo -Dexec.args='${project.version}' \
  --non-recursive exec:exec -Pnone 2>/dev/null | tail -1 || true)"
[[ -z "$VERSION" ]] && VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)"

# ---- 1. build and install to ~/.m2 -------------------------------------------------------------
# Always. A local install is what makes `nexuslink.sh --local` work on this machine, and in CI it is
# the step that proves the artifact builds before anything is published anywhere.

echo "publish: building $VERSION and installing to ~/.m2 …"
"$MVN" -q -P"$PLATFORMS" -pl nexuslink-app -am -DskipTests install

JAR="nexuslink-app/target/nexuslink.jar"
[[ -f "$JAR" ]] || { echo "publish: the fat jar was not built ($JAR)" >&2; exit 1; }
SIZE="$(du -h "$JAR" | cut -f1)"
echo "publish: installed com.nexuslink:nexuslink-app:$VERSION to ~/.m2  ($SIZE)"

if [[ "$MODE" == "local" ]]; then
  echo
  echo "Run it from the local install with:   ./dist/nexuslink.sh --local"
  exit 0
fi

# ---- 2. deploy, when a repository is configured -------------------------------------------------

have_server_in_settings() {
  local settings="$HOME/.m2/settings.xml"
  [[ -f "$settings" ]] && grep -q "<id>$REPO_ID</id>" "$settings"
}

if [[ -z "$REPO_URL" ]]; then
  if [[ "$MODE" == "deploy" ]]; then
    echo "publish: --deploy was requested but no repository is configured." >&2
    echo "         Set NEXUSLINK_REPO_URL, or pass --repo <url>." >&2
    exit 1
  fi
  echo
  echo "publish: no Artifactory configured — stopping after the local install."
  echo "         Set NEXUSLINK_REPO_URL (and a <server id=$REPO_ID> in ~/.m2/settings.xml) to deploy."
  echo "Run it from the local install with:   ./dist/nexuslink.sh --local"
  exit 0
fi

if ! have_server_in_settings; then
  echo "publish: note — no <server><id>$REPO_ID</id> in ~/.m2/settings.xml." >&2
  echo "         Deploying anyway; this only works if the repository allows anonymous writes." >&2
fi

echo "publish: deploying $VERSION to $REPO_URL (server id: $REPO_ID) …"
"$MVN" -q -P"$PLATFORMS",publish -pl nexuslink-app -am -DskipTests deploy \
  -Dnexuslink.repo.id="$REPO_ID" \
  -Dnexuslink.repo.url="$REPO_URL" \
  -Dnexuslink.snapshot.repo.url="${NEXUSLINK_SNAPSHOT_REPO_URL:-$REPO_URL}"

echo
echo "publish: done."
echo "  coordinates : com.nexuslink:nexuslink-app:$VERSION:all"
echo "  users run   : NEXUSLINK_REPO_URL=$REPO_URL ./nexuslink.sh"
