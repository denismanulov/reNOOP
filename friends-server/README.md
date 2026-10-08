# reNOOP friends server

A small self-hosted service behind the reNOOP **Friends** tab: nickname accounts, mutual friendships
by request, and one summary per day that each phone uploads after a strap sync.

This is a reNOOP fork feature. Upstream NOOP has no accounts and no server, and nothing here is meant
to go upstream. The app works exactly as before without it: the Friends tab is the only screen that
talks to this service, and only after the wearer signs in.

- Python 3.10+ standard library only, one SQLite file, no other dependency.
- Listens on loopback. TLS is the reverse proxy's job (a Caddy example is below).
- `python3 -m unittest test_server` runs the whole suite against a real server on a temporary database.

## What it stores, and the rules it keeps

- **An account:** nickname, display name, a scrypt password hash, four sharing switches, an optional
  picture. No e-mail, no phone number, no device identifier. There is no password reset.
- **Per day, per account:** the summary below, for the last 35 days. Nothing raw: no heart-rate
  stream, no RR intervals, no route, no journal.
- **A section that is switched off is not stored.** The server drops it from an upload, and turning a
  switch off erases that section from every day already uploaded. Turning it back on does not bring
  the erased figures back.
- **Only accepted friends read a day.** A friendship is mutual and starts with a request the other
  side accepts. Unfriending cuts both directions at once.
- **Nicknames are found by exact match only.** There is no directory and no prefix search.
- **Deleting the account** removes the nickname, the picture, every day, every friendship and every
  session in one transaction.
- Sessions are random bearer tokens stored as SHA-256 hashes, at most ten per account. Changing the
  password ends every other session.

## API (version 1)

JSON in, JSON out, UTF-8. Every call except the first five needs `Authorization: Bearer <token>`.
An error is `{"error": "<code>", "message": "<text>"}` with a matching HTTP status.

| Method and path | Body | Answer |
|---|---|---|
| `GET /healthz` | | `{"ok": true}` |
| `GET /v1/info` | | `{"name", "api": 1, "inviteRequired"}` |
| `GET /v1/nicks/{nick}` | | `{"nick", "free"}` |
| `POST /v1/register` | `{"nick", "password", "name"?, "invite"?}` | `201 {"token", "me"}` |
| `POST /v1/login` | `{"nick", "password"}` | `{"token", "me"}` |
| `DELETE /v1/session` | | `204`, this phone is signed out |
| `GET /v1/me` | | profile with `share` |
| `PATCH /v1/me` | `{"name"?, "share"?: {"scores"?, "sleep"?, "workouts"?, "hr"?}}` | profile |
| `POST /v1/me/password` | `{"old", "new"}` | `{"token"}`, every other session ended |
| `POST /v1/me/delete` | `{"password"}` | `204` |
| `PUT /v1/me/avatar` | raw JPEG, PNG or WebP, at most 200 KB | profile, `avatarRev` bumped |
| `DELETE /v1/me/avatar` | | `204` |
| `PUT /v1/me/days/{YYYY-MM-DD}` | a day (below) | `204`, replaces that day |
| `GET /v1/feed?days=7` | | me and every friend, newest day first (1 to 14 days), without `hr.series` |
| `GET /v1/users/{nick}/days?days=7` | | one friend (or yourself) in full, `hr.series` included |
| `GET /v1/users/{nick}` | | `{"nick", "name", "avatarRev", "relation"}` |
| `GET /v1/users/{nick}/avatar` | | the picture, for its owner, friends and a pending request |
| `GET /v1/friends/requests` | | `{"incoming": [...], "outgoing": [...]}` |
| `POST /v1/friends/requests` | `{"nick"}` | profile with `relation` |
| `POST /v1/friends/requests/{nick}/accept` | | profile, `relation: "friend"` |
| `DELETE /v1/friends/requests/{nick}` | | `204`, declines or withdraws |
| `DELETE /v1/friends/{nick}` | | `204` |

`relation` is `self`, `friend`, `outgoing`, `incoming` or `none`. Sending a request to someone who
already asked you is an acceptance. A nickname is 3 to 20 characters of `a-z`, `0-9` and `_`, stored
lower-case; a leading `@` is accepted and ignored. A password is 8 to 128 characters. Sign-up needs
only those two: the display name starts as the nickname and is changed later with `PATCH /v1/me`.

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
  converts to the wearer's chosen display scale (100 or 21) when it draws, so two friends on
  different scales still read the same effort.
- Timestamps are Unix seconds. The day in the path is the wearer's local calendar day.
- `sport` is the app's own sport label, at most 40 characters. At most 20 workouts a day.
- `hr.series` is a downsampled line for the day, at most 300 `[ts, bpm]` points.

The sharing switches map to members as: `scores` to `recovery`, `strain`, `sleepScore`; `sleep` to
`sleep`; `workouts` to `workouts`; `hr` to `hr`. New accounts share everything except `hr`.

In the feed each day also carries `day` and `updatedAt` (when the phone last uploaded it), and each
friend carries `share`, so the tab can say "does not share sleep" instead of drawing an empty card.
The feed leaves out `hr.series`, which is most of a day's bytes; a friend's own page asks for it with
`/v1/users/{nick}/days`.

## Limits

Sign-up: 5 an hour per address. Sign-in: 10 per ten minutes per nickname, 20 per address. Nickname
lookups: 30 a minute. Uploads: 120 an hour. 100 friends and 50 unanswered requests per account, 500
accounts per server unless `FRIENDS_MAX_USERS` says otherwise. Set `FRIENDS_INVITE_CODE` to make
sign-up ask for a code only your friends know.

## Running it

```bash
FRIENDS_DB=/var/lib/renoop-friends/friends.db python3 server.py
```

| Variable | Default | Meaning |
|---|---|---|
| `FRIENDS_DB` | `friends.db` | SQLite file |
| `FRIENDS_BIND` | `127.0.0.1` | Listen address. Keep it on loopback. |
| `FRIENDS_PORT` | `8787` | Listen port |
| `FRIENDS_INVITE_CODE` | unset | When set, sign-up needs it |
| `FRIENDS_MAX_USERS` | `500` | Accounts the server will hold |
| `FRIENDS_TRUST_PROXY` | `1` | Take the client address from the proxy's `X-Forwarded-For` |

`renoop-friends.service` is a systemd unit that runs it as a throwaway user with the database in
`/var/lib/renoop-friends`, and `Caddyfile.example` puts HTTPS in front. Passwords travel in the
request body, so the service must never be reachable over plain HTTP from outside.

Back up with `sqlite3 /var/lib/renoop-friends/friends.db ".backup '/root/friends-backup.db'"`; the
file is safe to copy while the server runs only through that command.
