# Friends tab: where everything is, for the iOS side

A reNOOP fork feature: follow a friend's Recovery, Strain and Sleep from inside the app. Upstream NOOP
has no accounts and no server, so none of this goes upstream. Android and the server are done and on
`android-m3`; iOS has nothing yet. This page is the map for whoever writes the iOS twin.

## What exists

| Piece | Where | State |
|---|---|---|
| Server | [`friends-server/`](../../../../friends-server/) | Live at `https://renoop.duckdns.org`. 23 tests. |
| API contract | [`friends-server/README.md`](../../../../friends-server/README.md) | The single source for both clients: every endpoint, the day payload, the limits. |
| Mockups | [`mockups/`](mockups/) | Six screens, Material 3. Structure and copy carry over to iOS; the look is Android's. |
| Android data layer | `android/app/src/main/java/com/noop/friends/` | Client, session store, day-payload builder, uploader. |
| Android screens | `android/app/src/main/java/com/noop/ui/friends/` | Sign-in, home, list, a friend's page, add by nickname, profile. |
| Android tests | `android/app/src/test/java/com/noop/friends/` | Payload, parsing, error mapping, address rules, tab logic. |

## Rules the iOS client has to keep

- **Signed out, the tab sends nothing.** No request and no scheduled work until the wearer signs in.
  The one exception is the "is this nickname free" check while typing in the sign-up form.
- **A friend sees the figures the wearer sees.** The day is built from the same resolvers the Summary
  uses, never derived a second way. On Android that is `FriendsDaySource` calling `SummaryLoader`.
- **A sharing switch that is off is not sent.** The server also drops it and erases what was already
  uploaded, but the client must not build that section in the first place.
- **Strain travels on the stored 0 to 100 axis** and is drawn on each wearer's chosen scale.
- **No carried-over scores.** On an unscored morning the day goes up without Recovery, and a friend
  reads "No data yet" for today rather than last night's figure with no label.
- **Upload today and yesterday** after a strap offload, when the tab opens, and after a sharing switch
  changes. Skip the request when the payload is unchanged. The strap sync path never waits on it.
- **HTTPS only**, redirects not followed, the token in the keychain, never logged.

## Decisions made on Android that iOS should match

- The bar carries Summary, Sleep, Workouts, Coach, Friends. Browse is the top row of Settings.
- Recovery level words are the app's existing five, not a new set.
- Workout `sport` goes up as the app's English label; each phone localises it when it draws.
- Avatar upload uses the existing profile photo; there is no separate picker on the tab.
- The three scores are named Recovery, Strain and Sleep (Russian: Восстановление, Нагрузка, Сон).
  Android was renamed on 2026-10-08. Still on the old names because they are pinned byte for byte to
  Swift twins and must flip on both platforms together: the `WeeklyDigest` and `ActivityCostEngine`
  sentences and the Test Centre mode registry.

## Open, do not build against yet

**How a wearer signs in is under discussion** (2026-10-09). Today it is a nickname and a password.
The owners are weighing a password-free sign-up (nickname only, a random key generated on the phone,
a transfer code for a new phone) and asked about binding to the strap's serial number, which was
advised against because a serial is not a secret and the server cannot check who holds the strap.
Everything else in the API is stable; treat `/v1/register`, `/v1/login` and `/v1/me/password` as the
part that may change, and keep the sign-in screen thin until that is settled.

## Not verified

Nobody has seen the Android Friends screens on a device with a real account yet. Unit tests and a
full protocol run against a local copy of the server pass; layout, the upload after a real strap
sync, and the figures against the Summary are still to be checked.
