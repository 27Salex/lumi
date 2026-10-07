"""My PC (view only) for Lumi Hub: list monitors and serve JPEG frames to an authorised, unlocked phone.

Strictly read-only: there is no input injection, no terminal and no command execution anywhere in this module.
Standard library only. Windows capture uses GDI (BitBlt) and GDI+ (JPEG encoder) through ctypes.
Never logs tokens or screen content (the audit log only records events).
"""

import hashlib
import hmac
import json
import secrets
import sys
import threading
import time
from collections import deque
from pathlib import Path

UNLOCK_TTL = 600            # seconds an unlock lasts (extendable while viewing)
UNLOCK_MAX_SESSION = 3600   # absolute cap of one unlock, however often it is extended
MAX_ACTIVE_TOKENS = 3
VIEWER_IDLE = 6             # seconds without a frame request before a viewer counts as gone
FAIL_LIMIT = 5              # failed attempts ...
FAIL_WINDOW = 300           # ... inside this many seconds ...
LOCKOUT_SECONDS = 900       # ... lock all PC-view access for this long
UNLOCKS_PER_MINUTE = 6
MIN_FRAME_INTERVAL = 0.12   # seconds between frames of one viewer (server side fps cap, about 8 fps)
MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH = 320, 1920, 1280
MIN_QUALITY, MAX_QUALITY, DEFAULT_QUALITY = 20, 85, 60
MAX_FRAME_BYTES = 1_500_000
MAX_MONITORS = 8


class PcError(Exception):
    def __init__(self, message, code=400, extra=None):
        super().__init__(message)
        self.code = code
        self.extra = extra or {}


class BackendUnavailable(Exception):
    pass


# -- Capture backends -------------------------------------------------------------------------------------------


class Monitor:
    def __init__(self, id, name, x, y, width, height, primary):
        self.id, self.name, self.x, self.y, self.width, self.height, self.primary = id, name, x, y, width, height, primary

    def public(self):
        return {"id": self.id, "name": self.name, "width": self.width, "height": self.height, "primary": self.primary}


class FakeBackend:
    """Deterministic backend for tests: a tiny fake JPEG whose bytes depend on the settings."""
    name = "fake"

    def __init__(self, monitors=2, size=2000):
        self.monitors = [Monitor(i, f"Monitor {i + 1}", i * 1920, 0, 1920, 1080, i == 0) for i in range(monitors)]
        self.size = size
        self.captures = 0
        self.tick = 0

    def list_monitors(self):
        return list(self.monitors)

    def capture_jpeg(self, monitor, max_width, quality):
        self.captures += 1
        width = min(max_width, monitor.width)
        height = round(monitor.height * width / monitor.width)
        body = b"\xff\xd8" + bytes([quality, monitor.id & 255]) + self.tick.to_bytes(4, "big") + b"\0" * self.size + b"\xff\xd9"
        return body, width, height


def _load_gdi():
    import ctypes
    from ctypes import wintypes

    user32, gdi32, ole32, kernel32 = ctypes.windll.user32, ctypes.windll.gdi32, ctypes.windll.ole32, ctypes.windll.kernel32
    gdiplus = ctypes.windll.gdiplus
    vp = ctypes.c_void_p
    user32.GetDC.restype, user32.GetDC.argtypes = vp, [vp]
    user32.ReleaseDC.argtypes = [vp, vp]
    gdi32.CreateCompatibleDC.restype, gdi32.CreateCompatibleDC.argtypes = vp, [vp]
    gdi32.CreateCompatibleBitmap.restype = vp
    gdi32.CreateCompatibleBitmap.argtypes = [vp, ctypes.c_int, ctypes.c_int]
    gdi32.SelectObject.restype, gdi32.SelectObject.argtypes = vp, [vp, vp]
    gdi32.DeleteObject.argtypes = [vp]
    gdi32.DeleteDC.argtypes = [vp]
    gdi32.SetStretchBltMode.argtypes = [vp, ctypes.c_int]
    gdi32.StretchBlt.argtypes = [vp] + [ctypes.c_int] * 4 + [vp] + [ctypes.c_int] * 4 + [ctypes.c_uint32]
    gdiplus.GdipCreateBitmapFromHBITMAP.argtypes = [vp, vp, ctypes.POINTER(vp)]
    gdiplus.GdipSaveImageToStream.argtypes = [vp, vp, vp, vp]
    gdiplus.GdipDisposeImage.argtypes = [vp]
    ole32.CreateStreamOnHGlobal.argtypes = [vp, ctypes.c_int, ctypes.POINTER(vp)]
    ole32.GetHGlobalFromStream.argtypes = [vp, ctypes.POINTER(vp)]
    kernel32.GlobalLock.restype, kernel32.GlobalLock.argtypes = vp, [vp]
    kernel32.GlobalUnlock.argtypes = [vp]
    kernel32.GlobalSize.restype, kernel32.GlobalSize.argtypes = ctypes.c_size_t, [vp]
    return ctypes, wintypes, user32, gdi32, ole32, kernel32, gdiplus


class GdiBackend:
    """Windows screen capture with no third-party packages: GDI BitBlt + the GDI+ JPEG encoder."""
    name = "windows-gdi"

    def __init__(self):
        if sys.platform != "win32":
            raise BackendUnavailable("Screen capture is implemented for Windows only.")
        try:
            (self.c, self.wt, self.user32, self.gdi32, self.ole32, self.kernel32, self.gdiplus) = _load_gdi()
        except Exception as e:
            raise BackendUnavailable(f"GDI / GDI+ not available: {e}")
        c = self.c
        try:
            self.user32.SetProcessDPIAware()  # physical pixels, not scaled coordinates
        except Exception:
            pass

        class StartupInput(c.Structure):
            _fields_ = [("version", c.c_uint32), ("callback", c.c_void_p), ("no_thread", c.c_int), ("no_codecs", c.c_int)]

        class Guid(c.Structure):
            _fields_ = [("d1", c.c_uint32), ("d2", c.c_uint16), ("d3", c.c_uint16), ("d4", c.c_ubyte * 8)]

        class EncoderParam(c.Structure):
            _fields_ = [("guid", Guid), ("count", c.c_ulong), ("type", c.c_ulong), ("value", c.c_void_p)]

        class EncoderParams(c.Structure):
            _fields_ = [("count", c.c_uint32), ("param", EncoderParam * 1)]

        def guid(text):
            import uuid
            u = uuid.UUID(text)
            g = Guid()
            g.d1, g.d2, g.d3 = u.time_low, u.time_mid, u.time_hi_version
            for i, b in enumerate(u.bytes[8:]):
                g.d4[i] = b
            return g

        self._Guid, self._Params, self._Param = Guid, EncoderParams, EncoderParam
        self._jpeg = guid("557CF401-1A04-11D3-9A73-0000F81EF32E")
        self._quality = guid("1D5BE4B5-FA4A-452D-9CDD-5DB35105E7EB")
        token = c.c_size_t()
        start = StartupInput(1, None, 0, 0)
        if self.gdiplus.GdiplusStartup(c.byref(token), c.byref(start), None) != 0:
            raise BackendUnavailable("GDI+ failed to start.")
        self.lock = threading.Lock()

    def list_monitors(self):
        c, wt = self.c, self.wt

        class MonitorInfo(c.Structure):
            _fields_ = [("size", c.c_uint32), ("monitor", wt.RECT), ("work", wt.RECT), ("flags", c.c_uint32),
                        ("device", c.c_wchar * 32)]

        found = []
        callback_type = c.WINFUNCTYPE(c.c_int, c.c_void_p, c.c_void_p, c.POINTER(wt.RECT), c.c_void_p)

        def callback(handle, _dc, _rect, _data):
            info = MonitorInfo()
            info.size = c.sizeof(MonitorInfo)
            if self.user32.GetMonitorInfoW(c.c_void_p(handle), c.byref(info)):
                r = info.monitor
                found.append((info.flags & 1 != 0, r.left, r.top, r.right - r.left, r.bottom - r.top))
            return 1

        cb = callback_type(callback)
        self.user32.EnumDisplayMonitors(None, None, cb, None)
        found.sort(key=lambda m: (not m[0], m[1], m[2]))  # primary first
        monitors = []
        for i, (primary, x, y, w, h) in enumerate(found[:MAX_MONITORS]):
            label = f"Monitor {i + 1}" + (" (main)" if primary else "")
            monitors.append(Monitor(i, label, x, y, w, h, primary))
        return monitors

    def capture_jpeg(self, monitor, max_width, quality):
        c = self.c
        width = min(max_width, monitor.width)
        height = max(1, round(monitor.height * width / monitor.width))
        with self.lock:
            screen = self.user32.GetDC(None)
            memdc = self.gdi32.CreateCompatibleDC(screen)
            bitmap = self.gdi32.CreateCompatibleBitmap(screen, width, height)
            old = self.gdi32.SelectObject(memdc, bitmap)
            image = c.c_void_p()
            try:
                self.gdi32.SetStretchBltMode(memdc, 4)  # HALFTONE: smooth downscaling
                ok = self.gdi32.StretchBlt(memdc, 0, 0, width, height, screen, monitor.x, monitor.y, monitor.width,
                                           monitor.height, 0x00CC0020 | 0x40000000)  # SRCCOPY | CAPTUREBLT
                if not ok:
                    raise PcError("capture_failed", 503)
                self.gdi32.SelectObject(memdc, old)
                old = None
                if self.gdiplus.GdipCreateBitmapFromHBITMAP(bitmap, None, c.byref(image)) != 0:
                    raise PcError("capture_failed", 503)
                return self._encode(image, quality), width, height
            finally:
                if image:
                    self.gdiplus.GdipDisposeImage(image)
                if old:
                    self.gdi32.SelectObject(memdc, old)
                self.gdi32.DeleteObject(bitmap)
                self.gdi32.DeleteDC(memdc)
                self.user32.ReleaseDC(None, screen)

    def _encode(self, image, quality):
        c = self.c
        value = c.c_ulong(int(quality))
        params = self._Params()
        params.count = 1
        params.param[0].guid = self._quality
        params.param[0].count = 1
        params.param[0].type = 4  # EncoderParameterValueTypeLong
        params.param[0].value = c.cast(c.byref(value), c.c_void_p).value
        stream = c.c_void_p()
        if self.ole32.CreateStreamOnHGlobal(None, 1, c.byref(stream)) != 0:
            raise PcError("capture_failed", 503)
        try:
            if self.gdiplus.GdipSaveImageToStream(image, stream, c.byref(self._jpeg), c.byref(params)) != 0:
                raise PcError("capture_failed", 503)
            hglobal = c.c_void_p()
            self.ole32.GetHGlobalFromStream(stream, c.byref(hglobal))
            size = self.kernel32.GlobalSize(hglobal)
            pointer = self.kernel32.GlobalLock(hglobal)
            try:
                return c.string_at(pointer, size)
            finally:
                self.kernel32.GlobalUnlock(hglobal)
        finally:
            vtable = c.cast(c.cast(stream, c.POINTER(c.c_void_p))[0], c.POINTER(c.c_void_p))
            c.WINFUNCTYPE(c.c_ulong, c.c_void_p)(vtable[2])(stream)  # IStream::Release (frees the buffer)


def default_backend():
    return GdiBackend()


# -- Audit log --------------------------------------------------------------------------------------------------


class AuditLog:
    """Append-only text log: events only, never tokens or screen content."""

    def __init__(self, path=None, clock=time.time):
        self.path = Path(path) if path else None
        self.clock = clock
        self.entries = deque(maxlen=200)
        self.lock = threading.Lock()

    def write(self, event, detail=""):
        detail = "".join(ch for ch in str(detail) if ch.isprintable())[:120]
        line = f"{time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(self.clock()))} {event} {detail}".rstrip()
        with self.lock:
            self.entries.append(line)
            if self.path:
                try:
                    with self.path.open("a", encoding="utf-8") as f:
                        f.write(line + "\n")
                except OSError:
                    pass


# -- Flags shared with the CLI (the CLI runs in another process; the hub re-reads the file) -----------------------


def read_flags(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False, 0.0
    return bool(data.get("pc_view_enabled")), float(data.get("pc_lock_epoch") or 0)


def write_flags(path, enabled=None, lock_now=False, clock=time.time):
    """Read-modify-write of the two keys the CLI owns, keeping everything else in the file."""
    path = Path(path)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    if enabled is not None:
        data["pc_view_enabled"] = bool(enabled)
    if lock_now:
        data["pc_lock_epoch"] = clock()
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(path)


class FileFlags:
    """Reads the flags from the config file, cached by modification time."""

    def __init__(self, path):
        self.path = Path(path)
        self._stamp = None
        self._value = (False, 0.0)

    def get(self):
        try:
            stamp = self.path.stat().st_mtime_ns
        except OSError:
            return False, 0.0
        if stamp != self._stamp:
            self._value, self._stamp = read_flags(self.path), stamp
        return self._value


# -- The service ------------------------------------------------------------------------------------------------


def _digest(token):
    return hashlib.sha256((token or "").encode("utf-8")).digest()


def clamp(value, low, high, default):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


class PcView:
    """Owner-only, off by default, unlock per session. All checks happen here so they are unit-testable."""

    def __init__(self, flags, backend, audit, clock=time.time, sleep=time.sleep, ttl=UNLOCK_TTL):
        self.flags, self.backend, self.audit, self.clock, self.sleep, self.ttl = flags, backend, audit, clock, sleep, ttl
        self.lock = threading.RLock()
        self.records = {}          # digest -> {"issued", "expires", "hard", "revoked", "last_frame"}
        self.failures = deque()
        self.unlocks = deque()
        self.locked_until = 0.0
        self.viewing = False
        self.last_frame_hash = {}

    # state helpers
    def enabled(self):
        return self.flags.get()[0]

    def _epoch(self):
        return self.flags.get()[1]

    def _lockout_left(self):
        return max(0, int(self.locked_until - self.clock() + 0.999))

    def _live(self, record):
        now = self.clock()
        return (not record["revoked"] and record["issued"] > self._epoch() and record["expires"] > now
                and record["hard"] > now)

    def _find(self, token):
        digest, found = _digest(token), None
        for known, record in self.records.items():
            if hmac.compare_digest(known, digest):
                found = record
        return found

    def _purge(self):
        now = self.clock()
        for key in [k for k, r in self.records.items() if r["hard"] < now - 7200]:
            del self.records[key]

    def viewers(self):
        now = self.clock()
        return sum(1 for r in self.records.values() if self._live(r) and now - r["last_frame"] < VIEWER_IDLE)

    def _sweep_viewers(self):
        active = self.viewers() > 0
        if self.viewing and not active:
            self.audit.write("viewer_stop")
        self.viewing = active

    # failures and lockout
    def note_failure(self, why):
        now = self.clock()
        with self.lock:
            self.failures.append(now)
            while self.failures and now - self.failures[0] > FAIL_WINDOW:
                self.failures.popleft()
            self.audit.write("auth_failed", why)
            if len(self.failures) >= FAIL_LIMIT and now >= self.locked_until:
                self.locked_until = now + LOCKOUT_SECONDS
                self.failures.clear()
                self.audit.write("lockout", f"{LOCKOUT_SECONDS}s")
                self._revoke_all("lockout")

    def check_open(self):
        """Raises when PC view is disabled on the PC or locked out. Call before any other check."""
        if not self.enabled():
            raise PcError("disabled", 403)
        left = self._lockout_left()
        if left:
            raise PcError("locked_out", 429, {"retry_after": left})

    # public operations
    def status(self):
        with self.lock:
            self._sweep_viewers()
            if not self.enabled():
                return {"state": "disabled", "enabled": False}
            left = self._lockout_left()
            if left:
                return {"state": "locked_out", "enabled": True, "retry_after": left}
            now = self.clock()
            live = [r for r in self.records.values() if self._live(r)]
            if live:
                best = max(live, key=lambda r: r["expires"])
                return {"state": "unlocked", "enabled": True, "expires_in": int(best["expires"] - now),
                        "viewers": self.viewers(), "backend": self.backend.name}
            return {"state": "locked", "enabled": True, "backend": self.backend.name}

    def unlock(self):
        """Phone authentication already passed; the phone checked its biometric / device credential."""
        with self.lock:
            self.check_open()
            now = self.clock()
            while self.unlocks and now - self.unlocks[0] > 60:
                self.unlocks.popleft()
            if len(self.unlocks) >= UNLOCKS_PER_MINUTE:
                self.note_failure("unlock rate")
                raise PcError("rate_limited", 429, {"retry_after": 30})
            self.unlocks.append(now)
            self._purge()
            live = sorted((r for r in self.records.values() if self._live(r)), key=lambda r: r["issued"])
            for old in live[: max(0, len(live) - MAX_ACTIVE_TOKENS + 1)]:
                old["revoked"] = True
            token = secrets.token_urlsafe(32)
            self.records[_digest(token)] = {"issued": now, "expires": now + self.ttl, "hard": now + UNLOCK_MAX_SESSION,
                                            "revoked": False, "last_frame": 0.0}
            self.audit.write("unlock", f"ttl={self.ttl}s")
            return {"token": token, "expires_in": self.ttl, "max_session": UNLOCK_MAX_SESSION}

    def _authorise(self, token):
        """The record of a valid unlock token, or raises (counted as a failed attempt)."""
        self.check_open()
        record = self._find(token)
        if record is None:
            self.note_failure("unknown token")
            raise PcError("unlock_required", 401, {"reason": "invalid"})
        if record["revoked"] or record["issued"] <= self._epoch():
            raise PcError("unlock_required", 401, {"reason": "revoked"})
        if not self._live(record):
            raise PcError("unlock_required", 401, {"reason": "expired"})
        return record

    def extend(self, token):
        with self.lock:
            record = self._authorise(token)
            now = self.clock()
            record["expires"] = min(now + self.ttl, record["hard"])
            self.audit.write("extend")
            return {"expires_in": int(record["expires"] - now)}

    def lock_now(self, reason="phone"):
        with self.lock:
            self._revoke_all(reason)
            self._sweep_viewers()

    def _revoke_all(self, reason):
        count = 0
        for record in self.records.values():
            if not record["revoked"]:
                record["revoked"] = True
                count += 1
        self.audit.write("lock", f"{reason} revoked={count}")
        self.last_frame_hash.clear()

    def monitors(self, token):
        with self.lock:
            self._authorise(token)
        try:
            return [m.public() for m in self.backend.list_monitors()]
        except PcError:
            raise
        except Exception:
            raise PcError("capture_failed", 503)

    def frame(self, token, monitor, width, quality, if_none_match=None):
        """Returns (jpeg bytes | None for 'unchanged', info dict). Capture happens only here, only for a valid token."""
        with self.lock:
            record = self._authorise(token)
            now = self.clock()
            wait = MIN_FRAME_INTERVAL - (now - record["last_frame"])
            if 0 < wait <= MIN_FRAME_INTERVAL:
                self.sleep(wait)
            record["last_frame"] = self.clock()
            if not self.viewing:
                self.viewing = True
                self.audit.write("viewer_start", f"monitor={monitor}")
        width = clamp(width, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH)
        quality = clamp(quality, MIN_QUALITY, MAX_QUALITY, DEFAULT_QUALITY)
        started = time.monotonic()
        try:
            monitors = self.backend.list_monitors()
            chosen = next((m for m in monitors if m.id == monitor), None)
            if chosen is None:
                raise PcError("no_such_monitor", 404)
            data = b""
            for _ in range(3):  # frame size limit: lower quality and size until it fits
                data, w, h = self.backend.capture_jpeg(chosen, width, quality)
                if len(data) <= MAX_FRAME_BYTES:
                    break
                quality, width = max(MIN_QUALITY, quality - 15), max(MIN_WIDTH, int(width * 0.75))
            else:
                raise PcError("frame_too_large", 413)
        except PcError:
            raise
        except Exception:
            raise PcError("capture_failed", 503)
        took = time.monotonic() - started
        etag = hashlib.sha256(data).hexdigest()[:16]
        info = {"width": w, "height": h, "etag": etag, "quality": quality,
                "delay_ms": int(max(MIN_FRAME_INTERVAL, took * 1.5) * 1000)}
        return (None if if_none_match == etag else data), info
