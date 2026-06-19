#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/build/tracker.jar"

RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

if [[ ! -f "$JAR" ]]; then
    echo -e "${RED}✗ tracker.jar not found. Run ./scripts/build.sh first.${RESET}"
    exit 1
fi

PORT="${1:-9000}"

echo -e "${BOLD}${CYAN}Starting P2P Share Tracker on port $PORT...${RESET}"
echo -e "${CYAN}Press Ctrl+C to stop.${RESET}"
echo ""

exec java -jar "$JAR" "$PORT"
