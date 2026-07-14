#!/data/data/com.termux/files/usr/bin/bash
# ==========================================================
# Odysseus + LiteLLM + 9Router — Zaustavi sve odjednom
# Termux Android
# ==========================================================
source ~/.bashrc 2>/dev/null || true

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'; NC='\033[0m'

PID_DIR="$HOME/.pids"

echo -e "${CYAN}"
echo "  ╔══════════════════════════════════════════╗"
echo "  ║   ODYSSEUS + LITELLM + 9ROUTER            ║"
echo "  ║   Gašenje svih servisa...                 ║"
echo "  ╚══════════════════════════════════════════╝"
echo -e "${NC}"

# ── Generička funkcija: ubij po PID fajlu, sa fallback-om na pkill ──
stop_service() {
  local pid_file="$1" name="$2" pkill_pattern="$3"
  local killed=false

  if [ -f "$pid_file" ]; then
    local pid
    pid=$(cat "$pid_file")
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      sleep 1
      # Ako je i dalje živ, ubij na silu
      if kill -0 "$pid" 2>/dev/null; then
        kill -9 "$pid" 2>/dev/null
      fi
      echo -e "${GREEN}[+]${NC} $name zaustavljen (PID $pid)."
      killed=true
    fi
    rm -f "$pid_file"
  fi

  # Fallback — uhvati eventualne "sirotinjske" procese koji nisu pokriveni PID fajlom
  if [ -n "$pkill_pattern" ]; then
    if pgrep -f "$pkill_pattern" >/dev/null 2>&1; then
      pkill -f "$pkill_pattern" 2>/dev/null
      echo -e "${GREEN}[+]${NC} $name — dodatni procesi ($pkill_pattern) ugašeni."
      killed=true
    fi
  fi

  if [ "$killed" = false ]; then
    echo -e "${YELLOW}[!]${NC} $name nije bio pokrenut."
  fi
}

stop_service "$PID_DIR/odysseus.pid" "Odysseus" "python app.py"
stop_service "$PID_DIR/9router.pid"  "9Router"  "9router"
stop_service "$PID_DIR/litellm.pid"  "LiteLLM"  "litellm --config"

# ── Skini wake lock ─────────────────────────────────────────
if command -v termux-wake-unlock >/dev/null 2>&1; then
  termux-wake-unlock
  echo -e "${GREEN}[+]${NC} Wake lock oslobođen."
fi

echo ""
echo -e "${CYAN}Svi servisi ugašeni.${NC}"
echo ""
