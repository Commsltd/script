# Firestick guide startup crash — 24 October 2026

The actual Firestick (192.168.68.63, installed 0.9.5-preview / 17) repeatedly threw OutOfMemoryError in CatalogueParser.parse, ByteArrayOutputStream.toString. The latest attempt requested a 58,420,128-byte string within a 128 MiB heap. This is distinct from the unrelated com.android.tv emulator crash that falsely failed CI run 40103269797.

The parser now reads compressed or plain JSON incrementally, converting each programme directly to its record without retaining a complete document byte buffer, string and JSON tree. Existing schema, channel identity, URL, guide reference, nesting, size and programme count checks remain. Unknown metadata is skipped. No channels, stream alternatives or user data were removed.

Build and unit tests passed. CatalogueParserTest also passed at a 128 MiB JVM heap with a generated 60,000-programme gzip guide. The non-debuggable 0.9.7-preview APK (versionCode 19) was built from cb4af0f (the 0.9.5 feature baseline) plus this parser fix, Gson dependency, version increment and the current bundled catalogue. It deliberately excludes the separately staged live-source-refresh feature. Build source is /private/tmp/uktv-memory-fix/uk-tv-app; APK and logs are in .local/delivery/0.9.7-memory-fix.

User requested to perform playback testing themselves. Do not launch or navigate the app for device tests. An in-place installation preserves the database, favourites and lists.
