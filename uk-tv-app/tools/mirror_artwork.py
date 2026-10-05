#!/usr/bin/env python3
"""Mirror artwork into the UK Television app-data branch and rewrite catalogue URLs.

Privacy goal: Fire TV requests artwork from the same GitHub data origin used for
the catalogue, rather than contacting every upstream logo/programme-image host.
Failed mirrors are left untouched so Hardened mode can still fall back to the
original; Strict mode will refuse those non-mirrored URLs.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import gzip
import hashlib
import io
import json
import mimetypes
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

from PIL import Image

MIRROR_PREFIX = "https://raw.githubusercontent.com/Commsltd/script/uk-tv-app-data/artwork/"
MAX_INPUT = 4 * 1024 * 1024
MAX_PROGRAMME_IMAGES = 1000
WORKERS = 16
ALLOWED_RASTER = {"image/jpeg", "image/png", "image/webp", "image/gif"}
SVG_TYPES = {"image/svg+xml"}

def key(url: str) -> str:
    return hashlib.sha256(url.encode("utf-8")).hexdigest()[:32]

def fetch(url: str) -> tuple[bytes, str]:
    request = urllib.request.Request(url, headers={"User-Agent": "UKTelevisionArtworkMirror/1.0"})
    with urllib.request.urlopen(request, timeout=10) as response:
        ctype = response.headers.get_content_type().lower()
        data = response.read(MAX_INPUT + 1)
        if len(data) > MAX_INPUT:
            raise ValueError("image exceeds 4 MB input limit")
        return data, ctype

def convert(url: str, purpose: str, out_dir: Path) -> tuple[str, int]:
    data, ctype = fetch(url)
    digest = key(url)
    if ctype in SVG_TYPES or urlsplit(url).path.lower().endswith(".svg"):
        if b"<svg" not in data[:4096].lower():
            raise ValueError("invalid SVG")
        name = digest + ".svg"
        (out_dir / name).write_bytes(data)
        return name, len(data)

    if ctype not in ALLOWED_RASTER:
        guessed, _ = mimetypes.guess_type(urlsplit(url).path)
        if guessed not in ALLOWED_RASTER:
            raise ValueError(f"unsupported content type {ctype}")

    image = Image.open(io.BytesIO(data))
    image.load()
    max_size = (320, 180) if purpose == "logo" else (640, 360)
    image.thumbnail(max_size, Image.Resampling.LANCZOS)

    if image.mode not in ("RGB", "RGBA"):
        image = image.convert("RGBA" if "transparency" in image.info else "RGB")

    name = digest + ".webp"
    target = out_dir / name
    save_args = {"format": "WEBP", "quality": 82 if purpose == "logo" else 76, "method": 4}
    if image.mode == "RGBA":
        save_args["lossless"] = purpose == "logo"
    image.save(target, **save_args)
    return name, target.stat().st_size

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalogue", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    data = json.loads(gzip.decompress(args.catalogue.read_bytes()))
    args.output_dir.mkdir(parents=True, exist_ok=True)

    logo_urls = []
    for channel in data.get("channels", []):
        url = channel.get("logo", "")
        if url.startswith(("http://", "https://")) and not url.startswith(MIRROR_PREFIX):
            logo_urls.append(url)

    programme_refs = []
    for programme in sorted(data.get("programmes", []), key=lambda p: p.get("start", 0)):
        url = programme.get("artwork", "")
        if url.startswith(("http://", "https://")) and not url.startswith(MIRROR_PREFIX):
            programme_refs.append(url)

    logo_urls = list(dict.fromkeys(logo_urls))
    programme_urls = []
    seen = set(logo_urls)
    for url in programme_refs:
        if url in seen:
            continue
        seen.add(url)
        programme_urls.append(url)
        if len(programme_urls) >= MAX_PROGRAMME_IMAGES:
            break

    purposes = {url: "logo" for url in logo_urls}
    purposes.update({url: "programme" for url in programme_urls})
    mirrored: dict[str, str] = {}
    failures: dict[str, str] = {}
    total_bytes = 0

    def job(item):
        url, purpose = item
        try:
            name, size = convert(url, purpose, args.output_dir)
            return url, name, size, None
        except Exception as exc:
            return url, None, 0, f"{type(exc).__name__}: {exc}"

    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        for url, name, size, error in pool.map(job, purposes.items()):
            if name:
                mirrored[url] = MIRROR_PREFIX + name
                total_bytes += size
            else:
                failures[url] = error or "unknown failure"

    channel_mirrors = 0
    for channel in data.get("channels", []):
        old = channel.get("logo", "")
        if old in mirrored:
            channel["logo"] = mirrored[old]
            channel_mirrors += 1

    programme_mirrors = 0
    for programme in data.get("programmes", []):
        old = programme.get("artwork", "")
        if old in mirrored:
            programme["artwork"] = mirrored[old]
            programme_mirrors += 1

    args.catalogue.write_bytes(gzip.compress(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        mtime=0
    ))

    status = {
        "channelLogoUrls": len(logo_urls),
        "programmeArtworkUrlsSelected": len(programme_urls),
        "uniqueMirrored": len(mirrored),
        "uniqueFailed": len(failures),
        "channelEntriesRewritten": channel_mirrors,
        "programmeEntriesRewritten": programme_mirrors,
        "mirrorBytes": total_bytes,
        "programmeArtworkCap": MAX_PROGRAMME_IMAGES,
    }
    args.output_dir.parent.joinpath("artwork-status.json").write_text(
        json.dumps(status, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(status, indent=2))

if __name__ == "__main__":
    main()
