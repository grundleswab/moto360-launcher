#!/usr/bin/env bash
# Keep the watch -> phone -> host command path alive.
#
# The phone's adbd can restart on its own (WiFi drop, reboot, etc.) and that
# silently removes the `adb reverse tcp:9999` rule.  The watch still sees a
# working GMS link and keeps its cached catalog, so the UI looks fine while
# every tap fails to reach the computer.  This monitor restores each link.
set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${MOTO360_CONFIG:-$HOME/.config/moto360-launcher/env}"
[ -r "$CONFIG_FILE" ] && . "$CONFIG_FILE"

ADB="${ADB:-adb}"
SS="${SS:-$(command -v ss || echo /usr/bin/ss)}"
PYTHON="${PYTHON:-$(command -v python3 || echo /usr/bin/python3)}"
TIMEOUT="${TIMEOUT:-$(command -v timeout || echo /usr/bin/timeout)}"

# Device addresses live in the local config, never in this repository.
PHONE="${MOTO360_PHONE:-}"
PHONE_USB="${MOTO360_PHONE_USB:-}"
PHONE_TCP_PORT="${MOTO360_PHONE_TCP_PORT:-${PHONE##*:}}"
HOST_PORT="${MOTO360_PORT:-9999}"
WATCH_FORWARD_PORT="${MOTO360_WATCH_FORWARD_PORT:-4444}"
PHONE_PKG="${MOTO360_PHONE_PKG:-com.example.omarchy.watch}"
BRIDGE_ACTIVITY="${MOTO360_BRIDGE_ACTIVITY:-com.example.omarchy.watch/com.example.omarchy.phone.MainActivity}"
DAEMON="${MOTO360_DAEMON:-$SCRIPT_DIR/omarchy_watch_daemon.py}"

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/moto360-launcher"
mkdir -p "$STATE_DIR"
DAEMON_LOG="${MOTO360_DAEMON_LOG:-$STATE_DIR/daemon.log}"
LOG="${MOTO360_MONITOR_LOG:-$STATE_DIR/monitor.log}"
INTERVAL="${MOTO360_MONITOR_INTERVAL:-20}"

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
    if [ -n "$PHONE_USB" ] && [ "$("$TIMEOUT" 15 "$ADB" -s "$PHONE_USB" get-state 2>/dev/null || true)" = "device" ]; then
        log "WiFi ADB refused - restarting adbd TCP mode via USB"
        adbq -s "$PHONE_USB" tcpip "$PHONE_TCP_PORT"
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
    if ! printf '%s\n' "$rules" | grep -q "tcp:${WATCH_FORWARD_PORT} localabstract:/adb-hub"; then
        log "watch hub forward missing - restoring"
        adbq -s "$PHONE" forward "tcp:${WATCH_FORWARD_PORT}" localabstract:/adb-hub
    fi
    if [ "$("$TIMEOUT" 15 "$ADB" -s "127.0.0.1:${WATCH_FORWARD_PORT}" get-state 2>/dev/null || true)" != "device" ]; then
        adbq disconnect "127.0.0.1:${WATCH_FORWARD_PORT}"
        adbq connect "127.0.0.1:${WATCH_FORWARD_PORT}"
    fi
}

cycle() {
    ensure_daemon
    if [ -z "$PHONE" ]; then
        log "ERROR: MOTO360_PHONE is not configured in $CONFIG_FILE"
        return 1
    fi
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
