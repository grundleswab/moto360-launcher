#!/usr/bin/env python3
"""Omarchy watch bridge: live app catalog + direct launch commands.

Phone connects to tcp://127.0.0.1:9999 (normally via
`adb reverse tcp:9999 tcp:9999`).  The daemon speaks newline-delimited JSON:

  host -> phone: {"type":"catalog","version":N,"apps":[...],"workspace":N}
  host -> phone: {"type":"workspace_state","id":N}
  phone -> host: {"type":"catalog_request"}
  phone -> host: {"type":"launch","id":"some.desktop"}
  phone -> host: {"type":"workspace","id":1..5}
  phone -> host: {"type":"focus"}
  phone -> host: {"type":"close"}
"""

import base64
import json
import os
import re
import shlex
import socket
import subprocess
import threading
import time

import gi

gi.require_version("Gio", "2.0")
gi.require_version("Gtk", "3.0")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import GdkPixbuf, Gio, Gtk  # noqa: E402

HOST = "127.0.0.1"
PORT = 9999
ICON_SIZE = 32
POLL_SECONDS = 2.0
WORKSPACE_COUNT = 5

_client_lock = threading.Lock()
_clients = set()
_catalog_lock = threading.RLock()
_catalog = {"type": "catalog", "version": 0, "apps": []}
_launch_paths = {}
_known_ids = set()


class Client:
    """One phone TCP connection with a dedicated send lock."""

    def __init__(self, conn, addr):
        self.conn = conn
        self.addr = addr
        self.lock = threading.Lock()
        self.reader = conn.makefile("rb")

    def send(self, obj):
        line = (json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8")
        with self.lock:
            self.conn.sendall(line)

    def close(self):
        try:
            self.reader.close()
        except Exception:
            pass
        try:
            self.conn.close()
        except Exception:
            pass


def _data_dirs():
    dirs = []
    home = os.environ.get(
        "XDG_DATA_HOME", os.path.join(os.path.expanduser("~"), ".local", "share")
    )
    dirs.append(os.path.join(home, "applications"))
    for part in os.environ.get("XDG_DATA_DIRS", "/usr/local/share:/usr/share").split(
        ":"
    ):
        if part:
            dirs.append(os.path.join(part, "applications"))
    # Common extra locations (Flatpak / Snap) when present.
    dirs.extend(
        [
            os.path.join(os.path.expanduser("~"), ".local/share/flatpak/exports/share/applications"),
            "/var/lib/flatpak/exports/share/applications",
            "/var/lib/snapd/desktop/applications",
        ]
    )
    seen = set()
    result = []
    for d in dirs:
        if d not in seen:
            seen.add(d)
            result.append(d)
    return result


def desktop_signature():
    """Cheap change detector based on desktop file names/mtimes/sizes."""
    entries = []
    for directory in _data_dirs():
        if not os.path.isdir(directory):
            continue
        for name in os.listdir(directory):
            if not name.endswith(".desktop"):
                continue
            path = os.path.join(directory, name)
            try:
                st = os.stat(path)
            except OSError:
                continue
            entries.append((path, st.st_mtime_ns, st.st_size))
    return tuple(sorted(entries))


def _init_gtk():
    try:
        Gtk.init([])
        return Gtk.IconTheme.get_default()
    except Exception as exc:
        print(f"GTK theme unavailable: {exc}", flush=True)
        return None


_THEME = None


def _png_bytes(path, size):
    try:
        pixbuf = GdkPixbuf.Pixbuf.new_from_file_at_size(path, size, size)
        return pixbuf.save_to_bufferv("png", [], [])[1]
    except Exception:
        return None


def _icon_base64(icon):
    if icon is None or _THEME is None:
        return ""
    try:
        if isinstance(icon, Gio.ThemedIcon):
            candidates = list(icon.get_names())
        elif isinstance(icon, Gio.FileIcon):
            candidates = [icon.get_file().get_path()]
        else:
            candidates = []
        for candidate in candidates:
            if not candidate:
                continue
            filename = candidate
            if not os.path.isabs(candidate):
                info = _THEME.lookup_icon(candidate, ICON_SIZE, 0)
                filename = info.get_filename() if info else None
            if not filename:
                continue
            data = _png_bytes(filename, ICON_SIZE)
            if data:
                return base64.b64encode(data).decode("ascii")
    except Exception as exc:
        print(f"icon lookup failed: {exc}", flush=True)
    return ""


def current_workspace():
    """Return the active Hyprland workspace id, or None if unavailable."""
    try:
        result = subprocess.run(
            ["hyprctl", "activeworkspace", "-j"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        if result.returncode == 0:
            workspace_id = json.loads(result.stdout).get("id")
            if isinstance(workspace_id, int) and workspace_id > 0:
                # Omarchy gives each monitor a block of ten global workspace ids.
                slot = (workspace_id - 1) % 10 + 1
                if 1 <= slot <= WORKSPACE_COUNT:
                    return slot
    except Exception as exc:
        print(f"cannot read active workspace: {exc}", flush=True)
    return None


def switch_workspace(workspace_id):
    if not isinstance(workspace_id, int) or not 1 <= workspace_id <= WORKSPACE_COUNT:
        print(f"rejected workspace id {workspace_id!r}", flush=True)
        return
    try:
        # Omarchy exposes workspace slots through its monitor-workspaces Lua
        # module; the plain `hyprctl dispatch workspace N` form is no longer
        # accepted by this Hyprland version.
        lua = (
            'require("default.hypr.monitor-workspaces").focus(%d)()'
            % workspace_id
        )
        result = subprocess.run(
            ["hyprctl", "eval", lua],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        print(
            f"workspace {workspace_id}: rc={result.returncode} "
            f"out={result.stdout!r} err={result.stderr!r}",
            flush=True,
        )
    except Exception as exc:
        print(f"workspace switch failed: {exc}", flush=True)
        return
    broadcast({"type": "workspace_state", "id": workspace_id})


def cycle_focus():
    """Focus the next window on the current workspace."""
    try:
        result = subprocess.run(
            ["hyprctl", "dispatch", "hl.dsp.window.cycle_next()"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        print(
            f"focus next: rc={result.returncode} "
            f"out={result.stdout!r} err={result.stderr!r}",
            flush=True,
        )
    except Exception as exc:
        print(f"focus cycle failed: {exc}", flush=True)


def close_focused():
    """Close the currently focused window."""
    try:
        result = subprocess.run(
            ["hyprctl", "dispatch", "hl.dsp.window.close()"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        print(
            f"close focused: rc={result.returncode} "
            f"out={result.stdout!r} err={result.stderr!r}",
            flush=True,
        )
    except Exception as exc:
        print(f"close focused failed: {exc}", flush=True)


def build_catalog():
    """Return (catalog dict, id->desktop path map)."""
    apps = []
    paths = {}
    try:
        app_infos = Gio.AppInfo.get_all()
    except Exception as exc:
        print(f"app discovery failed: {exc}", flush=True)
        app_infos = []

    for info in app_infos:
        try:
            should_show = info.should_show()
        except Exception:
            should_show = True
        if not should_show:
            continue
        desktop_id = info.get_id()
        if not desktop_id or not desktop_id.endswith(".desktop"):
            continue
        name = info.get_display_name() or info.get_name() or desktop_id
        apps.append(
            {
                "id": desktop_id,
                "name": name,
                "icon": _icon_base64(info.get_icon()),
            }
        )

        # Resolve the backing desktop file for gio fallback launches.
        try:
            cmdline = info.get_commandline() or ""
            executable = os.path.basename(shlex.split(cmdline)[0]) if cmdline else ""
        except Exception:
            executable = ""
        for directory in _data_dirs():
            candidate = os.path.join(directory, desktop_id)
            if os.path.isfile(candidate):
                paths[desktop_id] = candidate
                break
        if desktop_id not in paths and executable:
            paths[desktop_id] = executable

    apps.sort(key=lambda a: (a["name"].lower(), a["id"]))
    version = _catalog.get("version", 0) + 1
    workspace = current_workspace() or 1
    return {
        "type": "catalog",
        "version": version,
        "apps": apps,
        "workspace": workspace,
    }, paths


def refresh_catalog(force=False):
    global _catalog, _launch_paths, _known_ids
    signature = desktop_signature()
    if not force and signature == refresh_catalog.last_signature:
        return False
    refresh_catalog.last_signature = signature
    catalog, paths = build_catalog()
    with _catalog_lock:
        _catalog = catalog
        _launch_paths = paths
        _known_ids = {app["id"] for app in catalog["apps"]}
    print(
        f"catalog v{catalog['version']}: {len(catalog['apps'])} apps",
        flush=True,
    )
    broadcast(catalog)
    return True


refresh_catalog.last_signature = None


def broadcast(obj):
    with _client_lock:
        targets = list(_clients)
    for client in targets:
        try:
            client.send(obj)
        except Exception as exc:
            print(f"send to {client.addr} failed: {exc}", flush=True)
            with _client_lock:
                _clients.discard(client)
            client.close()


def _desktop_exec(desktop_id):
    path = _launch_paths.get(desktop_id)
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            in_main = False
            for raw in handle:
                line = raw.strip()
                if line.startswith("["):
                    in_main = line == "[Desktop Entry]"
                    continue
                if in_main and line.startswith("Exec="):
                    return line[5:].strip()
    except OSError:
        pass
    return None


def launch_app(desktop_id):
    # The _known_ids allowlist below is the real security boundary.  Only
    # reject path separators and control characters; desktop file names may
    # legitimately contain spaces (e.g. webapp-generated entries).
    if (
        not isinstance(desktop_id, str)
        or not desktop_id
        or "/" in desktop_id
        or "\\" in desktop_id
        or any(ord(ch) < 0x20 for ch in desktop_id)
    ):
        print(f"rejected launch id {desktop_id!r}", flush=True)
        return
    with _catalog_lock:
        allowed = desktop_id in _known_ids
    if not allowed:
        print(f"unknown launch id {desktop_id!r}", flush=True)
        return

    # Launch asynchronously and detach.  A blocking subprocess.run() with
    # capture_output=True inherits the stdout/stderr pipes into the launched
    # GUI app, so it waits for that app to exit before returning.  That stalls
    # the client's command thread and queues every later command (second
    # launch, close, workspace) until the app is closed -- which looks like the
    # launcher freezing and then replaying buffered closes.
    attempts = [["gtk-launch", desktop_id]]
    path = _launch_paths.get(desktop_id)
    if path and os.path.isfile(path):
        attempts.append(["gio", "launch", path])
    exec_line = _desktop_exec(desktop_id)
    if exec_line:
        cleaned = re.sub(r"%[uUfFdDnNickvm]", "", exec_line).strip()
        try:
            attempts.append(shlex.split(cleaned))
        except ValueError:
            pass

    for argv in attempts:
        if not argv:
            continue
        try:
            proc = subprocess.Popen(
                argv,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            print(
                f"launched {desktop_id} via {argv[0]} pid={proc.pid}",
                flush=True,
            )
            return
        except OSError as exc:
            print(f"launch {desktop_id} via {argv[0]} failed: {exc}", flush=True)


def handle_command(client, message):
    kind = message.get("type")
    if kind == "catalog_request":
        with _catalog_lock:
            catalog = dict(_catalog)
        client.send(catalog)
        workspace = current_workspace()
        if workspace is not None:
            client.send({"type": "workspace_state", "id": workspace})
    elif kind == "launch":
        launch_app(message.get("id"))
    elif kind == "workspace":
        switch_workspace(message.get("id"))
    elif kind == "focus":
        cycle_focus()
    elif kind == "close":
        close_focused()
    else:
        print(f"ignored command from {client.addr}: {kind!r}", flush=True)


def client_thread(client):
    try:
        for raw in client.reader:
            line = raw.strip()
            if not line:
                continue
            try:
                message = json.loads(line.decode("utf-8"))
            except (ValueError, UnicodeDecodeError) as exc:
                print(f"bad JSON from {client.addr}: {exc}", flush=True)
                continue
            if isinstance(message, dict):
                handle_command(client, message)
    except Exception as exc:
        print(f"client {client.addr} reader ended: {exc}", flush=True)
    finally:
        with _client_lock:
            _clients.discard(client)
        client.close()
        print(f"client {client.addr} disconnected", flush=True)


def watcher_loop():
    while True:
        try:
            refresh_catalog()
        except Exception as exc:
            print(f"catalog refresh failed: {exc}", flush=True)
        time.sleep(POLL_SECONDS)


def main():
    global _THEME
    _THEME = _init_gtk()
    refresh_catalog(force=True)

    threading.Thread(target=watcher_loop, daemon=True).start()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(8)
    print(f"listening on tcp://{HOST}:{PORT}", flush=True)

    while True:
        conn, addr = server.accept()
        client = Client(conn, addr)
        with _client_lock:
            _clients.add(client)
        print(f"client {addr} connected", flush=True)
        threading.Thread(target=client_thread, args=(client,), daemon=True).start()
        # Push current catalog immediately on connect.
        with _catalog_lock:
            catalog = dict(_catalog)
        try:
            client.send(catalog)
        except Exception as exc:
            print(f"initial catalog push to {addr} failed: {exc}", flush=True)


if __name__ == "__main__":
    main()
