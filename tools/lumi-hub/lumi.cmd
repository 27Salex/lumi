@echo off
rem One command for everything Lumi needs on this PC: the search engine (SearXNG in WSL) + Lumi Hub (agents, Orbit, web search via Claude).
rem   lumi            start both (the hub stays open in this window; Ctrl+C stops it)
rem   lumi status     show what is running
if /i "%~1"=="status" (
  wsl -d Ubuntu -- bash -c "pgrep -f searx.webapp >/dev/null && echo SearXNG: running || echo SearXNG: stopped"
  netstat -ano | findstr /R /C:"127.0.0.1:8766 .*LISTENING" >nul && (echo Lumi Hub: running) || (echo Lumi Hub: stopped)
  tailscale serve status
  exit /b 0
)
call lumi-search.cmd
echo Starting Lumi Hub...
call lumi-hub.cmd %*
