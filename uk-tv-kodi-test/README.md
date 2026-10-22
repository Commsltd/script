# UK Television — Crew catalogue and source bridge

## 0.2.0 catalogue bridge / UK TV 0.9.0

**Prepared locally, not deployed or verified on the Firestick.** The user is watching; no device connection was made during this update.

The new UK TV **Browse The Crew** screen calls the installed Crew add-on's own directories through [Kodi Files.GetDirectory](https://kodi.wiki/view/JSON-RPC_API/v12#Files.GetDirectory). Films, series, supported category pages and returned next-page links are rendered in a native remote-controlled grid. Search invokes `movieSearchterm` or `tvSearchterm` with the user's text. It does not run a Kodi instance on the Mac, copy a provider API key, or present the unrelated Movy discovery feed as Crew's catalogue.

The authenticated loopback API adds `POST /catalogue` and advertises `catalogue-v1` in `/status`. Requests accept a movie/TV section, a query or an opaque folder handle returned by an earlier page. Only returned, allowlisted Crew catalogue directories are traversed. Playback, settings and external plugin paths cannot be requested through this endpoint. Film/series metadata is returned with canonical title IDs; provider URLs stay inside Kodi. Requests run asynchronously so `/status` and `/quit` remain responsive. Handles expire with the engine. The Android app has a bounded recent-title cache, while live navigation follows provider pages. Menu items requiring Kodi's own interactive tools, personal-account workflows or unsupported episode/calendar views are omitted.

### Deployment when viewing has finished

Update **both** the normal UK Television package to the staged 0.9.0-preview APK **and** the existing `service.uktelevision.crewtest` add-on to the supplied 0.2.0 ZIP. Do not clear data, replace Kodi/Crew, change the VPN, or overwrite the private bridge token. Back up the installed APK, app preferences and existing bridge directory first. Kodi must be stopped while replacing this bridge; install/update its ZIP through Kodi's add-on mechanism or the existing authorised deployment process, then restart it for the new service to load. The APK alone cannot add catalogue support to the old 0.1.0 bridge. The application detects that mismatch explicitly.

Pending device checks: Crew Films/TV menus, genres/years, next pages and search; selecting a title into normal details; provider multi-selection and persistence after reopening; exclusion of Crew without starting Kodi; starting/stopping the engine and returning to UK TV. Existing successful-source playback has not been established. No successful end-to-end catalogue or source playback is claimed from the local unit tests.

## Earlier 0.1.0 source-only experiment

# Firestick-only Crew experiment

UK Television 0.8.0 included an optional, locally provisioned **The Crew · Firestick test** action in a title's details. The regular catalogue and providers do not depend on Kodi. Kodi starts only when this test is selected; its startup screen may appear briefly, then UK TV returns automatically. Resolved sources play in UK TV's Media3 player. The Mac is not a runtime dependency.

This is an experiment, not a reliable new content provider. On 22 October 2026, the actual Crew 3.2.2 / module 3.2.26, running in Kodi 21.3 ARMv7 on the AFTSS Firestick, returned **zero free sources** for The Shawshank Redemption and Breaking Bad S3E1 on the existing VPN connection. Kodi was killed by Fire OS during an early background attempt and again in the final combined device run. One complete in-app lookup passed, but repeat reliability failed. No successful Crew media playback has been demonstrated. Free-source results must not be represented as guaranteed availability.

The bridge imports the separately installed publisher add-on, without copying its implementation into the Android app. Source lookup concurrency is limited to four for this 1 GB device. No premium accounts are configured. The bridge binds only `127.0.0.1:18765`; each request needs a per-install token. Only status, bounded title lookup, indexed source resolution and shutdown are supported. No arbitrary Python or Kodi commands are exposed. Tokens and media addresses must never be committed or logged.

Provision the token once in Kodi's `special://profile/addon_data/service.uktelevision.crewtest/token` and in UK TV's private `crew-test` preferences, key `token`. The service immediately moves its token into Kodi's private Android files directory with mode 0600. The app hides its test action on devices without this provisioning. Install this service with the separately obtained Crew dependencies and enable it in Kodi. Use official Kodi releases and the Crew publisher repository; do not bundle third-party add-on ZIPs or credentials in this repository.

Kodi shuts down when no sources are returned, the test fails, the user leaves the test, or a resolved remote source starts playing. If Kodi itself proxies the media over loopback, it stays up for that playback. The bridge also exits after ten minutes without a client request; UK TV sends a keepalive during proxy-backed playback. Native remote-source playback does not need Kodi to remain running. Exiting the normal VOD library never starts Kodi.

Bridge source: GPL-3.0-only. The Crew and Kodi retain their own authorship and licences. Dependencies are installed separately; this directory contains only the UK Television adapter.
