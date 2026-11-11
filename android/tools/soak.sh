#!/usr/bin/env bash
set -uo pipefail

if [ $# -lt 2 ]; then
  echo "usage: tools/soak.sh <adb serial> <minutes> [srt port] [output directory]" >&2
  exit 2
fi

serial=$1
minutes=$2
port=${3:-9000}
out=${4:-soak-$(date +%Y%m%d-%H%M%S)}
package=com.opirl.app
interval=30
max_thermal=${SOAK_MAX_THERMAL:-5}
max_battery=${SOAK_MAX_BATTERY:-50}
mkdir -p "$out"

device() {
  timeout 20 adb -s "$serial" shell "$@" 2>/dev/null | tr -d '\r'
}

receive() {
  while :; do
    ffmpeg -nostdin -v error -i "srt://0.0.0.0:$port?mode=listener" -map 0 -f null - \
      -progress "$out/progress.txt" 2>>"$out/receiver-errors.log"
    date +%s >>"$out/receiver-restarts.log"
    sleep 1
  done
}

receive &
receiver=$!
adb -s "$serial" logcat -c
adb -s "$serial" logcat -v epoch -s StreamEngine:V AndroidRuntime:E >"$out/logcat.txt" &
logcat=$!
trap 'kill $receiver $logcat 2>/dev/null; pkill -P $receiver 2>/dev/null' EXIT

field() {
  printf '%s\n' "$1" | sed -n "s/.* $2=\([^ ]*\).*/\1/p"
}

echo "elapsed_s,pss_kb,native_kb,java_kb,threads,fds,cpu_pct,battery_c,thermal,state,app_fps,sent_kbps,target_kbps,dropped,rx_frames,rx_seconds,rx_restarts" >"$out/samples.csv"
start=$(date +%s)
last_ticks=
last_time=$start
while :; do
  now=$(date +%s)
  elapsed=$((now - start))
  [ "$elapsed" -ge $((minutes * 60)) ] && break
  pid=$(device pidof "$package")
  if [ -z "$pid" ]; then
    echo "$elapsed,,,,,,,,,DEAD,,,,,,," >>"$out/samples.csv"
    sleep "$interval"
    continue
  fi
  meminfo=$(device dumpsys meminfo "$package")
  pss=$(printf '%s\n' "$meminfo" | awk '/TOTAL PSS:/{print $3; exit}')
  native=$(printf '%s\n' "$meminfo" | awk '/Native Heap:/{print $3; exit}')
  java=$(printf '%s\n' "$meminfo" | awk '/Java Heap:/{print $3; exit}')
  threads=$(device "ls /proc/$pid/task | wc -l")
  fds=$(device "run-as $package ls /proc/$pid/fd | wc -l")
  ticks=$(device "cat /proc/$pid/stat" | awk '{print $14 + $15}')
  cpu=
  if [ -n "$last_ticks" ] && [ -n "$ticks" ] && [ "$now" -gt "$last_time" ]; then
    cpu=$(((ticks - last_ticks) / (now - last_time)))
  fi
  last_ticks=$ticks
  last_time=$now
  battery=$(device dumpsys battery | awk '/temperature/{print $2 / 10; exit}')
  thermal=$(device dumpsys thermalservice | awk -F': ' '/Thermal Status/{print $2; exit}')
  live=$(grep ' live state=' "$out/logcat.txt" | tail -1)
  frames=$(grep '^frame=' "$out/progress.txt" 2>/dev/null | tail -1 | cut -d= -f2)
  micros=$(grep '^out_time_us=' "$out/progress.txt" 2>/dev/null | tail -1 | cut -d= -f2)
  restarts=$(cat "$out/receiver-restarts.log" 2>/dev/null | wc -l)
  echo "$elapsed,$pss,$native,$java,$threads,$fds,$cpu,$battery,$thermal,$(field "$live" state),$(field "$live" fps),$(field "$live" sent),$(field "$live" target),$(field "$live" dropped),$frames,$((${micros:-0} / 1000000)),$restarts" >>"$out/samples.csv"
  degrees=${battery%.*}
  if [ "${thermal:-0}" -ge "$max_thermal" ] || [ "${degrees:-0}" -ge "$max_battery" ]; then
    adb -s "$serial" shell am force-stop "$package"
    echo "stopped the app after ${elapsed} s: thermal status $thermal, battery $battery C" | tee "$out/stopped.txt"
    break
  fi
  sleep "$interval"
done

awk -F, '
  NR == 1 { next }
  $10 == "DEAD" { dead++; next }
  $1 >= 300 && first == "" { first = $0; split($0, a, ",") }
  { last = $0; split($0, b, ",") }
  $8 > hot { hot = $8 }
  $9 > thermal { thermal = $9 }
  $10 != "LIVE" { offline++ }
  $11 != "" && (fps == "" || $11 < fps) { fps = $11 }
  END {
    printf "samples %d, not live %d, process gone %d\n", NR - 1, offline, dead
    printf "PSS %d -> %d kB, native %d -> %d kB, java %d -> %d kB\n", a[2], b[2], a[3], b[3], a[4], b[4]
    printf "threads %d -> %d, fds %d -> %d\n", a[5], b[5], a[6], b[6]
    printf "hottest battery %.1f C, highest thermal status %d, lowest fps %d\n", hot, thermal, fps
    printf "dropped frames %d, receiver restarts %d\n", b[14], b[17]
  }
' "$out/samples.csv" | tee "$out/summary.txt"
