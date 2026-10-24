"""Optional guide sources, with explicit service identities and provider IDs.

Primary schedules win. Never guess a region, timeshift or FAST distribution
from a similar display name. A failed optional feed cannot remove the primary.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import gzip
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

GUIDE_URLS = {
    'spain_pluto': 'https://i.mjh.nz/PlutoTV/es.xml.gz',
    'spain_samsung': 'https://i.mjh.nz/SamsungTVPlus/es.xml.gz',
    'tdt_spain': 'https://www.tdtchannels.com/epg/TV.xml.gz',
    'samsung_us': 'https://i.mjh.nz/SamsungTVPlus/us.xml.gz',
    'news_cbn': 'https://cmsedit.cbn.com/app_feeds/cbnFamily/liveschedule.php?channel=newsChannel&numHours=168',
    **{name: 'https://epgshare01.online/epgshare01/epg_ripper_'+feed+'.xml.gz'
       for name,feed in [('news_in','IN1'),('news_in4','IN4'),('news_kr','KR1'),('news_sg','SG1'),('news_za','ZA1'),
                         ('news_ng','NG1'),('news_il','IL1'),('news_aje','ALJAZEERA1')]},
    **{name: 'https://epgshare01.online/epgshare01/epg_ripper_'+feed+'.xml.gz' for name,feed in [('rakuten','RAKUTEN1'),('au','AU1'),('ca','CA2'),('ie','IE1'),('nz','NZ1'),('us','US2')]},
    'jm': 'https://epgshare01.online/epgshare01/epg_ripper_JM1.xml.gz',
    'nz_mjh': 'https://i.mjh.nz/nz/epg.xml.gz',
    'pluto_us': 'https://i.mjh.nz/PlutoTV/us.xml.gz',
    'openepg': 'https://www.open-epg.com/files/unitedkingdom.xml.gz',
    'uk1': 'https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz',
    'epgpw': 'https://epg.pw/xmltv/epg_GB.xml.gz',
    'pluto': 'https://i.mjh.nz/PlutoTV/gb.xml.gz',
    'samsung': 'https://i.mjh.nz/SamsungTVPlus/gb.xml.gz',
}
OPEN_EPG_IDS = {
    'BBCThreeCBBC.uk@HD': 'BBCThreeCBBC.uk',
    'BBCFourCBeebies.uk@HD': 'BBCFourCBeebies.uk',
    'SkySportsF1.uk@SD': 'SkySportsF1.uk',
    'MUTV.uk@SD': 'MUTV.uk',
    'EmanChannel.uk@SD': 'EmanChannel.uk',
    'FaithUK.uk@SD': 'FaithUK.uk',
    'FaithWorldTV.uk@SD': 'FaithWorldTV.uk',
    'IqraBangla.uk@SD': 'IQRABangla.uk',
    'IqraTV.uk@SD': 'IQRATV.uk',
    'MTA1World.uk@SD': 'MTA1World.uk',
    'NoorTV.uk@SD': 'NoorTV.uk',
    'TakbeerTV.uk@SD': 'TakbeerTV.uk',
    'AkaalChannel.uk@SD': 'AkaalChannel.uk',
    'DeenTV.uk@SD': 'DeenTV.uk',
    'IonTV.uk@SD': 'IONTV.uk',
    'SangatTV.uk@SD': 'Sangat.uk',
    'TVOne.uk@SD': 'TVOne.uk',
    'UtsavBharat.uk@SD': 'UtsavBharat.uk',
    'UtsavPlus.uk@SD': 'UtsavPlus.uk',
    'IslamChannel.uk@SD': 'IslamChannel.uk',
    'KanshiTV.uk@SD': 'KanshiTV.uk',
    'LoveWorldUK.ng@SD': 'LoveWorld.uk',
    'SanskarUK.in@SD': 'Sanskar.uk',
}
# These streams explicitly identify the Samsung UK distribution. Do not reuse
# the guide for similarly named Australian, Rakuten or unspecified streams.
SAMSUNG_IDS = {
    'FIFAPlusWomen.uk@English': 'GB3800009F0',
    'FailArmy.us@UK': 'GBAJ40003642',
    'NatureTime.ca@UK': 'GB2100004H5',
    'SoReal.us@UK': 'GBBD1100009M9',
    'ThePetCollective.us@UK': 'GBAJ40003481',
    'WaterBear.ch@SD': 'GBBC300003UQ',
    'WorldPokerTour.us@UK': 'GBBD2900001VD',
    'XITEHits.nl@UK': 'GBBB3800005QO',
    'MostHaunted.uk@UK': 'GB4300003ZT',
    'MrBeanLiveAction.uk@SD': 'GB4000008O5',
}
EPGPW_IDS = {'PanjabTV.uk@SD': '418100'}

def parse_xml(xml):
    if b'<!ENTITY' in xml.upper():
        raise ValueError('XML entities are not accepted')
    root = ET.fromstring(xml)
    if root.tag != 'tv':
        raise ValueError('Expected an XMLTV tv root')
    return root

def load_guide(path):
    with gzip.open(path, 'rb') as stream:
        xml = stream.read(100 * 1024 * 1024 + 1)
    if len(xml) > 100 * 1024 * 1024:
        raise ValueError('Guide exceeds size limit')
    parse_xml(xml)
    return xml

def mappings(source, channels):
    if source == 'openepg': return OPEN_EPG_IDS
    if source == 'samsung': return SAMSUNG_IDS
    if source == 'epgpw': return EPGPW_IDS
    if source == 'rakuten':
        return {'RakutenTV'+service+'Movies.es@UK': 'UK:.'+guide+'.-.Rakuten.TV.be' for service,guide in [('Action','Action.Movies'),('Comedy','Comedy.Movies'),('Drama','Drama.Movies'),('Family','Family'),('Top','Top.Movies')]}
    if source in {'au','ca','ie','nz','nz_mjh','us','tego','jm'}:
        from international_guides import mappings as country_mappings
        return country_mappings(source, channels)
    if source not in {'pluto','pluto_us'}: return {}
    result = {}
    for cid, channel in channels.items():
        ids = set()
        for stream in channel['sources']:
            url = urlsplit(stream['url'])
            match = re.fullmatch(r'/plu-([0-9a-f]{24})\.m3u8', url.path)
            if url.scheme == 'https' and url.hostname == 'jmp2.uk' and match:
                ids.add(match[1])
        if len(ids) == 1:
            result[cid] = ids.pop()
    return result

def cbn_xmltv(data):
    """Translate the broadcaster's dated schedule; preserve its explicit offsets."""
    if b'<!ENTITY' in data.upper(): raise ValueError('XML entities are not accepted')
    root=ET.fromstring(data)
    namespace={'c':'http://www.orca.tv/RiGHTv/7.0/EPG'}
    out=ET.Element('tv')
    for channel in root.findall('.//c:channel',namespace):
        if channel.get('id')!='newsChannel': continue
        for item in channel.findall('c:program',namespace):
            if item.get('delete')=='true': continue
            duration=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?',item.get('duration',''))
            title=(item.findtext('c:title','',namespace) or '').strip()
            if not duration or not title: continue
            try: start=datetime.fromisoformat(item.get('time',''))
            except ValueError: continue
            if start.tzinfo is None: continue
            seconds=sum(int(v or 0)*n for v,n in zip(duration.groups(),[3600,60,1]))
            if seconds<=0 or seconds>86400: continue
            stop=start+timedelta(seconds=seconds)
            fmt=lambda value:value.astimezone(timezone.utc).strftime('%Y%m%d%H%M%S +0000')
            programme=ET.SubElement(out,'programme',channel='CBNNews.official',start=fmt(start),stop=fmt(stop))
            ET.SubElement(programme,'title').text=title
            description=(item.findtext('c:description','',namespace) or '').strip()
            if description: ET.SubElement(programme,'desc').text=description
    return ET.tostring(out,encoding='utf-8')

def download(name, output):
    path = output / (name + '.xml.gz')
    try:
        with urlopen(Request(GUIDE_URLS[name], headers={'User-Agent':'Mozilla/5.0 (compatible; UKTelevision/0.7.1)'}), timeout=45) as response:
            data = response.read(20 * 1024 * 1024 + 1)
        if len(data) > 20 * 1024 * 1024:
            raise ValueError('Download exceeds size limit')
        if name=='news_cbn': data=gzip.compress(cbn_xmltv(data))
        temporary = path.with_suffix('.tmp.gz')
        temporary.write_bytes(data)
        xml = load_guide(temporary)
        root = parse_xml(xml)
        if not root.findall('programme'): raise ValueError('Guide has no programmes')
        temporary.replace(path)
        return {'source': name, 'channels':len(root.findall('channel')), 'programmes':len(root.findall('programme')), 'ok':True}
    except Exception as error:
        path.unlink(missing_ok=True)
        path.with_suffix('.tmp.gz').unlink(missing_ok=True)
        return {'source':name,'ok':False,'error':str(error)}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(lambda name:download(name,args.output_dir), GUIDE_URLS))
    (args.output_dir / 'guide-sources.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results,indent=2))
    for result in results:
        if not result['ok']: print(f"::warning::{result['source']} guide unavailable; retaining other guide sources")

if __name__ == '__main__': main()
