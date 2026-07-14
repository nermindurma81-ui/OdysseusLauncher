#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
# Odysseus + LiteLLM + 9Router — Pokreni sve odjednom
# Termux Android
# ==========================================================
source ~/.bashrc 2>/dev/null || true

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'; NC='\033[0m'

LOG_DIR="$HOME/logs"
mkdir -p "$LOG_DIR"

PID_DIR="$HOME/.pids"
mkdir -p "$PID_DIR"

echo -e "${CYAN}"
echo "  ╔══════════════════════════════════════════╗"
echo "  ║   ODYSSEUS + LITELLM + 9ROUTER            ║"
echo "  ║   Pokretanje svih servisa...              ║"
echo "  ╚══════════════════════════════════════════╝"
echo -e "${NC}"

# ── Wake lock — spriječi Android da ugasi Termux u pozadini ──
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock
  echo -e "${GREEN}[+]${NC} Wake lock aktiviran (Termux neće biti ubijen u pozadini)."
else
  echo -e "${YELLOW}[!]${NC} termux-wake-lock nije nađen. Instaliraj: pkg install termux-api"
fi

# ── Provjera da nešto već ne radi na tim portovima ────────
check_port() {
  local port="$1" name="$2"
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$port"; then
    echo -e "${YELLOW}[!]${NC} $name već radi na portu $port — preskačem pokretanje"
    return 1
  fi
  return 0
}

# ── 1. LITELLM PROXY (:4000) ───────────────────────────────
if check_port 4000 "LiteLLM"; then
  if [ ! -f "$HOME/litellm/config.yaml" ]; then
    echo -e "${RED}[X] ~/litellm/config.yaml ne postoji! Preskačem LiteLLM.${NC}"
  else
    echo -e "${GREEN}[+]${NC} Pokrećem LiteLLM proxy na :4000..."
    nohup litellm --config ~/litellm/config.yaml --port 4000 --host 127.0.0.1 \
      > "$LOG_DIR/litellm.log" 2>&1 &
    echo $! > "$PID_DIR/litellm.pid"
  fi
fi

# ── 2. 9ROUTER (:20128) ────────────────────────────────────
START_9ROUTER="$HOME/start_9router.sh"
NINE_ROUTER_BIN="/data/data/com.termux/files/usr/bin/9router"
if check_port 20128 "9Router"; then
  if [ -x "$START_9ROUTER" ]; then
    echo -e "${GREEN}[+]${NC} Pokrećem 9Router preko wrapper skripte..."
    "$START_9ROUTER"
  elif [ -f "$START_9ROUTER" ]; then
    echo -e "${GREEN}[+]${NC} Popravljam permission i pokrećem 9Router wrapper..."
    chmod +x "$START_9ROUTER" && "$START_9ROUTER"
  elif [ -x "$NINE_ROUTER_BIN" ]; then
    echo -e "${GREEN}[+]${NC} Pokrećem 9Router na :20128..."
    nohup "$NINE_ROUTER_BIN" --host 127.0.0.1 --tray --no-browser --skip-update       > "$LOG_DIR/9router.log" 2>&1 &
    echo $! > "$PID_DIR/9router.pid"
  else
    echo -e "${YELLOW}[!]${NC} 9router nije nađen ($START_9ROUTER ili $NINE_ROUTER_BIN). Preskačem."
  fi
fi

# ── Čekanje da proxyji dignu portove ───────────────────────
echo -n "    Čekam proxy servise da se dignu"
for i in 1 2 3 4 5 6 7 8; do
  sleep 1; echo -n "."
done
echo ""

# ── 3. ODYSSEUS (:7000) — pozadinski proces ────────────────
if check_port 7000 "Odysseus"; then
  echo -e "${GREEN}[+]${NC} Pokrećem Odysseus na :7000..."
  cd ~/odysseus
  nohup python app.py > "$LOG_DIR/odysseus.log" 2>&1 &
  echo $! > "$PID_DIR/odysseus.pid"
  cd - > /dev/null
else
  echo -e "${YELLOW}[!]${NC} Odysseus već radi na :7000 — ništa novo pokrenuto."
fi

echo ""
echo -e "${CYAN}════════════════════════════════════════${NC}"
echo -e "  Odysseus:  ${GREEN}http://127.0.0.1:7000${NC}  (log: ~/logs/odysseus.log)"
echo -e "  LiteLLM:   ${GREEN}http://127.0.0.1:4000${NC}  (log: ~/logs/litellm.log)"
echo -e "  9Router:   ${GREEN}http://127.0.0.1:20128${NC} (log: ~/logs/9router.log)"
echo -e "${CYAN}════════════════════════════════════════${NC}"
echo ""
echo "  Za gašenje svega: ~/stop_all.sh"
echo ""
