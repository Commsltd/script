"""Exact country and regional schedules; no name-based or cross-region guesses."""
import copy
import time
import xml.etree.ElementTree as ET

# Directory feed identity -> XMLTV service identity. A missing edition stays blank.
SERVICES = {
    # TVPassport station s63246 is TTT Trinidad, carried in the Jamaican guide.
    'jm': {'TTT.tt': {'':'TTT.jm'}},
    'nz_mjh': {'WhakaataMaori.nz':{'SD':'mjh-maori-tv'},'TeReo.nz':{'SD':'mjh-te-reo'},'ParliamentTV.nz':{'SD':'mjh-parliament-tv'},'JuiceTV.nz':{'SD':'mjh-mood-1290'}},
    'ie': {'TG4.ie': {'SD':'TG4.HD.ie'}, 'OireachtasTV.ie': {'SD':'Oireachtas.TV.ie'}},
    'nz': {'WhakaataMaori.nz': {'SD':'Whakaata.Māori.nz'}, 'ParliamentTV.nz': {'SD':'Parliament.nz'}, 'JuiceTV.nz': {'SD':'Juice.TV.nz'}},
    'ca': {
        'CBCTelevision.ca': dict(zip(
            ['CBCTDT','CBETDT','CBHTDT','CBKTDT','CBLTDT','CBMTDT','CBNTDT','CBOTDT','CBRTDT','CBUTDT','CBWTDT','CBXTDT'],
            ['CBCT.ca2','CBC.Television-Windsor.9.ca2','CBC.Halifax.HD.ca2','CBC.Saskatchewan.ca2','CBC.Toronto.HD.ca2','CBMT.-.CBC.Montreal.ca2','CBC.St..John’s.ca2','CBC.Ottawa.HD.ca2','CBC.Calgary.HD.ca2','CBC.Vancouver.HD.ca2','CBC.Winnipeg.HD.ca2','CBC.Edmonton.HD.ca2'])),
        'CBCNewsNetwork.ca': {'SD':'CBC.News.Network.HD.ca2'},
        'BNNBloomberg.ca': {'SD':'BNN.Bloomberg.HD.ca2'},
        'CPACEnglish.ca': {'SD':'[CPAC].Cable.Public.Affairs.Channel.HD.ca2'},
        'KnowledgeNetwork.ca': {'SD':'Knowledge.Network.ca2'},
        'NTV.ca': {'CJONDT':'NTV.HD.ca2'},
        # CityNews streams are not Citytv entertainment or CityNews radio.
    },
    'au': {
        'ABCTV.au': {r:'ABC'+city+'.au' for r,city in [('NSW','Sydney'),('QLD','Brisbane'),('SA','Adelaide'),('TAS','Hobart'),('VIC','Melbourne'),('WA','Perth')]},
        **{cid: {city:prefix+city+'.au' for city in ['Sydney','Adelaide','Brisbane','Hobart','Melbourne','Perth']} for cid,prefix in [
            ('Channel9.au','9'),('9Gem.au','9Gem'),('9Go.au','9Go'),('9Life.au','9Life'),('10Bold.au','10Bold'),('ABCKids.au','ABCKids'),('ABCEntertains.au','ABCEntertains'),('ABCNews.au','ABCNews')]},
    },
    'us': {'ABCNewsLive.us': {'SD':'ABC.News.Live.us2'}, 'CBSNews247.us': {'SD':'CBS.News.National.Stream.us2'}, 'PBS.us': {'East':'PBS.Stream.us2'}},
}

# Exact language/service identities. Never use French/Arabic i24 or France 24,
# NHK World Premium, or a regional Global entertainment schedule as substitutes.
NEWS_GUIDES = {
    'news_cbn': {'CBNNews.us@English':'CBNNews.official'},
    'news_aje': {'AlJazeera.qa@English':'AlJazeera.English.net'},
    'uk1': {'ArirangTV.kr@English':'Arirang.TV.HD.uk',
            'NHKWorldJapan.jp@English':'NHK.World.HD.uk',
            'TRTWorld.tr@English':'TRT.World.HD.uk',
            'France24.fr@English':'FRANCE.24.HD.uk'},
    'news_in': {'DDIndia.in@English':'DD.India.in',
                'DDNews.in@Hindi':'DD.News.in',
                'TaiwanPlusTV.tw@English':'Taiwan.Plus.in',
                'CNNNews18.in@English':'CNN.NEWS.18.in',
                'IndiaToday.in@English':'India.Today.in',
                'NDTV24x7.in@English':'NDTV.24x7.in',
                'RepublicTV.in@English':'Republic.TV.in',
                'WION.in@English':'Wion.in',
                'RT.ru@English':'RT.TV.in'},
    'news_in4': {'RepublicTV.in@English':'REPUBLIC.TV.in'},
    'news_kr': {'ArirangTV.kr@English':'Arirang.World.kr'},
    'news_sg': {'CNA.sg@English':'CNA.(HD).sg',
                'NHKWorldJapan.jp@English':'NHK.World.–.Japan.(HD).sg',
                'France24.fr@English':'France.24.(English).sg',
                'DW.de@English':'Deutsche.Welle.(English).sg',
                'CGTN.cn@English':'CGTN.sg'},
    'news_za': {'SABCNews.za@English':'SABC.News.za',
                'AfricanewsEnglish.fr@English':'Africanews.za'},
    'news_ng': {'NewsCentral.ng@English':'News.Central.TV.ng'},
    'news_il': {'i24NEWSEnglishWorld.il@English':'i24NEWS.ENGLISH.il'},
    'us': {'CheddarNews.us@English':'Cheddar.us2',
           'NewsmaxTV.us@English':'Newsmax.TV.HD.us2',
           'ScrippsNews.us@English':'Scripps.News.us2'},
}

def mappings(source, channels):
    result={}
    services=SERVICES.get(source,{})
    for cid,c in channels.items():
        if c.get('_guideSource')==source:
            result[cid]=c['_guideId']
        else:
            base,_,region=cid.partition('@')
            guide=services.get(base,{}).get(region)
            if guide: result[cid]=guide
    return result

def add_guides(data, guides, now_ms=None):
    # Expansion happens after the original UK catalogue is assembled. Give each
    # alternative its own schedule, including when the player switches regions.
    from export_manifest import supplement_missing, read_programmes, identity
    now_ms = int(time.time()*1000) if now_ms is None else now_ms
    virtual={}; defaults={}; targets={}
    for c in data['channels']:
        if c.get('variant')!='National': continue
        for source,services in SERVICES.items():
            regions=services.get(c['family'],{})
            for i,stream in enumerate(c['sources']):
                region=stream.get('region','')
                guide=regions.get(region)
                if not guide: continue
                cid=c['family']+'@'+region
                virtual[cid]={'id':cid,'epgId':cid,'sources':[],'_guideSource':source,'_guideId':guide}
                targets.setdefault(cid,[]).append(stream)
                if i==0: defaults[c['id']]=cid
    root=ET.Element('tv')
    populated={p['channelId'] for p in data['programmes']}
    stations={c['id']:dict(c,epgId=c['id']) for c in data['channels'] if c['id'] not in populated}
    if 'pluto_us' in guides:
        from guide_supplements import mappings as provider_mappings
        added=supplement_missing(root,stations,guides['pluto_us'],provider_mappings('pluto_us',stations))
        data['programmes'].extend(read_programmes(root,stations))
        data['supplementalGuideSources'].setdefault('pluto_us',[]).extend(added)
        for cid in added:
            for stream in stations[cid]['sources']: stream['guideChannelId']=cid
    # Imported English/mixed services are regular station rows, not the
    # National regional wrappers handled below. Existing primary listings win.
    populated={p['channelId'] for p in data['programmes']}
    news_stations={c['id']:dict(c,epgId=c['id']) for c in data['channels'] if c['id'] not in populated}
    news_root=ET.Element('tv')
    for source, mapping in NEWS_GUIDES.items():
        if source not in guides: continue
        # An expired source must not block a fresher exact-service fallback.
        from export_manifest import stamp
        from guide_supplements import parse_xml
        current_ids={p.get('channel') for p in parse_xml(guides[source]).findall('programme')
                     if (stamp(p.get('stop','')) or 0)>now_ms}
        current_mapping={cid:guide for cid,guide in mapping.items() if guide in current_ids}
        added=supplement_missing(news_root,news_stations,guides[source],current_mapping)
        data['supplementalGuideSources'].setdefault(source,[]).extend(added)
    data['programmes'].extend(read_programmes(news_root,news_stations))
    root=ET.Element('tv')
    for source in SERVICES:
        if source not in guides: continue
        added=supplement_missing(root,virtual,guides[source],mappings(source,virtual))
        data['supplementalGuideSources'].setdefault(source,[]).extend(added)
    regional=read_programmes(root,virtual)
    populated={p['channelId'] for p in regional}
    for cid in populated:
        for stream in targets[cid]: stream['guideChannelId']=cid
    # Default national row shows its actual first edition, not a country's
    # arbitrarily selected programme. Unmapped fallback editions stay blank.
    reverse={v:k for k,v in defaults.items()}
    for p in regional:
        if p['channelId'] in reverse:
            c=copy.deepcopy(p);c['channelId']=reverse[p['channelId']]
            c['key']=identity(f"{c['channelId']}|{c['start']}|{c['stop']}|{c['title']}")
            data['programmes'].append(c)
    data.setdefault('regionalProgrammes',[]).extend(regional)
    from fast_channels import add_guides as add_fast_guides
    add_fast_guides(data, guides, now_ms=now_ms)
    from spanish_channels import add_guides as add_spain_guides
    add_spain_guides(data, guides, now_ms=now_ms)
    data['programmes'].sort(key=lambda p:(p['channelId'],p['start'],p['stop']))
    data['channelsWithGuide']=len({p['channelId'] for p in data['programmes']})
    data['guideStart']=min((p['start'] for p in data['programmes']),default=0)
    data['guideEnd']=max((p['stop'] for p in data['programmes']),default=0)
    data['descriptionCount']=sum(bool(p['description']) for p in data['programmes'])
