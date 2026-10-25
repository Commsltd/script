# Audit fixes — UK Television 0.9.9

Prepared 25 October 2026, versionCode 21. The original audit records the pre-fix state; this document records the corrections and remaining validation boundaries.

## Corrected

- Programme imports insert at most 250 records per batch inside one Room transaction. Invalid late metadata rolls back all inserts and preserves the previous guide, stations and favourites. Downloaded gzip data is streamed to a bounded temporary file before the transaction, and artwork starts after guide activation.
- Artwork uses a published SHA-256 and conditional HTTP requests. Unchanged content avoids the archive body; failed updates retain the previous bundle. The publisher now excludes stale cache files and emits deterministic ZIPs. The current referenced bundle is 24,675,109 bytes versus 69,148,202 bytes previously.
- Renewed stream addresses clear attempted-source and health state for changed identities only. Probe cache identities include the address, headers and media kind. Healthy playback is not switched merely because a background refresh completes.
- Built-in stream probes and playback reject private destinations, including DNS results and redirects, and strip sensitive headers across origins, including child HLS requests. Explicit user-imported LAN streams remain supported. Required public HTTP feeds remain available outside Strict mode.
- Hidden guide screens stop collecting programme windows. VOD backgrounding stops polling and releases native player buffers, while retaining resume position and restoring quality controls on return.
- Stream inventory requests use ETags, parse each response once and retain last-success/error diagnostics. The existing hourly publisher remains the refresh schedule; these checks are bounded reachability checks, not proof that every channel decodes successfully every night.
- Build version and release names derive from Gradle. Smoke testing selects the portable tests rather than requiring a Kodi installation. Actions are pinned to commit SHAs, dependency versions are locked, and resolved artifacts have checksum verification metadata. Linux AAPT2 was fetched directly from Google's Maven repository for its CI checksum.
- Publication is held if the CI signing certificate differs from the existing installed app. The local normal build preserves the existing signer; no key rotation or private-key upload was performed.
- The packaged fallback catalogue was refreshed to 1,049 channels. User lists, favourites and source alternatives are preserved.

## Verification

- 97 JVM tests pass with a 128 MB heap, including a 60,000-programme batch-import regression.
- 69 Python catalogue/artwork/source tests pass, including deterministic archives that exclude stale files.
- Seven Android emulator tests pass against the nondebuggable normal APK: current bundled-guide seed, rollback/favourite preservation, encrypted playlist storage, guide/remote navigation, failed-source fallback, temporary-outage reconnect and regional fallback.
- Android lint: zero errors, 76 warnings. Normal APK signature verified.
- Final artifact and signature evidence are in `.local/audit-fixes-2026-10-25/` in the implementation worktree.

## Remaining limits

These local checks do not establish sustained Firestick performance under real-provider playback. The Firestick has not been navigated or restarted during these changes. Installation requires a viewing-safe opportunity; playback acceptance remains with the user.

CI source reconciliation is submitted separately because remote main had the old app source. A stable CI signing credential is still needed before that pipeline can publish an upgrade matching the installed identity. Existing debug-derived signing identity is retained for compatibility, not presented as a newly hardened production key. Checksum bootstrapping pins the retrieved artifacts; it is not an independent authenticity audit of every upstream dependency. R8 remains disabled pending provider/Kodi compatibility testing. Fire OS, Kodi/add-ons and external provider pages remain separate trust and reliability boundaries.
