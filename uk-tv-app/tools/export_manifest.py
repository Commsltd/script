#!/usr/bin/env python3
"""Read an immutable copy of the existing M3U/XMLTV outputs. Never changes them."""
from __future__ import annotations
import argparse
import copy
import gzip
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl
from guide_supplements import load_guide, mappings, parse_xml
from channel_groups import arrange_channels
from bbc_sources import combine as combine_bbc, refresh_sources

ATTR = re.compile(r'([\w-]+)="([^"]*)"')

YOUTUBE_PAGES = {
    'SkyNews.uk': 'https://www.youtube.com/@SkyNews/live',
    'EuronewsEnglish.fr': 'https://www.youtube.com/@euronews/live',
    'ABCNewsLive.us': 'https://www.youtube.com/@ABCNews/live',
    'CBSNews247.us': 'https://www.youtube.com/@CBSNews/live',
    'NBCNewsNOW.us': 'https://www.youtube.com/@NBCNews/live',
    'LiveNOWfromFOX.us': 'https://www.youtube.com/@LiveNOWFOX/live',
}

YOUTUBE_LABELS = {
    'SkyNews.uk': 'Sky News official YouTube',
    'EuronewsEnglish.fr': 'Euronews official YouTube',
    'ABCNewsLive.us': 'ABC News official YouTube',
    'CBSNews247.us': 'CBS News official YouTube',
    'NBCNewsNOW.us': 'NBC News official YouTube',
    'LiveNOWfromFOX.us': 'LiveNOW from FOX official YouTube',
}

# Exact service identities only: never infer a region or a +1 schedule.
SUPPLEMENT_IDS = {
    'BloombergTV.us@Europe': 'Bloomberg.HD.uk',
    'CNBCUK.uk@SD': 'CNBC.HD.uk',
    'TalkTV.uk@SD': 'TalkRadio.(TalkTV).uk',
    'NBCNewsNOW.us@SD': 'NBCNewsNowHD.uk',
    'BabyTV.uk@UK': 'BabyTV.uk',
    'Nickelodeon.uk@SD': 'NickelodeonHD.uk',
    'Now70s.uk@SD': 'NOW.70s.uk',
    'Now80s.uk@SD': 'NOW.80s.uk',
    'NOWRock.uk@SD': 'Now.Rock.uk',
    'SkySportsNFL.uk@SD': 'Sky.Sports.NFL.uk',
    'GODTV.uk@SD': 'GOD.Channel.uk',
    'QVCBeauty.uk@SD': 'QVC.Beauty.uk',
    'QVCExtra.uk@SD': 'QVC.Extra.uk',
    'QVCStyle.uk@SD': 'QVC.Style.HD.uk',
    'UAlibi.uk@SD': 'U.and.alibi.HD.uk',
}

def supplement_missing(root, channels, xml_bytes, id_map=None):
    if not xml_bytes:
        return []
    extra = parse_xml(xml_bytes)
    def valid(item):
        start, stop = stamp(item.get('start','')), stamp(item.get('stop',''))
        return start is not None and stop is not None and stop > start and bool((item.findtext('title') or '').strip())
    populated = {p.get('channel') for p in root.findall('programme') if valid(p)}
    by_id = {}
    for item in extra.findall('programme'):
        if valid(item): by_id.setdefault(item.get('channel'), []).append(item)
    added = []
    for cid, source_id in (SUPPLEMENT_IDS if id_map is None else id_map).items():
        channel = channels.get(cid)
        if not channel or not channel['epgId'] or channel['epgId'] in populated:
            continue
        items = by_id.get(source_id, [])
        for item in items:
            clone = copy.deepcopy(item)
            clone.set('channel', channel['epgId'])
            root.append(clone)
        if items:
            populated.add(channel['epgId'])
            added.append(cid)
    return added

def identity(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()[:24]

def delimiter(line):
    quoted = False
    for i, char in enumerate(line):
        if char == '"':
            quoted = not quoted
        elif char == ',' and not quoted:
            return i
    return len(line)

def parse_playlist(text):
    result, current = [], None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith('#EXTINF:'):
            at = delimiter(line)
            attrs = dict(ATTR.findall(line[:at]))
            current = {'attrs': attrs, 'name': line[at + 1:], 'headers': {}, 'drm': False}
        elif current and line.startswith('#EXTVLCOPT:'):
            key, _, value = line[len('#EXTVLCOPT:'):].partition('=')
            header = {'http-user-agent': 'User-Agent', 'http-referrer': 'Referer', 'http-referer': 'Referer', 'http-origin': 'Origin'}.get(key)
            if header and '\r' not in value and '\n' not in value:
                current['headers'][header] = value
        elif current and line.startswith('#KODIPROP:') and 'license' in line.lower():
            current['drm'] = True
        elif current and line and not line.startswith('#'):
            url, _, suffix = line.partition('|')
            if urlsplit(url).scheme in ('http', 'https'):
                for key, value in parse_qsl(suffix):
                    if key.lower() in ('user-agent', 'referer', 'origin') and not any(c in value for c in '\r\n'):
                        current['headers'][key] = value
                current['url'] = url
                result.append(current)
            current = None
    return result

def stamp(value):
    match = re.fullmatch(r'(\d{14})(?:\s*([+-]\d{4}))?', value.strip())
    if not match:
        return None
    try:
        return int(datetime.strptime(match[1] + (match[2] or '+0000'), '%Y%m%d%H%M%S%z').timestamp() * 1000)
    except ValueError:
        return None

def family(cid):
    base = cid.partition('@')[0]
    return {'BBCThreeCBBC.uk': 'BBCThree.uk', 'BBCFourCBeebies.uk': 'BBCFour.uk', 'STV.uk': 'ITV1.uk'}.get(base, base)

def build(playlist, xml_bytes, revision='unknown', youtube_live=None, supplemental_xml=None, extra_guides=None, bbc_sources=()):
    youtube_live = youtube_live or {}
    channels = {}
    warnings = []
    for order, entry in enumerate(parse_playlist(playlist)):
        attrs = entry['attrs']
        original_id = attrs.get('tvg-id', '').strip()
        cid = original_id or 'unmapped.' + identity(entry['name'])
        epg_id = original_id
        # Earlier M3U code incorrectly labelled STV as an ITV1 region. Do not
        # show London programmes on a different broadcaster's service.
        if cid == 'ITV1.uk@STV':
            cid, epg_id = 'STV.uk@Unspecified', ''
            warnings.append('STV kept beside ITV1 but London EPG deliberately not copied to it.')
        if cid not in channels:
            channels[cid] = {
                'id': cid, 'epgId': epg_id, 'family': family(cid),
                'name': entry['name'], 'group': attrs.get('group-title', '95 OTHER / ODDITIES'),
                'order': order, 'logo': attrs.get('tvg-logo', ''),
                'variant': cid.partition('@')[2], 'sources': [],
                'officialUrl': ''
            }
        channel = channels[cid]
        headers = entry['headers']
        url = entry['url']
        sid = identity(url + json.dumps(headers, sort_keys=True))
        if any(s['id'] == sid for s in channel['sources']):
            continue
        path = urlsplit(url).path.lower()
        mime = 'application/dash+xml' if path.endswith('.mpd') else ('video/mp2t' if path.endswith('.ts') else 'application/x-mpegURL')
        channel['sources'].append({'id': sid, 'url': url, 'label': entry['name'], 'host': urlsplit(url).hostname or '', 'mime': mime, 'headers': headers, 'unsupportedDrm': entry['drm'], 'kind': 'direct'})
        if not channel['logo'] and attrs.get('tvg-logo'):
            channel['logo'] = attrs['tvg-logo']

    youtube_count = 0
    for channel in channels.values():
        base = channel['id'].partition('@')[0]
        if base not in YOUTUBE_PAGES:
            continue
        resolved = youtube_live.get(base) or {}
        url = str(resolved.get('url') or '')
        video_id = str(resolved.get('videoId') or '')
        parsed = urlsplit(url)
        valid = (parsed.scheme == 'https' and parsed.hostname in {'www.youtube.com','youtube.com'}
                 and parsed.path == '/watch' and re.fullmatch(r'[A-Za-z0-9_-]{11}', video_id)
                 and dict(parse_qsl(parsed.query)).get('v') == video_id)
        channel['youtubeLivePage'] = YOUTUBE_PAGES[base]
        channel['youtubeLabel'] = YOUTUBE_LABELS[base]
        channel['youtubeSourceId'] = identity('youtube|' + base)
        if not valid:
            # Older apps only understand concrete watch links. Version 0.4 reads
            # this channel metadata and resolves /live when the user tunes in.
            url = YOUTUBE_PAGES[base]
            warnings.append(f"{channel['name']}: current YouTube live video will be resolved on device.")
            continue
        else:
            youtube_count += 1
        channel['sources'].append({
            'id': identity('youtube|' + base), 'url': url, 'livePage': YOUTUBE_PAGES[base],
            'label': YOUTUBE_LABELS[base], 'host': 'www.youtube.com',
            'mime': 'application/x-external', 'headers': {}, 'unsupportedDrm': False, 'kind': 'youtube',
        })

    if b'<!ENTITY' in xml_bytes.upper():
        raise ValueError('XML entities are not accepted')
    root = ET.fromstring(xml_bytes)
    if root.tag != 'tv':
        raise ValueError('Expected an XMLTV tv root')
    supplemented = supplement_missing(root, channels, supplemental_xml)
    guide_sources = {'uk1': list(supplemented)} if supplemental_xml else {}
    for source, xml in (extra_guides or {}).items():
        added = supplement_missing(root, channels, xml, SUPPLEMENT_IDS if source == 'uk1' else mappings(source, channels))
        guide_sources[source] = added
        supplemented.extend(added)
    by_epg = {}
    for channel in channels.values():
        if channel['epgId']:
            by_epg.setdefault(channel['epgId'], []).append(channel['id'])
    programmes, seen = [], set()
    for p in root.findall('programme'):
        start, stop = stamp(p.get('start', '')), stamp(p.get('stop', ''))
        title = (p.findtext('title') or '').strip()
        if start is None or stop is None or stop <= start or not title:
            continue
        for cid in by_epg.get(p.get('channel'), []):
            key = identity(f'{cid}|{start}|{stop}|{title}')
            if key in seen:
                continue
            seen.add(key)
            details = []
            for child in p.findall('episode-num'):
                if child.text:
                    value = child.text.strip()
                    if child.get('system') == 'xmltv_ns':
                        parts = value.split('.')
                        try:
                            season = str(int(parts[0].strip()) + 1) if parts[0].strip().isdigit() else ''
                            episode = str(int(parts[1].strip().split('/')[0]) + 1) if len(parts) > 1 and parts[1].strip().split('/')[0].isdigit() else ''
                            value = ' '.join(x for x in [f'Series {season}' if season else '', f'Episode {episode}' if episode else ''] if x)
                        except (IndexError, ValueError):
                            value = ''
                    if value:
                        details.append(value)
            categories = [c.text.strip() for c in p.findall('category') if c.text]
            details.extend(categories)
            if p.findtext('date'):
                details.append(p.findtext('date'))
            for rating in p.findall('rating'):
                value = rating.findtext('value')
                if value:
                    details.append('Rating ' + value)
            if p.find('previously-shown') is not None:
                details.append('Repeat')
            if p.find('new') is not None:
                details.append('New')
            credits = p.find('credits')
            if credits is not None:
                details.extend(f'{c.tag.title()}: {c.text}' for c in credits if c.text)
            programmes.append({'key': key, 'channelId': cid, 'start': start, 'stop': stop, 'title': title, 'subtitle': (p.findtext('sub-title') or '').strip(), 'description': (p.findtext('desc') or '').strip(), 'details': ' · '.join(dict.fromkeys(details)), 'artwork': p.find('icon').get('src', '') if p.find('icon') is not None else ''})
    programmes.sort(key=lambda p: (p['channelId'], p['start'], p['stop']))
    channels, programmes, regional, aliases = combine_bbc(list(channels.values()), programmes, bbc_sources)
    channels = arrange_channels(channels)
    return {'schemaVersion': 1, 'sourceRevision': revision, 'supplementedChannels': supplemented, 'supplementalGuideSources': guide_sources, 'channelsWithGuide': len({p['channelId'] for p in programmes}), 'youtubeLiveSources': youtube_count, 'builtAt': int(datetime.now(timezone.utc).timestamp() * 1000), 'guideStart': min((p['start'] for p in programmes), default=0), 'guideEnd': max((p['stop'] for p in programmes), default=0), 'descriptionCount': sum(bool(p['description']) for p in programmes), 'warnings': sorted(set(warnings)), 'channels': channels, 'programmes': programmes, 'regionalProgrammes': regional, 'channelAliases': aliases}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--revision', default='unknown')
    parser.add_argument('--youtube-live', type=Path)
    parser.add_argument('--supplement', type=Path)
    parser.add_argument('--guides-dir', type=Path)
    args = parser.parse_args()
    live = {}
    if args.youtube_live and args.youtube_live.exists():
        try:
            live = json.loads(args.youtube_live.read_text(encoding='utf-8'))
        except (OSError, json.JSONDecodeError):
            live = {}
    guides = {}
    guide_warnings = []
    if args.guides_dir:
        for source in ('pluto', 'samsung', 'openepg', 'uk1', 'epgpw'):
            path = args.guides_dir / (source + '.xml.gz')
            if path.exists():
                try:
                    guides[source] = load_guide(path)
                except (OSError, ValueError, ET.ParseError) as error:
                    guide_warnings.append(f'{source} guide could not be read; other schedules retained.')
    xml = gzip.decompress((args.source / 'guide.xml.gz').read_bytes())
    data = build(
        (args.source / 'playlist.m3u').read_text(encoding='utf-8-sig'),
        xml,
        args.revision,
        live,
        load_guide(args.supplement) if args.supplement and args.supplement.exists() else None,
        guides,
        refresh_sources()
    )
    data['warnings'].extend(guide_warnings)
    if len(data['channels']) < 10 or len(data['programmes']) < 100:
        raise SystemExit('Refusing to publish an incomplete catalogue')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
    args.output.write_bytes(gzip.compress(encoded, mtime=0))
    summary = {k: data[k] for k in ('schemaVersion', 'sourceRevision', 'youtubeLiveSources', 'builtAt', 'guideStart', 'guideEnd', 'descriptionCount', 'channelsWithGuide', 'supplementedChannels', 'supplementalGuideSources', 'warnings')}
    summary.update(channels=len(data['channels']), programmes=len(data['programmes']), bytes=args.output.stat().st_size)
    args.output.with_name('status.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))

if __name__ == '__main__':
    main()
