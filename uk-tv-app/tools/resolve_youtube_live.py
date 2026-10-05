#!/usr/bin/env python3
"""Resolve official channel /live pages to current official YouTube video IDs.

This deliberately resolves only the public video identity.  It never extracts
YouTube media/CDN URLs.
"""
from __future__ import annotations
import argparse
import json
import subprocess
from pathlib import Path

CHANNELS = {
    "SkyNews.uk": "https://www.youtube.com/@SkyNews/live",
    "EuronewsEnglish.fr": "https://www.youtube.com/@euronews/live",
    "ABCNewsLive.us": "https://www.youtube.com/@ABCNews/live",
    "CBSNews247.us": "https://www.youtube.com/@CBSNews/live",
    "NBCNewsNOW.us": "https://www.youtube.com/@NBCNews/live",
    "LiveNOWfromFOX.us": "https://www.youtube.com/@LiveNOWFOX/live",
}

def resolve(url: str) -> dict | None:
    cmd = [
        "yt-dlp",
        "--skip-download",
        "--no-warnings",
        "--no-playlist",
        "--js-runtimes", "node",
        "--dump-single-json",
        url,
    ]
    try:
        completed = subprocess.run(
            cmd, capture_output=True, text=True, timeout=45, check=False
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if completed.returncode != 0 or not completed.stdout.strip():
        return None
    try:
        info = json.loads(completed.stdout)
    except json.JSONDecodeError:
        return None

    video_id = str(info.get("id") or "")
    live_status = str(info.get("live_status") or "")
    is_live = bool(info.get("is_live"))
    if not video_id or not (is_live or live_status == "is_live"):
        return None

    return {
        "videoId": video_id,
        "url": f"https://www.youtube.com/watch?v={video_id}",
        "title": str(info.get("title") or ""),
        "channel": str(info.get("channel") or info.get("uploader") or ""),
        "liveStatus": live_status or "is_live",
    }

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--previous", type=Path)
    args = parser.parse_args()

    previous = {}
    if args.previous and args.previous.exists():
        try:
            previous = json.loads(args.previous.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            previous = {}

    result = {}
    for channel_id, url in CHANNELS.items():
        resolved = resolve(url)
        if not resolved:
            prior = previous.get(channel_id) or {}
            prior_url = str(prior.get("url") or "")
            if prior_url.startswith("https://www.youtube.com/watch?v="):
                resolved = resolve(prior_url)
        if resolved:
            result[channel_id] = resolved

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "configured": len(CHANNELS),
        "resolved": len(result),
        "channels": sorted(result),
    }, indent=2))

if __name__ == "__main__":
    main()
