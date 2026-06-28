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

CORE_SOURCES="$(mktemp)"; TRACKER_SOURCES="$(mktemp)"; DESKTOP_SOURCES="$(mktemp)"
trap 'rm -f "$CORE_SOURCES" "$TRACKER_SOURCES" "$DESKTOP_SOURCES"' EXIT

# ── Colours ────────────────────────────────────────────────────────────────
# Only emit ANSI escapes when stdout is a terminal, so CI/log output stays clean.
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
    CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; CYAN=''; BOLD=''; RESET=''
fi

info()    { echo -e "${CYAN}▸ $*${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ $*${RESET}"; }
fail()    { echo -e "${RED}✗ $*${RESET}"; exit 1; }

# Compile with javac, filtering only the noisy "Note:" lines for display while
# preserving javac's real exit code. A bare `javac ... | grep -v "^Note:"`
# would (a) mask compile failures behind grep's exit code and (b) make clean
# compiles look like failures, because grep exits 1 when it filters out every
# line. Capturing output and returning javac's own status fixes both.
run_javac() {
    local logfile rc
    logfile="$(mktemp)"
    set +e
    javac "$@" > "$logfile" 2>&1
    rc=$?
    set -e
    grep -v "^Note:" "$logfile" || true
    rm -f "$logfile"
    return "$rc"
}

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
find "$ROOT/core/src/main" -name "*.java" > "$CORE_SOURCES"
run_javac --release 17 \
    -cp "$GSON" \
    -d "$BUILD/core" \
    @"$CORE_SOURCES" || fail "core compilation failed"
[[ -d "$ROOT/core/src/main/resources" ]] && cp -r "$ROOT/core/src/main/resources/." "$BUILD/core/"
success "core compiled"

# ── Tracker ────────────────────────────────────────────────────────────────
info "Compiling tracker module..."
find "$ROOT/tracker/src/main" -name "*.java" > "$TRACKER_SOURCES"
run_javac --release 17 \
    -cp "$GSON:$BUILD/core" \
    -d "$BUILD/tracker" \
    @"$TRACKER_SOURCES" || fail "tracker compilation failed"
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
find "$ROOT/desktop/src/main" -name "*.java" > "$DESKTOP_SOURCES"
run_javac --release 17 \
    --module-path "$FX_MODS" \
    --add-modules javafx.controls,javafx.graphics \
    -cp "$GSON:$BUILD/core" \
    -d "$BUILD/desktop" \
    @"$DESKTOP_SOURCES" || fail "desktop compilation failed"
success "desktop compiled"

echo ""
echo -e "${GREEN}${BOLD}Build complete!${RESET}"
echo ""
echo -e "  Run tracker :  ${CYAN}./scripts/run-tracker.sh${RESET}"
echo -e "  Run desktop :  ${CYAN}./scripts/run-desktop.sh${RESET}"
echo ""
