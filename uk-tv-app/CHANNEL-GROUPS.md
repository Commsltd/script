# UK Television channel groups

The native catalogue groups channels by what viewers can watch. The existing M3U/XMLTV outputs remain unchanged. This works with the installed 0.3 app and the 0.4 candidate without an APK update.

## Categories

The 21 October 2026 catalogue contains 308 channel entries (including regional and quality variants):

| Group | Entries |
|---|---:|
| Main Uk | 16 |
| News & Politics | 20 |
| Films | 18 |
| Drama & Comedy | 25 |
| Entertainment | 15 |
| Documentaries | 18 |
| Crime & Mystery | 18 |
| Food & Lifestyle | 10 |
| Kids | 17 |
| Sport | 12 |
| Music | 20 |
| Regional & Local | 28 |
| World & Languages | 29 |
| Faith | 29 |
| Shopping | 8 |
| Live Events | 25 |

Favourites and All Channels remain available. All current services have an explicit category. Future unknown services retain an upstream genre when available, otherwise appear in More Channels.

## Grouping rules

- Familiar broadcasters lead Main UK, News, Films, Drama and Kids. Regional BBC services, STV, S4C, BBC Alba and local channels have their own group. Main UK retains the existing London/default services; regional identities and their schedules are never merged.
- Film4, Talking Pictures and GREAT! films sit alongside the other film channels. Scripted crime series belong in Drama & Comedy; factual crime and paranormal channels sit in Crime & Mystery.
- A catalogue country suffix does not determine viewing genre. UK Pluto drama, comedy, documentary and children's services are classified by their content. Adult animation such as South Park stays outside Kids.
- Music, sport and shopping channels previously filed under Other, International or Local move to their respective groups. Live Events contains the Red Button services in numeric order.
- Not 24/7 and Geo-blocked remain source information, not category or reliability judgements.
- Channel IDs, source IDs/URLs/headers, names, guide mappings and programmes are preserved. Existing favourites and playback health continue to refer to the same identities. Only group and ordering fields change.
- `01 MAIN UK` stays unchanged because installed version 0.3 uses it as the initial selection. Other labels can change through the feed.

The explicit mapping is in `tools/channel_groups.py`; it requires no additional network request. The native-data workflow watches this module and publishes updates through its existing feed.

## Validation

36 exporter, guide, artwork, resolver and category tests pass. A complete catalogue comparison preserves all 308 channel identities, 351 sources and 21,891 programme records. Ordering is deterministic and idempotent. The source M3U/XMLTV feed is not edited.

## Source assessment

- [iptv-org/epg](https://github.com/iptv-org/epg) is already the primary Freeview schedule collector in `.github/workflows/update-uk-tv-epg.yml`. The five supplementary feeds fill gaps after it. Other site-specific grabbers could fill further gaps after exact service and current-programme validation; they do not supply video streams.
- [Xtream-Codes-Library](https://github.com/rochana-sadila/Xtream-Codes-Library) describes itself as a collection of publicly available Xtream codes. That does not establish permission to use the accounts or stream availability. No shared accounts were tested or imported.
- Reddit and Telegram trial/code posts are leads, not a maintained or verified channel source. They have not been added as automatic dependencies. A provider-issued account with permission for its streams could be integrated through the app's existing additional-playlist and XMLTV support, keeping playback inside UK Television. There is no claim that any suggested shared code fixes ITV1, Channel 4, Channel 5 or YouTube.

## BBC regional fallback correction — 22 October 2026

BBC One and BBC Two each have one visible entry. The default service is tried first, followed by every retained regional/alternative source. Regional editions are no longer isolated from the main entry. This explicit exception does not merge ITV regions, +1 services, BBC News international editions or other channels. Source labels identify the region; the catalogue retains each regional schedule separately for players that support it. Older players display the default guide. The stream inventory is refreshed from exact BBC One/Two records in iptv-org and keeps the shipped backup list if that lookup fails; URLs are candidates, not claims of working playback.

## Country shelves — 0.7.0

Trinidad & Tobago, Ireland, USA, Canada, Australia and New Zealand have their own shelves. The app also shows existing channels in their country shelf without moving them out of genre groups or duplicating their IDs, favourites or source health. Explicit UK editions stay in their UK genre.

The curated additions use exact iptv-org service identities and public broadcaster/CDN streams. CBC Television, ABC TV Australia and Channel 9 retain all listed regional alternatives behind one national entry, with Toronto, NSW and Sydney first respectively. +1 editions are excluded from these fallback pools. Existing UK sources are untouched. The directory is refreshed by the existing hourly catalogue workflow, and a checked-in seed protects against directory outages.

TTT and CCN TV6 use their broadcaster's embedded player inside UK TV, including its own session and advertising. Back returns to the guide. CNC3 currently rejects the checked public page; it has not been represented by an unrelated stream. RTÉ One/Two, Virgin Media Ireland, CTV, Global, Seven, the main Network Ten feed Three NZ and TVNZ 1/2/DUKE still need usable sources. The directory’s three TVNZ CloudFront hosts no longer resolve; they were excluded after verification, rather than advertised as working channels. TVNZ’s current live service requires sign-in. USA includes the networks' explicitly named news services and PBS; news feeds are not labelled as the full ABC/CBS/NBC entertainment channels.

New channels do not receive invented programme schedules. Availability can depend on the current VPN route. A manifest responding on the build machine is not evidence of Firestick playback.
