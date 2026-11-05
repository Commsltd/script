import json,pathlib
p=pathlib.Path('/private/tmp/uktv-release-099/uk-tv-app/tools')
a=json.loads((p/'stream-source-seed.json').read_text());b=json.loads((p/'spain-channel-seed.json').read_text());f=json.loads((p/'fast-channel-seed.json').read_text())
def urls(name):return [x['url'] for x in a if x['title']==name][:3]
def routes(name,data):return [r['url'] for x in data if x['name']==name for r in x['routes']][:3]
sen=['https://linear-1224.frequency.stream/dist/lg-uk/1224/hls/master/playlist.m3u8','https://d111bsx9oooqoh.cloudfront.net/playlist.m3u8']
def channel(name,u):return {'name':name,'urls':u}
bloom=channel('Bloomberg Europe',urls('Bloomberg TV Europe'))
earth=channel('Adventure Earth',urls('Adventure Earth'))
d={
'business':{'video':[bloom,channel('Bloomberg Originals',routes('Bloomberg Originals',f))],'data':['fx','business']},
'world':{'video':[channel('Space Live / Sen — Earth view',sen),earth],'data':['world']},
'spain':{'video':[channel('Canal Sur Andalucía',routes('Canal Sur Andalucía',b)),channel('Canal Sur Más Noticias',routes('Canal Sur Más Noticias',b))],'data':['spain']},
'space':{'video':[channel('Space Live / Sen',sen)],'data':['kp','space']},
'classics':{'video':[channel('Mr Bean — Live Action',urls('Mr Bean Live Action')),channel('Mr Bean — Animated',urls('Mr Bean Animated'))],'data':['classics']},
'practical':{'video':[channel('Top Gear',urls('Top Gear')[-1:]+['https://d1daq1khzqxpku.cloudfront.net/playlist.m3u8']),earth],'data':['practical']},
 'technology':{'video':[channel('Bloomberg Originals — business & technology',routes('Bloomberg Originals',f)),bloom],'data':['technology']}}
pathlib.Path('/private/tmp/uktv-personal-design/default-feeds.json').write_text(json.dumps(d,indent=2))
