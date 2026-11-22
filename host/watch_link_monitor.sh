#!/usr/bin/env bash
# Keep the watch -> phone -> host command path alive.
#
# The phone's adbd can restart on its own (WiFi drop, reboot, etc.) and that
# silently removes the `adb reverse tcp:9999` rule.  The watch still sees a
# working GMS link and keeps its cached catalog, so the UI looks fine while
# every tap fails to reach the computer.  This monitor restores each link.
set -u

ADB=/home/swab/.local/bin/adb
SS=/usr/bin/ss
PYTHON=/usr/bin/python3
TIMEOUT=/usr/bin/timeout

PHONE="${PHONE:-192.168.12.123:37605}"
PHONE_USB="${PHONE_USB:-08041JEC216750}"
HOST_PORT="${HOST_PORT:-9999}"
PHONE_PKG="${PHONE_PKG:-com.example.omarchy.watch}"
BRIDGE_ACTIVITY="${BRIDGE_ACTIVITY:-com.example.omarchy.watch/com.example.omarchy.phone.MainActivity}"
DAEMON="${DAEMON:-/home/swab/Work/moto360-launcher/host/omarchy_watch_daemon.py}"
DAEMON_LOG="${DAEMON_LOG:-/tmp/opencode/omarchy-daemon.log}"
LOG="${LOG:-/tmp/opencode/watch-link-monitor.log}"
INTERVAL="${INTERVAL:-20}"

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"; }
adbq() { "$TIMEOUT" 15 "$ADB" "$@" >/dev/null 2>&1; }
phone_state() { "$TIMEOUT" 15 "$ADB" -s "$PHONE" get-state 2>/dev/null || true; }

ensure_daemon() {
    if "$SS" -tln 2>/dev/null | grep -q ":${HOST_PORT} "; then
        return 0
    fi
    log "host daemon not listening - starting"
    setsid "$PYTHON" "$DAEMON" >> "$DAEMON_LOG" 2>&1 < /dev/null &
}

ensure_phone() {
    [ "$(phone_state)" = "device" ] && return 0

    log "phone offline - reconnecting"
    adbq disconnect "$PHONE"
    adbq connect "$PHONE"
    [ "$(phone_state)" = "device" ] && { log "phone reconnected"; return 0; }

    # WiFi ADB listener may have gone away entirely.  USB can restart it.
    if [ "$("$TIMEOUT" 15 "$ADB" -s "$PHONE_USB" get-state 2>/dev/null || true)" = "device" ]; then
        log "WiFi ADB refused - restarting adbd TCP mode via USB"
        adbq -s "$PHONE_USB" tcpip 37605
        sleep 2
        adbq connect "$PHONE"
        [ "$(phone_state)" = "device" ] && { log "phone reconnected via USB"; return 0; }
    fi

    log "ERROR: phone unreachable and no USB fallback"
    return 1
}

ensure_reverse() {
    rules=$("$TIMEOUT" 15 "$ADB" -s "$PHONE" reverse --list 2>/dev/null || true)
    if printf '%s\n' "$rules" | grep -q "tcp:${HOST_PORT} tcp:${HOST_PORT}"; then
        return 0
    fi
    log "reverse tcp:${HOST_PORT} missing - restoring"
    adbq -s "$PHONE" reverse --remove-all
    adbq -s "$PHONE" reverse "tcp:${HOST_PORT}" "tcp:${HOST_PORT}"
    rules=$("$TIMEOUT" 15 "$ADB" -s "$PHONE" reverse --list 2>/dev/null || true)
    if printf '%s\n' "$rules" | grep -q "tcp:${HOST_PORT} tcp:${HOST_PORT}"; then
        log "reverse tcp:${HOST_PORT} restored"
    else
        log "ERROR: could not restore reverse tcp:${HOST_PORT}"
    fi
}

ensure_bridge() {
    if "$TIMEOUT" 15 "$ADB" -s "$PHONE" shell 'ps -A' 2>/dev/null | grep -q "$PHONE_PKG"; then
        return 0
    fi
    log "phone bridge not running - launching"
    adbq -s "$PHONE" shell "am start -n $BRIDGE_ACTIVITY"
}

ensure_watch_hub() {
    # Best-effort ADB access to the watch for debugging/firmware work.  Not
    # needed at runtime: the launcher talks to the phone over GMS.
    rules=$("$TIMEOUT" 15 "$ADB" -s "$PHONE" forward --list 2>/dev/null || true)
    if ! printf '%s\n' "$rules" | grep -q 'tcp:4444 localabstract:/adb-hub'; then
        log "watch hub forward missing - restoring"
        adbq -s "$PHONE" forward tcp:4444 localabstract:/adb-hub
    fi
    if [ "$("$TIMEOUT" 15 "$ADB" -s 127.0.0.1:4444 get-state 2>/dev/null || true)" != "device" ]; then
        adbq disconnect 127.0.0.1:4444
        adbq connect 127.0.0.1:4444
    fi
}

cycle() {
    ensure_daemon
    if ensure_phone; then
        ensure_reverse
        ensure_bridge
        ensure_watch_hub
    fi
}

if [ "${1:-}" = "--once" ]; then
    cycle
    exit 0
fi

log "monitor started (interval ${INTERVAL}s)"
while true; do
    cycle
    sleep "$INTERVAL"
done
