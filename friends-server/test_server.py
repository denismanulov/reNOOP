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


def together(*calls):
    """Runs the calls on threads released at the same instant and answers their results in order. A call
    that raised is reported here, not left to print from its thread."""
    gate = threading.Barrier(len(calls))
    results = [None] * len(calls)
    errors = []

    def run(index, call):
        try:
            gate.wait(timeout=10)
            results[index] = call()
        except BaseException as err:  # noqa: BLE001 - collected and asserted below
            errors.append(err)

    threads = [threading.Thread(target=run, args=(i, call)) for i, call in enumerate(calls)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    if errors:
        raise AssertionError("calls raised: %r" % (errors,))
    return results


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

    def today(self):
        return time.strftime("%Y-%m-%d", time.gmtime(self.now))

    def full_day(self):
        now = int(self.now)
        return {
            "recovery": 81, "strain": 38.6, "sleepScore": 88,
            "sleep": {"startTs": now - 30000, "endTs": now - 1000, "asleepMin": 474, "awakeMin": 18,
                      "remMin": 104, "lightMin": 252, "deepMin": 100, "needMin": 495},
            "workouts": [{"startTs": now - 900, "sport": "Running", "durationS": 1860, "strain": 35.2,
                          "avgHr": 139, "maxHr": 162, "kcal": 310}],
            "hr": {"lastBpm": 62, "lastTs": now - 60, "restingBpm": 51, "series": [[now - 120, 64], [now - 60, 62]]},
        }

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
        # It is remembered as long as its time can still be admitted: a request stamped at the edge of the
        # window is good until 300 s past its stamp, which is 600 s after the first such request.
        stamp = int(self.now) + 300
        self.assertEqual(200, phone.call("GET", "/v2/me", ts=stamp, nonce="late-late-late-late")[0])
        for later in (300, 300):
            self.advance(later)
            status, body = phone.call("GET", "/v2/me", ts=stamp, nonce="late-late-late-late")
            self.assertEqual((401, "replayed"), (status, body.get("error")), self.now)
        self.advance(1)
        status, body = phone.call("GET", "/v2/me", ts=stamp, nonce="late-late-late-late")
        self.assertEqual((401, "clock_skew"), (status, body["error"]))

    def test_adding_a_live_nonce_again_is_refused_in_the_same_step(self):
        cache = server.NonceCache()
        self.assertTrue(cache.add(b"k", "n", 1000))
        self.assertFalse(cache.add(b"k", "n", 1000))
        self.assertFalse(cache.add(b"k", "n", 1600))
        self.assertTrue(cache.add(b"k", "n", 1601))

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

    # --- profile, days, feed ---

    def test_the_name_and_the_switches_are_changed_by_patch(self):
        anna = self.enroll("Anna")
        status, me = anna.call("PATCH", "/v2/me", {"name": "  Анна  ", "share": {"hr": True, "sleep": False}})
        self.assertEqual(200, status, me)
        self.assertEqual("Анна", me["name"])
        self.assertEqual({"scores": True, "sleep": False, "workouts": True, "hr": True}, me["share"])
        for body in ({"name": ""}, {"share": {"steps": True}}, {"share": {"hr": 1}}, {"nick": "x"}):
            self.assertEqual(400, anna.call("PATCH", "/v2/me", body)[0], body)

    def test_a_stranger_sees_nothing_and_a_friend_sees_only_what_is_shared(self):
        anna, max_, zoe = self.enroll("Anna"), self.enroll("Max"), self.enroll("Zoe")
        self.assertEqual(204, anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day())[0])
        self.befriend(anna, max_)
        status, feed = max_.call("GET", "/v2/feed")
        self.assertEqual(200, status, feed)
        self.assertEqual(int(self.now), feed["serverTime"])
        self.assertEqual(max_.id, feed["me"]["id"])
        self.assertEqual([], feed["claims"])
        self.assertIsNone(feed["strapClaim"])
        friend = feed["friends"][0]
        self.assertEqual((anna.id, "Anna"), (friend["id"], friend["name"]))
        day = friend["days"][0]
        self.assertEqual((self.today(), 81, 88), (day["day"], day["recovery"], day["sleepScore"]))
        self.assertIn("sleep", day)
        self.assertIn("workouts", day)
        self.assertNotIn("hr", day)  # heart rate is off by default
        self.assertEqual([], zoe.call("GET", "/v2/feed")[1]["friends"])
        status, body = zoe.call("GET", "/v2/users/%s/days" % anna.id)
        self.assertEqual((404, "no_such_user"), (status, body["error"]))

    def test_the_heart_rate_line_is_on_a_persons_page_not_in_the_feed(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        anna.call("PATCH", "/v2/me", {"share": {"hr": True}})
        anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day())
        self.befriend(anna, max_)
        in_feed = max_.call("GET", "/v2/feed")[1]["friends"][0]["days"][0]["hr"]
        self.assertEqual({"lastBpm": 62, "lastTs": int(self.now) - 60, "restingBpm": 51}, in_feed)
        status, page = max_.call("GET", "/v2/users/%s/days?days=3" % anna.id)
        self.assertEqual(200, status, page)
        self.assertEqual(2, len(page["days"][0]["hr"]["series"]))
        self.assertEqual(200, anna.call("GET", "/v2/users/%s/days" % anna.id)[0])

    def test_switching_a_section_off_erases_what_was_uploaded(self):
        anna, max_ = self.enroll("Anna"), self.enroll("Max")
        anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day())
        self.befriend(anna, max_)
        anna.call("PATCH", "/v2/me", {"share": {"sleep": False}})
        day = max_.call("GET", "/v2/feed")[1]["friends"][0]["days"][0]
        self.assertNotIn("sleep", day)
        anna.call("PATCH", "/v2/me", {"share": {"sleep": True}})
        day = max_.call("GET", "/v2/feed")[1]["friends"][0]["days"][0]
        self.assertNotIn("sleep", day)  # erased, not hidden
        self.assertFalse(max_.call("GET", "/v2/feed")[1]["friends"][0]["share"]["hr"])

    def test_upload_refuses_anything_outside_the_contract(self):
        anna = self.enroll("Anna")
        path = "/v2/me/days/" + self.today()
        for body in ({"recovery": 101}, {"steps": 5}, {"sleep": {"startTs": 1, "endTs": 2, "asleepMin": 1}},
                     {"workouts": [{"startTs": int(self.now), "sport": "", "durationS": 1}]}):
            self.assertEqual(400, anna.call("PUT", path, body)[0], body)
        self.assertEqual(400, anna.call("PUT", "/v2/me/days/2020-01-01", {"recovery": 5})[0])
        self.assertEqual(404, anna.call("PUT", "/v2/me/days/tomorrow", {"recovery": 5})[0])

    def test_a_later_upload_replaces_the_day(self):
        anna = self.enroll("Anna")
        path = "/v2/me/days/" + self.today()
        anna.call("PUT", path, {"recovery": 40})
        anna.call("PUT", path, {"recovery": 77, "strain": 12.345})
        day = anna.call("GET", "/v2/feed")[1]["me"]["days"][0]
        self.assertEqual((77, 12.35), (day["recovery"], day["strain"]))
        self.assertEqual(int(self.now), day["updatedAt"])

    def test_a_picture_is_seen_by_its_owner_and_friends_only(self):
        anna, max_, zoe = self.enroll("Anna"), self.enroll("Max"), self.enroll("Zoe")
        jpeg = b"\xff\xd8\xff\xe0" + b"0" * 64
        status, me = anna.call("PUT", "/v2/me/avatar", raw=jpeg)
        self.assertEqual((200, 1), (status, me["avatarRev"]))
        self.assertEqual(415, anna.call("PUT", "/v2/me/avatar", raw=b"GIF89a....")[0])
        self.assertEqual(413, anna.call("PUT", "/v2/me/avatar", raw=b"\xff\xd8\xff" + b"0" * 210_000)[0])
        self.assertEqual((200, jpeg), anna.call("GET", "/v2/users/%s/avatar" % anna.id))
        self.assertEqual(404, max_.call("GET", "/v2/users/%s/avatar" % anna.id)[0])
        self.befriend(anna, max_)
        self.assertEqual((200, jpeg), max_.call("GET", "/v2/users/%s/avatar" % anna.id))
        self.assertEqual(404, zoe.call("GET", "/v2/users/%s/avatar" % anna.id)[0])
        self.assertEqual(204, anna.call("DELETE", "/v2/me/avatar")[0])
        self.assertEqual(0, anna.call("GET", "/v2/me")[1]["avatarRev"])
        self.assertEqual(404, max_.call("GET", "/v2/users/%s/avatar" % anna.id)[0])

    def test_the_feed_carries_the_claims_that_wait_for_an_answer(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        new = Phone(self)
        claim = self.file_claim(new, "whoop-AAA111")[1]["claim"]
        self.assertEqual([claim], anna.call("GET", "/v2/feed")[1]["claims"])
        buyer = self.enroll("Buyer")
        taken = self.put_strap(buyer, "whoop-AAA111")[1]["claim"]
        self.assertEqual(taken, buyer.call("GET", "/v2/feed")[1]["strapClaim"])
        self.assertEqual([claim["id"], taken["id"]], [c["id"] for c in anna.call("GET", "/v2/feed")[1]["claims"]])
        anna.call("POST", "/v2/claims/%d/decline" % taken["id"])
        self.assertIsNone(buyer.call("GET", "/v2/feed")[1]["strapClaim"])

    def test_the_feed_names_a_phone_that_joined_unconfirmed(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        self.assertEqual([], anna.call("GET", "/v2/feed")[1]["unconfirmed"])
        guest = self.joined_by_silence("whoop-AAA111")
        named = anna.call("GET", "/v2/feed")[1]["unconfirmed"]
        self.assertEqual([guest.key_id], [d["id"] for d in named])
        self.assertEqual(int(self.now) + 7 * DAY, named[0]["probationUntil"])
        # The phone on probation is not told about itself this way: its own state is in `me.device`.
        self.assertEqual([], guest.call("GET", "/v2/feed")[1]["unconfirmed"])
        anna.call("POST", "/v2/me/devices/%s/trust" % guest.key_id)
        self.assertEqual([], anna.call("GET", "/v2/feed")[1]["unconfirmed"])

    def test_probation_uploads_its_day_and_changes_nothing_else(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        guest = self.joined_by_silence("whoop-AAA111")
        self.assertEqual(204, guest.call("PUT", "/v2/me/days/" + self.today(), {"recovery": 50})[0])
        self.assertEqual(200, guest.call("GET", "/v2/feed")[0])
        for method, path, body, raw in (("PATCH", "/v2/me", {"name": "Mine"}, None),
                                        ("PUT", "/v2/me/avatar", None, b"\xff\xd8\xff0"),
                                        ("DELETE", "/v2/me/avatar", None, None)):
            status, answer = guest.call(method, path, body, raw=raw)
            self.assertEqual((403, "probation"), (status, answer["error"]), path)
        self.assertEqual("Anna", anna.call("GET", "/v2/me")[1]["name"])

    # --- export, deletion, the idle sweep ---

    def test_the_export_is_everything_held_about_the_account(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        max_ = self.enroll("Max")
        self.befriend(anna, max_)
        anna.call("PUT", "/v2/me/avatar", raw=b"\xff\xd8\xff\xe0" + b"0" * 60)
        anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day())
        invite = anna.call("POST", "/v2/invites")[1]
        claim = self.file_claim(Phone(self), "whoop-AAA111")[1]["claim"]
        status, export = anna.call("GET", "/v2/me/export")
        self.assertEqual(200, status, export)
        self.assertEqual({"id": anna.id, "name": "Anna",
                          "share": {"scores": True, "sleep": True, "workouts": True, "hr": False},
                          "avatarRev": 1, "avatarBytes": 64, "strapBound": True,
                          "createdAt": int(self.now), "lastSeenAt": int(self.now)}, export["account"])
        self.assertEqual([anna.key_id], [d["id"] for d in export["devices"]])
        self.assertEqual([{"id": max_.id, "name": "Max"}], export["friends"])
        self.assertEqual([invite["id"]], [i["id"] for i in export["invites"]])
        self.assertEqual([claim["id"]], [c["id"] for c in export["claims"]])
        self.assertEqual(81, export["days"][0]["recovery"])
        self.assertNotIn("hr", export["days"][0])  # never stored: the switch was off
        text = json.dumps(export)
        self.assertNotIn(handle("whoop-AAA111"), text)
        self.assertNotIn(invite["code"].replace("-", ""), text)

    def test_deleting_an_account_removes_every_trace_and_frees_the_strap(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        max_ = self.enroll("Max")
        self.befriend(anna, max_)
        anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day())
        anna.call("POST", "/v2/invites")
        self.assertEqual(204, anna.call("POST", "/v2/me/delete")[0])
        status, body = anna.call("GET", "/v2/me")
        self.assertEqual((401, "unknown_key"), (status, body["error"]))
        self.assertEqual([], max_.call("GET", "/v2/feed")[1]["friends"])
        for table in ("devices", "days", "invites", "friendships", "claims"):
            left = self.rows("SELECT COUNT(*) FROM %s" % table)[0][0]
            self.assertEqual(1 if table == "devices" else 0, left, table)
        again = self.enroll("Anna again", serial="whoop-AAA111")
        self.assertNotEqual(anna.id, again.id)

    def test_probation_cannot_delete_the_account(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        guest = self.joined_by_silence("whoop-AAA111")
        status, body = guest.call("POST", "/v2/me/delete")
        self.assertEqual((403, "probation"), (status, body["error"]))
        self.assertEqual(200, guest.call("GET", "/v2/me/export")[0])
        self.assertEqual(200, anna.call("GET", "/v2/me")[0])

    def test_an_account_nobody_used_for_half_a_year_is_forgotten(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        max_ = self.enroll("Max")
        self.advance(180 * DAY - 10)
        self.assertEqual(200, max_.call("GET", "/v2/me")[0])
        self.advance(20)
        self.sweep()
        self.assertEqual([(max_.id,)], self.rows("SELECT pub_id FROM accounts"))
        self.assertEqual(1, self.rows("SELECT COUNT(*) FROM devices")[0][0])
        self.assertEqual(401, anna.call("GET", "/v2/me")[0])

    def sweep(self):
        """The periodic sweep, on a connection of its own as the sweeping thread has."""
        conn = sqlite3.connect(self.db_path)
        conn.execute("PRAGMA foreign_keys=ON")
        with conn:
            self.app.sweep(conn, int(self.now))
        conn.close()

    def test_days_past_the_window_go_without_an_upload(self):
        anna = self.enroll("Anna")
        anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day())
        self.advance(34 * DAY)
        self.sweep()
        self.assertEqual(1, self.rows("SELECT COUNT(*) FROM days")[0][0])
        self.advance(2 * DAY)
        self.sweep()
        self.assertEqual(0, self.rows("SELECT COUNT(*) FROM days")[0][0])
        self.assertEqual([(anna.id,)], self.rows("SELECT pub_id FROM accounts"))

    def test_the_sweep_clears_expired_invites_and_claims_past_their_time(self):
        anna = self.enroll("Anna", serial="whoop-AAA111")
        anna.call("POST", "/v2/invites")
        self.file_claim(Phone(self), "whoop-AAA111")
        self.advance(7 * DAY)
        self.sweep()
        self.assertEqual(0, self.rows("SELECT COUNT(*) FROM invites")[0][0])
        self.assertEqual([("pending",)], self.rows("SELECT state FROM claims"))
        self.advance(7 * DAY)
        self.sweep()
        self.assertEqual([("expired",)], self.rows("SELECT state FROM claims"))
        self.advance(7 * DAY)
        self.sweep()
        self.assertEqual([("expired",)], self.rows("SELECT state FROM claims"))
        self.advance(1)
        self.sweep()
        self.assertEqual(0, self.rows("SELECT COUNT(*) FROM claims")[0][0])
        self.assertEqual(1, self.rows("SELECT COUNT(*) FROM accounts")[0][0])

    def test_a_full_server_makes_room_by_forgetting_the_idle(self):
        self.app.max_users = 1
        self.enroll("Anna")
        self.advance(180 * DAY + 1)
        self.enroll("Max")
        self.assertEqual([("Max",)], self.rows("SELECT name FROM accounts"))

    # --- requests that arrive together ---

    ROUNDS = 20

    def two_confirmed_phones(self, serial):
        first = self.enroll("Anna", serial=serial)
        second = Phone(self)
        claim = self.file_claim(second, serial)[1]["claim"]
        self.assertEqual(204, first.call("POST", "/v2/claims/%d/approve" % claim["id"])[0])
        return first, second

    def phones_of(self, phone):
        return self.rows("SELECT COUNT(*) FROM devices WHERE account_id = (SELECT id FROM accounts WHERE pub_id = ?)",
                         (phone.id,))[0][0]

    def test_one_code_redeemed_by_two_at_once_makes_one_friend(self):
        for n in range(self.ROUNDS):
            anna, max_, zoe = self.enroll("Anna"), self.enroll("Max"), self.enroll("Zoe")
            code = anna.call("POST", "/v2/invites")[1]["code"]
            answers = together(lambda: max_.call("POST", "/v2/invites/redeem", {"code": code}),
                               lambda: zoe.call("POST", "/v2/invites/redeem", {"code": code}))
            self.assertEqual([200, 404], sorted(status for status, _ in answers), "round %d: %r" % (n, answers))
            self.assertEqual(n + 1, self.friendships(), "round %d" % n)

    def test_two_confirmed_phones_leaving_at_once_leave_one(self):
        for n in range(self.ROUNDS):
            first, second = self.two_confirmed_phones("whoop-LEAVE%03d" % n)
            answers = together(lambda: first.call("DELETE", "/v2/me/devices/" + first.key_id),
                               lambda: second.call("DELETE", "/v2/me/devices/" + second.key_id))
            self.assertEqual([204, 409], sorted(status for status, _ in answers), "round %d: %r" % (n, answers))
            self.assertEqual("last_device", [body for status, body in answers if status == 409][0]["error"])
            self.assertEqual(1, self.phones_of(first), "round %d" % n)

    def test_two_confirmed_phones_removing_each_other_leave_one(self):
        for n in range(self.ROUNDS):
            first, second = self.two_confirmed_phones("whoop-CROSS%03d" % n)
            answers = together(lambda: first.call("DELETE", "/v2/me/devices/" + second.key_id),
                               lambda: second.call("DELETE", "/v2/me/devices/" + first.key_id))
            self.assertEqual([204, 401], sorted(status for status, _ in answers), "round %d: %r" % (n, answers))
            self.assertEqual("unknown_key", [body for status, body in answers if status == 401][0]["error"])
            self.assertEqual(1, self.phones_of(first), "round %d" % n)

    def test_one_key_enrolling_twice_at_once_is_one_account(self):
        for serial in (None, "whoop-TWICE%03d"):
            for n in range(self.ROUNDS):
                phone = Phone(self)
                body = {"key": phone.key_b64, "name": "Anna", "platform": "ios"}
                if serial:
                    body["strap"] = handle(serial % n)
                # Two requests from one phone, so the nonces are fixed here and not counted by the phone.
                answers = together(
                    lambda: phone.call("POST", "/v2/enroll", body, nonce="race-%s-%d-aaaaaaaaaa" % (bool(serial), n)),
                    lambda: phone.call("POST", "/v2/enroll", body, nonce="race-%s-%d-bbbbbbbbbb" % (bool(serial), n)))
                self.assertEqual([200, 201], sorted(status for status, _ in answers),
                                 "strap %s, round %d: %r" % (bool(serial), n, answers))
                self.assertEqual(answers[0][1]["me"]["id"], answers[1][1]["me"]["id"])
        self.assertEqual(2 * self.ROUNDS, self.rows("SELECT COUNT(*) FROM accounts")[0][0])

    def test_an_upload_racing_a_switch_off_stores_nothing_of_that_section(self):
        for n in range(self.ROUNDS):
            anna = self.enroll("Anna")
            answers = together(lambda: anna.call("PUT", "/v2/me/days/" + self.today(), self.full_day()),
                               lambda: anna.call("PATCH", "/v2/me", {"share": {"sleep": False}}))
            self.assertEqual([200, 204], sorted(status for status, _ in answers), "round %d: %r" % (n, answers))
            stored = self.rows("SELECT payload FROM days WHERE account_id = (SELECT id FROM accounts WHERE pub_id = ?)",
                               (anna.id,))
            self.assertEqual(1, len(stored), "round %d" % n)
            self.assertNotIn("sleep", json.loads(stored[0][0]), "round %d" % n)

    def test_every_handler_that_changes_something_or_settles_a_claim_holds_the_write_lock(self):
        # A handler added later that writes without the decorator would reopen all of the above.
        for method, pattern, handler, auth, size, limit in server.ROUTES:
            if method != "GET" or handler in (server.h_feed, server.h_claim_mine):
                self.assertTrue(getattr(handler, "writes", False), "%s %s" % (method, pattern.pattern))



if __name__ == "__main__":
    unittest.main()
