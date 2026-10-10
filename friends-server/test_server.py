"""End-to-end tests: a real server on an ephemeral port, a temporary database, signed HTTP calls."""

import base64
import hashlib
import hmac
import json
import os
import sqlite3
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import server

PEPPER = b"p" * 32
HOUR = 3600
DAY = 86400


def handle(adopted_id):
    """The strap handle a phone sends for a registry id such as `whoop-4A0123456`."""
    return hashlib.sha256(("renoop-friends-strap-v1\n" + adopted_id).encode()).hexdigest()


class Phone:
    """One phone: its key, its address, and calls signed the way the apps sign them."""

    _count = 0

    def __init__(self, test, platform="ios"):
        Phone._count += 1
        self.test = test
        self.platform = platform
        # Each phone arrives from its own address, as it would through the proxy, so a per-address
        # limit is tested where it is meant to bite and nowhere else.
        self.ip = "10.%d.%d.%d" % (Phone._count // 62500, Phone._count // 250 % 250, Phone._count % 250 + 1)
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.spki = self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.key_id = hashlib.sha256(self.spki).hexdigest()
        self.id = None
        self._nonces = 0

    @property
    def key_b64(self):
        return base64.b64encode(self.spki).decode()

    def call(self, method, path, body=None, raw=None, signed=True, ts=None, nonce=None,
             sign_body=None, sign_path=None, sign_method=None, key_id=None, headers=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else b"")
        req = urllib.request.Request(self.test.base + path, data=data or None, method=method)
        req.add_header("X-Forwarded-For", self.ip)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if signed:
            ts = str(int(self.test.now)) if ts is None else str(ts)
            if nonce is None:
                self._nonces += 1
                nonce = "n%021d" % self._nonces
            message = server.signing_string(sign_method or method, sign_path or path, ts, nonce,
                                            data if sign_body is None else sign_body)
            signature = self.key.sign(message, ec.ECDSA(hashes.SHA256()))
            req.add_header("X-Friends-Key", key_id or self.key_id)
            req.add_header("X-Friends-Time", ts)
            req.add_header("X-Friends-Nonce", nonce)
            req.add_header("X-Friends-Signature", base64.b64encode(signature).decode())
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                payload = resp.read()
                kind = resp.headers.get("Content-Type", "")
                self.last_headers = resp.headers
                return resp.status, (json.loads(payload) if kind.startswith("application/json") else payload)
        except urllib.error.HTTPError as err:
            with err:
                payload = err.read()
            return err.code, (json.loads(payload) if payload else None)


class FriendsServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.tmp.name, "t.db")
        self.now = 1_791_540_000.0
        self.app = server.App(self.db_path, pepper=PEPPER, clock=lambda: self.now)
        self.httpd = server.make_server(self.app, "127.0.0.1", 0, quiet=True)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % self.httpd.server_address[1]

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.app.close_db()
        self.tmp.cleanup()

    def advance(self, seconds):
        self.now += seconds

    def rows(self, sql, args=()):
        """Reads the database directly, for what no call exposes."""
        conn = sqlite3.connect(self.db_path)
        try:
            return conn.execute(sql, args).fetchall()
        finally:
            conn.close()

    def enroll(self, name, serial=None, platform="ios"):
        phone = Phone(self, platform)
        body = {"key": phone.key_b64, "name": name, "platform": platform}
        if serial is not None:
            body["strap"] = handle(serial)
        status, answer = phone.call("POST", "/v2/enroll", body)
        self.assertEqual(201, status, answer)
        phone.id = answer["me"]["id"]
        return phone

    # --- the frame ---

    def test_health_and_info_need_no_signature(self):
        anyone = Phone(self)
        self.assertEqual((200, {"ok": True}), anyone.call("GET", "/healthz", signed=False))
        status, info = anyone.call("GET", "/v2/info", signed=False)
        self.assertEqual(200, status)
        self.assertEqual({"name": "renoop-friends", "api": 2, "time": int(self.now)}, info)

    def test_the_signing_string_is_the_contracts(self):
        text = server.signing_string("PUT", "/v2/me/days/2026-10-10", "1791540000",
                                     "AAAAAAAAAAAAAAAAAAAAAA", b'{"recovery":81}')
        self.assertEqual(
            b"renoop-friends-v2\nPUT\n/v2/me/days/2026-10-10\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\n"
            b"a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445", text)
        spki = base64.b64decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ/iGnmqlOeIlaOq9cf3gYSDRWcoyKOiHtx1m7TC7Wnr6CDeXUMf1QOVArjNKQgNPyKXVXw0/"
            "9N1Iyj8xadf1YA==")
        self.assertEqual("b9310888608f332f9d712a19e65fd9a088948406f04240f82a4e50843774784a",
                         hashlib.sha256(spki).hexdigest())
        signature = base64.b64decode(
            "MEYCIQCbv6DGgAsKyGcPSjPiv6/b8i3IJZkUCmkxZt2/mg+nBgIhAPIfodgQQUa5TD0KWsCpCIKzIgtAwstLtonCzIHZLJMQ")
        server.verify(spki, signature, text)
        with self.assertRaises(server.ApiError):
            server.verify(spki, signature, text + b" ")

    def test_enrolling_makes_an_account_and_a_trusted_phone(self):
        ruslan = self.enroll("Руслан")
        status, me = ruslan.call("GET", "/v2/me")
        self.assertEqual(200, status, me)
        self.assertRegex(me["id"], r"^[0-9a-f]{16}$")
        self.assertEqual({"id": me["id"], "name": "Руслан", "avatarRev": 0,
                          "share": {"scores": True, "sleep": True, "workouts": True, "hr": False},
                          "strapBound": False,
                          "device": {"id": ruslan.key_id, "probationUntil": None}}, me)

    def test_enrolling_twice_with_one_key_is_one_account(self):
        phone = self.enroll("Anna")
        status, again = phone.call("POST", "/v2/enroll", {"key": phone.key_b64, "name": "Other", "platform": "ios"})
        self.assertEqual(200, status, again)
        self.assertEqual(phone.id, again["me"]["id"])
        self.assertEqual("Anna", again["me"]["name"])
        self.assertEqual(1, self.rows("SELECT COUNT(*) FROM accounts")[0][0])

    def test_a_signed_route_refuses_an_unsigned_request(self):
        phone = self.enroll("Anna")
        status, body = phone.call("GET", "/v2/me", signed=False)
        self.assertEqual((401, "unsigned"), (status, body["error"]))

    def test_a_key_the_server_does_not_know_is_refused(self):
        stranger = Phone(self)
        status, body = stranger.call("GET", "/v2/me")
        self.assertEqual((401, "unknown_key"), (status, body["error"]))

    def test_a_signature_over_anything_else_is_refused(self):
        phone = self.enroll("Anna")
        for changed in ({"sign_path": "/v2/me?x=1"}, {"sign_method": "POST"}, {"sign_body": b"x"}):
            status, body = phone.call("GET", "/v2/me", **changed)
            self.assertEqual((401, "bad_signature"), (status, body["error"]), changed)
        # Another phone's key id over this phone's signature is not this phone.
        other = self.enroll("Max")
        status, body = phone.call("GET", "/v2/me", key_id=other.key_id)
        self.assertEqual((401, "bad_signature"), (status, body["error"]))

    def test_a_time_outside_the_window_answers_with_the_servers_clock(self):
        phone = self.enroll("Anna")
        for skew in (-301, 301):
            status, body = phone.call("GET", "/v2/me", ts=int(self.now) + skew)
            self.assertEqual((401, "clock_skew"), (status, body["error"]))
            self.assertEqual(int(self.now), body["serverTime"])
        self.assertEqual(200, phone.call("GET", "/v2/me", ts=int(self.now) - 300)[0])

    def test_a_nonce_is_good_once(self):
        phone = self.enroll("Anna")
        self.assertEqual(200, phone.call("GET", "/v2/me", nonce="once-once-once-once")[0])
        status, body = phone.call("GET", "/v2/me", nonce="once-once-once-once")
        self.assertEqual((401, "replayed"), (status, body["error"]))
        # A request that failed its signature did not use its nonce up.
        self.assertEqual(401, phone.call("GET", "/v2/me", nonce="twice-twice-twice-twice", sign_body=b"x")[0])
        self.assertEqual(200, phone.call("GET", "/v2/me", nonce="twice-twice-twice-twice")[0])

    def test_the_key_in_the_body_must_be_a_p256_key_and_the_one_in_the_header(self):
        phone = Phone(self)
        other = Phone(self)
        status, body = phone.call("POST", "/v2/enroll", {"key": other.key_b64, "name": "A", "platform": "ios"})
        self.assertEqual((400, "bad_key"), (status, body["error"]))
        status, body = phone.call("POST", "/v2/enroll", {"key": "bm90IGEga2V5", "name": "A", "platform": "ios"})
        self.assertEqual((400, "bad_key"), (status, body["error"]))
        big = Phone(self)
        big.key = ec.generate_private_key(ec.SECP384R1())
        big.spki = big.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        big.key_id = hashlib.sha256(big.spki).hexdigest()
        status, body = big.call("POST", "/v2/enroll", {"key": big.key_b64, "name": "A", "platform": "ios"})
        self.assertEqual((400, "bad_key"), (status, body["error"]))
        self.assertEqual(0, self.rows("SELECT COUNT(*) FROM accounts")[0][0])

    def test_names_and_platforms_are_checked(self):
        phone = Phone(self)
        for body, code in (({"key": phone.key_b64, "name": "", "platform": "ios"}, "bad_name"),
                           ({"key": phone.key_b64, "name": "x" * 41, "platform": "ios"}, "bad_name"),
                           ({"key": phone.key_b64, "name": "A", "platform": "windows"}, "bad_platform"),
                           ({"key": phone.key_b64, "name": "A", "platform": "ios", "nick": "a"}, "bad_payload")):
            status, answer = phone.call("POST", "/v2/enroll", body)
            self.assertEqual((400, code), (status, answer["error"]), body)

    def test_a_bound_strap_refuses_a_second_enrolment(self):
        self.enroll("Anna", serial="whoop-4A0123456")
        second = Phone(self)
        status, body = second.call("POST", "/v2/enroll", {
            "key": second.key_b64, "name": "Max", "platform": "ios", "strap": handle("whoop-4A0123456")})
        self.assertEqual((409, "strap_bound"), (status, body["error"]))
        self.assertEqual(1, self.rows("SELECT COUNT(*) FROM accounts")[0][0])
        status, body = second.call("POST", "/v2/enroll", {
            "key": second.key_b64, "name": "Max", "platform": "ios", "strap": "not-a-handle"})
        self.assertEqual((400, "bad_strap"), (status, body["error"]))

    def test_the_pepper_keys_what_is_stored_for_a_strap(self):
        self.enroll("Anna", serial="whoop-4A0123456")
        stored = self.rows("SELECT strap FROM accounts")[0][0]
        sent = bytes.fromhex(handle("whoop-4A0123456"))
        self.assertEqual("98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707", sent.hex())
        self.assertNotEqual(sent, stored)
        self.assertEqual(hmac.new(PEPPER, sent, hashlib.sha256).digest(), stored)

    def test_enrolment_is_limited_per_address(self):
        ip = {"X-Forwarded-For": "10.250.0.1"}
        for i in range(5):
            phone = Phone(self)
            body = {"key": phone.key_b64, "name": "P%d" % i, "platform": "ios"}
            self.assertEqual(201, phone.call("POST", "/v2/enroll", body, headers=ip)[0])
        phone = Phone(self)
        status, body = phone.call("POST", "/v2/enroll", {"key": phone.key_b64, "name": "P", "platform": "ios"}, headers=ip)
        self.assertEqual((429, "rate_limited"), (status, body["error"]))

    def test_the_server_holds_only_so_many_accounts(self):
        self.app.max_users = 1
        self.enroll("Anna")
        phone = Phone(self)
        status, body = phone.call("POST", "/v2/enroll", {"key": phone.key_b64, "name": "Max", "platform": "ios"})
        self.assertEqual((403, "server_full"), (status, body["error"]))

    def test_a_version_1_database_is_refused_and_left_alone(self):
        old = os.path.join(self.tmp.name, "old.db")
        conn = sqlite3.connect(old)
        conn.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, nick TEXT)")
        conn.execute("INSERT INTO users(nick) VALUES ('ruslan')")
        conn.commit()
        conn.close()
        with self.assertRaises(server.OldDatabase):
            server.App(old, pepper=PEPPER)
        conn = sqlite3.connect(old)
        self.assertEqual([("ruslan",)], conn.execute("SELECT nick FROM users").fetchall())
        self.assertEqual([("users",)], conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall())
        conn.close()

    def test_a_short_pepper_is_refused(self):
        with self.assertRaises(ValueError):
            server.App(os.path.join(self.tmp.name, "p.db"), pepper=b"short")

    def test_unknown_paths_and_methods(self):
        phone = self.enroll("Anna")
        self.assertEqual(404, phone.call("GET", "/v2/nope")[0])
        self.assertEqual(404, phone.call("GET", "/v1/me")[0])
        self.assertEqual(405, phone.call("DELETE", "/v2/info", signed=False)[0])


if __name__ == "__main__":
    unittest.main()
