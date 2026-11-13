# My Channel 0.9.13 — editorial and programme revision

Catalogue run 43466587922, attempt 2, completed successfully including publication on 13 November 2026. Subsequent scheduled exports are also passing.

## Seven domains

- Business: Bloomberg picture; sourced news summaries; FTSE 100, S&P 500 and IBEX 35 delayed quotations and daily changes; ECB currency histories and calculated context; ONS forthcoming economic releases; Bank of England bulletins.
- World Window: live cameras, camera-specific map, weather, local clock and description; rotating world news; USGS earthquakes. Unpinned cameras change on each return; an explicitly selected camera stays selected.
- Spain: Spanish picture, El País and secondary Spanish publisher headlines and summaries, AEMET warnings, Madrid and Seville forecasts. These are labelled defaults, not inferred home locations.
- Space: Sen picture, ISS position/map/telemetry, NOAA Kp history, NASA summaries and upcoming launches from The Space Devs.
- British Classics: default classics collections and existing My Lists, actual selected-channel now/next, personal programme queue.
- Practical Living: gardening/outdoor picture, current programme context, practical reading and outdoor conditions.
- Technology & AI: engineering picture, technology summaries, arXiv paper abstracts and official tool release information.

Every domain retains a live picture, editorial panels, ticker, data band and clock. Headlines rotate, similar stories are deduplicated and the ticker omits the lead story. Selecting information opens its complete source detail. All seven domains are included in the initial rotation; existing saved settings are preserved.

## Personal programme flow

My Lists → Build a personal programme mix uses relevant saved collections and favourites, respecting enabled providers. It resolves exact episodes, skips completed items and tries another permitted provider on failure. Work is bounded to five titles, eight episode/season requests per title and two providers. The queue is reviewable, reorderable and persistent; playback resumes through the existing player. Autoplay is opt-in. A manual Next advances without marking watched; only actual player completion records completion. Kodi/Crew is excluded from automatic mixes to avoid switching apps.

## Resource and failure behaviour

One video decoder, 8 MB target video buffer, preferred 720p / 3 Mbps ceiling. Only visible-domain feeds are fetched, with two data workers, bounded responses, disk caching, cancellation, TTLs and failure backoff. Backgrounding releases the player and stops refresh/ticker work. Failed information panels do not stop the picture; stale data is labelled. Delayed market data and daily FX are explicitly distinguished from live quotations.

## Qualification

129 unit tests passed, including captured live payload parsing for six new feeds, expired warning rejection, prior-session index changes, provider fallback, completed-episode skipping and bounded season work. Android lint and the normal preview APK build passed. Native tests cover seven rendered domains, rapid switching, background release, queue persistence/completion semantics and existing guide/playback/privacy behaviour. Exact final native results and captures accompany the delivery artifact.

No Firestick installation is performed by this revision. Existing rollback APKs remain separate and unchanged. Public feeds and provider playback can vary by region; native emulator proof is not physical Firestick acceptance.
