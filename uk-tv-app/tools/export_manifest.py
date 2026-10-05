#!/usr/bin/env python3
"""Read an immutable copy of the existing M3U/XMLTV outputs. Never changes them."""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl

ATTR = re.compile(r'([\w-]+)="([^"]*)"')

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

def build(playlist, xml_bytes, revision='unknown'):
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
                'officialUrl': 'https://www.youtube.com/@SkyNews/live' if cid.partition('@')[0] == 'SkyNews.uk' else ''
            }
        channel = channels[cid]
        headers = entry['headers']
        url = entry['url']
        sid = identity(url + json.dumps(headers, sort_keys=True))
        if any(s['id'] == sid for s in channel['sources']):
            continue
        path = urlsplit(url).path.lower()
        mime = 'application/dash+xml' if path.endswith('.mpd') else ('video/mp2t' if path.endswith('.ts') else 'application/x-mpegURL')
        channel['sources'].append({'id': sid, 'url': url, 'label': entry['name'], 'host': urlsplit(url).hostname or '', 'mime': mime, 'headers': headers, 'unsupportedDrm': entry['drm']})
        if not channel['logo'] and attrs.get('tvg-logo'):
            channel['logo'] = attrs['tvg-logo']
    if b'<!ENTITY' in xml_bytes.upper():
        raise ValueError('XML entities are not accepted')
    root = ET.fromstring(xml_bytes)
    if root.tag != 'tv':
        raise ValueError('Expected an XMLTV tv root')
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
    return {'schemaVersion': 1, 'sourceRevision': revision, 'builtAt': int(datetime.now(timezone.utc).timestamp() * 1000), 'guideStart': min((p['start'] for p in programmes), default=0), 'guideEnd': max((p['stop'] for p in programmes), default=0), 'descriptionCount': sum(bool(p['description']) for p in programmes), 'warnings': sorted(set(warnings)), 'channels': list(channels.values()), 'programmes': programmes}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--revision', default='unknown')
    args = parser.parse_args()
    xml = gzip.decompress((args.source / 'guide.xml.gz').read_bytes())
    data = build((args.source / 'playlist.m3u').read_text(encoding='utf-8-sig'), xml, args.revision)
    if len(data['channels']) < 10 or len(data['programmes']) < 100:
        raise SystemExit('Refusing to publish an incomplete catalogue')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
    args.output.write_bytes(gzip.compress(encoded, mtime=0))
    summary = {k: data[k] for k in ('schemaVersion', 'sourceRevision', 'builtAt', 'guideStart', 'guideEnd', 'descriptionCount', 'warnings')}
    summary.update(channels=len(data['channels']), programmes=len(data['programmes']), bytes=args.output.stat().st_size)
    args.output.with_name('status.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))

if __name__ == '__main__':
    main()
