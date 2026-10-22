"""Optional guide sources, with explicit service identities and provider IDs.

Primary schedules win. Never guess a region, timeshift or FAST distribution
from a similar display name. A failed optional feed cannot remove the primary.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import gzip
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

GUIDE_URLS = {
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

def download(name, output):
    path = output / (name + '.xml.gz')
    try:
        with urlopen(Request(GUIDE_URLS[name], headers={'User-Agent':'Mozilla/5.0 (compatible; UKTelevision/0.7.1)'}), timeout=45) as response:
            data = response.read(20 * 1024 * 1024 + 1)
        if len(data) > 20 * 1024 * 1024:
            raise ValueError('Download exceeds size limit')
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
