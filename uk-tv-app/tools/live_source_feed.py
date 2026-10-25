"""Small address inventory for recovery, published atomically with the hourly guide.

No web/player pages or provider buttons. Channel identity and every native-media
alternative are retained. This is discovery data, not a claim of playback health.
"""
import gzip
import json
from urllib.parse import urlsplit


def build(catalogue):
    channels = []
    for channel in catalogue['channels']:
        sources = []
        for source in channel['sources']:
            address = urlsplit(source['url'])
            if (source.get('kind', 'direct') != 'direct' or source.get('unsupportedDrm')
                    or address.scheme not in ('http', 'https') or not address.hostname
                    or address.username or address.password):
                continue
            sources.append(source)
        if sources and not channel['id'].startswith('user.'):
            channels.append({'id': channel['id'], 'sources': sources})
    return {'schemaVersion': 1, 'builtAt': catalogue['builtAt'],
            'sourceRevision': catalogue['sourceRevision'], 'channels': channels}


def write(catalogue, path):
    data = build(catalogue)
    encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
    if not data['channels'] or len(encoded) > 6 * 1024 * 1024:
        raise ValueError('Empty or oversized stream inventory')
    path.write_bytes(gzip.compress(encoded, mtime=0))
