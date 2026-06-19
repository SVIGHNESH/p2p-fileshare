#!/usr/bin/env bash
# Packages core + Gson into android/libs/p2p-core.jar
# Run this once before opening the Android project in Android Studio.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GSON="$(find "$HOME/.gradle/caches" -name "gson-2.10.1.jar" 2>/dev/null | head -1)"
LIBS="$ROOT/android/libs"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}▸ $*${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
fail()    { echo -e "${RED}✗ $*${RESET}"; exit 1; }

echo -e "${BOLD}"
echo "╔══════════════════════════════════════╗"
echo "║   P2P Share — Build Android Libs     ║"
echo "╚══════════════════════════════════════╝"
echo -e "${RESET}"

[[ -z "$GSON" ]] && fail "gson-2.10.1.jar not found in Gradle cache."
[[ ! -d "$ROOT/build/core" ]] && fail "Core not compiled. Run ./scripts/build.sh first."

info "Creating android/libs/ directory..."
mkdir -p "$LIBS"

info "Packaging core + Gson into p2p-core.jar..."
STAGE=$(mktemp -d)
cd "$STAGE"

# Unpack Gson into staging
jar xf "$GSON"

# Copy compiled core classes
cp -r "$ROOT/build/core/." .

# Package everything as one JAR (fat jar for Android)
jar cf "$LIBS/p2p-core.jar" .

cd "$ROOT"
rm -rf "$STAGE"

success "android/libs/p2p-core.jar created ($(du -sh "$LIBS/p2p-core.jar" | cut -f1))"
echo ""
echo -e "  Now open ${CYAN}android/${RESET} in Android Studio and run:"
echo -e "  ${CYAN}Build → Build Bundle(s) / APK(s) → Build APK(s)${RESET}"
echo ""
echo -e "  Or from terminal inside android/:"
echo -e "  ${CYAN}./gradlew assembleDebug${RESET}"
echo ""
