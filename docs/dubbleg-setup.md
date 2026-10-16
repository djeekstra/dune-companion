# DubbleG connection and sharing

The DubbleG admin creates a phone credential at `/admin/stats-devices`. On the **display
phone**, paste the credential and connect after choosing **CONNECT DUBBLEG** in the browser
panel's game setup. Connection settings are also available in the display's long-press overlay. Credentials are encrypted using Android
Keystore, excluded from backups, and never included in the APK, LAN panel or history exports.

In game setup, check **SHARE FINISHED STATS ON DUBBLEG** to upload that game's names, scores
and turn timings once a winner is recorded. The checkbox starts unchecked for each new game.
Alternatively, in **STATS**, open a past game, choose **SHARE ON DUBBLEG** and confirm the upload.
Copy its unlisted link from that game's STATS page. DubbleG may decline
short games or games without turn records; the panel shows its explanation. Shared games do
not enter official editions, community totals, announcements or the live-game pipeline.

Uploads are saved before sending and retry with backoff while the app runs. Closing the app
pauses upload processing; reopening resumes it. Credential or payload errors require action
and do not retry forever. Disconnecting pauses queued work until the same phone credential
is reconnected. The admin can revoke a phone’s upload access independently of Rabbit.

Deleting local history cancels pending work, but does not revoke a page already shared (an
upload already in flight may complete). The DubbleG admin can revoke, rotate or delete a
shared page at `/admin/casual-games`. Uninstalling deletes local history and queued uploads.

There are no bundled service credentials. The public app uses the same optional integration
as local builds; no private fork is needed. Backend changes and the API contract are described
in [API contract](dubbleg-stats.md).
