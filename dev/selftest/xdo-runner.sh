#!/bin/bash
# Runs the dev client and performs real input for self-tests: every "XDO <args>" line a script
# echoes is run as `xdotool <args>`, with WIN replaced by the game window. Needs X11 and xdotool.
# Usage: dev/selftest/xdo-runner.sh [extra runClient args], e.g. --quickPlaySingleplayer test
cd "$(dirname "$0")/../.."
LOG=$(mktemp)
./gradlew runClient --args="$*" --console=plain > "$LOG" 2>&1 &
GRADLE=$!
done_lines=0
while kill -0 $GRADLE 2>/dev/null; do
  sleep 0.2
  mapfile -t cmds < <(grep -a '\[CHAT\] XDO ' "$LOG" | sed 's/.*\[CHAT\] XDO //')
  while [ $done_lines -lt ${#cmds[@]} ]; do
    WIN=$(xdotool search --name "Minecraft" | head -1)
    xdotool windowfocus --sync "$WIN" 2>/dev/null
    # xargs splits the line like a shell would, so 'quoted text' stays one argument (and isn't run).
    printf '%s\n' "${cmds[$done_lines]//WIN/$WIN}" | xargs xdotool
    done_lines=$((done_lines + 1))
  done
done
grep -aE "\[CHAT\] [A-Z]+ (OK|FAIL|DONE)" "$LOG" | sed 's/.*\[CHAT\] //'
