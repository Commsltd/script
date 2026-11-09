# My Channel broadcast build — 0.9.12

My Channel remains a channel inside the existing UK Television app. The revised native screen follows a broadcast hierarchy: full-aspect picture and story rundown at left, an editorial column, a narrower numerical/reference column, local/domain clocks, a separate data strip and a scrolling ticker. It replaces the rejected five-card layout. Rectangular rules, condensed type, complete measured text rows and source dates keep several records visible at once.

Composition reference: Bloomberg Open Interest, 13 March 2025, visible at https://www.cato.org/multimedia/media-highlights-tv/johan-norberg-discusses-swedens-response-covid-19-pandemic-bloomberg . No reference screenshot or Bloomberg branding was copied into app assets.

## Domains and defaults

| Domain | Default picture | Native information presentation |
|---|---|---|
| Business & Markets | Bloomberg Europe | BBC/Guardian rundown; four dated ONS releases; Bank of England policy bulletin; five ECB currency pairs with daily change and 30-observation histories; local/London clocks |
| World Window | Truckee, California | Ground-camera location map and context; BBC/Guardian world news; matching local conditions and three-day forecasts; USGS earthquake bulletin |
| Spain & Local | Canal Sur Andalucía | El País rundown; El Mundo reporting; Madrid forecast; Seville conditions; current weather strip |
| Space & Earth | Space Live / Sen | ISS position marker on a land outline; orbital altitude, speed and coordinates; NOAA Kp history; NASA news and USGS observations |
| British Classics | Midsomer Murders | Porridge, Minder and Only Fools and Horses synopses/casts; My Lists; programme queue and a programme-information ticker |
| Practical Living | Garden Rescue | Gardening/environment reporting; Homestead Rescue reference; labelled Seville outdoor conditions and forecast; My Lists/queue controls |
| Technology & AI | Modern Marvels | BBC/Guardian technology news; arXiv AI research; official VS Code release rundown; programme queue |

Weather cities are labelled defaults, not an inferred home location. TVmaze spotlights are programme reference, not claims about the current broadcast or VOD availability. Currency values are daily ECB references, not live trading quotes. Headlines retain publisher and publication dates; full source detail is remote accessible.

First use opens Business and shuffles Business/World/Spain/Space every 15 minutes. Existing preferences survive. MENU exposes all seven domains, rotation pool/order/interval, Stay, source choice, picture-only mode and explicit episode queues. Ticker speed, pause and reduced-motion paging are configurable. Live rotation stops while On Demand playback is in front.

World defaults to the Android-verified Truckee camera. Alternatives include Old Faithful, Scripps Pier underwater and Greenhorn Ranch horse arena. Old Faithful responds with a valid manifest but did not render in the local Android decoder check; it is retained as an optional source, not the default. Public camera metadata is resolved on opening; signed media URLs are not saved. Camera silence/darkness are explained. The small bundled Natural Earth map outline is public domain and draws natively; no tile service or browser is used.

## Data, playback and resource limits

The 29 public feed definitions live in `app/src/main/assets/my-channel/panel-feeds.json`. The current domain alone fetches data, at most two requests at a time. Each request has a 15-second limit and 512 KiB body cap. Models retain at most eight records and 40 chart points (30 per currency pair); each disk cache is bounded to 64 KiB. Refreshes cancel when changing domain/backgrounding. Failures back off to 16 minutes plus jitter. Cached observations and unavailable feeds are distinguished. RSS publication-age limits reject obsolete articles; the defunct RTVE feed was replaced with El Mundo.

Currency histories use ECB observations via Frankfurter, with the ECB XML feed as an alternative. Native HLS MIME is explicit, including redirect URLs without file extensions; otherwise Media3 can wrongly treat those manifests as progressive media. Recovery tries alternatives for the selected programme/camera and re-resolves expiring camera links. Choosing another title remains explicit.

There is one ExoPlayer, an 8 MiB buffer target, 15-second maximum time buffer and a 720p preference where available. Some cameras offer only 1080p. Player and requests are released on backgrounding. Maps are static Canvas paths; charts rebuild only on data/size change. The ticker reuses its measured text and stops when hidden or paused. No dashboard WebView, second decoder, external app, paid service or Mac server is added.

Existing HTTPS/public-network and DNS/redirect protections apply. There are no credentials or scraped account sessions in this feature.

## Qualification and rollback

Unit checks cover bounds, date filtering, currency calculations/history, cache round trips and unsafe camera routes. Captured live feed responses are parsed separately. Native instrumentation records actual decoded pictures and each feed's availability, captures all seven screens, performs 21 rapid domain changes and verifies decoder release on background. Local visual review uses a 1 GiB API 28 arm64 emulator with a 1920×1080 TV display profile; that is not a physical Firestick.

The prior 0.9.10 and 0.9.11 APK backups remain outside the repository. The final local APK must retain the installed signing identity. Replace in place, never uninstall or clear the user's data. No Firestick installation is part of this off-device review.

Physical Firestick playback, long-duration memory behavior and user acceptance remain separate qualification steps. Live stock-index/FTSE quotes, private telemetry, keyed alert services and automatically finding the next unwatched episode of an arbitrary series remain outside this build; explicit episode queues are preserved.
