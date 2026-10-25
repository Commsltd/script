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
    'US': ('19 USA', 'ABCNewsLive.us CBSNews247.us NBCNewsNOW.us LiveNOWfromFOX.us PBS.us FoxWeather.us CBSNewsNewYork.us CBSNewsLosAngeles.us FilmRiseClassicTV.us FilmRiseWestern.us DoveChannel.us ClassicArtsShowcase.us BounceXL.us TheBobRossChannel.us'.split()),
    'CA': ('20 CANADA', 'CBCTelevision.ca CBCNewsNetwork.ca CityNewsToronto.ca CityNewsVancouver.ca CityNewsCalgary.ca CityNewsEdmonton.ca BNNBloomberg.ca CPACEnglish.ca TheWeatherNetwork.ca KnowledgeNetwork.ca NTV.ca'.split()),
    'AU': ('21 AUSTRALIA', 'ABCTV.au ABCNews.au Channel9.au 9Gem.au 9Go.au 9Life.au 10Bold.au ABCKids.au ABCEntertains.au AusbizTV.au tickerNews.au C31Melbourne.au'.split()),
    'NZ': ('22 NEW ZEALAND', 'WhakaataMaori.nz TeReo.nz ParliamentTV.nz JuiceTV.nz'.split()),
}
WEB = {
    'TrinityTV.tt': 'https://player.tegotv.com/player.php?channel=6&about=0&chat=0&donation=0&epg=0&autoplay=1',
    'TTT.tt': 'https://player.tegotv.com/player.php?channel=1&about=0&chat=0&donation=0&epg=0&autoplay=1',
    'CCNTV6.tt': 'https://www.tv6tnt.com/watch_live/',
}
# These are public broadcaster/CDN endpoints in the directory, not private
# IPTV hosts. Do not accept raw IP streams or /live/user/password paths.
HOSTS = ('akamaized.net','cloudfront.net','amagi.tv','pbs.org','mjh.nz','jmp2.uk',
         'brightcove.com','5centscdn.com','streamlock.net','bizzmedia.biz','getstreamhosting.com',
         'juicex.nz','kordia.net.nz','streamhoster.com','livepush.io',
         'frequency.stream','wurl.tv','cbsnews.com','abcnews.com','uplynk.com','9c9media.com','mediapackage.us-west-2.amazonaws.com')
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

def expand(channels, streams=(), inventory=None, refresh_spain=False, refresh_business=False):
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
    from fast_channels import expand as expand_fast
    result = expand_fast(result)
    from business_channels import expand as expand_business, refreshed_inventory as refresh_business_inventory
    result = expand_business(result, refresh_business_inventory() if refresh_business else None, streams)
    from spanish_channels import expand as expand_spain, refreshed_inventory
    result = expand_spain(result, refreshed_inventory() if refresh_spain else None)
    return label_news_countries(add_space_live(add_english_news(add_cgtn_english(add_rt_english(result, streams), streams), streams)))


def add_rt_english(channels, streams=()):
    """One English news channel, retaining native alternatives across refreshes."""
    result = copy.deepcopy(channels)
    cid = 'RT.ru@English'
    existing = next((c for c in result if c['id'] == cid), None)
    urls = [
        'https://rt-glb.rttv.com/dvr/rtnews/playlist.m3u8',
        'https://rt-glb.rttv.com/live/rtnews/playlist.m3u8',
        'https://wisewatchoott.wiseplayout.com/RT/master.m3u8',
    ]
    entries = []
    for url in urls:
        entries.append(source(url, {}, 'RT English · ' + urlsplit(url).hostname, len(entries)))
    # The directory can update addresses on the verified English-service hosts.
    for row in streams:
        if row.get('channel') != 'RT.ru' or not valid(row): continue
        address = urlsplit(row['url'])
        if address.scheme != 'https' or address.hostname not in ('rt-glb.rttv.com', 'wisewatchoott.wiseplayout.com'): continue
        if address.hostname == 'rt-glb.rttv.com' and '/rtnews/' not in address.path: continue
        if address.hostname == 'wisewatchoott.wiseplayout.com' and not address.path.startswith('/RT/'): continue
        headers = {k:v for k,v in [('User-Agent', row.get('user_agent')), ('Referer', row.get('referrer'))] if v}
        entry = source(row['url'], headers, 'RT English · ' + address.hostname, 3)
        if not any(s['id'] == entry['id'] for s in entries): entries.append(entry)
    if existing:
        seen = {s['id'] for s in entries}
        entries.extend(s for s in existing['sources'] if s['id'] not in seen)
        existing['sources'] = entries
    else:
        result.append({'id':cid, 'epgId':'', 'family':'RT.ru', 'name':'RT English',
            'group':'02 NEWS & POLITICS', 'order':len(result), 'logo':'', 'variant':'English',
            'sources':entries, 'officialUrl':'https://www.rt.com/on-air/'})
    return result


def add_cgtn_english(channels, streams=()):
    result = copy.deepcopy(channels)
    cid = 'CGTN.cn@English'
    existing = next((c for c in result if c['id'] == cid), None)
    urls = [
        'https://english-livebkali.cgtn.com/live/encgtn.m3u8',
        'https://amg00405-rakutentv-cgtn-rakuten-i9tar.amagi.tv/master.m3u8',
        'https://hlspackager.akamaized.net/live/DB/CGTN_ENGLISH/HLS/CGTN_ENGLISH.m3u8',
    ]
    entries = [source(url, {}, 'CGTN English · ' + urlsplit(url).hostname, i) for i, url in enumerate(urls)]
    routes = {(urlsplit(u).hostname, urlsplit(u).path) for u in urls}
    for row in streams:
        if row.get('channel') != 'CGTN.cn' or not valid(row): continue
        address = urlsplit(row['url'])
        if address.scheme != 'https' or (address.hostname, address.path) not in routes: continue
        headers = {k:v for k,v in [('User-Agent', row.get('user_agent')), ('Referer', row.get('referrer'))] if v}
        entry = source(row['url'], headers, 'CGTN English · ' + address.hostname, 3)
        if not any(s['id'] == entry['id'] for s in entries): entries.append(entry)
    if existing:
        seen = {s['id'] for s in entries}
        entries.extend(s for s in existing['sources'] if s['id'] not in seen)
        existing['sources'] = entries
    else:
        result.append({'id':cid, 'epgId':'', 'family':'CGTN.cn', 'name':'CGTN English',
            'group':'02 NEWS & POLITICS', 'order':len(result), 'logo':'', 'variant':'English',
            'sources':entries, 'officialUrl':'https://www.cgtn.com/channel/english'})
    return result


# English services with audio/video verified on 2026-10-24. No short-lived NTA
# signatures or inactive VOA black-screen feed are persisted here.
ENGLISH_NEWS = {'ArirangTV.kr': {'name': 'Arirang TV',
                  'website': 'https://www.arirang.com/',
                  'feeds': ['SD'],
                  'urls': ['https://amdlive-ch02-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8']},
 'NHKWorldJapan.jp': {'name': 'NHK World-Japan',
                      'website': 'https://www3.nhk.or.jp/nhkworld/',
                      'feeds': ['HD', 'SD'],
                      'urls': ['https://masterpl.hls.nhkworld.jp/hls/w/live/smarttv.m3u8',
                               'https://nhk.lls.pbs.org/index.m3u8']},
 'CNA.sg': {'name': 'CNA Singapore',
            'website': 'https://www.channelnewsasia.com/',
            'feeds': ['SD'],
            'urls': ['https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43aeb4a48ec97f66ebbe/index.m3u8']},
 'TaiwanPlusTV.tw': {'name': 'TaiwanPlus',
                     'website': 'https://www.taiwanplus.com',
                     'feeds': ['SD'],
                     'urls': ['https://fastly.live.brightcove.com/6385235869112/ap-northeast-1/6282251407001/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJob3N0IjoiaWU4OWZvLmVncmVzcy5pdjNqZDgiLCJhY2NvdW50X2lkIjoiNjI4MjI1MTQwNzAwMSIsImVobiI6ImZhc3RseS5saXZlLmJyaWdodGNvdmUuY29tIiwiaXNzIjoiYmxpdmUtcGxheWJhY2stc291cmNlLWFwaSIsInN1YiI6InBhdGhtYXB0b2tlbiIsImF1ZCI6WyI2MjgyMjUxNDA3MDAxIl0sImp0aSI6IjYzODUyMzU4NjkxMTIifQ.4c-V1Ks3nBx9nRljtn5-jCNP_nLuWBbk-pw9BFWZfMQ/playlist-hls.m3u8']},
 'DDIndia.in': {'name': 'DD India',
                'website': 'https://prasarbharati.gov.in/doordarshan/',
                'feeds': ['SD'],
                'urls': ['https://d2gvyg6lvauoko.cloudfront.net/230226/ddindia/chunks.m3u8',
                         'https://d2gvyg6lvauoko.cloudfront.net/230226/ddindia/playlist.m3u8?hdnts=st=1735898689~exp=1835898688~acl=*~hmac=f5fe24724fe05481e3841f9eb5ab8efdee0a3dd83645ae9dcf45703f525bab7b',
                         'https://d3qs3d2rkhfqrt.cloudfront.net/out/v1/ceda14583477426aa162a65392d8ea07/index.m3u8']},
 'AlJazeera.qa': {'name': 'Al Jazeera English',
                  'website': 'https://www.aljazeera.net/',
                  'feeds': ['English'],
                  'urls': ['https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8',
                           'https://live-hls-apps-aje-v3-fa.getaj.net/AJE/index.m3u8',
                           'https://live-hls-web-aje-fa.thehlive.com/AJE/index.m3u8',
                           'https://amg00222-amg00222c4-amgplt0002.playout.now3.amagi.tv/aljazeera-noscte/playlist.m3u8']},
 'TRTWorld.tr': {'name': 'TRT World',
                 'website': 'https://www.trtworld.com/',
                 'feeds': ['SD'],
                 'urls': ['https://tv-trtworld.medya.trt.com.tr/master.m3u8']},
 'SABCNews.za': {'name': 'SABC News',
                 'website': 'http://www.sabc.co.za/',
                 'feeds': ['SD'],
                 'urls': ['https://sabconetanw.cdn.mangomolo.com/news/smil:news.stream.smil/master.m3u8']},
 'France24.fr': {'name': 'France 24 English',
                 'website': 'https://www.france24.com/',
                 'feeds': ['English'],
                 'urls': ['https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_5000.m3u8',
                          'https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_5000.m3u8',
                          'https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_2300.m3u8',
                          'https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_2300.m3u8',
                          'https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_900.m3u8',
                          'https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_900.m3u8',
                          'https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_500.m3u8',
                          'https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_500.m3u8',
                          'https://amg00106-amg00106c1-samsung-nz-4151.playouts.now.amagi.tv/playlist.m3u8']},
 'DW.de': {'name': 'DW English',
           'website': 'https://www.dw.com/',
           'feeds': ['English'],
           'urls': ['https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/stream03/streamPlaylist.m3u8',
                    'https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/master.m3u8']},
 'AfricanewsEnglish.fr': {'name': 'Africanews English',
                          'website': 'https://www.africanews.com/',
                          'feeds': ['Poland', 'SD'],
                          'urls': ['https://cdn-euronews.akamaized.net/live/eds/africanews-en/25049/index.m3u8',
                                   'https://c3c275b999764df8a2dd55ffe2996818.mediatailor.eu-west-1.amazonaws.com/v1/master/0547f18649bd788bec7b67b746e47670f558b6b2/production-LiveChannel-6576/bitok/eyJzdGlkIjoiOTU0NDAyODQtOTU0My00Yzc2LThmZjQtNDRhY2YwYmQxYTYwIiwibWt0IjoicGwiLCJjaCI6NjYwNiwicHRmIjo1fQ==/26036/africanews-en.m3u8']},
 'AlArabiyaEnglish.sa': {'name': 'Al Arabiya English',
                         'website': '',
                         'feeds': ['SD'],
                         'urls': ['https://live.alarabiya.net/alarabiapublish/english/playlist_dvr.m3u8']},
 'CBNNews.us': {'name': 'CBN News',
                'website': 'https://www1.cbn.com/',
                'feeds': ['SD'],
                'urls': ['https://fastly.live.brightcove.com/6380396819112/us-east-1/734546207001/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJob3N0IjoiczFpM3ZpLmVncmVzcy50N2M3emwiLCJhY2NvdW50X2lkIjoiNzM0NTQ2MjA3MDAxIiwiZWhuIjoiZmFzdGx5LmxpdmUuYnJpZ2h0Y292ZS5jb20iLCJpc3MiOiJibGl2ZS1wbGF5YmFjay1zb3VyY2UtYXBpIiwic3ViIjoicGF0aG1hcHRva2VuIiwiYXVkIjpbIjczNDU0NjIwNzAwMSJdLCJqdGkiOiI2MzgwMzk2ODE5MTEyIn0.GDYp4IWtzwPkupEWeeOavnioVknO-Ev3UGlHvM1rE6I/playlist-hls.m3u8']},
 'CheddarNews.us': {'name': 'Cheddar News',
                    'website': 'https://cheddar.com/',
                    'feeds': ['SD'],
                    'urls': ['https://hls.livecdn.io/cheddar.com/cheddar/playlist.m3u8',
                             'https://wurlcheddar.global.transmit.live/hls/689ceac9301c34d3919676f3/v1/Cheddar/samsung_us/latest/main/hls/playlist.m3u8']},
 'CNNNews18.in': {'name': 'CNN-News18',
                  'website': 'https://www.news18.com/',
                  'feeds': ['SD'],
                  'urls': ['https://n18syndication.akamaized.net/bpk-tv/CNN_News18_NW18_MOB/output01/master.m3u8']},
 'i24NEWSEnglishWorld.il': {'name': 'i24NEWS English',
                            'website': 'https://www.i24news.tv/en/',
                            'feeds': ['SD'],
                            'urls': ['https://i24newsenglish-cdn.encoders.immergo.tv/master.m3u8',
                                     'https://hlspackager.akamaized.net/live/DB/i24_ENGLISH/HLS/i24_ENGLISH.m3u8']},
 'IndiaToday.in': {'name': 'India Today',
                   'website': 'https://www.indiatoday.in/',
                   'feeds': ['SD'],
                   'urls': ['https://d1rc86nwwc9fag.cloudfront.net/vglive-sk-293160/master.m3u8',
                            'https://indiatodaylive.akamaized.net/hls/live/2014320/indiatoday/indiatodaylive/playlist.m3u8']},
 'NDTV24x7.in': {'name': 'NDTV 24x7',
                 'website': 'https://www.ndtv.com/',
                 'feeds': ['SD'],
                 'urls': ['https://ndtv24x7elemarchana.akamaized.net/hls/live/2003678/ndtv24x7/master.m3u8']},
 'NewsCentral.ng': {'name': 'News Central',
                    'website': 'http://newscentral.africa/',
                    'feeds': ['SD'],
                    'urls': ['https://wf.newscentral.ng:8443/hls/stream.m3u8']},
 'NewsmaxTV.us': {'name': 'Newsmax TV',
                  'website': 'https://www.newsmaxtv.com/',
                  'feeds': ['SD'],
                  'urls': ['https://nmx1ota.akamaized.net/hls/live/2107010/Live_1/index.m3u8']},
 'PressTV.ir': {'name': 'Press TV',
                'website': 'https://www.presstv.ir/',
                'feeds': ['SD'],
                'urls': ['https://live.presstv.co.uk/hls/presstv.m3u8']},
 'RepublicTV.in': {'name': 'Republic TV',
                   'website': 'https://www.republicworld.com/',
                   'feeds': ['SD'],
                   'urls': ['https://d3qs3d2rkhfqrt.cloudfront.net/out/v1/2e31d831f08640ff92f65003bdc89991/index.m3u8']},
 'ScrippsNews.us': {'name': 'Scripps News',
                    'website': 'https://scrippsnews.com/',
                    'feeds': ['SD'],
                    'urls': ['https://jmp2.uk/plu-5459795fc9f133a519bc0bef.m3u8',
                             'https://pb-syzbb4sicfx4g.akamaized.net/ScrippsNews_US.m3u8',
                             'https://aegis-cloudfront-1.tubi.video/7e1c26b7-7975-4240-9a4f-480eaa8f3ea4/playlist.m3u8']},
 'WION.in': {'name': 'WION',
             'website': 'https://www.wionews.com/',
             'feeds': ['SD'],
             'urls': ['https://d7x8z4yuq42qn.cloudfront.net/index_7.m3u8',
                      'https://d7x8z4yuq42qn.cloudfront.net/index_1.m3u8',
                      'https://d7x8z4yuq42qn.cloudfront.net/index_2.m3u8',
                      'https://d7x8z4yuq42qn.cloudfront.net/index_5.m3u8',
                      'https://d7x8z4yuq42qn.cloudfront.net/index_4.m3u8',
                      'https://d7x8z4yuq42qn.cloudfront.net/index_3.m3u8',
                      'https://wion-klowdtv.amagi.tv/playlist.m3u8']},
 'GlobalNews.ca': {'name': 'Global News National 24/7',
                   'website': 'https://globalnews.ca/',
                   'feeds': ['National'],
                   'urls': ['https://live.corusdigitaldev.com/groupd/live/49a91e7f-1023-430f-8d66-561055f3d0f7/live.isml/.m3u8']},
 'AL24News.dz': {'name': 'AL24 News · Mixed languages',
                 'variant': 'Mixed',
                 'website': '',
                 'feeds': ['SD'],
                 'urls': ['https://cdn.live.easybroadcast.io/abr_corp/66_al24_u4yga6h/corp/66_al24_u4yga6h_240p/chunks.m3u8']},
 'CRTVNews.cm': {'name': 'CRTV News · English/French',
                 'variant': 'Mixed',
                 'website': '',
                 'feeds': ['SD'],
                 'urls': ['https://live1.acangroup.org:1929/publiclive/crtv_news/playlist.m3u8']},
 'DDNews.in': {'name': 'DD News · Hindi',
               'variant': 'Hindi',
               'website': '',
               'feeds': ['HD'],
               'urls': ['https://d3qs3d2rkhfqrt.cloudfront.net/out/v1/0811cd8c37ca4c409d5385a6cd2fa18b/index.m3u8']}}

def add_english_news(channels, streams=()):
    """Preserve station identities/backups; refresh only verified English routes."""
    result = copy.deepcopy(channels)
    for cid, spec in ENGLISH_NEWS.items():
        identities = {cid}
        # Older EPG inventories used separate language-specific service IDs.
        identities.update({'AlJazeera.qa': ['AlJazeeraEnglish.qa'],
                           'France24.fr': ['France24English.fr'],
                           'DW.de': ['DWEnglish.de']}.get(cid, []))
        existing = next((c for c in result if c['id'].partition('@')[0] in identities
                         and (c['id'].partition('@')[0] != cid
                              or cid not in ('AlJazeera.qa', 'France24.fr', 'DW.de')
                              or c.get('variant') == 'English'
                              or c['id'].partition('@')[2] in ('English', 'EnglishHD'))), None)
        entries = [source(u, {}, spec['name'] + ' · ' + urlsplit(u).hostname, i)
                   for i, u in enumerate(spec['urls'])]
        routes = {(urlsplit(u).hostname, urlsplit(u).path) for u in spec['urls']}
        for row in streams:
            if row.get('channel') != cid or row.get('feed') not in spec['feeds'] or not valid(row): continue
            address = urlsplit(row['url'])
            if address.scheme != 'https' or (address.hostname, address.path) not in routes: continue
            headers = {k:v for k,v in [('User-Agent',row.get('user_agent')),('Referer',row.get('referrer'))] if v}
            entry = source(row['url'], headers, spec['name'] + ' · ' + address.hostname, len(entries))
            if not any(s['id'] == entry['id'] for s in entries): entries.append(entry)
        if existing:
            seen = {s['id'] for s in entries}
            entries.extend(s for s in existing['sources'] if s['id'] not in seen)
            existing['sources'] = entries
        else:
            result.append({'id':cid+'@'+spec.get('variant','English'), 'epgId':'', 'family':cid, 'name':spec['name'],
                'group':'02 NEWS & POLITICS', 'order':len(result), 'logo':'', 'variant':spec.get('variant','English'),
                'sources':entries, 'officialUrl':spec['website']})
    return result

# Use the service's country/edition, not the CDN host or its parent company's base.
NEWS_COUNTRY_NAMES = {'uk':'UK', 'us':'USA', 'ca':'Canada', 'au':'Australia',
    'nz':'New Zealand', 'ie':'Ireland', 'tt':'Trinidad & Tobago', 'in':'India',
    'sg':'Singapore', 'tw':'Taiwan', 'jp':'Japan', 'kr':'South Korea', 'cn':'China',
    'qa':'Qatar', 'tr':'Turkey', 'za':'South Africa', 'fr':'France', 'de':'Germany',
    'ru':'Russia', 'ng':'Nigeria', 'ir':'Iran', 'il':'Israel', 'sa':'Saudi Arabia',
    'pk':'Pakistan', 'gh':'Ghana', 'ke':'Kenya', 'dz':'Algeria', 'cm':'Cameroon',
    'es':'Spain', 'ch':'Switzerland', 'eg':'Egypt', 'bw':'Botswana', 'zw':'Zimbabwe', 'my':'Malaysia'}
NEWS_COUNTRY_OVERRIDES = {'EuronewsEnglish.fr':'Europe', 'AfricanewsEnglish.fr':'Pan-African',
    'BloombergTV.us@Europe':'Europe'}
COUNTRY_SHELF_NEWS = {'BNNBloomberg.ca', 'CPACEnglish.ca', 'FoxWeather.us',
    'LiveNOWfromFOX.us', 'AusbizTV.au'}

def label_news_countries(channels):
    result = copy.deepcopy(channels)
    for channel in result:
        cid = channel['id'].partition('@')[0]
        if not (channel.get('group') in ('02 NEWS & POLITICS', '08 INTERNATIONAL', '13 WORLD & LANGUAGES') or
                'news' in channel.get('name','').lower() or cid in COUNTRY_SHELF_NEWS):
            continue
        country = channel_country(channel)
        if country and not channel['name'].endswith(' ('+country+')'):
            channel['name'] += ' ('+country+')'
    return result


def add_space_live(channels):
    """One Sen service with independent native CDN alternatives."""
    result = copy.deepcopy(channels)
    cid = 'SpaceLivepoweredbysen.uk@SD'
    existing = next((c for c in result if c['id'].partition('@')[0] == cid.partition('@')[0]), None)
    urls = ['https://linear-1224.frequency.stream/dist/lg-uk/1224/hls/master/playlist.m3u8',
            'https://d111bsx9oooqoh.cloudfront.net/playlist.m3u8']
    entries = [source(url, {}, 'Space Live / Sen · ' + urlsplit(url).hostname, i)
               for i, url in enumerate(urls)]
    if existing:
        seen = {s['id'] for s in entries}
        existing['sources'] = entries + [s for s in existing['sources'] if s['id'] not in seen]
    else:
        result.append({'id':cid, 'family':cid.partition('@')[0], 'epgId':'',
            'name':'Space Live / Sen', 'group':'06 DOCUMENTARIES', 'order':len(result),
            'logo':'', 'variant':'SD', 'sources':entries, 'officialUrl':'https://www.sen.com/live'})
    return result


def channel_country(channel):
    if channel.get('country'): return channel['country']
    cid, _, edition = channel['id'].partition('@')
    override = NEWS_COUNTRY_OVERRIDES.get(channel['id'], NEWS_COUNTRY_OVERRIDES.get(cid))
    if override: return override
    if edition in ('UK', 'UKHD', 'GB', 'GBHD'): return 'UK'
    return NEWS_COUNTRY_NAMES.get(cid.rsplit('.', 1)[-1].lower(), 'Other')
