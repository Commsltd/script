# UK TV EPG for TiviMate

This folder builds a fresh XMLTV Electronic Programme Guide for the IPTV-org UK playlist:

`https://iptv-org.github.io/iptv/countries/uk.m3u`

It uses the maintained IPTV-org EPG grabber with Freeview listings, then adds safe aliases where the playlist and guide use different HD/SD variants.

## TiviMate URL

After the GitHub Action has run successfully, use:

`https://raw.githubusercontent.com/Commsltd/script/uk-tv-epg-output/guide.xml.gz`

In TiviMate:

1. Settings -> EPG -> EPG sources -> Add source.
2. Enter the URL above.
3. Settings -> Playlists -> UK playlist -> EPG sources -> enable it.
4. Settings -> EPG -> Clear EPG.
5. Update EPG.

## Behaviour

- Keeps exact IDs such as BBC/ITV/Channel 4 IDs where Freeview already matches.
- Adds schedule aliases where HD/SD variants carry the same schedule.
- Never maps a normal channel to a +1 channel or vice versa.
- Updates twice per day.
- Does not alter or proxy video streams; it only supplies listings data.
