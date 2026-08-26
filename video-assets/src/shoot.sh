#!/usr/bin/env bash
# Captures screenshots of NexusLink for the video assets.
#
#   ./video-assets/src/shoot.sh <output-name> <tabs> [settle-seconds] [extra -D flags…]
#
# The application snapshots its own scene (-Dnexuslink.screenshot) and exits — nothing reads the
# desktop, so a capture can only ever contain NexusLink. It runs against a throwaway HOME so the
# shots show a clean profile in the dark theme, and never the operator's own history or connections.
set -euo pipefail

NAME="$1"; TABS="${2:-}"; SETTLE="${3:-9}"; shift 3 2>/dev/null || shift $# 
OUT="video-assets/screenshots/$NAME.png"
JAR="nexuslink-app/target/nexuslink.jar"
DEMO_HOME="${DEMO_HOME:-/tmp/nexuslink-demo-home}"

[[ -f "$JAR" ]] || { echo "build the fat JAR first: ./dist/publish.sh --local --host-only"; exit 1; }

# A clean profile: dark theme (the default), no onboarding overlay, no saved history.
mkdir -p "$DEMO_HOME/.java/.userPrefs/com/nexuslink"
cat > "$DEMO_HOME/.java/.userPrefs/com/nexuslink/prefs.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">
<map MAP_XML_VERSION="1.0">
  <entry key="onboardingDismissed" value="true"/>
</map>
XML

# -Duser.home is what actually matters: Java derives user.home from the passwd entry, not $HOME,
# so without it the app would read the operator's own preferences, history and saved connections.
env HOME="$DEMO_HOME" NEXUSLINK_OPEN_TABS="$TABS" \
    java -Duser.home="$DEMO_HOME" \
         -Dnexuslink.screenshot="$PWD/$OUT" -Dnexuslink.screenshot.delay="$SETTLE" "$@" \
         -jar "$JAR" 2>&1 | grep -E "^screenshot:|screenshot failed" || true
