# UK Television — 0.2.0 preview

An original remote-first Fire OS / Android TV application using Warren's existing UK channel playlist and enriched programme guide. This is not TiviMate code and does not replace or alter TiviMate or the existing feeds.

## Install the preview

Use the APK attached to this GitHub preview release. Install it alongside TiviMate. It has its own application ID, `com.commercialoutcomes.uktelevision.preview`.

On an Android-based Firestick, enable **Settings → My Fire TV → Developer options → ADB debugging**. On models where Developer options is hidden, open About and select the device name seven times. Use Amazon's documented ADB process from a computer on the same network:

```sh
adb connect FIRESTICK_IP:5555
adb install -r UK-Television-0.2.0-preview.apk
```

Replace `FIRESTICK_IP` with the address shown on your own Firestick. Approve the computer's connection on the TV. Turn ADB debugging off afterwards. The APK can also be installed with an APK downloader on the Firestick after granting that downloader installation permission. No service-provider login is required for this app itself.

Compatibility target: Android 6 / API 23 or newer, including Android-based Fire OS 6, 7 and 8. It is not a Vega OS application. Real Firestick hardware/network playback still needs device testing even when the Android emulator checks pass.

## Included

- Proper horizontal programme grid; descriptions, episode titles and supplied metadata appear above it.
- Existing channel names, logos, categories and inline regional/source variants.
- Favourites, guide search (including synopses), previous channel and now/next information.
- Media3 HLS, DASH and supported transport-stream playback, subtitles, audio tracks and aspect-ratio controls.
- Source selector and automatic fallback across **all usable direct sources of the same service only**, ordered by locally observed reliability.
- Local playback success/failure history, recorded separately for manually selected connection profiles.
- Cache-first startup using a real guide snapshot bundled at build time.
- Transactional guide refresh that retains favourites and stream preferences and rejects invalid/truncated data.
- Automatic six-hour background refresh where Android permits it, plus refresh on launch and from the menu.
- First-class external-source support. Official YouTube/web fallbacks can sit beside direct HLS/DASH sources; they open the official player/site rather than scraping temporary YouTube media URLs.
- Multiple additional M3U playlists can be added from Settings. Exact matching tvg-id entries extend an existing channel's source pool; new channels remain grouped under their playlist.

**An available stream URL is not a guarantee of playback.** This app cannot revive a dead feed, remove geographic restrictions or supply rights/authentication for provider-only services. YouTube is not an invisible automatic fallback. LiveNOW from FOX is not Fox News Channel, and STV is not the London ITV1 schedule.

## Remote

| Control | Guide | Player |
|---|---|---|
| Up / down | Adjacent channel/inline variant | Adjacent channel/inline variant |
| Left / right | Previous/next programme; left edge opens categories | Show programme overlay |
| OK | Watch the live programme; show details for other times | Pause/resume or retry |
| Hold OK | Source selector | Source selector |
| Menu | Favourites, full details, search, categories, refresh, settings | Those options plus tracks, picture size and source preference |
| Back | Categories back to guide, then exit confirmation | Return to guide |
| Rewind / fast-forward | Move six hours | Previous channel / jump to live |

Guide times follow the Firestick's own time zone. Connection profiles distinguish reliability records only; they do not control a VPN. No background video playback is intended: the player releases its decoder when the app is backgrounded.

## Data and privacy

The app uses `https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/catalogue.json.gz`. This native JSON is generated from an immutable copy of `uk-tv-epg-output/playlist.m3u` and `guide.xml.gz`. The original EPG workflow and output branch remain unchanged.

A separate export workflow runs after a successful original EPG update and has a six-hour schedule as a safety net. Data conversion does not refresh the underlying broadcaster schedules more frequently than the original EPG job.

Favourites, source preferences and playback results stay in the app's private local storage. Additional-playlist URLs (which may contain provider credentials or tokens) are encrypted at rest with a non-exportable AES key held by Android Keystore. There are no analytics or cloud-account SDKs. The app deliberately does not create an Android MediaSession, reducing the playback metadata exposed through the normal OS media-session surface; Fire OS itself still cannot be made blind to an app running on Fire hardware. GitHub, image hosts and stream providers necessarily see the connections made to their servers. The catalogue itself is fetched over HTTPS. **Hardened compatibility** preserves HTTP-only legacy video streams when required so existing channels do not silently disappear. **Strict Privacy** blocks HTTP video, remote programme/channel artwork and external-app handoffs at runtime. Cleartext support remains declared in the compatibility APK because Android's manifest/network policy cannot be toggled per user at runtime; Strict Privacy enforces the block in the app's source-selection layer.

Programme descriptions are displayed when supplied, with an explicit missing-synopsis message otherwise. No descriptions or programme schedules are invented. The exporter deliberately refuses the old ITV1/London EPG assignment for the separately labelled STV stream.

## Build and tests

Pinned toolchain: JDK 17, Gradle 8.13, Android SDK 35, AGP 8.9.2, Kotlin 2.1.20, Media3 1.8.0. CI performs Python exporter tests, Kotlin unit tests, Android lint, APK-signature validation and Android API 28 emulator smoke tests (launch, guide, remote navigation and options). Screenshots and reports are attached to the workflow. These are not a claim that all internet channels play.

For a local build, install JDK 17, Gradle 8.13 and Android SDK 35, then:

```sh
git clone --depth 1 --branch uk-tv-epg-output https://github.com/Commsltd/script.git /tmp/uk-tv-feed
python3 tools/export_manifest.py --source /tmp/uk-tv-feed --revision "$(git -C /tmp/uk-tv-feed rev-parse HEAD)" --output app/src/main/assets/catalogue.json.gz
gradle testDebugUnitTest lintDebug assembleDebug
```

## Signing and upgrades

The initial preview is **debug-signed**, not production-signed. No private signing key is committed, cached publicly or attached to a release. A new clean CI build may use a different debug certificate, so later previews can require uninstall/reinstall (which clears that preview's local preferences). Do not treat this as a stable automatic-update channel yet.

For private, repeatable release signing, provide GitHub Actions secrets `TV_KEYSTORE_B64`, `TV_KEYSTORE_PASSWORD`, `TV_KEY_ALIAS` and `TV_KEY_PASSWORD`. The workflow already supports them and deletes its temporary keystore after signing. Preserve the private keystore securely yourself. Production identity is `com.commercialoutcomes.uktelevision`, separate from preview installs.

## Deliberately not included yet

DVR, persistent timeshift, cloud accounts, provider-login/DRM integrations, scraped/embedded YouTube playback, an automatic app installer, and production signing-key provisioning. The supported update process today is native-data refresh plus manual installation of a new APK.

## Technical sources

- https://developer.android.com/training/tv/playback/compose
- https://developer.android.com/media/media3/exoplayer/hello-world
- https://developer.android.com/training/data-storage/room
- https://developer.android.com/develop/background-work/background-tasks/persistent-work
- https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html
- https://developer.android.com/studio/publish/app-signing
