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
