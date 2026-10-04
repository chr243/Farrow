#!/data/data/com.termux/files/usr/bin/python3
"""
Farrow <-> Termux Browser Pilot (TBP) bridge.

Installed into Termux by the Farrow setup wizard (~/.farrow/tbp_bridge.py) and started with:
    python ~/.farrow/tbp_bridge.py --port 8765 --token <TOKEN>

Standard library only. Listens on 127.0.0.1 and wraps the `tbp` CLI
(https://github.com/salviz/termux-browser-pilot):

  GET  /health                    -> {"ok", "version", "tbp", "daemon"}
  GET  /daemon/status             -> {"ok", "running", "status", "error", "tbp_log", "daemon_log"}
  POST /daemon/start              -> single-flight: clean orphans/stale locks, `tbp start` detached, wait ≤30 s
  POST /daemon/reset              -> stop everything (tbp stop, kill trees, Xvfb, locks) and start fresh
  POST /cmd {"cmd": str, "args": {...}}  -> {"ok", "code", "stdout", "stderr", "data"}
  POST /cmd job_start {"cmd": "cookies_import", "args"} / job_status {"job"}  -> background job (v1.10.0)
  GET  /ws  (WebSocket)           -> send {"id", "cmd", "args"} frames, receive {"id", ...result} + {"event": ...}

Every request must carry the header `X-Bridge-Token: <TOKEN>` (WebSocket: `?token=<TOKEN>`),
because any app on the phone can reach 127.0.0.1.

Human-like input parameters sent by the app:
  click: {"selector", "human": bool, "mouse_path": [[x,y],...] normalised 0..1, "step_delay_ms": int}
         The normalised Bezier path is mapped from the current pointer to the element centre and replayed
         with xdotool on the TBP Xvfb display, then the click is delegated to `tbp click --human`.
  type:  {"selector", "text", "delays_ms": [int per char], "submit": bool}
         Characters are typed one by one with xdotool using the given delays (falls back to `tbp type`).
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import socket
import sqlite3
import struct
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

VERSION = "1.13.0"
HOME = os.path.expanduser("~")
STATE_DIR = os.path.join(HOME, ".farrow")
SESSIONS_DIR = os.path.join(STATE_DIR, "sessions")
DISPLAY = os.environ.get("TBP_DISPLAY", ":99")  # TBP's default Xvfb display
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
TOKEN = ""
ws_clients = set()
ws_lock = threading.Lock()


def run(argv, timeout=90, env=None):
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, env=env)
        return p.returncode, p.stdout, p.stderr
    except FileNotFoundError as e:
        return 127, "", str(e)
    except subprocess.TimeoutExpired:
        return 124, "", "timeout after %ss" % timeout


def tbp(*args, timeout=90):
    if args and str(args[0]) not in DAEMONLESS and "daemon_lock" in globals():
        bad = ensure_daemon_ready()
        if bad:
            return {"ok": False, "code": 3, "stdout": "", "stderr": "TBP daemon not running: " + str(bad.get("error")),
                    "data": None}
    js_cmd = bool(args) and str(args[0]) in JS_CMDS and "daemon_lock" in globals()
    before = js_guard_before() if js_cmd else None
    code, out, err = run(["tbp", *[str(a) for a in args], "--json"], timeout=timeout)
    if js_cmd:
        leak = js_guard_after(before)
        if leak:
            return {"ok": False, "code": 1, "stdout": "", "data": {"eval_leak": True, "recovered_to": leak.get("back_to")},
                    "stderr": "eval was typed into the address bar instead of the DevTools console (TBP lost the console "
                              "window); went back to %s and reopened the console. Retry, or tap Reset browser if it repeats."
                              % (leak.get("back_to") or "the previous page")}
    data = None
    try:
        data = json.loads(out) if out.strip() else None
    except ValueError:
        data = None
    ok = code == 0
    if isinstance(data, dict) and data.get("success") is False:
        ok = False
        err = (str(data.get("error") or "") + "\n" + err).strip()
    return {"ok": ok, "code": code, "stdout": out[-200000:], "stderr": err[-4000:], "data": data}


TBP_DIR = os.path.join(HOME, ".tbp")          # termux-browser-pilot state: daemon.pid, daemon.sock, daemon.log
TBP_PID = os.path.join(TBP_DIR, "daemon.pid")
TBP_SOCK = os.path.join(TBP_DIR, "daemon.sock")
TBP_DAEMON_LOG = os.path.join(TBP_DIR, "daemon.log")
TBP_PROFILE = os.path.join(TBP_DIR, "firefox_profile")
# src/lock.py: the browser session lock holds the DAEMON's own pid (SessionLock.acquire writes os.getpid()).
TBP_LOCK = os.path.join(os.environ.get("TMPDIR", HOME), ".tbp_browser.lock")
TBP_START_LOG = os.path.join(STATE_DIR, "tbp.log")
XVFB_NUM = DISPLAY.lstrip(":") or "99"
# Serialises start / reset (single flight) and keeps daemon-backed tbp commands from racing a start.
daemon_lock = threading.RLock()
daemon_info = {"starting_since": 0.0, "last_start": None}

# Root cause of "daemon.pid missing" (TBP src/client.py ensure_daemon): ANY daemon-backed tbp command that runs while
# the daemon is still starting (pid written, socket not yet created — Xvfb + Firefox take 6+ s) deletes daemon.pid
# and daemon.sock and spawns a second daemon. That one dies on the session lock ("Another browser session is
# running (PID …)") while the first keeps running WITHOUT a pid file, so `tbp status` / `tbp start` no longer see it.
# Fixes: health = the socket answers "status" (pid file optional, repaired from the lock), and no daemon-backed tbp
# command is run unless the daemon answers (they wait for / trigger our single-flight start instead).
DAEMONLESS = {"status", "start", "stop", "kill", "--version", "version", "device"}


def tail(path, lines=40):
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 16000))
            return "\n".join(f.read().decode("utf-8", "replace").splitlines()[-lines:])
    except OSError:
        return ""


def read_pid(path):
    try:
        with open(path) as f:
            pid = int(f.read().strip())
        return pid if pid > 0 else None
    except (OSError, ValueError):
        return None


def pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except OSError:
        return False


def is_tbp_process(pid):
    """Guards against PID reuse: only treat a live PID as TBP's if its cmdline looks like the daemon / tbp."""
    if not pid_alive(pid):
        return False
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            cmd = f.read().replace(b"\0", b" ").decode("utf-8", "replace")
    except OSError:
        return False
    return any(k in cmd for k in ("python", "tbp", "daemon", "firefox", "Xvfb"))


def daemon_pid_alive():
    pid = read_pid(TBP_PID)
    return pid if pid_alive(pid) else None


def socket_status(timeout=5.0):
    """Ask the daemon over its unix socket (TBP protocol: one JSON line per request). Returns the reply or None."""
    if not os.path.exists(TBP_SOCK):
        return None
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(TBP_SOCK)
        s.sendall(json.dumps({"id": 1, "action": "status", "params": {}}).encode() + b"\n")
        buf = b""
        while not buf.endswith(b"\n") and len(buf) < 1_000_000:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
        reply = json.loads(buf.decode("utf-8", "replace") or "null")
        return reply if isinstance(reply, dict) else None
    except (OSError, ValueError):
        return None
    finally:
        s.close()


def socket_connectable(timeout=2.0):
    """What TBP itself uses (client.ensure_daemon): the unix socket accepts a connection = the daemon is listening.
    NOT the status action: _handle_status evaluates url()/title() through the Firefox devtools console (serialised with
    every other JS call), which can take far longer than a few seconds — v1.3.0 probed it with a 5 s timeout,
    called a healthy daemon "unresponsive" and its orphan cleanup then killed it (→ "Firefox process died (rc=1)")."""
    if not os.path.exists(TBP_SOCK):
        return False
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(TBP_SOCK)
        return True
    except OSError:
        return False
    finally:
        s.close()


status_cache = {"at": 0.0, "data": None, "busy": False}


def refresh_status_async():
    """Details (url, title, uptime) via `tbp status --json` (10 s), at most every 30 s, never blocking /health."""
    if status_cache["busy"] or time.time() - status_cache["at"] < 30 or not daemon_pid_alive():
        return

    def work():
        status_cache["busy"] = True
        try:
            code, out, _ = run(["tbp", "status", "--json"], timeout=10)
            try:
                d = json.loads(out)
                if d.get("success"):
                    status_cache["data"] = d.get("data")
            except ValueError:
                pass
        finally:
            status_cache["at"] = time.time()
            status_cache["busy"] = False
    threading.Thread(target=work, daemon=True).start()


def live_tbp_pid():
    """A live daemon process we know of: daemon.pid, else the browser-lock holder (same pid in TBP)."""
    pid = daemon_pid_alive()
    if pid and is_tbp_process(pid):
        return pid
    lock_pid = read_pid(TBP_LOCK)
    return lock_pid if is_tbp_process(lock_pid) else None


def repair_pid_file():
    if daemon_pid_alive():
        return
    pid = read_pid(TBP_LOCK)
    if is_tbp_process(pid):
        try:
            with open(TBP_PID, "w") as f:
                f.write(str(pid))
            log("repaired ~/.tbp/daemon.pid -> %s" % pid)
        except OSError:
            pass


def daemon_state(timeout=None):
    """running = the socket accepts connections. pid alive + socket silent = 'unresponsive' (starting or hung):
    never restarted automatically, the app offers Reset browser."""
    if not shutil.which("tbp"):
        return {"running": False, "error": "tbp not installed"}
    if socket_connectable():
        repair_pid_file()
        refresh_status_async()
        return {"running": True, "status": status_cache["data"] or {"pid": live_tbp_pid()}}
    pid = live_tbp_pid()
    if daemon_info["starting_since"] and time.time() - daemon_info["starting_since"] < 60:
        return {"running": False, "starting": True, "pid": pid, "error": "daemon is starting"}
    if pid:
        return {"running": False, "unresponsive": True, "pid": pid,
                "error": "daemon pid %d alive but its socket doesn't accept connections (still starting or hung) — "
                         "wait a moment or tap Reset browser" % pid}
    return {"running": False, "error": "no daemon running"}


def children_of(pid):
    """All descendants of pid (from /proc/*/stat ppid)."""
    kids = {}
    for d in os.listdir("/proc"):
        if not d.isdigit():
            continue
        try:
            with open("/proc/%s/stat" % d) as f:
                st = f.read()
            ppid = int(st[st.rindex(")") + 2:].split()[1])
            kids.setdefault(ppid, []).append(int(d))
        except (OSError, ValueError):
            continue
    out, todo = [], [pid]
    while todo:
        p = todo.pop()
        for c in kids.get(p, []):
            if c not in out:
                out.append(c)
                todo.append(c)
    return out


def kill_tree(pid, notes):
    if not pid_alive(pid) or pid == os.getpid():
        return
    victims = children_of(pid) + [pid]
    for sig in (15, 9):
        for v in victims:
            try:
                os.kill(v, sig)
            except OSError:
                pass
        for _ in range(30):
            if not any(pid_alive(v) for v in victims):
                break
            time.sleep(0.1)
    notes.append("killed PID %d and %d child process(es)" % (pid, len(victims) - 1))


def remove(path, notes):
    try:
        os.unlink(path)
        notes.append("removed " + path)
    except FileNotFoundError:
        pass
    except OSError as e:
        notes.append("could not remove %s: %s" % (path, e))


def clear_stale_files(notes, include_profile_locks=False):
    prefix_tmp = os.environ.get("TMPDIR", "/data/data/com.termux/files/usr/tmp")
    for pth in (TBP_LOCK, TBP_PID, TBP_SOCK,
                os.path.join(prefix_tmp, ".X%s-lock" % XVFB_NUM), os.path.join(prefix_tmp, ".X11-unix", "X%s" % XVFB_NUM),
                "/tmp/.X%s-lock" % XVFB_NUM, "/tmp/.X11-unix/X%s" % XVFB_NUM):
        remove(pth, notes)
    if include_profile_locks:
        for name in ("lock", ".parentlock"):
            remove(os.path.join(TBP_PROFILE, name), notes)


def procs_matching(pattern):
    """PIDs whose cmdline matches the regex — a /proc scan, because pgrep/pkill (procps) are often missing on Termux
    (v1.5.0's pgrep returned 127 → 'Firefox gone' immediately → cookies.sqlite written under a running Firefox)."""
    rx, me, out = re.compile(pattern), os.getpid(), []
    for d in os.listdir("/proc"):
        if not d.isdigit() or int(d) == me:
            continue
        try:
            with open("/proc/%s/cmdline" % d, "rb") as f:
                cmd = f.read().replace(b"\0", b" ").decode("utf-8", "replace").strip()
        except OSError:
            continue
        if cmd and rx.search(cmd):
            out.append(int(d))
    return out


def pkill(pattern, notes):
    pids = procs_matching(pattern)
    for sig in (15, 9):
        for pid in pids:
            try:
                os.kill(pid, sig)
            except OSError:
                pass
        for _ in range(30):
            if not any(pid_alive(p) for p in pids):
                break
            time.sleep(0.1)
    if pids:
        notes.append("killed %d process(es) matching '%s'" % (len(pids), pattern))


FIREFOX_RX = r"firefox.*" + re.escape(TBP_PROFILE)


def browser_alive():
    """The daemon (pid file / lock holder) or any Firefox on TBP's profile."""
    return bool(live_tbp_pid() or procs_matching(FIREFOX_RX))


def stop_everything(notes, hard=False):
    """`tbp stop` if TBP still knows the daemon, then kill the daemon / lock-holder trees, Xvfb :N and stale files."""
    if daemon_pid_alive():
        code, out, err = run(["tbp", "stop"], timeout=20)
        notes.append("tbp stop -> %s %s" % (code, (out + err).strip()[:200]))
    for pid in {read_pid(TBP_PID), read_pid(TBP_LOCK)}:
        if is_tbp_process(pid):
            kill_tree(pid, notes)
    pkill("Xvfb :%s( |$)" % XVFB_NUM, notes)
    if hard:
        pkill("firefox.*%s" % re.escape(TBP_PROFILE), notes)
        pkill("python.*-m src.daemon", notes)
    clear_stale_files(notes, include_profile_locks=hard)


def wait_connectable(seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if socket_connectable():
            return True
        time.sleep(1)
    return socket_connectable()


def clear_if_absent(notes):
    """Only when NO daemon process is alive: drop the dead lock, stale daemon.pid and stale socket."""
    if os.path.exists(TBP_LOCK) and not is_tbp_process(read_pid(TBP_LOCK)):
        remove(TBP_LOCK, notes)
    if os.path.exists(TBP_PID) and not is_tbp_process(read_pid(TBP_PID)):
        remove(TBP_PID, notes)
    if os.path.exists(TBP_SOCK):
        remove(TBP_SOCK, notes)


FIREFOX_PROBE_LOG = os.path.join(STATE_DIR, "firefox-probe.log")


def firefox_probe(notes):
    """TBP starts Firefox with stderr=DEVNULL, so "Firefox process died (rc=1)" has no reason. When no daemon is alive,
    launch Firefox once on the TBP display with a scratch profile for 8 s and keep its stderr."""
    if live_tbp_pid() or not shutil.which("firefox"):
        return ""
    prof = os.path.join(STATE_DIR, "firefox-probe-profile")
    os.makedirs(prof, exist_ok=True)
    env = dict(os.environ, DISPLAY=DISPLAY, LIBGL_ALWAYS_SOFTWARE="1", MOZ_CRASHREPORTER_DISABLE="1")
    xvfb = None
    if not os.path.exists("/tmp/.X11-unix/X%s" % XVFB_NUM) and not os.path.exists(
            os.path.join(os.environ.get("TMPDIR", "/tmp"), ".X11-unix", "X%s" % XVFB_NUM)) and shutil.which("Xvfb"):
        xvfb = subprocess.Popen(["Xvfb", DISPLAY, "-screen", "0", "1280x720x24"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)
    with open(FIREFOX_PROBE_LOG, "wb") as f:
        f.write(("--- %s firefox probe (DISPLAY=%s)\n" % (time.strftime("%H:%M:%S"), DISPLAY)).encode())
        f.flush()
        p = subprocess.Popen(["firefox", "--no-remote", "-profile", prof, "about:blank"], env=env, stdout=f, stderr=subprocess.STDOUT)
        try:
            rc = p.wait(timeout=8)
            f.write(("--- firefox exited rc=%s within 8 s\n" % rc).encode())
        except subprocess.TimeoutExpired:
            f.write(b"--- firefox still running after 8 s (it starts fine on its own)\n")
            p.terminate()
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
    if xvfb:
        xvfb.terminate()
    notes.append("ran a Firefox probe (stderr in ~/.farrow/firefox-probe.log)")
    return tail(FIREFOX_PROBE_LOG, 40)


def start_daemon(reset=False, wait_s=30):
    """Single flight. Starts ONLY when the daemon is definitively absent (no live pid, socket not listening). A live
    pid with a silent socket is waited for (≤ 20 s) and then reported as needs_reset — never killed automatically."""
    with daemon_lock:
        notes = []
        if not shutil.which("tbp"):
            return {"ok": False, "error": "tbp not installed (run setup step 3)"}
        if reset:
            stop_everything(notes, hard=True)
        else:
            if socket_connectable():
                repair_pid_file()
                return {"ok": True, "started": False, "already_running": True, "running": True}
            pid = live_tbp_pid()
            if pid:
                wait = int(os.environ.get("FARROW_UNRESPONSIVE_WAIT_S", "20"))
                notes.append("daemon pid %d alive, socket not listening yet — waiting up to %d s" % (pid, wait))
                if wait_connectable(wait):
                    repair_pid_file()
                    return {"ok": True, "started": False, "already_running": True, "running": True, "notes": notes}
                res = {"ok": False, "started": False, "running": False, "needs_reset": True, "pid": pid, "notes": notes,
                       "error": "TBP daemon (pid %d) is alive but its socket doesn't answer after %d s — tap Reset browser" % (pid, wait),
                       "tbp_log": tail(TBP_START_LOG), "daemon_log": tail(TBP_DAEMON_LOG)}
                daemon_info["last_start"] = res
                return res
            clear_if_absent(notes)
        if not browser_alive():   # profile files may only be written while Firefox is stopped
            write_locale_prefs(notes)
            write_media_prefs(notes)
            seed_google_consent(notes)
        os.makedirs(STATE_DIR, exist_ok=True)
        js_state["synced"] = False
        with open(TBP_START_LOG, "ab") as logf:
            logf.write(("--- %s %s (bridge %s)%s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), "reset + tbp start" if reset else "tbp start",
                        VERSION, "".join("\n    " + n for n in notes))).encode())
            logf.flush()
            daemon_info["starting_since"] = time.time()
            p = subprocess.Popen(["tbp", "start"], stdin=subprocess.DEVNULL, stdout=logf, stderr=subprocess.STDOUT,
                                 start_new_session=True, close_fds=True)
        threading.Thread(target=p.wait, daemon=True).start()  # reap; the real daemon double-forks
        try:
            if wait_connectable(wait_s):
                repair_pid_file()
                prime_console(notes)
                res = {"ok": True, "started": True, "running": True, "notes": notes,
                       "seconds": round(time.time() - daemon_info["starting_since"], 1)}
                daemon_info["last_start"] = res
                return res
            dlog = tail(TBP_DAEMON_LOG)
            res = {"ok": False, "started": True, "running": False, "notes": notes,
                   "error": "TBP daemon did not listen on ~/.tbp/daemon.sock within %ds" % wait_s,
                   "tbp_log": tail(TBP_START_LOG), "daemon_log": dlog}
            if re.search(r"Firefox (process died|failed to start)", "\n".join(dlog.splitlines()[-15:])) and not live_tbp_pid():
                res["firefox_log"] = firefox_probe(notes)
            daemon_info["last_start"] = res
            return res
        finally:
            daemon_info["starting_since"] = 0.0


def ensure_daemon_ready():
    """Before daemon-backed tbp commands: never let TBP's own ensure_daemon run during a start or against a live pid
    (it deletes daemon.pid and spawns a second daemon)."""
    if socket_connectable():
        return None
    res = start_daemon()
    return None if res.get("running") else res


def unwrap(res):
    """TBP --json prints {"success", "data": {...}}; return the inner payload's 'result' (eval) or the payload."""
    d = res.get("data")
    if isinstance(d, dict) and "data" in d:
        d = d["data"]
    if isinstance(d, dict) and "result" in d:
        return d["result"]
    return d


def xdotool(*args):
    env = dict(os.environ, DISPLAY=DISPLAY)
    return run(["xdotool", *[str(a) for a in args]], timeout=15, env=env)


def safe_name(name):
    name = re.sub(r"[^A-Za-z0-9_.-]", "_", name or "default")[:64]
    return name or "default"


def element_center(selector):
    """Screen coordinates of the element centre (Firefox exposes mozInnerScreenX/Y)."""
    js = ("(()=>{const e=document.querySelector(%s);if(!e)return null;e.scrollIntoView({block:'center'});"
          "const r=e.getBoundingClientRect();const d=window.devicePixelRatio||1;"
          "const ox=(window.mozInnerScreenX||0),oy=(window.mozInnerScreenY||0);"
          "return JSON.stringify({x:Math.round((ox+r.left+r.width/2)*d),y:Math.round((oy+r.top+r.height/2)*d)})})()"
          % json.dumps(selector))
    res = tbp("eval", js, timeout=20)
    raw = unwrap(res)
    if raw is None:
        raw = res.get("stdout", "").strip()
    try:
        pt = json.loads(raw) if isinstance(raw, str) else raw
        return int(pt["x"]), int(pt["y"])
    except Exception:
        return None


def pointer():
    code, out, _ = xdotool("getmouselocation", "--shell")
    if code != 0:
        return None
    vals = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    try:
        return int(vals["X"]), int(vals["Y"])
    except Exception:
        return None


def replay_path(selector, path, step_delay_ms):
    if not path or not shutil.which("xdotool"):
        return False
    start, end = pointer(), element_center(selector)
    if not start or not end:
        return False
    (x0, y0), (x1, y1) = start, end
    for px, py in path:
        xdotool("mousemove", int(x0 + px * (x1 - x0)), int(y0 + py * (y1 - y0)))
        time.sleep(max(0, min(int(step_delay_ms or 12), 200)) / 1000.0)
    return True


def cmd_click(a):
    sel = a.get("selector") or ""
    moved = replay_path(sel, a.get("mouse_path"), a.get("step_delay_ms"))
    res = tbp("click", sel, *(["--human"] if a.get("human", True) else []))
    res["mouse_path_replayed"] = moved
    return res


def cmd_type(a):
    sel, text = a.get("selector") or "", a.get("text") or ""
    delays = a.get("delays_ms") or []
    used_xdotool = False
    if delays and shutil.which("xdotool"):
        focus = tbp("click", sel)
        if focus["ok"]:
            used_xdotool = True
            for i, ch in enumerate(text):
                if ch == "\n":
                    xdotool("key", "Return")
                else:
                    xdotool("type", "--delay", "0", "--", ch)
                time.sleep(max(0, min(int(delays[i] if i < len(delays) else 60), 1500)) / 1000.0)
            res = {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"typed": len(text)}}
    if not used_xdotool:
        res = tbp("type", sel, text)
    if a.get("submit") and res["ok"]:
        tbp("press", "Enter")
    res["human_typing"] = used_xdotool
    return res


def cmd_cookies_save(a):
    """Logins live in Firefox's own cookie store (cookies.sqlite, persistent) since bridge 1.5.0; the WebView import
    keeps <name>.import.json for re-imports. TBP's `cookies --save` only sees document.cookie (no HttpOnly), so skip it."""
    path = os.path.join(SESSIONS_DIR, safe_name(a.get("name")) + ".import.json")
    return {"ok": True, "code": 0, "stdout": "session kept in Firefox's cookie store", "stderr": "", "path": path, "data": None}


COOKIE_YEAR = 365 * 24 * 3600
# Cookies worth listing first in the report (X: auth_token + ct0 are what matters; Facebook: c_user + xs).
KEY_COOKIES = ("auth_token", "ct0", "twid", "kdt", "att", "guest_id", "c_user", "xs", "datr", "fr", "sb")
# Cookie banners: REJECT first (EN/FR/DE/ES/IT/NL/PT), accept only if there is no reject button. Covers Google's
# consent wall (consent.google.com, "Before you continue" / "Avant d'accéder à Google").
CONSENT_JS = r"""(function(){var rej=/^(reject all|reject all cookies|refuse non-essential cookies|only allow essential cookies|decline optional cookies|decline|tout refuser|refuser tout|tout rejeter|continuer sans accepter|alle ablehnen|ablehnen|rechazar todo|rechazar|rifiuta tutto|rifiuta|alles weigeren|rejeitar tudo)$/i;
var acc=/^(accept all|accept all cookies|allow all cookies|tout accepter|accepter tout|alle akzeptieren|aceptar todo|accetta tutto|alles accepteren|aceitar tudo|i agree|j'accepte)$/i;
var els=[].slice.call(document.querySelectorAll('button,[role=button],a[role=link],input[type=submit],input[type=button]'));
function txt(e){return (e.innerText||e.textContent||e.value||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim()}
for(var i=0;i<els.length;i++){var t=txt(els[i]);if(rej.test(t)){els[i].click();return 'clicked: '+t}}
for(var k=0;k<els.length;k++){var u=txt(els[k]);if(acc.test(u)){els[k].click();return 'clicked: '+u}}return 'no banner'})()"""
COOKIES_DB = os.path.join(TBP_PROFILE, "cookies.sqlite")
SAMESITE_INT = {"None": 0, "Lax": 1, "Strict": 2}   # nsICookie SAMESITE_NONE / LAX / STRICT
SAMESITE_NAME = {v: k for k, v in SAMESITE_INT.items()}


def normalize_cookie(c, now=None):
    """Cookie record (also TBP's cookies.json schema): name, value, domain ('.host' = with subdomains), path, secure,
    httpOnly, sameSite, expires (epoch s; WebView gives none → +1 year). SameSite=None requires secure."""
    now = int(now if now is not None else time.time())
    name = str(c.get("name", "")).strip()
    domain = str(c.get("domain") or c.get("host") or "").strip()
    if domain and not domain.startswith(".") and "." in domain and not re.match(r"^\d+(\.\d+){3}$", domain):
        domain = "." + domain
    same = str(c.get("sameSite") or "None").capitalize()
    if same not in SAMESITE_INT:
        same = "None"
    exp = c.get("expires", c.get("expiry"))
    try:
        exp = int(float(exp))
        if exp > 10 ** 11:   # milliseconds
            exp //= 1000
    except (TypeError, ValueError):
        exp = 0
    if exp <= now:
        exp = now + COOKIE_YEAR
    return {"name": name, "value": str(c.get("value", "")), "domain": domain, "path": c.get("path") or "/",
            "secure": True if same == "None" else bool(c.get("secure", True)), "httpOnly": bool(c.get("httpOnly", False)),
            "sameSite": same, "expires": exp}


def firefox_major():
    code, out, _ = run(["firefox", "--version"], timeout=20)
    m = re.search(r"(\d+)\.", out or "")
    return int(m.group(1)) if m else 0


def ensure_cookie_db(path=COOKIES_DB):
    """Firefox normally created it already. If not, create the schema Firefox expects for its version
    (schema 16 = Firefox ≥ 142, expiry in ms; schema 15 before, expiry in s)."""
    if os.path.exists(path):
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    ver = 16 if firefox_major() >= 142 else 15
    db = sqlite3.connect(path)
    try:
        db.execute("CREATE TABLE moz_cookies (id INTEGER PRIMARY KEY, originAttributes TEXT NOT NULL DEFAULT '', name TEXT, "
                   "value TEXT, host TEXT, path TEXT, expiry INTEGER, lastAccessed INTEGER, creationTime INTEGER, isSecure INTEGER, "
                   "isHttpOnly INTEGER, inBrowserElement INTEGER DEFAULT 0, sameSite INTEGER DEFAULT 0, schemeMap INTEGER DEFAULT 0, "
                   "isPartitionedAttributeSet INTEGER DEFAULT 0, CONSTRAINT moz_uniqueid UNIQUE (name, host, path, originAttributes))")
        db.execute("PRAGMA user_version = %d" % ver)
        db.commit()
    finally:
        db.close()


def write_cookie_db(cookies, path=COOKIES_DB, now=None):
    """Writes the cookies straight into Firefox's cookie store (moz_cookies) — the only way to get HttpOnly + Secure
    cookies into TBP's Firefox: TBP has no privileged context, its cookie load is page JS (document.cookie), which
    can't set HttpOnly and only works on the current page's origin. Firefox MUST be stopped. Adapts to the table's
    columns and the expiry unit (PRAGMA user_version ≥ 16 → milliseconds)."""
    ensure_cookie_db(path)
    now = time.time() if now is None else now
    db = sqlite3.connect(path, timeout=10)
    try:
        ver = db.execute("PRAGMA user_version").fetchone()[0]
        cols = [r[1] for r in db.execute("PRAGMA table_info(moz_cookies)")]
        ms = ver >= 16
        written = []
        for c in cookies:
            host = c["domain"] or ""
            bare = host.lstrip(".")
            # Drop every older copy (JS-set host-only ones from earlier attempts, guest cookies) of this name.
            db.execute("DELETE FROM moz_cookies WHERE name = ? AND host IN (?, ?, ?) AND originAttributes = ''",
                       (c["name"], host, bare, "www." + bare))
            same = SAMESITE_INT.get(c["sameSite"], 0)
            row = {"originAttributes": "", "name": c["name"], "value": c["value"], "host": host, "path": c["path"],
                   "expiry": c["expires"] * 1000 if ms else c["expires"], "lastAccessed": int(now * 1_000_000),
                   "creationTime": int(now * 1_000_000), "isSecure": 1 if c["secure"] else 0, "isHttpOnly": 1 if c["httpOnly"] else 0,
                   "inBrowserElement": 0, "sameSite": same, "rawSameSite": same, "schemeMap": 2, "isPartitionedAttributeSet": 0}
            keys = [k for k in row if k in cols]
            db.execute("INSERT INTO moz_cookies (%s) VALUES (%s)" % (",".join(keys), ",".join("?" * len(keys))), [row[k] for k in keys])
            written.append(c["name"])
        db.commit()
        return {"schema": ver, "expiry_unit": "ms" if ms else "s", "written": written}
    finally:
        db.close()


def read_cookie_db(domain, path=COOKIES_DB):
    """Reads Firefox's cookie store for *domain (works while Firefox runs: we read a copy of the db + its WAL)."""
    if not os.path.exists(path):
        return []
    tmpd = os.path.join(STATE_DIR, "cookie-readback")
    os.makedirs(tmpd, exist_ok=True)
    for suffix in ("", "-wal"):
        src = path + suffix
        dst = os.path.join(tmpd, "cookies.sqlite" + suffix)
        if os.path.exists(src):
            shutil.copyfile(src, dst)
        elif os.path.exists(dst):
            os.unlink(dst)
    db = sqlite3.connect(os.path.join(tmpd, "cookies.sqlite"))
    try:
        ver = db.execute("PRAGMA user_version").fetchone()[0]
        bare = domain.lstrip(".")
        rows = db.execute("SELECT name, value, host, path, expiry, isSecure, isHttpOnly, sameSite FROM moz_cookies "
                          "WHERE (host = ? OR host = ? OR host LIKE ?) AND originAttributes = ''",
                          ("." + bare, bare, "%." + bare)).fetchall()
    finally:
        db.close()
    out = []
    for name, value, host, cpath, expiry, sec, http, same in rows:
        exp = int(expiry or 0) // (1000 if ver >= 16 else 1)
        out.append({"name": name, "value": value, "domain": host, "path": cpath, "expires": exp, "secure": bool(sec),
                    "httpOnly": bool(http), "sameSite": SAMESITE_NAME.get(same, str(same))})
    return out


def cookie_report(wanted, stored, url):
    by = {}
    for c in stored:
        by.setdefault(c["name"], c)
    lines = ["Firefox cookie store (cookies.sqlite) after the import — %d cookie(s) for this site. Final URL: %s" % (len(stored), url or "?")]
    for c in sorted(wanted, key=lambda c: (c["name"] not in KEY_COOKIES, c["name"])):
        s = by.get(c["name"])
        if s is None:
            lines.append("❌ MISSING     %s" % c["name"])
            continue
        state = "✅ present" if s["value"] == c["value"] else "🔄 updated by site"
        lines.append("%-13s %-12s domain=%s path=%s expires=%s secure=%s httpOnly=%s sameSite=%s" % (
            state, c["name"], s["domain"], s["path"], time.strftime("%Y-%m-%d", time.gmtime(s["expires"])) if s["expires"] else "session",
            s["secure"], s["httpOnly"], s["sameSite"]))
    return "\n".join(lines)


# ---------------- Firefox windows, console guard, URL without JS ----------------
# Root cause of "Imported 10 cookies but X.com still shows: no session cookies" + the Google search page with
# `var _r;try{_r=JSON.stringify({r:eval("document.cookie…` in it: TBP runs JS by pasting it into the DevTools console
# (ctrl+a, paste, ctrl+Return). On the first JS after every daemon start it "syncs" the console state: ctrl+l (URL bar)
# + ctrl+shift+k (toggle) + a test paste. On a slow phone the DevTools window isn't up within its 1 s, the test JS is
# pasted into the URL bar, the toggle closes the console again and TBP still believes it's open → every following
# eval (document.cookie, url, title, readyState) is typed into the address bar and ctrl+Return makes it a Google
# search (→ consent wall). The import restarts the daemon, so it always hit this. Fixes: open + focus the DevTools
# window ourselves right after the daemon starts and before every bridge JS command, detect a leak (main window title /
# last history URL contain the script), go back with the keyboard and reopen the console; verify the import without
# JS (cookies.sqlite + places.sqlite history + window title).
DEVTOOLS_TITLES = ("Developer Tools", "Outils de développement", "Entwicklerwerkzeuge", "Herramientas de desarrollo",
                   "Strumenti di sviluppo")
LEAK_MARKERS = ("_r;try", "_r=JSON.stringify", "JSON.stringify({r:", "copy('TBP", "copy(\"TBP")
JS_CMDS = {"eval", "url", "title", "text", "html", "links"}
js_state = {"synced": False, "leaks": 0}
PLACES_DB = os.path.join(TBP_PROFILE, "places.sqlite")


def win_list(pattern):
    if not shutil.which("xdotool"):
        return []
    code, out, _ = xdotool("search", "--name", pattern)
    if code != 0:
        return []
    res = []
    for wid in out.split():
        c, name, _ = xdotool("getwindowname", wid)
        if c == 0:
            res.append((wid, name.strip()))
    return res


def devtools_window():
    for t in DEVTOOLS_TITLES[:1]:
        for wid, name in win_list(t):
            return wid
    return None


def main_window():
    for wid, name in win_list("Mozilla Firefox"):
        if not any(t in name for t in DEVTOOLS_TITLES):
            return wid, name
    return None, ""


def open_console(main_wid, wait_s=8):
    """ctrl+shift+k from the main window (Escape first, so focus isn't left in the URL bar), wait for the DevTools
    window, focus it."""
    xdotool("windowactivate", "--sync", main_wid)
    xdotool("key", "Escape")
    time.sleep(0.2)
    xdotool("key", "ctrl+shift+k")
    deadline = time.time() + wait_s
    while time.time() < deadline:
        dt = devtools_window()
        if dt:
            xdotool("windowactivate", "--sync", dt)
            time.sleep(0.3)
            return dt
        time.sleep(0.4)
    return None


def kb_navigate(main_wid, url):
    """Navigate with the keyboard only (no JS): focus the URL bar, type, Enter."""
    xdotool("windowactivate", "--sync", main_wid)
    xdotool("key", "Escape")
    xdotool("key", "ctrl+l")
    time.sleep(0.2)
    xdotool("type", "--delay", "0", "--", url)
    xdotool("key", "Return")


def copy_db(path, name):
    tmpd = os.path.join(STATE_DIR, "cookie-readback")
    os.makedirs(tmpd, exist_ok=True)
    for suffix in ("", "-wal"):
        src, dst = path + suffix, os.path.join(tmpd, name + suffix)
        if os.path.exists(src):
            shutil.copyfile(src, dst)
        elif os.path.exists(dst):
            os.unlink(dst)
    return os.path.join(tmpd, name)


def last_visited_url(path=PLACES_DB):
    """Most recent history visit (places.sqlite, read from a copy) — the current page's URL without running JS."""
    if not os.path.exists(path):
        return None
    try:
        db = sqlite3.connect(copy_db(path, "places.sqlite"))
        try:
            row = db.execute("SELECT p.url FROM moz_historyvisits v JOIN moz_places p ON p.id = v.place_id "
                             "ORDER BY v.visit_date DESC, v.id DESC LIMIT 1").fetchone()
        finally:
            db.close()
        return row[0] if row else None
    except (sqlite3.Error, OSError):
        return None


def has_leak(text):
    from urllib.parse import unquote_plus
    t = unquote_plus(text or "")
    return any(m in t for m in LEAK_MARKERS)


def prime_console(notes):
    """Right after the daemon starts (page = about:blank): open + focus the DevTools window, then run one JS so TBP's
    console sync happens now, with the console focused, instead of on the user's page."""
    if not shutil.which("xdotool"):
        return
    wid = None
    for _ in range(max(1, int(float(os.environ.get("FARROW_WINDOW_WAIT_S", "8")) * 2))):
        wid, _t = main_window()
        if wid:
            break
        time.sleep(0.5)
    if not wid:
        notes.append("console prime: no Firefox window found")
        return
    dt = devtools_window() or open_console(wid)
    notes.append("console prime: DevTools window %s" % (dt or "did not open"))
    res = tbp("eval", "1+1", timeout=40)
    notes.append("console prime: eval -> %s" % ("ok" if res.get("ok") else (res.get("stderr") or "failed")[:160]))


def js_guard_before():
    if not shutil.which("xdotool"):
        return None
    wid, title = main_window()
    before = {"wid": wid, "title": title, "url": last_visited_url()}
    if js_state["synced"] and wid:
        dt = devtools_window()
        if dt:
            xdotool("windowactivate", "--sync", dt)
        else:
            open_console(wid)
    return before


def js_guard_after(before):
    if before is None:
        return None
    wid, title = main_window()
    url = last_visited_url()
    js_state["synced"] = True
    leak_now = has_leak(title) or has_leak(url)
    changed = title != before.get("title") or url != before.get("url")
    if not (leak_now and changed):
        return None
    js_state["leaks"] += 1
    prev = before.get("url")
    back = prev if prev and not has_leak(prev) else "about:blank"
    log("JS leaked into the address bar (title %r, url %r) — going back to %s" % (title[:120], (url or "")[:120], back))
    wid = wid or before.get("wid")
    if wid:
        kb_navigate(wid, back)
        time.sleep(2)
        if not devtools_window():
            open_console(wid)
    return {"back_to": back}


# ---------------- browser language ----------------
# The internal browser is English by default, even on a French phone (setting "Browser language" in the app; step 5
# and set_language write ~/.farrow/browser_lang). Applied to Firefox's user.js before every daemon start.
LANG_FILE = os.path.join(STATE_DIR, "browser_lang")
LANGS = {  # code: (intl.accept_languages, intl.locale.requested, google gl, duckduckgo kl)
    "en": ("en-US, en", "en-US", "us", "us-en"),
    "fr": ("fr-FR, fr, en-US, en", "fr-FR", "fr", "fr-fr"),
    "de": ("de-DE, de, en-US, en", "de-DE", "de", "de-de"),
    "es": ("es-ES, es, en-US, en", "es-ES", "es", "es-es"),
    "it": ("it-IT, it, en-US, en", "it-IT", "it", "it-it"),
}
LOCALE_KEYS = ("intl.accept_languages", "intl.locale.requested", "javascript.use_us_english_locale")


def browser_lang():
    try:
        with open(LANG_FILE) as f:
            code = f.read().strip()
    except OSError:
        code = ""
    return code if code in LANGS else "en"


def write_locale_prefs(notes, lang=None, profile=TBP_PROFILE):
    """user.js: our three intl prefs replace any earlier values (TBP only appends its own keys). Firefox stopped."""
    lang = lang or browser_lang()
    accept, locale, _gl, _kl = LANGS.get(lang, LANGS["en"])
    os.makedirs(profile, exist_ok=True)
    path = os.path.join(profile, "user.js")
    try:
        lines = []
        if os.path.exists(path):
            with open(path) as f:
                lines = [l for l in f.read().splitlines() if not any('"%s"' % k in l for k in LOCALE_KEYS)]
        lines += ['user_pref("intl.accept_languages", %s);' % json.dumps(accept),
                  'user_pref("intl.locale.requested", %s);' % json.dumps(locale),
                  'user_pref("javascript.use_us_english_locale", %s);' % ("true" if lang == "en" else "false")]
        with open(path, "w") as f:
            f.write("\n".join(lines) + "\n")
        notes.append("browser language %s (user.js intl.accept_languages=%s)" % (lang, accept))
        return True
    except OSError as e:
        notes.append("could not write user.js: %s" % e)
        return False


def localize_search_url(url, lang=None):
    """Google search → www.google.com with hl/gl/pws=0 (never google.fr); DuckDuckGo → kl. Others unchanged."""
    from urllib.parse import parse_qsl, urlencode
    lang = lang or browser_lang()
    _a, _l, gl, kl = LANGS.get(lang, LANGS["en"])
    u = urlparse(url or "")
    host = (u.hostname or "").lower()
    if re.match(r"^(www\.)?google\.[a-z.]+$", host) and u.path in ("", "/", "/search", "/webhp"):
        q = [(k, v) for k, v in parse_qsl(u.query, keep_blank_values=True) if k not in ("hl", "gl", "pws")]
        q += [("hl", lang), ("gl", gl), ("pws", "0")]
        return "https://www.google.com%s?%s" % (u.path or "/", urlencode(q))
    if re.match(r"^(html\.|lite\.|www\.)?duckduckgo\.com$", host):
        q = [(k, v) for k, v in parse_qsl(u.query, keep_blank_values=True) if k != "kl"] + [("kl", kl)]
        return "https://%s%s?%s" % (host, u.path or "/", urlencode(q))
    return url


def cmd_set_language(a):
    lang = a.get("lang") or "en"
    if lang not in LANGS:
        return {"ok": False, "code": 2, "stdout": "", "stderr": "unknown language %s (one of %s)" % (lang, ", ".join(LANGS)), "data": None}
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(LANG_FILE, "w") as f:
        f.write(lang)
    notes = []
    running = browser_alive()
    if not running:
        write_locale_prefs(notes, lang)
    return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"lang": lang, "applied": not running,
            "restart_needed": running, "notes": notes}}


# ---------------- images / video (v1.11.0) ----------------
# Images and video are blocked in the internal browser by default (the agent reads text; fewer bytes, faster pages).
# Setting "Load images" in the app → set_media → ~/.farrow/load_images ("1" = load). Written to user.js now and before
# every daemon start; Firefox reads user.js only at startup, so a running browser is never restarted for it.
MEDIA_FILE = os.path.join(STATE_DIR, "load_images")
MEDIA_KEYS = ("permissions.default.image", "media.autoplay.default", "media.autoplay.blocking_policy")


def load_images():
    try:
        with open(MEDIA_FILE) as f:
            return f.read().strip() == "1"
    except OSError:
        return False


def media_pref_lines(load):
    vals = (1, 1, 0) if load else (2, 5, 2)   # image 2 = block all; autoplay 5 = block audio+video; policy 2 = user gesture
    return ['user_pref("%s", %d);' % (k, v) for k, v in zip(MEDIA_KEYS, vals)]


def write_media_prefs(notes, load=None, profile=TBP_PROFILE):
    """user.js: our media prefs replace earlier values. Safe while Firefox runs (it only reads user.js at startup)."""
    load = load_images() if load is None else load
    os.makedirs(profile, exist_ok=True)
    path = os.path.join(profile, "user.js")
    try:
        lines = []
        if os.path.exists(path):
            with open(path) as f:
                lines = [l for l in f.read().splitlines() if not any('"%s"' % k in l for k in MEDIA_KEYS)]
        lines += media_pref_lines(load)
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            f.write("\n".join(lines) + "\n")
        os.replace(tmp, path)
        notes.append("images/video %s (user.js)" % ("loaded" if load else "blocked"))
        return True
    except OSError as e:
        notes.append("could not write user.js: %s" % e)
        return False


def cmd_set_media(a):
    load = bool(a.get("load_images"))
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(MEDIA_FILE, "w") as f:
        f.write("1" if load else "0")
    notes = []
    written = write_media_prefs(notes, load)
    running = browser_alive()
    return {"ok": written, "code": 0 if written else 1, "stdout": "", "stderr": "" if written else "; ".join(notes),
            "data": {"load_images": load, "applied": written and not running, "restart_needed": running, "notes": notes}}


# ---------------- Google consent ----------------
# Pre-answered consent wall: SOCS on google.* / youtube.com ("CAE…" = choices made / reject all; yt-dlp uses "CAI" =
# accept all). Override with FARROW_GOOGLE_SOCS.
GOOGLE_SOCS = os.environ.get("FARROW_GOOGLE_SOCS", "CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
GOOGLE_DOMAINS = (".google.com", ".google.fr", ".google.de", ".google.es", ".google.it", ".google.co.uk", ".google.be",
                  ".google.ch", ".google.nl", ".google.pt", ".google.at", ".google.ca", ".youtube.com")
CONSENT_PAGE_RX = re.compile(r"consent\.(google|youtube)\.|Before you continue|Avant d'accéder|Bevor Sie zu|Antes de ir a|"
                             r"Prima di continuare|Voordat je verdergaat", re.I)


def seed_google_consent(notes, path=COOKIES_DB):
    """Firefox must be stopped. Writes SOCS only for domains that don't have one yet."""
    if not os.path.isdir(os.path.dirname(path)):
        return []
    try:
        ensure_cookie_db(path)
        db = sqlite3.connect(path, timeout=10)
        try:
            have = {r[0] for r in db.execute("SELECT host FROM moz_cookies WHERE name = 'SOCS'")}
        finally:
            db.close()
        todo = [d for d in GOOGLE_DOMAINS if d not in have]
        if not todo:
            return []
        now = int(time.time())
        write_cookie_db([{"name": "SOCS", "value": GOOGLE_SOCS, "domain": d, "path": "/", "secure": True, "httpOnly": False,
                          "sameSite": "Lax", "expires": now + 395 * 86400} for d in todo], path=path)
        notes.append("seeded Google consent cookie (SOCS) for %d domain(s)" % len(todo))
        return todo
    except sqlite3.Error as e:
        notes.append("Google consent seeding failed: %s" % e)
        return []


def handle_consent_wall(target):
    """After a goto: on a consent page/wall (detected by URL / window title, no JS), click Reject all, then return to
    the target if we're still not there."""
    _wid, title = main_window()
    url = last_visited_url() or ""
    if not CONSENT_PAGE_RX.search(title + " " + url):
        return None
    clicked = unwrap(tbp("eval", CONSENT_JS, timeout=20))
    time.sleep(2.5)
    wid, title = main_window()
    url = last_visited_url() or ""
    if CONSENT_PAGE_RX.search(title + " " + url) and wid and target:
        kb_navigate(wid, target)
        time.sleep(3)
    return "consent wall: %s" % clicked


def cmd_goto(a):
    target = localize_search_url(a.get("url", ""))
    res = tbp("goto", target, *(["-cf"] if a.get("cf") else []), timeout=120)
    note = handle_consent_wall(target)
    if note:
        res["consent"] = note
    return res


# ---------------- JS-free navigation, readiness, site status (bridge 1.7.0) ----------------
# Why x_post's "goto compose" and x_status hung on the phone: TBP's goto is location.assign() through the DevTools
# console + 3 s + hiding the console + polling document.readyState / location.href — every poll is another console
# paste (seconds each on a phone). X never settles quickly, the app's 20 s step budget expired, but the daemon kept
# running the goto (TBP serialises every command), so the next eval (x_status) queued behind it until the 90 s CLI
# timeout. Now: navigation by keyboard (ctrl+l, URL, Enter) + history/title polling (no JS), a `ready` command that
# waits for in-flight commands and probes the console, and a cookie-store + URL `site_status` (no JS).
inflight = {"n": 0, "cmds": []}
inflight_cv = threading.Condition()
NO_DAEMON_CMDS = {"job_start", "job_status", "ready", "site_status", "status", "cookies_check", "cookies_list", "exec", "fingerprint", "set_language", "set_media", "start", "stop", "reset"}


def url_matches(url, target):
    """Same host (www. ignored) and the target's path is a prefix of the URL's path."""
    if not url:
        return False
    u, t = urlparse(url), urlparse(target)
    hu, ht = (u.hostname or "").lower(), (t.hostname or "").lower()
    if hu.removeprefix("www.") != ht.removeprefix("www."):
        return False
    tp = (t.path or "/").rstrip("/")
    return not tp or (u.path or "/").rstrip("/").startswith(tp)


def cmd_nav(a):
    """Keyboard navigation (no JS), returns as soon as the history shows the target URL (or after `timeout` s).
    Not waiting for 'load' — SPAs like X keep connections open. Falls back to `tbp goto` without xdotool."""
    target = localize_search_url(a.get("url", ""))
    timeout = max(3, min(int(a.get("timeout", 30)), 110))
    t0 = time.time()
    steps = []
    if not shutil.which("xdotool"):
        res = tbp("goto", target, timeout=timeout)
        res["data"] = {"via": "tbp goto", "url": None, "matched": res.get("ok"), "seconds": round(time.time() - t0, 1)}
        return res
    bad = ensure_daemon_ready()
    if bad:
        return {"ok": False, "code": 3, "stdout": "", "stderr": "TBP daemon not running: %s" % bad.get("error"), "data": None}
    wid = None
    for _ in range(10):
        wid, _t = main_window()
        if wid:
            break
        time.sleep(0.5)
    if not wid:
        return {"ok": False, "code": 1, "stdout": "", "stderr": "no Firefox window on %s" % DISPLAY, "data": None}
    before = last_visited_url()
    steps.append("from %s" % (before or "?"))
    kb_navigate(wid, target)
    steps.append("typed URL + Enter (%.1f s)" % (time.time() - t0))
    url, title = before, ""
    while time.time() - t0 < timeout:
        time.sleep(0.7)
        url = last_visited_url()
        _w, title = main_window()
        if url_matches(url, target) and (url != before or url_matches(before, target)):
            break
    matched = url_matches(url, target)
    steps.append("%s after %.1f s: %s | %s" % ("at target" if matched else "NOT at target", time.time() - t0, url, title))
    note = handle_consent_wall(target) if matched is False else None
    if note:
        steps.append(note)
    log("nav %s -> %s" % (target, "; ".join(steps)))
    return {"ok": matched, "code": 0 if matched else 1, "stdout": "", "stderr": "" if matched else
            "navigation to %s not confirmed within %d s (current: %s)" % (target, timeout, url),
            "data": {"via": "keyboard", "url": url, "title": title, "matched": matched, "seconds": round(time.time() - t0, 1), "steps": steps}}


def cmd_ready(a):
    """Before site tools: socket up (≤ timeout s, starting the daemon if needed), no other command in flight
    (TBP serialises them), DevTools console window present, and a 1+1 eval answering."""
    timeout = max(5, min(int(a.get("timeout", 30)), 90))
    t0 = time.time()
    steps = []
    if not socket_connectable():
        st = start_daemon(wait_s=timeout)
        steps.append("daemon start -> %s" % ("ok" if st.get("running") else st.get("error")))
    if not socket_connectable():
        return {"ok": False, "code": 3, "stdout": "", "stderr": "TBP daemon not running", "data": {"socket": False, "steps": steps,
                "daemon_log": tail(TBP_DAEMON_LOG, 20)}}
    with inflight_cv:
        busy = list(inflight["cmds"])
        while inflight["n"] > 0 and time.time() - t0 < timeout:
            inflight_cv.wait(1.0)
        still = list(inflight["cmds"])
    if busy:
        steps.append("waited %.1f s for %s" % (time.time() - t0, ", ".join(busy)))
    if still:
        return {"ok": False, "code": 1, "stdout": "", "stderr": "browser still busy with %s after %d s" % (", ".join(still), timeout),
                "data": {"socket": True, "busy": still, "steps": steps}}
    console = None
    if shutil.which("xdotool"):
        wid, _t = main_window()
        console = devtools_window() or (open_console(wid) if wid and js_state["synced"] else None)
        steps.append("console window %s" % (console or "missing"))
    left = max(5, int(timeout - (time.time() - t0)))
    t1 = time.time()
    probe = tbp("eval", "1+1", timeout=left)
    ok = probe.get("ok") and str(unwrap(probe)).strip() in ("2", "2.0")
    steps.append("eval probe %s in %.1f s" % ("ok" if ok else (probe.get("stderr") or "failed")[:160], time.time() - t1))
    return {"ok": bool(ok), "code": 0 if ok else 1, "stdout": "", "stderr": "" if ok else "console not answering: " + steps[-1],
            "data": {"socket": True, "console": console, "eval_ok": bool(ok), "seconds": round(time.time() - t0, 1), "steps": steps}}


def cmd_site_status(a):
    """Login state without JS: key cookies in cookies.sqlite (not expired) + current URL (history) / window title."""
    domain = a.get("domain") or ".x.com"
    keys = a.get("key_cookies") or ["auth_token", "ct0"]
    patterns = a.get("logged_out_patterns") or []
    now = time.time()
    t0 = time.time()
    stored = read_cookie_db(domain)
    t_cookies = time.time() - t0
    live = {c["name"] for c in stored if not c["expires"] or c["expires"] > now}
    missing = [k for k in keys if k not in live]
    url = last_visited_url()
    title = main_window()[1] if shutil.which("xdotool") else ""
    with inflight_cv:
        busy = list(inflight["cmds"])
    bare = domain.lstrip(".")
    on_site = bool(url) and (urlparse(url).hostname or "").endswith(bare)
    wall = on_site and (any(p in url for p in patterns) or bool(LOGGED_OUT_RX.search(urlparse(url).path or "")))
    if missing:
        state, reason = "LOGGED_OUT", "session cookies missing in Firefox's cookie store: %s" % ", ".join(missing)
    elif wall:
        state, reason = "LOGGED_OUT", "the browser is on a login page (%s)" % url
    else:
        state, reason = "LOGGED_IN", "session cookies %s in Firefox's cookie store%s" % (
            ", ".join(keys), "; current page %s" % url if on_site else "")
    return {"ok": True, "code": 0, "stdout": "", "stderr": "",
            "data": {"state": state, "reason": reason, "url": url, "title": title, "on_site": on_site, "missing": missing,
                     "cookies": sorted(live), "busy": busy, "cookies_ms": int(t_cookies * 1000),
                     "ms": int((time.time() - t0) * 1000)}}


# ---------------- sturdy editor input (bridge 1.8.0) ----------------
# X's compose box is a Draft.js contenteditable. `tbp click` on it can hang (its readiness polling is console JS), so
# the app falls back to: focus via one eval, type with xdotool into the main window (the page keeps its focus), verify
# the text by eval, and if it isn't there, document.execCommand('insertText') (Draft.js handles that input event).
FOCUS_JS = r"""(function(sel){var e=document.querySelector(sel);if(!e)return 'missing';
var ed=e.matches('[contenteditable=true],[contenteditable=""],[role=textbox],input,textarea')?e:(e.querySelector('[contenteditable=true],[role=textbox]')||null);
var t=ed||e;t.scrollIntoView({block:'center'});if(ed){ed.focus();try{var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);var s=getSelection();s.removeAllRanges();s.addRange(r)}catch(x){}return 'focused'}
t.click();return 'clicked'})(%s)"""
TEXT_JS = r"""(function(sel){var e=document.querySelector(sel);if(!e)return '';var ed=e.matches('[contenteditable],[role=textbox],input,textarea')?e:(e.querySelector('[contenteditable=true],[role=textbox]')||e);
return (ed.value!==undefined&&ed.value!==null&&ed.tagName!=='DIV'?ed.value:ed.innerText)||''})(%s)"""
INSERT_JS = r"""(function(sel,txt){var e=document.querySelector(sel);if(!e)return 'missing';var ed=e.matches('[contenteditable],[role=textbox]')?e:(e.querySelector('[contenteditable=true],[role=textbox]')||e);
ed.focus();try{document.execCommand('selectAll',false,null)}catch(x){}var ok=false;try{ok=document.execCommand('insertText',false,txt)}catch(x){}
if(!ok){try{var dt=new DataTransfer();dt.setData('text/plain',txt);ed.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));ok=true}catch(x){}}
return ok?'inserted':'failed'})(%s,%s)"""


def norm_text(s):
    return re.sub(r"\s+", " ", (s or "").replace("\u200b", "")).strip()


def js_value(res):
    v = unwrap(res)
    return v if isinstance(v, str) else ("" if v is None else str(v))


def cmd_focus(a):
    """Focus (contenteditable/textbox/input) or click the element with one eval — no `tbp click`."""
    res = tbp("eval", FOCUS_JS % json.dumps(a.get("selector") or ""), timeout=int(a.get("timeout", 30)))
    v = js_value(res)
    ok = res.get("ok") and v in ("focused", "clicked")
    return {"ok": bool(ok), "code": 0 if ok else 1, "stdout": v, "stderr": "" if ok else (res.get("stderr") or "element %s" % v),
            "data": {"result": v}}


def cmd_key(a):
    """xdotool key(s) on the main Firefox window (e.g. ctrl+Return), no JS."""
    keys = a.get("keys") or "Return"
    wid, _t = main_window()
    if not wid:
        return {"ok": False, "code": 1, "stdout": "", "stderr": "no Firefox window", "data": None}
    xdotool("windowactivate", "--sync", wid)
    code, _o, err = xdotool("key", "--clearmodifiers", keys)
    return {"ok": code == 0, "code": code, "stdout": "", "stderr": err, "data": {"keys": keys}}


# bridge 1.13.0 (Farrow v1.0.11): editor_type is again exactly v1.0.0's (bridge 1.9.0) — focus by eval → xdotool
# typing into the main window → verify → insertText. The 1.12.0 probe/paste/marker variant typed nothing on the phone.
def cmd_editor_type(a):
    """Type into a (Draft.js) editor: focus by eval → xdotool typing into the main window → verify → insertText."""
    sel, text = a.get("selector") or "", a.get("text") or ""
    delays = a.get("delays_ms") or []
    steps = []
    f = cmd_focus({"selector": sel})
    steps.append("focus: %s" % (f["stdout"] or f["stderr"])[:120])
    if not f["ok"]:
        return {"ok": False, "code": 1, "stdout": "", "stderr": "could not focus %s: %s" % (sel, f["stderr"]), "data": {"steps": steps}}
    method = None
    if shutil.which("xdotool"):
        wid, _t = main_window()
        if wid:
            xdotool("windowactivate", "--sync", wid)   # page keeps document.activeElement
            time.sleep(0.2)
            for i, ch in enumerate(text):
                if ch == "\n":
                    xdotool("key", "shift+Return")
                else:
                    xdotool("type", "--delay", "0", "--", ch)
                time.sleep(max(0, min(int(delays[i] if i < len(delays) else 40), 600)) / 1000.0)
            method = "xdotool"
            steps.append("typed %d chars with xdotool" % len(text))
    want = norm_text(text)
    got = norm_text(js_value(tbp("eval", TEXT_JS % json.dumps(sel), timeout=30)))
    steps.append("editor text after typing: %d chars" % len(got))
    if want not in got:
        r = tbp("eval", INSERT_JS % (json.dumps(sel), json.dumps(text)), timeout=30)
        steps.append("insertText: %s" % (js_value(r) or r.get("stderr", ""))[:120])
        got = norm_text(js_value(tbp("eval", TEXT_JS % json.dumps(sel), timeout=30)))
        method = "insertText"
    ok = want in got
    log("editor_type %s: %s" % (sel, "; ".join(steps)))
    return {"ok": ok, "code": 0 if ok else 1, "stdout": "", "stderr": "" if ok else
            "text not in the editor after typing and insertText (editor has %d chars)" % len(got),
            "data": {"method": method, "chars": len(got), "steps": steps}}


# ---------------- cookie import ----------------
LOGGED_OUT_RX = re.compile(r"/login|/i/flow/|/signup|/onboarding|/checkpoint|login\.php|/account/access|/logout", re.I)


def count_rows(domain, path=COOKIES_DB):
    try:
        return len(read_cookie_db(domain, path))
    except (sqlite3.Error, OSError):
        return -1


def wait_browser_gone(seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if not browser_alive():
            return True
        time.sleep(0.5)
    return not browser_alive()


def checkpoint_db(path=COOKIES_DB):
    db = sqlite3.connect(path, timeout=10)
    try:
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    finally:
        db.close()


def stop_for_profile_write(notes):
    """Stop the daemon and wait until the daemon AND every Firefox on the profile have exited (Firefox rewrites
    cookies.sqlite from memory on exit, so writing earlier is lost). Kill after 20 s. Only then clear stale files."""
    if live_tbp_pid() or socket_connectable():
        code, out, err = run(["tbp", "stop"], timeout=40)
        notes.append("tbp stop -> %s" % code)
    if not wait_browser_gone(20):
        notes.append("daemon/Firefox still alive 20 s after tbp stop — killing")
        stop_everything(notes)
        pkill(FIREFOX_RX, notes)
        wait_browser_gone(10)
    gone = not browser_alive()
    clear_if_absent(notes)
    return gone


def restart_confirmed(notes):
    """Start the daemon and confirm its socket accepts connections; one clean retry (stale files cleared or a reset
    if a pid hangs). Returns start_daemon's result + daemon_log tail when still down."""
    wait = int(os.environ.get("FARROW_RESTART_WAIT_S", "40"))
    st = start_daemon(wait_s=wait)
    if not st.get("running"):
        notes.append("restart failed (%s) — retrying once" % st.get("error"))
        if st.get("needs_reset") or browser_alive():
            st = start_daemon(reset=True, wait_s=wait)
        else:
            clear_if_absent(notes)
            st = start_daemon(wait_s=wait)
    st["running"] = bool(st.get("running")) and socket_connectable()
    if not st["running"]:
        st["daemon_log"] = tail(TBP_DAEMON_LOG, 30)
    notes.extend(st.get("notes", []))
    return st


def cmd_cookies_import(a):
    """Import = stop the daemon and wait for Firefox to exit, write moz_cookies (HttpOnly/Secure/SameSite/expiry
    intact), checkpoint, ALWAYS restart the daemon and confirm its socket, goto the target page, then verify without
    JS: cookie rows in cookies.sqlite + final URL from history (places.sqlite) / window title."""
    raw = a.get("cookies")
    if not isinstance(raw, list) or not raw:
        return {"ok": False, "code": 2, "stdout": "", "stderr": "no cookies given", "data": None}
    cookies = [normalize_cookie(c) for c in raw if isinstance(c, dict) and c.get("name")]
    os.makedirs(SESSIONS_DIR, exist_ok=True)
    path = os.path.join(SESSIONS_DIR, safe_name(a.get("name") or "import") + ".import.json")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(cookies, f, indent=2)
    domain = cookies[0]["domain"] or ".x.com"
    then = (a.get("then") or a.get("origin") or "").strip() or ("https://" + domain.lstrip(".") + "/")
    notes = ["profile: %s" % TBP_PROFILE]
    rows = {}
    write_err = None
    with daemon_lock:
        try:
            gone = stop_for_profile_write(notes)
            if not gone:
                write_err = "Firefox on %s did not exit — cookies not written" % TBP_PROFILE
            else:
                rows["before"] = count_rows(domain)
                info = write_cookie_db(cookies)
                checkpoint_db()
                rows["after_write"] = count_rows(domain)
                notes.append("wrote %d cookies into cookies.sqlite (schema %s, expiry in %s)" % (
                    len(info["written"]), info["schema"], info["expiry_unit"]))
        except sqlite3.Error as e:
            write_err = "writing %s failed: %s" % (COOKIES_DB, e)
        finally:
            st = restart_confirmed(notes)   # always — the import never leaves the browser off
    if write_err or not st.get("running"):
        msg = write_err or ("cookies written, but the TBP daemon did not come back: %s" % st.get("error"))
        if write_err and not st.get("running"):
            msg += "; the TBP daemon is also down: %s" % st.get("error")
        log("cookies_import %s: %s rows %s" % (domain, msg, rows))
        return {"ok": False, "code": 1, "stdout": "", "stderr": msg,
                "data": {"notes": notes, "rows": rows, "report": "", "daemon_down": not st.get("running"),
                         "daemon_log": st.get("daemon_log", ""), "error": st.get("error")}}
    rows["after_restart"] = count_rows(domain)
    nav = tbp("goto", then, timeout=75)
    time.sleep(3)
    consent = handle_consent_wall(then)
    banner = unwrap(tbp("eval", CONSENT_JS, timeout=20)) if not consent else consent
    time.sleep(1.5)
    _wid, title = main_window()
    url = last_visited_url()
    stored = read_cookie_db(domain)
    rows["after_goto"] = len(stored)
    names = {c["name"] for c in stored}
    present = [c["name"] for c in cookies if c["name"] in names]
    missing = [c["name"] for c in cookies if c["name"] not in names]
    keys = [n for n in ("auth_token", "ct0", "c_user", "xs") if any(c["name"] == n for c in cookies)]
    keys_ok = all(n in names for n in keys)
    bare = domain.lstrip(".")
    on_site = bool(url) and (urlparse(url).hostname or "").endswith(bare)
    logged_in = None if not url else bool(keys_ok and on_site and not LOGGED_OUT_RX.search(urlparse(url).path or ""))
    report = (cookie_report(cookies, stored, url) + "\nWindow title: %s\nCookie banner: %s\nRows for %s: %s\n%s" % (
        title or "?", banner, domain, ", ".join("%s=%s" % kv for kv in rows.items()), "\n".join(notes)))
    log("cookies_import %s: present %s missing %s url %s logged_in %s rows %s" % (domain, present, missing, url, logged_in, rows))
    ok = bool(present) and not missing
    return {"ok": ok, "code": 0 if ok else 1, "stdout": report,
            "stderr": "" if ok else "missing in Firefox's cookie store: %s" % ", ".join(missing), "path": path, "count": len(cookies),
            "data": {"present": present, "missing": missing, "report": report, "url": url, "title": title, "banner": banner,
                     "notes": notes, "rows": rows, "logged_in": logged_in, "key_cookies_ok": keys_ok,
                     "goto_ok": nav.get("ok"), "daemon_down": False}}


# ---------------- background jobs (v1.10.0) ----------------
# cookies_import stops Firefox, writes cookies.sqlite and restarts the daemon. Run as a job it is ONE atomic bridge-side
# operation: the app only starts it and polls job_status, so the app closing, going to the background, losing the
# connection or the user cancelling (the app just stops waiting) can never leave the browser stopped — the write and
# the restart always finish here. Results are kept in memory and in ~/.farrow/jobs/<id>.json.
JOBS_DIR = os.path.join(STATE_DIR, "jobs")
JOB_CMDS = {"cookies_import", "reset", "start", "stop"}   # v1.11.0: reset/start/stop too (Settings → Browser)
jobs = {}
jobs_lock = threading.Lock()


def _job_save(job):
    try:
        os.makedirs(JOBS_DIR, exist_ok=True)
        tmp = os.path.join(JOBS_DIR, job["id"] + ".json.tmp")
        with open(tmp, "w") as f:
            json.dump(job, f)
        os.replace(tmp, os.path.join(JOBS_DIR, job["id"] + ".json"))
    except (OSError, TypeError, ValueError) as e:
        log("job %s: could not persist: %r" % (job.get("id"), e))


def cmd_job_start(a):
    """{"cmd": "cookies_import", "args": {...}} -> {"job": id} at once; the command runs in a bridge thread.
    Single-flight per command: while one runs, a second start returns the running job (attached = true)."""
    cmd = a.get("cmd")
    if cmd not in JOB_CMDS:
        return {"ok": False, "code": 2, "stdout": "", "stderr": "not a job command: %s" % cmd, "data": None}
    with jobs_lock:
        for j in jobs.values():
            if j["cmd"] == cmd and j["state"] == "running":
                return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"job": j["id"], "attached": True}}
        jid = "%s-%d-%s" % (cmd, int(time.time() * 1000), os.urandom(3).hex())
        job = {"id": jid, "cmd": cmd, "state": "running", "started": time.time(), "finished": None, "result": None}
        jobs[jid] = job
    _job_save(job)

    def work():
        res = dispatch(cmd, a.get("args") or {})
        with jobs_lock:
            job["result"] = res
            job["state"] = "done"
            job["finished"] = time.time()
        _job_save(job)
        log("job %s done ok=%s" % (jid, res.get("ok")))

    threading.Thread(target=work, name="job-" + jid, daemon=False).start()
    return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"job": jid, "attached": False}}


def cmd_job_status(a):
    """{"job": id} -> {"state": running|done|unknown, "result": <command result when done>}."""
    jid = safe_name(a.get("job") or "")
    with jobs_lock:
        job = dict(jobs[jid]) if jid in jobs else None
    if job is None:
        try:
            with open(os.path.join(JOBS_DIR, jid + ".json")) as f:
                job = json.load(f)
            if job.get("state") == "running":   # the bridge restarted while it ran: it can't still be running
                job["state"] = "lost"
        except (OSError, ValueError):
            job = {"id": jid, "state": "unknown"}
    return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": job}


def cmd_cookies_load(a):
    """Restore a saved login: if Firefox's cookie store still has the cookies (the normal case — they're persistent
    now), nothing to do; otherwise re-import <name>.import.json (restarts the browser)."""
    path = os.path.join(SESSIONS_DIR, safe_name(a.get("name")) + ".import.json")
    if not os.path.exists(path):
        return {"ok": False, "code": 2, "stdout": "", "stderr": "no saved session " + path, "data": None}
    try:
        with open(path) as f:
            cookies = [normalize_cookie(c) for c in json.load(f)]
    except (OSError, ValueError) as e:
        return {"ok": False, "code": 2, "stdout": "", "stderr": "bad session file: %s" % e, "data": None}
    domain = cookies[0]["domain"] if cookies else ".x.com"
    have = {c["name"] for c in read_cookie_db(domain) if not c["expires"] or c["expires"] > time.time()}
    if all(c["name"] in have for c in cookies if c["httpOnly"] or c["name"] in KEY_COOKIES):
        return {"ok": True, "code": 0, "stdout": "cookies already in Firefox's cookie store", "stderr": "", "path": path,
                "data": {"already": True}}
    res = cmd_cookies_import({"name": a.get("name"), "cookies": cookies, "then": a.get("then")})
    res["path"] = path
    return res


def cmd_cookies_check(a):
    """Read-only: Firefox's cookie store for a domain (for the verification card)."""
    domain = a.get("domain") or ".x.com"
    return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"cookies": [dict(c, value=c["value"][:6] + "…") for c in read_cookie_db(domain)]}}


WORK_DIR = os.path.join(HOME, "farrow-work")


def cmd_exec(a):
    """termux_run tool: one bash command inside Termux (ffmpeg, imagemagick, yt-dlp, git, node, jq … installed with
    pkg), cwd ~/farrow-work, timeout ≤ 140 s, output capped at 64 KB each."""
    cmd = a.get("command") or ""
    if not cmd.strip():
        return {"ok": False, "code": 2, "stdout": "", "stderr": "command is required", "data": None}
    os.makedirs(WORK_DIR, exist_ok=True)
    timeout = max(1, min(int(a.get("timeout_s") or 120), 140))  # app HTTP read timeout is 150 s
    try:
        p = subprocess.run(["bash", "-lc", cmd], cwd=WORK_DIR, capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
        out, err, code = p.stdout, p.stderr, p.returncode
    except subprocess.TimeoutExpired as e:
        out, err, code = e.stdout or b"", (e.stderr or b"") + (b"\ntimeout after %ds" % timeout), 124
    cap = 65536
    return {"ok": code == 0, "code": code, "stdout": out[-cap:].decode("utf-8", "replace"), "stderr": err[-cap:].decode("utf-8", "replace"),
            "data": {"cwd": WORK_DIR, "truncated": len(out) > cap or len(err) > cap}}


def cmd_dismiss_cookie_banner(a):
    return tbp("eval", CONSENT_JS, timeout=20)


def cmd_cookies_list(a):
    os.makedirs(SESSIONS_DIR, exist_ok=True)
    names = sorted({f[:-12] if f.endswith(".import.json") else f[:-5] for f in os.listdir(SESSIONS_DIR) if f.endswith(".json")})
    return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"sessions": names}}


def cmd_cookie_set(a):
    argv = ["cookie-set", a.get("name", ""), a.get("value", "")]
    if a.get("domain"):
        argv += ["--domain", a["domain"]]
    if a.get("secure", True):
        argv.append("--secure")
    return tbp(*argv)


def cmd_fingerprint(a):
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(os.path.join(STATE_DIR, "fingerprint.json"), "w") as f:
        json.dump(a.get("info") or {}, f, indent=2)
    return {"ok": True, "code": 0, "stdout": "", "stderr": "", "data": {"saved": True}}


def cmd_screenshot(a):
    """PNG of the current page as base64: `tbp screenshot PATH`, falling back to ImageMagick `import` on Xvfb."""
    os.makedirs(STATE_DIR, exist_ok=True)
    path = os.path.join(STATE_DIR, "screenshot.png")
    try:
        os.remove(path)
    except OSError:
        pass
    notes = []
    sel = (a.get("selector") or "").strip()
    full = bool(a.get("full_page"))
    mode = "viewport"
    if sel:
        # 1.9.0: element screenshot (`tbp screenshot-element SELECTOR PATH`); no root-window fallback for an element.
        res = tbp("screenshot-element", sel, path, timeout=int(a.get("timeout", 30)))
        if not os.path.exists(path):
            d = unwrap(res)
            p = d.get("path") if isinstance(d, dict) else None
            if p and os.path.exists(p):
                path = p
            else:
                return {"ok": False, "code": 1, "stdout": "", "data": None,
                        "stderr": "element screenshot failed for %s: %s" % (sel, (res.get("stderr") or res.get("stdout") or "no file").strip()[:300])}
        mode = "element"
        res = {"ok": True}
    elif full:
        res = tbp("screenshot", path, "--full", timeout=int(a.get("timeout", 45)))
        if os.path.exists(path):
            mode = "full_page"
        else:
            notes.append("full page: " + (res.get("stderr") or res.get("stdout") or "no file").strip()[:200])
            res = tbp("screenshot", path, timeout=int(a.get("timeout", 30)))
    else:
        res = tbp("screenshot", path, timeout=int(a.get("timeout", 30)))
    if not os.path.exists(path):
        d = unwrap(res)
        p = d.get("path") if isinstance(d, dict) else None
        if p and os.path.exists(p):
            path = p
        else:
            notes.append("tbp screenshot: " + (res.get("stderr") or res.get("stdout") or "no file").strip()[:300])
    if not os.path.exists(path) and shutil.which("import"):
        code, _, err = run(["import", "-display", DISPLAY, "-window", "root", path], timeout=20)
        if code != 0:
            notes.append("import: " + err.strip()[:300])
    if not os.path.exists(path):
        if not shutil.which("import"):
            notes.append("ImageMagick not installed (pkg install imagemagick) for the Xvfb fallback")
        return {"ok": False, "code": 1, "stdout": "", "stderr": "; ".join(notes) or "screenshot failed", "data": None}
    with open(path, "rb") as f:
        png = f.read()
    if len(png) > 8 * 1024 * 1024:
        return {"ok": False, "code": 1, "stdout": "", "stderr": "screenshot too large (%d bytes)" % len(png), "data": None}
    return {"ok": True, "code": 0, "stdout": "", "stderr": "",
            "data": {"png_base64": base64.b64encode(png).decode("ascii"), "path": path, "bytes": len(png), "mode": mode,
                     "notes": notes}}


COMMANDS = {
    "goto": cmd_goto,
    "text": lambda a: tbp("text", *(["--selector", a["selector"]] if a.get("selector") else [])),
    "html": lambda a: tbp("html", *(["--selector", a["selector"]] if a.get("selector") else [])),
    "eval": lambda a: tbp("eval", a.get("expression", ""), timeout=max(5, min(int(a.get("timeout", 90)), 110))),
    "nav": cmd_nav,
    "focus": cmd_focus,
    "key": cmd_key,
    "editor_type": cmd_editor_type,
    "ready": cmd_ready,
    "site_status": cmd_site_status,
    "title": lambda a: tbp("title"),
    "url": lambda a: tbp("url"),
    "links": lambda a: tbp("links", "--limit", int(a.get("limit", 100))),
    "press": lambda a: tbp("press", a.get("key", "Enter")),
    "scroll": lambda a: tbp("scroll", "--up" if a.get("up") else "--down"),
    "click": cmd_click,
    "type": cmd_type,
    "cookies_save": cmd_cookies_save,
    "cookies_load": cmd_cookies_load,
    "cookies_list": cmd_cookies_list,
    "cookies_clear": lambda a: tbp("cookies", "--clear"),
    "fingerprint": cmd_fingerprint,
    "cookie_set": cmd_cookie_set,
    "cookies_import": cmd_cookies_import,
    "job_start": cmd_job_start,
    "job_status": cmd_job_status,
    "dismiss_cookie_banner": cmd_dismiss_cookie_banner,
    "cookies_check": cmd_cookies_check,
    "exec": cmd_exec,
    "set_language": cmd_set_language,
    "set_media": cmd_set_media,
    "reset": lambda a: start_daemon(reset=True),
    "screenshot": cmd_screenshot,
    "status": lambda a: tbp("status", timeout=20),
    "start": lambda a: start_daemon(),
    "stop": lambda a: tbp("stop", timeout=30),
}


def dispatch(cmd, args):
    fn = COMMANDS.get(cmd)
    if fn is None:
        return {"ok": False, "code": 2, "stdout": "", "stderr": "unknown cmd: %s" % cmd, "data": None}
    tracked = cmd not in NO_DAEMON_CMDS
    if tracked:
        with inflight_cv:
            inflight["n"] += 1; inflight["cmds"].append(cmd)
    try:
        res = fn(args or {})
    except Exception as e:  # never crash the server
        res = {"ok": False, "code": 1, "stdout": "", "stderr": repr(e), "data": None}
    finally:
        if tracked:
            with inflight_cv:
                inflight["n"] -= 1
                if cmd in inflight["cmds"]:
                    inflight["cmds"].remove(cmd)
                inflight_cv.notify_all()
    broadcast({"event": "cmd", "cmd": cmd, "ok": res.get("ok", False)})
    return res


# ---------------- minimal WebSocket (RFC 6455, text frames only) ----------------

def ws_send(sock, text):
    payload = text.encode("utf-8")
    header = bytearray([0x81])
    n = len(payload)
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126); header += struct.pack(">H", n)
    else:
        header.append(127); header += struct.pack(">Q", n)
    sock.sendall(bytes(header) + payload)


def ws_recv(sock):
    def read(n):
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        return buf
    b1, b2 = read(2)
    opcode, masked, n = b1 & 0x0F, b2 & 0x80, b2 & 0x7F
    if n == 126:
        n = struct.unpack(">H", read(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", read(8))[0]
    mask = read(4) if masked else b"\x00\x00\x00\x00"
    data = bytes(b ^ mask[i % 4] for i, b in enumerate(read(n)))
    return opcode, data


def broadcast(obj):
    msg = json.dumps(obj)
    with ws_lock:
        dead = []
        for s in ws_clients:
            try:
                ws_send(s, msg)
            except Exception:
                dead.append(s)
        for s in dead:
            ws_clients.discard(s)


class Handler(BaseHTTPRequestHandler):
    server_version = "FarrowBridge/" + VERSION

    def log_message(self, fmt, *args):
        pass

    def _authorised(self):
        q = parse_qs(urlparse(self.path).query)
        given = self.headers.get("X-Bridge-Token") or (q.get("token") or [""])[0]
        return bool(TOKEN) and given == TOKEN

    def _json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if not self._authorised():
            return self._json(401, {"ok": False, "error": "bad token"})
        if path == "/health":
            d = daemon_state()
            return self._json(200, {"ok": True, "version": VERSION, "tbp": shutil.which("tbp") is not None,
                                    "xdotool": shutil.which("xdotool") is not None,
                                    "daemon": d["running"], "daemon_status": d.get("status"), "daemon_error": d.get("error"),
                                    "daemon_unresponsive": d.get("unresponsive", False)})
        if path == "/daemon/status":
            d = daemon_state()
            return self._json(200, {"ok": True, "running": d["running"], "starting": d.get("starting", False),
                                    "status": d.get("status"), "error": d.get("error"), "last_start": daemon_info["last_start"],
                                    "lock_pid": read_pid(TBP_LOCK), "daemon_pid": read_pid(TBP_PID),
                                    "unresponsive": d.get("unresponsive", False), "needs_reset": d.get("unresponsive", False),
                                    "firefox_log": tail(FIREFOX_PROBE_LOG, 40),
                                    "tbp_log": tail(TBP_START_LOG), "daemon_log": tail(TBP_DAEMON_LOG)})
        if path == "/daemon/start":
            return self._json(200, start_daemon())
        if path == "/daemon/reset":
            return self._json(200, start_daemon(reset=True))
        if path == "/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
            return self._websocket()
        return self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._authorised():
            return self._json(401, {"ok": False, "error": "bad token"})
        if urlparse(self.path).path == "/daemon/start":
            return self._json(200, start_daemon())
        if urlparse(self.path).path == "/daemon/reset":
            return self._json(200, start_daemon(reset=True))
        if urlparse(self.path).path != "/cmd":
            return self._json(404, {"ok": False, "error": "not found"})
        length = int(self.headers.get("Content-Length") or 0)
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self._json(400, {"ok": False, "error": "invalid json"})
        return self._json(200, dispatch(req.get("cmd"), req.get("args")))

    def _websocket(self):
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        sock = self.connection
        with ws_lock:
            ws_clients.add(sock)
        try:
            ws_send(sock, json.dumps({"event": "hello", "version": VERSION}))
            while True:
                opcode, data = ws_recv(sock)
                if opcode == 0x8:
                    break
                if opcode == 0x9:  # ping -> pong
                    sock.sendall(bytes([0x8A, len(data)]) + data)
                    continue
                if opcode != 0x1:
                    continue
                try:
                    req = json.loads(data.decode("utf-8"))
                except ValueError:
                    continue
                res = dispatch(req.get("cmd"), req.get("args"))
                res["id"] = req.get("id")
                with ws_lock:
                    ws_send(sock, json.dumps(res))
        except (ConnectionError, OSError):
            pass
        finally:
            with ws_lock:
                ws_clients.discard(sock)
        self.close_connection = True


def log(msg):
    print("[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--token", default=os.environ.get("FARROW_BRIDGE_TOKEN", ""))
    a = ap.parse_args()
    import sys
    log("Farrow bridge %s starting (python %s, pid %d)" % (VERSION, sys.version.split()[0], os.getpid()))
    TOKEN = a.token
    source = "--token"
    if not TOKEN:
        token_file = os.path.join(STATE_DIR, "token")
        if os.path.exists(token_file):
            with open(token_file) as f:
                TOKEN = f.read().strip()
            source = token_file
    if not TOKEN:
        log("ERROR: no token (pass --token or run wizard step 4 to write ~/.farrow/token)")
        raise SystemExit(2)
    log("token loaded from %s; tbp=%s xdotool=%s" % (source, shutil.which("tbp"), shutil.which("xdotool")))
    os.makedirs(SESSIONS_DIR, exist_ok=True)
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    except OSError as e:
        log("ERROR: cannot bind 127.0.0.1:%d: %s (is another bridge already running? pkill -f '[t]bp_bridge.py')" % (a.port, e))
        raise SystemExit(3)
    srv.daemon_threads = True
    log("listening on 127.0.0.1:%d" % a.port)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        log("bridge stopped")


if __name__ == "__main__":
    main()
