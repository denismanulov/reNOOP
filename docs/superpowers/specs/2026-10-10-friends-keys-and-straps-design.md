# Friends v2: phone keys, strap binding, invites, and a hidden origin

Date: 2026-10-10. Status: design approved in conversation, awaiting review of this file.

A reNOOP fork feature. Nothing here is meant for upstream NOOP, which has no accounts and no server.

## 1. Goal

Replace the Friends service's nickname-and-password accounts with an identity the wearer never types:

- No password and no nickname. Turning Friends on is one tap.
- The account is bound to the strap, so the same strap on a new phone leads back to the same account.
- A new strap on the same phone takes the account over with no action from the wearer.
- Friends find each other by a one-time invite (QR, link or typed code), never by a searchable name.
- The server stays open and checkable: its code, its protocol and its deployment files are in the
  repository, and a wearer can read everything it holds about them.
- The machine that holds the database is not reachable or discoverable from the internet.

## 2. Decisions taken

| Question | Decision |
|---|---|
| Same strap, new phone | The old phone confirms. If every trusted phone of the account is silent for 48 hours, the strap alone is enough. |
| Name and discovery | No nickname. Display name and photo come from the app profile. Friends are added by invite only. |
| What a phone proves itself with | An ECDSA P-256 key pair held by the phone; every request is signed. |
| Redeeming an invite | Friends at once, no second confirmation. |
| Where the server runs | A rented VPS (the origin), behind a separate small proxy VPS. No Cloudflare, no purchased domain. |
| Who the users are | Russia and elsewhere, so nothing may depend on a network that is throttled in Russia. |
| "Transparent" | Open and verifiable, not end-to-end encrypted. Only the origin's address is hidden. |
| Existing accounts and API v1 | Clean slate. v1 is removed, the database starts empty, everyone enrols again. |
| Scope of this work | Server and iOS/macOS client. Android gets the contract and a work list; its Friends tab does not work against a v2 server until ported. |

## 3. Identity model

### 3.1 Account

A row on the server with an opaque public id (16 lower-case hex characters, random). It owns:

- up to 5 **devices** (phone keys),
- at most one **strap binding**,
- a display name, an optional picture, four sharing switches,
- its days (unchanged from v1: 35 days kept, the `FriendsDay` payload is not touched).

There is no nickname, no password, no e-mail and no session token.

### 3.2 Device key

- ECDSA over P-256. On iOS and macOS the key is generated in the Secure Enclave
  (`SecureEnclave.P256.Signing.PrivateKey`) and its opaque blob is kept in the Keychain as
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, so an upload can follow a background strap sync.
  Where there is no Secure Enclave (the simulator, a Mac without one) the key is a software
  `P256.Signing.PrivateKey` in the same Keychain item class. On Android it is an `AndroidKeyStore` key.
- One key per server address. The Keychain account name carries a hash of the normalised address, so a
  self-hosted server never sees requests signed with the key the standard server knows.
- The key is not a setting, never enters UserDefaults and never enters a `.noopbak` backup.
- Wire forms:
  - public key: SubjectPublicKeyInfo DER, standard base64 with padding. The server accepts only an
    uncompressed P-256 point and answers `400 bad_key` to anything else.
  - **key id**: lower-case hex SHA-256 of the SPKI DER bytes (64 characters). This is also the
    device's id in the API.
  - signature: ECDSA with SHA-256, DER (`Ecdsa-Sig-Value`), standard base64.

### 3.3 Signed requests

Every `/v2/` call except `GET /v2/info` carries four headers:

| Header | Value |
|---|---|
| `X-Friends-Key` | key id |
| `X-Friends-Time` | Unix seconds, decimal |
| `X-Friends-Nonce` | 16 random bytes, base64url without padding (the server accepts 16 to 64 characters of `[A-Za-z0-9_-]`) |
| `X-Friends-Signature` | signature over the UTF-8 bytes of the signing string |

The signing string is six lines joined by a single `\n`, with no trailing newline:

```
renoop-friends-v2
<METHOD, upper case>
<request target: the path beginning with /v2/, plus ?query when there is one, exactly as sent>
<X-Friends-Time>
<X-Friends-Nonce>
<lower-case hex SHA-256 of the request body bytes>
```

An empty body hashes to `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
The target is the path the client appends to its base address, so a server mounted under a prefix by its
proxy (which strips the prefix) verifies the same string.

Server checks, cheapest first: route, per-address limit, header shapes, time window, key lookup,
nonce not seen before, body size and read, signature, handler. A nonce is recorded only once its
signature has verified, so requests that fail cannot fill the cache.

- **Time window:** `|server now - X-Friends-Time| <= 300 s`, otherwise
  `401 {"error":"clock_skew","serverTime":N}`. The client then signs with `device now + (N - device
  now)` and retries once; it keeps the offset in memory for later calls. A phone with a wrong clock
  therefore still works.
- **Key lookup before any signature maths.** A key id that is neither a device nor a pending join
  claim is `401 unknown_key` without a verification, so an unauthenticated caller cannot make the
  server burn CPU. The two calls that introduce a new key (`POST /v2/enroll`, `POST /v2/claims`) carry
  the public key in their body, so for them the body is read first; `X-Friends-Key` must be the
  key's hash; they are verified with it and are limited per address before the verification.
- **Nonce:** the server remembers `(key id, nonce)` for 600 s in memory and answers `401 replayed` to
  a repeat. A restart forgets the cache, which only widens replay to the 300 s window; every mutating
  call is idempotent or one-shot, so a replay inside it changes nothing.
- A wrong signature is `401 bad_signature`.

Signature verification uses the `cryptography` package (Debian/Ubuntu `python3-cryptography`), the
server's only dependency beyond the standard library.

### 3.4 Strap binding

- The phone derives a **strap handle**: lower-case hex SHA-256 of the UTF-8 bytes of
  `"renoop-friends-strap-v1\n" + <adopted id>`, where the adopted id is the registry's serial-derived
  id (`whoop-<SERIAL>` from `WhoopSerialIdentity.adoptedId(serial:)`, `oura-<SERIAL>` for a ring).
  Vector: adopted id `whoop-4A0123456` gives
  `98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707`.
- The serial itself never leaves the phone. The server stores
  `HMAC-SHA256(FRIENDS_STRAP_PEPPER, <the 32 handle bytes>)`. The pepper is in the service's
  environment file, not in the database, so a leaked database gives neither serials nor a handle that
  could be used to file a claim.
- One strap handle belongs to at most one account, and an account holds at most one.
- **The binding is a recovery handle and a one-account-per-strap rule. It is not a credential.** A
  WHOOP 4.0 advertises its serial in its Bluetooth name, so a serial alone must never sign anyone in
  at once. Section 3.6 is what a serial alone can do.
- The handle comes from the registry's **active** device. A legacy `my-whoop` registry row is not
  re-keyed onto its serial, so `BLEManager` gains one writer: when a serial is confirmed (the DIS
  serial, or a 4.0 hello serial that passed `RepeatedSerialGate`), it is stored against the active
  registry id in UserDefaults. `FriendsStrap` reads the adopted id for the active device from the
  registry id when it is already serial-derived, else from that stored serial. The same physical
  strap yields the same handle either way.
- An active device with no serial concept (Apple Watch, a chest strap, an import) gives no handle. The
  account then has no binding and no strap recovery until a strap with a serial becomes active.
- The 54-character device key that sits beside the serial in a 4.0 hello response is not read, hashed
  or used. The existing rule that it never leaves the device stands.

### 3.5 Scenarios

| Situation | What happens |
|---|---|
| First use, strap unbound | One tap on "Turn on". `POST /v2/enroll` creates the account, the device and the binding. |
| New strap, same phone | Automatic. The upload run sees a handle different from the one the server last accepted and calls `PUT /v2/me/strap`. The binding moves; the old strap is released. |
| New phone, old phone alive | New phone: enrol answers `strap_bound`; the wearer picks "This is my account"; a join claim is filed and a waiting screen shows a 6-digit code. Old phone: a banner on the Friends tab shows the same code with Confirm and Decline. |
| New phone, old phone gone (lost, wiped, reinstalled on Android) | Same claim, nobody answers. It matures after 48 hours of silence (3.6) and the new phone joins on a 7-day probation. |
| Second-hand strap still bound to someone | The wearer picks "The strap came from someone else". An account without a binding is created at once and Friends works. `PUT /v2/me/strap` files a take claim; the previous owner releases the strap, or it matures after 48 hours of silence. |
| New phone and new strap at once | With the old strap still at hand: pair it once on the new phone, join by claim, then switch straps. Without it the account cannot be recovered and a new one is made. |
| Reinstall on iOS, same signing identity | The Keychain item survives, so the phone is still a device. No claim. |

### 3.6 Claims

A claim is a pending request against a bound strap. Two kinds:

- **join:** a key that is not yet a device asks to become one of the bound account's devices.
  Filed by `POST /v2/claims`, signed with the new key.
- **take:** an existing account asks for a strap bound to another account. Filed by
  `PUT /v2/me/strap` when the handle is bound elsewhere.

Rules:

1. Each claim gets a random 6-digit `code`, shown on both phones so two claims can be told apart.
2. At most 3 pending claims per strap (`429 too_many_claims`). A claim not settled in 14 days expires.
3. A **trusted** device is one not on probation. **Approve** and **decline** come from a trusted
   device of the bound account. Approving a join adds the key as a trusted device. Approving a take
   moves the binding to the claimant's account and leaves the previous account without one.
   Declining marks the claim declined; the same key (join) or account (take) cannot claim that strap
   again for 7 days (`429 claim_declined`).
4. **Silence.** A claim matures when
   `now >= max(claim.createdAt, account.trustedSeen) + 48 h`, where `trustedSeen` is the time of the
   latest signed request from any trusted device of the bound account. It is kept on the account
   row, so it still stands after that device is removed. Every such request pushes maturity out, so
   an owner whose phone still syncs can only be joined by their own confirmation. A device on
   probation does not move it: someone who got in by silence cannot hold the door shut against the
   real owner's later claim.
5. Maturity is settled lazily, in one function, whenever a claim is read: the claimant's
   `GET /v2/claims/mine`, a repeated `PUT /v2/me/strap`, and `GET /v2/feed` of either side.
6. A join that matures by silence adds the device with `probationUntil = now + 7 days`. A take that
   matures moves the binding.
7. **Probation.** A device on probation may read (feed, friends' days, its own profile, devices,
   export) and upload days. Every other change answers `403 probation`: profile and sharing
   switches, picture, invites, redeeming, unfriending, approving or declining claims, moving the
   strap, removing another device, deleting the account. It may remove itself. A trusted device may
   remove it at any time, or end its probation early (`trust`).
8. A join is refused at filing (`409 too_many_devices`) when the account already has 5 devices.
9. **The last trusted device cannot remove itself** (`409 last_device`). With no password there would
   be no way back except a 48-hour claim, so a wearer with one phone has two choices: keep Friends on,
   or delete the account (which frees the strap, and turning Friends on again makes a new account at
   once). "Remove this phone" is offered only when another trusted device exists.

What this buys: someone who learned a serial cannot enter an account whose owner uses the app, and if
they enter one whose owner was silent for two days they cannot lock the owner out, change the account,
or plant themselves as a friend.

### 3.7 Risks accepted

- After 48 hours of owner silence, a person who knows the serial can read the owner's feed until the
  owner returns and removes them. The owner sees a banner naming the unconfirmed phone.
- If the owner's phone is truly gone and an attacker files a claim in the same window, both mature
  together and both leave probation after 7 days as equal devices. This needs a serial and exact
  timing, and the stake is a friends leaderboard.
- Losing the phone and the strap together loses the account.
- The proxy can be taken offline by a volumetric attack; Friends is then unavailable until the attack
  ends or the proxy is replaced. The origin and the database are unaffected.
- The server operator can read every uploaded day. This is the stated trade against end-to-end
  encryption, and section 7 is how a wearer checks what is held.

## 4. Friends by invite

- `POST /v2/invites` mints a single-use code: 10 characters of Crockford base32
  (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), shown as `XXXXX-XXXXX`, valid 7 days, at most 10 outstanding
  per account. The server stores SHA-256 of the normalised code and returns the code once.
- Normalising typed input: upper-case, drop `-` and whitespace, read `O` as `0` and `I` or `L` as
  `1`. The field also accepts a pasted invite link or `renoop://` link and takes the code out of it.
- **Redeeming makes the two accounts friends at once** and consumes the invite. Redeeming one's own
  invite is `400 own_invite`. Redeeming when already friends answers `200` and leaves the invite
  unused. An unknown, expired or used code is `404 no_such_invite` in every case.
- The invite sheet shows three things: a QR code, a Share button and the code.
  - The QR and the shared text hold `https://<server>/i/<code>`: a static page served by the friends
    server with an "Open in reNOOP" link to `renoop://friends/add?c=<code>` and the code as text. An
    https link is tappable in every messenger; a custom scheme is not. The system camera reads the
    QR, so the app needs no scanner and no camera permission.
  - `GET /i/{code}` does not touch the database, so it reveals nothing about whether a code is
    valid. The path segment is checked against `^[0-9A-Za-z-]{10,11}$` before it is written into the
    page. Headers: `Cache-Control: no-store`, `Referrer-Policy: no-referrer`,
    `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'`. The access log writes
    the path as `/i/…`.
- `renoop://friends/add?c=<code>` opens the Friends tab and asks "Add this person as a friend?"
  before redeeming, so opening a link never adds anyone silently. With Friends off, the code is held
  until Friends is turned on and the question is asked then.
- Removed from the server entirely: lookup by name, the "is this name free" check, incoming and
  outgoing requests. No call remains that finds or enumerates accounts.

## 5. Profile

- The display name is `ProfileStore.displayName` (1 to 40 characters). The turn-on screen shows the
  name and picture as friends will see them; when the profile name is empty it has a field that saves
  into the profile. There is one name for the whole app.
- The picture is `ProfileStore.avatarImageData`, passed through the existing
  `FriendsAvatars.fitForUpload`. A fifth switch on the sharing page, "Photo" (local setting
  `friends.sharePhoto`, on by default), decides whether it is sent; turning it off deletes the
  server's copy.
- **Push on local change only.** The phone keeps a fingerprint of the name and of the picture bytes it
  last sent, and sends again when the local value no longer matches that fingerprint. It never sends
  because the server's value differs. Two phones of one account with different profiles therefore do
  not overwrite each other in a loop; the last local edit wins. A phone that joins an existing
  account does not push its profile on joining.
- No profile push is attempted while the device is on probation.

## 6. API version 2

JSON in and out, UTF-8. An error is `{"error": "<code>", "message": "<text>"}` with a matching
status. `{id}` is an account id, `{key}` a key id, `{claimId}` a claim's id, `{day}` is `YYYY-MM-DD`.

| Method and path | Signed by | Body | Answer |
|---|---|---|---|
| `GET /healthz` | nobody | | `{"ok": true}` |
| `GET /v2/info` | nobody | | `{"name": "renoop-friends", "api": 2, "time": N}` |
| `GET /i/{code}` | nobody | | the invite page (HTML) |
| `POST /v2/enroll` | the new key | `{"key", "name", "platform", "strap"?}` | `201 {"me"}`; `200 {"me"}` when the key is already a device; `409 strap_bound` |
| `POST /v2/claims` | the new key | `{"key", "strap", "platform"}` | `201 {"claim"}`; `404 strap_free`; `409 already_enrolled`; `409 too_many_devices` |
| `GET /v2/claims/mine` | the claiming key | | `{"claim"}` with `state` `pending`, `approved`, `declined` or `expired` |
| `DELETE /v2/claims/mine` | the claiming key | | `204`, withdrawn |
| `POST /v2/claims/{claimId}/approve` | a device | | `204` |
| `POST /v2/claims/{claimId}/decline` | a device | | `204` |
| `GET /v2/me` | a device | | `me` |
| `PATCH /v2/me` | a device | `{"name"?, "share"?}` | `me` |
| `PUT /v2/me/avatar` | a device | raw JPEG, PNG or WebP, at most 200 KB | `me` |
| `DELETE /v2/me/avatar` | a device | | `204` |
| `PUT /v2/me/strap` | a device | `{"strap"}` | `200 {"bound": true}`; `202 {"claim"}` when bound elsewhere |
| `GET /v2/me/devices` | a device | | `{"devices": [{"id", "platform", "addedAt", "lastSeenAt", "probationUntil", "current"}]}` |
| `DELETE /v2/me/devices/{key}` | a device | | `204`. Removing oneself turns Friends off on this phone; `409 last_device` for the last trusted device. |
| `POST /v2/me/devices/{key}/trust` | a device | | `204`, probation ended |
| `GET /v2/me/export` | a device | | everything the server holds for the account (section 7) |
| `POST /v2/me/delete` | a device | | `204`, the account and all it owns are gone in one transaction |
| `PUT /v2/me/days/{day}` | a device | a day (v1 format, unchanged) | `204` |
| `GET /v2/feed?days=7` | a device | | below |
| `GET /v2/users/{id}/days?days=7` | a device | | one friend, or oneself, with `hr.series` |
| `GET /v2/users/{id}/avatar` | a device | | the picture, for its owner and friends |
| `POST /v2/invites` | a device | | `201 {"id", "code", "expiresAt"}` |
| `GET /v2/invites` | a device | | `{"invites": [{"id", "createdAt", "expiresAt"}]}` |
| `DELETE /v2/invites/{inviteId}` | a device | | `204` |
| `POST /v2/invites/redeem` | a device | `{"code"}` | `200 {"friend"}` |
| `DELETE /v2/friends/{id}` | a device | | `204` |

- `platform` is `ios`, `android` or `mac`. It is the only thing the server knows about a phone
  besides its public key.
- `me`: `{"id", "name", "avatarRev", "share": {"scores", "sleep", "workouts", "hr"}, "strapBound",
  "device": {"id", "probationUntil"}}`. `probationUntil` is null for a trusted device.
- `claim`: `{"id", "kind", "code", "state", "platform", "createdAt", "maturesAt"}`.
- A person as others see them: `{"id", "name", "avatarRev"}`, with `share` and `days` where the call
  returns them.
- An invite's `id` is the first 16 hex characters of its code hash, so a listing never repeats a code.
- **Feed:** `{"serverTime", "me": {…, "days"}, "friends": [{…, "share", "days"}], "claims": [claim],
  "strapClaim": claim | null}`. `claims` are the pending claims against this account's strap;
  `strapClaim` is this account's own pending take claim. `pendingIncoming` no longer exists.
- Sharing switches, the day format, the 35-day window, the erase-on-switch-off rule and the
  friends-only read rule are exactly v1's.

Error codes the client tells apart: `unknown_key` (this phone is not, or no longer, a device: return
to the turn-on screen and keep the key), `clock_skew` (retry once with the offset), `bad_signature`,
`replayed`, `strap_bound`, `strap_free`, `probation`, `claim_declined`, `too_many_claims`,
`too_many_devices`, `last_device`, `too_many_invites`, `too_many_friends`, `no_such_invite`,
`own_invite`, `server_full`, and `429` in general.

## 7. Server

### 7.1 Storage

One SQLite file, `PRAGMA user_version = 2`.

```
accounts(id INTEGER PK, pub_id TEXT UNIQUE, name, share_scores, share_sleep, share_workouts, share_hr,
         avatar BLOB, avatar_type, avatar_rev, strap BLOB UNIQUE NULL, created_at,
         last_seen,       -- any signed request: the idle sweep reads it
         trusted_seen)    -- a signed request from a trusted device: claim maturity reads it
devices(key_id BLOB PK, account_id → accounts ON DELETE CASCADE, spki BLOB, platform,
        added_at, last_seen, probation_until INTEGER NULL)
claims(id INTEGER PK, kind, strap BLOB, account_id → accounts (the bound account),
       key_id BLOB NULL, spki BLOB NULL, platform,           -- join
       claimant_id → accounts NULL,                          -- take
       code TEXT, state, created_at, settled_at NULL)
invites(code_hash BLOB PK, account_id → accounts ON DELETE CASCADE, created_at, expires_at)
friendships(a, b, created_at, PRIMARY KEY (a, b), CHECK (a < b))
days(account_id, day, payload, updated_at, PRIMARY KEY (account_id, day))
```

- The three last-seen columns are written at most once a minute per device.
- Settled claims are kept 7 days (so the claimant can read the outcome and the re-claim block
  holds), then deleted.
- **A v1 database is refused, not migrated and not deleted.** On start, a file that has a `users`
  table makes the server exit with a message telling the operator to move it aside. The server never
  removes data on its own.
- `FRIENDS_STRAP_PEPPER` is required (at least 32 characters); the server does not start without it.
  It must be backed up apart from the database: without it, existing bindings stop matching (accounts
  still work by key, and each phone binds again on its next sync).
- An account with no signed request for 180 days is deleted with everything it owns, which also frees
  its strap. Its days expired long before. The sweep runs once a day, and once more before an
  enrolment would be refused for the account cap.
- `FRIENDS_INVITE_CODE` (the v1 sign-up code) is removed. `FRIENDS_MAX_USERS` stays.

### 7.2 Limits

Per address: 600 requests a minute overall; 5 enrolments an hour; 10 join claims an hour.
Per key: 120 uploads an hour; 20 invites an hour; 10 redeem attempts an hour.
Per account: 100 friends, 10 outstanding invites, 5 devices. Per strap: 3 pending claims.
Body caps per route as in v1 (64 KB JSON, 200 KB picture).

Because requests are signed, a per-key limit cannot be dodged by changing address.

### 7.3 What a wearer can check

- `friends-server/README.md` is the contract: every call, every stored field, every limit. Its
  privacy section is rewritten to say what is now held: public keys of the phones, the platform of
  each, and a keyed hash of the strap serial. The v1 sentence "no device identifier" is no longer true
  and is removed.
- `GET /v2/me/export` returns the account row (without the picture's bytes, with their size), the
  devices, whether a strap is bound, friends (id and name), outstanding invite ids, claims, and every
  stored day in full. The sharing page shows it as it came, as text.
- `friends-server/deploy/` holds the deployment files of section 8, so anyone can run the same thing.

## 8. Network

```
phone ──► renoop.duckdns.org ──► proxy VPS (nginx stream, TCP only)
                                      │ WireGuard
                                      ▼
                         origin VPS: Caddy (TLS) ──► server.py on 127.0.0.1
                         no inbound 80/443 from the internet
```

- **Origin.** `server.py` listens on loopback as today. Caddy listens only on the WireGuard address,
  terminates TLS, and takes the caller's address from the PROXY protocol header the proxy sends, so
  per-address limits see real addresses. The firewall drops inbound 80 and 443 on the public
  interface and accepts the WireGuard port only from the proxy. SSH stays, key-only.
- **Proxy.** A small VPS chosen at a host whose tariff includes L3/L4 DDoS filtering. nginx `stream`
  forwards TCP 443 unchanged with `proxy_protocol on`. It holds no certificate and no key and sees
  only ciphertext. It is disposable: DuckDNS answers with a 60-second TTL, so replacing a burned
  proxy is a new VPS and a changed A record, with no app update.
- **Certificate.** Caddy on the origin obtains it by TLS-ALPN-01, which passes through the TCP proxy.
- **The current origin address is already public** (the DuckDNS name pointed at it, and passive DNS
  keeps history). After the proxy works, the origin gets a new address from its host or is rebuilt,
  and no public DNS record ever points at the new one.
- **The client's networking does not change.** One standard address,
  `https://renoop.duckdns.org`; a wearer-supplied server address works as in v1. No endpoint list and
  no failover: the short TTL makes them unnecessary.

Deliverables in `friends-server/deploy/`: the proxy's nginx configuration, the WireGuard
configuration for both ends, the origin's Caddyfile, the origin's firewall rules, and a runbook in the
README (order of steps, moving the address, checks with `curl --resolve`, replacing the proxy).
`Caddyfile.example` and the systemd unit are updated to match.

The deployment itself is done by the operator. This work produces the files and the runbook and does
not log in to any machine.

## 9. iOS and macOS client

`Strand/Friends/` compiles into both the `Strand` (macOS) and `NOOPiOS` targets. Both must build, and
`StrandTests` runs on the macOS leg.

### 9.1 Units

| Unit | Purpose | Depends on |
|---|---|---|
| `Packages/StrandAnalytics/Sources/StrandAnalytics/FriendsWire.swift` (new) | Pure text rules shared with Android: the signing string, invite-code normalising and formatting, taking a code out of a link. No hashing, so it builds on Linux. | nothing |
| `Strand/Friends/FriendsKey.swift` (new) | The device key: create or load (Secure Enclave, else software), public key as SPKI, key id, sign. Per-server Keychain item. | CryptoKit, Security |
| `Strand/Friends/FriendsStrap.swift` (new) | The strap handle of the active registry device, or nil. | `DeviceRegistry`, `WhoopSerialIdentity`, CryptoKit |
| `Strand/Friends/FriendsAPI.swift` | The v2 wire: signed `perform`, the clock-skew retry, models keyed by account id. `FriendsNick`, `FriendsPassword`, register, login and password calls are deleted. | `FriendsKey`, `FriendsWire` |
| `Strand/Friends/FriendsStore.swift` | The state machine below, profile push, strap rebinding inside the upload run, claims, invites, devices. The session `epoch` guard is kept. `FriendsKeychain` (the token) is deleted. | the above |
| `Strand/Friends/FriendsInvite.swift` (new) | The invite sheet: QR (CoreImage), share, code. | `FriendsStore` |
| `BLEManager` | One added writer: the confirmed serial against the active registry id. No change to the connection path. | |

### 9.2 State

```
off ──Turn on──► enrolling ──201──────────────────────────► on
                     │
                     ├─ active strap has a serial not yet read ─► waitingForStrap ─► enrolling
                     │
                     └─ 409 strap_bound ─► strapBound
                            ├─ "This is my account" ─► waiting(claim) ─ approved / matured ─► on
                            │                              └ declined / expired / cancelled ─► strapBound
                            └─ "The strap came from someone else" ─► enrol without strap ─► on
                                                                     (take claim runs in the background)
on ── 401 unknown_key ──► off (key kept)
on ── remove this device / delete account ──► off (key deleted)
```

`waiting(claim)` polls `GET /v2/claims/mine` when the tab appears and at most once a minute while it
is open. Cancel on the waiting screen withdraws the claim (`DELETE /v2/claims/mine`). The state, the claim code and `maturesAt` survive a relaunch (UserDefaults); the key is in
the Keychain.

When the active device is a WHOOP or an Oura whose serial has not been read yet, Turn on waits for it
(`waitingForStrap`, "Connect your strap to finish") instead of enrolling without a binding. A device
with no serial concept enrols without one.

### 9.3 Screens

- **Welcome:** the name-and-password form is replaced by a card showing the wearer as friends will see
  them and a Turn on button, with the states of 9.2 (waiting for the strap, the two-way choice, the
  waiting screen with the code and the time the strap alone will be enough).
- **Manage sheet** (the `person.fill.badge.plus` button): Invite a friend, Enter a code, outstanding
  invites (swipe to revoke), My Sharing. The Requests section is removed, and so is the Invited
  section of the main page. The badge on the button counts pending claims.
- **My Sharing:** the four switches plus Photo; Phones (this one and the others, with remove and, for
  one on probation, Keep; "Remove this phone" only while another trusted phone exists); What the
  server stores; Delete account, confirmed by a dialog instead of a password.
- **Banners on the tab:** a join claim ("A new phone wants to sign in", code, Confirm, Decline); a
  take claim ("Someone connected your strap", Release, Decline); an unconfirmed phone that joined by
  silence (Keep, Remove); this phone on probation, with the date it ends.
- **Deep link:** `renoop://friends/add?c=…` in the existing `.onOpenURL`.
- Friends everywhere are keyed by account id; nothing shows or accepts a nickname. The picture cache
  is keyed by id.
- Design tokens only. New strings go into `Localizable.xcstrings` in its hand-formatted style, with
  the keys' translations as the i18n gate requires.

## 10. Android work list

Not done in this work. The contract is `friends-server/README.md`; the pinned vectors of section 11
are what its tests assert.

1. `AndroidKeyStore` P-256 key per server address; SPKI from `publicKey.encoded`; `SHA256withECDSA`
   (already DER).
2. An OkHttp interceptor that signs every `/v2/` request and performs the one clock-skew retry.
3. Kotlin twin of `FriendsWire` (signing string, invite code rules) and of the strap handle, through
   the existing Kotlin `WhoopSerialIdentity`.
4. Models keyed by account id; `FriendsApi` rewritten for v2; `FriendsStore` state machine of 9.2.
5. `FriendsConnectScreen` becomes the Turn on card and its states; `FriendsAddScreen` becomes invite
   and enter-code; requests UI removed; devices, export and probation banners added.
6. An intent filter for `renoop://friends/add`; QR generation.
7. `FriendsApiLiveServerTest` moved to v2.

Until then the Android Friends tab fails against a v2 server.

## 11. Testing

**Server** (`python3 -m unittest test_server`, a real server on a temporary database, with the
server's clock injected so 48 hours and 7 days are steps, not waits):

- signing: a good request; a wrong signature; a changed body, path, query or method; a time outside
  the window and the `serverTime` it answers; a repeated nonce; an unknown key rejected without
  verification; a non-P-256 key.
- enrolment: free strap, no strap, bound strap, the same key twice, the account cap.
- join claims: approve, decline and the 7-day block, withdrawal, maturity after 48 hours, activity of
  a trusted device pushing maturity out, activity of a probation device not doing so, three pending
  claims, the device cap, expiry.
- probation: each forbidden call answers `403 probation`; reading and uploading work; `trust` ends
  it; a trusted device removes it; it ends by itself after 7 days.
- strap: rebinding releases the old handle; a take claim approved and matured; the pepper changes the
  stored value.
- invites: single use, expiry, own invite, already friends, the outstanding cap, normalised input,
  revoke; the page reflects only a well-formed code and never reads the database.
- days and feed: v1's cases carried over, with ids instead of nicknames; friends-only reads.
- export contents; account deletion; device removal; the idle sweep; refusal of a v1 database; limits.

**Cross-platform vectors**, written into the README by the server work and asserted by each client:
the strap handle above; a signing string for a fixed request; and a fixed triple of public key,
signing string and signature. ECDSA signatures are randomised, so a client verifies the triple and
separately checks that what it signs verifies with its own public key.

**Swift:** `FriendsWire` in `swift test` (StrandAnalytics). In `StrandTests` with a `URLProtocol`
stub: the headers and signature of a real request verify; the clock-skew retry happens once; the 9.2
transitions; profile push only on local change; strap rebinding on a changed handle; code and link
parsing. Both app targets are built with `xcodebuild`, since no default CI compiles them.

**Hardware:** the `BLEManager` writer is on a path CI cannot exercise. It is checked on a real strap:
the serial is stored after a connect, for a serial-keyed row and for a legacy `my-whoop` row, and the
connection behaves as before.

## 12. Order of work

0. The uncommitted Friends redesign in the working tree is committed as a checkpoint first, with the
   owner's say-so; this work builds on it.
1. Server v2, its tests, the README contract and vectors.
2. Client core: `FriendsWire`, `FriendsKey`, `FriendsStrap`, the v2 `FriendsAPI`, the `FriendsStore`
   state machine, the `BLEManager` writer, unit tests.
3. Client screens and strings; both targets built; checked in the simulator against a local server.
4. `friends-server/deploy/` and the runbook.
5. The Android work list is section 10 of this file; nothing further is written for it.

Deployment by the operator: install `python3-cryptography`, set `FRIENDS_STRAP_PEPPER`, move the v1
database aside, start v2, then carry out the runbook of section 8.

## 13. Considered and not chosen

- **A strap serial as the credential.** It is broadcast in the 4.0's Bluetooth name.
- **A secret derived from the 4.0 hello device key.** It would be strap-bound and unadvertised, but the
  project's rule is that this key is never read, and the 5/MG has no known equivalent.
- **A bearer token per phone.** Simpler and dependency-free, but a long-lived secret travels in every
  request. Not chosen by the owner.
- **Cloudflare Tunnel in front.** Strongest DDoS absorption at no cost, but it needs a purchased
  domain, terminates TLS at a third party, and is throttled in Russia.
- **An endpoint list with failover in the client.** Unnecessary with a 60-second DNS TTL.
- **End-to-end encryption of days.** Declined: much more work on both platforms and harder recovery.
- **A recovery secret carried in `.noopbak`.** Would cover losing phone and strap together; left out
  until someone needs it.
- **A switch to disable strap-only recovery.** One boolean that would give the strictest mode; left
  out of this version.
