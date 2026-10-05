#!/usr/bin/env python3
"""Learned Ayahs web server.

Serves the web app, the bundled Qur'an data (shared with the Android app), a same-origin audio
cache, and a small JSON API for accounts and per-user data. Standard library only: Python 3.9+,
SQLite, nothing to install.

    python3 server.py                      # http://127.0.0.1:8080
    python3 server.py --host 0.0.0.0       # reachable from other devices on your network

The first account registered becomes the administrator. See README.md for the API and options.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import hmac
import json
import mimetypes
import os
import re
import secrets
import shutil
import sqlite3
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen

VERSION = "1.0.0"
HERE = Path(__file__).resolve().parent
REPO = HERE.parent
TOTAL_AYAHS = 6236

# Reciter folders on everyayah.com — must match static/js/core/reciters.js and the Android
# app's data/Reciter.kt. Only these can be fetched through the audio cache.
RECITER_FOLDERS = frozenset(
    {
        "MaherAlMuaiqly128kbps",
        "Abdul_Basit_Murattal_192kbps",
        "Husary_128kbps",
        "Abdurrahmaan_As-Sudais_192kbps",
        "Saood_ash-Shuraym_128kbps",
    }
)
EVERYAYAH = "https://everyayah.com/data"
WORD_CLIPS = "https://audio.qurancdn.com/wbw"

DATA_FILES = re.compile(r"^(quran_text|word_translations|word_timings_[a-z_]+)\.json$")
AYAH_AUDIO = re.compile(r"^(?P<folder>[A-Za-z0-9_\-]+)/(?P<file>\d{6}\.mp3)$")
INTRO_AUDIO = re.compile(r"^(audhubillah|bismillah)\.mp3$")
WORD_AUDIO = re.compile(r"^\d{3}_\d{3}_\d{3}\.mp3$")
USERNAME = re.compile(r"^[A-Za-z0-9_.\-]{3,32}$")

SESSION_COOKIE = "la_session"
CSRF_HEADER = "X-Requested-With"
CSRF_VALUE = "learned-ayahs"
MAX_BODY = 2 * 1024 * 1024
MAX_SETTINGS_BYTES = 32 * 1024


# --------------------------------------------------------------------------------------------
# Configuration


@dataclass
class Config:
    host: str = "127.0.0.1"
    port: int = 8080
    db_path: Path = HERE / "data" / "learned_ayahs.db"
    audio_cache: Path = HERE / "data" / "audio-cache"
    assets_dir: Path = REPO / "android-app-quran-player" / "app" / "src" / "main" / "assets"
    font_path: Path = REPO / "android-app-quran-player" / "app" / "src" / "main" / "res" / "font" / "scheherazade_new.ttf"
    static_dir: Path = HERE / "static"
    registration_default: bool = True
    session_days: int = 30
    pbkdf2_iterations: int = 200_000
    secure_cookies: bool = False
    fetch_audio: bool = True
    quiet: bool = False
    upstream: Dict[str, str] = field(default_factory=lambda: {"everyayah": EVERYAYAH, "wbw": WORD_CLIPS})
    export_static: Optional[Path] = None


# --------------------------------------------------------------------------------------------
# Passwords and tokens


def hash_password(password: str, iterations: int) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return "pbkdf2_sha256${}${}${}".format(
        iterations, base64.b64encode(salt).decode(), base64.b64encode(digest).decode()
    )


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, iterations, salt_b64, digest_b64 = stored.split("$")
        if scheme != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), base64.b64decode(salt_b64), int(iterations)
        )
        return hmac.compare_digest(digest, base64.b64decode(digest_b64))
    except (ValueError, TypeError):
        return False


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------------------------
# Storage

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name  TEXT NOT NULL DEFAULT '',
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('admin', 'user')),
    disabled      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    last_login_at TEXT
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    expires_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS user_data (
    user_id    INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    learned    TEXT NOT NULL DEFAULT '[]',
    bookmarks  TEXT NOT NULL DEFAULT '[]',
    settings   TEXT NOT NULL DEFAULT '{}',
    progress   TEXT NOT NULL DEFAULT '{}',
    revision   INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS app_settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


class Store:
    """SQLite access. One connection per thread; WAL so readers don't block the writer."""

    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.local = threading.local()
        self.write_lock = threading.Lock()
        with self.write_lock:
            self.conn.executescript(SCHEMA)
            self.conn.commit()

    @property
    def conn(self) -> sqlite3.Connection:
        conn = getattr(self.local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(str(self.path), timeout=30)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA foreign_keys = ON")
            conn.execute("PRAGMA journal_mode = WAL")
            self.local.conn = conn
        return conn

    def query(self, sql: str, params: tuple = ()) -> List[sqlite3.Row]:
        return self.conn.execute(sql, params).fetchall()

    def one(self, sql: str, params: tuple = ()) -> Optional[sqlite3.Row]:
        return self.conn.execute(sql, params).fetchone()

    def write(self, fn: Callable[[sqlite3.Connection], Any]) -> Any:
        with self.write_lock:
            conn = self.conn
            try:
                result = fn(conn)
                conn.commit()
                return result
            except Exception:
                conn.rollback()
                raise

    # -- app settings ---------------------------------------------------------------------

    def get_setting(self, key: str, default: str) -> str:
        row = self.one("SELECT value FROM app_settings WHERE key = ?", (key,))
        return row["value"] if row else default

    def set_setting(self, key: str, value: str) -> None:
        self.write(
            lambda c: c.execute(
                "INSERT INTO app_settings(key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
        )

    def user_count(self) -> int:
        return self.one("SELECT COUNT(*) AS n FROM users")["n"]

    def admin_count(self, exclude_id: Optional[int] = None) -> int:
        row = self.one(
            "SELECT COUNT(*) AS n FROM users WHERE role = 'admin' AND disabled = 0 AND id != ?",
            (exclude_id if exclude_id is not None else -1,),
        )
        return row["n"]


# --------------------------------------------------------------------------------------------
# Validation helpers


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, extra: Optional[dict] = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.extra = extra or {}


def require_str(body: dict, key: str, *, min_len: int = 0, max_len: int = 256) -> str:
    value = body.get(key)
    if not isinstance(value, str):
        raise ApiError(400, "invalid_" + key, f"'{key}' is required.")
    if not (min_len <= len(value) <= max_len):
        raise ApiError(400, "invalid_" + key, f"'{key}' must be {min_len}-{max_len} characters.")
    return value


def validate_username(name: str) -> str:
    if not USERNAME.match(name):
        raise ApiError(
            400,
            "invalid_username",
            "Usernames are 3-32 characters: letters, numbers, dot, dash or underscore.",
        )
    return name


def validate_password(password: str) -> str:
    if len(password) < 8:
        raise ApiError(400, "weak_password", "Passwords need at least 8 characters.")
    if len(password) > 256:
        raise ApiError(400, "invalid_password", "Passwords can be at most 256 characters.")
    return password


def validate_ids(value: Any, key: str) -> List[int]:
    if not isinstance(value, list) or len(value) > TOTAL_AYAHS * 2:
        raise ApiError(400, "invalid_" + key, f"'{key}' must be a list of ayah ids.")
    ids = set()
    for item in value:
        if not isinstance(item, int) or isinstance(item, bool) or not 1 <= item <= TOTAL_AYAHS:
            raise ApiError(400, "invalid_" + key, f"'{key}' contains an invalid ayah id: {item!r}")
        ids.add(item)
    return sorted(ids)


def validate_object(value: Any, key: str) -> dict:
    if not isinstance(value, dict):
        raise ApiError(400, "invalid_" + key, f"'{key}' must be an object.")
    if len(json.dumps(value)) > MAX_SETTINGS_BYTES:
        raise ApiError(413, "too_large", f"'{key}' is too large.")
    return value


def public_user(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "username": row["username"],
        "displayName": row["display_name"] or row["username"],
        "role": row["role"],
        "disabled": bool(row["disabled"]),
        "createdAt": row["created_at"],
        "lastLoginAt": row["last_login_at"],
    }


# --------------------------------------------------------------------------------------------
# Login throttling


class LoginThrottle:
    """At most `limit` failed logins per key in `window` seconds (keyed by IP and by username)."""

    def __init__(self, limit: int = 10, window: float = 15 * 60):
        self.limit = limit
        self.window = window
        self.failures: Dict[str, List[float]] = {}
        self.lock = threading.Lock()

    def _recent(self, key: str, now: float) -> List[float]:
        times = [t for t in self.failures.get(key, []) if now - t < self.window]
        self.failures[key] = times
        return times

    def blocked(self, *keys: str) -> bool:
        now = time.time()
        with self.lock:
            return any(len(self._recent(k, now)) >= self.limit for k in keys)

    def fail(self, *keys: str) -> None:
        now = time.time()
        with self.lock:
            for k in keys:
                self._recent(k, now).append(now)

    def clear(self, *keys: str) -> None:
        with self.lock:
            for k in keys:
                self.failures.pop(k, None)


# --------------------------------------------------------------------------------------------
# Static and data files


class FileCache:
    """Bundled data files with a precomputed gzip copy and an ETag."""

    def __init__(self):
        self.entries: Dict[Path, Tuple[float, str, bytes, bytes]] = {}
        self.lock = threading.Lock()

    def get(self, path: Path) -> Tuple[str, bytes, bytes]:
        mtime = path.stat().st_mtime
        with self.lock:
            entry = self.entries.get(path)
            if entry and entry[0] == mtime:
                return entry[1], entry[2], entry[3]
        raw = path.read_bytes()
        etag = '"' + hashlib.sha256(raw).hexdigest()[:32] + '"'
        gz = gzip.compress(raw, 6)
        with self.lock:
            self.entries[path] = (mtime, etag, raw, gz)
        return etag, raw, gz


def looks_like_audio(head: bytes) -> bool:
    """MP3 (ID3 tag or MPEG frame sync), WAV or Ogg — not an HTML error page served as 200."""
    return (
        head[:3] == b"ID3"
        or head[:4] in (b"RIFF", b"OggS")
        or (len(head) >= 2 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0)
    )


def sniff_audio_type(head: bytes) -> str:
    if head[:4] == b"RIFF":
        return "audio/wav"
    if head[:4] == b"OggS":
        return "audio/ogg"
    return "audio/mpeg"


class AudioCache:
    """Same-origin audio: fetched once from upstream, kept on disk, served with Range support.

    Files are written to a temporary name and renamed into place, so an interrupted download can
    never leave a truncated MP3 that would later be served as complete.
    """

    def __init__(self, config: Config):
        self.root = config.audio_cache
        self.fetch_enabled = config.fetch_audio
        self.upstream = config.upstream
        self.locks: Dict[Path, threading.Lock] = {}
        self.locks_lock = threading.Lock()

    def resolve(self, kind: str, rel: str) -> Tuple[Path, str]:
        """Validates a request path; returns (local file, upstream URL)."""
        if kind == "everyayah":
            m = AYAH_AUDIO.match(rel)
            if m and m.group("folder") in RECITER_FOLDERS:
                return self.root / "everyayah" / m.group("folder") / m.group("file"), f"{self.upstream['everyayah']}/{rel}"
            if INTRO_AUDIO.match(rel):
                return self.root / "everyayah" / rel, f"{self.upstream['everyayah']}/{rel}"
        elif kind == "wbw" and WORD_AUDIO.match(rel):
            return self.root / "wbw" / rel, f"{self.upstream['wbw']}/{rel}"
        raise ApiError(404, "not_found", "No such audio file.")

    def _lock_for(self, path: Path) -> threading.Lock:
        with self.locks_lock:
            return self.locks.setdefault(path, threading.Lock())

    def ensure(self, path: Path, url: str) -> Path:
        if path.exists() and path.stat().st_size > 0:
            return path
        if not self.fetch_enabled:
            raise ApiError(404, "not_cached", "This audio has not been downloaded to the server.")
        with self._lock_for(path):
            if path.exists() and path.stat().st_size > 0:
                return path
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_name(path.name + f".{secrets.token_hex(4)}.part")
            try:
                req = Request(url, headers={"User-Agent": f"LearnedAyahsWeb/{VERSION}"})
                with urlopen(req, timeout=60) as resp, open(tmp, "wb") as out:
                    expected = resp.headers.get("Content-Length")
                    written = 0
                    while True:
                        chunk = resp.read(64 * 1024)
                        if not chunk:
                            break
                        out.write(chunk)
                        written += len(chunk)
                if written == 0 or (expected and int(expected) != written):
                    raise OSError("incomplete download")
                with open(tmp, "rb") as fh:
                    if not looks_like_audio(fh.read(4)):
                        raise ApiError(502, "upstream_not_audio", "The audio source returned something that is not audio.")
                os.replace(tmp, path)
            except HTTPError as exc:
                raise ApiError(404 if exc.code == 404 else 502, "upstream_error", f"Upstream returned {exc.code}.")
            except (URLError, OSError, TimeoutError) as exc:
                raise ApiError(502, "upstream_unreachable", f"Could not fetch audio: {exc}")
            finally:
                if tmp.exists():
                    tmp.unlink()
        return path


# --------------------------------------------------------------------------------------------
# The application


class App:
    def __init__(self, config: Config):
        self.config = config
        self.store = Store(config.db_path)
        # Per-username limits stop guessing at one account; the looser per-IP limit stops spraying
        # many accounts without locking out a whole household behind one router.
        self.user_throttle = LoginThrottle(limit=10)
        self.ip_throttle = LoginThrottle(limit=30)
        self.files = FileCache()
        self.audio = AudioCache(config)
        if self.store.one("SELECT 1 FROM app_settings WHERE key = 'registration_open'") is None:
            self.store.set_setting("registration_open", "1" if config.registration_default else "0")

    # -- sessions -------------------------------------------------------------------------

    def create_session(self, user_id: int) -> str:
        token = secrets.token_urlsafe(32)
        expires = time.time() + self.config.session_days * 86400

        def tx(c):
            c.execute("DELETE FROM sessions WHERE expires_at < ?", (time.time(),))
            c.execute(
                "INSERT INTO sessions(token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                (token_hash(token), user_id, now_iso(), expires),
            )
            c.execute("UPDATE users SET last_login_at = ? WHERE id = ?", (now_iso(), user_id))

        self.store.write(tx)
        return token

    def user_for_token(self, token: Optional[str]) -> Optional[sqlite3.Row]:
        if not token:
            return None
        row = self.store.one(
            "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id "
            "WHERE s.token_hash = ? AND s.expires_at > ? AND u.disabled = 0",
            (token_hash(token), time.time()),
        )
        return row

    def registration_open(self) -> bool:
        return self.store.user_count() == 0 or self.store.get_setting("registration_open", "1") == "1"

    # -- user data ------------------------------------------------------------------------

    def load_data(self, user_id: int) -> dict:
        row = self.store.one("SELECT * FROM user_data WHERE user_id = ?", (user_id,))
        if row is None:
            return {"learned": [], "bookmarks": [], "settings": {}, "progress": {}, "revision": 0, "updatedAt": None}
        return {
            "learned": json.loads(row["learned"]),
            "bookmarks": json.loads(row["bookmarks"]),
            "settings": json.loads(row["settings"]),
            "progress": json.loads(row["progress"]),
            "revision": row["revision"],
            "updatedAt": row["updated_at"],
        }

    def save_data(self, user_id: int, patch: dict) -> dict:
        updates: Dict[str, str] = {}
        if "learned" in patch:
            updates["learned"] = json.dumps(validate_ids(patch["learned"], "learned"))
        if "bookmarks" in patch:
            updates["bookmarks"] = json.dumps(validate_ids(patch["bookmarks"], "bookmarks"))
        if "settings" in patch:
            updates["settings"] = json.dumps(validate_object(patch["settings"], "settings"))
        if "progress" in patch:
            updates["progress"] = json.dumps(validate_object(patch["progress"], "progress"))
        if not updates:
            raise ApiError(400, "empty_update", "Nothing to save.")

        def tx(c):
            c.execute(
                "INSERT INTO user_data(user_id, updated_at) VALUES (?, ?) ON CONFLICT(user_id) DO NOTHING",
                (user_id, now_iso()),
            )
            assignments = ", ".join(f"{k} = ?" for k in updates)
            c.execute(
                f"UPDATE user_data SET {assignments}, revision = revision + 1, updated_at = ? WHERE user_id = ?",
                (*updates.values(), now_iso(), user_id),
            )

        self.store.write(tx)
        return self.load_data(user_id)


# --------------------------------------------------------------------------------------------
# HTTP


def make_handler(app: App):
    config = app.config

    class Handler(BaseHTTPRequestHandler):
        server_version = f"LearnedAyahs/{VERSION}"
        protocol_version = "HTTP/1.1"

        # -- plumbing ---------------------------------------------------------------------

        def log_message(self, fmt, *args):  # noqa: N802 - stdlib signature
            if not config.quiet:
                sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

        def client_ip(self) -> str:
            return self.client_address[0]

        def security_headers(self):
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "same-origin")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Permissions-Policy", "microphone=(self), camera=(), geolocation=()")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                "img-src 'self' data:; font-src 'self'; "
                "media-src 'self' https://everyayah.com https://audio.qurancdn.com blob:; "
                "connect-src 'self' https://everyayah.com https://audio.qurancdn.com; "
                "worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'",
            )

        def send_bytes(self, status: int, body: bytes, content_type: str, headers: Optional[dict] = None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.security_headers()
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def send_json(self, status: int, payload: Any, headers: Optional[dict] = None):
            body = json.dumps(payload).encode("utf-8")
            merged = {"Cache-Control": "no-store"}
            merged.update(headers or {})
            self.send_bytes(status, body, "application/json; charset=utf-8", merged)

        def send_error_json(self, err: ApiError):
            self.send_json(err.status, {"error": err.code, "message": err.message, **err.extra})

        def cookie(self, name: str) -> Optional[str]:
            raw = self.headers.get("Cookie", "")
            for part in raw.split(";"):
                k, _, v = part.strip().partition("=")
                if k == name:
                    return v
            return None

        def session_cookie(self, token: str, max_age: int) -> str:
            secure = config.secure_cookies or self.headers.get("X-Forwarded-Proto") == "https"
            return (
                f"{SESSION_COOKIE}={token}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age}"
                + ("; Secure" if secure else "")
            )

        def read_json(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                raise ApiError(413, "too_large", "Request body too large.")
            raw = self.rfile.read(length) if length else b"{}"
            try:
                body = json.loads(raw.decode("utf-8") or "{}")
            except (UnicodeDecodeError, json.JSONDecodeError):
                raise ApiError(400, "invalid_json", "Request body must be JSON.")
            if not isinstance(body, dict):
                raise ApiError(400, "invalid_json", "Request body must be a JSON object.")
            return body

        def current_user(self) -> Optional[sqlite3.Row]:
            return app.user_for_token(self.cookie(SESSION_COOKIE))

        def require_user(self) -> sqlite3.Row:
            user = self.current_user()
            if user is None:
                raise ApiError(401, "not_authenticated", "Please sign in.")
            return user

        def require_admin(self) -> sqlite3.Row:
            user = self.require_user()
            if user["role"] != "admin":
                raise ApiError(403, "forbidden", "Administrators only.")
            return user

        # -- dispatch ---------------------------------------------------------------------

        def do_GET(self):  # noqa: N802
            self.dispatch("GET")

        def do_HEAD(self):  # noqa: N802
            self.dispatch("GET")

        def do_POST(self):  # noqa: N802
            self.dispatch("POST")

        def do_PUT(self):  # noqa: N802
            self.dispatch("PUT")

        def do_PATCH(self):  # noqa: N802
            self.dispatch("PATCH")

        def do_DELETE(self):  # noqa: N802
            self.dispatch("DELETE")

        def dispatch(self, method: str):
            path = unquote(urlsplit(self.path).path)
            try:
                if path.startswith("/api/"):
                    if method != "GET" and self.headers.get(CSRF_HEADER) != CSRF_VALUE:
                        raise ApiError(403, "csrf", "Missing request header.")
                    self.route_api(method, path)
                elif method != "GET":
                    raise ApiError(405, "method_not_allowed", "Method not allowed.")
                elif path.startswith("/data/"):
                    self.serve_data(path[len("/data/"):])
                elif path.startswith("/audio/"):
                    kind, _, rel = path[len("/audio/"):].partition("/")
                    self.serve_audio(kind, rel)
                elif path == "/fonts/scheherazade_new.ttf":
                    self.serve_file(config.font_path, "font/ttf", "public, max-age=604800")
                else:
                    self.serve_static(path)
            except ApiError as err:
                self.send_error_json(err)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as exc:  # pragma: no cover - last resort
                sys.stderr.write(f"error handling {method} {path}: {exc!r}\n")
                try:
                    self.send_json(500, {"error": "server_error", "message": "Something went wrong."})
                except OSError:
                    pass

        # -- static -----------------------------------------------------------------------

        def serve_static(self, path: str):
            rel = path.lstrip("/") or "index.html"
            target = (config.static_dir / rel).resolve()
            static_root = config.static_dir.resolve()
            if static_root not in target.parents and target != static_root:
                raise ApiError(404, "not_found", "Not found.")
            if target.is_dir():
                target = target / "index.html"
            if not target.is_file():
                # Client-side routes fall back to the app shell.
                if "." in Path(rel).name:
                    raise ApiError(404, "not_found", "Not found.")
                target = config.static_dir / "index.html"
            ctype = {
                ".js": "text/javascript; charset=utf-8",
                ".css": "text/css; charset=utf-8",
                ".html": "text/html; charset=utf-8",
                ".webmanifest": "application/manifest+json",
                ".svg": "image/svg+xml",
                ".json": "application/json; charset=utf-8",
            }.get(target.suffix, mimetypes.guess_type(target.name)[0] or "application/octet-stream")
            # The service worker keeps the app usable offline; always revalidate here.
            self.serve_file(target, ctype, "no-cache")

        def serve_file(self, path: Path, ctype: str, cache_control: str):
            if not path.is_file():
                raise ApiError(404, "not_found", "Not found.")
            etag, raw, gz = app.files.get(path)
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", cache_control)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            headers = {"ETag": etag, "Cache-Control": cache_control, "Vary": "Accept-Encoding"}
            compressible = ctype.startswith(("text/", "application/json", "application/manifest", "image/svg"))
            if compressible and "gzip" in self.headers.get("Accept-Encoding", ""):
                headers["Content-Encoding"] = "gzip"
                self.send_bytes(200, gz, ctype, headers)
            else:
                self.send_bytes(200, raw, ctype, headers)

        def serve_data(self, name: str):
            if not DATA_FILES.match(name):
                raise ApiError(404, "not_found", "Not found.")
            self.serve_file(config.assets_dir / name, "application/json; charset=utf-8", "public, max-age=86400")

        # -- audio ------------------------------------------------------------------------

        def serve_audio(self, kind: str, rel: str):
            local, url = app.audio.resolve(kind, rel)
            path = app.audio.ensure(local, url)
            size = path.stat().st_size
            with open(path, "rb") as fh:
                head = fh.read(4)
            ctype = sniff_audio_type(head)
            headers = {"Accept-Ranges": "bytes", "Cache-Control": "public, max-age=31536000, immutable"}
            start, end = 0, size - 1
            status = 200
            rng = self.headers.get("Range")
            if rng:
                m = re.match(r"bytes=(\d*)-(\d*)$", rng.strip())
                if not m or (not m.group(1) and not m.group(2)):
                    self.send_bytes(416, b"", ctype, {"Content-Range": f"bytes */{size}"})
                    return
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                else:
                    start = max(size - int(m.group(2)), 0)
                end = min(end, size - 1)
                if start > end:
                    self.send_bytes(416, b"", ctype, {"Content-Range": f"bytes */{size}"})
                    return
                status = 206
                headers["Content-Range"] = f"bytes {start}-{end}/{size}"
            with open(path, "rb") as fh:
                fh.seek(start)
                body = fh.read(end - start + 1)
            self.send_bytes(status, body, ctype, headers)

        # -- API --------------------------------------------------------------------------

        def route_api(self, method: str, path: str):
            routes = {
                ("GET", "/api/health"): self.api_health,
                ("GET", "/api/auth/me"): self.api_me,
                ("POST", "/api/auth/register"): self.api_register,
                ("POST", "/api/auth/login"): self.api_login,
                ("POST", "/api/auth/logout"): self.api_logout,
                ("PATCH", "/api/account"): self.api_update_account,
                ("POST", "/api/account/password"): self.api_change_password,
                ("DELETE", "/api/account"): self.api_delete_account,
                ("GET", "/api/data"): self.api_get_data,
                ("PUT", "/api/data"): self.api_put_data,
                ("GET", "/api/admin/users"): self.api_admin_users,
                ("POST", "/api/admin/users"): self.api_admin_create_user,
                ("GET", "/api/admin/settings"): self.api_admin_settings,
                ("PATCH", "/api/admin/settings"): self.api_admin_update_settings,
            }
            handler = routes.get((method, path))
            if handler:
                handler()
                return
            m = re.match(r"^/api/admin/users/(\d+)$", path)
            if m and method in ("PATCH", "DELETE"):
                (self.api_admin_update_user if method == "PATCH" else self.api_admin_delete_user)(int(m.group(1)))
                return
            raise ApiError(404, "not_found", "No such endpoint.")

        def api_health(self):
            self.send_json(
                200,
                {
                    "ok": True,
                    "version": VERSION,
                    "registrationOpen": app.registration_open(),
                    "hasUsers": app.store.user_count() > 0,
                    "audioProxy": True,
                },
            )

        def api_me(self):
            user = self.current_user()
            self.send_json(
                200,
                {"user": public_user(user) if user else None, "registrationOpen": app.registration_open()},
            )

        def api_register(self):
            body = self.read_json()
            username = validate_username(require_str(body, "username", min_len=3, max_len=32))
            password = validate_password(require_str(body, "password", min_len=1, max_len=256))
            display = body.get("displayName") or ""
            if not isinstance(display, str) or len(display) > 64:
                raise ApiError(400, "invalid_displayName", "Display names can be at most 64 characters.")
            if not app.registration_open():
                raise ApiError(403, "registration_closed", "Registration is closed. Ask an administrator for an account.")
            pw_hash = hash_password(password, config.pbkdf2_iterations)

            def tx(c):
                # The first account administers the server; decided inside the write lock.
                role = "admin" if c.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0 else "user"
                try:
                    cur = c.execute(
                        "INSERT INTO users(username, display_name, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?)",
                        (username, display.strip(), pw_hash, role, now_iso()),
                    )
                except sqlite3.IntegrityError:
                    raise ApiError(409, "username_taken", "That username is already taken.")
                return cur.lastrowid

            user_id = app.store.write(tx)
            token = app.create_session(user_id)
            user = app.store.one("SELECT * FROM users WHERE id = ?", (user_id,))
            self.send_json(201, {"user": public_user(user)}, {"Set-Cookie": self.session_cookie(token, config.session_days * 86400)})

        def api_login(self):
            body = self.read_json()
            username = require_str(body, "username", min_len=1, max_len=64)
            password = require_str(body, "password", min_len=1, max_len=256)
            ip_key, user_key = self.client_ip(), username.lower()
            if app.ip_throttle.blocked(ip_key) or app.user_throttle.blocked(user_key):
                raise ApiError(429, "too_many_attempts", "Too many failed sign-ins. Try again in 15 minutes.")
            user = app.store.one("SELECT * FROM users WHERE username = ?", (username,))
            if user is None or not verify_password(password, user["password_hash"]):
                app.ip_throttle.fail(ip_key)
                app.user_throttle.fail(user_key)
                raise ApiError(401, "invalid_credentials", "Incorrect username or password.")
            if user["disabled"]:
                raise ApiError(403, "account_disabled", "This account has been disabled by an administrator.")
            app.user_throttle.clear(user_key)
            token = app.create_session(user["id"])
            user = app.store.one("SELECT * FROM users WHERE id = ?", (user["id"],))
            self.send_json(200, {"user": public_user(user)}, {"Set-Cookie": self.session_cookie(token, config.session_days * 86400)})

        def api_logout(self):
            token = self.cookie(SESSION_COOKIE)
            if token:
                app.store.write(lambda c: c.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash(token),)))
            self.send_json(200, {"ok": True}, {"Set-Cookie": self.session_cookie("", 0)})

        def api_update_account(self):
            user = self.require_user()
            body = self.read_json()
            display = require_str(body, "displayName", min_len=0, max_len=64).strip()
            app.store.write(lambda c: c.execute("UPDATE users SET display_name = ? WHERE id = ?", (display, user["id"])))
            self.send_json(200, {"user": public_user(app.store.one("SELECT * FROM users WHERE id = ?", (user["id"],)))})

        def api_change_password(self):
            user = self.require_user()
            body = self.read_json()
            current = require_str(body, "currentPassword", min_len=1, max_len=256)
            new = validate_password(require_str(body, "newPassword", min_len=1, max_len=256))
            if not verify_password(current, user["password_hash"]):
                raise ApiError(403, "invalid_credentials", "Your current password is incorrect.")
            pw_hash = hash_password(new, config.pbkdf2_iterations)
            keep = token_hash(self.cookie(SESSION_COOKIE) or "")

            def tx(c):
                c.execute("UPDATE users SET password_hash = ? WHERE id = ?", (pw_hash, user["id"]))
                # Sign out every other device.
                c.execute("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?", (user["id"], keep))

            app.store.write(tx)
            self.send_json(200, {"ok": True})

        def api_delete_account(self):
            user = self.require_user()
            body = self.read_json()
            password = require_str(body, "password", min_len=1, max_len=256)
            if not verify_password(password, user["password_hash"]):
                raise ApiError(403, "invalid_credentials", "Your password is incorrect.")
            if user["role"] == "admin" and app.store.admin_count(exclude_id=user["id"]) == 0 and app.store.user_count() > 1:
                raise ApiError(409, "last_admin", "Make another user an administrator before deleting this account.")
            app.store.write(lambda c: c.execute("DELETE FROM users WHERE id = ?", (user["id"],)))
            self.send_json(200, {"ok": True}, {"Set-Cookie": self.session_cookie("", 0)})

        def api_get_data(self):
            user = self.require_user()
            self.send_json(200, app.load_data(user["id"]))

        def api_put_data(self):
            user = self.require_user()
            body = self.read_json()
            self.send_json(200, app.save_data(user["id"], body))

        # -- admin ------------------------------------------------------------------------

        def api_admin_users(self):
            self.require_admin()
            rows = app.store.query(
                "SELECT u.*, COALESCE(json_array_length(d.learned), 0) AS learned_count, d.updated_at AS data_updated_at "
                "FROM users u LEFT JOIN user_data d ON d.user_id = u.id ORDER BY u.id"
            )
            users = []
            for r in rows:
                u = public_user(r)
                u["learnedCount"] = r["learned_count"]
                u["dataUpdatedAt"] = r["data_updated_at"]
                users.append(u)
            self.send_json(200, {"users": users})

        def api_admin_create_user(self):
            self.require_admin()
            body = self.read_json()
            username = validate_username(require_str(body, "username", min_len=3, max_len=32))
            password = validate_password(require_str(body, "password", min_len=1, max_len=256))
            role = body.get("role", "user")
            if role not in ("admin", "user"):
                raise ApiError(400, "invalid_role", "Role must be 'admin' or 'user'.")
            display = body.get("displayName") or ""
            if not isinstance(display, str) or len(display) > 64:
                raise ApiError(400, "invalid_displayName", "Display names can be at most 64 characters.")
            pw_hash = hash_password(password, config.pbkdf2_iterations)

            def tx(c):
                try:
                    return c.execute(
                        "INSERT INTO users(username, display_name, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?)",
                        (username, display.strip(), pw_hash, role, now_iso()),
                    ).lastrowid
                except sqlite3.IntegrityError:
                    raise ApiError(409, "username_taken", "That username is already taken.")

            user_id = app.store.write(tx)
            self.send_json(201, {"user": public_user(app.store.one("SELECT * FROM users WHERE id = ?", (user_id,)))})

        def api_admin_update_user(self, user_id: int):
            admin = self.require_admin()
            body = self.read_json()
            target = app.store.one("SELECT * FROM users WHERE id = ?", (user_id,))
            if target is None:
                raise ApiError(404, "not_found", "No such user.")
            sets: Dict[str, Any] = {}
            if "role" in body:
                if body["role"] not in ("admin", "user"):
                    raise ApiError(400, "invalid_role", "Role must be 'admin' or 'user'.")
                if body["role"] == "user" and target["role"] == "admin" and app.store.admin_count(exclude_id=user_id) == 0:
                    raise ApiError(409, "last_admin", "There must always be at least one administrator.")
                sets["role"] = body["role"]
            if "disabled" in body:
                if not isinstance(body["disabled"], bool):
                    raise ApiError(400, "invalid_disabled", "'disabled' must be true or false.")
                if body["disabled"] and user_id == admin["id"]:
                    raise ApiError(409, "cannot_disable_self", "You cannot disable your own account.")
                if body["disabled"] and target["role"] == "admin" and app.store.admin_count(exclude_id=user_id) == 0:
                    raise ApiError(409, "last_admin", "There must always be at least one administrator.")
                sets["disabled"] = 1 if body["disabled"] else 0
            if "displayName" in body:
                sets["display_name"] = require_str(body, "displayName", min_len=0, max_len=64).strip()
            new_password = None
            if "password" in body:
                new_password = hash_password(
                    validate_password(require_str(body, "password", min_len=1, max_len=256)), config.pbkdf2_iterations
                )
                sets["password_hash"] = new_password
            if not sets:
                raise ApiError(400, "empty_update", "Nothing to change.")

            def tx(c):
                c.execute(
                    f"UPDATE users SET {', '.join(f'{k} = ?' for k in sets)} WHERE id = ?",
                    (*sets.values(), user_id),
                )
                if new_password or sets.get("disabled") == 1:
                    c.execute("DELETE FROM sessions WHERE user_id = ?", (user_id,))

            app.store.write(tx)
            self.send_json(200, {"user": public_user(app.store.one("SELECT * FROM users WHERE id = ?", (user_id,)))})

        def api_admin_delete_user(self, user_id: int):
            admin = self.require_admin()
            if user_id == admin["id"]:
                raise ApiError(409, "cannot_delete_self", "Delete your own account from Account settings instead.")
            target = app.store.one("SELECT * FROM users WHERE id = ?", (user_id,))
            if target is None:
                raise ApiError(404, "not_found", "No such user.")
            app.store.write(lambda c: c.execute("DELETE FROM users WHERE id = ?", (user_id,)))
            self.send_json(200, {"ok": True})

        def api_admin_settings(self):
            self.require_admin()
            self.send_json(200, {"registrationOpen": app.store.get_setting("registration_open", "1") == "1"})

        def api_admin_update_settings(self):
            self.require_admin()
            body = self.read_json()
            if not isinstance(body.get("registrationOpen"), bool):
                raise ApiError(400, "invalid_registrationOpen", "'registrationOpen' must be true or false.")
            app.store.set_setting("registration_open", "1" if body["registrationOpen"] else "0")
            self.send_json(200, {"registrationOpen": body["registrationOpen"]})

    return Handler


def make_server(config: Config) -> ThreadingHTTPServer:
    app = App(config)
    server = ThreadingHTTPServer((config.host, config.port), make_handler(app))
    server.daemon_threads = True
    server.app = app  # type: ignore[attr-defined]
    return server


def export_static(config: Config, out: Path) -> int:
    """Writes a self-contained copy of the app for a static web host (GitHub Pages, any web
    server): the app shell, the Qur'an data and the Arabic font. Without server.py there are no
    accounts — each browser keeps its own list — and audio streams straight from its sources."""
    if out.exists() and any(out.iterdir()):
        print(f"{out} is not empty; choose a new folder.", file=sys.stderr)
        return 1
    shutil.copytree(config.static_dir, out, dirs_exist_ok=True)
    (out / "data").mkdir(exist_ok=True)
    copied = 0
    for f in sorted(config.assets_dir.iterdir()):
        if DATA_FILES.match(f.name):
            shutil.copy2(f, out / "data" / f.name)
            copied += 1
    (out / "fonts").mkdir(exist_ok=True)
    shutil.copy2(config.font_path, out / "fonts" / config.font_path.name)
    print(f"Static app written to {out} ({copied} data files). Serve the folder over HTTPS (or http://localhost).")
    return 0


def parse_args(argv: Optional[List[str]] = None) -> Config:
    env = os.environ.get
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default=env("LA_HOST", "127.0.0.1"), help="interface to bind (default 127.0.0.1)")
    p.add_argument("--port", type=int, default=int(env("LA_PORT", "8080")))
    p.add_argument("--db", default=env("LA_DB"), help="SQLite database path (default data/learned_ayahs.db)")
    p.add_argument("--audio-cache", default=env("LA_AUDIO_CACHE"), help="audio cache directory (default data/audio-cache)")
    p.add_argument("--assets", default=env("LA_ASSETS"), help="Qur'an data directory (default: the Android app's assets)")
    p.add_argument(
        "--registration",
        choices=("open", "closed"),
        default=env("LA_REGISTRATION", "open"),
        help="initial sign-up policy; administrators can change it in the app",
    )
    p.add_argument("--no-fetch-audio", action="store_true", help="serve only audio already in the cache")
    p.add_argument("--secure-cookies", action="store_true", help="mark cookies Secure (use behind HTTPS)")
    p.add_argument("--quiet", action="store_true", help="don't log requests")
    p.add_argument("--export-static", metavar="DIR", help="write a static copy of the app (no accounts) to DIR and exit")
    a = p.parse_args(argv)
    cfg = Config(host=a.host, port=a.port, registration_default=a.registration == "open")
    if a.db:
        cfg.db_path = Path(a.db)
    if a.audio_cache:
        cfg.audio_cache = Path(a.audio_cache)
    if a.assets:
        cfg.assets_dir = Path(a.assets)
    cfg.fetch_audio = not a.no_fetch_audio
    cfg.secure_cookies = a.secure_cookies
    cfg.quiet = a.quiet
    cfg.export_static = Path(a.export_static) if a.export_static else None
    if env("LA_PBKDF2_ITERATIONS"):
        cfg.pbkdf2_iterations = int(env("LA_PBKDF2_ITERATIONS"))
    return cfg


def main(argv: Optional[List[str]] = None) -> int:
    config = parse_args(argv)
    if not (config.assets_dir / "quran_text.json").is_file():
        print(f"Qur'an data not found in {config.assets_dir}. Pass --assets.", file=sys.stderr)
        return 1
    if config.export_static:
        return export_static(config, config.export_static)
    server = make_server(config)
    shown = "localhost" if config.host in ("127.0.0.1", "0.0.0.0") else config.host
    print(f"Learned Ayahs web app: http://{shown}:{server.server_address[1]}/  (Ctrl+C to stop)")
    if config.host == "0.0.0.0":
        print("Listening on all interfaces: other devices can reach it via this computer's IP address.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
