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

    def file_claim(self, phone, serial):
        return phone.call("POST", "/v2/claims", {
            "key": phone.key_b64, "strap": handle(serial), "platform": phone.platform})

    def joined_by_silence(self, serial):
        """A phone that claims `serial` and gets in because nobody answered for two days: on probation."""
        phone = Phone(self)
        status, body = self.file_claim(phone, serial)
        self.assertEqual(201, status, body)
        self.advance(48 * HOUR + 1)
        status, body = phone.call("GET", "/v2/claims/mine")
        self.assertEqual("approved", body["claim"]["state"], body)
        return phone

    def put_strap(self, phone, serial):
        return phone.call("PUT", "/v2/me/strap", {"strap": handle(serial)})

    def befriend(self, a, b):
        status, invite = a.call("POST", "/v2/invites")
        self.assertEqual(201, status, invite)
        status, body = b.call("POST", "/v2/invites/redeem", {"code": invite["code"]})
        self.assertEqual(200, status, body)
        return invite

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

    # --- joining from a new phone ---

    def test_a_new_phone_joins_when_the_old_one_confirms(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self, platform="android")
        status, body = self.file_claim(new, "whoop-AAA111")
        self.assertEqual(201, status, body)
        claim = body["claim"]
        self.assertEqual(("join", "pending", "android"), (claim["kind"], claim["state"], claim["platform"]))
        self.assertRegex(claim["code"], r"^[0-9]{6}$")
        self.assertEqual(int(self.now) + 48 * HOUR, claim["maturesAt"])
        self.assertEqual((401, "unknown_key"), (new.call("GET", "/v2/me")[0], new.call("GET", "/v2/me")[1]["error"]))
        self.assertEqual(204, old.call("POST", "/v2/claims/%d/approve" % claim["id"])[0])
        status, body = new.call("GET", "/v2/claims/mine")
        self.assertEqual("approved", body["claim"]["state"])
        status, me = new.call("GET", "/v2/me")
        self.assertEqual(200, status)
        self.assertEqual(old.id, me["id"])
        self.assertIsNone(me["device"]["probationUntil"])
        status, body = old.call("GET", "/v2/me/devices")
        self.assertEqual([("ios", True), ("android", False)],
                         [(d["platform"], d["current"]) for d in body["devices"]])

    def test_filing_the_same_claim_twice_is_one_claim(self):
        self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        first = self.file_claim(new, "whoop-AAA111")[1]["claim"]
        status, body = self.file_claim(new, "whoop-AAA111")
        self.assertEqual((201, first["id"], first["code"]), (status, body["claim"]["id"], body["claim"]["code"]))

    def test_a_declined_claim_blocks_that_key_for_a_week(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        claim = self.file_claim(new, "whoop-AAA111")[1]["claim"]
        self.assertEqual(204, old.call("POST", "/v2/claims/%d/decline" % claim["id"])[0])
        self.assertEqual("declined", new.call("GET", "/v2/claims/mine")[1]["claim"]["state"])
        status, body = self.file_claim(new, "whoop-AAA111")
        self.assertEqual((429, "claim_declined"), (status, body["error"]))
        # A decision is made once.
        status, body = old.call("POST", "/v2/claims/%d/approve" % claim["id"])
        self.assertEqual((409, "claim_settled"), (status, body["error"]))
        self.advance(7 * DAY + 1)
        old.call("GET", "/v2/me")
        self.assertEqual(201, self.file_claim(new, "whoop-AAA111")[0])

    def test_a_claim_can_be_withdrawn(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        claim = self.file_claim(new, "whoop-AAA111")[1]["claim"]
        self.assertEqual(204, new.call("DELETE", "/v2/claims/mine")[0])
        status, body = old.call("POST", "/v2/claims/%d/approve" % claim["id"])
        self.assertEqual((404, "no_claim"), (status, body["error"]))
        self.assertEqual(401, new.call("GET", "/v2/claims/mine")[0])

    def test_someone_elses_claim_is_not_mine_to_answer(self):
        self.enroll("Anna", serial="whoop-AAA111")
        other = self.enroll("Max", serial="whoop-BBB222")
        claim = self.file_claim(Phone(self), "whoop-AAA111")[1]["claim"]
        status, body = other.call("POST", "/v2/claims/%d/approve" % claim["id"])
        self.assertEqual((404, "no_claim"), (status, body["error"]))

    def test_a_claim_needs_a_bound_strap_and_a_key_that_is_not_a_phone_yet(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        status, body = self.file_claim(Phone(self), "whoop-NOBODY")
        self.assertEqual((404, "strap_free"), (status, body["error"]))
        status, body = self.file_claim(anna, "whoop-AAA111")
        self.assertEqual((409, "already_enrolled"), (status, body["error"]))

    def test_two_days_of_silence_let_the_strap_alone_in_on_probation(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        self.file_claim(new, "whoop-AAA111")
        self.advance(48 * HOUR - 1)
        self.assertEqual("pending", new.call("GET", "/v2/claims/mine")[1]["claim"]["state"])
        self.advance(2)
        self.assertEqual("approved", new.call("GET", "/v2/claims/mine")[1]["claim"]["state"])
        status, me = new.call("GET", "/v2/me")
        self.assertEqual((200, old.id), (status, me["id"]))
        self.assertEqual(int(self.now) + 7 * DAY, me["device"]["probationUntil"])

    def test_a_trusted_phone_that_still_syncs_holds_the_door(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        self.file_claim(new, "whoop-AAA111")
        self.advance(47 * HOUR)
        self.assertEqual(200, old.call("GET", "/v2/me")[0])
        self.advance(2 * HOUR)
        claim = new.call("GET", "/v2/claims/mine")[1]["claim"]
        self.assertEqual("pending", claim["state"])
        self.assertEqual(int(self.now) + 46 * HOUR, claim["maturesAt"])
        self.advance(46 * HOUR + 1)
        self.assertEqual("approved", new.call("GET", "/v2/claims/mine")[1]["claim"]["state"])

    def test_a_phone_on_probation_does_not_hold_the_door(self):
        self.enroll("Anna", serial="whoop-AAA111")
        intruder = self.joined_by_silence("whoop-AAA111")
        owner = Phone(self)
        self.file_claim(owner, "whoop-AAA111")
        for _ in range(4):
            self.advance(12 * HOUR)
            self.assertEqual(200, intruder.call("GET", "/v2/me")[0])
        self.advance(1)
        self.assertEqual("approved", owner.call("GET", "/v2/claims/mine")[1]["claim"]["state"])

    def test_probation_reads_but_decides_nothing(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = self.joined_by_silence("whoop-AAA111")
        self.assertEqual(200, new.call("GET", "/v2/me/devices")[0])
        claim = self.file_claim(Phone(self), "whoop-AAA111")[1]["claim"]
        for method, path in (("POST", "/v2/claims/%d/approve" % claim["id"]),
                             ("POST", "/v2/claims/%d/decline" % claim["id"]),
                             ("DELETE", "/v2/me/devices/" + old.key_id),
                             ("POST", "/v2/me/devices/%s/trust" % new.key_id)):
            status, body = new.call(method, path)
            self.assertEqual((403, "probation"), (status, body["error"]), path)
        self.assertEqual(200, old.call("GET", "/v2/me")[0])

    def test_probation_ends_by_itself_after_a_week(self):
        self.enroll("Anna", serial="whoop-AAA111")
        new = self.joined_by_silence("whoop-AAA111")
        self.advance(7 * DAY + 1)
        self.assertIsNone(new.call("GET", "/v2/me")[1]["device"]["probationUntil"])
        self.assertEqual(200, new.call("GET", "/v2/me/devices")[0])
        self.assertEqual([(None,), (None,)], self.rows("SELECT probation_until FROM devices"))

    def test_a_trusted_phone_keeps_or_removes_one_on_probation(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        kept = self.joined_by_silence("whoop-AAA111")
        self.assertEqual(204, old.call("POST", "/v2/me/devices/%s/trust" % kept.key_id)[0])
        self.assertIsNone(kept.call("GET", "/v2/me")[1]["device"]["probationUntil"])
        removed = self.joined_by_silence("whoop-AAA111")
        self.assertEqual(204, old.call("DELETE", "/v2/me/devices/" + removed.key_id)[0])
        status, body = removed.call("GET", "/v2/me")
        self.assertEqual((401, "unknown_key"), (status, body["error"]))
        status, body = old.call("DELETE", "/v2/me/devices/" + "0" * 64)
        self.assertEqual((404, "no_such_device"), (status, body["error"]))

    def test_the_last_trusted_phone_cannot_remove_itself(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        status, body = old.call("DELETE", "/v2/me/devices/" + old.key_id)
        self.assertEqual((409, "last_device"), (status, body["error"]))
        guest = self.joined_by_silence("whoop-AAA111")
        # One on probation is not a second trusted phone, and may leave by itself.
        self.assertEqual(409, old.call("DELETE", "/v2/me/devices/" + old.key_id)[0])
        self.assertEqual(204, guest.call("DELETE", "/v2/me/devices/" + guest.key_id)[0])
        second = Phone(self)
        claim = self.file_claim(second, "whoop-AAA111")[1]["claim"]
        old.call("POST", "/v2/claims/%d/approve" % claim["id"])
        self.assertEqual(204, old.call("DELETE", "/v2/me/devices/" + old.key_id)[0])
        self.assertEqual(200, second.call("GET", "/v2/me")[0])

    def test_three_claims_wait_at_most_and_five_phones_is_the_cap(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        waiting = [Phone(self) for _ in range(3)]
        claims = [self.file_claim(p, "whoop-AAA111")[1]["claim"] for p in waiting]
        status, body = self.file_claim(Phone(self), "whoop-AAA111")
        self.assertEqual((429, "too_many_claims"), (status, body["error"]))
        for claim in claims:
            self.assertEqual(204, old.call("POST", "/v2/claims/%d/approve" % claim["id"])[0])
        fifth = Phone(self)
        claim = self.file_claim(fifth, "whoop-AAA111")[1]["claim"]
        self.assertEqual(204, old.call("POST", "/v2/claims/%d/approve" % claim["id"])[0])
        status, body = self.file_claim(Phone(self), "whoop-AAA111")
        self.assertEqual((409, "too_many_devices"), (status, body["error"]))

    def test_a_claim_nobody_settles_expires_after_two_weeks(self):
        old = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        self.file_claim(new, "whoop-AAA111")
        for _ in range(14):
            self.advance(DAY)
            self.assertEqual(200, old.call("GET", "/v2/me")[0])
        self.advance(1)
        self.assertEqual("expired", new.call("GET", "/v2/claims/mine")[1]["claim"]["state"])

    def test_enrolling_supersedes_a_join_still_waiting(self):
        self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        self.file_claim(new, "whoop-AAA111")
        status, body = new.call("POST", "/v2/enroll", {"key": new.key_b64, "name": "New", "platform": "ios"})
        self.assertEqual(201, status, body)
        self.assertEqual(0, self.rows("SELECT COUNT(*) FROM claims")[0][0])

    # --- moving the strap ---

    def test_a_new_strap_takes_the_account_over_and_the_old_one_is_free(self):
        anna = self.enroll("Anna", serial="whoop-OLD111")
        self.assertEqual((200, {"bound": True}), self.put_strap(anna, "whoop-NEW222"))
        self.assertEqual((200, {"bound": True}), self.put_strap(anna, "whoop-NEW222"))
        self.assertTrue(anna.call("GET", "/v2/me")[1]["strapBound"])
        # The strap left behind belongs to nobody: whoever wears it next starts their own account.
        self.enroll("Buyer", serial="whoop-OLD111")
        second = Phone(self)
        status, body = second.call("POST", "/v2/enroll", {
            "key": second.key_b64, "name": "X", "platform": "ios", "strap": handle("whoop-NEW222")})
        self.assertEqual((409, "strap_bound"), (status, body["error"]))

    def test_an_account_without_a_strap_binds_one(self):
        anna = self.enroll("Anna")
        self.assertFalse(anna.call("GET", "/v2/me")[1]["strapBound"])
        self.assertEqual(200, self.put_strap(anna, "whoop-AAA111")[0])
        self.assertTrue(anna.call("GET", "/v2/me")[1]["strapBound"])
        status, body = anna.call("PUT", "/v2/me/strap", {"strap": "zz"})
        self.assertEqual((400, "bad_strap"), (status, body["error"]))

    def test_a_strap_bound_elsewhere_is_asked_for_and_its_owner_lets_it_go(self):
        seller = self.enroll("Seller", serial="whoop-AAA111")
        buyer = self.enroll("Buyer")
        status, body = self.put_strap(buyer, "whoop-AAA111")
        self.assertEqual(202, status, body)
        claim = body["claim"]
        self.assertEqual(("take", "pending"), (claim["kind"], claim["state"]))
        # Asking again is the same claim, and the buyer's account works meanwhile.
        self.assertEqual(claim["id"], self.put_strap(buyer, "whoop-AAA111")[1]["claim"]["id"])
        self.assertEqual(200, buyer.call("GET", "/v2/me")[0])
        self.assertEqual(204, seller.call("POST", "/v2/claims/%d/approve" % claim["id"])[0])
        self.assertEqual((200, {"bound": True}), self.put_strap(buyer, "whoop-AAA111"))
        self.assertFalse(seller.call("GET", "/v2/me")[1]["strapBound"])
        self.assertEqual(200, seller.call("GET", "/v2/me")[0])

    def test_a_take_matures_after_two_days_of_silence(self):
        seller = self.enroll("Seller", serial="whoop-AAA111")
        buyer = self.enroll("Buyer")
        self.assertEqual(202, self.put_strap(buyer, "whoop-AAA111")[0])
        self.advance(48 * HOUR + 1)
        self.assertEqual((200, {"bound": True}), self.put_strap(buyer, "whoop-AAA111"))
        self.assertEqual([(None,)], self.rows("SELECT strap FROM accounts WHERE pub_id = ?", (seller.id,)))

    def test_a_declined_take_blocks_that_account_for_a_week(self):
        owner = self.enroll("Owner", serial="whoop-AAA111")
        other = self.enroll("Other")
        claim = self.put_strap(other, "whoop-AAA111")[1]["claim"]
        self.assertEqual(204, owner.call("POST", "/v2/claims/%d/decline" % claim["id"])[0])
        status, body = self.put_strap(other, "whoop-AAA111")
        self.assertEqual((429, "claim_declined"), (status, body["error"]))
        self.assertTrue(owner.call("GET", "/v2/me")[1]["strapBound"])

    def test_letting_a_strap_go_ends_the_claims_that_waited_on_it(self):
        anna = self.enroll("Anna", serial="whoop-OLD111")
        new = Phone(self)
        self.file_claim(new, "whoop-OLD111")
        self.assertEqual(200, self.put_strap(anna, "whoop-NEW222")[0])
        self.assertEqual("expired", new.call("GET", "/v2/claims/mine")[1]["claim"]["state"])

    def test_a_phone_on_probation_cannot_move_the_strap(self):
        self.enroll("Anna", serial="whoop-AAA111")
        guest = self.joined_by_silence("whoop-AAA111")
        status, body = self.put_strap(guest, "whoop-MINE99")
        self.assertEqual((403, "probation"), (status, body["error"]))

    # --- invites ---

    def friendships(self):
        return self.rows("SELECT COUNT(*) FROM friendships")[0][0]

    def test_an_invite_makes_two_friends_once(self):
        anna, max_, zoe = self.enroll("Anna"), self.enroll("Max"), self.enroll("Zoe")
        status, invite = anna.call("POST", "/v2/invites")
        self.assertEqual(201, status, invite)
        self.assertRegex(invite["code"], r"^[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}$")
        self.assertEqual(int(self.now) + 7 * DAY, invite["expiresAt"])
        status, body = max_.call("POST", "/v2/invites/redeem", {"code": invite["code"]})
        self.assertEqual(200, status, body)
        self.assertEqual({"id": anna.id, "name": "Anna", "avatarRev": 0}, body["friend"])
        self.assertEqual(1, self.friendships())
        status, body = zoe.call("POST", "/v2/invites/redeem", {"code": invite["code"]})
        self.assertEqual((404, "no_such_invite"), (status, body["error"]))
        self.assertEqual({"invites": []}, anna.call("GET", "/v2/invites")[1])

    def test_a_code_is_read_as_it_is_typed(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        self.assertEqual("0123456789", server.normalize_code(" o12-3456 789 ".replace("o", "O")))
        self.assertEqual("1100ABCDEF", server.normalize_code("ilooabcdef"))
        self.assertIsNone(server.normalize_code("ABCDE-1234"))
        self.assertIsNone(server.normalize_code("ABCDE-1234U"))
        self.assertIsNone(server.normalize_code(12))
        code = anna.call("POST", "/v2/invites")[1]["code"]
        self.assertEqual(200, max_.call("POST", "/v2/invites/redeem", {"code": " " + code.lower().replace("-", " ")})[0])
        status, body = max_.call("POST", "/v2/invites/redeem", {"code": "nonsense"})
        self.assertEqual((404, "no_such_invite"), (status, body["error"]))

    def test_an_invite_is_not_for_its_maker_and_is_kept_between_friends(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        self.befriend(anna, max_)
        code = anna.call("POST", "/v2/invites")[1]["code"]
        status, body = anna.call("POST", "/v2/invites/redeem", {"code": code})
        self.assertEqual((400, "own_invite"), (status, body["error"]))
        status, body = max_.call("POST", "/v2/invites/redeem", {"code": code})
        self.assertEqual((200, anna.id), (status, body["friend"]["id"]))
        self.assertEqual(1, self.friendships())
        # Already friends: the invite is still there for someone else.
        self.assertEqual(1, len(anna.call("GET", "/v2/invites")[1]["invites"]))

    def test_an_invite_expires_after_a_week(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        code = anna.call("POST", "/v2/invites")[1]["code"]
        self.advance(7 * DAY)
        status, body = max_.call("POST", "/v2/invites/redeem", {"code": code})
        self.assertEqual((404, "no_such_invite"), (status, body["error"]))

    def test_invites_are_listed_without_their_codes_and_can_be_revoked(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        invite = anna.call("POST", "/v2/invites")[1]
        listed = anna.call("GET", "/v2/invites")[1]["invites"]
        self.assertEqual([{"id": invite["id"], "createdAt": int(self.now), "expiresAt": invite["expiresAt"]}], listed)
        self.assertRegex(invite["id"], r"^[0-9a-f]{16}$")
        self.assertEqual({"invites": []}, max_.call("GET", "/v2/invites")[1])
        self.assertEqual(204, max_.call("DELETE", "/v2/invites/" + invite["id"])[0])
        self.assertEqual(1, len(anna.call("GET", "/v2/invites")[1]["invites"]))
        self.assertEqual(204, anna.call("DELETE", "/v2/invites/" + invite["id"])[0])
        self.assertEqual(404, max_.call("POST", "/v2/invites/redeem", {"code": invite["code"]})[0])
        # The database never holds a code, only its hash.
        text = "".join(str(v) for row in self.rows("SELECT * FROM invites") for v in row)
        self.assertNotIn(invite["code"].replace("-", ""), text)

    def test_ten_invites_wait_at_most(self):
        anna = self.enroll("Anna")
        for _ in range(10):
            self.assertEqual(201, anna.call("POST", "/v2/invites")[0])
        status, body = anna.call("POST", "/v2/invites")
        self.assertEqual((409, "too_many_invites"), (status, body["error"]))
        self.advance(7 * DAY)
        self.assertEqual(201, anna.call("POST", "/v2/invites")[0])

    def test_guessing_codes_is_limited_per_phone(self):
        max_ = self.enroll("Max")
        for _ in range(10):
            self.assertEqual(404, max_.call("POST", "/v2/invites/redeem", {"code": "AAAAA-AAAAA"})[0])
        status, body = max_.call("POST", "/v2/invites/redeem", {"code": "AAAAA-AAAAA"})
        self.assertEqual((429, "rate_limited"), (status, body["error"]))

    def test_unfriending_cuts_both_directions(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        self.befriend(anna, max_)
        self.assertEqual(204, max_.call("DELETE", "/v2/friends/" + anna.id)[0])
        self.assertEqual(0, self.friendships())
        self.assertEqual(404, max_.call("DELETE", "/v2/friends/" + "0" * 16)[0])

    def test_probation_cannot_touch_the_friend_list(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        max_ = self.enroll("Max")
        self.befriend(anna, max_)
        code = max_.call("POST", "/v2/invites")[1]["code"]
        guest = self.joined_by_silence("whoop-AAA111")
        for method, path, body in (("POST", "/v2/invites", None),
                                   ("POST", "/v2/invites/redeem", {"code": code}),
                                   ("DELETE", "/v2/friends/" + max_.id, None)):
            status, answer = guest.call(method, path, body)
            self.assertEqual((403, "probation"), (status, answer["error"]), path)
        self.assertEqual(200, guest.call("GET", "/v2/invites")[0])
        self.assertEqual(1, self.friendships())

    def test_the_invite_page_shows_a_code_and_asks_the_database_nothing(self):
        anyone = Phone(self)
        status, page = anyone.call("GET", "/i/K7QM2-XRD4P", signed=False)
        self.assertEqual(200, status)
        text = page.decode("utf-8")
        self.assertIn('href="renoop://friends/add?c=K7QM2-XRD4P"', text)
        self.assertIn("<code>K7QM2-XRD4P</code>", text)
        headers = anyone.last_headers
        self.assertTrue(headers["Content-Type"].startswith("text/html"))
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("no-referrer", headers["Referrer-Policy"])
        self.assertEqual("default-src 'none'; style-src 'unsafe-inline'", headers["Content-Security-Policy"])
        for bad in ("/i/short", "/i/%3Cscript%3Ealert(1)", "/i/K7QM2-XRD4P-TOOLONG", "/i/"):
            self.assertEqual(404, anyone.call("GET", bad, signed=False)[0], bad)
        self.assertEqual("/i/…", server.log_path("/i/K7QM2-XRD4P"))
        self.assertEqual("/v2/feed", server.log_path("/v2/feed"))
        self.assertEqual("/v2/friends/…", server.log_path("/v2/friends/c0aa3cd1d44734dd"))
        self.assertEqual("/v2/me/devices/…/trust", server.log_path("/v2/me/devices/" + "ab" * 32 + "/trust"))
        self.assertEqual("/v2/me/days/2026-10-10", server.log_path("/v2/me/days/2026-10-10"))


if __name__ == "__main__":
    unittest.main()
