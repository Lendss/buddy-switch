#!/system/bin/sh
# Logs what stays in memory around Buddy SW's runs, for checking that nothing lingers while it's idle.
# Run as root, detached, so it outlives Termux:
#   su -c "setsid nohup sh tools/idle-monitor.sh [minutes] [log] >/dev/null 2>&1 &"
# Columns: time, Termux's pid and PSS (MB), Termux's service in the foreground (1/0), open terminal sessions,
# Claude Code processes, Buddy SW's PSS and native heap (MB) and open screens (activities), MemAvailable (MB).
MIN=${1:-30}
LOG=${2:-/sdcard/Documents/buddy-sw-idle.log}
TUID=$(stat -c %u /data/data/com.termux)
END=$(( $(date +%s) + MIN * 60 ))
echo "time     termux pss fg sessions claude buddy_sw_pss native act mem_avail" > "$LOG"
while [ "$(date +%s)" -lt "$END" ]; do
  tp=$(pidof com.termux)
  tpss=- fg=- ses=-
  if [ -n "$tp" ]; then
    tpss=$(dumpsys meminfo com.termux | awk '/TOTAL PSS:/ {print int($3 / 1024)}')
    fg=$(dumpsys activity services com.termux/.app.TermuxService | grep -c "isForeground=true")
    # terminal sessions: shells the Termux app started on a pseudo-terminal
    ses=0
    for p in $(pgrep -P "$tp"); do readlink "/proc/$p/fd/0" | grep -q '^/dev/pts/' && ses=$((ses + 1)); done
  fi
  cl=$(pgrep -u "$TUID" -f 'claude-native/' | wc -l)
  b=$(dumpsys meminfo app.buddy.sw | awk '/TOTAL PSS:/ {t = int($3 / 1024)} /^ *Native Heap:/ {n = int($3 / 1024)}
    /Activities:/ {a = $4} END {if (t != "") print t, n, a}')
  ma=$(awk '/MemAvailable/ {print int($2 / 1024)}' /proc/meminfo)
  echo "$(date +%H:%M:%S) ${tp:--} $tpss $fg $ses $cl ${b:-- - -} $ma" >> "$LOG"
  sleep 20
done
