#!/usr/bin/env bash
#
# Compiles and runs the core JUnit 5 test suite.
#
# Reproducibility is the point (BT.2): the suite must run identically on a clean
# checkout and in CI. Every dependency it needs — the JUnit console launcher and
# gson — is provisioned as a pinned, checksum-verified jar from Maven Central
# into .test-libs/ (gitignored), rather than discovered from whatever happens to
# be in the local ~/.m2 / ~/.gradle caches. gson is reused from the Gradle cache
# when present (the fast path on dev machines) and downloaded otherwise, so the
# runner is fully self-contained on a fresh clone. Cached jars make reruns
# offline. (Note: test.sh needs only gson, not JavaFX, so unlike build.sh it can
# be made fully reproducible today; the build.sh JavaFX story is BT.3/BT.4.)
#
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build"
TEST_LIBS="$ROOT/.test-libs"

# ── Pinned dependencies (Maven Central) ──────────────────────────────────────
JUNIT_VERSION="1.11.3"
JUNIT_JAR="$TEST_LIBS/junit-platform-console-standalone-$JUNIT_VERSION.jar"
JUNIT_SHA256="33440476714985bda2584ed6c70d0d877085012343be67e961dcf80dac596227"
JUNIT_URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"

GSON_VERSION="2.10.1"
GSON_SHA256="4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593"
GSON_URL="https://repo1.maven.org/maven2/com/google/code/gson/gson/$GSON_VERSION/gson-$GSON_VERSION.jar"

# ── Colours (only when stdout is a terminal, so CI logs stay clean) ──────────
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'
else
    RED=''; GREEN=''; CYAN=''; BOLD=''; RESET=''
fi
info()    { echo -e "${CYAN}▸ $*${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
fail()    { echo -e "${RED}✗ $*${RESET}"; exit 1; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "Need sha256sum or shasum to verify downloads."
    fi
}

# provision_jar <dest> <url> <sha256> <label>
# Ensures <dest> exists and matches <sha256>, downloading from <url> if needed.
provision_jar() {
    local dest="$1" url="$2" want="$3" label="$4"
    mkdir -p "$(dirname "$dest")"
    if [[ -f "$dest" ]] && [[ "$(sha256_of "$dest")" != "$want" ]]; then
        info "Cached $label failed checksum — re-downloading."
        rm -f "$dest"
    fi
    if [[ ! -f "$dest" ]]; then
        info "Downloading $label..."
        if command -v curl >/dev/null 2>&1; then
            curl -fSL -o "$dest" "$url" || fail "Download failed (no network?): $url"
        elif command -v wget >/dev/null 2>&1; then
            wget -qO "$dest" "$url" || fail "Download failed (no network?): $url"
        else
            fail "Need curl or wget to download $label."
        fi
    fi
    local got; got="$(sha256_of "$dest")"
    [[ "$got" == "$want" ]] || fail "$label checksum mismatch: expected $want got $got"
    success "$label ready (verified)"
}

# ── Provision dependencies ───────────────────────────────────────────────────
provision_jar "$JUNIT_JAR" "$JUNIT_URL" "$JUNIT_SHA256" "junit-platform-console-standalone $JUNIT_VERSION"

# gson: reuse the Gradle cache when present (fast path), else fetch from Central.
GSON="$(find "$HOME/.gradle/caches" -name "gson-$GSON_VERSION.jar" 2>/dev/null | head -1)"
if [[ -z "$GSON" ]]; then
    GSON="$TEST_LIBS/gson-$GSON_VERSION.jar"
    provision_jar "$GSON" "$GSON_URL" "$GSON_SHA256" "gson $GSON_VERSION"
fi

# ── Compile core (main only) so tests run against fresh bytecode ─────────────
info "Compiling core module..."
CORE_SOURCES="$(mktemp)"; TEST_SOURCES="$(mktemp)"
trap 'rm -f "$CORE_SOURCES" "$TEST_SOURCES"' EXIT
mkdir -p "$BUILD/core" "$BUILD/test"
find "$ROOT/core/src/main" -name "*.java" > "$CORE_SOURCES"
javac --release 17 -cp "$GSON" -d "$BUILD/core" @"$CORE_SOURCES" || fail "core compilation failed"

# ── Compile tests ────────────────────────────────────────────────────────────
info "Compiling core tests..."
find "$ROOT/core/src/test" -name "*.java" > "$TEST_SOURCES"
[[ -s "$TEST_SOURCES" ]] || fail "No test sources found under core/src/test"
javac --release 17 -cp "$BUILD/core:$GSON:$JUNIT_JAR" -d "$BUILD/test" @"$TEST_SOURCES" \
    || fail "test compilation failed"
success "tests compiled"

# ── Run ──────────────────────────────────────────────────────────────────────
echo -e "${BOLD}Running core test suite...${RESET}"
java -jar "$JUNIT_JAR" execute \
    --class-path "$BUILD/core:$BUILD/test:$GSON" \
    --scan-class-path="$BUILD/test" \
    --fail-if-no-tests \
    --disable-banner \
    --details=tree
