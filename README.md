# Moto 360 Omarchy launcher

A Gen 1 Moto 360 becomes a small remote control for a Linux desktop: an
icon-only app grid, direct app launch, workspace up/down, focus-next-window and
close. When docked, the watch still uses its normal clock screensaver.

```
watch app  --GMS Data Layer-->  phone app  --TCP over adb reverse-->  host daemon  --hyprctl-->  desktop
```

The host daemon scans XDG application directories, sends icons and app ids to
the watch, and accepts `launch`, `workspace`, `focus` and `close` commands.
A systemd user service keeps the ADB tunnel, phone bridge and daemon alive.

## Requirements

- **Watch:** Wear 1.x with Google Play Services (tested on Gen 1 Moto 360,
  Android 6.0.1, API 23). The layout is tuned for its 320x290 round screen.
- **Phone:** Android phone with Google Play Services, the Wear OS companion app
  and ADB available. For debugging over Bluetooth, the phone must be unlocked
  while enabling the option.
- **Desktop:** Hyprland/Omarchy (`hyprctl`, the `default.hypr.monitor-workspaces`
  Lua module), Python 3 with PyGObject (`Gio`, `Gtk`, `GdkPixbuf`), `adb`, `ss`,
  `timeout`, `gtk-launch`/`gio`. The Hyprland-specific commands are configurable.
- **Build:** JDK 17, Android SDK platforms 23 (watch) and 33 (phone), build-tools
  30.0.3, and the extracted Play Services / AndroidX / support AARs.

## Configuration

The repository contains no device addresses or personal paths. Copy the template
and edit it:

```sh
mkdir -p ~/.config/moto360-launcher
cp config.env.example ~/.config/moto360-launcher/env
$EDITOR ~/.config/moto360-launcher/env
```

At minimum set `MOTO360_PHONE` to the phone's adb address, e.g.
`<host>:<adb-port>`. Other values have defaults. Variables present in the config
file are applied on startup; omit one to keep its default or an externally
supplied value. `MOTO360_CONFIG` can point at a different file.

Key variables:

| Variable | Default | Purpose |
|---|---|---|
| `MOTO360_PHONE` | *(none)* | Phone adb address for bridge/monitor |
| `MOTO360_PHONE_USB` | *(none)* | Optional USB serial to restart WiFi ADB |
| `MOTO360_PORT` | `9999` | Host daemon TCP port |
| `MOTO360_WATCH_FORWARD_PORT` | `4444` | Watch adb-hub forward |
| `MOTO360_WORKSPACES` | `5` | Workspace count sent in the catalog |
| `MOTO360_WORKSPACE_LUA` | Omarchy module | `hyprctl eval` template |
| `MOTO360_FOCUS_DISPATCH`, `MOTO360_CLOSE_DISPATCH` | Omarchy dispatch names | Window commands |
| `MOTO360_HYPRCTL` | `hyprctl` | hyprctl binary |
| `AARS_DIR`, `AAR_LIST` | see template | Build dependencies |

The workspace count is read by the watch app from the `workspace_count` catalog
field, with a fallback of 5. Rebuild the watch APK after changing it.

## Build

```sh
./build_apk.sh watch 23 /tmp/omarchy-watch.apk
./build_apk.sh phone 33 /tmp/omarchy-phone.apk
```

The script uses `ANDROID_SDK_ROOT`/`ANDROID_HOME`, `JAVA_HOME`,
`ANDROID_BUILD_TOOLS`, `AARS_DIR` and `MOTO360_KEYSTORE`. `AARS_DIR` must contain
the extracted AAR directories. Defaults: Play Services 8.4.0 / support 24.2.1
for API 23, Play Services / AndroidX 18.2.0 for API 33. Override with `AAR_LIST`
for other versions. A standard Android debug keystore is generated if missing.

## Bluetooth ADB debugging on the watch

Runtime launching uses GMS and does not need watch ADB; ADB is only for
installing/updating the watch app and debugging. To enable it:

1. **Phone:** open the Wear OS companion app, go to Settings and enable
   **Debugging over Bluetooth**. The screen should report both **host** and
   **target** as connected. The phone must be unlocked for this step.
2. **Watch:** open Settings > Developer options and enable both **ADB
   debugging** and **Debug over Bluetooth**. Keep watch and phone near each
   other.
3. **Computer:** with the phone connected by ADB, run:

   ```sh
   adb -s <phone-adb-address> forward tcp:4444 localabstract:/adb-hub
   adb connect 127.0.0.1:4444
   adb devices          # 127.0.0.1:4444 should show as "device"
   adb -s 127.0.0.1:4444 shell getprop ro.product.model
   ```

4. Install and start the watch app:

   ```sh
   adb -s 127.0.0.1:4444 install -r /tmp/omarchy-watch.apk
   adb -s 127.0.0.1:4444 shell am start -n com.example.omarchy.watch/.MainActivity
   ```

On Wear 1.x, `install -r` can take a couple of minutes; let it finish.

## Run

On the desktop:

```sh
setsid python3 host/omarchy_watch_daemon.py >/path/to/daemon.log 2>&1 </dev/null &
host/watch_bridge.sh
host/watch_link_monitor.sh --once
```

For continuous operation, install the systemd user unit from
`systemd/omarchy-watch-link.service.example` (adjust `ExecStart` to your clone
path). Every 20 seconds it verifies the daemon listener, phone ADB (with a USB
`tcpip` fallback), `adb reverse`, the phone bridge, and the watch hub forward.

## Verifying an update

```sh
adb -s 127.0.0.1:4444 shell input tap 280 200   # workspace down
```

Expected: watch log shows `workspace zone delta=1 target=2`, the phone bridge
logs a delivered message, the host daemon logs `workspace N: rc=0`, and the
watch label and desktop workspace both change. Button coordinates on the round
watch are focus `(40,120)`, close `(40,200)`, up `(280,120)`, down `(280,200)`.

## Gotchas

- The bridge launch must stay non-blocking (`Popen` with detached stdio), or a
  launched GUI app stalls the single command thread.
- `MainActivity` uses `singleTask` + `excludeFromRecents` to avoid stacked
  activities. `am force-stop` sets the stopped flag, which suppresses implicit
  broadcasts such as dock pickup until the app is launched again.
- The dock-pickup action string is `android.intent.action.ACTION_POWER_DISCONNECTED`;
  omitting the inner `ACTION_` silently never matches.
- The daemon binds to loopback and allows only catalog-listed desktop ids. Do
  not expose the port publicly without adding authentication.
