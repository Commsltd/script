"""Curated country shelves. Exact channel identities; never import shared accounts.

Retain every eligible alternative, including regional editions for the explicitly
listed national networks. Existing services keep their IDs, categories and sources.
The seed survives directory outages; an inventory entry is not proof of playback.
"""
import copy
import json
from pathlib import Path
from urllib.parse import urlsplit
from stream_sources import valid, source

COUNTRIES = {
    'TT': ('17 TRINIDAD & TOBAGO', 'TTT.tt CCNTV6.tt BizzTV.tt ScorchTV.tt RiddimHopeTV.tt SankhyaTV.tt TrinityTV.tt TheIslamicNetwork.tt'.split()),
    'IE': ('18 IRELAND', 'TG4.ie OireachtasTV.ie'.split()),
    'US': ('19 USA', 'ABCNewsLive.us CBSNews247.us NBCNewsNOW.us LiveNOWfromFOX.us PBS.us FoxWeather.us CBSNewsNewYork.us CBSNewsLosAngeles.us'.split()),
    'CA': ('20 CANADA', 'CBCTelevision.ca CBCNewsNetwork.ca CityNewsToronto.ca CityNewsVancouver.ca CityNewsCalgary.ca CityNewsEdmonton.ca BNNBloomberg.ca CPACEnglish.ca TheWeatherNetwork.ca KnowledgeNetwork.ca NTV.ca'.split()),
    'AU': ('21 AUSTRALIA', 'ABCTV.au ABCNews.au Channel9.au 9Gem.au 9Go.au 9Life.au 10Bold.au ABCKids.au ABCEntertains.au AusbizTV.au tickerNews.au C31Melbourne.au'.split()),
    'NZ': ('22 NEW ZEALAND', 'TVNZ1.nz TVNZ2.nz TVNZDUKE.nz WhakaataMaori.nz TeReo.nz ParliamentTV.nz JuiceTV.nz'.split()),
}
WEB = {
    'TTT.tt': 'https://player.tegotv.com/player.php?channel=1&about=0&chat=0&donation=0&epg=0',
    'CCNTV6.tt': 'https://geo.dailymotion.com/player/x8dgt.html?video=x7x3klh',
}
# These are public broadcaster/CDN endpoints in the directory, not private
# IPTV hosts. Do not accept raw IP streams or /live/user/password paths.
HOSTS = ('akamaized.net','cloudfront.net','amagi.tv','pbs.org','mjh.nz','jmp2.uk',
         'brightcove.com','5centscdn.com','streamlock.net','bizzmedia.biz','getstreamhosting.com',
         'cbsnews.com','abcnews.com','uplynk.com','9c9media.com','mediapackage.us-west-2.amazonaws.com')
DEFAULT_FEED = {'ABCTV.au':'NSW','Channel9.au':'Sydney','CBCTelevision.ca':'CBLTDT','PBS.us':'East'}
EXCLUDE_FEEDS = ('Plus1','Plus2','UK','UKHD','GB','GBHD')

def eligible(row):
    if not valid(row): return False
    parsed = urlsplit(row['url'])
    if parsed.scheme != 'https' or any(k in parsed.query.lower() for k in ('password=','username=')): return False
    if row['feed'] in EXCLUDE_FEEDS: return False
    if row['channel']=='PBS.us' and row['feed']!='East': return False
    host = parsed.hostname or ''
    return any(host==h or host.endswith('.'+h) for h in HOSTS)

def seed():
    return json.loads(Path(__file__).with_name('international-seed.json').read_text())

def expand(channels, streams=(), inventory=None):
    inventory=inventory or seed()
    result=copy.deepcopy(channels)
    metadata={c['id']:c for c in inventory['channels']}
    candidates=inventory['streams']+list(streams)
    for country,(group,ids) in COUNTRIES.items():
        for cid in ids:
            existing=[c for c in result if c['id'].partition('@')[0]==cid]
            if existing: continue  # augment() already retains their exact-feed backups.
            meta=metadata.get(cid)
            if not meta: continue
            matches=[r for r in candidates if r.get('channel')==cid and eligible(r)]
            matches.sort(key=lambda r:(r['feed']!=DEFAULT_FEED.get(cid),r['feed'],r['url']))
            sources=[]
            for row in matches:
                headers={k:v for k,v in [('User-Agent',row.get('user_agent')),('Referer',row.get('referrer'))] if v}
                entry=source(row['url'],headers,' · '.join(filter(None,[meta['name'],row['feed'],row.get('quality')])),0 if row['feed']==DEFAULT_FEED.get(cid) else 1)
                entry['region']=row['feed']
                if not any(s['id']==entry['id'] for s in sources): sources.append(entry)
            if cid in WEB:
                entry=source(WEB[cid],{},meta['name']+' · broadcaster player',0)
                entry.update(kind='web',backgroundCheck=False,mime='text/html')
                sources.insert(0,entry)
            if not sources: continue
            # A single national entry cycles its retained regional editions;
            # no invented programme guide is copied between regions/countries.
            result.append({'id':cid+'@National','epgId':'','family':cid,'name':meta['name'],
                'group':group,'order':len(result),'logo':meta.get('logo',''),'variant':'National',
                'sources':sources,'officialUrl':meta.get('website') or ''})
    return result
