#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GSON="$(find "$HOME/.gradle/caches" -name "gson-2.10.1.jar" 2>/dev/null | head -1)"
M2="$HOME/.m2/repository/org/openjfx"
BUILD="$ROOT/build"

RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

if [[ ! -d "$BUILD/desktop" ]]; then
    echo -e "${RED}✗ Desktop not built. Run ./scripts/build.sh first.${RESET}"
    exit 1
fi

FX_MODS="\
$M2/javafx-base/21.0.2/javafx-base-21.0.2.jar:\
$M2/javafx-base/21.0.2/javafx-base-21.0.2-linux.jar:\
$M2/javafx-controls/21.0.2/javafx-controls-21.0.2.jar:\
$M2/javafx-controls/21.0.2/javafx-controls-21.0.2-linux.jar:\
$M2/javafx-graphics/21.0.2/javafx-graphics-21.0.2.jar:\
$M2/javafx-graphics/21.0.2/javafx-graphics-21.0.2-linux.jar"

echo -e "${BOLD}${CYAN}Launching P2P Share desktop app...${RESET}"
echo ""

exec java \
    --module-path "$FX_MODS" \
    --add-modules javafx.controls,javafx.graphics \
    --enable-native-access=javafx.graphics \
    -cp "$GSON:$BUILD/core:$BUILD/desktop" \
    com.p2p.desktop.Main "$@"
