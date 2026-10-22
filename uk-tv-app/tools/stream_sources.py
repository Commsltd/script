"""Retain additional public feeds by exact station identity; refresh official Sky URLs.

No name matching, credentials, region substitution or three-source limit. The
inventory is not a claim of playability; the viewer's device tests its own route.
"""
import copy
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

API = 'https://iptv-org.github.io/api/streams.json'
SKY_PAGE = 'https://news.sky.com/watch-live'

def fetch(url, limit):
    with urlopen(Request(url, headers={'User-Agent': 'UKTelevision/catalogue'}), timeout=20) as r:
        data = r.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Source response too large')
    return data

def valid(row):
    if not isinstance(row, dict):
        return False
    url = urlsplit(row.get('url') or '')
    return (bool(row.get('channel') and row.get('feed')) and url.scheme in ('https','http')
            and bool(url.hostname) and not url.username and not url.password
            and url.path.lower().endswith(('.m3u8','.mpd','.ts'))
            and all(not any(c in str(row.get(k) or '') for c in '\r\n') for k in ('user_agent','referrer')))

def refresh():
    seed = json.loads(Path(__file__).with_name('stream-source-seed.json').read_text())
    try:
        rows = json.loads(fetch(API, 12 * 1024 * 1024))
        if not isinstance(rows, list): raise ValueError('Expected stream list')
        return seed + [r for r in rows if valid(r)]
    except (OSError, ValueError):
        return seed

def sky_urls(html):
    found = []
    for key in ('dynamicStreamUrl', 'fallbackStreamUrl'):
        match = re.search(r'const\s+' + key + r'\s*=\s*"([^"\r\n]+)"', html)
        if not match: continue
        url = match[1]
        parsed = urlsplit(url)
        if (parsed.scheme == 'https' and (parsed.hostname or '').endswith('.delivery.skycdp.com')
                and parsed.path.endswith('.m3u8') and not parsed.username and not parsed.password):
            found.append({'kind': 'dynamic' if key.startswith('dynamic') else 'fallback', 'url': url})
    # Never silently replace the ad-inserting stream with the fallback.
    return found if len(found) == 2 else []

def refresh_sky():
    seed = json.loads(Path(__file__).with_name('sky-source-seed.json').read_text())
    try:
        return sky_urls(fetch(SKY_PAGE, 2 * 1024 * 1024).decode()) or seed
    except (OSError, ValueError):
        return seed

def source(url, headers, label, priority=1):
    path = urlsplit(url).path.lower()
    return {'id': hashlib.sha256((url + json.dumps(headers, sort_keys=True)).encode()).hexdigest()[:24],
            'url': url, 'label': label, 'host': urlsplit(url).hostname, 'headers': headers,
            'mime': 'application/dash+xml' if path.endswith('.mpd') else 'video/mp2t' if path.endswith('.ts') else 'application/x-mpegURL',
            'kind': 'direct', 'unsupportedDrm': False, 'priority': priority, 'backgroundCheck': True}

def augment(channels, additions=(), sky=()):
    channels = copy.deepcopy(channels)
    by_id = {c['id']: c for c in channels}
    for row in additions:
        if not valid(row) or row['channel'] in ('BBCOne.uk','BBCTwo.uk'): continue
        channel = by_id.get(row['channel'] + '@' + row['feed'])
        if not channel: continue
        headers = {key: value for key, value in [('User-Agent', row.get('user_agent')), ('Referer', row.get('referrer'))] if value}
        s = source(row['url'], headers, ' · '.join(x for x in (channel['name'], row.get('quality'), urlsplit(row['url']).hostname) if x))
        if not any(old['url'] == s['url'] and old.get('headers',{}) == headers for old in channel['sources']):
            channel['sources'].append(s)
    for channel in channels:
        if channel['id'].partition('@')[0] != 'SkyNews.uk' or not sky: continue
        fresh = [source(r['url'], {}, 'Sky News · official ' + ('live stream' if r['kind']=='dynamic' else 'backup'), i)
                 for i,r in enumerate(sky)]
        ids = {s['id'] for s in fresh}
        for old in channel['sources']:
            if old['id'] not in ids:
                old['priority'] = max(2, old.get('priority',0))
                fresh.append(old)
        channel['sources'] = fresh
    return channels
