"""Verified English business services; preserve identities, alternatives and edition guides."""
import copy
import html
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

GROUP = '24 BUSINESS & FINANCE'

def seed():
    return json.loads(Path(__file__).with_name('business-channel-seed.json').read_text())

def fetch(url, data=None, limit=3*1024*1024):
    request = Request(url, data=data, headers={'User-Agent':'Mozilla/5.0 (compatible; UKTelevision)', 'Content-Type':'application/json'})
    with urlopen(request, timeout=15) as response:
        content = response.read(limit+1)
    if len(content)>limit: raise ValueError('Provider response too large')
    return content

def schwab_context(content):
    match = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', content.decode(), re.S)
    if not match: raise ValueError('Schwab player data missing')
    return json.loads(match[1])['props']['pageProps']['context']

def refreshed_inventory():
    specs=seed()
    for spec in specs:
        try:
            if spec['family']=='SchwabNetwork.us':
                url=schwab_context(fetch(spec['officialUrl']))['liveUrl']['value']
                parsed=urlsplit(url)
                if parsed.scheme!='https' or parsed.hostname!='content.uplynk.com' or not parsed.path.endswith('.m3u8') or parsed.username:
                    raise ValueError('Unexpected Schwab source')
                if not fetch(url).lstrip().startswith(b'#EXTM3U'): raise ValueError('Invalid live playlist')
                spec['routes'][0]['url']=url
            elif spec['family']=='FintechTV.us':
                page=fetch('https://terminal.fintech.tv/').decode()
                script=re.search(r'src="(/assets/index-[A-Za-z0-9_-]+\.js)"',page)
                if not script: raise ValueError('Player bundle missing')
                code=fetch('https://terminal.fintech.tv'+script[1],limit=8*1024*1024).decode()
                match=re.search(r'https://[A-Za-z0-9.-]+\.fintech\.tv/[^"\s]+/main\.m3u8',code)
                if not match: raise ValueError('Official live URL missing')
                spec['routes'][0]['url']=match[0]
            elif spec['family']=='Tastylive.us':
                body=json.dumps({'query':'query { siteSettings { livePlayerSettings { jwStreamId } } }'}).encode()
                stream=json.loads(fetch('https://www.tastylive.com/graphql',body))['data']['siteSettings']['livePlayerSettings']['jwStreamId']
                if not re.fullmatch(r'[A-Za-z0-9]{8}',stream): raise ValueError('Unexpected live identity')
                spec['routes'][0]['url']='https://cdn.jwplayer.com/live/broadcast/'+stream+'.m3u8'
        except (OSError,ValueError,KeyError,TypeError):
            # A provider outage does not erase the verified seed or its backups.
            continue
    return specs

def expand(channels, inventory=None, streams=()):
    from fast_channels import expand as add
    specs=copy.deepcopy(seed() if inventory is None else inventory)
    from stream_sources import valid
    for spec in specs:
        hosts={urlsplit(r['url']).hostname for r in spec['routes']}
        for item in streams:
            if item.get('channel')!=spec['family'] or item.get('feed')!=spec['variant'] or not valid(item): continue
            parsed=urlsplit(item['url'])
            if parsed.scheme!='https' or parsed.hostname not in hosts: continue
            if any(r['url']==item['url'] for r in spec['routes']): continue
            route=copy.deepcopy(spec['routes'][0]);route['url']=item['url'];route['label']=spec['name']+' · refreshed provider route'
            route['headers']={k:v for k,v in [('User-Agent',item.get('user_agent')),('Referer',item.get('referrer'))] if v}
            spec['routes'].append(route)
    rows=add(channels,specs)
    metadata={s['id']:s for s in specs}
    for row in rows:
        if row['id'] in metadata: row['officialUrl']=metadata[row['id']].get('officialUrl','')
    return rows

def add_guides(data,guides,now_ms=None):
    from fast_channels import add_guides as add
    add(data,guides,seed(),now_ms)

def schwab_xmltv(content, now=None):
    """Only the broadcaster's current scheduled show, never an invented all-day block."""
    import xml.etree.ElementTree as ET
    now=now or datetime.now(timezone.utc)
    show=schwab_context(content)['currentShow']['value']
    schedule=show['scheduleTime']
    start_text,zone=schedule['startTime'].rsplit(' ',1)
    end_text,end_zone=schedule['endTime'].rsplit(' ',1)
    if zone!=end_zone: raise ValueError('Inconsistent schedule timezone')
    tz=ZoneInfo(zone);local=now.astimezone(tz)
    start_clock=datetime.strptime(start_text,'%I:%M %p').time()
    end_clock=datetime.strptime(end_text,'%I:%M %p').time()
    root=ET.Element('tv');ET.SubElement(ET.SubElement(root,'channel',id='SchwabNetwork.official'),'display-name').text='Schwab Network'
    for offset in (-1,0):
        date=local.date()+timedelta(days=offset)
        start=datetime.combine(date,start_clock,tz);end=datetime.combine(date,end_clock,tz)
        if end<=start:end+=timedelta(days=1)
        if not start<=local<end:continue
        programme=ET.SubElement(root,'programme',channel='SchwabNetwork.official',start=start.strftime('%Y%m%d%H%M%S %z'),stop=end.strftime('%Y%m%d%H%M%S %z'))
        ET.SubElement(programme,'title').text=show['name']
        ET.SubElement(programme,'desc').text=html.unescape(re.sub('<[^>]*>','',show.get('description','')))
        break
    return ET.tostring(root,encoding='utf-8')
