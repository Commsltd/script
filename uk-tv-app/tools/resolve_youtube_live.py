#!/usr/bin/env python3
"""Resolve official channel /live pages to current official YouTube video IDs.

This deliberately resolves only the public video identity.  It never extracts
YouTube media/CDN URLs.
"""
from __future__ import annotations
import argparse
import json
import subprocess
import re
import time
import urllib.request
import sys
from pathlib import Path

CHANNELS = {
    "SkyNews.uk": "https://www.youtube.com/@SkyNews/live",
    "EuronewsEnglish.fr": "https://www.youtube.com/@euronews/live",
    "ABCNewsLive.us": "https://www.youtube.com/@ABCNews/live",
    "CBSNews247.us": "https://www.youtube.com/@CBSNews/live",
    "NBCNewsNOW.us": "https://www.youtube.com/@NBCNews/live",
    "LiveNOWfromFOX.us": "https://www.youtube.com/@LiveNOWFOX/live",
}

def from_live_page(url: str) -> dict | None:
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "en-GB,en;q=0.9"})
        with urllib.request.urlopen(request, timeout=15) as response:
            text = response.read(3 * 1024 * 1024).decode("utf-8", "replace")
        marker = re.search(r"ytInitialPlayerResponse\s*=\s*", text)
        if not marker:
            return None
        info, _ = json.JSONDecoder().raw_decode(text[marker.end():])
        details = info.get("videoDetails", {})
        live = info.get("microformat", {}).get("playerMicroformatRenderer", {}).get("liveBroadcastDetails", {})
        video_id = str(details.get("videoId", ""))
        if not live.get("isLiveNow") or not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id):
            return None
        return {"videoId": video_id, "url": f"https://www.youtube.com/watch?v={video_id}",
                "title": details.get("title", ""), "channel": details.get("author", ""),
                "liveStatus": "is_live", "resolvedAt": int(time.time() * 1000)}
    except Exception as error:
        print(f"Live page lookup failed for {url}: {type(error).__name__}", file=sys.stderr)
        return None

def resolve(url: str) -> dict | None:
    cmd = [
        "yt-dlp",
        "--skip-download",
        "--ignore-no-formats-error",
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
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"YouTube resolver failed: {type(error).__name__}", file=sys.stderr)
        return from_live_page(url)
    if completed.returncode != 0 or not completed.stdout.strip():
        print(f"YouTube lookup failed for {url}: {completed.stderr[-500:].strip()}", file=sys.stderr)
        return from_live_page(url)
    try:
        info = json.loads(completed.stdout)
    except json.JSONDecodeError:
        return None

    video_id = str(info.get("id") or "")
    live_status = str(info.get("live_status") or "")
    is_live = bool(info.get("is_live"))
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id) or not (is_live or live_status == "is_live"):
        return None

    return {
        "videoId": video_id,
        "url": f"https://www.youtube.com/watch?v={video_id}",
        "title": str(info.get("title") or ""),
        "channel": str(info.get("channel") or info.get("uploader") or ""),
        "liveStatus": live_status or "is_live",
        "resolvedAt": int(time.time() * 1000),
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
    status = {"configured": len(CHANNELS), "resolved": len(result),
              "missing": sorted(set(CHANNELS) - set(result)),
              "status": "ok" if len(result) == len(CHANNELS) else "degraded"}
    args.output.with_name("youtube-status.json").write_text(json.dumps(status, indent=2)+"\n")
    if len(result) != len(CHANNELS):
        print("::warning::Some official live videos could not be resolved; see youtube-status.json")
    print(json.dumps({
        "configured": len(CHANNELS),
        "resolved": len(result),
        "channels": sorted(result),
    }, indent=2))

if __name__ == "__main__":
    main()
