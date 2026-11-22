# Moto 360 Omarchy launcher

Turns a Gen 1 Moto 360 into a small remote control for a Linux desktop: an
icon-only app grid, direct app launch, workspace up/down, focus/next-window and
close. The watch talks to a paired Android phone over the Google Wearable Data
Layer; the phone reaches a host daemon over TCP (normally an `adb reverse`
tunnel). When docked, the watch uses its normal clock screensaver.

No credentials or device addresses are stored in this repository. Local values
live in `~/.config/moto360-launcher/env`.

## Architecture

```
watch app  --GMS Data Layer-->  phone app  --TCP->  host daemon  --hyprctl-->  desktop
   (Wear 1.x)                  (WearableListenerService)  127.0.0.1:PORT      (Omarchy/Hyprland)
```

The host daemon scans XDG application directories, sends icons and app ids to
the watch, and accepts `launch`, `workspace`, `focus` and `close` commands.
A small monitor keeps the ADB tunnel, phone bridge and daemon alive.

## Requirements

- A watch running Wear 1.x with Google Play Services (tested on Gen 1 Moto 360,
  Android 6.0, API 23). The UI layout is tuned for its 320x290 round screen.
- An Android phone with Google Play Services and ADB available (WiFi ADB is
  recommended; a USB serial can be used to restart it).
- A Linux desktop with:
  - `hyprctl` and the Omarchy `default.hypr.monitor-workspaces` Lua module
    (everything is overridable — see `config.env.example`)
  - Python 3 with PyGObject (`Gio`, `Gtk`, `GdkPixbuf`)
  - `adb`, `ss`, `timeout`, `gtk-launch`/`gio`
- For building the APKs: JDK 17, Android SDK platform 23 (watch) and 33
  (phone), build-tools 30.0.3, and the extracted Play Services / support AARs.

The host side is written for Omarchy/Hyprland. On another desktop, set
`MOTO360_WORKSPACE_LUA`, `MOTO360_FOCUS_DISPATCH` and `MOTO360_CLOSE_DISPATCH`
to equivalent commands, or adapt `host/omarchy_watch_daemon.py`.

## Configuration

```sh
mkdir -p ~/.config/moto360-launcher
cp config.env.example ~/.config/moto360-launcher/env
$EDITOR ~/.config/moto360-launcher/env
```

At minimum set `MOTO360_PHONE` (for example `<host>:<adb-port>`). The other
values have usable defaults. Values present in the config file are applied when
a script starts; omit a variable to keep its default or an externally supplied
value. `MOTO360_CONFIG` can point at a different file.

The workspace count is read from the host daemon at runtime: set
`MOTO360_WORKSPACES` on the host and rebuild the watch APK so it picks up the
`workspace_count` field in the catalog. The watch falls back to 5 if absent.

## Build

```sh
./build_apk.sh watch 23 /tmp/omarchy-watch.apk
./build_apk.sh phone 33 /tmp/omarchy-phone.apk
```

The script uses `ANDROID_SDK_ROOT`/`ANDROID_HOME`, `JAVA_HOME`, `ANDROID_BUILD_TOOLS`,
`AARS_DIR` and `MOTO360_KEYSTORE`. `AARS_DIR` must contain the extracted AAR
directories; the default lists are Play Services 8.4.0 / support 24.2.1 for
API 23 and Play Services / AndroidX 18.2.0 for API 33. Override with `AAR_LIST`
if you use different versions. It generates a standard Android debug keystore
if one is not present. Install the two APKs on the watch and phone, then open
the phone app once so its foreground bridge service starts.

## Run

On the desktop:

```sh
setsid python3 host/omarchy_watch_daemon.py >/path/to/daemon.log 2>&1 </dev/null &
host/watch_bridge.sh
host/watch_link_monitor.sh --once   # optional health check
```

For continuous operation, run the monitor as a systemd user service; a template
is in `systemd/omarchy-watch-link.service.example`. It checks every 20 seconds
that the daemon is listening, the phone is reachable, `adb reverse` is present,
the phone bridge is running and the watch ADB hub forward is up.

## Ports and paths

- `MOTO360_PORT` (default 9999): host TCP listener; phone side uses `adb reverse`.
- `MOTO360_WATCH_FORWARD_PORT` (default 4444): optional watch ADB debug hub.
- Logs default to `${XDG_STATE_HOME:-$HOME/.local/state}/moto360-launcher/`.

## Security note

The daemon only accepts a fixed set of commands, and app launches are restricted
to ids from the current desktop catalog. It binds to loopback by default. Do not
expose the port to a public network without adding your own authentication.
