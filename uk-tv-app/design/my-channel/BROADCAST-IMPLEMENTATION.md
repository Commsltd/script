# My Channel broadcast build — 0.9.12

This implements the seven-domain broadcast screen inside the existing UK Television guide. It replaces the sparse single-text panel with three right-hand panels, two lower panels, a persistent headline ticker and local/domain clocks. Native Android views and one ExoPlayer are used; there is no embedded dashboard, second video, Mac server or new paid service.

## Defaults

| Domain | Default picture | Information |
|---|---|---|
| Business & Markets | Bloomberg Europe | ECB currency references and 30-observation history; BBC/Guardian business; ONS releases; Bank of England announcements |
| World Window | Old Faithful, Yellowstone | Camera location/publisher/timezone; BBC/Guardian world news; matching Open-Meteo weather; USGS earthquakes |
| Spain & Local | Canal Sur Andalucía | Madrid and Seville reference weather, RTVE and El País headlines; these are labelled cities, not an inferred home location |
| Space & Earth | Space Live / Sen | ISS position; NASA news; NOAA planetary Kp and history; USGS earthquakes |
| British Classics | Midsomer Murders | Porridge, Minder and Only Fools and Horses programme/cast reference; existing My Lists and programme queue |
| Practical Living | Garden Rescue | Homestead Rescue reference, gardening/environment headlines, My Lists and programme queue |
| Technology & AI | Modern Marvels | BBC/Guardian technology; arXiv papers; official VS Code releases; programme queue |

First use starts Business and shuffles Business/World/Spain/Space every 15 minutes. Existing settings survive. MENU provides all seven domains, rotation pool/order/interval, Stay, source choice, picture-only layout and queue controls. Ticker speed, pause and reduced-motion paging are configurable. Focused information opens a scrollable source/date detail view. Existing explicit episode queue/completion behavior is preserved; live rotation never operates while the On Demand player is in front.

World alternatives include Truckee, underwater Scripps Pier and Greenhorn Ranch horse arena. Public camera player metadata is resolved on opening; signed/expiring routes are not persisted. Attribution, natural silence and darkness are explained. Native video is preferred at 720p; cameras offering only a 1080p rendition remain single-decoder playback and require Firestick memory measurement.

## Data and recovery

29 public feed definitions are in `app/src/main/assets/my-channel/panel-feeds.json`. Only visible-domain feeds are fetched, with at most two concurrent requests, 15-second call limits, 512 KiB responses, eight records and 40 chart points per feed. Caches are independent and bounded. Requests stop on domain exit/backgrounding; errors back off up to 16 minutes plus jitter. Original observation/publication dates remain visible; retrieved time is separate. ECB values are daily reference rates, not live trading prices. Programme spotlights are reference metadata, not claims about the current broadcast or catalogue availability.

Video recovery tries alternative routes belonging to the selected programme. Choosing another programme/camera is explicit. Last working routes are preferred; expired camera URLs are re-resolved. Picture and data failures are independent. Menus and Back remain usable on failure. The active video buffer target is 8 MiB and the player is released before replacement or backgrounding.

The existing HTTPS-only public-network restrictions and redirect/DNS protections apply to feeds and media. No private device endpoints, credentials, scraped account sessions or external-app launches are introduced.

## Evidence and remaining qualification

Local tests cover parser bounds, dates, cache round trips, unsafe camera URLs and captured live responses. TV instrumentation exercises all seven native layouts, 21 rapid changes, clocks and player release on background; screenshots and process-memory samples are collected. Such emulator results do not establish Firestick A/V performance or a long-duration memory soak.

The exact 0.9.10 APK backup and the previously installed/tested 0.9.11 APK are retained outside the repository. Install the local APK signed with the existing app identity using replacement, never uninstall or clear data.

Live stock indices/FTSE quotes, keyed official weather alerts, private telemetry and automatically selecting the next unwatched episode of an arbitrary series are not implemented in this build. Existing explicit episode queues remain available. Those wider roadmap items must not be represented as verified simply because their menus or reference metadata exist.
