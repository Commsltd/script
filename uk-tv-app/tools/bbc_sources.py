"""One BBC One/Two entry, retaining every regional source and its own guide.

BBC regional fallback is explicitly wanted for this app. It is not a general
rule for ITV, BBC News international editions, +1 services or other channels.
"""
import copy
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

API = 'https://iptv-org.github.io/api/streams.json'
DEFAULTS = {'BBCOne.uk': ('BBCOne.uk@LondonHD', 'BBC One', {'London', 'LondonHD'}),
            'BBCTwo.uk': ('BBCTwo.uk@HD', 'BBC Two', {'HD', 'SD'})}

def approved(row):
    url = urlsplit(row.get('url', ''))
    return (row.get('channel') in DEFAULTS and url.scheme == 'https'
            and not url.username and not url.password
            and (url.hostname or '').endswith(('.akamaized.net', '.bbci.co.uk'))
            and ':bbc_' in url.path and row.get('feed'))

def refresh_sources():
    """A failed catalogue lookup never removes the shipped source inventory."""
    seed = json.loads(Path(__file__).with_name('bbc-source-seed.json').read_text())['streams']
    try:
        with urlopen(Request(API, headers={'User-Agent': 'UKTelevision/catalogue'}), timeout=20) as response:
            data = response.read(12 * 1024 * 1024 + 1)
        if len(data) > 12 * 1024 * 1024:
            raise ValueError('Oversized stream list')
        rows = json.loads(data)
        if not isinstance(rows, list):
            raise ValueError('Invalid stream list')
        return [r for r in seed + rows if approved(r)]
    except (OSError, ValueError):
        return seed

def region_label(variant, url=''):
    if 'bbc_one_east_yorkshire' in url:
        return 'East Yorkshire'
    variant = re.sub(r'(HD|SD)$', '', variant)
    return re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', variant) or 'National'

def combine(channels, programmes, additions=()):
    channels = copy.deepcopy(channels)
    aliases, regional = {}, []
    for family, (preferred_id, name, defaults) in DEFAULTS.items():
        members = [c for c in channels if c['id'].partition('@')[0] == family]
        if not members:
            continue
        primary = next((c for c in members if c['id'] == preferred_id), None)
        primary = primary or next((c for c in members if c['id'].partition('@')[2] in defaults), members[0])
        primary_id = primary['id']
        members.sort(key=lambda c: (c['id'] != primary_id, c['id'].partition('@')[2] not in defaults, c['order']))
        guides = {p['channelId'] for p in programmes}
        sources = []
        def annotate(source, cid, variant):
            source = copy.deepcopy(source)
            region = region_label(variant, source['url'])
            guide_id = cid if cid in guides else ''
            # The upstream Yorkshire feed label includes East Yorkshire URLs.
            # Do not give those streams a different regional news schedule.
            if region == 'East Yorkshire':
                guide_id = ''
            source.update(region=region, guideChannelId=guide_id,
                          priority=0 if variant in defaults else 1)
            quality = source.get('quality') or ('HEVC' if 'hevc_' in source['url'] else '')
            fmt = 'HLS' if '.m3u8' in source['url'] else 'DASH' if '.mpd' in source['url'] else ''
            source['label'] = ' · '.join(x for x in (name, region + (' / default' if source['priority'] == 0 else ''), quality, fmt) if x)
            sources.append(source)
        for member in members:
            aliases[member['id']] = primary_id
            for source in member['sources']:
                annotate(source, member['id'], member['id'].partition('@')[2])
        for row in additions:
            if not approved(row) or row['channel'] != family:
                continue
            cid = family + '@' + row['feed']
            headers = {k:v for k,v in [('User-Agent', row.get('user_agent')), ('Referer', row.get('referrer'))] if v}
            url = row['url']
            source = {'id': hashlib.sha256((url + json.dumps(headers, sort_keys=True)).encode()).hexdigest()[:24],
                      'url':url, 'host':urlsplit(url).hostname, 'headers':headers,
                      'kind':'direct', 'unsupportedDrm':False, 'quality':row.get('quality') or '',
                      'mime':'application/dash+xml' if urlsplit(url).path.endswith('.mpd') else 'application/x-mpegURL'}
            # HD and SD versions of the same named region share a schedule.
            if cid not in guides:
                same_region = [m['id'] for m in members if region_label(m['variant']) == region_label(row['feed']) and m['id'] in guides]
                cid = same_region[0] if same_region else cid
            annotate(source, cid, row['feed'])
        # Default service first, HLS before DASH/HEVC, then regional backups.
        # Stable source IDs retain previous playback health and manual pins.
        sources.sort(key=lambda s:(s['priority'], 'hevc_' in s['url'], '.m3u8' not in s['url'],
                                    not (urlsplit(s['url']).hostname or '').endswith(('.akamaized.net','.bbci.co.uk'))))
        seen = set()
        primary['sources'] = [s for s in sources if not (s['id'] in seen or seen.add(s['id']))]
        primary.update(name=name, group='01 MAIN UK')
        removed = {m['id'] for m in members if m['id'] != primary_id}
        regional.extend(p for p in programmes if p['channelId'] in removed)
        programmes = [p for p in programmes if p['channelId'] not in removed]
        channels = [c for c in channels if c['id'] not in removed]
    return channels, programmes, regional, {k:v for k,v in aliases.items() if k != v}
