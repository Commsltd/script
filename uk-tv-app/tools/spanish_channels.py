"""Spain broadcaster sources with exact TDTChannels service/guide identities."""
import copy
import ipaddress
import json
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

CATALOGUE = 'https://www.tdtchannels.com/lists/tv.json'


def seed():
    return json.loads(Path(__file__).with_name('spain-channel-seed.json').read_text())


def public_hls(url):
    try:
        parsed = urlsplit(url)
        host = parsed.hostname or ''
        if (parsed.scheme not in ('https', 'http') or parsed.username or parsed.password
                or not host or host.endswith(('.local', '.localhost')) or host == 'localhost'
                or '[' in url or '\n' in url or '\r' in url
                or not parsed.path.lower().endswith('.m3u8')): return False
        try: return ipaddress.ip_address(host).is_global
        except ValueError: return '.' in host
    except ValueError:
        return False


def refreshed_inventory():
    """Retain tested routes; append current public alternatives for exact services."""
    inventory = seed()
    try:
        with urlopen(Request(CATALOGUE, headers={'User-Agent':'Mozilla/5.0 (compatible; UKTelevision/catalogue)'}), timeout=20) as response:
            raw = response.read(4*1024*1024+1)
        if len(raw)>4*1024*1024: return inventory
        data = json.loads(raw)
        country = next(c for c in data['countries'] if c['name']=='Spain')
        current = {area['name']+'|'+c['name']:c for area in country['ambits'] for c in area['channels']}
        for spec in inventory:
            known = {r['url'] for r in spec['routes']}
            for option in current.get(spec['tdtKey'],{}).get('options',[]):
                url = option.get('url','')
                if option.get('format')!='m3u8' or not public_hls(url) or url in known: continue
                route = dict(spec['routes'][0], url=url, label='Spain · broadcaster alternative')
                spec['routes'].append(route);known.add(url)
    except (OSError, ValueError, KeyError, TypeError, StopIteration):
        pass
    return inventory


def expand(channels, inventory=None):
    from fast_channels import expand as expand_routes
    specs = seed() if inventory is None else inventory
    rows = expand_routes(channels, specs)
    metadata = {s['id']:s for s in specs}
    for channel in rows:
        spec = metadata.get(channel['id'])
        if spec:
            channel.update(country='Spain', group='23 SPAIN',
                           englishSubtitles=spec.get('englishSubtitles',False),
                           streamVerified=spec.get('streamVerified',False))
    return rows


def add_guides(data, guides, now_ms=None):
    from fast_channels import add_guides as add_exact_guides
    add_exact_guides(data, guides, seed(), now_ms)
