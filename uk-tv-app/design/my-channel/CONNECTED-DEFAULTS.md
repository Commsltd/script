# Connected default feeds — 25 October 2026

This local browser prototype now has real HLS playback, automatic route failover and refreshed feed data. It is not installed in the Android app.

| Screen | Default video / alternatives | Data |
|---|---|---|
| Business | Bloomberg Europe; Bloomberg Originals candidate fallback | ECB daily FX, BBC Business |
| World | Space Live / Sen Earth view; Adventure Earth documentary fallback | BBC World |
| Spain | Canal Sur Andalucía; Canal Sur Más Noticias | El País España |
| Space | Space Live / Sen, two routes | NOAA planetary Kp, NASA news |
| British Classics | Mr Bean live action; animated alternative | BBC entertainment news |
| Practical Living | Garden Rescue / Tiny House Nation candidates; Top Gear and Adventure Earth fallbacks | BBC Science & Environment |
| Technology & AI | Modern Marvels engineering candidate; Bloomberg business coverage fallback | BBC Technology |

Fallback broadcaster names are displayed; alternatives are not represented as the same programme. These linear defaults do not pretend to offer Porridge or a specific Homestead Rescue episode. Linear channels rotate on the timer. Full VOD episode queues are still future Android integration.

Data collector: `python3 connect_feeds.py --loop`, every five minutes; bounded requests, timestamps, preserved stale values. Keys/account setup are not required for these defaults. Cached feed failure is explicitly labelled. Browser autoplay starts muted; Start with sound is a browser gesture requirement.

Validation: stream-checks.json records ffprobe video/audio discovery per route. Bloomberg decoded at 1280x720 with progressing playback in the browser. This is not Firestick playback evidence. Some candidate alternatives fail and remain behind working routes.

Use: serve this directory on localhost, start the data collector, open index.html through the local HTTP server. One HLS decoder, bounded buffers, teardown on hidden tab/guide, 16-second failure timeout, retry after 60 seconds when all routes fail. No public server deployed.
