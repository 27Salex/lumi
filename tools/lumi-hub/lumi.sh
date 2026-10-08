#!/usr/bin/env bash
# Linux/macOS equivalent of lumi.cmd: start Lumi Hub (and SearXNG if it is installed here).
#   ./lumi.sh            start both (the hub stays in this terminal; Ctrl+C stops it)
#   ./lumi.sh status     show what is running
set -u
cd "$(dirname "$0")"

if [ "${1:-}" = "status" ]; then
  pgrep -f searx.webapp >/dev/null 2>&1 && echo "SearXNG: running" || echo "SearXNG: stopped"
  pgrep -f lumi_hub.py >/dev/null 2>&1 && echo "Lumi Hub: running" || echo "Lumi Hub: stopped"
  command -v tailscale >/dev/null 2>&1 && tailscale serve status
  exit 0
fi

# SearXNG is optional: start it only when this machine has it (a local clone in ~/searxng or a `searxng` command)
if ! pgrep -f searx.webapp >/dev/null 2>&1; then
  if [ -d "$HOME/searxng" ] && [ -x "$HOME/searxng/venv/bin/python" ]; then
    echo "Starting SearXNG..."
    (cd "$HOME/searxng" && SEARXNG_SETTINGS_PATH="${SEARXNG_SETTINGS_PATH:-$HOME/searxng/settings.yml}" \
      nohup ./venv/bin/python -m searx.webapp >/tmp/searxng.log 2>&1 &)
  else
    echo "SearXNG not found (optional): web search will use the phone."
  fi
fi

PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "Python 3 is required."; exit 1; }
echo "Starting Lumi Hub..."
[ $# -eq 0 ] && set -- serve
exec "$PY" lumi_hub.py "$@"
