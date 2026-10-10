# Friends Server v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite `friends-server/` to API version 2: no nicknames or passwords, every request signed by a phone's P-256 key, the account bound to a strap, friends made by one-time invites.

**Architecture:** One Python file and one SQLite file, as today. A request passes one pipeline (route, per-address limit, signature headers, time window, key lookup, nonce, body, signature) before its handler runs. Time-dependent rules (a claim maturing after 48 hours of silence, a 7-day probation) read an injected clock, so the tests step through days instantly.

**Tech Stack:** Python 3.10+, `sqlite3`, `http.server`, and `cryptography` for ECDSA verification. `unittest` against a real server on an ephemeral port.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-10-friends-keys-and-straps-design.md`. Sections 3, 4, 6 and 7 are this plan's requirements.
- Only dependency beyond the standard library: `cryptography`.
- Signing string: six lines joined by `\n`, no trailing newline: `renoop-friends-v2`, METHOD, request target, time, nonce, lower-case hex SHA-256 of the body.
- Time window 300 s. Nonce memory 600 s. Silence 48 h. Probation 7 d. Claim lifetime 14 d. Re-claim block 7 d. Invite lifetime 7 d. Idle account deletion 180 d.
- Caps: 5 devices and 10 outstanding invites and 100 friends per account; 3 pending claims per strap.
- A version 1 database is refused. The server never migrates it and never deletes it.
- The day payload (`clean_day`) is unchanged from version 1.
- Commit subjects `feature:` / `fix:` / `docs:`; no `Co-Authored-By`; stage named files only.
- All commands run from the repository root unless a step says otherwise.

## File Structure

| File | Responsibility |
|---|---|
| `friends-server/server.py` | The whole service. Rewritten in Task 1, grown by one group of handlers per task. |
| `friends-server/test_server.py` | End-to-end tests. Rewritten in Task 1, grown per task. |
| `friends-server/README.md` | The contract. Rewritten in Task 6. |
| `friends-server/renoop-friends.service` | One comment line changes in Task 6. |
| `.gitignore` | Ignores the local virtual environment (Task 1). |

`server.py` keeps its v1 layout: constants, schema, `ApiError`, `RateLimiter`, validation helpers, `App` (storage), handlers (`h_*`), `ROUTES`, `Request`, `Handler`, `make_server`, `main`.

---

### Task 1: The frame: signed requests and enrolment

**Files:**
- Modify: `.gitignore`
- Rewrite: `friends-server/server.py`
- Rewrite: `friends-server/test_server.py`

**Interfaces:**
- Produces, for every later task:
  - `server.App(db_path, pepper, max_users=500, trust_proxy=True, clock=time.time)` with `now()`, `db()`, `device(key_id)`, `account(account_id)`, `account_by_pub(db, pub_id)`, `strap_value(handle)`, `strap_owner(db, strap)`, `settle(db, strap, now)`, `admit(db, claim, now, probation)`, `close_claim(db, claim_id, state, now)`, `device_count(db, account_id)`, `trusted_count(db, account_id, now)`, `are_friends(db, x, y)`, `befriend(db, x, y)`, `sweep_idle(db, now)`.
  - `server.signing_string(method, target, ts, nonce, body) -> bytes`, `server.verify(spki, signature, message)`, `server.ApiError(status, code, message="", **extra)`, `server.OldDatabase`.
  - `ROUTES` rows are `(method, pattern, handler, auth, max_body, ip_limit)`; `auth` is `"none"`, `"device"`, `"newkey"` or `"claimant"`; `ip_limit` is `None` or `(name, count, window_s)`.
  - A handler is `h(app, req)` returning a JSON-able value or `(status, value)`. `req` has `match`, `query`, `body`, `ip`, `now`, `key_id` (32 bytes), `spki`, `device`, `account`, `trusted`, and `json(allowed)`.
  - `PROBATION_OK`: the set of non-GET handlers a device on probation may call.
  - Test helpers: `Phone(test, platform="ios")` with `.call(...)`, `.key_b64`, `.key_id`, `.id`; `FriendsServerTest.enroll(name, serial=None)`, `.advance(seconds)`, `.rows(sql, args=())`; module function `handle(adopted_id)`; constants `PEPPER`, `HOUR`, `DAY`.

- [ ] **Step 1: Create the Python environment**

```bash
cd friends-server && python3 -m venv .venv && .venv/bin/pip install --quiet cryptography && .venv/bin/python -c "import cryptography; print(cryptography.__version__)"
```

Expected: a version number. Then add one line to `.gitignore`, directly under the existing `__pycache__/` line:

```
friends-server/.venv/
```

- [ ] **Step 2: Write the failing tests**

Replace `friends-server/test_server.py` with:

```python
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
```

`X-Forwarded-For` appears twice in `test_enrolment_is_limited_per_address` (the phone's own and the test's). The server reads the last hop, and `urllib` keeps one header per name with the later value winning, so the test's address is the one seen.

- [ ] **Step 3: Run the tests to see them fail**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -5
```

Expected: errors, the first being `AttributeError: module 'server' has no attribute 'signing_string'` or a `TypeError` from `server.App(... pepper=...)`.

- [ ] **Step 4: Rewrite `server.py`**

Replace `friends-server/server.py`. Six blocks of the version 1 file are carried over **byte for byte**; copy them from the current file before overwriting it:

- `class RateLimiter` (v1 lines 106–125)
- `_bad`, `_int`, `_num`, `_obj` (v1 lines 131–154)
- `clean_day` (v1 lines 157–220)
- `filter_day` (v1 lines 223–229)
- `clean_name` (v1 lines 241–247)
- `sniff_image` (v1 lines 256–263)

The new file, with a marker where each carried block goes:

```python
#!/usr/bin/env python3
"""reNOOP friends server, API version 2.

A small self-hosted service for the reNOOP "Friends" tab. An account has no name to type and no
password: each phone holds a P-256 key and signs every request, the account is bound to the strap as a
way back to it, and friends are made by one-time invites. It holds only what a wearer chose to share
(README.md is the contract). One SQLite file, the standard library plus `cryptography` for checking
signatures, meant to sit behind a TLS-terminating reverse proxy on loopback.
"""

import base64
import binascii
import collections
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import unicodedata
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

API_VERSION = 2
SIGN_PREFIX = "renoop-friends-v2"

DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
KEY_ID_RE = re.compile(r"^[0-9a-f]{64}$")
TIME_RE = re.compile(r"^[0-9]{1,12}$")
NONCE_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
HANDLE_RE = re.compile(r"^[0-9a-f]{64}$")
PLATFORMS = ("ios", "android", "mac")
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

MAX_JSON_BYTES = 64 * 1024
MAX_AVATAR_BYTES = 200 * 1024
MAX_DEVICES = 5
MAX_PENDING_CLAIMS = 3
MAX_INVITES = 10
MAX_FRIENDS = 100
MAX_WORKOUTS_PER_DAY = 20
MAX_HR_POINTS = 300
KEEP_DAYS = 35
FEED_MAX_DAYS = 14
MIN_TS = 1_500_000_000

TIME_WINDOW_S = 300
NONCE_KEEP_S = 600
SEEN_WRITE_EVERY_S = 60
SILENCE_S = 48 * 3600
PROBATION_S = 7 * 86400
CLAIM_TTL_S = 14 * 86400
CLAIM_BLOCK_S = 7 * 86400
SETTLED_KEEP_S = 7 * 86400
INVITE_TTL_S = 7 * 86400
IDLE_DELETE_S = 180 * 86400

# Which uploaded keys each sharing switch governs. A key outside this table is never stored.
SECTIONS = {
    "scores": ("recovery", "strain", "sleepScore"),
    "sleep": ("sleep",),
    "workouts": ("workouts",),
    "hr": ("hr",),
}
SHARE_COLUMNS = {"scores": "share_scores", "sleep": "share_sleep", "workouts": "share_workouts", "hr": "share_hr"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER PRIMARY KEY,
    pub_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    share_scores INTEGER NOT NULL DEFAULT 1,
    share_sleep INTEGER NOT NULL DEFAULT 1,
    share_workouts INTEGER NOT NULL DEFAULT 1,
    share_hr INTEGER NOT NULL DEFAULT 0,
    avatar BLOB,
    avatar_type TEXT,
    avatar_rev INTEGER NOT NULL DEFAULT 0,
    strap BLOB UNIQUE,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    trusted_seen INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    key_id BLOB PRIMARY KEY,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    spki BLOB NOT NULL,
    platform TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    probation_until INTEGER
);
CREATE INDEX IF NOT EXISTS devices_account ON devices(account_id);
CREATE TABLE IF NOT EXISTS claims (
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL,
    strap BLOB NOT NULL,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    key_id BLOB,
    spki BLOB,
    platform TEXT NOT NULL,
    claimant_id INTEGER REFERENCES accounts(id) ON DELETE CASCADE,
    code TEXT NOT NULL,
    state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    settled_at INTEGER
);
CREATE INDEX IF NOT EXISTS claims_strap ON claims(strap);
CREATE INDEX IF NOT EXISTS claims_key ON claims(key_id);
CREATE TABLE IF NOT EXISTS invites (
    code_hash BLOB PRIMARY KEY,
    id TEXT NOT NULL UNIQUE,
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS invites_account ON invites(account_id);
CREATE TABLE IF NOT EXISTS friendships (
    a INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    b INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (a, b),
    CHECK (a < b)
);
CREATE INDEX IF NOT EXISTS friendships_b ON friendships(b);
CREATE TABLE IF NOT EXISTS days (
    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    day TEXT NOT NULL,
    payload TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (account_id, day)
);
"""


class ApiError(Exception):
    def __init__(self, status, code, message="", **extra):
        super().__init__(code)
        self.status = status
        self.code = code
        self.message = message
        self.extra = extra


class OldDatabase(Exception):
    """The file is a version 1 database. It is neither migrated nor deleted here."""


# >>> carried over unchanged from version 1: class RateLimiter


class NonceCache:
    """Nonces seen inside the replay window, in memory. A restart forgets them, which widens a replay
    only to the time window, and every call that changes something is idempotent or one-shot."""

    def __init__(self):
        self._seen = {}
        self._lock = threading.Lock()

    def seen(self, key_id, nonce, now):
        with self._lock:
            at = self._seen.get((key_id, nonce))
            return at is not None and at > now - NONCE_KEEP_S

    def add(self, key_id, nonce, now):
        with self._lock:
            if len(self._seen) > 100_000:
                self._seen = {k: v for k, v in self._seen.items() if v > now - NONCE_KEEP_S}
            self._seen[(key_id, nonce)] = now


# --- signatures ---------------------------------------------------------------------------------


def signing_string(method, target, ts, nonce, body):
    """What a phone signs: six lines, no trailing newline. `target` is the path and query as sent."""
    return "\n".join((SIGN_PREFIX, method.upper(), target, ts, nonce, hashlib.sha256(body).hexdigest())).encode("utf-8")


def load_spki(raw):
    """A P-256 public key from its canonical SubjectPublicKeyInfo, or a 400. Canonical means the bytes
    are exactly what the key serialises to, so one key has one key id."""
    try:
        key = serialization.load_der_public_key(raw)
    except Exception:  # noqa: BLE001 - whatever the parser raises, these bytes are not a key
        raise ApiError(400, "bad_key", "")
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ApiError(400, "bad_key", "")
    canonical = key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if canonical != raw:
        raise ApiError(400, "bad_key", "")
    return key


def verify(spki, signature, message):
    key = load_spki(spki)
    try:
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        raise ApiError(401, "bad_signature", "")
    except Exception:  # noqa: BLE001 - a signature that is not DER at all
        raise ApiError(401, "bad_signature", "")


def spki_in_body(body, key_id):
    """The public key a self-introducing call carries in its body, checked against its header's key id."""
    try:
        value = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ApiError(400, "bad_json", "")
    if not isinstance(value, dict) or not isinstance(value.get("key"), str):
        raise ApiError(400, "bad_key", "")
    try:
        spki = base64.b64decode(value["key"], validate=True)
    except (binascii.Error, ValueError):
        raise ApiError(400, "bad_key", "")
    load_spki(spki)
    if hashlib.sha256(spki).digest() != key_id:
        raise ApiError(400, "bad_key", "")
    return spki


# --- payload validation -------------------------------------------------------------------------

# >>> carried over unchanged from version 1: _bad, _int, _num, _obj, clean_day, filter_day, clean_name, sniff_image


def clean_platform(value):
    if value not in PLATFORMS:
        raise ApiError(400, "bad_platform", "")
    return value


def is_trusted(device, now):
    """A device is trusted unless it joined by silence and its probation is still running."""
    return device["probation_until"] is None or device["probation_until"] <= now


# --- application --------------------------------------------------------------------------------


class App:
    def __init__(self, db_path, pepper, max_users=500, trust_proxy=True, clock=time.time):
        if not isinstance(pepper, bytes) or len(pepper) < 32:
            raise ValueError("the strap pepper must be at least 32 bytes")
        self.db_path = db_path
        self.pepper = pepper
        self.max_users = max_users
        self.trust_proxy = trust_proxy
        self.clock = clock
        self.limiter = RateLimiter()
        self.nonces = NonceCache()
        self._local = threading.local()
        db = self.db()
        if db.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'users'").fetchone():
            self.close_db()
            raise OldDatabase(db_path)
        db.executescript(SCHEMA)
        db.execute("PRAGMA user_version = 2")

    def now(self):
        return int(self.clock())

    def db(self):
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(self.db_path, timeout=10)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA foreign_keys=ON")
            conn.execute("PRAGMA busy_timeout=5000")
            self._local.conn = conn
        return conn

    def close_db(self):
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
            self._local.conn = None

    # --- accounts and devices ---

    def device(self, key_id):
        return self.db().execute("SELECT * FROM devices WHERE key_id = ?", (key_id,)).fetchone()

    def account(self, account_id):
        return self.db().execute("SELECT * FROM accounts WHERE id = ?", (account_id,)).fetchone()

    def account_by_pub(self, db, pub_id):
        account = db.execute("SELECT * FROM accounts WHERE pub_id = ?", (pub_id,)).fetchone()
        if account is None:
            raise ApiError(404, "no_such_user", "")
        return account

    def claimant_spki(self, key_id):
        """The key of whoever filed a join claim; once the claim is settled and swept, of the device it became."""
        db = self.db()
        row = db.execute(
            "SELECT spki FROM claims WHERE key_id = ? AND kind = 'join' ORDER BY id DESC LIMIT 1", (key_id,)).fetchone()
        if row is None:
            row = db.execute("SELECT spki FROM devices WHERE key_id = ?", (key_id,)).fetchone()
        return row["spki"] if row else None

    def touch(self, device, now):
        """Records that a device was heard from, at most once a minute, and answers its fresh row and
        its account's. A trusted device also moves the account's `trusted_seen`, which is what a claim's
        maturity is measured from; one on probation does not."""
        db = self.db()
        trusted = is_trusted(device, now)
        probation_over = trusted and device["probation_until"] is not None
        if probation_over or now - device["last_seen"] >= SEEN_WRITE_EVERY_S:
            with db:
                db.execute("UPDATE devices SET last_seen = ?, probation_until = ? WHERE key_id = ?",
                           (now, None if trusted else device["probation_until"], device["key_id"]))
                if trusted:
                    db.execute("UPDATE accounts SET last_seen = ?, trusted_seen = ? WHERE id = ?",
                               (now, now, device["account_id"]))
                else:
                    db.execute("UPDATE accounts SET last_seen = ? WHERE id = ?", (now, device["account_id"]))
            device = self.device(device["key_id"])
        return device, self.account(device["account_id"])

    def device_count(self, db, account_id):
        return db.execute("SELECT COUNT(*) FROM devices WHERE account_id = ?", (account_id,)).fetchone()[0]

    def trusted_count(self, db, account_id, now):
        return db.execute(
            "SELECT COUNT(*) FROM devices WHERE account_id = ? AND (probation_until IS NULL OR probation_until <= ?)",
            (account_id, now)).fetchone()[0]

    def sweep_idle(self, db, now):
        """Deletes every account not heard from in 180 days, with all it owns. Its days expired long before."""
        db.execute("DELETE FROM accounts WHERE last_seen < ?", (now - IDLE_DELETE_S,))

    # --- straps and claims ---

    def strap_value(self, handle):
        """What is stored for a strap handle: its HMAC under the server's pepper. A leaked database then
        holds neither a serial nor a handle anyone could file a claim with."""
        if not isinstance(handle, str) or not HANDLE_RE.match(handle):
            raise ApiError(400, "bad_strap", "")
        return hmac.new(self.pepper, bytes.fromhex(handle), hashlib.sha256).digest()

    def strap_owner(self, db, strap):
        return db.execute("SELECT * FROM accounts WHERE strap = ?", (strap,)).fetchone()

    def close_claim(self, db, claim_id, state, now):
        db.execute("UPDATE claims SET state = ?, settled_at = ? WHERE id = ?", (state, now, claim_id))

    def settle(self, db, strap, now):
        """Applies the clock to every pending claim on `strap`: expiry first, then maturity by silence.
        The one place time settles a claim; whatever reads a claim calls this first, inside its transaction."""
        for row in db.execute(
                "SELECT id FROM claims WHERE strap = ? AND state = 'pending' ORDER BY id", (strap,)).fetchall():
            claim = db.execute("SELECT * FROM claims WHERE id = ?", (row["id"],)).fetchone()
            if claim is None or claim["state"] != "pending":
                continue  # settled by an earlier claim in this pass
            if now >= claim["created_at"] + CLAIM_TTL_S:
                self.close_claim(db, claim["id"], "expired", now)
                continue
            seen = db.execute("SELECT trusted_seen FROM accounts WHERE id = ?", (claim["account_id"],)).fetchone()[0]
            if now >= max(claim["created_at"], seen) + SILENCE_S:
                self.admit(db, claim, now, probation=True)
        db.execute("DELETE FROM claims WHERE state != 'pending' AND settled_at < ?", (now - SETTLED_KEEP_S,))

    def admit(self, db, claim, now, probation):
        """Grants a claim. A join adds the claiming key as a device (on probation when nobody confirmed
        it); a take moves the strap to the claiming account."""
        if claim["kind"] == "join":
            taken = db.execute("SELECT 1 FROM devices WHERE key_id = ?", (claim["key_id"],)).fetchone()
            if taken or self.device_count(db, claim["account_id"]) >= MAX_DEVICES:
                self.close_claim(db, claim["id"], "expired", now)
                return
            db.execute(
                "INSERT INTO devices(key_id, account_id, spki, platform, added_at, last_seen, probation_until) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (claim["key_id"], claim["account_id"], claim["spki"], claim["platform"], now, now,
                 now + PROBATION_S if probation else None))
            self.close_claim(db, claim["id"], "approved", now)
            return
        db.execute("UPDATE accounts SET strap = NULL WHERE id = ?", (claim["account_id"],))
        db.execute("UPDATE accounts SET strap = ? WHERE id = ?", (claim["strap"], claim["claimant_id"]))
        self.close_claim(db, claim["id"], "approved", now)
        # Whatever else waited on this strap was aimed at the account that no longer holds it, and what
        # waited on the claimant's previous strap was aimed at a strap it no longer holds.
        db.execute(
            "UPDATE claims SET state = 'expired', settled_at = ? WHERE state = 'pending' AND (strap = ? OR account_id = ?)",
            (now, claim["strap"], claim["claimant_id"]))

    # --- friends ---

    def are_friends(self, db, x, y):
        a, b = min(x, y), max(x, y)
        return db.execute("SELECT 1 FROM friendships WHERE a = ? AND b = ?", (a, b)).fetchone() is not None

    def friend_count(self, db, account_id):
        return db.execute(
            "SELECT COUNT(*) FROM friendships WHERE a = ? OR b = ?", (account_id, account_id)).fetchone()[0]

    def befriend(self, db, x, y, now):
        if self.friend_count(db, x) >= MAX_FRIENDS or self.friend_count(db, y) >= MAX_FRIENDS:
            raise ApiError(409, "too_many_friends", "Friend limit reached.")
        db.execute("INSERT OR IGNORE INTO friendships(a, b, created_at) VALUES (?, ?, ?)", (min(x, y), max(x, y), now))


def share_of(account):
    return {section: bool(account[column]) for section, column in SHARE_COLUMNS.items()}


def public_profile(account):
    return {"id": account["pub_id"], "name": account["name"],
            "avatarRev": account["avatar_rev"] if account["avatar"] else 0}


def me_json(account, device, now):
    profile = public_profile(account)
    profile["share"] = share_of(account)
    profile["strapBound"] = account["strap"] is not None
    profile["device"] = {"id": device["key_id"].hex(),
                         "probationUntil": None if is_trusted(device, now) else device["probation_until"]}
    return profile


# --- handlers -----------------------------------------------------------------------------------


def h_health(app, req):
    return {"ok": True}


def h_info(app, req):
    return {"name": "renoop-friends", "api": API_VERSION, "time": req.now}


def h_enroll(app, req):
    body = req.json(("key", "name", "platform", "strap"))
    name = clean_name(body.get("name"))
    platform = clean_platform(body.get("platform"))
    strap = app.strap_value(body["strap"]) if body.get("strap") is not None else None
    db = app.db()
    with db:
        device = app.device(req.key_id)
        if device is not None:
            # The answer to an earlier enrolment was lost on the way: the same key is the same account.
            return {"me": me_json(app.account(device["account_id"]), device, req.now)}
        if strap is not None:
            app.settle(db, strap, req.now)
            if app.strap_owner(db, strap) is not None:
                raise ApiError(409, "strap_bound", "This strap already has an account.")
        if db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] >= app.max_users:
            app.sweep_idle(db, req.now)
            if db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] >= app.max_users:
                raise ApiError(403, "server_full", "This server is not taking new accounts.")
        cur = db.execute(
            "INSERT INTO accounts(pub_id, name, strap, created_at, last_seen, trusted_seen) VALUES (?, ?, ?, ?, ?, ?)",
            (secrets.token_hex(8), name, strap, req.now, req.now, req.now))
        db.execute(
            "INSERT INTO devices(key_id, account_id, spki, platform, added_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)",
            (req.key_id, cur.lastrowid, req.spki, platform, req.now, req.now))
        # An account of its own supersedes a join this key was still waiting on.
        db.execute("DELETE FROM claims WHERE key_id = ?", (req.key_id,))
        account = app.account(cur.lastrowid)
        device = app.device(req.key_id)
    return 201, {"me": me_json(account, device, req.now)}


def h_me(app, req):
    return me_json(req.account, req.device, req.now)


class RawBody:
    def __init__(self, data, content_type, headers=None):
        self.data = data
        self.content_type = content_type
        self.headers = headers or {}


# The calls other than reads that a device on probation may make. Every other change waits.
PROBATION_OK = set()

# (method, path pattern, handler, who signs, largest accepted body, per-address limit before any signature maths)
ROUTES = [
    ("GET", r"/healthz", h_health, "none", 0, None),
    ("GET", r"/v2/info", h_info, "none", 0, None),
    ("POST", r"/v2/enroll", h_enroll, "newkey", MAX_JSON_BYTES, ("enroll", 5, 3600)),
    ("GET", r"/v2/me", h_me, "device", 0, None),
]
ROUTES = [(m, re.compile("^" + p + "$"), h, auth, size, limit) for m, p, h, auth, size, limit in ROUTES]


class Request:
    def __init__(self, match, query, ip, now):
        self.match = match
        self.query = query
        self.ip = ip
        self.now = now
        self.body = b""
        self.key_id = None
        self.spki = None
        self.device = None
        self.account = None

    @property
    def trusted(self):
        return is_trusted(self.device, self.now)

    def json(self, allowed):
        try:
            value = json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise ApiError(400, "bad_json", "")
        if not isinstance(value, dict):
            raise ApiError(400, "bad_json", "")
        if allowed is not None and not set(value) <= set(allowed):
            raise ApiError(400, "bad_payload", "unknown field")
        return value


LOG_ID_RE = re.compile(r"[0-9a-f]{16,}")


def log_path(path):
    """The path as the access log writes it: an invite code is never logged, and neither is an account
    id or a key id, so the log says what was asked for and not by or about whom."""
    if path.startswith("/i/"):
        return "/i/…"
    return LOG_ID_RE.sub("…", path)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "renoop-friends"
    sys_version = ""
    timeout = 20

    def _client_ip(self):
        app = self.server.app
        forwarded = self.headers.get("X-Forwarded-For")
        if app.trust_proxy and forwarded:
            # The proxy appends the peer it saw; only that last hop is its own observation.
            return forwarded.split(",")[-1].strip()
        return self.client_address[0]

    def _send(self, status, payload):
        extra = {}
        if isinstance(payload, RawBody):
            data, content_type, extra = payload.data, payload.content_type, payload.headers
        elif payload is None:
            data, content_type = b"", None
        else:
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            content_type = "application/json"
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for name, value in extra.items():
            self.send_header(name, value)
        self.end_headers()
        if data and self.command != "HEAD":
            self.wfile.write(data)

    def _route(self, path):
        path_known = False
        for method, pattern, handler, auth, max_body, ip_limit in ROUTES:
            match = pattern.match(path)
            if match:
                path_known = True
                if method == self.command:
                    return handler, auth, max_body, ip_limit, match
        raise ApiError(405 if path_known else 404, "method_not_allowed" if path_known else "not_found", "")

    def _signature_headers(self):
        key_id = self.headers.get("X-Friends-Key", "")
        ts = self.headers.get("X-Friends-Time", "")
        nonce = self.headers.get("X-Friends-Nonce", "")
        signature = self.headers.get("X-Friends-Signature", "")
        if not (KEY_ID_RE.match(key_id) and TIME_RE.match(ts) and NONCE_RE.match(nonce) and signature):
            raise ApiError(401, "unsigned", "")
        try:
            raw = base64.b64decode(signature, validate=True)
        except (binascii.Error, ValueError):
            raise ApiError(401, "unsigned", "")
        return bytes.fromhex(key_id), ts, nonce, raw

    def _handle(self):
        app = self.server.app
        url = urlparse(self.path)
        body_read = False
        try:
            ip = self._client_ip()
            app.limiter.check(("all", ip), 600, 60)
            handler, auth, max_body, ip_limit, match = self._route(url.path)
            if ip_limit is not None:
                app.limiter.check((ip_limit[0], ip), ip_limit[1], ip_limit[2])
            if self.headers.get("Transfer-Encoding"):
                raise ApiError(411, "length_required", "")
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                raise ApiError(400, "bad_length", "")
            if length < 0 or length > max_body:
                raise ApiError(413, "too_large", "")
            now = app.now()
            req = Request(match, parse_qs(url.query), ip, now)
            device = None
            if auth != "none":
                key_id, ts, nonce, signature = self._signature_headers()
                if abs(now - int(ts)) > TIME_WINDOW_S:
                    raise ApiError(401, "clock_skew", "", serverTime=now)
                spki = None
                if auth == "device":
                    device = app.device(key_id)
                    spki = device["spki"] if device else None
                elif auth == "claimant":
                    spki = app.claimant_spki(key_id)
                if auth != "newkey" and spki is None:
                    # Known before any signature maths, so a stranger cannot make the server work.
                    raise ApiError(401, "unknown_key", "")
                if app.nonces.seen(key_id, nonce, now):
                    raise ApiError(401, "replayed", "")
            req.body = self.rfile.read(length) if length else b""
            body_read = True
            if auth != "none":
                if auth == "newkey":
                    spki = spki_in_body(req.body, key_id)
                verify(spki, signature, signing_string(self.command, self.path, ts, nonce, req.body))
                # Recorded only now: a request that failed could not use a nonce up.
                app.nonces.add(key_id, nonce, now)
                req.key_id, req.spki = key_id, spki
                if auth == "device":
                    req.device, req.account = app.touch(device, now)
                    if not req.trusted and self.command != "GET" and handler not in PROBATION_OK:
                        raise ApiError(403, "probation",
                                       "This phone joined without confirmation and can only read and upload for now.")
            result = handler(app, req)
            status, payload = result if isinstance(result, tuple) else (200, result)
            self._send(status, payload)
        except ApiError as err:
            if not body_read:
                # Refused before its body was read: the unread bytes must not be parsed as a next request.
                self.close_connection = True
            answer = {"error": err.code, "message": err.message}
            answer.update(err.extra)
            self._send(err.status, answer)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception as err:  # noqa: BLE001 - one request must never take the server down
            sys.stderr.write("internal error on %s %s: %r\n" % (self.command, log_path(url.path), err))
            self._send(500, {"error": "internal", "message": ""})

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _handle

    def finish(self):
        # One thread serves one connection, so its database handle ends with it.
        self.server.app.close_db()
        super().finish()

    def log_message(self, fmt, *args):
        # Method, path without its query or its ids, status. Never a body, a key, a signature or an address.
        if self.server.app_quiet:
            return
        status = args[1] if len(args) > 1 else "-"
        sys.stderr.write("%s %s %s\n" % (self.command, log_path(urlparse(self.path).path), status))


def make_server(app, host="127.0.0.1", port=8787, quiet=False):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.app = app
    server.app_quiet = quiet
    return server


def sweep_forever(app):
    """Once a day, forgets the accounts nobody has used for 180 days."""
    while True:
        time.sleep(86400)
        try:
            db = app.db()
            with db:
                app.sweep_idle(db, app.now())
        except sqlite3.Error as err:
            sys.stderr.write("idle sweep failed: %r\n" % (err,))


def main():
    db_path = os.environ.get("FRIENDS_DB", "friends.db")
    host = os.environ.get("FRIENDS_BIND", "127.0.0.1")
    port = int(os.environ.get("FRIENDS_PORT", "8787"))
    pepper = os.environ.get("FRIENDS_STRAP_PEPPER", "").encode("utf-8")
    if len(pepper) < 32:
        sys.exit("FRIENDS_STRAP_PEPPER is not set, or is shorter than 32 characters. Generate one with "
                 "`python3 -c 'import secrets; print(secrets.token_urlsafe(48))'`, put it in the service's "
                 "environment file, and keep a copy apart from the database.")
    try:
        app = App(
            db_path,
            pepper=pepper,
            max_users=int(os.environ.get("FRIENDS_MAX_USERS", "500")),
            trust_proxy=os.environ.get("FRIENDS_TRUST_PROXY", "1") == "1",
        )
    except OldDatabase:
        sys.exit("%s is a version 1 friends database. Version 2 starts empty: move that file aside and "
                 "start again. Nothing in it was changed." % db_path)
    server = make_server(app, host, port)
    threading.Thread(target=sweep_forever, args=(app,), daemon=True).start()
    sys.stderr.write("renoop-friends listening on %s:%d, database %s\n" % (host, port, db_path))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
```

Note `App.__init__` opens the database on the constructing thread and the tests' server threads open their own; `close_db` in `tearDown` closes the constructing thread's.

- [ ] **Step 5: Run the tests to see them pass**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `Ran 18 tests` and `OK`.

- [ ] **Step 6: Commit**

```bash
git add .gitignore friends-server/server.py friends-server/test_server.py
git commit -m "feature: friends server v2 frame (signed requests, enrolment by phone key)"
```

---

### Task 2: Joining an account from a new phone (claims, devices, probation)

**Files:**
- Modify: `friends-server/server.py` (handlers before `class RawBody`; `PROBATION_OK`; `ROUTES`)
- Modify: `friends-server/test_server.py` (helpers and tests inside `FriendsServerTest`)

**Interfaces:**
- Consumes: Task 1's `App.settle`, `App.admit`, `App.close_claim`, `App.strap_owner`, `App.device_count`, `App.trusted_count`, `claimant` auth.
- Produces: `claim_json(db, claim)`; `new_claim_code()`; `claim_blocked(db, strap, now, key_id=None, claimant_id=None)`; `pending_claims(db, strap)`; routes `POST /v2/claims`, `GET|DELETE /v2/claims/mine`, `POST /v2/claims/{claimId}/approve|decline`, `GET /v2/me/devices`, `DELETE /v2/me/devices/{key}`, `POST /v2/me/devices/{key}/trust`. Test helpers `file_claim(phone, serial)`, `joined_by_silence(serial)`.

- [ ] **Step 1: Write the failing tests**

Add to `FriendsServerTest`, after `enroll`:

```python
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
```

Add these tests at the end of the class:

```python
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
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `FAILED` with errors on the new tests (`404` where `201` was expected: the routes do not exist).

- [ ] **Step 3: Add the handlers**

In `server.py`, insert after `h_me` and before `class RawBody`:

```python
def new_claim_code():
    return "%06d" % secrets.randbelow(1_000_000)


def claim_json(db, claim):
    matures = None
    if claim["state"] == "pending":
        seen = db.execute("SELECT trusted_seen FROM accounts WHERE id = ?", (claim["account_id"],)).fetchone()[0]
        matures = max(claim["created_at"], seen) + SILENCE_S
    return {"id": claim["id"], "kind": claim["kind"], "code": claim["code"], "state": claim["state"],
            "platform": claim["platform"], "createdAt": claim["created_at"], "maturesAt": matures}


def pending_claims(db, strap):
    return db.execute("SELECT COUNT(*) FROM claims WHERE strap = ? AND state = 'pending'", (strap,)).fetchone()[0]


def claim_blocked(db, strap, now, key_id=None, claimant_id=None):
    """Whether this key (a join) or this account (a take) was turned down for this strap inside the last week."""
    column, value = ("key_id", key_id) if key_id is not None else ("claimant_id", claimant_id)
    return db.execute(
        "SELECT 1 FROM claims WHERE strap = ? AND state = 'declined' AND settled_at > ? AND %s = ?" % column,
        (strap, now - CLAIM_BLOCK_S, value)).fetchone() is not None


def h_claim_file(app, req):
    """A key that is not a phone of the account yet asks to become one, on the strength of the strap."""
    body = req.json(("key", "strap", "platform"))
    strap = app.strap_value(body.get("strap"))
    platform = clean_platform(body.get("platform"))
    db = app.db()
    with db:
        app.settle(db, strap, req.now)
        if app.device(req.key_id) is not None:
            raise ApiError(409, "already_enrolled", "")
        owner = app.strap_owner(db, strap)
        if owner is None:
            raise ApiError(404, "strap_free", "")
        waiting = db.execute(
            "SELECT * FROM claims WHERE key_id = ? AND strap = ? AND state = 'pending'", (req.key_id, strap)).fetchone()
        if waiting is not None:
            return 201, {"claim": claim_json(db, waiting)}
        if claim_blocked(db, strap, req.now, key_id=req.key_id):
            raise ApiError(429, "claim_declined", "This request was declined. Try again in a week.")
        if pending_claims(db, strap) >= MAX_PENDING_CLAIMS:
            raise ApiError(429, "too_many_claims", "")
        if app.device_count(db, owner["id"]) >= MAX_DEVICES:
            raise ApiError(409, "too_many_devices", "")
        # One claim per key: whatever it asked for before no longer stands.
        db.execute("DELETE FROM claims WHERE key_id = ? AND state = 'pending'", (req.key_id,))
        cur = db.execute(
            "INSERT INTO claims(kind, strap, account_id, key_id, spki, platform, code, state, created_at) "
            "VALUES ('join', ?, ?, ?, ?, ?, ?, 'pending', ?)",
            (strap, owner["id"], req.key_id, req.spki, platform, new_claim_code(), req.now))
        claim = db.execute("SELECT * FROM claims WHERE id = ?", (cur.lastrowid,)).fetchone()
        return 201, {"claim": claim_json(db, claim)}


def h_claim_mine(app, req):
    db = app.db()
    with db:
        claim = db.execute(
            "SELECT * FROM claims WHERE key_id = ? AND kind = 'join' ORDER BY id DESC LIMIT 1", (req.key_id,)).fetchone()
        if claim is None:
            raise ApiError(404, "no_claim", "")
        app.settle(db, claim["strap"], req.now)
        claim = db.execute("SELECT * FROM claims WHERE id = ?", (claim["id"],)).fetchone()
        if claim is None:
            raise ApiError(404, "no_claim", "")
        return {"claim": claim_json(db, claim)}


def h_claim_withdraw(app, req):
    db = app.db()
    with db:
        db.execute("DELETE FROM claims WHERE key_id = ? AND kind = 'join' AND state = 'pending'", (req.key_id,))
    return 204, None


def _claim_to_answer(app, db, req):
    """A pending claim against the caller's account, with the clock already applied to it."""
    claim = db.execute("SELECT * FROM claims WHERE id = ? AND account_id = ?",
                       (int(req.match.group(1)), req.account["id"])).fetchone()
    if claim is None:
        raise ApiError(404, "no_claim", "")
    app.settle(db, claim["strap"], req.now)
    claim = db.execute("SELECT * FROM claims WHERE id = ?", (claim["id"],)).fetchone()
    if claim is None or claim["state"] != "pending":
        raise ApiError(409, "claim_settled", "")
    return claim


def h_claim_approve(app, req):
    db = app.db()
    with db:
        app.admit(db, _claim_to_answer(app, db, req), req.now, probation=False)
    return 204, None


def h_claim_decline(app, req):
    db = app.db()
    with db:
        app.close_claim(db, _claim_to_answer(app, db, req)["id"], "declined", req.now)
    return 204, None


def h_devices(app, req):
    rows = app.db().execute(
        "SELECT * FROM devices WHERE account_id = ? ORDER BY added_at, rowid", (req.account["id"],)).fetchall()
    return {"devices": [{
        "id": row["key_id"].hex(), "platform": row["platform"], "addedAt": row["added_at"],
        "lastSeenAt": row["last_seen"],
        "probationUntil": None if is_trusted(row, req.now) else row["probation_until"],
        "current": row["key_id"] == req.key_id,
    } for row in rows]}


def _own_device(app, db, req):
    target = db.execute("SELECT * FROM devices WHERE key_id = ? AND account_id = ?",
                        (bytes.fromhex(req.match.group(1)), req.account["id"])).fetchone()
    if target is None:
        raise ApiError(404, "no_such_device", "")
    return target


def h_device_delete(app, req):
    db = app.db()
    with db:
        target = _own_device(app, db, req)
        mine = target["key_id"] == req.key_id
        if not mine and not req.trusted:
            raise ApiError(403, "probation", "")
        if mine and req.trusted and app.trusted_count(db, req.account["id"], req.now) <= 1:
            # With no password there would be no way back but a two-day claim.
            raise ApiError(409, "last_device", "This is the account's only confirmed phone. Delete the account instead.")
        db.execute("DELETE FROM devices WHERE key_id = ?", (target["key_id"],))
    return 204, None


def h_device_trust(app, req):
    db = app.db()
    with db:
        target = _own_device(app, db, req)
        db.execute("UPDATE devices SET probation_until = NULL WHERE key_id = ?", (target["key_id"],))
    return 204, None
```

Replace the `PROBATION_OK = set()` line with:

```python
PROBATION_OK = {h_device_delete}
```

Add these rows to `ROUTES`, after the `/v2/me` row:

```python
    ("POST", r"/v2/claims", h_claim_file, "newkey", MAX_JSON_BYTES, ("claim", 10, 3600)),
    ("GET", r"/v2/claims/mine", h_claim_mine, "claimant", 0, None),
    ("DELETE", r"/v2/claims/mine", h_claim_withdraw, "claimant", 0, None),
    ("POST", r"/v2/claims/([0-9]{1,12})/approve", h_claim_approve, "device", 0, None),
    ("POST", r"/v2/claims/([0-9]{1,12})/decline", h_claim_decline, "device", 0, None),
    ("GET", r"/v2/me/devices", h_devices, "device", 0, None),
    ("DELETE", r"/v2/me/devices/([0-9a-f]{64})", h_device_delete, "device", 0, None),
    ("POST", r"/v2/me/devices/([0-9a-f]{64})/trust", h_device_trust, "device", 0, None),
```

`/v2/claims/mine` must come before the `/v2/claims/{claimId}` rows only in spirit: the patterns do not overlap (`mine` is not digits), so order does not matter.

- [ ] **Step 4: Run the tests**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `Ran 34 tests` and `OK`.

If `test_a_claim_can_be_withdrawn` fails on its last line with `404` instead of `401`: after a withdrawal the key has neither a claim nor a device, so `claimant` auth must answer `unknown_key`. Check `App.claimant_spki` returns `None` in that case.

- [ ] **Step 5: Commit**

```bash
git add friends-server/server.py friends-server/test_server.py
git commit -m "feature: friends server join claims, phones and probation"
```

---

### Task 3: Moving the strap (`PUT /v2/me/strap`, take claims)

**Files:**
- Modify: `friends-server/server.py` (one handler, one route)
- Modify: `friends-server/test_server.py`

**Interfaces:**
- Consumes: `claim_json`, `claim_blocked`, `pending_claims`, `new_claim_code`, `App.settle`, `App.strap_owner`.
- Produces: `PUT /v2/me/strap` answering `200 {"bound": true}` or `202 {"claim": {...}}`. Test helper `put_strap(phone, serial)`.

- [ ] **Step 1: Write the failing tests**

Add the helper after `joined_by_silence`:

```python
    def put_strap(self, phone, serial):
        return phone.call("PUT", "/v2/me/strap", {"strap": handle(serial)})
```

Add the tests:

```python
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
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `FAILED`; the new tests get `404` from `PUT /v2/me/strap`.

- [ ] **Step 3: Add the handler**

Insert after `h_device_trust`:

```python
def h_strap_put(app, req):
    """Binds the caller's account to the strap it now wears. A free strap is bound at once and the
    previous one let go; one bound to another account is asked for, and that claim settles like a join."""
    body = req.json(("strap",))
    strap = app.strap_value(body.get("strap"))
    me = req.account["id"]
    db = app.db()
    with db:
        app.settle(db, strap, req.now)
        owner = app.strap_owner(db, strap)
        if owner is not None and owner["id"] == me:
            return {"bound": True}
        if owner is None:
            db.execute("UPDATE accounts SET strap = ? WHERE id = ?", (strap, me))
            # What waited on the strap just let go was aimed at this account through it; a take of this
            # account's own has nothing left to ask for.
            db.execute(
                "UPDATE claims SET state = 'expired', settled_at = ? WHERE state = 'pending' "
                "AND ((account_id = ? AND strap != ?) OR claimant_id = ?)", (req.now, me, strap, me))
            return {"bound": True}
        waiting = db.execute(
            "SELECT * FROM claims WHERE claimant_id = ? AND strap = ? AND state = 'pending'", (me, strap)).fetchone()
        if waiting is not None:
            return 202, {"claim": claim_json(db, waiting)}
        if claim_blocked(db, strap, req.now, claimant_id=me):
            raise ApiError(429, "claim_declined", "This request was declined. Try again in a week.")
        if pending_claims(db, strap) >= MAX_PENDING_CLAIMS:
            raise ApiError(429, "too_many_claims", "")
        # One take per account: a claim on some other strap no longer stands.
        db.execute("DELETE FROM claims WHERE claimant_id = ? AND state = 'pending'", (me,))
        cur = db.execute(
            "INSERT INTO claims(kind, strap, account_id, platform, claimant_id, code, state, created_at) "
            "VALUES ('take', ?, ?, ?, ?, ?, 'pending', ?)",
            (strap, owner["id"], req.device["platform"], me, new_claim_code(), req.now))
        claim = db.execute("SELECT * FROM claims WHERE id = ?", (cur.lastrowid,)).fetchone()
        return 202, {"claim": claim_json(db, claim)}
```

Add to `ROUTES` after the `/v2/me` row:

```python
    ("PUT", r"/v2/me/strap", h_strap_put, "device", MAX_JSON_BYTES, None),
```

- [ ] **Step 4: Run the tests**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `Ran 41 tests` and `OK`.

- [ ] **Step 5: Commit**

```bash
git add friends-server/server.py friends-server/test_server.py
git commit -m "feature: friends server strap rebinding and take claims"
```

---

### Task 4: Invites, the invite page, unfriending

**Files:**
- Modify: `friends-server/server.py`
- Modify: `friends-server/test_server.py`

**Interfaces:**
- Consumes: `App.befriend(db, x, y, now)`, `App.are_friends`, `App.account_by_pub`, `RawBody(data, content_type, headers)`, `public_profile`.
- Produces: `normalize_code(value) -> str | None`, `show_code(code)`; routes `POST|GET /v2/invites`, `DELETE /v2/invites/{inviteId}`, `POST /v2/invites/redeem`, `DELETE /v2/friends/{id}`, `GET /i/{code}`. Test helper `befriend(a, b)`.

- [ ] **Step 1: Write the failing tests**

Add the helper after `put_strap`:

```python
    def befriend(self, a, b):
        status, invite = a.call("POST", "/v2/invites")
        self.assertEqual(201, status, invite)
        status, body = b.call("POST", "/v2/invites/redeem", {"code": invite["code"]})
        self.assertEqual(200, status, body)
        return invite
```

Add the tests:

```python
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
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `FAILED`; `AttributeError: module 'server' has no attribute 'normalize_code'` and `404` on the invite routes.

- [ ] **Step 3: Add the handlers**

Insert after `h_strap_put`:

```python
def normalize_code(value):
    """An invite code as it is stored: ten characters of the code alphabet. Typed input is forgiven its
    case, its dash and spaces, and the letters that look like digits. None when it is not a code."""
    if not isinstance(value, str):
        return None
    code = "".join(ch for ch in value.upper() if ch not in "- \t\r\n")
    code = code.replace("O", "0").replace("I", "1").replace("L", "1")
    if len(code) != 10 or any(ch not in CODE_ALPHABET for ch in code):
        return None
    return code


def show_code(code):
    return code[:5] + "-" + code[5:]


def h_invite_create(app, req):
    app.limiter.check(("invite", req.key_id), 20, 3600)
    me = req.account["id"]
    db = app.db()
    with db:
        db.execute("DELETE FROM invites WHERE expires_at <= ?", (req.now,))
        if db.execute("SELECT COUNT(*) FROM invites WHERE account_id = ?", (me,)).fetchone()[0] >= MAX_INVITES:
            raise ApiError(409, "too_many_invites", "Too many invites are waiting. Revoke one first.")
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(10))
        digest = hashlib.sha256(code.encode()).digest()
        expires = req.now + INVITE_TTL_S
        db.execute("INSERT INTO invites(code_hash, id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                   (digest, digest.hex()[:16], me, req.now, expires))
    # The one time the code is said. The database holds only its hash.
    return 201, {"id": digest.hex()[:16], "code": show_code(code), "expiresAt": expires}


def h_invites(app, req):
    rows = app.db().execute(
        "SELECT id, created_at, expires_at FROM invites WHERE account_id = ? AND expires_at > ? ORDER BY created_at, rowid",
        (req.account["id"], req.now)).fetchall()
    return {"invites": [{"id": r["id"], "createdAt": r["created_at"], "expiresAt": r["expires_at"]} for r in rows]}


def h_invite_revoke(app, req):
    db = app.db()
    with db:
        db.execute("DELETE FROM invites WHERE id = ? AND account_id = ?", (req.match.group(1), req.account["id"]))
    return 204, None


def h_invite_redeem(app, req):
    app.limiter.check(("redeem", req.key_id), 10, 3600)
    body = req.json(("code",))
    code = normalize_code(body.get("code"))
    me = req.account["id"]
    db = app.db()
    with db:
        invite = None
        if code is not None:
            invite = db.execute("SELECT * FROM invites WHERE code_hash = ? AND expires_at > ?",
                                (hashlib.sha256(code.encode()).digest(), req.now)).fetchone()
        if invite is None:
            # Unknown, expired and already used all read the same.
            raise ApiError(404, "no_such_invite", "This code is not valid any more.")
        if invite["account_id"] == me:
            raise ApiError(400, "own_invite", "That is your own invite.")
        friend = app.account(invite["account_id"])
        if not app.are_friends(db, me, friend["id"]):
            app.befriend(db, me, friend["id"], req.now)
            db.execute("DELETE FROM invites WHERE code_hash = ?", (invite["code_hash"],))
    return {"friend": public_profile(friend)}


def h_unfriend(app, req):
    db = app.db()
    with db:
        other = app.account_by_pub(db, req.match.group(1))
        me = req.account["id"]
        db.execute("DELETE FROM friendships WHERE a = ? AND b = ?", (min(me, other["id"]), max(me, other["id"])))
    return 204, None


INVITE_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>reNOOP invite</title>
<style>
body{font:17px/1.4 -apple-system,system-ui,sans-serif;margin:0;padding:56px 24px;text-align:center;background:#000;color:#fff}
a.open{display:inline-block;margin:28px 0 20px;padding:14px 28px;border-radius:999px;background:#a6ff00;color:#000;font-weight:600;text-decoration:none}
code{font:600 24px/1.2 ui-monospace,Menlo,monospace;letter-spacing:2px}
p.note{color:#8e8e93;font-size:15px;margin:8px 0}
</style></head>
<body>
<h1>reNOOP</h1>
<p>You are invited to share your day with a friend.</p>
<p><a class="open" href="renoop://friends/add?c=%(code)s">Open in reNOOP</a></p>
<p class="note">Or enter this code on the Friends tab:</p>
<p><code>%(code)s</code></p>
</body></html>
"""


def h_invite_page(app, req):
    """A page that hands an invite code to the app. It reads nothing from the database, so it says
    nothing about whether the code is real. The route's pattern is what makes the code safe to print."""
    page = INVITE_PAGE % {"code": req.match.group(1).upper()}
    return 200, RawBody(page.encode("utf-8"), "text/html; charset=utf-8", {
        "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'",
    })
```

`h_invite_page` is defined before `class RawBody` in the file and uses it only when called, which is fine in Python.

Add to `ROUTES`:

```python
    ("GET", r"/i/([0-9A-Za-z-]{10,11})", h_invite_page, "none", 0, None),
    ("POST", r"/v2/invites", h_invite_create, "device", 0, None),
    ("GET", r"/v2/invites", h_invites, "device", 0, None),
    ("DELETE", r"/v2/invites/([0-9a-f]{16})", h_invite_revoke, "device", 0, None),
    ("POST", r"/v2/invites/redeem", h_invite_redeem, "device", MAX_JSON_BYTES, None),
    ("DELETE", r"/v2/friends/([0-9a-f]{16})", h_unfriend, "device", 0, None),
```

- [ ] **Step 4: Run the tests**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `Ran 51 tests` and `OK`.

If `test_the_invite_page_...` fails on `/i/%3Cscript%3Ealert(1)`: the route pattern is matched against the raw, still-encoded path, which contains `%` and is refused. Do not decode the path before matching.

- [ ] **Step 5: Commit**

```bash
git add friends-server/server.py friends-server/test_server.py
git commit -m "feature: friends server one-time invites and the invite page"
```

---

### Task 5: Profile, pictures, days and the feed

**Files:**
- Modify: `friends-server/server.py`
- Modify: `friends-server/test_server.py`

**Interfaces:**
- Consumes: `clean_day`, `filter_day`, `clean_name`, `sniff_image` (carried over in Task 1); `claim_json`; `App.settle`; `befriend` test helper.
- Produces: routes `PATCH /v2/me`, `PUT|DELETE /v2/me/avatar`, `PUT /v2/me/days/{day}`, `GET /v2/feed`, `GET /v2/users/{id}/days`, `GET /v2/users/{id}/avatar`. Feed shape `{"serverTime", "me", "friends", "claims", "strapClaim", "unconfirmed"}`. `h_day_put` joins `PROBATION_OK`. Test helpers `today()`, `full_day()`.

- [ ] **Step 1: Write the failing tests**

Add the helpers after `befriend`:

```python
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
```

Add the tests:

```python
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
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `FAILED`, `404`/`405` on the new routes.

- [ ] **Step 3: Add the handlers**

Insert after `h_unfriend` (before `INVITE_PAGE`):

```python
def h_me_patch(app, req):
    body = req.json(("name", "share"))
    me = req.account["id"]
    db = app.db()
    with db:
        if "name" in body:
            db.execute("UPDATE accounts SET name = ? WHERE id = ?", (clean_name(body["name"]), me))
        if "share" in body:
            share = body["share"]
            if not isinstance(share, dict) or not set(share) <= set(SHARE_COLUMNS):
                raise ApiError(400, "bad_payload", "share")
            for section, value in share.items():
                if not isinstance(value, bool):
                    raise ApiError(400, "bad_payload", "share." + section)
                db.execute("UPDATE accounts SET %s = ? WHERE id = ?" % SHARE_COLUMNS[section], (int(value), me))
        account = app.account(me)
        if "share" in body:
            # Switching a section off removes what was already uploaded, not only what friends see.
            now_share = share_of(account)
            for row in db.execute("SELECT day, payload FROM days WHERE account_id = ?", (me,)).fetchall():
                kept = filter_day(json.loads(row["payload"]), now_share)
                db.execute("UPDATE days SET payload = ? WHERE account_id = ? AND day = ?",
                           (json.dumps(kept, separators=(",", ":")), me, row["day"]))
    return me_json(account, req.device, req.now)


def h_avatar_put(app, req):
    kind = sniff_image(req.body)
    if kind is None:
        raise ApiError(415, "bad_image", "JPEG, PNG or WebP only.")
    db = app.db()
    with db:
        db.execute("UPDATE accounts SET avatar = ?, avatar_type = ?, avatar_rev = avatar_rev + 1 WHERE id = ?",
                   (req.body, kind, req.account["id"]))
        account = app.account(req.account["id"])
    return me_json(account, req.device, req.now)


def h_avatar_delete(app, req):
    db = app.db()
    with db:
        db.execute("UPDATE accounts SET avatar = NULL, avatar_type = NULL WHERE id = ?", (req.account["id"],))
    return 204, None


def _may_read(app, db, me, other):
    return me == other["id"] or app.are_friends(db, me, other["id"])


def h_user_avatar(app, req):
    db = app.db()
    other = app.account_by_pub(db, req.match.group(1))
    # A picture is shown only where a day is: to its owner and to friends.
    if not _may_read(app, db, req.account["id"], other) or not other["avatar"]:
        raise ApiError(404, "no_avatar", "")
    return 200, RawBody(other["avatar"], other["avatar_type"])


def h_day_put(app, req):
    app.limiter.check(("upload", req.key_id), 120, 3600)
    day = req.match.group(1)
    try:
        parsed = date.fromisoformat(day)
    except ValueError:
        raise ApiError(400, "bad_day", "")
    today = datetime.fromtimestamp(req.now, timezone.utc).date()
    if not today - timedelta(days=KEEP_DAYS) <= parsed <= today + timedelta(days=1):
        raise ApiError(400, "bad_day", "Outside the kept window.")
    payload = filter_day(clean_day(req.json(None), req.now), share_of(req.account))
    me = req.account["id"]
    db = app.db()
    with db:
        db.execute(
            "INSERT INTO days(account_id, day, payload, updated_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(account_id, day) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at",
            (me, day, json.dumps(payload, separators=(",", ":")), req.now))
        db.execute("DELETE FROM days WHERE account_id = ? AND day < ?",
                   (me, (today - timedelta(days=KEEP_DAYS)).isoformat()))
    return 204, None


def _days_since(req):
    try:
        days = int(req.query.get("days", ["7"])[0])
    except ValueError:
        raise ApiError(400, "bad_query", "days")
    days = max(1, min(FEED_MAX_DAYS, days))
    today = datetime.fromtimestamp(req.now, timezone.utc).date()
    return (today - timedelta(days=days)).isoformat()


def _days_of(db, account, share, since, with_series):
    rows = db.execute(
        "SELECT day, payload, updated_at FROM days WHERE account_id = ? AND day >= ? ORDER BY day DESC",
        (account["id"], since)).fetchall()
    out = []
    for row in rows:
        entry = filter_day(json.loads(row["payload"]), share)
        if not with_series and "hr" in entry:
            del entry["hr"]["series"]
        entry["day"] = row["day"]
        entry["updatedAt"] = row["updated_at"]
        out.append(entry)
    return out


def h_user_days(app, req):
    """One person's days in full, heart-rate line included: what a friend's own page draws."""
    db = app.db()
    other = app.account_by_pub(db, req.match.group(1))
    if not _may_read(app, db, req.account["id"], other):
        raise ApiError(404, "no_such_user", "")
    share = share_of(other)
    profile = public_profile(other)
    profile["share"] = share
    profile["days"] = _days_of(db, other, share, _days_since(req), with_series=True)
    return profile


def h_feed(app, req):
    """Everyone at a glance, and whatever waits for this account's answer. Carries the latest heart
    rate but not the day's line, which is the bulk of a day."""
    since = _days_since(req)
    db = app.db()
    with db:
        me = req.account
        mine = db.execute(
            "SELECT * FROM claims WHERE claimant_id = ? AND state = 'pending' ORDER BY id DESC LIMIT 1",
            (me["id"],)).fetchone()
        for strap in {me["strap"], mine["strap"] if mine else None} - {None}:
            app.settle(db, strap, req.now)
        me = app.account(me["id"])
        result = {"serverTime": req.now, "me": me_json(me, req.device, req.now), "friends": []}
        result["me"]["days"] = _days_of(db, me, share_of(me), since, with_series=False)
        friends = db.execute(
            "SELECT u.* FROM friendships f JOIN accounts u ON u.id = CASE WHEN f.a = ? THEN f.b ELSE f.a END "
            "WHERE f.a = ? OR f.b = ? ORDER BY u.name COLLATE NOCASE", (me["id"], me["id"], me["id"])).fetchall()
        for friend in friends:
            share = share_of(friend)
            profile = public_profile(friend)
            profile["share"] = share
            profile["days"] = _days_of(db, friend, share, since, with_series=False)
            result["friends"].append(profile)
        waiting = db.execute(
            "SELECT * FROM claims WHERE account_id = ? AND state = 'pending' ORDER BY id", (me["id"],)).fetchall()
        result["claims"] = [claim_json(db, claim) for claim in waiting]
        mine = db.execute(
            "SELECT * FROM claims WHERE claimant_id = ? AND state = 'pending' ORDER BY id DESC LIMIT 1",
            (me["id"],)).fetchone()
        result["strapClaim"] = claim_json(db, mine) if mine else None
        # Other phones of the account that got in by silence: the owner is told, and keeps or removes them.
        result["unconfirmed"] = [d for d in h_devices(app, req)["devices"]
                                 if d["probationUntil"] is not None and not d["current"]]
        return result
```

Change `PROBATION_OK` to:

```python
PROBATION_OK = {h_device_delete, h_day_put}
```

Add to `ROUTES`:

```python
    ("PATCH", r"/v2/me", h_me_patch, "device", MAX_JSON_BYTES, None),
    ("PUT", r"/v2/me/avatar", h_avatar_put, "device", MAX_AVATAR_BYTES, None),
    ("DELETE", r"/v2/me/avatar", h_avatar_delete, "device", 0, None),
    ("PUT", r"/v2/me/days/(\d{4}-\d{2}-\d{2})", h_day_put, "device", MAX_JSON_BYTES, None),
    ("GET", r"/v2/feed", h_feed, "device", 0, None),
    ("GET", r"/v2/users/([0-9a-f]{16})/days", h_user_days, "device", 0, None),
    ("GET", r"/v2/users/([0-9a-f]{16})/avatar", h_user_avatar, "device", 0, None),
```

- [ ] **Step 4: Run the tests**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `Ran 61 tests` and `OK`.

- [ ] **Step 5: Commit**

```bash
git add friends-server/server.py friends-server/test_server.py
git commit -m "feature: friends server profile, days and feed on account ids"
```

---

### Task 6: Export, deletion, the idle sweep, and the contract

**Files:**
- Modify: `friends-server/server.py`
- Modify: `friends-server/test_server.py`
- Rewrite: `friends-server/README.md`
- Modify: `friends-server/renoop-friends.service`

**Interfaces:**
- Consumes: everything above.
- Produces: `GET /v2/me/export`, `POST /v2/me/delete`; the README that plans 2 to 4 and the Android port read.

- [ ] **Step 1: Write the failing tests**

```python
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
        conn = sqlite3.connect(self.db_path)
        conn.execute("PRAGMA foreign_keys=ON")
        with conn:
            self.app.sweep_idle(conn, int(self.now))
        conn.close()
        self.assertEqual([(max_.id,)], self.rows("SELECT pub_id FROM accounts"))
        self.assertEqual(1, self.rows("SELECT COUNT(*) FROM devices")[0][0])
        self.assertEqual(401, anna.call("GET", "/v2/me")[0])

    def test_a_full_server_makes_room_by_forgetting_the_idle(self):
        self.app.max_users = 1
        self.enroll("Anna")
        self.advance(180 * DAY + 1)
        self.enroll("Max")
        self.assertEqual([("Max",)], self.rows("SELECT name FROM accounts"))
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `FAILED`; `404` on `/v2/me/export` and `/v2/me/delete`. The two sweep tests already pass.

- [ ] **Step 3: Add the handlers**

Insert after `h_feed`:

```python
def h_export(app, req):
    """Everything the server holds for the caller's account, as it is stored. The picture is given by
    its size, and the strap only as bound or not: its stored value is a keyed hash that means nothing
    outside this server."""
    me = req.account
    db = app.db()
    devices = h_devices(app, req)["devices"]
    friends = db.execute(
        "SELECT u.pub_id, u.name FROM friendships f JOIN accounts u ON u.id = CASE WHEN f.a = ? THEN f.b ELSE f.a END "
        "WHERE f.a = ? OR f.b = ? ORDER BY u.name COLLATE NOCASE", (me["id"], me["id"], me["id"])).fetchall()
    claims = db.execute(
        "SELECT * FROM claims WHERE account_id = ? OR claimant_id = ? ORDER BY id", (me["id"], me["id"])).fetchall()
    days = db.execute(
        "SELECT day, payload, updated_at FROM days WHERE account_id = ? ORDER BY day DESC", (me["id"],)).fetchall()
    out_days = []
    for row in days:
        entry = json.loads(row["payload"])
        entry["day"] = row["day"]
        entry["updatedAt"] = row["updated_at"]
        out_days.append(entry)
    return {
        "account": {"id": me["pub_id"], "name": me["name"], "share": share_of(me),
                    "avatarRev": me["avatar_rev"] if me["avatar"] else 0,
                    "avatarBytes": len(me["avatar"]) if me["avatar"] else 0,
                    "strapBound": me["strap"] is not None,
                    "createdAt": me["created_at"], "lastSeenAt": me["last_seen"]},
        "devices": devices,
        "friends": [{"id": f["pub_id"], "name": f["name"]} for f in friends],
        "invites": h_invites(app, req)["invites"],
        "claims": [claim_json(db, claim) for claim in claims],
        "days": out_days,
    }


def h_delete_account(app, req):
    db = app.db()
    with db:
        db.execute("DELETE FROM accounts WHERE id = ?", (req.account["id"],))
    return 204, None
```

Add to `ROUTES`:

```python
    ("GET", r"/v2/me/export", h_export, "device", 0, None),
    ("POST", r"/v2/me/delete", h_delete_account, "device", 0, None),
```

- [ ] **Step 4: Run the whole suite**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -4
```

Expected: `Ran 66 tests` and `OK`.

- [ ] **Step 5: Start the server by hand once**

```bash
cd friends-server && FRIENDS_DB=/tmp/friends-smoke.db FRIENDS_PORT=8799 .venv/bin/python server.py; echo "exit $?"
```

Expected: it exits at once with the message about `FRIENDS_STRAP_PEPPER` and `exit 1`. Then:

```bash
cd friends-server && (FRIENDS_DB=/tmp/friends-smoke.db FRIENDS_PORT=8799 FRIENDS_STRAP_PEPPER=0123456789abcdef0123456789abcdef .venv/bin/python server.py & sleep 1; curl -s http://127.0.0.1:8799/v2/info; echo; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8799/v2/me; curl -s http://127.0.0.1:8799/i/K7QM2-XRD4P | grep -c 'renoop://friends/add?c=K7QM2-XRD4P'; kill %1); rm -f /tmp/friends-smoke.db*
```

Expected: `{"name":"renoop-friends","api":2,"time":…}`, then `401`, then `1`.

- [ ] **Step 6: Rewrite the README**

Replace `friends-server/README.md` with:

````markdown
# reNOOP friends server

A small self-hosted service behind the reNOOP **Friends** tab: accounts with nothing to type, tied to
the strap; friends made by one-time invites; and one summary per day that each phone uploads after a
strap sync.

This is a reNOOP fork feature. Upstream NOOP has no accounts and no server, and nothing here is meant
to go upstream. The app works exactly as before without it: the Friends tab is the only screen that
talks to this service, and only after the wearer turns Friends on.

- Python 3.10+, one SQLite file, and one dependency: `cryptography` (Debian and Ubuntu:
  `apt install python3-cryptography`), used to check signatures.
- Listens on loopback. TLS is the reverse proxy's job; `deploy/` has the whole arrangement.
- `python3 -m unittest test_server` runs the suite against a real server on a temporary database.

## How a phone is known

There is no nickname and no password.

- **A phone holds an ECDSA P-256 key** that never leaves it (the Secure Enclave on Apple devices, the
  Keystore on Android) and signs every request. The server knows the public key. Nothing a proxy or a
  copy of the database holds can sign in as anyone.
- **An account is bound to a strap.** The phone sends a hash of the strap's serial, never the serial;
  the server stores that hash under its own secret key. One strap belongs to one account.
- **The strap is a way back, not a password.** A strap's serial can be read by anyone near it, so it
  never signs anyone in at once:
  - A new phone that presents a bound strap files a **claim**. A phone already on the account confirms
    it, and both show the same six-digit code.
  - If no confirmed phone of the account makes a request for **48 hours**, the claim is granted by
    itself and the new phone joins **on probation for 7 days**: it can read and upload its day, and
    change nothing else. A confirmed phone can remove it at any time.
- **A new strap on the same phone** takes the account over with one call and the old strap is let go.
  A strap still bound to someone else is asked for the same way: its owner releases it, or 48 hours
  of silence do.
- **Friends are made by one-time invites.** There is no directory, no search and no request to
  answer: no call finds or lists accounts.

## What it stores, and the rules it keeps

- **An account:** an opaque id, a display name, four sharing switches, an optional picture, and a
  keyed hash of the bound strap's serial hash. No e-mail, no phone number.
- **Per phone:** its public key, its platform (`ios`, `android` or `mac`), when it joined and when it
  was last heard from. Nothing else about the device.
- **Per day, per account:** the summary below, for the last 35 days. Nothing raw: no heart-rate
  stream, no RR intervals, no route, no journal.
- **A section that is switched off is not stored.** The server drops it from an upload, and turning a
  switch off erases that section from every day already uploaded. Turning it back on does not bring
  the erased figures back.
- **Only friends read a day or see a picture.** Unfriending cuts both directions at once.
- **An invite is stored as a hash** and its code is said once, when it is made.
- **`GET /v2/me/export` returns everything held for the caller's account.** The app shows it as it
  comes.
- **Deleting the account** removes the name, the picture, every day, every friendship, every phone,
  every invite and every claim in one transaction, and frees the strap.
- **An account not heard from for 180 days is deleted** the same way.
- The access log holds a method, a path and a status. Never a body, a key, a signature or an address;
  an invite code, an account id and a key id in a path are written as `…`.

## Signed requests

Every `/v2/` call except `GET /v2/info` carries:

| Header | Value |
|---|---|
| `X-Friends-Key` | key id: lower-case hex SHA-256 of the public key's SubjectPublicKeyInfo DER |
| `X-Friends-Time` | Unix seconds |
| `X-Friends-Nonce` | 16 to 64 characters of `[A-Za-z0-9_-]`, new for every request |
| `X-Friends-Signature` | base64 of the DER ECDSA-SHA256 signature over the signing string |

The signing string is six lines joined by `\n`, with no trailing newline:

```
renoop-friends-v2
<METHOD>
<request target: path and ?query exactly as sent, beginning /v2/>
<X-Friends-Time>
<X-Friends-Nonce>
<lower-case hex SHA-256 of the body; of nothing when there is none>
```

- The public key travels as base64 of its SubjectPublicKeyInfo DER, and only an uncompressed P-256
  point in its canonical encoding is accepted (`400 bad_key`).
- A time more than 300 s from the server's is `401 clock_skew` with `serverTime`; a client signs
  again with the difference applied.
- A nonce is good once per key (`401 replayed`). A key the server does not know is `401 unknown_key`
  before any signature is checked. A missing or malformed header is `401 unsigned`; a signature that
  does not verify is `401 bad_signature`.

### Vectors

A strap handle is lower-case hex SHA-256 of `"renoop-friends-strap-v1\n" + <adopted id>`. For
`whoop-4A0123456` it is `98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707`.

`PUT /v2/me/days/2026-10-10` at time `1791540000` with nonce `AAAAAAAAAAAAAAAAAAAAAA` and the body
`{"recovery":81}` is signed as:

```
renoop-friends-v2
PUT
/v2/me/days/2026-10-10
1791540000
AAAAAAAAAAAAAAAAAAAAAA
a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445
```

This signature over it verifies with this key (ECDSA is randomised, so verify it rather than expect
to reproduce it):

```
key        MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ/iGnmqlOeIlaOq9cf3gYSDRWcoyKOiHtx1m7TC7Wnr6CDeXUMf1QOVArjNKQgNPyKXVXw0/9N1Iyj8xadf1YA==
key id     b9310888608f332f9d712a19e65fd9a088948406f04240f82a4e50843774784a
signature  MEYCIQCbv6DGgAsKyGcPSjPiv6/b8i3IJZkUCmkxZt2/mg+nBgIhAPIfodgQQUa5TD0KWsCpCIKzIgtAwstLtonCzIHZLJMQ
```

## API (version 2)

JSON in, JSON out, UTF-8. An error is `{"error": "<code>", "message": "<text>"}` with a matching HTTP
status. `{id}` is an account id (16 hex), `{key}` a key id (64 hex), `{claimId}` a number.

| Method and path | Signed by | Body | Answer |
|---|---|---|---|
| `GET /healthz` | | | `{"ok": true}` |
| `GET /v2/info` | | | `{"name", "api": 2, "time"}` |
| `GET /i/{code}` | | | the invite page |
| `POST /v2/enroll` | the new key | `{"key", "name", "platform", "strap"?}` | `201 {"me"}`; `200 {"me"}` if the key is already a phone; `409 strap_bound` |
| `POST /v2/claims` | the new key | `{"key", "strap", "platform"}` | `201 {"claim"}`; `404 strap_free`; `409 already_enrolled`; `409 too_many_devices` |
| `GET /v2/claims/mine` | the claiming key | | `{"claim"}`; `404 no_claim` |
| `DELETE /v2/claims/mine` | the claiming key | | `204` |
| `POST /v2/claims/{claimId}/approve` | a phone | | `204`; `404 no_claim`; `409 claim_settled` |
| `POST /v2/claims/{claimId}/decline` | a phone | | `204` |
| `GET /v2/me` | a phone | | `me` |
| `PATCH /v2/me` | a phone | `{"name"?, "share"?: {"scores"?, "sleep"?, "workouts"?, "hr"?}}` | `me` |
| `PUT /v2/me/avatar` | a phone | raw JPEG, PNG or WebP, at most 200 KB | `me` |
| `DELETE /v2/me/avatar` | a phone | | `204` |
| `PUT /v2/me/strap` | a phone | `{"strap"}` | `200 {"bound": true}`; `202 {"claim"}` when the strap is bound elsewhere |
| `GET /v2/me/devices` | a phone | | `{"devices": [{"id", "platform", "addedAt", "lastSeenAt", "probationUntil", "current"}]}` |
| `DELETE /v2/me/devices/{key}` | a phone | | `204`; `409 last_device` for the only confirmed phone |
| `POST /v2/me/devices/{key}/trust` | a phone | | `204`, probation ended |
| `GET /v2/me/export` | a phone | | everything held for the account |
| `POST /v2/me/delete` | a phone | | `204` |
| `PUT /v2/me/days/{YYYY-MM-DD}` | a phone | a day (below) | `204`, replaces that day |
| `GET /v2/feed?days=7` | a phone | | below |
| `GET /v2/users/{id}/days?days=7` | a phone | | one friend (or yourself) in full, `hr.series` included |
| `GET /v2/users/{id}/avatar` | a phone | | the picture, for its owner and friends |
| `POST /v2/invites` | a phone | | `201 {"id", "code", "expiresAt"}` |
| `GET /v2/invites` | a phone | | `{"invites": [{"id", "createdAt", "expiresAt"}]}` |
| `DELETE /v2/invites/{inviteId}` | a phone | | `204` |
| `POST /v2/invites/redeem` | a phone | `{"code"}` | `200 {"friend"}`; `404 no_such_invite`; `400 own_invite` |
| `DELETE /v2/friends/{id}` | a phone | | `204` |

- `me`: `{"id", "name", "avatarRev", "share", "strapBound", "device": {"id", "probationUntil"}}`.
  `probationUntil` is null for a confirmed phone.
- `claim`: `{"id", "kind": "join" | "take", "code", "state", "platform", "createdAt", "maturesAt"}`.
  `state` is `pending`, `approved`, `declined` or `expired`. `maturesAt` is when silence alone will
  grant it, and moves forward each time a confirmed phone of the account is heard from.
- A person: `{"id", "name", "avatarRev"}`, plus `share` and `days` where a call returns them.
- **Feed:** `{"serverTime", "me": {…, "days"}, "friends": [{…, "share", "days"}], "claims": [claim],
  "strapClaim": claim | null, "unconfirmed": [device]}`, newest day first, 1 to 14 days, without
  `hr.series`. `claims` wait for this account's answer; `strapClaim` is this account's own request for
  a strap; `unconfirmed` are the account's other phones that joined by silence and are still on
  probation, in the shape `GET /v2/me/devices` gives.
- A phone on probation gets `403 probation` from every call that is not a `GET`, an upload of a day,
  or removing itself.
- A name is 1 to 40 characters. `strap` is a strap handle (64 hex). An invite code is ten characters
  of `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, shown as `XXXXX-XXXXX`; typed input may be lower-case, may
  drop the dash, and has `O` read as `0` and `I` or `L` as `1`. It is valid for 7 days and works once;
  between two people who are already friends it is left unused.

### A day

Every member is optional; a missing one is simply not shown to friends. Unknown members are a `400`.

```json
{
  "recovery": 81,
  "strain": 38.6,
  "sleepScore": 88,
  "sleep": {"startTs": 1791500000, "endTs": 1791528920, "asleepMin": 474,
            "awakeMin": 18, "remMin": 104, "lightMin": 252, "deepMin": 100, "needMin": 495},
  "workouts": [{"startTs": 1791530400, "sport": "Running", "durationS": 1860,
                "strain": 35.2, "avgHr": 139, "maxHr": 162, "kcal": 310}],
  "hr": {"lastBpm": 62, "lastTs": 1791540000, "restingBpm": 51,
         "series": [[1791539880, 64], [1791540000, 62]]}
}
```

- `recovery` and `sleepScore` are whole numbers 0 to 100.
- `strain` is on the app's stored **0 to 100** axis, for the day and for each workout. Each phone
  converts to the wearer's chosen display scale (100 or 21) when it draws.
- Timestamps are Unix seconds. The day in the path is the wearer's local calendar day.
- `sport` is the app's own sport label, at most 40 characters. At most 20 workouts a day.
- `hr.series` is a downsampled line for the day, at most 300 `[ts, bpm]` points.

The sharing switches map to members as: `scores` to `recovery`, `strain`, `sleepScore`; `sleep` to
`sleep`; `workouts` to `workouts`; `hr` to `hr`. New accounts share everything except `hr`.

In the feed each day also carries `day` and `updatedAt`, and each friend carries `share`, so the tab
can say "does not share sleep" instead of drawing an empty card.

## Limits

Per address: 600 requests a minute, 5 enrolments an hour, 10 join claims an hour. Per phone: 120
uploads, 20 invites and 10 redeem attempts an hour. Per account: 5 phones, 100 friends, 10 invites
waiting. Per strap: 3 claims waiting. 500 accounts per server unless `FRIENDS_MAX_USERS` says
otherwise. A claim nobody settles expires after 14 days; one that was declined cannot be filed again
for 7.

## Running it

```bash
FRIENDS_DB=/var/lib/renoop-friends/friends.db FRIENDS_STRAP_PEPPER=<secret> python3 server.py
```

| Variable | Default | Meaning |
|---|---|---|
| `FRIENDS_STRAP_PEPPER` | none, required | The key strap hashes are stored under, at least 32 characters. |
| `FRIENDS_DB` | `friends.db` | SQLite file |
| `FRIENDS_BIND` | `127.0.0.1` | Listen address. Keep it on loopback. |
| `FRIENDS_PORT` | `8787` | Listen port |
| `FRIENDS_MAX_USERS` | `500` | Accounts the server will hold |
| `FRIENDS_TRUST_PROXY` | `1` | Take the client address from the proxy's `X-Forwarded-For` |

Make the pepper once and keep a copy apart from the database:

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
```

If it is lost, accounts go on working (a phone is known by its key) but no stored strap matches any
more; each phone binds its strap again on its next sync, and a claim filed before then finds the
strap free.

`renoop-friends.service` runs the server as a throwaway user with the database in
`/var/lib/renoop-friends` and reads the pepper from `/etc/renoop-friends.env`.

`backup.py` with `renoop-friends-backup.service` and `.timer` writes a consistent copy to
`/var/backups/renoop-friends` every night and keeps the newest seven. Those copies sit on the same
disk, so they cover a damaged or emptied database, not a lost server. Never copy the live file by
hand while the server runs: it is only consistent through SQLite's backup call.

### Coming from version 1

Version 2 starts empty. The server refuses to open a version 1 database and changes nothing in it:
move the old file aside, install `python3-cryptography`, set `FRIENDS_STRAP_PEPPER`, and start.
Everyone turns Friends on again in the app and invites their friends again.
````

- [ ] **Step 7: Update the systemd unit's comment**

In `friends-server/renoop-friends.service`, replace the line

```
# Optional overrides, e.g. FRIENDS_INVITE_CODE=...
```

with

```
# Required here: FRIENDS_STRAP_PEPPER=<at least 32 characters>. Optional: FRIENDS_MAX_USERS=...
```

- [ ] **Step 8: Run the suite one last time and commit**

```bash
cd friends-server && .venv/bin/python -m unittest test_server 2>&1 | tail -3
```

Expected: `Ran 66 tests` and `OK`.

```bash
git add friends-server/server.py friends-server/test_server.py friends-server/README.md friends-server/renoop-friends.service
git commit -m "feature: friends server export, account deletion and the v2 contract"
```

---

## Done when

- `cd friends-server && .venv/bin/python -m unittest test_server` reports 66 tests, all passing.
- `grep -n -i -E 'nick|password|pw_hash|scrypt|/v1/' friends-server/server.py` prints nothing.
- The README's vectors are the ones in this directory's `README.md`.
