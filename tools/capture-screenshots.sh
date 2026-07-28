#!/usr/bin/env bash
#
# Capture launcher screenshots from a connected head unit or emulator.
#
#   ./tools/capture-screenshots.sh            # capture every screen, prompting between
#   ./tools/capture-screenshots.sh dashboard  # capture a single named screen
#
# Files land in docs/screenshots/<name>.png, which is what the README links to.

set -euo pipefail

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs/screenshots"
mkdir -p "$OUT_DIR"

SCREENS=(
    "dashboard:Home dashboard — clock, weather and media"
    "drawer:App drawer with search"
    "settings:Settings"
    "gradients:Colour theme picker"
    "setup:First-run setup"
)

if ! adb get-state >/dev/null 2>&1; then
    echo "No device found. Connect the head unit (USB debugging on) or start an emulator." >&2
    exit 1
fi

capture() {
    local name="$1"
    adb exec-out screencap -p > "$OUT_DIR/$name.png"
    echo "  saved docs/screenshots/$name.png"
}

if [ $# -gt 0 ]; then
    capture "$1"
    exit 0
fi

for entry in "${SCREENS[@]}"; do
    name="${entry%%:*}"
    description="${entry#*:}"
    read -r -p "Open: $description — then press Enter (s to skip) " answer
    [ "$answer" = "s" ] && continue
    capture "$name"
done

echo
echo "Done. Commit them with:"
echo "  git add docs/screenshots && git commit -m 'Add screenshots' && git push"
