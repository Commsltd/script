# Held update 0.9.10

Not installed: the user chose to keep watching. Do not merge/publish the category changes until installation is authorised; the old app generates country shelves using the previous numbers.

Favourites and All Channels remain first. Viewing categories follow: Main UK; Drama & Comedy; Films; News & Politics; Business & Finance; Documentaries; Crime & Mystery; Entertainment; Sport; Live Events; Food & Lifestyle; Music; Regional & Local; Kids; World & Languages; Faith; Shopping; More Channels (when present).

Country shelves end the list alphabetically: Australia, Canada, Ireland, New Zealand, Spain, Trinidad & Tobago, USA. Existing cached category labels are normalised in the app. All 1,062 channels were compared before/after: only group and order changed, with repeat application stable.

## Business channel audio

User reports silence in both preview and fullscreen for Business Rockstars and EntrepreneurTV. Passive Firestick log inspection showed repeated PES parser warnings and no active audio output during the silent playback. No channel switching, restart or install was performed.

Both Publica streams contain audible audio when decoded externally. Local Android emulator A/B testing reproduced no audio output and a null audio format with default chunkless HLS preparation. Disabling chunkless preparation produced an advancing audio output on both feeds. The EntrepreneurTV KlowdTV alternative produced audio under both settings.

The app now probes actual segments for getpublica.com HLS streams only, retaining the existing request policy, retry handling and bounded playback buffers. Other providers keep the existing startup behaviour. The opt-in BusinessAudioLiveTest compares default preparation with the actual app source factory and asserts advancing audio for the latter. This is emulator evidence, not confirmation of the fix on the Firestick.

Validation: 75 catalogue tests; JVM unit tests; normal-build lint; 0.9.10 normal APK build. Category-only CI build and smoke also passed before the audio change. Final live diagnostic results are retained in /private/tmp/uktv-audio-fixed-instrumentation.log.

## Movy VOD freeze while playing Homestead Rescue S14E1

The Firestick recorded an input-dispatch ANR in VodPlayerActivity at 15:19 device-log time, then killed and restarted UK Television at OnDemandActivity. The app and WebView renderer were both consuming CPU while kswapd accounted for 29% CPU; the native PlayerControlView main thread was waiting in ART. This supports system memory pressure during simultaneous retained web/native playback, not a missing episode diagnosis.

After the native renderer produces its first frame, the provider document is now unloaded to about:blank, freeing its scripts and media buffers. Returning to stream/quality controls reloads the provider page and restores position before presenting controls. Native background release does not reload the hidden webpage; shutdown cancels restoration. Dulo episode lookup and Reload source retain the original page identity.

Validation: JVM tests, normal-build lint and APK build passed. VodHandoffMemoryTest passed in the separate local emulator, checking actual native video output, removal of the provider document and restoration of provider controls. This is not a successful playback test of Homestead Rescue S14E1 on the Firestick. Device logs were read passively; no Firestick install, restart or navigation occurred. The installation hold remains in effect.
