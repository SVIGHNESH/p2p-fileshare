#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GSON="$(find "$HOME/.gradle/caches" -name "gson-2.10.1.jar" 2>/dev/null | head -1)"
M2="$HOME/.m2/repository/org/openjfx"

FX_MODS="\
$M2/javafx-base/21.0.2/javafx-base-21.0.2.jar:\
$M2/javafx-base/21.0.2/javafx-base-21.0.2-linux.jar:\
$M2/javafx-controls/21.0.2/javafx-controls-21.0.2.jar:\
$M2/javafx-controls/21.0.2/javafx-controls-21.0.2-linux.jar:\
$M2/javafx-graphics/21.0.2/javafx-graphics-21.0.2.jar:\
$M2/javafx-graphics/21.0.2/javafx-graphics-21.0.2-linux.jar"

BUILD="$ROOT/build"

# ── Colours ────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}▸ $*${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ $*${RESET}"; }
fail()    { echo -e "${RED}✗ $*${RESET}"; exit 1; }

echo -e "${BOLD}"
echo "╔══════════════════════════════════════╗"
echo "║      P2P Share — Build Script        ║"
echo "╚══════════════════════════════════════╝"
echo -e "${RESET}"

# ── Checks ─────────────────────────────────────────────────────────────────
[[ -z "$GSON" ]]  && fail "gson-2.10.1.jar not found in Gradle cache. Run './gradlew dependencies' once first."
[[ ! -f "$M2/javafx-graphics/21.0.2/javafx-graphics-21.0.2-linux.jar" ]] && \
    fail "JavaFX 21 not found in ~/.m2. Run './gradlew :desktop:dependencies' once first."

# ── Clean ──────────────────────────────────────────────────────────────────
info "Cleaning previous build..."
rm -rf "$BUILD"
mkdir -p "$BUILD/core" "$BUILD/tracker" "$BUILD/desktop"

# ── Core ───────────────────────────────────────────────────────────────────
info "Compiling core module..."
find "$ROOT/core/src" -name "*.java" > /tmp/p2p_core_sources.txt
javac --release 17 \
    -cp "$GSON" \
    -d "$BUILD/core" \
    @/tmp/p2p_core_sources.txt 2>&1 | grep -v "^Note:" || true
success "core compiled"

# ── Tracker ────────────────────────────────────────────────────────────────
info "Compiling tracker module..."
find "$ROOT/tracker/src" -name "*.java" > /tmp/p2p_tracker_sources.txt
javac --release 17 \
    -cp "$GSON:$BUILD/core" \
    -d "$BUILD/tracker" \
    @/tmp/p2p_tracker_sources.txt 2>&1 | grep -v "^Note:" || true
success "tracker compiled"

info "Packaging tracker JAR..."
TRACKER_STAGE=$(mktemp -d)
cd "$TRACKER_STAGE"
jar xf "$GSON"
cp -r "$BUILD/core/." .
cp -r "$BUILD/tracker/." .
jar cfm "$BUILD/tracker.jar" \
    <(echo "Main-Class: com.p2p.tracker.TrackerServer") .
cd "$ROOT"
rm -rf "$TRACKER_STAGE"
success "tracker.jar → build/tracker.jar"

# ── Desktop ────────────────────────────────────────────────────────────────
info "Compiling desktop module..."
cp -r "$ROOT/desktop/src/main/resources/." "$BUILD/desktop/"
find "$ROOT/desktop/src" -name "*.java" > /tmp/p2p_desktop_sources.txt
javac --release 17 \
    --module-path "$FX_MODS" \
    --add-modules javafx.controls,javafx.graphics \
    -cp "$GSON:$BUILD/core" \
    -d "$BUILD/desktop" \
    @/tmp/p2p_desktop_sources.txt 2>&1 | grep -v "^Note:" || true
success "desktop compiled"

echo ""
echo -e "${GREEN}${BOLD}Build complete!${RESET}"
echo ""
echo -e "  Run tracker :  ${CYAN}./scripts/run-tracker.sh${RESET}"
echo -e "  Run desktop :  ${CYAN}./scripts/run-desktop.sh${RESET}"
echo ""
