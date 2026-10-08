"""End-to-end tests: a real server on an ephemeral port, a temporary database, plain HTTP calls."""

import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

import server


class Client:
    def __init__(self, base, token=None):
        self.base = base
        self.token = token

    def call(self, method, path, body=None, raw=None, headers=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                payload = resp.read()
                kind = resp.headers.get("Content-Type", "")
                return resp.status, (json.loads(payload) if kind.startswith("application/json") else payload)
        except urllib.error.HTTPError as err:
            with err:
                payload = err.read()
            return err.code, (json.loads(payload) if payload else None)


class FriendsServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = server.App(os.path.join(self.tmp.name, "t.db"), trust_proxy=True)
        self.httpd = server.make_server(self.app, "127.0.0.1", 0, quiet=True)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.anon = Client(self.base)
        self._ip = 0

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.app.close_db()
        self.tmp.cleanup()

    def signup(self, nick, name=None, password="correct horse"):
        # Each sign-up arrives from its own address, as it would through the proxy, so the
        # per-address sign-up limit is tested where it is meant to bite and nowhere else.
        self._ip += 1
        status, body = self.anon.call(
            "POST", "/v1/register", {"nick": nick, "name": name or nick.title(), "password": password},
            headers={"X-Forwarded-For": "10.0.0.%d" % self._ip})
        self.assertEqual(201, status, body)
        return Client(self.base, body["token"])

    def befriend(self, a, a_nick, b, b_nick):
        self.assertEqual(200, a.call("POST", "/v1/friends/requests", {"nick": b_nick})[0])
        self.assertEqual(200, b.call("POST", "/v1/friends/requests/%s/accept" % a_nick)[0])

    def today(self):
        return time.strftime("%Y-%m-%d", time.gmtime())

    def full_day(self):
        now = int(time.time())
        return {
            "recovery": 81, "strain": 38.6, "sleepScore": 88,
            "sleep": {"startTs": now - 30000, "endTs": now - 1000, "asleepMin": 474, "awakeMin": 18,
                      "remMin": 104, "lightMin": 252, "deepMin": 100, "needMin": 495},
            "workouts": [{"startTs": now - 900, "sport": "Running", "durationS": 1860, "strain": 35.2,
                          "avgHr": 139, "maxHr": 162, "kcal": 310}],
            "hr": {"lastBpm": 62, "lastTs": now - 60, "restingBpm": 51, "series": [[now - 120, 64], [now - 60, 62]]},
        }

    # --- accounts ---

    def test_register_login_and_profile(self):
        ruslan = self.signup("ruslan", "Руслан")
        status, me = ruslan.call("GET", "/v1/me")
        self.assertEqual(200, status)
        self.assertEqual({"nick": "ruslan", "name": "Руслан", "avatarRev": 0,
                          "share": {"scores": True, "sleep": True, "workouts": True, "hr": False}}, me)
        status, body = self.anon.call("POST", "/v1/login", {"nick": "@Ruslan", "password": "correct horse"})
        self.assertEqual(200, status)
        self.assertEqual(200, Client(self.base, body["token"]).call("GET", "/v1/me")[0])

    def test_wrong_password_and_unknown_nick_read_the_same(self):
        self.signup("ruslan")
        wrong = self.anon.call("POST", "/v1/login", {"nick": "ruslan", "password": "not the one"})
        missing = self.anon.call("POST", "/v1/login", {"nick": "nobody", "password": "not the one"})
        self.assertEqual((401, "bad_credentials"), (wrong[0], wrong[1]["error"]))
        self.assertEqual(wrong, missing)

    def test_nick_rules_and_uniqueness(self):
        self.signup("ruslan")
        for bad in ("ab", "has space", "кириллица", "x" * 21):
            status, body = self.anon.call("POST", "/v1/register", {"nick": bad, "name": "N", "password": "12345678"})
            self.assertEqual((400, "bad_nick"), (status, body["error"]), bad)
        status, body = self.anon.call("POST", "/v1/register", {"nick": "RUSLAN", "name": "N", "password": "12345678"})
        self.assertEqual((409, "nick_taken"), (status, body["error"]))
        self.assertEqual({"nick": "ruslan", "free": False}, self.anon.call("GET", "/v1/nicks/ruslan")[1])
        self.assertEqual({"nick": "denchik", "free": True}, self.anon.call("GET", "/v1/nicks/denchik")[1])

    def test_short_password_refused(self):
        status, body = self.anon.call("POST", "/v1/register", {"nick": "ruslan", "name": "R", "password": "1234567"})
        self.assertEqual((400, "bad_password"), (status, body["error"]))

    def test_everything_but_sign_in_needs_a_session(self):
        for method, path in (("GET", "/v1/me"), ("GET", "/v1/feed"), ("GET", "/v1/users/ruslan"),
                             ("PUT", "/v1/me/days/" + self.today()), ("GET", "/v1/friends/requests")):
            self.assertEqual(401, self.anon.call(method, path, {} if method == "PUT" else None)[0], path)
        self.assertEqual(401, Client(self.base, "made-up-token").call("GET", "/v1/me")[0])

    def test_logout_ends_only_that_session(self):
        first = self.signup("ruslan")
        second = Client(self.base, self.anon.call("POST", "/v1/login", {"nick": "ruslan", "password": "correct horse"})[1]["token"])
        self.assertEqual(204, first.call("DELETE", "/v1/session")[0])
        self.assertEqual(401, first.call("GET", "/v1/me")[0])
        self.assertEqual(200, second.call("GET", "/v1/me")[0])

    def test_password_change_signs_other_phones_out(self):
        phone = self.signup("ruslan")
        other = Client(self.base, self.anon.call("POST", "/v1/login", {"nick": "ruslan", "password": "correct horse"})[1]["token"])
        self.assertEqual(401, phone.call("POST", "/v1/me/password", {"old": "nope nope", "new": "new password"})[0])
        status, body = phone.call("POST", "/v1/me/password", {"old": "correct horse", "new": "new password"})
        self.assertEqual(200, status)
        self.assertEqual(401, other.call("GET", "/v1/me")[0])
        self.assertEqual(401, phone.call("GET", "/v1/me")[0])
        self.assertEqual(200, Client(self.base, body["token"]).call("GET", "/v1/me")[0])
        self.assertEqual(200, self.anon.call("POST", "/v1/login", {"nick": "ruslan", "password": "new password"})[0])

    def test_invite_code_gates_sign_up_when_set(self):
        self.app.invite_code = "let-me-in"
        self.assertTrue(self.anon.call("GET", "/v1/info")[1]["inviteRequired"])
        body = {"nick": "ruslan", "name": "R", "password": "12345678"}
        self.assertEqual(403, self.anon.call("POST", "/v1/register", body)[0])
        self.assertEqual(403, self.anon.call("POST", "/v1/register", dict(body, invite="guess"))[0])
        self.assertEqual(201, self.anon.call("POST", "/v1/register", dict(body, invite="let-me-in"))[0])

    def test_sign_up_is_limited_per_address(self):
        codes = [self.anon.call("POST", "/v1/register", {"nick": "user_%d" % i, "name": "U", "password": "12345678"},
                                headers={"X-Forwarded-For": "203.0.113.9"})[0] for i in range(6)]
        self.assertEqual([201] * 5 + [429], codes)

    def test_login_is_limited_per_nick(self):
        self.signup("ruslan")
        codes = [self.anon.call("POST", "/v1/login", {"nick": "ruslan", "password": "guess %d" % i},
                                headers={"X-Forwarded-For": "198.51.100.%d" % i})[0] for i in range(11)]
        self.assertEqual([401] * 10 + [429], codes)

    # --- friends ---

    def test_request_accept_and_relations(self):
        ruslan, denis = self.signup("ruslan"), self.signup("denchik", "Денис")
        self.assertEqual("none", ruslan.call("GET", "/v1/users/denchik")[1]["relation"])
        self.assertEqual("outgoing", ruslan.call("POST", "/v1/friends/requests", {"nick": "@denchik"})[1]["relation"])
        self.assertEqual("incoming", denis.call("GET", "/v1/users/ruslan")[1]["relation"])
        inbox = denis.call("GET", "/v1/friends/requests")[1]
        self.assertEqual(["ruslan"], [r["nick"] for r in inbox["incoming"]])
        self.assertEqual(["denchik"], [r["nick"] for r in ruslan.call("GET", "/v1/friends/requests")[1]["outgoing"]])
        self.assertEqual(1, denis.call("GET", "/v1/feed")[1]["pendingIncoming"])
        self.assertEqual("friend", denis.call("POST", "/v1/friends/requests/ruslan/accept")[1]["relation"])
        self.assertEqual("friend", ruslan.call("GET", "/v1/users/denchik")[1]["relation"])
        self.assertEqual({"incoming": [], "outgoing": []}, denis.call("GET", "/v1/friends/requests")[1])

    def test_two_crossing_requests_make_a_friendship(self):
        ruslan, denis = self.signup("ruslan"), self.signup("denchik")
        ruslan.call("POST", "/v1/friends/requests", {"nick": "denchik"})
        self.assertEqual("friend", denis.call("POST", "/v1/friends/requests", {"nick": "ruslan"})[1]["relation"])

    def test_decline_withdraw_and_no_self_request(self):
        ruslan, denis = self.signup("ruslan"), self.signup("denchik")
        self.assertEqual(400, ruslan.call("POST", "/v1/friends/requests", {"nick": "ruslan"})[0])
        self.assertEqual(404, ruslan.call("POST", "/v1/friends/requests", {"nick": "nobody_here"})[0])
        ruslan.call("POST", "/v1/friends/requests", {"nick": "denchik"})
        self.assertEqual(204, denis.call("DELETE", "/v1/friends/requests/ruslan")[0])
        self.assertEqual("none", ruslan.call("GET", "/v1/users/denchik")[1]["relation"])
        self.assertEqual(404, denis.call("POST", "/v1/friends/requests/ruslan/accept")[0])

    def test_a_stranger_sees_nothing_and_a_friend_sees_only_what_is_shared(self):
        ruslan, denis, misha = self.signup("ruslan"), self.signup("denchik"), self.signup("mishka")
        self.assertEqual(204, denis.call("PUT", "/v1/me/days/" + self.today(), self.full_day())[0])
        self.befriend(ruslan, "ruslan", denis, "denchik")

        self.assertEqual([], misha.call("GET", "/v1/feed")[1]["friends"])
        friend = ruslan.call("GET", "/v1/feed")[1]["friends"][0]
        self.assertEqual("denchik", friend["nick"])
        day = friend["days"][0]
        self.assertEqual((81, 38.6, 88), (day["recovery"], day["strain"], day["sleepScore"]))
        self.assertEqual(474, day["sleep"]["asleepMin"])
        self.assertEqual("Running", day["workouts"][0]["sport"])
        self.assertNotIn("hr", day)  # heart rate is off unless its owner turns it on
        self.assertEqual(self.today(), day["day"])

    def test_switching_a_section_off_erases_what_was_uploaded(self):
        ruslan, denis = self.signup("ruslan"), self.signup("denchik")
        self.befriend(ruslan, "ruslan", denis, "denchik")
        self.assertEqual(200, denis.call("PATCH", "/v1/me", {"share": {"hr": True}})[0])
        denis.call("PUT", "/v1/me/days/" + self.today(), self.full_day())
        self.assertIn("hr", ruslan.call("GET", "/v1/feed")[1]["friends"][0]["days"][0])

        denis.call("PATCH", "/v1/me", {"share": {"hr": False, "sleep": False}})
        day = ruslan.call("GET", "/v1/feed")[1]["friends"][0]["days"][0]
        self.assertNotIn("hr", day)
        self.assertNotIn("sleep", day)
        self.assertIn("recovery", day)
        # Turning it back on does not bring the erased figures back: they are gone from the database.
        denis.call("PATCH", "/v1/me", {"share": {"hr": True}})
        self.assertNotIn("hr", denis.call("GET", "/v1/feed")[1]["me"]["days"][0])
        stored = self.app.db().execute("SELECT payload FROM days").fetchone()[0]
        self.assertNotIn("lastBpm", stored)
        self.assertNotIn("asleepMin", stored)

    def test_unfriending_cuts_both_directions(self):
        ruslan, denis = self.signup("ruslan"), self.signup("denchik")
        self.befriend(ruslan, "ruslan", denis, "denchik")
        self.assertEqual(204, ruslan.call("DELETE", "/v1/friends/denchik")[0])
        self.assertEqual([], ruslan.call("GET", "/v1/feed")[1]["friends"])
        self.assertEqual([], denis.call("GET", "/v1/feed")[1]["friends"])

    def test_deleting_an_account_removes_every_trace(self):
        ruslan, denis = self.signup("ruslan"), self.signup("denchik")
        self.befriend(ruslan, "ruslan", denis, "denchik")
        denis.call("PUT", "/v1/me/days/" + self.today(), self.full_day())
        self.assertEqual(401, denis.call("POST", "/v1/me/delete", {"password": "wrong one"})[0])
        self.assertEqual(204, denis.call("POST", "/v1/me/delete", {"password": "correct horse"})[0])
        self.assertEqual(401, denis.call("GET", "/v1/me")[0])
        self.assertEqual([], ruslan.call("GET", "/v1/feed")[1]["friends"])
        db = self.app.db()
        for table in ("days", "friendships", "requests"):
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM %s" % table).fetchone()[0], table)
        self.assertEqual(1, db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0])
        self.assertTrue(self.anon.call("GET", "/v1/nicks/denchik")[1]["free"])

    # --- uploads ---

    def test_upload_refuses_anything_outside_the_contract(self):
        ruslan = self.signup("ruslan")
        path = "/v1/me/days/" + self.today()
        now = int(time.time())
        bad = [
            {"recovery": 101}, {"recovery": "81"}, {"recovery": True}, {"strain": -1}, {"unknown": 1},
            {"sleep": {"startTs": now, "endTs": now - 5, "asleepMin": 10}},
            {"sleep": {"startTs": now - 50, "endTs": now, "asleepMin": 10, "note": "x"}},
            {"workouts": [{"startTs": now, "sport": "", "durationS": 5}]},
            {"workouts": [{"startTs": now, "sport": "Run", "durationS": 5, "route": "encoded"}]},
            {"workouts": [{"startTs": now, "sport": "Run", "durationS": 5}] * 21},
            {"hr": {"lastBpm": 62, "lastTs": now, "series": [[now, 62]] * 301}},
            {"hr": {"lastBpm": 5, "lastTs": now}},
        ]
        for body in bad:
            status, err = ruslan.call("PUT", path, body)
            self.assertEqual((400, "bad_payload"), (status, err["error"]), body)
        self.assertEqual(400, ruslan.call("PUT", path, raw=b"[1,2]")[0])
        self.assertEqual(400, ruslan.call("PUT", "/v1/me/days/2020-01-01", {"recovery": 50})[0])
        self.assertEqual(413, ruslan.call("PUT", path, raw=b" " * (server.MAX_JSON_BYTES + 1))[0])
        self.assertEqual([], ruslan.call("GET", "/v1/feed")[1]["me"]["days"])

    def test_a_later_upload_replaces_the_day(self):
        ruslan = self.signup("ruslan")
        path = "/v1/me/days/" + self.today()
        ruslan.call("PUT", path, {"recovery": 60, "strain": 10})
        ruslan.call("PUT", path, {"recovery": 67})
        day = ruslan.call("GET", "/v1/feed")[1]["me"]["days"][0]
        self.assertEqual(67, day["recovery"])
        self.assertNotIn("strain", day)

    # --- avatars ---

    def test_avatar_is_visible_to_friends_only(self):
        ruslan, denis, misha = self.signup("ruslan"), self.signup("denchik"), self.signup("mishka")
        jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 64
        self.assertEqual(415, denis.call("PUT", "/v1/me/avatar", raw=b"<svg></svg>")[0])
        self.assertEqual(413, denis.call("PUT", "/v1/me/avatar", raw=jpeg + b"\x00" * server.MAX_AVATAR_BYTES)[0])
        status, me = denis.call("PUT", "/v1/me/avatar", raw=jpeg)
        self.assertEqual((200, 1), (status, me["avatarRev"]))
        self.assertEqual((200, jpeg), denis.call("GET", "/v1/users/denchik/avatar"))
        self.assertEqual(404, ruslan.call("GET", "/v1/users/denchik/avatar")[0])
        self.befriend(ruslan, "ruslan", denis, "denchik")
        self.assertEqual((200, jpeg), ruslan.call("GET", "/v1/users/denchik/avatar"))
        self.assertEqual(404, misha.call("GET", "/v1/users/denchik/avatar")[0])
        self.assertEqual(204, denis.call("DELETE", "/v1/me/avatar")[0])
        self.assertEqual(0, denis.call("GET", "/v1/me")[1]["avatarRev"])

    # --- plumbing ---

    def test_unknown_paths_and_methods(self):
        self.assertEqual(404, self.anon.call("GET", "/v1/nope")[0])
        self.assertEqual(405, self.anon.call("DELETE", "/v1/register")[0])
        self.assertEqual({"ok": True}, self.anon.call("GET", "/healthz")[1])
        self.assertEqual(1, self.anon.call("GET", "/v1/info")[1]["api"])


if __name__ == "__main__":
    unittest.main()
