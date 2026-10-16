# Optional finished-game stats on DubbleG

Companion never publishes live state, official edition history, audience events or messages.
It sends only explicitly selected completed games to DubbleG's unlisted casual-game storage.

## Authorization

The backend's `/admin/stats-devices` page issues a 256-bit random `dgstats_…` credential,
shown once and stored only as a SHA-256 hash. An admin can revoke each phone independently.
Create/revoke forms require admin login and a session-bound CSRF token; credential responses
have no analytics, no referrer and no cache. The phone verifies the credential using
`GET /api/companion/connection` and receives `device_id`, `name` and
`scope: "casual-history:write"`. Existing Rabbit authorization is unchanged. Scoped credentials
are accepted only by this connection endpoint and `POST /api/casual/history`.

Choose CONNECT DUBBLEG in the browser panel's game setup to open credential entry on the
display phone. The QR screen has no connection controls. The app encrypts
it with an Android Keystore AES-GCM key in private preferences; backups are disabled. Neither
the browser panel nor JSON exports return it. Uploads use the fixed HTTPS DubbleG origin;
redirects are disabled so authorization cannot be forwarded to another host.

## Upload contract

The setup checkbox grants consent for that game only, pinned to the connected device identity.
The choice survives restarts in the saved session. When a winner is recorded, its local archive
keeps `share_device_id`; the worker queues that archive without altering existing jobs. This
recovers even if the app stops between archiving and outbox creation. Past games may also be
shared explicitly from STATS. Local consent metadata is excluded from the upload body.

The approved game snapshot is stored in `files/dubbleg_shares.json` before networking. Its
schema-1 body contains `session_id: "casual-<device_id>-<start epoch ms>"`,
`provenance: "casual"`, `status: "finished"`, `finished: true`, `test_mode: false`, and no
edition. Names, winner, rounds, elapsed game time, turn records and score checkpoints follow
Rabbit's casual-game format. The upload allowlist excludes local metadata and secrets.
The local history keeps its original `game-<start epoch ms>` identity.

The server checks the phone's namespace and requires a finished game with a winner, three or
four distinct valid seats, valid turn records and nonnegative game time. It reuses the existing
idempotent casual-game route, share-page renderer and separate casual storage. A retry returns
the same link; revoked/deleted games retain tombstones. These uploads stay outside official
DubbleG editions, bot posts and member/community aggregates.

- Success: 2xx with `url: "https://dubbleg.org/g/<token>"`.
- Skipped: 2xx with `url: null`, `skipped` and `skipped_note`; display the site's explanation.
- Revoked: 2xx with `url: null`, `revoked: true`; stop retrying.
- Retry network errors, 5xx, 408, 429 and incomplete/malformed successful responses with
  backoff from 2 to 60 seconds, retained across app restarts.
- Stop on other 4xx, including invalid/revoked credentials, and show an actionable error.
  Reconnect and explicitly retry that game using its original namespace and snapshot.

No uploads happen without per-game consent via the setup checkbox or the past-game Share action. Closing the app stops the
worker; reopening resumes it. Disconnecting suspends queued work. Already shared URLs are
kept locally; revoking or rotating a URL on the site does not refresh the phone's cached URL.
Deleting local history cancels queued work but cannot retract an upload already in flight.
Use DubbleG's `/admin/casual-games` page to revoke, rotate or delete server copies.

## Backend files

The matching Elika changes add `website/companion_stats.py`, the admin devices template,
route/auth changes in `website/app.py`, the admin navigation link and
`COMPANION_STATS_DEVICES_PATH` in `webgame_common.py`. The runtime
`companion_stats_devices.json` file is gitignored. Tests cover scope isolation, revocation,
namespace collisions, completed-game validation and one-time credential display.

Deploy the backend before creating a phone credential. No production credentials are included
in the source or APK and no private source branch is needed.
