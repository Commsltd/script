#!/usr/bin/env python3
"""Build a Freeview XMLTV guide whose IDs match an IPTV-org M3U playlist.

Commands:
  prepare <freeview.channels.xml> <playlist_url> <custom.channels.xml> <aliases.json>
  enrich <guide.xml> <epgshare_UK1.xml.gz>
  apply   <guide.xml> <aliases.json> <playlist.m3u> <guide.xml.gz>

Only Python's standard library is used.
"""

from __future__ import annotations

import copy
import gzip
import json
import re
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

UA = "Mozilla/5.0 (UK-EPG-Builder/1.0)"
TVG_ID_RE = re.compile(r'tvg-id\s*=\s*"([^"]+)"', re.I)


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


def fetch_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=45) as r:
        return r.read().decode("utf-8", errors="replace")


def load_text(source: str) -> str:
    path = Path(source)
    if path.exists():
        return path.read_text(encoding="utf-8", errors="replace")
    return fetch_text(source)


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


def prepare(channels_path: str, playlist_source: str, output_path: str, aliases_path: str) -> None:
    playlist = load_text(playlist_source)
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
        json.dumps(aliases, indent=2, sort_keys=True) + chr(10),
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


def apply_aliases(guide_path: str, aliases_path: str, playlist_source: str, gzip_path: str) -> None:
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

    # Mirror the M3U logos into XMLTV channel icons. This avoids players
    # showing blank logos when their logo priority is set to EPG rather than
    # playlist, and keeps both sources visually consistent.
    try:
        _, playlist_entries = parse_m3u_entries(load_text(playlist_source))
        logo_by_id = {}
        for entry in playlist_entries:
            cid = entry.get("id", "")
            logo = entry.get("attrs", {}).get("tvg-logo", "").strip()
            if cid and logo and cid not in logo_by_id:
                logo_by_id[cid] = logo

        epg_logo_count = 0
        for channel in root.findall("channel"):
            cid = channel.get("id", "")
            logo = logo_by_id.get(cid)
            if not logo:
                continue
            icon = channel.find("icon")
            if icon is None:
                icon = ET.SubElement(channel, "icon")
            icon.set("src", logo)
            epg_logo_count += 1
        print(f"EPG channel logos synced: {epg_logo_count}")
    except Exception as exc:
        print(f"WARNING: could not sync playlist logos into EPG: {exc}")

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



# ---------------------------------------------------------------------------
# Programme metadata enrichment
# ---------------------------------------------------------------------------

def xmltv_timestamp(value: str) -> int | None:
    if not value:
        return None
    m = re.match(r"^(\\d{14})(?:\\s+([+-]\\d{4}))?", value.strip())
    if not m:
        return None
    try:
        dt = datetime.strptime(m.group(1), "%Y%m%d%H%M%S")
        offset = m.group(2)
        if offset:
            sign = 1 if offset[0] == "+" else -1
            hours = int(offset[1:3])
            minutes = int(offset[3:5])
            tz = timezone(sign * timedelta(hours=hours, minutes=minutes))
            dt = dt.replace(tzinfo=tz)
        else:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp())
    except Exception:
        return None


def normalise_programme_title(value: str) -> str:
    value = (value or "").casefold()
    value = value.replace("&", " and ")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\\s+", " ", value).strip()


def normalise_channel_name(value: str) -> str:
    value = normalise_programme_title(value)
    replacements = {
        "bbc one lon": "bbc one london",
        "bbc one e mid": "bbc one east midlands",
        "bbc one n west": "bbc one north west",
        "bbc one ne and c": "bbc one north east cumbria",
        "bbc one ni": "bbc one northern ireland",
        "bbc one s east": "bbc one south east",
        "bbc one s west": "bbc one south west",
        "bbc one scot": "bbc one scotland",
        "bbc one sth": "bbc one south",
        "bbc one wal": "bbc one wales",
        "bbc one wm": "bbc one west midlands",
        "bbc one yorks": "bbc one yorkshire",
        "bbc two ni": "bbc two northern ireland",
        "bbc two wal": "bbc two wales",
        "u and yesterday": "u yesterday",
        "u and dave": "u dave",
        "u and drama": "u drama",
        "u and w": "u w",
    }
    for old, new in replacements.items():
        value = value.replace(old, new)
    value = re.sub(r"\\b(hd|sd|uk|united kingdom)\\b", " ", value)
    return re.sub(r"\\s+", " ", value).strip()


def epgshare_source_id(target_id: str) -> str | None:
    base, variant = split_id(target_id)
    v = variant.casefold()
    plus1 = "plus1" in v or "+1" in v

    if base == "BBCOne.uk":
        region_map = {
            "london": "BBC.One.Lon.HD.uk",
            "east": "BBC.One.EastHD.uk",
            "eastmidlands": "BBC.One.E.Mid.HD.uk",
            "northeastcumbria": "BBC.One.NE.and.C.HD.uk",
            "northernireland": "BBC.One.NI.HD.uk",
            "northwest": "BBC.One.N.West.HD.uk",
            "scotland": "BBC.One.ScotHD.uk",
            "south": "BBC.One.Sth.HD.uk",
            "southeast": "BBC.One.S.East.HD.uk",
            "southwest": "BBC.One.S.West.HD.uk",
            "wales": "BBC.One.Wal.HD.uk",
            "west": "BBC.One.West.HD.uk",
            "westmidlands": "BBC.One.WM.HD.uk",
            "yorkshire": "BBC.One.Yorks.HD.uk",
            "yorkshirelincolnshire": "BBC.One.Y.and.L.HD.uk",
            "channelislands": "BBC.One.CI.HD.uk",
        }
        for token, source in region_map.items():
            if token in v:
                return source
        return "BBC.One.Lon.HD.uk"

    if base == "BBCTwo.uk":
        if "northernireland" in v:
            return "BBC.Two.NI.HD.uk"
        if "wales" in v:
            return "BBC.Two.Wal.HD.uk"
        return "BBC.Two.HD.uk"

    fixed = {
        "BBCThree.uk": "BBC.Three.HD.uk",
        "BBCThreeCBBC.uk": "BBC.Three.HD.uk",
        "BBCFour.uk": "BBC.Four.HD.uk",
        "BBCFourCBeebies.uk": "BBC.Four.HD.uk",
        "CBBC.uk": "CBBC.HD.uk",
        "CBeebies.uk": "CBeebies.HD.uk",
        "BBCNews.uk": "BBC.NEWS.HD.uk",
        "SkyNews.uk": "Sky.News.HD.uk",
        "SkyArts.uk": "Sky.Arts.HD.uk",
        "SkyMix.uk": "Sky.Mix.HD.uk",
        "TalkingPicturesTV.uk": "TalkingPictures.uk",
        "Blaze.uk": "BLAZE.uk",
        "FoodNetwork.uk": "Food.Network.uk",
        "5SELECT.uk": "5SELECT.uk",
        "5Action.uk": "5ACTION.uk",
        "4seven.uk": "4seven.uk",
        "GREATmovies.uk": "GREAT!.movies.uk",
        "GREATromance.uk": "GREAT!.romance.uk",
        "GREATaction.uk": "GREAT!.action.uk",
        "GREATtv.uk": "GREAT!.tv.uk",
        "talkSPORT.uk": "talkSPORT.uk",
    }
    if base in fixed:
        return fixed[base]

    if base == "ITV1.uk":
        return "ITV1+1.uk" if plus1 else "ITV1.HD.uk"
    if base == "ITV2.uk":
        return "ITV2+1.uk" if plus1 else "ITV2.HD.uk"
    if base == "ITV3.uk":
        return "ITV3+1.uk" if plus1 else "ITV3.HD.uk"
    if base == "ITV4.uk":
        return "ITV4+1.uk" if plus1 else "ITV4.HD.uk"
    if base == "Channel4.uk":
        return "Channel.4+1.uk" if plus1 else "Channel.4.HD.uk"
    if base == "Channel5.uk":
        return "Channel.5+1.uk" if plus1 else "Channel.5.HD.uk"
    if base == "E4.uk":
        return "E4+1.uk" if plus1 else "E4.HD.uk"
    if base == "Film4.uk":
        return "Film4+1.uk" if plus1 else "Film4.HD.uk"
    if base == "More4.uk":
        return "More4+1.uk" if plus1 else "More4.HD.uk"
    if base == "5STAR.uk":
        return "5STAR+1.uk" if plus1 else "5STAR.uk"
    if base == "5USA.uk":
        return "5USA+1.uk" if plus1 else None
    if base == "UDave.uk":
        return "U.and.Dave.ja.vu.uk" if plus1 else "U.and.Dave.HD.uk"
    if base == "UDaveJaVu.uk":
        return "U.and.Dave.ja.vu.uk"
    if base == "UDrama.uk":
        return "U.and.Drama+1.uk" if plus1 else "U.and.Drama.uk"
    if base == "UYesterday.uk":
        return "U.and.YESTERDAY+1.uk" if plus1 else "U.and.YESTERDAY.uk"
    if base == "UW.uk":
        return "U.and.W+1.uk" if plus1 else "U.and.W.HD.uk"
    if base == "Quest.uk":
        return "QUEST+1.uk" if plus1 else "QUEST.HD.uk"
    if base == "QuestRed.uk":
        return "Quest.Red+1.uk" if plus1 else "Quest.Red.uk"
    if base == "SkySportsCricket.uk":
        return "SkySpCricket.HD.uk"

    return None


def enrich_guide(guide_path: str, source_gzip_path: str) -> None:
    source_path = Path(source_gzip_path)
    if not source_path.exists() or source_path.stat().st_size < 1000:
        print("WARNING: enrichment guide unavailable; keeping Freeview-only metadata")
        return

    tree = ET.parse(guide_path)
    root = tree.getroot()

    target_channel_names = {}
    for ch in root.findall("channel"):
        cid = ch.get("id", "")
        names = [
            (n.text or "").strip()
            for n in ch.findall("display-name")
            if (n.text or "").strip()
        ]
        if cid:
            target_channel_names[cid] = names

    # UK1 is modest enough to parse in memory and this preserves child
    # nodes (title/desc/category) until the parent programme is processed.
    with gzip.open(source_path, "rb") as fh:
        source_root = ET.parse(fh).getroot()

    source_channel_names = {}
    for elem in source_root.findall("channel"):
        cid = elem.get("id", "")
        names = [
            (n.text or "").strip()
            for n in elem.findall("display-name")
            if (n.text or "").strip()
        ]
        if cid:
            source_channel_names[cid] = names

    normalised_source_names = {}
    for cid, names in source_channel_names.items():
        for name in names:
            norm = normalise_channel_name(name)
            if norm:
                normalised_source_names.setdefault(norm, []).append(cid)

    target_to_source = {}
    for target_id, names in target_channel_names.items():
        explicit = epgshare_source_id(target_id)
        if explicit and explicit in source_channel_names:
            target_to_source[target_id] = explicit
            continue

        candidates = []
        for name in names:
            norm = normalise_channel_name(name)
            candidates.extend(normalised_source_names.get(norm, []))
        if candidates:
            target_to_source[target_id] = candidates[0]

    wanted_sources = set(target_to_source.values())
    source_programmes = {}
    source_programme_count = 0

    all_source_programmes = source_root.findall("programme")
    all_source_programme_channels = sorted({
        p.get("channel", "") for p in all_source_programmes if p.get("channel")
    })
    print(f"EPGshare total programmes: {len(all_source_programmes)}")
    print(f"EPGshare programme channel IDs: {len(all_source_programme_channels)}")
    print("EPGshare programme channel ID sample: " + ", ".join(all_source_programme_channels[:40]))
    print("EPGshare wanted channel ID sample: " + ", ".join(sorted(wanted_sources)[:40]))

    for elem in all_source_programmes:
        source_channel = elem.get("channel", "")
        if source_channel not in wanted_sources:
            continue

        start = xmltv_timestamp(elem.get("start", ""))
        title_node = elem.find("title")
        title = normalise_programme_title(
            title_node.text if title_node is not None else ""
        )
        if start is not None and title:
            source_programmes.setdefault(source_channel, {}).setdefault(
                start // 60, []
            ).append(elem)
            source_programme_count += 1

    print(f"EPGshare source programmes indexed: {source_programme_count}")

    copy_tags = {
        "desc", "sub-title", "category", "episode-num", "date",
        "rating", "star-rating", "credits", "country"
    }

    matched = 0
    descriptions_added = 0
    metadata_nodes_added = 0
    target_channels_enriched = set()

    for programme in root.findall("programme"):
        target_channel = programme.get("channel", "")
        source_channel = target_to_source.get(target_channel)
        if not source_channel:
            continue

        start = xmltv_timestamp(programme.get("start", ""))
        title_node = programme.find("title")
        target_title = normalise_programme_title(
            title_node.text if title_node is not None else ""
        )
        if start is None or not target_title:
            continue

        best = None
        best_score = 0.0
        minute = start // 60
        by_minute = source_programmes.get(source_channel, {})

        for delta in range(-10, 11):
            for candidate in by_minute.get(minute + delta, []):
                source_title_node = candidate.find("title")
                source_title = normalise_programme_title(
                    source_title_node.text if source_title_node is not None else ""
                )
                if not source_title:
                    continue

                if source_title == target_title:
                    score = 1.0
                else:
                    score = SequenceMatcher(
                        None, target_title, source_title
                    ).ratio()

                # Prefer title similarity, with a small penalty for time drift.
                score -= abs(delta) * 0.01
                if score > best_score:
                    best_score = score
                    best = candidate

        if best is None or best_score < 0.72:
            continue

        matched += 1
        target_channels_enriched.add(target_channel)
        existing_tags = {child.tag for child in programme}

        for child in list(best):
            if child.tag not in copy_tags:
                continue

            if child.tag == "desc":
                existing_desc = programme.find("desc")
                if existing_desc is not None and (existing_desc.text or "").strip():
                    continue
                programme.append(copy.deepcopy(child))
                descriptions_added += 1
                metadata_nodes_added += 1
                continue

            if child.tag in {"sub-title", "date", "credits"} and child.tag in existing_tags:
                continue

            if child.tag in {"category", "episode-num", "rating", "star-rating", "country"}:
                serialised = ET.tostring(child, encoding="unicode")
                if any(
                    ET.tostring(existing, encoding="unicode") == serialised
                    for existing in programme.findall(child.tag)
                ):
                    continue

            programme.append(copy.deepcopy(child))
            metadata_nodes_added += 1

    ET.indent(tree, space="  ")
    tree.write(guide_path, encoding="utf-8", xml_declaration=True)

    print(f"EPGshare channel mappings: {len(target_to_source)}")
    print(f"EPGshare programme matches: {matched}")
    print(f"Programme descriptions added: {descriptions_added}")
    print(f"Extra metadata nodes added: {metadata_nodes_added}")
    print(f"Channels enriched: {len(target_channels_enriched)}")


# ---------------------------------------------------------------------------
# Structured playlist builder
# ---------------------------------------------------------------------------

ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')

GROUPS = [
    "01 MAIN UK",
    "02 NEWS",
    "03 FILM & ENTERTAINMENT",
    "04 KIDS",
    "05 MUSIC",
    "06 SPORT",
    "07 LOCAL & REGIONAL",
    "08 INTERNATIONAL",
    "09 RELIGIOUS",
    "10 SHOPPING",
    "80 RED BUTTON / SPECIAL EVENTS",
    "90 ALTERNATIVE STREAMS",
    "95 OTHER / ODDITIES",
    "99 EXPERIMENTAL / UNRELIABLE",
]

MAIN_BASES = [
    "BBCOne.uk",
    "BBCTwo.uk",
    "ITV1.uk",
    "Channel4.uk",
    "Channel5.uk",
    "BBCThree.uk",
    "BBCThreeCBBC.uk",
    "BBCFour.uk",
    "BBCFourCBeebies.uk",
    "ITV2.uk",
    "ITV3.uk",
    "ITV4.uk",
    "E4.uk",
    "More4.uk",
    "Film4.uk",
    "4seven.uk",
    "5STAR.uk",
    "5USA.uk",
    "5Action.uk",
    "5SELECT.uk",
    "UDave.uk",
    "UDrama.uk",
    "UYesterday.uk",
    "UW.uk",
    "SkyArts.uk",
    "SkyMix.uk",
    "Quest.uk",
    "QuestRed.uk",
    "Really.uk",
    "FoodNetwork.uk",
    "TalkingPicturesTV.uk",
    "GREATtv.uk",
    "GREATmovies.uk",
    "GREATromance.uk",
    "GREATaction.uk",
    "Legend.uk",
    "HorrorXtra.uk",
    "Blaze.uk",
]

MAIN_VARIANT_BASES = {base.casefold() for base in MAIN_BASES}


PREFERRED_VARIANTS = {
    "BBCOne.uk": ["LondonHD", "London", "UKHD", "HD", "UK", "SD"],
    "BBCTwo.uk": ["HD", "England", "UKHD", "UK", "SD"],
    "ITV1.uk": ["LondonHD", "London", "CentralHD", "GranadaHD", "MeridianHD", "HD", "SD"],
    "Channel4.uk": ["UKHD", "HD", "UK", "SD"],
    "Channel5.uk": ["HD", "UKHD", "UK", "SD"],
}


def parse_m3u_entries(text: str) -> tuple[str, list[dict]]:
    lines = text.splitlines()
    header = lines[0].strip() if lines and lines[0].startswith("#EXTM3U") else "#EXTM3U"
    entries = []
    block = []

    for line in lines[1:] if lines and lines[0].startswith("#EXTM3U") else lines:
        if line.startswith("#EXTINF"):
            if block:
                entries.append(parse_m3u_block(block))
            block = [line]
        elif block:
            block.append(line)

    if block:
        entries.append(parse_m3u_block(block))

    return header, [e for e in entries if e.get("url")]


def parse_m3u_block(block: list[str]) -> dict:
    extinf = block[0]
    attrs = {m.group(1): m.group(2) for m in ATTR_RE.finditer(extinf)}
    comma = extinf.find(",")
    name = extinf[comma + 1:].strip() if comma >= 0 else attrs.get("tvg-name", "")
    url = ""
    for line in reversed(block[1:]):
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            url = stripped
            break
    cid = attrs.get("tvg-id", "").strip()
    base, variant = split_id(cid) if cid else ("", "")
    return {
        "block": block[:],
        "extinf": extinf,
        "attrs": attrs,
        "name": name,
        "url": url,
        "id": cid,
        "base": base,
        "variant": variant,
    }


def set_m3u_attr(extinf: str, key: str, value: str) -> str:
    pattern = re.compile(rf'{re.escape(key)}="[^"]*"')
    replacement = f'{key}="{value}"'
    if pattern.search(extinf):
        return pattern.sub(replacement, extinf, count=1)
    comma = extinf.find(",")
    if comma < 0:
        return extinf + " " + replacement
    return extinf[:comma].rstrip() + " " + replacement + extinf[comma:]


def set_m3u_name(extinf: str, name: str) -> str:
    comma = extinf.find(",")
    if comma < 0:
        return extinf + "," + name
    return extinf[:comma + 1] + name


def stream_score(entry: dict) -> int:
    name = entry["name"].casefold()
    url = entry["url"].casefold()
    score = 0

    m = re.search(r'\((2160|1440|1080|720|576|540|480|396|360)p\)', name)
    if m:
        res = int(m.group(1))
        score += {2160: 90, 1440: 80, 1080: 70, 720: 60, 576: 50,
                  540: 45, 480: 40, 396: 35, 360: 30}.get(res, 0)

    if "[not 24/7]" in name:
        score -= 80
    if url.startswith("https://"):
        score += 8
    if ".m3u8" in url:
        score += 18
    elif ".mpd" in url:
        score += 10
    if "short.gy" in url:
        score -= 2

    return score


def preferred_variant_score(entry: dict) -> int:
    base = entry["base"]
    variant = entry["variant"]
    prefs = PREFERRED_VARIANTS.get(base)
    if prefs:
        try:
            return 2000 - prefs.index(variant) * 100
        except ValueError:
            pass

    v = variant.casefold()
    score = 0
    if "plus1" in v or "+1" in v:
        score -= 1200
    if "hd" in v:
        score += 250
    if "uk" in v:
        score += 80
    if "sd" in v:
        score += 30
    if not v:
        score += 20
    return score


MAIN_NAME_RULES = [
    ("BBC One", re.compile(r"^bbc one\b", re.I)),
    ("BBC Two", re.compile(r"^bbc two\b", re.I)),
    ("ITV1", re.compile(r"^itv1\b", re.I)),
    ("Channel 4", re.compile(r"^channel 4\b", re.I)),
    ("Channel 5", re.compile(r"^channel 5\b", re.I)),
    ("BBC Three", re.compile(r"^bbc three\b", re.I)),
    ("BBC Four", re.compile(r"^bbc four\b", re.I)),
    ("ITV2", re.compile(r"^itv2\b", re.I)),
    ("ITV3", re.compile(r"^itv3\b", re.I)),
    ("ITV4", re.compile(r"^itv4\b", re.I)),
    ("E4", re.compile(r"^e4\b", re.I)),
    ("More4", re.compile(r"^more ?4\b", re.I)),
    ("Film4", re.compile(r"^film ?4\b", re.I)),
    ("4seven", re.compile(r"^4seven\b", re.I)),
    ("5STAR", re.compile(r"^5star\b", re.I)),
    ("5USA", re.compile(r"^5usa\b", re.I)),
    ("5ACTION", re.compile(r"^5action\b", re.I)),
    ("5SELECT", re.compile(r"^5select\b", re.I)),
    ("U&Dave", re.compile(r"^(u&)?dave\b", re.I)),
    ("U&Drama", re.compile(r"^(u&)?drama\b", re.I)),
    ("U&Yesterday", re.compile(r"^(u&)?yesterday\b", re.I)),
    ("U&W", re.compile(r"^(u&)?w\b", re.I)),
    ("Sky Arts", re.compile(r"^sky arts\b", re.I)),
    ("Sky Mix", re.compile(r"^sky mix\b", re.I)),
    ("Quest", re.compile(r"^quest\b(?!.*red)", re.I)),
    ("Quest Red", re.compile(r"^quest red\b", re.I)),
    ("Really", re.compile(r"^really\b", re.I)),
    ("Food Network", re.compile(r"^food network\b", re.I)),
    ("Talking Pictures", re.compile(r"^talking pictures", re.I)),
    ("GREAT! TV", re.compile(r"^great!? tv\b", re.I)),
    ("GREAT! Movies", re.compile(r"^great!? movies\b", re.I)),
    ("GREAT! Romance", re.compile(r"^great!? romance\b", re.I)),
    ("GREAT! Action", re.compile(r"^great!? action\b", re.I)),
    ("Legend", re.compile(r"^legend\b", re.I)),
    ("Blaze", re.compile(r"^blaze\b", re.I)),
]


def compact_name(entry: dict) -> str:
    name = entry["name"]
    name = re.sub(r"\s*\([^)]*\)", "", name)
    name = re.sub(r"\s*\[[^]]*\]", "", name)
    return re.sub(r"\s+", " ", name).strip()


def main_candidate_score(slot: str, entry: dict) -> int:
    name = compact_name(entry).casefold()
    cid = entry["id"].casefold()
    variant = entry["variant"].casefold()
    hay = " ".join([name, cid, variant])
    score = stream_score(entry) + preferred_variant_score(entry)

    if "plus1" in hay or "+1" in hay:
        return -100000

    if slot == "BBC One":
        if "london" in hay:
            score += 3000
        regional = [
            "scotland", "wales", "northern ireland", "northernireland",
            "east", "west", "south", "north", "midlands", "yorkshire",
            "channel islands", "channelislands", "cumbria"
        ]
        if any(x in hay for x in regional) and "london" not in hay:
            score -= 1200
    elif slot == "BBC Two":
        if " hd" in " " + hay or "@hd" in cid:
            score += 1200
        if "wales" in hay or "northern ireland" in hay or "northernireland" in hay:
            score -= 500
    elif slot == "ITV1":
        if "london" in hay:
            score += 3000
        regional = [
            "anglia", "border", "central", "granada", "meridian", "tyne",
            "wales", "westcountry", "yorkshire", "channel television"
        ]
        if any(x in hay for x in regional) and "london" not in hay:
            score -= 1000
    elif slot in {"Channel 4", "Channel 5"}:
        if "hd" in hay:
            score += 700

    return score


def choose_main(entries: list[dict]) -> dict[str, dict]:
    selected = {}
    used = set()

    for slot, pattern in MAIN_NAME_RULES:
        candidates = [
            e for e in entries
            if id(e) not in used and pattern.search(compact_name(e))
        ]
        if not candidates:
            continue

        best = max(candidates, key=lambda e: (main_candidate_score(slot, e), -entries.index(e)))
        if main_candidate_score(slot, best) <= -100000:
            continue

        selected[slot] = best
        used.add(id(best))

    return selected

def classify(entry: dict, main_entry_ids: set[int]) -> str:
    if id(entry) in main_entry_ids:
        return "01 MAIN UK"

    name = entry["name"].casefold()
    cid = entry["id"].casefold()
    base = entry["base"].casefold()
    variant = entry["variant"].casefold()
    hay = " ".join([name, cid, base, variant])

    if "[not 24/7]" in name:
        return "99 EXPERIMENTAL / UNRELIABLE"

    if any(x in hay for x in [
        "redbutton", "red button", "bbcrb", "bbcuhd1", "bbcuhd2", "bbcuhd3", "bbcuhd4"
    ]):
        return "80 RED BUTTON / SPECIAL EVENTS"

    # Keep variants of the main UK channel families in the main guide.
    # This includes BBC One regions, ITV1 regions/+1, Channel 4/5 variants,
    # ITV2/3/4 variants and the U/Quest/etc families. Core selected streams
    # still sort first; variants follow afterwards.
    compact = compact_name(entry).casefold()
    if base in MAIN_VARIANT_BASES:
        return "01 MAIN UK"
    if compact.startswith("bbc one") or compact.startswith("itv1"):
        return "01 MAIN UK"

    if any(x in hay for x in [
        "stv", "utv", "londonlive", "london tv", "latesttv", "latest tv",
        "kmtv", "talkbirmingham", "talkbristol", "talkcardiff", "talkleeds",
        "talkliverpool", "talknorthwales", "talkteesside", "talktynewear",
        "thatstv.uk@", "bbcscotland", "bbcalba", "s4c"
    ]):
        return "07 LOCAL & REGIONAL"

    if any(x in hay for x in [
        "bbcnews", "skynews", "gbnews", "newsmax", "aljazeera", "al jazeera",
        "france24", "france 24", "euronews", "bloomberg", "cnbc", "iraninternational",
        "afghanistaninternational", "talktv"
    ]):
        return "02 NEWS"

    if any(x in hay for x in [
        "sport", "fifa", "mutv", "sky sports", "talksport", "setantasports"
    ]):
        return "06 SPORT"

    if any(x in hay for x in [
        "cbbc", "cbeebies", "tinypop", "tiny pop", "pop.uk", "pop up",
        "nickelodeon", "moonbug", "babytv", "baby tv", "ketchup", "cartoon"
    ]):
        return "04 KIDS"

    if any(x in hay for x in [
        "music", "mtv", "now80s", "now90s", "totalmusic", "gigs"
    ]):
        return "05 MUSIC"

    if any(x in hay for x in [
        "faith", "godtv", "revelation", "tbn", "islam", "iqra", "ahlulbayt",
        "eman", "noortv", "mta1", "mta2", "mta3", "mta4", "mta5", "mta6",
        "mta7", "mta8", "takbeer", "sonlife", "it is written"
    ]):
        return "09 RELIGIOUS"

    if any(x in hay for x in [
        "qvc", "gemporia", "tjc", "idealworld", "ideal world", "hobbymaker",
        "hobby maker", "jewellerymaker", "jewellery maker", "shop on tv",
        "high street tv", "must have ideas", "tvwarehouse", "tv warehouse"
    ]):
        return "10 SHOPPING"

    if any(x in hay for x in [
        "film4", "movie", "movies", "legend", "horror", "blaze", "drama",
        "dave", "yesterday", "quest", "really", "foodnetwork", "food network",
        "skyarts", "sky arts", "skymix", "sky mix", "talkingpictures",
        "talking pictures", "great!", "greattv", "greatmovies", "greatromance",
        "great action", "amc", "epicdrama", "epic drama"
    ]):
        return "03 FILM & ENTERTAINMENT"

    # Anything whose canonical ID is not UK is kept, but moved after the UK groups.
    if entry["id"] and ".uk" not in base:
        return "08 INTERNATIONAL"

    return "95 OTHER / ODDITIES"


CARRIER_ID_MAP = {
    "bbc2.uk": ("BBCTwo.uk@HD", "BBC Two HD"),
    "bbc3cbbc.uk": ("BBCThreeCBBC.uk@HD", "BBC Three/CBBC"),
    "bbc4cbeebies.uk": ("BBCFourCBeebies.uk@HD", "BBC Four/CBeebies"),
    "bbcworld.uk": ("BBCNews.uk@UKHD", "BBC News HD"),
    "itv2.uk": ("ITV2.uk@HD", "ITV2 HD"),
    "itv3.uk": ("ITV3.uk@SD", "ITV3"),
    "itv4.uk": ("ITV4.uk@SD", "ITV4"),
    "questtv.com": ("Quest.uk@SD", "Quest"),
    "channel4.uk": ("Channel4.uk@UKHD", "Channel 4"),
    "channel5.uk": ("Channel5.uk@HD", "Channel 5"),
    "film4.uk": ("Film4.uk@SD", "Film4"),
    "more4.uk": ("More4.uk@SD", "More4"),
    "foodnetwork.uk": ("FoodNetwork.uk@SD", "Food Network"),
}


def canonicalise_carrier_entry(entry: dict) -> dict | None:
    mapping = CARRIER_ID_MAP.get(entry["id"].casefold())
    if not mapping:
        return None

    target_id, display_name = mapping
    out = dict(entry)
    out["block"] = entry["block"][:]
    out["id"] = target_id
    out["base"], out["variant"] = split_id(target_id)
    out["name"] = display_name

    extinf = out["block"][0]
    extinf = set_m3u_attr(extinf, "tvg-id", target_id)
    extinf = set_m3u_name(extinf, display_name)
    out["block"][0] = extinf
    out["extinf"] = extinf
    out["attrs"] = dict(entry["attrs"])
    out["attrs"]["tvg-id"] = target_id
    return out


def build_playlist(
    playlist_url: str,
    alt_playlist_url: str,
    carrier_playlist_url: str,
    output_path: str
) -> None:
    text = fetch_text(playlist_url)
    header, entries = parse_m3u_entries(text)
    if not entries:
        die("No playable entries found in playlist")

    # Start with the user's existing IPTV-org country playlist. These remain
    # the preferred streams wherever a logical channel already exists.
    primaries = entries[:]
    carrier_alternatives: list[dict] = []

    # Supplement only selected missing UK channels from a current carrier OTT
    # playlist. Existing working IPTV-org channels are never replaced.
    try:
        carrier_text = fetch_text(carrier_playlist_url)
        _, carrier_entries_raw = parse_m3u_entries(carrier_text)
    except Exception as exc:
        print(f"WARNING: could not load carrier stream pool: {exc}")
        carrier_entries_raw = []

    existing_bases = {e["base"].casefold() for e in primaries if e["base"]}
    added_carrier_primaries = []

    for raw in carrier_entries_raw:
        e = canonicalise_carrier_entry(raw)
        if e is None or not e["url"]:
            continue

        base_key = e["base"].casefold()
        if base_key not in existing_bases:
            primaries.append(e)
            added_carrier_primaries.append(e)
            existing_bases.add(base_key)
        else:
            carrier_alternatives.append(e)

    # Choose the Freeview-like headline set only after supplemental sources
    # have been merged, so missing C4/C5/ITV3/ITV4/etc can be promoted.
    main_by_slot = choose_main(primaries)
    main_entry_ids = {id(e) for e in main_by_slot.values()}
    main_entry_rank = {
        id(entry): i for i, (_, entry) in enumerate(main_by_slot.items())
    }

    # Pull extra candidate URLs from IPTV-org's underlying UK stream pool.
    # They only appear in Alternatives and never displace a working primary.
    alternatives: list[dict] = []
    source_urls = {e["url"] for e in primaries}
    primary_by_id = {e["id"]: e for e in primaries if e["id"]}

    try:
        alt_text = fetch_text(alt_playlist_url)
        _, alt_entries = parse_m3u_entries(alt_text)
    except Exception as exc:
        print(f"WARNING: could not load alternative stream pool: {exc}")
        alt_entries = []

    alt_by_id: dict[str, list[dict]] = {}
    for e in alt_entries:
        if not e["id"] or e["id"] not in primary_by_id:
            continue
        if not e["url"] or e["url"] in source_urls:
            continue
        alt_by_id.setdefault(e["id"], []).append(e)

    for e in carrier_alternatives:
        if e["id"] in primary_by_id and e["url"] not in source_urls:
            alt_by_id.setdefault(e["id"], []).append(e)

    # Keep at most three backup URLs per logical channel.
    for cid, candidates in alt_by_id.items():
        seen = set()
        unique = []
        for e in sorted(candidates, key=stream_score, reverse=True):
            if e["url"] in seen:
                continue
            seen.add(e["url"])
            unique.append(e)
            if len(unique) == 3:
                break

        primary = primary_by_id[cid]
        primary_logo = primary["attrs"].get("tvg-logo", "")
        for n, alt in enumerate(unique, start=1):
            alt = dict(alt)
            alt["block"] = alt["block"][:]
            alt["alt_number"] = n
            alt["primary_logo"] = primary_logo
            alternatives.append(alt)

    group_entries = {g: [] for g in GROUPS}

    for e in primaries:
        group = classify(e, main_entry_ids)
        group_entries[group].append(e)

    for e in alternatives:
        group_entries["90 ALTERNATIVE STREAMS"].append(e)

    family_rank = {
        base.casefold(): i for i, base in enumerate(MAIN_BASES)
    }

    def normal_sort_key(e: dict):
        if id(e) in main_entry_rank:
            return (0, main_entry_rank[id(e)], 0, e["name"].casefold())

        base_key = e["base"].casefold()
        if classify(e, main_entry_ids) == "01 MAIN UK" and base_key in family_rank:
            return (
                1,
                family_rank[base_key],
                -preferred_variant_score(e),
                e["name"].casefold(),
            )

        return (2, e["name"].casefold(), e["id"].casefold(), -stream_score(e))

    header = set_m3u_attr(
        header,
        "x-tvg-url",
        "https://raw.githubusercontent.com/Commsltd/script/uk-tv-epg-output/guide.xml.gz"
    )
    out_lines = [header]
    channel_number = 1

    for group in GROUPS:
        items = group_entries[group]
        items.sort(key=normal_sort_key)

        for e in items:
            block = e["block"][:]
            extinf = block[0]
            extinf = set_m3u_attr(extinf, "group-title", group)
            extinf = set_m3u_attr(extinf, "tvg-chno", str(channel_number))

            if group == "90 ALTERNATIVE STREAMS":
                alt_n = e.get("alt_number", 1)
                if not e["attrs"].get("tvg-logo") and e.get("primary_logo"):
                    extinf = set_m3u_attr(extinf, "tvg-logo", e["primary_logo"])
                extinf = set_m3u_name(extinf, f'{e["name"]} [Alt {alt_n}]')

            block[0] = extinf
            out_lines.extend(block)
            channel_number += 1

    Path(output_path).write_text(chr(10).join(out_lines) + chr(10), encoding="utf-8")

    print(f"Structured playlist written: {len(primaries)} primary streams")
    print(f"Carrier primaries added: {len(added_carrier_primaries)}")
    for entry in added_carrier_primaries:
        print(f"  CARRIER PRIMARY {entry['name']}: {entry['id']}")
    print(f"Main UK channels selected: {len(main_by_slot)}")
    for slot, entry in main_by_slot.items():
        print(f"  MAIN {slot}: {entry['name']} [{entry['id']}]")
    print(f"Alternative streams added: {len(alternatives)}")
    for group in GROUPS:
        print(f"  {group}: {len(group_entries[group])}")

def main() -> None:
    if len(sys.argv) < 2:
        die(__doc__.strip())

    cmd = sys.argv[1]
    if cmd == "prepare" and len(sys.argv) == 6:
        prepare(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    elif cmd == "enrich" and len(sys.argv) == 4:
        enrich_guide(sys.argv[2], sys.argv[3])
    elif cmd == "apply" and len(sys.argv) == 6:
        apply_aliases(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    elif cmd == "build-playlist" and len(sys.argv) == 6:
        build_playlist(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    else:
        die(__doc__.strip())


if __name__ == "__main__":
    main()
