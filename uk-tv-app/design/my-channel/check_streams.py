import json,concurrent.futures,subprocess,pathlib
p=pathlib.Path(__file__).parent;d=json.loads((p/'default-feeds.json').read_text())
u=list(dict.fromkeys(u for v in d.values() for c in v['video'] for u in c['urls']))
def check(url):
 try:
  r=subprocess.run(['/opt/homebrew/bin/ffprobe','-v','error','-rw_timeout','10000000','-analyzeduration','2000000','-probesize','1000000','-show_entries','stream=codec_type,codec_name','-of','json',url],capture_output=True,text=True,timeout=18)
  streams=json.loads(r.stdout or '{}').get('streams',[])
  return url,{'video':any(s.get('codec_type')=='video' for s in streams),'audio':any(s.get('codec_type')=='audio' for s in streams)}
 except Exception:return url,{'video':False,'audio':False}
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:r=dict(pool.map(check,u))
(p/'stream-checks.json').write_text(json.dumps(r,indent=2));print(json.dumps(r,indent=2))
# Prefer independently probed video routes without discarding alternatives.
for v in d.values():
 for c in v['video']:c['urls'].sort(key=lambda u:not r[u]['video'])
 v['video'].sort(key=lambda c:not any(r[u]['video'] for u in c['urls']))
(p/'default-feeds.json').write_text(json.dumps(d,indent=2))
