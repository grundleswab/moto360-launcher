#!/usr/bin/env bash
# Moto 360 <-> phone <-> host bridge setup.
#
# Establishes the ADB-over-WiFi path to the watch (via the Pixel 4a phone)
# and sets up the TCP forwards/reverse used by the launcher bridge.
#
# Safe to re-run: tears down stale connections first. Intended to be called
# on demand.

set -u

PHONE_SERIAL="${PHONE_SERIAL:-192.168.12.123:37605}"
WATCH_FORWARD_PORT="${WATCH_FORWARD_PORT:-4444}"
HOST_BRIDGE_PORT="${HOST_BRIDGE_PORT:-9999}"
ADB="${ADB:-adb}"

log() { echo "[watch-bridge] $*"; }

# 1. Phone ADB over WiFi
log "connecting phone $PHONE_SERIAL"
$ADB disconnect "$PHONE_SERIAL" >/dev/null 2>&1 || true
$ADB connect "$PHONE_SERIAL" >/dev/null 2>&1 || true

for i in 1 2 3 4 5; do
    state=$($ADB -s "$PHONE_SERIAL" get-state 2>/dev/null || true)
    [ "$state" = "device" ] && break
    log "waiting for phone (attempt $i)..."
    sleep 2
done
state=$($ADB -s "$PHONE_SERIAL" get-state 2>/dev/null || true)
if [ "$state" != "device" ]; then
    log "ERROR: phone not available via ADB"
    exit 1
fi

# 2. Watch ADB hub forwarded through the phone, then connect to it
log "setting up watch ADB forward on port $WATCH_FORWARD_PORT"
$ADB -s "$PHONE_SERIAL" forward "tcp:$WATCH_FORWARD_PORT" localabstract:/adb-hub >/dev/null 2>&1 || true
$ADB connect "127.0.0.1:$WATCH_FORWARD_PORT" >/dev/null 2>&1 || true
WATCH="127.0.0.1:$WATCH_FORWARD_PORT"

for i in 1 2 3 4 5; do
    state=$($ADB -s "$WATCH" get-state 2>/dev/null || true)
    [ "$state" = "device" ] && break
    log "waiting for watch (attempt $i)..."
    sleep 2
done
state=$($ADB -s "$WATCH" get-state 2>/dev/null || true)
if [ "$state" != "device" ]; then
    log "ERROR: watch not available via ADB hub"
    exit 1
fi

# 3. Reverse tunnel for the phone->host launch bridge
log "setting up reverse tcp:$HOST_BRIDGE_PORT"
$ADB -s "$PHONE_SERIAL" reverse "tcp:$HOST_BRIDGE_PORT" "tcp:$HOST_BRIDGE_PORT" >/dev/null 2>&1 || true

log "bridge ready (watch=$WATCH)"
