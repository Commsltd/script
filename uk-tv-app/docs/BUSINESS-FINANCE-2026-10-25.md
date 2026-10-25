# Business & Finance expansion — 25 October 2026

Location: **24 BUSINESS & FINANCE** in the guide. The candidate has **22 channels**, up from nine: **13 additions**, with four extra native alternatives added to existing channels. Country/name ordering is retained, with UK services first. This is a catalogue update; it does not require reinstalling the app.

Every newly added service decoded both audio and video in a short local FFmpeg check. This is not a Firestick playback or VPN-region guarantee. Existing channels, source identities, alternatives and favourites are preserved. No Firestick navigation or restart was performed for this work.

## Requested list accounted for

| Requested service | Result | Programme information |
|---|---|---|
| Bloomberg TV+ | Added separate USA FAST edition | Current exact Samsung USA schedule |
| Bloomberg Television | Added US edition; existing Europe edition retained | Current US and existing Europe schedules |
| Bloomberg Originals | Existing; added official native alternative | Existing Samsung USA schedule retained; no invented global-edition schedule |
| Yahoo Finance | Existing; three additional native alternatives | Existing schedule retained |
| Cheddar | Existing, two alternatives retained | Existing guide retained |
| Schwab Network | Added current official stream and verified alternative | Broadcaster's actual current show and description, timezone-aware; refreshed hourly |
| FINTECH.TV | Added official 24/7 HLS | No reliable current programme timetable verified |
| DeFiance Media | Added English linear stream | No reliable current programme timetable verified |
| Ticker News | Existing | No reliable current programme timetable verified |
| StockCharts TV | Not added: current supplied page embeds a YouTube playlist; a working continuous native stream was not verified | — |
| BizTV | Not added: official native master exists, but media decoding repeatedly timed out | — |
| Business Rockstars | Added; initial probe timeout recovered during the media decode check | No reliable current timetable verified |
| EntrepreneurTV | Added with three verified native alternatives | No exact current provider-edition schedule verified |
| Moconomy | Added English US distribution feed | No exact schedule for this edition verified; Polish/UK schedules not substituted |
| CGTN Global Biz | Added official English finance feed | No reliable current timetable verified |
| $FN TV | Not added: no working linear stream verified from supplied site | — |
| CNBC US | Not added: no unrestricted official full stream verified; provider access needed |
| CNBC Europe | Existing CNBC UK service retained; no separate authenticated Europe feed added | Existing UK guide retained |
| CNBC Asia | Not added: no unrestricted official full stream verified |
| CNBC World | Not added: no unrestricted official full stream verified |
| Fox Business | Not added: official preview is not treated as a full subscription stream |
| BNN Bloomberg | Existing service retained; not newly certified as unrestricted | Existing Canada guide retained |
| CNBC Africa | Not added: no usable public native stream verified |
| Business Day TV | Not added: no usable native stream verified from supplied broadcaster site |
| CNBC-TV18 | Added | Exact IN4 mapping wired; available listings expired on 22 October, so stale shows are not displayed as current |
| CNBC-TV18 Prime HD | Added | Current CNBC TV18 Prime schedule, distinct from regular TV18 and Hindi services |
| ET NOW | Not added: listed route returned a server error; no working replacement verified |
| NDTV Profit | Added official working rendition | Current exact India schedule |
| ausbiz | Existing | No reliable current timetable verified |
| tastylive | Added official JW live stream; broadcast/replay availability follows provider | No verified per-programme guide added |
| Business Today TV | Not added: a continuous usable native channel was not established |
| CNBC+ Global Stream | Not added: account/provider integration required |
| CNBC+ Data Stream | Not added: account/provider integration required |

Business Insider remains in the category too. At candidate validation, **12 of the 22 business channels had current programme listings**. The other ten stay honest about missing listings; no generic all-day blocks were fabricated.

## Refresh and validation

- The hourly publisher now includes the business seed and explicit guide mappings.
- Schwab's public player URL, FINTECH.TV's current public player bundle and tastylive's public live-stream ID are re-resolved during publication. Provider lookup failures retain verified seed routes.
- Directory updates are accepted only for the exact service/feed on an already approved provider host; unrelated hosts, credentials and other-language feeds are rejected.
- 75 Python catalogue tests pass, including preservation, idempotence, provider failure, host/feed restrictions and timezone-aware current-show conversion.
- Candidate: 1,062 total channels; all previous channel IDs and source IDs retained.

Primary discovery sources include [Schwab Network](https://schwabnetwork.com/), [FINTECH.TV](https://terminal.fintech.tv/), [EntrepreneurTV](https://www.entrepreneur.com/tv/), [DeFiance Media](https://www.defiance.media/), [Moconomy](https://www.moconomy.tv/), [tastylive](https://www.tastylive.com/livestream), [BizTV](https://biztv.com/watch-biztv) and the supplied broadcaster sites. Native route probes and candidate guide counts are recorded under `/private/tmp/uktv-business/`.
