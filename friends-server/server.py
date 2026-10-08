#!/usr/bin/env python3
"""reNOOP friends server.

A small self-hosted service for the reNOOP "Friends" tab: nickname accounts, mutual friendships by
request, and a per-day summary each phone uploads after a strap sync. It holds only what a wearer
chose to share (see README.md for the API and the privacy rules). Standard library only, one SQLite
file, meant to sit behind a TLS-terminating reverse proxy on loopback.
"""

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

API_VERSION = 1

NICK_RE = re.compile(r"^[a-z0-9_]{3,20}$")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

MAX_JSON_BYTES = 64 * 1024
MAX_AVATAR_BYTES = 200 * 1024
MAX_SESSIONS_PER_USER = 10
MAX_FRIENDS = 100
MAX_PENDING_OUT = 50
MAX_WORKOUTS_PER_DAY = 20
MAX_HR_POINTS = 300
KEEP_DAYS = 35
FEED_MAX_DAYS = 14
MIN_TS = 1_500_000_000

# Which uploaded keys each sharing switch governs. A key outside this table is never stored.
SECTIONS = {
    "scores": ("recovery", "strain", "sleepScore"),
    "sleep": ("sleep",),
    "workouts": ("workouts",),
    "hr": ("hr",),
}
SHARE_COLUMNS = {"scores": "share_scores", "sleep": "share_sleep", "workouts": "share_workouts", "hr": "share_hr"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    nick TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    -- Unused since accounts lost their passwords; kept so a database made before that still opens.
    pw_salt BLOB NOT NULL DEFAULT x'',
    pw_hash BLOB NOT NULL DEFAULT x'',
    share_scores INTEGER NOT NULL DEFAULT 1,
    share_sleep INTEGER NOT NULL DEFAULT 1,
    share_workouts INTEGER NOT NULL DEFAULT 1,
    share_hr INTEGER NOT NULL DEFAULT 0,
    avatar BLOB,
    avatar_type TEXT,
    avatar_rev INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash BLOB PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS friendships (
    a INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    b INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (a, b),
    CHECK (a < b)
);
CREATE INDEX IF NOT EXISTS friendships_b ON friendships(b);
CREATE TABLE IF NOT EXISTS requests (
    from_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (from_id, to_id)
);
CREATE INDEX IF NOT EXISTS requests_to ON requests(to_id);
CREATE TABLE IF NOT EXISTS days (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day TEXT NOT NULL,
    payload TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, day)
);
"""


class ApiError(Exception):
    def __init__(self, status, code, message=""):
        super().__init__(code)
        self.status = status
        self.code = code
        self.message = message


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


def clean_nick(value):
    if not isinstance(value, str):
        raise ApiError(400, "bad_nick", "Nickname: 3 to 20 characters, a-z, 0-9 and _.")
    nick = value.strip().lstrip("@").lower()
    if not NICK_RE.match(nick):
        raise ApiError(400, "bad_nick", "Nickname: 3 to 20 characters, a-z, 0-9 and _.")
    return nick


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


# --- application --------------------------------------------------------------------------------


class App:
    def __init__(self, db_path, invite_code=None, max_users=500, trust_proxy=True):
        self.db_path = db_path
        self.invite_code = invite_code or None
        self.max_users = max_users
        self.trust_proxy = trust_proxy
        self.limiter = RateLimiter()
        self._local = threading.local()
        with self.db() as db:
            db.executescript(SCHEMA)

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

    # --- sessions ---

    def new_session(self, db, user_id):
        token = secrets.token_urlsafe(32)
        db.execute(
            "INSERT INTO sessions(token_hash, user_id, created_at) VALUES (?, ?, ?)",
            (hashlib.sha256(token.encode()).digest(), user_id, int(time.time())),
        )
        db.execute(
            "DELETE FROM sessions WHERE user_id = ? AND token_hash NOT IN "
            "(SELECT token_hash FROM sessions WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT ?)",
            (user_id, user_id, MAX_SESSIONS_PER_USER),
        )
        return token

    def user_for_token(self, token):
        if not token:
            return None
        return self.db().execute(
            "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
            (hashlib.sha256(token.encode()).digest(),),
        ).fetchone()

    # --- relations ---

    def are_friends(self, db, x, y):
        a, b = min(x, y), max(x, y)
        return db.execute("SELECT 1 FROM friendships WHERE a = ? AND b = ?", (a, b)).fetchone() is not None

    def relation(self, db, me, other):
        if me == other:
            return "self"
        if self.are_friends(db, me, other):
            return "friend"
        if db.execute("SELECT 1 FROM requests WHERE from_id = ? AND to_id = ?", (me, other)).fetchone():
            return "outgoing"
        if db.execute("SELECT 1 FROM requests WHERE from_id = ? AND to_id = ?", (other, me)).fetchone():
            return "incoming"
        return "none"

    def friend_count(self, db, user_id):
        return db.execute("SELECT COUNT(*) FROM friendships WHERE a = ? OR b = ?", (user_id, user_id)).fetchone()[0]

    def befriend(self, db, x, y):
        if self.friend_count(db, x) >= MAX_FRIENDS or self.friend_count(db, y) >= MAX_FRIENDS:
            raise ApiError(409, "too_many_friends", "Friend limit reached.")
        db.execute(
            "INSERT OR IGNORE INTO friendships(a, b, created_at) VALUES (?, ?, ?)",
            (min(x, y), max(x, y), int(time.time())),
        )
        db.execute("DELETE FROM requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)", (x, y, y, x))

    def user_by_nick(self, db, nick):
        user = db.execute("SELECT * FROM users WHERE nick = ?", (nick,)).fetchone()
        if user is None:
            raise ApiError(404, "no_such_user", "No one has this nickname.")
        return user


def share_of(user):
    return {section: bool(user[column]) for section, column in SHARE_COLUMNS.items()}


def public_profile(user):
    return {"nick": user["nick"], "name": user["name"], "avatarRev": user["avatar_rev"] if user["avatar"] else 0}


def own_profile(user):
    profile = public_profile(user)
    profile["share"] = share_of(user)
    return profile


# --- handlers -----------------------------------------------------------------------------------


def h_health(app, req):
    return {"ok": True}


def h_info(app, req):
    return {"name": "renoop-friends", "api": API_VERSION, "inviteRequired": app.invite_code is not None}


def h_register(app, req):
    app.limiter.check(("register", req.ip), 5, 3600)
    body = req.json(("nick", "name", "invite"))
    nick = clean_nick(body.get("nick"))
    # Sign-up asks for a nickname only; the display name starts as the nickname. There is no
    # password: the token this returns is the account's one credential, and the phone keeps it.
    name = clean_name(body["name"]) if body.get("name") is not None else nick
    if app.invite_code is not None:
        invite = body.get("invite")
        if not isinstance(invite, str) or not hmac.compare_digest(invite.encode(), app.invite_code.encode()):
            raise ApiError(403, "bad_invite", "Wrong invite code.")
    db = app.db()
    with db:
        if db.execute("SELECT COUNT(*) FROM users").fetchone()[0] >= app.max_users:
            raise ApiError(403, "server_full", "This server is not taking new accounts.")
        try:
            cur = db.execute(
                "INSERT INTO users(nick, name, pw_salt, pw_hash, created_at) VALUES (?, ?, x'', x'', ?)",
                (nick, name, int(time.time())),
            )
        except sqlite3.IntegrityError:
            raise ApiError(409, "nick_taken", "This nickname is taken.")
        token = app.new_session(db, cur.lastrowid)
        user = db.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    return 201, {"token": token, "me": own_profile(user)}


def h_nick_free(app, req):
    """Lets the sign-up form say whether a nickname is free before it is sent."""
    app.limiter.check(("nickfree", req.ip), 30, 60)
    nick = clean_nick(req.match.group(1))
    taken = app.db().execute("SELECT 1 FROM users WHERE nick = ?", (nick,)).fetchone() is not None
    return {"nick": nick, "free": not taken}


def h_logout(app, req):
    db = app.db()
    with db:
        db.execute("DELETE FROM sessions WHERE token_hash = ?", (hashlib.sha256(req.token.encode()).digest(),))
    return 204, None


def h_me(app, req):
    return own_profile(req.user)


def h_me_patch(app, req):
    body = req.json(("name", "share"))
    db = app.db()
    with db:
        if "name" in body:
            db.execute("UPDATE users SET name = ? WHERE id = ?", (clean_name(body["name"]), req.user["id"]))
        if "share" in body:
            share = body["share"]
            if not isinstance(share, dict) or not set(share) <= set(SHARE_COLUMNS):
                raise ApiError(400, "bad_payload", "share")
            for section, value in share.items():
                if not isinstance(value, bool):
                    raise ApiError(400, "bad_payload", "share." + section)
                db.execute("UPDATE users SET %s = ? WHERE id = ?" % SHARE_COLUMNS[section], (int(value), req.user["id"]))
        user = db.execute("SELECT * FROM users WHERE id = ?", (req.user["id"],)).fetchone()
        if "share" in body:
            # Switching a section off removes what was already uploaded, not only what friends see.
            now_share = share_of(user)
            for row in db.execute("SELECT day, payload FROM days WHERE user_id = ?", (user["id"],)).fetchall():
                kept = filter_day(json.loads(row["payload"]), now_share)
                db.execute(
                    "UPDATE days SET payload = ? WHERE user_id = ? AND day = ?",
                    (json.dumps(kept, separators=(",", ":")), user["id"], row["day"]),
                )
    return own_profile(user)


def h_delete_account(app, req):
    """The session is the proof: whoever holds the token owns the account, and there is nothing else to ask."""
    db = app.db()
    with db:
        db.execute("DELETE FROM users WHERE id = ?", (req.user["id"],))
    return 204, None


def h_avatar_put(app, req):
    kind = sniff_image(req.body)
    if kind is None:
        raise ApiError(415, "bad_image", "JPEG, PNG or WebP only.")
    db = app.db()
    with db:
        db.execute(
            "UPDATE users SET avatar = ?, avatar_type = ?, avatar_rev = avatar_rev + 1 WHERE id = ?",
            (req.body, kind, req.user["id"]),
        )
        user = db.execute("SELECT * FROM users WHERE id = ?", (req.user["id"],)).fetchone()
    return own_profile(user)


def h_avatar_delete(app, req):
    db = app.db()
    with db:
        db.execute("UPDATE users SET avatar = NULL, avatar_type = NULL WHERE id = ?", (req.user["id"],))
    return 204, None


def h_user(app, req):
    app.limiter.check(("lookup", req.user["id"]), 30, 60)
    db = app.db()
    other = app.user_by_nick(db, clean_nick(req.match.group(1)))
    profile = public_profile(other)
    profile["relation"] = app.relation(db, req.user["id"], other["id"])
    return profile


def h_user_avatar(app, req):
    db = app.db()
    other = app.user_by_nick(db, clean_nick(req.match.group(1)))
    # A picture is shown only where a name already is: to its owner, to friends, and across a pending request.
    if app.relation(db, req.user["id"], other["id"]) == "none" or not other["avatar"]:
        raise ApiError(404, "no_avatar", "")
    return 200, RawBody(other["avatar"], other["avatar_type"])


def h_requests(app, req):
    db = app.db()
    incoming = db.execute(
        "SELECT u.*, r.created_at AS requested_at FROM requests r JOIN users u ON u.id = r.from_id "
        "WHERE r.to_id = ? ORDER BY r.created_at DESC", (req.user["id"],)).fetchall()
    outgoing = db.execute(
        "SELECT u.*, r.created_at AS requested_at FROM requests r JOIN users u ON u.id = r.to_id "
        "WHERE r.from_id = ? ORDER BY r.created_at DESC", (req.user["id"],)).fetchall()

    def item(row):
        profile = public_profile(row)
        profile["requestedAt"] = row["requested_at"]
        return profile

    return {"incoming": [item(r) for r in incoming], "outgoing": [item(r) for r in outgoing]}


def h_request_send(app, req):
    app.limiter.check(("request", req.user["id"]), 20, 3600)
    body = req.json(("nick",))
    db = app.db()
    me = req.user["id"]
    with db:
        other = app.user_by_nick(db, clean_nick(body.get("nick")))
        relation = app.relation(db, me, other["id"])
        if relation == "self":
            raise ApiError(400, "self_request", "That is your own nickname.")
        if relation == "incoming":
            # They already asked: answering with a request of your own is an acceptance.
            app.befriend(db, me, other["id"])
            relation = "friend"
        elif relation == "none":
            pending = db.execute("SELECT COUNT(*) FROM requests WHERE from_id = ?", (me,)).fetchone()[0]
            if pending >= MAX_PENDING_OUT:
                raise ApiError(409, "too_many_requests", "Too many unanswered requests.")
            db.execute(
                "INSERT INTO requests(from_id, to_id, created_at) VALUES (?, ?, ?)", (me, other["id"], int(time.time())))
            relation = "outgoing"
    profile = public_profile(other)
    profile["relation"] = relation
    return profile


def h_request_accept(app, req):
    db = app.db()
    me = req.user["id"]
    with db:
        other = app.user_by_nick(db, clean_nick(req.match.group(1)))
        if app.relation(db, me, other["id"]) != "incoming":
            raise ApiError(404, "no_request", "No request from this person.")
        app.befriend(db, me, other["id"])
    profile = public_profile(other)
    profile["relation"] = "friend"
    return profile


def h_request_delete(app, req):
    """Declines an incoming request or withdraws an outgoing one. The other side is not told which."""
    db = app.db()
    me = req.user["id"]
    with db:
        other = app.user_by_nick(db, clean_nick(req.match.group(1)))
        db.execute(
            "DELETE FROM requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)",
            (me, other["id"], other["id"], me),
        )
    return 204, None


def h_unfriend(app, req):
    db = app.db()
    me = req.user["id"]
    with db:
        other = app.user_by_nick(db, clean_nick(req.match.group(1)))
        db.execute("DELETE FROM friendships WHERE a = ? AND b = ?", (min(me, other["id"]), max(me, other["id"])))
    return 204, None


def h_day_put(app, req):
    app.limiter.check(("upload", req.user["id"]), 120, 3600)
    day = req.match.group(1)
    now = int(time.time())
    try:
        parsed = date.fromisoformat(day)
    except ValueError:
        raise ApiError(400, "bad_day", "")
    today = datetime.fromtimestamp(now, timezone.utc).date()
    if not today - timedelta(days=KEEP_DAYS) <= parsed <= today + timedelta(days=1):
        raise ApiError(400, "bad_day", "Outside the kept window.")
    body = req.json(None)
    payload = filter_day(clean_day(body, now), share_of(req.user))
    db = app.db()
    with db:
        db.execute(
            "INSERT INTO days(user_id, day, payload, updated_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(user_id, day) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at",
            (req.user["id"], day, json.dumps(payload, separators=(",", ":")), now),
        )
        db.execute(
            "DELETE FROM days WHERE user_id = ? AND day < ?",
            (req.user["id"], (today - timedelta(days=KEEP_DAYS)).isoformat()),
        )
    return 204, None


def _days_query(req):
    try:
        days = int(req.query.get("days", ["7"])[0])
    except ValueError:
        raise ApiError(400, "bad_query", "days")
    days = max(1, min(FEED_MAX_DAYS, days))
    today = datetime.fromtimestamp(time.time(), timezone.utc).date()
    return (today - timedelta(days=days)).isoformat()


def _days_of(db, user, share, since, with_series):
    rows = db.execute(
        "SELECT day, payload, updated_at FROM days WHERE user_id = ? AND day >= ? ORDER BY day DESC",
        (user["id"], since),
    ).fetchall()
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
    other = app.user_by_nick(db, clean_nick(req.match.group(1)))
    if app.relation(db, req.user["id"], other["id"]) not in ("self", "friend"):
        raise ApiError(404, "no_such_user", "No one has this nickname.")
    share = share_of(other)
    profile = public_profile(other)
    profile["share"] = share
    profile["days"] = _days_of(db, other, share, _days_query(req), with_series=True)
    return profile


def h_feed(app, req):
    """Everyone at a glance. Carries the latest heart rate but not the day's line, which is the bulk of a day."""
    since = _days_query(req)
    db = app.db()
    me = req.user

    def days_of(user, share):
        return _days_of(db, user, share, since, with_series=False)

    friends = db.execute(
        "SELECT u.* FROM friendships f JOIN users u ON u.id = CASE WHEN f.a = ? THEN f.b ELSE f.a END "
        "WHERE f.a = ? OR f.b = ? ORDER BY u.name COLLATE NOCASE",
        (me["id"], me["id"], me["id"]),
    ).fetchall()
    result = {"serverTime": int(time.time()), "me": own_profile(me), "friends": []}
    result["me"]["days"] = days_of(me, share_of(me))
    for friend in friends:
        share = share_of(friend)
        profile = public_profile(friend)
        profile["share"] = share
        profile["days"] = days_of(friend, share)
        result["friends"].append(profile)
    result["pendingIncoming"] = db.execute("SELECT COUNT(*) FROM requests WHERE to_id = ?", (me["id"],)).fetchone()[0]
    return result


class RawBody:
    def __init__(self, data, content_type):
        self.data = data
        self.content_type = content_type


# (method, path pattern, handler, needs a session, largest accepted body)
ROUTES = [
    ("GET", r"/healthz", h_health, False, 0),
    ("GET", r"/v1/info", h_info, False, 0),
    ("POST", r"/v1/register", h_register, False, MAX_JSON_BYTES),
    ("GET", r"/v1/nicks/([^/]{1,40})", h_nick_free, False, 0),
    ("DELETE", r"/v1/session", h_logout, True, 0),
    ("GET", r"/v1/me", h_me, True, 0),
    ("PATCH", r"/v1/me", h_me_patch, True, MAX_JSON_BYTES),
    ("POST", r"/v1/me/delete", h_delete_account, True, 0),
    ("PUT", r"/v1/me/avatar", h_avatar_put, True, MAX_AVATAR_BYTES),
    ("DELETE", r"/v1/me/avatar", h_avatar_delete, True, 0),
    ("PUT", r"/v1/me/days/(\d{4}-\d{2}-\d{2})", h_day_put, True, MAX_JSON_BYTES),
    ("GET", r"/v1/feed", h_feed, True, 0),
    ("GET", r"/v1/users/([^/]{1,40})", h_user, True, 0),
    ("GET", r"/v1/users/([^/]{1,40})/avatar", h_user_avatar, True, 0),
    ("GET", r"/v1/users/([^/]{1,40})/days", h_user_days, True, 0),
    ("GET", r"/v1/friends/requests", h_requests, True, 0),
    ("POST", r"/v1/friends/requests", h_request_send, True, MAX_JSON_BYTES),
    ("POST", r"/v1/friends/requests/([^/]{1,40})/accept", h_request_accept, True, 0),
    ("DELETE", r"/v1/friends/requests/([^/]{1,40})", h_request_delete, True, 0),
    ("DELETE", r"/v1/friends/([^/]{1,40})", h_unfriend, True, 0),
]
ROUTES = [(m, re.compile("^" + p + "$"), h, auth, size) for m, p, h, auth, size in ROUTES]


class Request:
    def __init__(self, match, query, body, ip, token, user):
        self.match = match
        self.query = query
        self.body = body
        self.ip = ip
        self.token = token
        self.user = user

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
        if isinstance(payload, RawBody):
            data, content_type = payload.data, payload.content_type
        elif payload is None:
            data, content_type = b"", None
        else:
            data, content_type = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), "application/json"
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if data and self.command != "HEAD":
            self.wfile.write(data)

    def _handle(self):
        app = self.server.app
        url = urlparse(self.path)
        body_read = False
        try:
            ip = self._client_ip()
            app.limiter.check(("all", ip), 600, 60)
            route = None
            path_known = False
            for method, pattern, handler, needs_auth, max_body in ROUTES:
                match = pattern.match(url.path)
                if match:
                    path_known = True
                    if method == self.command:
                        route = (handler, needs_auth, max_body, match)
                        break
            if route is None:
                raise ApiError(405 if path_known else 404, "method_not_allowed" if path_known else "not_found", "")
            handler, needs_auth, max_body, match = route
            if self.headers.get("Transfer-Encoding"):
                raise ApiError(411, "length_required", "")
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                raise ApiError(400, "bad_length", "")
            if length < 0 or length > max_body:
                raise ApiError(413, "too_large", "")
            body = self.rfile.read(length) if length else b""
            body_read = True
            token = None
            auth = self.headers.get("Authorization", "")
            if auth.startswith("Bearer "):
                token = auth[7:].strip()
            user = app.user_for_token(token) if needs_auth else None
            if needs_auth and user is None:
                raise ApiError(401, "unauthorized", "Sign in again.")
            result = handler(app, Request(match, parse_qs(url.query), body, ip, token, user))
            status, payload = result if isinstance(result, tuple) else (200, result)
            self._send(status, payload)
        except ApiError as err:
            if not body_read:
                # Refused before its body was read: the unread bytes must not be parsed as a next request.
                self.close_connection = True
            self._send(err.status, {"error": err.code, "message": err.message})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception as err:  # noqa: BLE001 - one request must never take the server down
            sys.stderr.write("internal error on %s %s: %r\n" % (self.command, url.path, err))
            self._send(500, {"error": "internal", "message": ""})

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _handle

    def finish(self):
        # One thread serves one connection, so its database handle ends with it.
        self.server.app.close_db()
        super().finish()

    def log_message(self, fmt, *args):
        # Method, path without its query, status. Never a body, a token or an address.
        if self.server.app_quiet:
            return
        status = args[1] if len(args) > 1 else "-"
        sys.stderr.write("%s %s %s\n" % (self.command, urlparse(self.path).path, status))


def make_server(app, host="127.0.0.1", port=8787, quiet=False):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.app = app
    server.app_quiet = quiet
    return server


def main():
    db_path = os.environ.get("FRIENDS_DB", "friends.db")
    host = os.environ.get("FRIENDS_BIND", "127.0.0.1")
    port = int(os.environ.get("FRIENDS_PORT", "8787"))
    app = App(
        db_path,
        invite_code=os.environ.get("FRIENDS_INVITE_CODE"),
        max_users=int(os.environ.get("FRIENDS_MAX_USERS", "500")),
        trust_proxy=os.environ.get("FRIENDS_TRUST_PROXY", "1") == "1",
    )
    server = make_server(app, host, port)
    sys.stderr.write("renoop-friends listening on %s:%d, database %s\n" % (host, port, db_path))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
