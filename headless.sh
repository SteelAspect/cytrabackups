#!/usr/bin/env bash
# Runs a command (e.g. ./smoketest.sh) on an invisible virtual display, silent,
# so automated test clients never show up on the user's screen.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/PrismLauncher/java/java-runtime-delta}"
export ALSOFT_DRIVERS=null
mkdir -p build
printf '%q ' "$@" > build/.headless-cmd
exec dbus-run-session kwin_wayland --virtual --xwayland --width 1280 --height 720 --no-lockscreen \
    --exit-with-session="bash -c 'cd $(pwd) && $(cat build/.headless-cmd); echo \$? > build/.headless-exit'"
