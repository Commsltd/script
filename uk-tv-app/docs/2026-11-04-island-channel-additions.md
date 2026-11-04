# Island broadcaster checks — 4 November 2026

Added Nevis Television and ZIZ TV (Channel 5) to World & Languages, labelled St Kitts & Nevis. Both official slactech HLS feeds passed ffprobe audio/video discovery. No Firestick playback verification or restart was performed. Existing channel identities and alternatives remain untouched.

Sources: https://nevistvonline.com/ and https://zizonline.com/tv/channel-5/ . Exact programme-guide mappings were not established; no synthetic programmes were added.

Other requested broadcasters are not added as dead placeholders:
- GBC Gibraltar: official player redirected to a captcha; no native stream verified.
- Compass TV: official site returned an access block, including in the browser.
- CMR TV: official Cloudflare player was located, but its HLS manifest returned HTTP 403.
- Greater Belize Media: official Castr player exposed HLS, but the manifest returned HTTP 403.
- Channel 82 Bermuda: official live page retrieved, but no usable native media source established.
- ABS Antigua & Barbuda: embedded Tego player identified; requires its session/API playback flow, not a verified static stream. No full integration implemented.
- VITI+: homepage advertises free FBC TV, FBC Sports and FBC2. All three player pages returned HTTP 403. Paid events are separate. No native streams verified.

Access failures are observations from this machine, not proof these broadcasters are offline or unavailable to viewers. Additional player integrations remain outstanding. Catalogue updates use the existing hourly publishing workflow; refresh the guide in the app after publication.
