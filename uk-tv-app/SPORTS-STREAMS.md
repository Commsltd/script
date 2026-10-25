# Sports streams and changing addresses — 23 October 2026

Status: shared live-address recovery built locally in 0.9.6; **none of the five
requested sites is yet integrated as a verified native stream**. No Firestick
connection, installation, restart, channel-list publication or VPN change was made.

## Source inspection

| Requested site | Evidence from this run | Native integration status |
| --- | --- | --- |
| [FreeStreams](https://freestreams-live1i.pk/live-tv/) | Channel directory loads. The sampled TNT Sports 1 player follows wikisport → xstream and redirects to a parked domain. Sky Sports Main Event redirects to a differently labelled Sky Sports page; its nested player returns 403. The Sky Sports News nested player returns 404. | No sampled working media feed. Do not import the directory as playable channels or infer that every listed channel is dead. |
| [Free Live Sports](https://freelivesports.tv/home) | Website works in a normal browser. “Free Live Sports” channel 2117 reached video readyState 4 at 640×360. The site uses guest-session authentication; direct config requests returned 403 and channel-details requests without that session returned 401. The browser video uses a blob URL. | Browser playback confirmed for one channel; a renewable native playback session/URL resolver remains needed. No browser session token copied into the app or feed. |
| [SportsStream](https://sportsstream.us/) | Public event directory loads. Its client obtains changing event/player domains through an obfuscated API layer. No verified native manifest obtained. | Unfinished resolver; no webpage added as a stream. |
| [StreamEast](https://v2.streameast.ga/) | Redirect to an authentication handoff; direct request returned 403. | Access blocked in this check. No access-gate bypass attempted. |
| [Sportsurge](https://v2.sportsurge.net/live-sports/) | Directory is visible via web retrieval, but direct requests returned 403. Listed events point to other streaming sites. | No verified native event feed. Directory visibility does not prove playback. |

The browser test was closed afterwards. Availability on the Firestick's VPN route
has not been tested. These observations do not establish geography as the reason
for a 401/403 response.

## Implemented shared recovery

- The existing hourly catalogue exporter now also emits `live-sources.json.gz`.
  It carries native stream addresses and exact channel IDs, without programme data
  or artwork. Web, YouTube, DRM and user-account entries are excluded.
- Native playback prefetches this inventory alongside the existing bounded backup
  checks after sustained playback, at most once per five minutes for the whole app.
  It does not replace a healthy playing stream.
- After available alternatives fail, the player checks this inventory and the
  latest hourly guide for new addresses before falling back or reporting failure.
  A changed URL can be retried even when its source ID is unchanged. Merely
  renaming an identical failed address does not trigger another playback attempt.
- Existing independent alternatives are retained, with no three-source limit.
  National/regional channel identities, favourites, lists and account playlists
  are not changed. Changing channel or closing the player invalidates late results.
- Fetches have a six-second total HTTP limit, public HTTPS/DNS/redirect checks and
  a six-MiB decompressed response limit. Outages and invalid/older inventories keep
  the cached alternatives. The existing privacy filter still applies to playback.

The published source inventory is refreshed **hourly**, while the app can check
for that inventory every five minutes. This is not a per-website session resolver:
it cannot renew a browser-bound token, repair a dead host, bypass authentication,
or invent an unavailable alternative. YouTube and embedded web playback retain
their existing separate handling. HLS adaptive quality selection is unchanged.

## Validation and rollout

- 90 Android unit tests pass, including rotating-address replacement, alternative
  preservation, duplicate/invalid/stale feeds and compressed-size bounds.
- 30 focused Python exporter/source tests pass. Android lint and the normal
  non-debuggable APK build pass. Approved icon bytes are preserved.
- Exporting the live published catalogue locally produced 330 native channels,
  724 alternatives and a 41,142-byte compressed address inventory. This checks
  extraction and preservation, not the health of those 724 streams.
- Staged APK/evidence: `.local/delivery/0.9.6-staged/`.
- The workflow/source changes must be published and the normal APK installed
  before this new recovery path is active. Neither was done during this run.
- Device checks remain: a controlled expired-link recovery, switching channel
  during refresh, retained alternatives/favourites, and sustained playback.

To complete sports integration, obtain a reproducible current channel/event →
native media resolver for each usable site. Resolve session-bound URLs on the
Firestick's own network; never publish a Mac/browser session URL as a permanent
channel. Scheduled match rows also need exact identity and start/expiry handling.
