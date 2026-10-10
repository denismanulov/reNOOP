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


class RateLimiter:
    """Sliding-window counters held in memory. A restart forgets them, which only ever loosens a limit."""

    def __init__(self):
        self._hits = {}
        self._lock = threading.Lock()

    def check(self, key, limit, window_s):
        now = time.monotonic()
        with self._lock:
            hits = self._hits.get(key)
            if hits is None:
                if len(self._hits) > 50_000:
                    self._hits = {k: v for k, v in self._hits.items() if v and v[-1] > now - 3600}
                hits = self._hits[key] = collections.deque()
            while hits and hits[0] <= now - window_s:
                hits.popleft()
            if len(hits) >= limit:
                raise ApiError(429, "rate_limited", "Too many attempts. Try again later.")
            hits.append(now)


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

def _bad(path):
    return ApiError(400, "bad_payload", path)


def _int(value, lo, hi, path, nullable=True):
    if value is None and nullable:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
        raise _bad(path)
    return value


def _num(value, lo, hi, path, nullable=True):
    if value is None and nullable:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value or not lo <= value <= hi:
        raise _bad(path)
    return round(float(value), 2)


def _obj(value, allowed, path):
    if not isinstance(value, dict) or not set(value) <= set(allowed):
        raise _bad(path)
    return value


def clean_day(raw, now_ts):
    """The whitelisted, range-checked form of one uploaded day. Anything unexpected is a 400, never stored."""
    _obj(raw, ("recovery", "strain", "sleepScore", "sleep", "workouts", "hr"), "$")
    max_ts = now_ts + 2 * 86400
    out = {}
    if "recovery" in raw:
        out["recovery"] = _int(raw["recovery"], 0, 100, "recovery")
    if "strain" in raw:
        out["strain"] = _num(raw["strain"], 0, 100, "strain")
    if "sleepScore" in raw:
        out["sleepScore"] = _int(raw["sleepScore"], 0, 100, "sleepScore")
    if raw.get("sleep") is not None:
        s = _obj(raw["sleep"], ("startTs", "endTs", "asleepMin", "awakeMin", "remMin", "lightMin", "deepMin", "needMin"), "sleep")
        sleep = {
            "startTs": _int(s.get("startTs"), MIN_TS, max_ts, "sleep.startTs", nullable=False),
            "endTs": _int(s.get("endTs"), MIN_TS, max_ts, "sleep.endTs", nullable=False),
            "asleepMin": _int(s.get("asleepMin"), 0, 1440, "sleep.asleepMin", nullable=False),
        }
        if sleep["endTs"] <= sleep["startTs"]:
            raise _bad("sleep.endTs")
        for key in ("awakeMin", "remMin", "lightMin", "deepMin", "needMin"):
            sleep[key] = _int(s.get(key), 0, 1440, "sleep." + key)
        out["sleep"] = sleep
    if raw.get("workouts") is not None:
        items = raw["workouts"]
        if not isinstance(items, list) or len(items) > MAX_WORKOUTS_PER_DAY:
            raise _bad("workouts")
        workouts = []
        for i, w in enumerate(items):
            p = "workouts[%d]." % i
            _obj(w, ("startTs", "sport", "durationS", "strain", "avgHr", "maxHr", "kcal"), p)
            sport = w.get("sport")
            if not isinstance(sport, str) or not 1 <= len(sport) <= 40 or not sport.isprintable():
                raise _bad(p + "sport")
            workouts.append({
                "startTs": _int(w.get("startTs"), MIN_TS, max_ts, p + "startTs", nullable=False),
                "sport": sport,
                "durationS": _int(w.get("durationS"), 0, 86400, p + "durationS", nullable=False),
                "strain": _num(w.get("strain"), 0, 100, p + "strain"),
                "avgHr": _int(w.get("avgHr"), 20, 250, p + "avgHr"),
                "maxHr": _int(w.get("maxHr"), 20, 250, p + "maxHr"),
                "kcal": _int(w.get("kcal"), 0, 20000, p + "kcal"),
            })
        out["workouts"] = workouts
    if raw.get("hr") is not None:
        h = _obj(raw["hr"], ("lastBpm", "lastTs", "restingBpm", "series"), "hr")
        series = h.get("series") or []
        if not isinstance(series, list) or len(series) > MAX_HR_POINTS:
            raise _bad("hr.series")
        points = []
        for i, point in enumerate(series):
            if not isinstance(point, list) or len(point) != 2:
                raise _bad("hr.series[%d]" % i)
            points.append([
                _int(point[0], MIN_TS, max_ts, "hr.series[%d][0]" % i, nullable=False),
                _int(point[1], 20, 250, "hr.series[%d][1]" % i, nullable=False),
            ])
        out["hr"] = {
            "lastBpm": _int(h.get("lastBpm"), 20, 250, "hr.lastBpm", nullable=False),
            "lastTs": _int(h.get("lastTs"), MIN_TS, max_ts, "hr.lastTs", nullable=False),
            "restingBpm": _int(h.get("restingBpm"), 20, 250, "hr.restingBpm"),
            "series": points,
        }
    return out


def filter_day(payload, share):
    """Drops every section whose sharing switch is off."""
    allowed = set()
    for section, keys in SECTIONS.items():
        if share[section]:
            allowed.update(keys)
    return {k: v for k, v in payload.items() if k in allowed}


def clean_name(value):
    if not isinstance(value, str):
        raise ApiError(400, "bad_name", "Name: 1 to 40 characters.")
    name = "".join(ch for ch in unicodedata.normalize("NFC", value) if ch.isprintable()).strip()
    if not 1 <= len(name) <= 40:
        raise ApiError(400, "bad_name", "Name: 1 to 40 characters.")
    return name


def sniff_image(data):
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


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


class RawBody:
    def __init__(self, data, content_type, headers=None):
        self.data = data
        self.content_type = content_type
        self.headers = headers or {}


# The calls other than reads that a device on probation may make. Every other change waits.
PROBATION_OK = {h_device_delete, h_day_put}

# (method, path pattern, handler, who signs, largest accepted body, per-address limit before any signature maths)
ROUTES = [
    ("GET", r"/healthz", h_health, "none", 0, None),
    ("GET", r"/v2/info", h_info, "none", 0, None),
    ("POST", r"/v2/enroll", h_enroll, "newkey", MAX_JSON_BYTES, ("enroll", 5, 3600)),
    ("GET", r"/v2/me", h_me, "device", 0, None),
    ("PUT", r"/v2/me/strap", h_strap_put, "device", MAX_JSON_BYTES, None),
    ("POST", r"/v2/claims", h_claim_file, "newkey", MAX_JSON_BYTES, ("claim", 10, 3600)),
    ("GET", r"/v2/claims/mine", h_claim_mine, "claimant", 0, None),
    ("DELETE", r"/v2/claims/mine", h_claim_withdraw, "claimant", 0, None),
    ("POST", r"/v2/claims/([0-9]{1,12})/approve", h_claim_approve, "device", 0, None),
    ("POST", r"/v2/claims/([0-9]{1,12})/decline", h_claim_decline, "device", 0, None),
    ("GET", r"/v2/me/devices", h_devices, "device", 0, None),
    ("DELETE", r"/v2/me/devices/([0-9a-f]{64})", h_device_delete, "device", 0, None),
    ("POST", r"/v2/me/devices/([0-9a-f]{64})/trust", h_device_trust, "device", 0, None),
    ("GET", r"/i/([0-9A-Za-z-]{10,11})", h_invite_page, "none", 0, None),
    ("POST", r"/v2/invites", h_invite_create, "device", 0, None),
    ("GET", r"/v2/invites", h_invites, "device", 0, None),
    ("DELETE", r"/v2/invites/([0-9a-f]{16})", h_invite_revoke, "device", 0, None),
    ("POST", r"/v2/invites/redeem", h_invite_redeem, "device", MAX_JSON_BYTES, None),
    ("DELETE", r"/v2/friends/([0-9a-f]{16})", h_unfriend, "device", 0, None),
    ("PATCH", r"/v2/me", h_me_patch, "device", MAX_JSON_BYTES, None),
    ("PUT", r"/v2/me/avatar", h_avatar_put, "device", MAX_AVATAR_BYTES, None),
    ("DELETE", r"/v2/me/avatar", h_avatar_delete, "device", 0, None),
    ("PUT", r"/v2/me/days/(\d{4}-\d{2}-\d{2})", h_day_put, "device", MAX_JSON_BYTES, None),
    ("GET", r"/v2/feed", h_feed, "device", 0, None),
    ("GET", r"/v2/users/([0-9a-f]{16})/days", h_user_days, "device", 0, None),
    ("GET", r"/v2/users/([0-9a-f]{16})/avatar", h_user_avatar, "device", 0, None),
    ("GET", r"/v2/me/export", h_export, "device", 0, None),
    ("POST", r"/v2/me/delete", h_delete_account, "device", 0, None),
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
