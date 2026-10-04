#!/usr/bin/env python3
"""Build a Freeview XMLTV guide whose IDs match an IPTV-org M3U playlist.

Commands:
  prepare <freeview.channels.xml> <playlist_url> <custom.channels.xml> <aliases.json>
  apply   <guide.xml> <aliases.json> <guide.xml.gz>

Only Python's standard library is used.
"""

from __future__ import annotations

import copy
import gzip
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

UA = "Mozilla/5.0 (UK-EPG-Builder/1.0)"
TVG_ID_RE = re.compile(r'tvg-id\\s*=\\s*"([^"]+)"', re.I)


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


def fetch_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=45) as r:
        return r.read().decode("utf-8", errors="replace")


def split_id(cid: str) -> tuple[str, str]:
    if "@" in cid:
        base, variant = cid.split("@", 1)
        return base, variant
    return cid, ""


def candidate_score(target: str, source: str) -> int:
    if target == source:
        return 100000

    tb, tv = split_id(target)
    sb, sv = split_id(source)
    if tb.casefold() != sb.casefold():
        return -100000

    t = tv.casefold()
    s = sv.casefold()
    score = 100

    if t == s:
        score += 1000

    tp = "plus1" in t or "+1" in t
    sp = "plus1" in s or "+1" in s
    if tp != sp:
        return -100000
    if tp and sp:
        score += 250

    region_tokens = [
        "london", "anglia", "border", "central", "granada", "meridian",
        "tynetees", "wales", "westcountry", "yorkshire", "scotland",
        "northernireland", "northwest", "northeast", "southwest",
        "southeast", "westmidlands", "eastmidlands", "channelislands",
        "east", "south", "west", "uk"
    ]
    for token in region_tokens:
        if token in t:
            score += 80 if token in s else -25

    if "hd" in t:
        score += 30 if "hd" in s else 0
    elif "sd" in t:
        score += 20 if "sd" in s else 0

    if not s:
        score += 15

    return score


def parse_playlist_ids(text: str) -> list[str]:
    seen = set()
    out = []
    for m in TVG_ID_RE.finditer(text):
        cid = m.group(1).strip()
        if cid and cid not in seen:
            seen.add(cid)
            out.append(cid)
    return out


def prepare(channels_path: str, playlist_url: str, output_path: str, aliases_path: str) -> None:
    playlist = fetch_text(playlist_url)
    playlist_ids = parse_playlist_ids(playlist)
    if not playlist_ids:
        die("No tvg-id values found in playlist")

    tree = ET.parse(channels_path)
    root = tree.getroot()
    all_channels = [c for c in root.findall("channel") if c.get("xmltv_id")]
    by_id = {}
    by_base = {}

    for c in all_channels:
        cid = c.get("xmltv_id", "")
        by_id.setdefault(cid, c)
        base, _ = split_id(cid)
        by_base.setdefault(base.casefold(), []).append(c)

    chosen: dict[str, ET.Element] = {}
    aliases: dict[str, str] = {}
    unmatched = []

    for target in playlist_ids:
        if target in by_id:
            chosen[target] = by_id[target]
            continue

        base, _ = split_id(target)
        candidates = by_base.get(base.casefold(), [])
        if not candidates:
            unmatched.append(target)
            continue

        ranked = sorted(
            ((candidate_score(target, c.get("xmltv_id", "")), c) for c in candidates),
            key=lambda x: x[0],
            reverse=True,
        )
        best_score, best = ranked[0]
        if best_score < 0:
            unmatched.append(target)
            continue

        source_id = best.get("xmltv_id", "")
        chosen[source_id] = best
        aliases[target] = source_id

    out_root = ET.Element("channels")
    for cid in sorted(chosen, key=str.casefold):
        out_root.append(copy.deepcopy(chosen[cid]))

    ET.indent(out_root, space="  ")
    ET.ElementTree(out_root).write(output_path, encoding="utf-8", xml_declaration=True)
    Path(aliases_path).write_text(
        json.dumps(aliases, indent=2, sort_keys=True) + "\\n",
        encoding="utf-8",
    )

    print(f"Playlist IDs: {len(playlist_ids)}")
    print(f"Freeview source channels selected: {len(chosen)}")
    print(f"Aliases to create: {len(aliases)}")
    print(f"Playlist IDs with no Freeview guide match: {len(unmatched)}")
    if unmatched:
        print("No Freeview match (first 30):")
        for cid in unmatched[:30]:
            print(f"  - {cid}")


def apply_aliases(guide_path: str, aliases_path: str, gzip_path: str) -> None:
    aliases: dict[str, str] = json.loads(Path(aliases_path).read_text(encoding="utf-8"))
    tree = ET.parse(guide_path)
    root = tree.getroot()

    channel_nodes = {c.get("id"): c for c in root.findall("channel") if c.get("id")}
    programmes_by_channel: dict[str, list[ET.Element]] = {}
    for p in root.findall("programme"):
        cid = p.get("channel")
        if cid:
            programmes_by_channel.setdefault(cid, []).append(p)

    children = list(root)
    first_programme_idx = next(
        (i for i, el in enumerate(children) if el.tag == "programme"),
        len(children),
    )
    insert_at = first_programme_idx

    added_channels = 0
    added_programmes = 0
    missing_sources = []

    for target, source in sorted(aliases.items(), key=lambda kv: kv[0].casefold()):
        if target in channel_nodes:
            continue
        src_channel = channel_nodes.get(source)
        if src_channel is None:
            missing_sources.append((target, source))
            continue

        ch = copy.deepcopy(src_channel)
        ch.set("id", target)
        root.insert(insert_at, ch)
        insert_at += 1
        channel_nodes[target] = ch
        added_channels += 1

        for src_prog in programmes_by_channel.get(source, []):
            p = copy.deepcopy(src_prog)
            p.set("channel", target)
            root.append(p)
            added_programmes += 1

    ET.indent(tree, space="  ")
    tree.write(guide_path, encoding="utf-8", xml_declaration=True)

    data = Path(guide_path).read_bytes()
    with open(gzip_path, "wb") as raw:
        with gzip.GzipFile(filename="guide.xml", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write(data)

    print(f"Alias channels added: {added_channels}")
    print(f"Alias programme entries added: {added_programmes}")
    print(f"Final XML: {len(data):,} bytes")
    print(f"Final gzip: {Path(gzip_path).stat().st_size:,} bytes")

    if missing_sources:
        print("Aliases whose source was absent from generated guide:")
        for target, source in missing_sources:
            print(f"  - {target} <- {source}")


def main() -> None:
    if len(sys.argv) < 2:
        die(__doc__.strip())

    cmd = sys.argv[1]
    if cmd == "prepare" and len(sys.argv) == 6:
        prepare(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    elif cmd == "apply" and len(sys.argv) == 5:
        apply_aliases(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        die(__doc__.strip())


if __name__ == "__main__":
    main()
