"""End-to-end tests for server.py: a real server on a free port, a temporary database, and a
fake upstream audio host. Run with:  python3 -m unittest discover -s tests -p 'test_*.py'
"""

from __future__ import annotations

import gzip
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor
from http.cookiejar import CookieJar

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import server  # noqa: E402

FAKE_MP3 = b"ID3" + b"\x00" * 2045


class FakeUpstream(BaseHTTPRequestHandler):
    hits: list = []

    def log_message(self, *args):
        pass

    def do_GET(self):  # noqa: N802
        FakeUpstream.hits.append(self.path)
        if "missing" in self.path or self.path.endswith("/999999.mp3"):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path.endswith("/114006.mp3"):
            # A captive portal or error page that answers 200 with HTML.
            page = b"<html><body>Please sign in to the Wi-Fi</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(len(FAKE_MP3)))
        self.end_headers()
        self.wfile.write(FAKE_MP3)


class Client:
    def __init__(self, base: str):
        self.base = base
        self.jar = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.jar))

    def request(self, method: str, path: str, body=None, headers=None, csrf=True):
        data = json.dumps(body).encode() if body is not None else None
        hdrs = {"Content-Type": "application/json"} if data is not None else {}
        if csrf:
            hdrs[server.CSRF_HEADER] = server.CSRF_VALUE
        hdrs.update(headers or {})
        req = Request(self.base + path, data=data, method=method, headers=hdrs)
        try:
            with self.opener.open(req, timeout=10) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except HTTPError as err:
            return err.code, dict(err.headers), err.read()

    def json(self, method, path, body=None, **kw):
        status, headers, raw = self.request(method, path, body, **kw)
        return status, (json.loads(raw) if raw and headers.get("Content-Type", "").startswith("application/json") else None)


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        static = tmp / "static"
        static.mkdir()
        (static / "index.html").write_text("<!doctype html><title>Learned Ayahs</title>")
        (static / "app.js").write_text("console.log('hi')")
        (tmp / "secret.txt").write_text("outside the static root")

        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), FakeUpstream)
        threading.Thread(target=cls.upstream.serve_forever, daemon=True).start()
        up = f"http://127.0.0.1:{cls.upstream.server_address[1]}"

        config = server.Config(
            host="127.0.0.1",
            port=0,
            db_path=tmp / "db.sqlite",
            audio_cache=tmp / "audio",
            static_dir=static,
            pbkdf2_iterations=1000,
            quiet=True,
            upstream={"everyayah": up + "/data", "wbw": up + "/wbw"},
        )
        cls.config = config
        cls.httpd = server.make_server(config)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"

        # Accounts shared by the tests below; the first one becomes the administrator.
        cls.admin = Client(cls.base)
        status, body = cls.admin.json("POST", "/api/auth/register", {"username": "owner", "password": "correct horse"})
        assert status == 201 and body["user"]["role"] == "admin", body

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.upstream.shutdown()
        cls.upstream.server_close()
        cls.tmp.cleanup()

    def new_user(self, name: str, password: str = "password123") -> Client:
        c = Client(self.base)
        status, body = c.json("POST", "/api/auth/register", {"username": name, "password": password})
        self.assertEqual(status, 201, body)
        return c

    # -- accounts -------------------------------------------------------------------------

    def test_health_and_me(self):
        status, body = Client(self.base).json("GET", "/api/health")
        self.assertEqual(status, 200)
        self.assertTrue(body["hasUsers"])
        status, body = Client(self.base).json("GET", "/api/auth/me")
        self.assertEqual(status, 200)
        self.assertIsNone(body["user"])
        status, body = self.admin.json("GET", "/api/auth/me")
        self.assertEqual(body["user"]["username"], "owner")

    def test_later_accounts_are_ordinary_users_and_names_are_unique(self):
        c = self.new_user("second_user")
        status, body = c.json("GET", "/api/auth/me")
        self.assertEqual(body["user"]["role"], "user")
        status, body = Client(self.base).json("POST", "/api/auth/register", {"username": "SECOND_USER", "password": "password123"})
        self.assertEqual(status, 409)
        self.assertEqual(body["error"], "username_taken")

    def test_registration_validates_input(self):
        c = Client(self.base)
        self.assertEqual(c.json("POST", "/api/auth/register", {"username": "a", "password": "password123"})[0], 400)
        self.assertEqual(c.json("POST", "/api/auth/register", {"username": "bad name!", "password": "password123"})[0], 400)
        status, body = c.json("POST", "/api/auth/register", {"username": "shortpw", "password": "short"})
        self.assertEqual((status, body["error"]), (400, "weak_password"))

    def test_login_logout_and_bad_password(self):
        self.new_user("logmein", "password123")
        c = Client(self.base)
        status, body = c.json("POST", "/api/auth/login", {"username": "logmein", "password": "wrong-password"})
        self.assertEqual((status, body["error"]), (401, "invalid_credentials"))
        status, body = c.json("POST", "/api/auth/login", {"username": "LogMeIn", "password": "password123"})
        self.assertEqual(status, 200)
        self.assertEqual(c.json("GET", "/api/auth/me")[1]["user"]["username"], "logmein")
        self.assertEqual(c.json("POST", "/api/auth/logout")[0], 200)
        self.assertIsNone(c.json("GET", "/api/auth/me")[1]["user"])

    def test_session_cookie_is_httponly_and_samesite(self):
        status, headers, _ = Client(self.base).request(
            "POST", "/api/auth/register", {"username": "cookiecheck", "password": "password123"}
        )
        self.assertEqual(status, 201)
        cookie = headers["Set-Cookie"]
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=Lax", cookie)

    def test_mutations_require_the_csrf_header(self):
        status, body = self.admin.json("PUT", "/api/data", {"learned": [1]}, csrf=False)
        self.assertEqual((status, body["error"]), (403, "csrf"))

    def test_failed_logins_are_throttled_per_account(self):
        self.new_user("throttled", "password123")
        self.new_user("neighbour", "password123")
        c = Client(self.base)
        try:
            for _ in range(10):
                c.json("POST", "/api/auth/login", {"username": "throttled", "password": "nope-nope"})
            status, body = c.json("POST", "/api/auth/login", {"username": "throttled", "password": "password123"})
            self.assertEqual((status, body["error"]), (429, "too_many_attempts"))
            # Someone else on the same network can still sign in.
            status, _ = c.json("POST", "/api/auth/login", {"username": "neighbour", "password": "password123"})
            self.assertEqual(status, 200)
        finally:
            self.httpd.app.user_throttle.failures.clear()
            self.httpd.app.ip_throttle.failures.clear()

    def test_password_change_signs_out_other_devices(self):
        laptop = self.new_user("traveller", "password123")
        phone = Client(self.base)
        self.assertEqual(phone.json("POST", "/api/auth/login", {"username": "traveller", "password": "password123"})[0], 200)
        status, body = laptop.json("POST", "/api/account/password", {"currentPassword": "wrong-pass", "newPassword": "newpassword1"})
        self.assertEqual(status, 403)
        status, _ = laptop.json("POST", "/api/account/password", {"currentPassword": "password123", "newPassword": "newpassword1"})
        self.assertEqual(status, 200)
        self.assertIsNotNone(laptop.json("GET", "/api/auth/me")[1]["user"], "this device stays signed in")
        self.assertIsNone(phone.json("GET", "/api/auth/me")[1]["user"], "other devices are signed out")

    def test_display_name_and_account_deletion(self):
        c = self.new_user("leaving")
        status, body = c.json("PATCH", "/api/account", {"displayName": "Leaving Soon"})
        self.assertEqual(body["user"]["displayName"], "Leaving Soon")
        c.json("PUT", "/api/data", {"learned": [1, 2, 3]})
        self.assertEqual(c.json("DELETE", "/api/account", {"password": "wrong-pass"})[0], 403)
        self.assertEqual(c.json("DELETE", "/api/account", {"password": "password123"})[0], 200)
        status, body = Client(self.base).json("POST", "/api/auth/login", {"username": "leaving", "password": "password123"})
        self.assertEqual(status, 401)
        rows = self.httpd.app.store.query("SELECT * FROM user_data ud LEFT JOIN users u ON u.id = ud.user_id WHERE u.id IS NULL")
        self.assertEqual(rows, [], "the account's data goes with it")

    # -- user data ------------------------------------------------------------------------

    def test_data_round_trip_and_validation(self):
        c = self.new_user("reviser")
        status, body = c.json("GET", "/api/data")
        self.assertEqual((status, body["learned"], body["revision"]), (200, [], 0))
        status, body = c.json(
            "PUT",
            "/api/data",
            {"learned": [262, 1, 262], "bookmarks": [6236], "settings": {"theme": "LIGHT"}, "progress": {"lastGlobalId": 262}},
        )
        self.assertEqual(status, 200)
        self.assertEqual(body["learned"], [1, 262], "sorted and de-duplicated")
        self.assertEqual(body["bookmarks"], [6236])
        self.assertEqual(body["settings"], {"theme": "LIGHT"})
        self.assertEqual(body["revision"], 1)
        # Partial update leaves the other fields alone.
        status, body = c.json("PUT", "/api/data", {"progress": {"lastGlobalId": 1}})
        self.assertEqual((body["learned"], body["revision"]), ([1, 262], 2))
        for bad in ({"learned": [0]}, {"learned": [6237]}, {"learned": "1,2"}, {"learned": [True]}, {"settings": []}, {}):
            self.assertEqual(c.json("PUT", "/api/data", bad)[0], 400, bad)
        self.assertEqual(c.json("PUT", "/api/data", {"settings": {"x": "y" * 40000}})[0], 413)
        self.assertEqual(Client(self.base).json("GET", "/api/data")[0], 401)

    def test_users_cannot_see_each_others_data(self):
        a = self.new_user("alice")
        b = self.new_user("bob_the_user")
        a.json("PUT", "/api/data", {"learned": [5]})
        self.assertEqual(b.json("GET", "/api/data")[1]["learned"], [])

    # -- administration -------------------------------------------------------------------

    def test_admin_endpoints_are_admin_only(self):
        c = self.new_user("nosy")
        self.assertEqual(c.json("GET", "/api/admin/users")[0], 403)
        self.assertEqual(Client(self.base).json("GET", "/api/admin/users")[0], 401)

    def test_admin_manages_users(self):
        status, body = self.admin.json("POST", "/api/admin/users", {"username": "managed", "password": "password123"})
        self.assertEqual(status, 201)
        uid = body["user"]["id"]
        managed = Client(self.base)
        self.assertEqual(managed.json("POST", "/api/auth/login", {"username": "managed", "password": "password123"})[0], 200)
        managed.json("PUT", "/api/data", {"learned": [1, 2, 3]})
        users = self.admin.json("GET", "/api/admin/users")[1]["users"]
        row = next(u for u in users if u["id"] == uid)
        self.assertEqual(row["learnedCount"], 3)

        # Disabling signs the user out and blocks sign-in.
        self.assertEqual(self.admin.json("PATCH", f"/api/admin/users/{uid}", {"disabled": True})[0], 200)
        self.assertIsNone(managed.json("GET", "/api/auth/me")[1]["user"])
        status, body = managed.json("POST", "/api/auth/login", {"username": "managed", "password": "password123"})
        self.assertEqual((status, body["error"]), (403, "account_disabled"))
        # Re-enable with a reset password.
        self.admin.json("PATCH", f"/api/admin/users/{uid}", {"disabled": False, "password": "resetpass99"})
        self.assertEqual(managed.json("POST", "/api/auth/login", {"username": "managed", "password": "resetpass99"})[0], 200)
        # Promote, demote, delete.
        self.assertEqual(self.admin.json("PATCH", f"/api/admin/users/{uid}", {"role": "admin"})[1]["user"]["role"], "admin")
        self.assertEqual(self.admin.json("PATCH", f"/api/admin/users/{uid}", {"role": "user"})[1]["user"]["role"], "user")
        self.assertEqual(self.admin.json("DELETE", f"/api/admin/users/{uid}")[0], 200)
        self.assertEqual(self.admin.json("DELETE", f"/api/admin/users/{uid}")[0], 404)

    def test_the_last_administrator_is_protected(self):
        me = self.admin.json("GET", "/api/auth/me")[1]["user"]
        status, body = self.admin.json("PATCH", f"/api/admin/users/{me['id']}", {"role": "user"})
        self.assertEqual((status, body["error"]), (409, "last_admin"))
        status, body = self.admin.json("PATCH", f"/api/admin/users/{me['id']}", {"disabled": True})
        self.assertEqual(status, 409)
        status, body = self.admin.json("DELETE", f"/api/admin/users/{me['id']}")
        self.assertEqual((status, body["error"]), (409, "cannot_delete_self"))

    def test_registration_can_be_closed(self):
        status, body = self.admin.json("PATCH", "/api/admin/settings", {"registrationOpen": False})
        self.assertEqual(body, {"registrationOpen": False})
        try:
            status, body = Client(self.base).json("POST", "/api/auth/register", {"username": "latecomer", "password": "password123"})
            self.assertEqual((status, body["error"]), (403, "registration_closed"))
            self.assertFalse(Client(self.base).json("GET", "/api/health")[1]["registrationOpen"])
            # Administrators can still create accounts.
            self.assertEqual(self.admin.json("POST", "/api/admin/users", {"username": "latecomer", "password": "password123"})[0], 201)
        finally:
            self.admin.json("PATCH", "/api/admin/settings", {"registrationOpen": True})

    # -- files ----------------------------------------------------------------------------

    def test_static_files_and_spa_fallback(self):
        c = Client(self.base)
        status, headers, body = c.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn(b"Learned Ayahs", body)
        self.assertIn("default-src 'self'", headers["Content-Security-Policy"])
        status, headers, _ = c.request("GET", "/app.js")
        self.assertTrue(headers["Content-Type"].startswith("text/javascript"))
        self.assertEqual(c.request("GET", "/surah/36")[0], 200, "client-side routes get the app shell")
        self.assertEqual(c.request("GET", "/missing.js")[0], 404)
        self.assertEqual(c.request("GET", "/../secret.txt")[0], 404)
        self.assertEqual(c.request("GET", "/%2e%2e/secret.txt")[0], 404)

    def test_quran_data_is_served_compressed_with_etags(self):
        c = Client(self.base)
        status, headers, body = c.request("GET", "/data/quran_text.json", headers={"Accept-Encoding": "gzip"})
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Encoding"), "gzip")
        data = json.loads(gzip.decompress(body))
        self.assertEqual(len(data), 6236)
        status, _, _ = c.request("GET", "/data/quran_text.json", headers={"If-None-Match": headers["ETag"]})
        self.assertEqual(status, 304)
        self.assertEqual(c.request("GET", "/data/word_timings_maher.json")[0], 200)
        self.assertEqual(c.request("GET", "/data/../server.py")[0], 404)
        self.assertEqual(c.request("GET", "/data/secrets.json")[0], 404)

    def test_audio_is_fetched_once_cached_atomically_and_range_served(self):
        c = Client(self.base)
        FakeUpstream.hits.clear()
        path = "/audio/everyayah/MaherAlMuaiqly128kbps/001001.mp3"
        status, headers, body = c.request("GET", path)
        self.assertEqual((status, body), (200, FAKE_MP3))
        self.assertEqual(headers["Content-Type"], "audio/mpeg")
        self.assertEqual(headers["Accept-Ranges"], "bytes")
        status, headers, body = c.request("GET", path, headers={"Range": "bytes=10-19"})
        self.assertEqual((status, len(body)), (206, 10))
        self.assertEqual(headers["Content-Range"], f"bytes 10-19/{len(FAKE_MP3)}")
        self.assertEqual(c.request("GET", path, headers={"Range": "bytes=-4"})[2], FAKE_MP3[-4:])
        self.assertEqual(c.request("GET", path, headers={"Range": "bytes=99999-"})[0], 416)
        self.assertEqual(FakeUpstream.hits, ["/data/MaherAlMuaiqly128kbps/001001.mp3"], "fetched upstream exactly once")
        cache = Path(self.config.audio_cache)
        self.assertEqual(list(cache.rglob("*.part")), [], "no partial files left behind")

        self.assertEqual(c.request("GET", "/audio/everyayah/audhubillah.mp3")[0], 200)
        self.assertEqual(c.request("GET", "/audio/wbw/001_001_001.mp3")[0], 200)

    def test_audio_paths_are_validated(self):
        c = Client(self.base)
        for bad in (
            "/audio/everyayah/SomeoneElse/001001.mp3",
            "/audio/everyayah/MaherAlMuaiqly128kbps/../../x.mp3",
            "/audio/everyayah/MaherAlMuaiqly128kbps/1.mp3",
            "/audio/wbw/1_1_1.mp3",
            "/audio/elsewhere/001001.mp3",
        ):
            self.assertEqual(c.request("GET", bad)[0], 404, bad)
        self.assertEqual(c.request("GET", "/audio/everyayah/MaherAlMuaiqly128kbps/999999.mp3")[0], 404)

    def test_a_non_audio_answer_is_not_cached(self):
        c = Client(self.base)
        path = "/audio/everyayah/MaherAlMuaiqly128kbps/114006.mp3"
        self.assertEqual(c.request("GET", path)[0], 502)
        cache = Path(self.config.audio_cache)
        self.assertEqual(list(cache.rglob("114006*")), [], "nothing cached, not even a partial file")

    def test_wav_files_get_a_matching_content_type(self):
        target = Path(self.config.audio_cache) / "everyayah" / "Husary_128kbps" / "001002.mp3"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"RIFF" + b"\x00" * 100)
        status, headers, _ = Client(self.base).request("GET", "/audio/everyayah/Husary_128kbps/001002.mp3")
        self.assertEqual((status, headers["Content-Type"]), (200, "audio/wav"))


class PasswordTest(unittest.TestCase):
    def test_hash_round_trip(self):
        stored = server.hash_password("s3cret-pass", 1000)
        self.assertTrue(server.verify_password("s3cret-pass", stored))
        self.assertFalse(server.verify_password("s3cret-Pass", stored))
        self.assertFalse(server.verify_password("x", "garbage"))
        self.assertNotEqual(stored, server.hash_password("s3cret-pass", 1000), "salted")


class ExportStaticTest(unittest.TestCase):
    def test_writes_app_shell_data_and_font(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "site"
            self.assertEqual(server.main(["--export-static", str(out)]), 0)
            self.assertTrue((out / "index.html").is_file())
            self.assertTrue((out / "js" / "main.js").is_file())
            self.assertTrue((out / "sw.js").is_file())
            self.assertTrue((out / "data" / "quran_text.json").is_file())
            self.assertTrue((out / "data" / "word_translations.json").is_file())
            self.assertTrue(list((out / "data").glob("word_timings_*.json")))
            self.assertTrue((out / "fonts" / "scheherazade_new.ttf").is_file())
            # Refuses to write into a folder that already has files in it.
            self.assertEqual(server.main(["--export-static", str(out)]), 1)


if __name__ == "__main__":
    unittest.main()
