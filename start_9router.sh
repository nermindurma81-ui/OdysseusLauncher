#!/data/data/com.termux/files/usr/bin/bash
# Headless 9Router startup for Odysseus Launcher / Termux RUN_COMMAND.
# Safe to call repeatedly: it kills stale 9router processes and starts a fresh
# localhost-only instance without opening browser/menu UI.
set -u

export HOME="${HOME:-/data/data/com.termux/files/home}"
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

LOG_DIR="$HOME/logs"
PID_DIR="$HOME/.pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

NINE_ROUTER_BIN="$PREFIX/bin/9router"
LOG_FILE="$LOG_DIR/9router.log"
PID_FILE="$PID_DIR/9router.pid"

pkill -9 -f '9router' 2>/dev/null || true
sleep 1

if [ ! -x "$NINE_ROUTER_BIN" ]; then
  echo "[X] 9router nije instaliran ili nije executable: $NINE_ROUTER_BIN" >&2
  exit 127
fi

nohup "$NINE_ROUTER_BIN" --host 127.0.0.1 --tray --no-browser --skip-update \
  > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"

echo "[+] 9Router pokrenut na http://127.0.0.1:20128 (pid $(cat "$PID_FILE"), log: $LOG_FILE)"
