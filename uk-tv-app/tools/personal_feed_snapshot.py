import json, urllib.request, xml.etree.ElementTree as ET, concurrent.futures, pathlib, datetime, time
import os
ROOT=pathlib.Path(os.environ.get("PERSONAL_FEED_DIR", ".")); ROOT.mkdir(parents=True, exist_ok=True)
SOURCES={
 'business':('BBC Business','https://feeds.bbci.co.uk/news/business/rss.xml'),
 'world':('BBC World','https://feeds.bbci.co.uk/news/world/rss.xml'),
 'spain':('El País España','https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/espana/portada'),
 'space':('NASA','https://www.nasa.gov/feed/'),
 'classics':('BBC Entertainment','https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml'),
 'practical':('BBC Science & Environment','https://feeds.bbci.co.uk/news/science_and_environment/rss.xml'),
 'technology':('BBC Technology','https://feeds.bbci.co.uk/news/technology/rss.xml'),
 'fx':('ECB','https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml'),
 'kp':('NOAA','https://services.swpc.noaa.gov/json/planetary_k_index_1m.json')}
def fetch(pair):
 key,(name,url)=pair
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'UKTelevision-MyChannel/1.0'})
  raw=urllib.request.urlopen(req,timeout=18).read(2000000)
  result={'source':name,'url':url,'fetchedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'ok'}
  if key=='kp':result['reading']=json.loads(raw)[-1]
  elif key=='fx':
   root=ET.fromstring(raw);result['rates']={e.attrib['currency']:float(e.attrib['rate']) for e in root.iter() if 'currency' in e.attrib};result['date']=next(e.attrib['time'] for e in root.iter() if 'time' in e.attrib)
  else:
   root=ET.fromstring(raw);result['items']=[{'title':i.findtext('title',''),'url':i.findtext('link',''),'date':i.findtext('pubDate','')} for i in root.findall('.//item')[:5]]
   if not result['items']:raise ValueError('Empty feed')
  return key,result
 except Exception as e:return key,{'source':name,'url':url,'status':'unavailable','error':type(e).__name__}
def collect(previous=None):
 old=dict(previous or {})
 with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
  for k,v in pool.map(fetch,SOURCES.items()):
   if v['status']=='unavailable' and k in old:old[k]=dict(old[k],status='stale')
   else:old[k]=v
 return old

def refresh():
 old=json.loads((ROOT/'feeds.json').read_text()) if (ROOT/'feeds.json').exists() else {}
 result=collect(old)
 temp=ROOT/'feeds.tmp';temp.write_text(json.dumps(result));temp.replace(ROOT/'feeds.json')
 print({k:v['status'] for k,v in result.items()},flush=True)
if __name__=='__main__':
 import sys
 while True:
  refresh()
  if '--loop' not in sys.argv:break
  time.sleep(300)
