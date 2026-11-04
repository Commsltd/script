# Koryo Front channels — 4 November 2026

Added KCTV, Athletic TV and Ryongnamsan TV under World & Languages, labelled North Korea. URLs and exact guide identities come from the site's publicly offered https://koryofront.org/api/channels playlist. All three HLS endpoints passed ffprobe discovery with H.264 video, AAC audio and WebVTT subtitle tracks. Subtitle language/accuracy and Firestick playback have not been verified.

Connected https://koryofront.org/api/epg to the existing bounded supplemental-guide pipeline, with exact KCTV, SportsTV and Ryongnamsan IDs. Existing hourly catalogue exports refresh this schedule. Current programme data passed local mapping checks for all three stations. No synthetic schedule data added.

The site states Athletic TV broadcasts weekends 17:00–23:00 Pyongyang time and Ryongnamsan weekdays 17:00–23:00; test cards outside broadcast hours are expected. Availability and schedules are supplied by Koryo Front, not independently confirmed broadcaster schedules. Existing alternatives and identities were preserved. No device restart required; refresh guide after publication.
