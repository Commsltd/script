# UK Television — 0.6.1 preview

An original remote-first Fire OS / Android TV application using Warren's existing UK channel playlist and enriched programme guide. This is not TiviMate code and does not replace or alter TiviMate or the existing feeds.

## Install the preview

Use the APK attached to this GitHub preview release. It uses the application ID `com.commercialoutcomes.uktelevision.preview`.

On an Android-based Firestick, enable **Settings → My Fire TV → Developer options → ADB debugging**. On models where Developer options is hidden, open About and select the device name seven times. Use Amazon's documented ADB process from a computer on the same network:

```sh
adb connect FIRESTICK_IP:5555
adb install -r UK-Television-0.6.1-preview.apk
```

Replace `FIRESTICK_IP` with the address shown on your own Firestick. Approve the computer's connection on the TV. Turn ADB debugging off afterwards. The APK can also be installed with an APK downloader on the Firestick after granting that downloader installation permission. No service-provider login is required for this app itself.

Compatibility target: Android 6 / API 23 or newer, including Android-based Fire OS 6, 7 and 8. It is not a Vega OS application. Real Firestick hardware/network playback still needs device testing even when the Android emulator checks pass.

## Upgrade status

The preview includes a native On Demand library for Movy, Plex, Pluto TV and Tubi, with films, series, provider filters and shared title/source cards. Select **Menu → On Demand** from live TV or the guide. Provider players stay inside UK Television, retain their adverts and consent controls, and use the full screen without a permanent UK TV toolbar. The supplied television artwork is used for the app icon and Fire TV tile.

Live-channel alternatives are retained without a three-source cap. The published catalogue includes 51 BBC One, 16 BBC Two and 11 GB News source candidates; these counts are alternatives, not guarantees of independent or working feeds. BBC national feeds precede regional fallbacks. Playback tries untried alternatives before retrying a failed source. Background reachability checks rotate through annotated built-in alternatives, up to three every five minutes after stable playback. User-added account playlists are not probed automatically.

Two official Sky News feeds sustained playback on the Firestick. The official YouTube embed also passed playback and remote pause/resume checks, but took about 55 seconds to start in that run; fast startup is not established. ITV1, Channel 4 and Channel 5 remain unverified/unresolved on this connection. See `UPGRADE-VALIDATION.md` for current device evidence and limitations.

The expanded guide and 16 viewing categories remain available. Optional XMLTV schedules are used only for explicitly mapped services or exact Pluto stream IDs with no existing guide. The optional sources are Open EPG, EPGShare UK1, epg.pw GB and the UK Pluto/Samsung guides; unavailable or malformed optional guides leave the primary guide intact. Regional and +1 schedules are not guessed.

Version 0.6.1 checks decoded video continuously, so a feed that continues playing audio after losing its picture can advance to another source. The six-second picture timeout pauses while playback is paused or buffering. After the initial default/pinned source, alternatives with recent successful playback are preferred; every retained source remains eligible. Live buffering is bounded to reduce memory pressure on the 1 GB Firestick. Guide grouping runs off the UI thread, guide/VOD result lists are cached until their inputs change, and recent or unchanged guide snapshots avoid repeated downloads and database replacement. Failed artwork downloads remain eligible for retry.

## On Demand

Version 0.6 adds combinable genre, exact release year/decade, format and provider filters, plus Featured, title A–Z/Z–A and newest/oldest sorting. Use the visible filter row or **Menu**. **Clear filters** resets the view; **More titles** expands the loaded catalogues. Years and genres come from provider metadata/labelled genre collections; missing values remain under All and can be selected as Unknown. These controls apply to the loaded library rather than every title on the internet.

Open a title and choose **Add to favourites** or **Save to lists**. The **Favourites** and **My lists** tabs show saved items, including when absent from the latest provider home page. Lists can be created from the menu or while saving a title; a title may belong to several lists. Open a list and use **Menu → Manage this list** to rename or delete it. Details let you remove a title from the current list. Saved title metadata, provider links and membership remain in private local app storage across relaunches; no provider account is needed. Playback availability is still checked by the provider when opened.

The native library gathers public provider catalogues through the Firestick's current connection and expands beyond home-page highlights. **More titles** loads further discovered collections; **Search** filters titles already loaded, not every film available online. Select a title to choose a provider. Matching title, year and format combine available provider links without merging remakes. Series open native season/episode lists where the provider supplies usable metadata.

**Back** returns from playback to the library; **Menu** offers another listed source, reload or return. Tubi also has a full-screen command. Tubi requests the desktop web player to avoid its mobile preview/app-install screen. Movy's TV player is scaled to the actual WebView viewport so the right and bottom controls remain visible. Plex and Pluto use their own full-size web players. Catalogue availability, advertising, consent and stream behaviour depend on the provider and connection; a listed title is not proof that every source will play.

The current library is a collection of discovered titles, not a complete provider catalogue. A failed provider is reported under **Menu → service status**; retry **Refresh** after changing connection. Automatic fallback during VOD playback, persistent watch history and cross-provider episode alternatives are not yet implemented. Live-TV failover is separate. A generic HTTP 403 is not labelled a proven geographic restriction. VOD does not yet infer a reliable VPN-country recommendation for every provider error.

`myfreemovies.site` is excluded from playable sources: both inspected film players stopped at ten seconds for registration and did not establish full-film availability. No registration or subscription was created.

## Included

- Proper horizontal programme grid; descriptions, episode titles and supplied metadata appear above it.
- Existing channel names and logos, 16 genre/regional categories, and optional inline source variants.
- Favourites, guide search (including synopses), previous channel and now/next information.
- Media3 HLS, DASH and supported transport-stream playback, subtitles, audio tracks and aspect-ratio controls.
- Source selector and automatic fallback across **all usable direct sources of the same service only**, ordered by locally observed reliability.
- Local playback success/failure history, recorded separately for manually selected connection profiles.
- Cache-first startup using a real guide snapshot bundled at build time.
- Transactional guide refresh that retains favourites and stream preferences and rejects invalid/truncated data.
- Automatic hourly background refresh where Android permits it, plus refresh on launch and from the menu.
- First-class in-app YouTube support. Official YouTube fallbacks sit beside direct HLS/DASH sources and play inside UK Television through the official embedded player; the app does not extract temporary YouTube CDN URLs and does not launch the YouTube app or a browser.
- Multiple additional M3U playlists can be added from Settings. Exact matching tvg-id entries extend an existing channel's source pool; new channels remain grouped under their playlist.

**An available stream URL is not a guarantee of playback.** This app cannot revive a dead feed, remove geographic restrictions or supply rights/authentication for provider-only services. For configured services, direct feeds are tried first and the official in-app YouTube player can be the final same-service fallback. LiveNOW from FOX is not Fox News Channel, and STV is not the London ITV1 schedule.

## Remote

| Control | Guide | Player |
|---|---|---|
| Up / down | Adjacent channel/inline variant | Adjacent channel/inline variant |
| Left / right | Previous/next programme; left edge opens categories | Show programme overlay |
| OK | Watch the live programme; show details for other times | Pause/resume or retry |
| Hold OK | Source selector | Source selector |
| Menu | On Demand, favourites, full details, search, categories, refresh, settings | Those options plus tracks, picture size and source preference |
| Back | Categories back to guide, then exit confirmation | Return to guide |
| Rewind / fast-forward | Move six hours | Previous channel / jump to live |

Guide times follow the Firestick's own time zone. Connection profiles distinguish reliability records only; they do not control a VPN. No background video playback is intended: the player releases its decoder when the app is backgrounded.

## Data and privacy

The app uses `https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/catalogue.json.gz`. This native JSON is generated from an immutable copy of `uk-tv-epg-output/playlist.m3u` and `guide.xml.gz`. The original EPG workflow and output branch remain unchanged.

A separate export workflow runs after a successful original EPG update and has an hourly schedule for short-horizon FAST guides. Data conversion does not refresh the underlying broadcaster schedules more frequently than the original EPG job.

Favourites, source preferences and playback results stay in the app's private local storage. Additional-playlist URLs (which may contain provider credentials or tokens) are encrypted at rest with a non-exportable AES key held by Android Keystore. The app does not send usage analytics or provide cloud accounts. The app deliberately does not create an Android MediaSession, reducing the playback metadata exposed through the normal OS media-session surface; Fire OS itself still cannot be made blind to an app running on Fire hardware. GitHub and stream providers necessarily see the connections made to their servers. Curated image hosts are contacted by the GitHub build pipeline rather than by the Firestick once their artwork has been mirrored. The catalogue itself is fetched over HTTPS. **Hardened compatibility** preserves HTTP-only legacy video streams when required so existing channels do not silently disappear. **Strict Privacy** blocks HTTP video and direct third-party artwork requests. Curated channel/programme artwork is mirrored by the data pipeline, delivered as one validated bundle, and rendered from local Fire TV storage; official YouTube fallbacks remain inside UK Television. Cleartext support remains declared in the compatibility APK because Android's manifest/network policy cannot be toggled per user at runtime; Strict Privacy enforces the block in the app's source-selection layer.

On Demand contacts provider catalogue, poster and player hosts directly. Strict Privacy disables remote VOD posters and additional third-party cookie acceptance; provider pages still make their own HTTPS requests. Provider cookies remain available across player switches so sessions and consent choices are not erased.

Programme descriptions are displayed when supplied, with an explicit missing-synopsis message otherwise. No descriptions or programme schedules are invented. The exporter deliberately refuses the old ITV1/London EPG assignment for the separately labelled STV stream.

## Build and tests

Pinned toolchain: JDK 17, Gradle 8.14.3, Android SDK 35 and Media3 1.8.0. CI performs Python exporter tests, Kotlin unit tests, Android lint, APK-signature validation and Android API 28 TV emulator smoke tests (launch, guide, remote navigation and options). Screenshots and reports are attached to the workflow. These are not a claim that all internet channels play.

For a local build, install JDK 17, Gradle 8.14.3 and Android SDK 35, then:

```sh
git clone --depth 1 --branch uk-tv-epg-output https://github.com/Commsltd/script.git /tmp/uk-tv-feed
python3 tools/export_manifest.py --source /tmp/uk-tv-feed --revision "$(git -C /tmp/uk-tv-feed rev-parse HEAD)" --output app/src/main/assets/catalogue.json.gz
gradle testDebugUnitTest lintDebug lintTvPreview assembleTvPreview
```

## Signing and upgrades

The installable `tvPreview` build uses the normal, non-debuggable runtime and the existing preview application ID. It is **debug-signed**, not production-signed. `assembleDebug` remains available for development and private data inspection; both builds use the same local signing key and can update each other without clearing data. R8 shrinking is not yet enabled. No private signing key is committed, cached publicly or attached to a release. A new clean CI build may use a different debug certificate, so later previews can require uninstall/reinstall (which clears that preview's local preferences). Do not treat this as a stable automatic-update channel yet.

For private, repeatable release signing, provide GitHub Actions secrets `TV_KEYSTORE_B64`, `TV_KEYSTORE_PASSWORD`, `TV_KEY_ALIAS` and `TV_KEY_PASSWORD`. The workflow already supports them and deletes its temporary keystore after signing. Preserve the private keystore securely yourself. Production identity is `com.commercialoutcomes.uktelevision`, separate from preview installs.

## Deliberately not included yet

DVR, persistent timeshift, cloud accounts, provider-login/DRM integrations, scraped YouTube media URLs, an automatic app installer, and production signing-key provisioning. Official YouTube embeds are supported inside the app. The supported update process today is native-data refresh plus manual installation of a new APK.

## Technical sources

- https://developer.android.com/training/tv/playback/compose
- https://developer.android.com/media/media3/exoplayer/hello-world
- https://developer.android.com/training/data-storage/room
- https://developer.android.com/develop/background-work/background-tasks/persistent-work
- https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html
- https://developer.android.com/studio/publish/app-signing

### BBC recovery update (22 October 2026)

BBC One and BBC Two each appear once, with all previous regional alternatives retained in their source pool. The current inventory is 51 BBC One and 16 BBC Two source links, including additional official URLs. These are source candidates, not independent providers or a guarantee every URL works. Default feeds precede regional editions. The 0.4 preview switches to another source before retrying a failed one, and identifies the region and corresponding schedule during live playback. See `UPGRADE-VALIDATION.md` for installed-device evidence and rollback details.
