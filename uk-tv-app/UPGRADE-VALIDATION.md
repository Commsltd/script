# UK Television upgrade validation — 22 October 2026

## Released guide improvement

PR https://github.com/Commsltd/script/pull/11 was merged. Native catalogue run https://github.com/Commsltd/script/actions/runs/39687208815 succeeded. The published feed has 308 channel entries, 21,891 programmes and 167 entries with guide information, compared with 76 previously. All 167 had current programmes when checked. The additions are UK1 14, Open EPG 23, epg.pw 1, Pluto 44 and Samsung 9. All 29 Python tests passed. Original XMLTV/M3U outputs are unchanged; optional guide failures preserve the primary data. Server refresh is hourly.

## Player candidate — not released

The original 0.3.0 app and data remain intact. A separate `com.commercialoutcomes.uktelevision.candidate` was used on AFTSS / Android 9 / 32-bit ARM with the user's VPN active. The source adds bounded reconnects, live-window recovery, temporary failure penalties, explicit diagnostics and current YouTube video identity resolution. No external viewing application is launched.

Earlier device fixture tests passed direct HTTP 404 fallback, recorded successful playback, guide navigation, preview, favourites, source selection and full-screen/quick-guide/back controls. The extended pause/resume and single-source HTTP 503 tests still require final validation. Kotlin unit tests and Android lint passed before the last renderer configuration changes.

## YouTube investigation

The official Sky News embed in the system Amazon WebView reached ready/buffering but never started video. Both startup tests failed. A public Amazon developer report describes similar WebView 138 rendering problems, but this is corroborating evidence rather than proof of the exact cause here:
https://community.amazondeveloper.com/t/html5-video-shows-black-white-frosted-in-webview-based-apps-on-fire-tv-since-oct-11-same-page-plays-in-silk/29748

A bundled GeckoView 158 experiment was tested entirely inside UK TV. An initial helper-process WorkManager crash was corrected. Subsequent runs still failed: high memory/process use, failed instrumentation, and media decoder errors, including `NS_ERROR_DOM_MEDIA_FATAL_ERR`. Reducing process isolation and using a texture surface did not produce a successful live test. This experimental renderer is not suitable for release on this Firestick. Its code and logs are preserved for investigation; it must not be described as a working YouTube fix.

## Unresolved streams

On this VPN-connected Firestick the current ITV1 source terminates unexpectedly; Channel 4 and Channel 5 hosts fail DNS resolution. Three alternative netplus feeds timed out on both Mac and device. The current upstream catalogue still lists the same broken sources and no usable replacement for ITV1. Listings do not repair these video sources. No provider-login/DRM implementation or verified replacement stream has been added.

## Local evidence

`.local/reports/` contains build, Python, lint and device test logs; `.local/backups/` contains the original installed APK and data backup. Do not commit these private/device artefacts. The installed original APK's signing certificate differs from the local build certificate, so an in-place replacement has not been attempted. Additional playlist secrets would need special care across any uninstall because their Android Keystore keys are non-exportable.
