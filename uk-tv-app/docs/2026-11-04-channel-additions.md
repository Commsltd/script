# Requested channel additions — 4 November 2026

Sports now sorts by visible channel name, regardless of country. Other category ordering is unchanged. Western Bound stays in Drama & Comedy.

## Added

- Sports: Outdoor America (USA), three verified native alternatives.
- Food & Lifestyle: Canal do Boi (Brazil); DD Kisan (India).
- World & Languages: MNB (Mongolia); RÚV2 (Iceland); KNR1 (Greenland).
- USA: FNX — First Nations Experience, with PBS and KVCR routes.
- Australia: C44 Adelaide. C31 Melbourne was already present and its stream passed checks.
- Faith: Makkah — Quran TV; Madinah — Sunnah TV, the two services embedded by deen2u.
- Crime & Mystery: Court TV (USA), Samsung route with exact programme guide and an alternate native route.
- WildEarth already exists; moved its existing channel identity from Spain into Documentaries, preserving stream and guide.

Every newly added native route passed ffprobe video/audio discovery on this machine. KNR1 was confirmed live through its official YouTube player metadata; the exporter refreshes the embed from KNR's exact KNR1 page hourly, avoiding the channel /live alias which currently selects KNR2. Firestick playback has not been tested.

## Not newly added / remaining

- Whakaata Māori already exists in New Zealand. Its existing route failed this machine's check; preserved rather than removed. Te Reo's candidate also failed.
- ICTV's current live webpage assembles a scheduled sequence of on-demand items via PseudoTvController; a stable native linear stream was not established.
- CCTV-11 and CCTV-17: official candidate endpoints timed out; unverified anonymous relay addresses were not substituted.
- TV Perú: broadcaster page returned 403 and both native HTTPS candidates failed.

No guide programmes are fabricated. New services without a verified matching XMLTV feed will show the app's existing no-programme-information state. Court TV has exact Samsung USA mapping; WildEarth retains its original exact guide. Other new services still need guide adapters.

All existing stream alternatives and favourites identities are preserved. No Android binary changes or device restart are required; use MENU → Refresh guide after catalogue publication.
