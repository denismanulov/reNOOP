# Friends v2: implementation plans

Spec: [`docs/superpowers/specs/2026-10-10-friends-keys-and-straps-design.md`](../../specs/2026-10-10-friends-keys-and-straps-design.md).

Four plans, in this order. Each ends with something that builds and passes its own tests.

| # | Plan | Delivers | Depends on |
|---|---|---|---|
| 1 | [`01-server.md`](01-server.md) | `friends-server/` at API version 2, its tests, the README contract and vectors | nothing |
| 2 | [`02-client-core.md`](02-client-core.md) | The phone key, request signing, the strap handle, the v2 client and store, the Turn On flow. Both app targets build. | plan 1 (a local server to check against) |
| 3 | [`03-client-screens.md`](03-client-screens.md) | Invites and QR, phones, claims banners, the export page, the deep link, strings | plan 2 |
| 4 | [`04-deploy.md`](04-deploy.md) | `friends-server/deploy/` and the runbook | plan 1 |

Android is not planned here. Its work list is section 10 of the spec.

## Before the first task

1. **Checkpoint the uncommitted Friends redesign.** The working tree holds an uncommitted redesign of
   the Friends tab (16 modified files, 6 new). Plans 2 and 3 edit the same files. Ask the owner, then
   commit that work as it stands before starting plan 2. Plan 1 does not touch those files and can
   start without it.
2. **A Python environment with `cryptography`** for plan 1 (its first task creates it).

## Rules every task follows

- Commit subjects are `feature:`, `fix:` or `docs:` followed by a plain description. No scope in
  parentheses, no `Co-Authored-By` trailer.
- Stage the files a task names, by name. Never `git add -A` or `git add .`: unrelated work sits in the
  same tree.
- Repository text is English: code comments, commit messages, documentation. Comments are neutral and
  third-person, and match the density of the file they are in.
- No hardcoded colours, fonts or spacing in UI: `StrandPalette`, `StrandFont`, `NoopMetrics`,
  `FriendsStyle` only.
- `Strand.xcodeproj` is generated. After adding or removing a Swift file run `xcodegen generate`, and
  never commit the project.
- No default CI job validates app-target Swift here. A task that touches `Strand/`, `StrandiOS/` or
  `StrandTests/` builds both targets itself (commands in each plan).

## Vectors shared by the server and every client

These literals appear in the server's tests, the README, and the Swift tests. A Kotlin port asserts
the same ones.

**Strap handle.** Adopted id `whoop-4A0123456` gives

```
98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707
```

**Signing string.** Method `PUT`, target `/v2/me/days/2026-10-10`, time `1791540000`, nonce
`AAAAAAAAAAAAAAAAAAAAAA`, body `{"recovery":81}` (15 bytes) gives these six lines joined by `\n`, with
no trailing newline:

```
renoop-friends-v2
PUT
/v2/me/days/2026-10-10
1791540000
AAAAAAAAAAAAAAAAAAAAAA
a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445
```

**A signature over that string** (ECDSA P-256 with SHA-256, DER, base64). ECDSA is randomised, so a
client verifies this triple and separately checks that what it signs verifies with its own key.

```
public key (SPKI DER, base64):
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ/iGnmqlOeIlaOq9cf3gYSDRWcoyKOiHtx1m7TC7Wnr6CDeXUMf1QOVArjNKQgNPyKXVXw0/9N1Iyj8xadf1YA==

key id:
b9310888608f332f9d712a19e65fd9a088948406f04240f82a4e50843774784a

signature:
MEYCIQCbv6DGgAsKyGcPSjPiv6/b8i3IJZkUCmkxZt2/mg+nBgIhAPIfodgQQUa5TD0KWsCpCIKzIgtAwstLtonCzIHZLJMQ
```

The private key behind this triple was discarded when it was made.
