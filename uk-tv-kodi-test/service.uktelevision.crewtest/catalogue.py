# SPDX-License-Identifier: GPL-3.0-only
"""Read actual Crew directories through Kodi; never invoke a play/settings route.

Folder handles are opaque and valid only for this engine session. Provider URLs
(and any credentials embedded in them) never leave the local Kodi process.
"""
import html
import json
import re
import uuid
from urllib.parse import urlparse, parse_qs, urlencode, unquote

PREFIX = 'plugin://plugin.video.thecrew/'
DIRECTORIES = frozenset(('movieNavigator', 'tvNavigator', 'movies', 'tvshows',
    'movieWidget', 'movieGenres', 'movieYears', 'movieLanguages', 'movieCertificates',
    'moviePersons', 'tvGenres', 'tvYears', 'tvLanguages', 'tvCertificates', 'tvNetworks',
    'tvPersons', 'docuNavigator', 'tmdbmovieslist', 'tmdbtvlist', 'traktlist',
    'movieSearchterm', 'tvSearchterm'))
TAG = re.compile(r'\[/?(?:B|I|COLOR(?: [^\]]*)?|UPPERCASE|LOWERCASE|CAPITALIZE)\]', re.I)
def clean(value, limit=500):
    return html.unescape(TAG.sub('', str(value or ''))).strip()[:limit]
def params(path):
    u = urlparse(path)
    if u.scheme != 'plugin' or u.netloc != 'plugin.video.thecrew' or u.path not in ('', '/') or u.fragment:
        return {}
    return {k:v[0] for k,v in parse_qs(u.query).items()}
def picture(raw):
    raw = str(raw or '')
    if raw.startswith('image://'):
        raw = unquote(raw[8:].rstrip('/'))
    u = urlparse(raw)
    return raw if u.scheme == 'https' and u.hostname and not u.username else ''

class CrewCatalogue:
    def __init__(self, execute):
        self.execute = execute
        self.routes = {}

    def directory(self, body):
        folder = body.get('folder')
        section = body.get('section', 'movie')
        if section not in ('movie', 'tv'):
            raise ValueError('Choose Movies or TV series')
        if folder:
            if folder not in self.routes:
                raise ValueError('Catalogue session expired. Open Movies or TV series again.')
            path = self.routes[folder]
        else:
            query = str(body.get('query') or '').strip()
            if query:
                if not 2 <= len(query) <= 120:
                    raise ValueError('Search must contain 2 to 120 characters')
                path = PREFIX + '?' + urlencode({'action':'movieSearchterm' if section=='movie' else 'tvSearchterm', 'name':query})
            else:
                path = PREFIX + '?action=' + ('movieNavigator' if section=='movie' else 'tvNavigator')
        if params(path).get('action') not in DIRECTORIES:
            raise ValueError('Unsupported catalogue directory')
        request = {'jsonrpc':'2.0','id':1,'method':'Files.GetDirectory','params':{
            'directory':path,'media':'video','properties':['title','year','plot','genre','thumbnail','art'],
            'sort':{'method':'none'},'limits':{'start':0,'end':500}}}
        raw = self.execute(json.dumps(request))
        if len(raw) > 4*1024*1024:
            raise ValueError('Catalogue page too large')
        reply = json.loads(raw)
        if 'error' in reply:
            raise RuntimeError('The Crew could not open this catalogue category')
        result = reply.get('result', {})
        rows = result.get('files', [])
        if result.get('limits', {}).get('total', len(rows)) > len(rows):
            raise RuntimeError('This category exceeds the page limit. Choose a narrower category or search.')
        return self.parse(rows, section)

    def parse(self, rows, section):
        folders, titles = [], []
        skipped = 0
        for item in rows:
            path = item.get('file', '')
            p = params(path)
            action = p.get('action', '')
            identifier = p.get('tmdb', '')
            if action in ('play','seasons','episodes') and re.fullmatch(r'[1-9][0-9]*', identifier):
                kind = 'tv' if action in ('seasons','episodes') or p.get('tvshowtitle') else 'movie'
                # Episode/calendar rows are not presented as full films or shows.
                if action=='play' and (p.get('episode') or p.get('season')):
                    skipped += 1
                    continue
                name = clean(p.get('tvshowtitle') if kind=='tv' else p.get('title')) or clean(item.get('title') or item.get('label'))
                if not name:
                    skipped += 1
                    continue
                year = str(p.get('year') or item.get('year') or '')
                genres = item.get('genre') or []
                if isinstance(genres, str): genres = genres.split(' / ')
                art = item.get('art') or {}
                titles.append({'title':name,'kind':'show' if kind=='tv' else 'movie',
                    'year':int(year) if year.isdigit() and 1880<=int(year)<=2200 else None,
                    'url':'crew://%s/%s' % (kind, identifier),
                    'poster':picture(art.get('poster') or item.get('thumbnail')),
                    'description':clean(item.get('plot'), 1600), 'genres':[clean(g,80) for g in genres[:12]]})
            elif item.get('filetype')=='directory' and action in DIRECTORIES:
                if len(self.routes)>=2000:
                    raise RuntimeError('Catalogue session is full. Reopen the catalogue.')
                handle = uuid.uuid4().hex
                self.routes[handle] = path
                folders.append({'id':handle,'label':clean(item.get('label')) or 'More titles'})
            else:
                skipped += 1
        return {'folders':folders,'titles':titles,'skipped':skipped}
