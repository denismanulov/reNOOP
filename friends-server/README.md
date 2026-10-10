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
