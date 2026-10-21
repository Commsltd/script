# UK Television upgrade validation — 22 October 2026

## BBC recovery correction and installed preview

The category change isolated regional BBC sources from the main entries. PR https://github.com/Commsltd/script/pull/14 restores all of them behind one BBC One entry (51 source links) and one BBC Two entry (16). Native catalogue run https://github.com/Commsltd/script/actions/runs/39693255502 succeeded. All previous source IDs survive; 23 regional/quality rows are combined, leaving 285 visible entries. Their schedules are retained separately; BBC News international editions, ITV and +1 services are not merged. The current feed has 144 visible entries with a default guide, 17,933 default programme records and 3,955 regional records. Programme totals change with refresh. The original 0.3 app was verified to contain the new pools and to play both BBC One and BBC Two on the current VPN.

The 0.4 player tries the default BBC service before regional alternatives, moves to an untried alternative before retrying a failed feed, and preserves bounded retries when no alternative remains. Source labels and the live information panel identify the playing region and use its own schedule. Recent failures outweigh older success records. After stable BBC playback, bounded background checks sample up to three backups per five minutes using the device network/VPN; reachability is kept separate from actual playback history. HTTP 401/403 alone is not called a proven geo block; known BBC UK feeds identify the expected VPN country as United Kingdom. This does not add a native VOD catalogue or resolve the previously unavailable ITV/C4/C5/YouTube sources.

Validation before installing: 38 Python tests, 30 Kotlin unit tests and Android lint pass. Five Firestick tests pass: four deterministic guide/player fixtures and an opt-in live test of BBC One/BBC Two, each sustaining playback for 20 seconds. The new fixture moves from HTTP 503 through a missing regional feed to a working regional feed within 12 seconds. Pause/resume and single-source recovery still pass.

The normal package was updated to 0.4.0-preview (code 4), preserving its preferences, channel database and artwork. The previous APK and complete preferences/databases/files archive were saved and SHA-256 verified before replacement in `.local/backups/before-bbc-recovery-2026-10-22/`. The different signing key required a reinstall; no additional playlists were configured and no favourites existed. Earlier statements below describe the previous validation stage.

Post-install live validation also passed in the normal package: BBC One London/default, BBC Two national/default, and a deliberately selected BBC One Scotland HLS alternative each sustained playback for at least 20 seconds. Report `bbc-playback.json` records the requested and actual region, source ID and status. The Scotland check verifies a real regional source, separately from the deterministic failure-injection test. The final build also restricts background probes to annotated built-in BBC sources so additional account playlist sources are not probed.

## Released guide improvement (earlier stage)

PR https://github.com/Commsltd/script/pull/11 was merged. Native catalogue run https://github.com/Commsltd/script/actions/runs/39687208815 succeeded. The published feed has 308 channel entries, 21,891 programmes and 167 entries with guide information, compared with 76 previously. The original installed 0.3.0 app was refreshed and its database checked independently: it contains the same 308 / 21,891 / 167 counts and published source revision. No app reinstall was required. All 167 had current programmes when checked. The additions are UK1 14, Open EPG 23, epg.pw 1, Pluto 44 and Samsung 9. All 29 Python tests passed. Original XMLTV/M3U outputs are unchanged; optional guide failures preserve the primary data. Server refresh is hourly.

## Released channel grouping

PR https://github.com/Commsltd/script/pull/13 was merged. Publication https://github.com/Commsltd/script/actions/runs/39689802870 and the normal app build/emulator smoke run https://github.com/Commsltd/script/actions/runs/39689802878 both succeeded. All 36 Python checks pass. The original installed 0.3 app was restarted with user approval and its stable database copy checked: all 308 channel identities, category assignments and sort orders match the published feed exactly. There are 16 viewing groups, including 16 Main UK entries (previously 49), and no current services in Other. Programme coverage remains 167 entries, with 21,884 current programme records. The catalogue snapshot builtAt is 1792623293302. Existing favourites were unchanged. The new category rail was also verified visually on the Firestick. No APK reinstall was needed; the original source M3U/XMLTV outputs remain unchanged. See `CHANNEL-GROUPS.md` for classification rules.

## Player candidate validation (before the BBC correction)

The original 0.3.0 app and data remain intact. A separate `com.commercialoutcomes.uktelevision.candidate` was used on AFTSS / Android 9 / 32-bit ARM with the user's VPN active. The source adds bounded reconnects, live-window recovery, temporary failure penalties, explicit diagnostics and current YouTube video identity resolution. No external viewing application is launched.

All three final Firestick fixture tests passed: direct HTTP 404 fallback with recorded success; pause for 21 seconds then resume for 21 seconds without a false stall timeout; single-source recovery after four HTTP 503 responses; and guide navigation, preview, favourites, source selection and full-screen/quick-guide/back controls. These generated video fixtures validate recovery and UI behaviour, not every live internet feed. The final lightweight build passes all 25 Kotlin unit tests and Android lint. GeckoView and its larger toolchain have been removed from the normal candidate and preserved on the local `codex/uk-tv-gecko-investigation` branch.

## YouTube investigation

The official Sky News embed in the system Amazon WebView reached ready/buffering but never started video. Both startup tests failed. A public Amazon developer report describes similar WebView 138 rendering problems, but this is corroborating evidence rather than proof of the exact cause here:
https://community.amazondeveloper.com/t/html5-video-shows-black-white-frosted-in-webview-based-apps-on-fire-tv-since-oct-11-same-page-plays-in-silk/29748

A bundled GeckoView 158 experiment was tested entirely inside UK TV. An initial helper-process WorkManager crash was corrected. Subsequent runs still failed: high memory/process use, failed instrumentation, and media decoder errors, including `NS_ERROR_DOM_MEDIA_FATAL_ERR`. Reducing process isolation and using a texture surface did not produce a successful live test. This experimental renderer is not suitable for release on this Firestick. Its code and logs are preserved for investigation; it must not be described as a working YouTube fix.

## Unresolved streams

On this VPN-connected Firestick the current ITV1 source terminates unexpectedly; Channel 4 and Channel 5 hosts fail DNS resolution. Three alternative netplus feeds timed out on both Mac and device. The current upstream catalogue still lists the same broken sources and no usable replacement for ITV1. Listings do not repair these video sources. No provider-login/DRM implementation or verified replacement stream has been added.

## Local evidence

`.local/reports/` contains build, Python, lint and device test logs; `.local/backups/` contains the original installed APK and data backup. Do not commit these private/device artefacts. The original 0.3 APK's signing certificate differs from the local build certificate. The later BBC correction used the verified backup/reinstall/restore procedure described above. Additional playlist secrets would need special care across any uninstall because their Android Keystore keys are non-exportable.
