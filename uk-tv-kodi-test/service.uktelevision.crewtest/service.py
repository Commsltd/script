# SPDX-License-Identifier: GPL-3.0-only
# Uses the separately installed, unmodified The Crew add-on in Kodi on this device.
import hmac, json, os, sys, threading, time, urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
import xbmc, xbmcaddon, xbmcvfs
PROFILE=xbmcvfs.translatePath('special://profile/addon_data/service.uktelevision.crewtest/')
PROVISION_PATH=os.path.join(PROFILE,'token')
TOKEN_PATH='/data/user/0/org.xbmc.kodi/files/uktv-test-token'
if os.path.exists(PROVISION_PATH):
    provisioned=open(PROVISION_PATH).read().strip()
    if len(provisioned)<32:raise RuntimeError('Invalid local provisioning')
    fd=os.open(TOKEN_PATH,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as handle:handle.write(provisioned)
    os.chmod(TOKEN_PATH,0o600);os.unlink(PROVISION_PATH)
if not os.path.exists(TOKEN_PATH):
    raise RuntimeError('UK TV test bridge requires local provisioning')
TOKEN=open(TOKEN_PATH).read().strip()
if len(TOKEN)<32: raise RuntimeError('Invalid local provisioning')
for addon in ['script.module.thecrew','script.module.requests','script.module.beautifulsoup4','script.module.resolveurl','script.module.six','script.module.simplejson']:
    root=xbmcaddon.Addon(addon).getAddonInfo('path')
    lib=os.path.join(root,'lib')
    if lib not in sys.path:sys.path.insert(0,lib)
from catalogue import CrewCatalogue
catalogue_reader=CrewCatalogue(xbmc.executeJSONRPC)
state={'status':'idle','items':[],'engine':None,'error':'','job':'','last':time.monotonic()}
lock=threading.Lock()
def search(body):
    try:
        from resources.lib.modules import sources
        # Bound parallel lookups on this 1 GB Firestick; provider code is unchanged.
        sources._SCRAPER_CONCURRENCY=4
        engine=sources.Sources()
        rows=engine.getSources(body['title'],str(body['year']),body['imdb'],tmdb=str(body.get('tmdb','')),season=str(body.get('season','')),episode=str(body.get('episode','')),tvshowtitle=body.get('show',''),timeout=20,show_dialog=False) or []
        # Free source test only. Do not prompt for accounts or start Kodi playback.
        raw_count=len(rows)
        rows=[r for r in rows if isinstance(r,dict) and not r.get('debrid') and not r.get('debridonly')][:100]
        with lock:state.update(status='ready',items=rows,engine=engine,error='',rawCount=raw_count,providerCount=len(engine.sourceDict),resolverCount=len(engine.hostDict))
    except Exception as e:
        with lock:state.update(status='error',error=type(e).__name__+': '+str(e)[:200])
def catalogue(body):
    try:
        page=catalogue_reader.directory(body)
        with lock:state.update(status='catalogue-ready',catalogue=page,error='')
    except Exception as e:
        with lock:state.update(status='error',error=type(e).__name__+': '+str(e)[:200])
def resolve(index):
    try:
        value=state['engine'].sourcesResolve(state['items'][index],info=False)
        if not value:raise RuntimeError('The source did not resolve to a playable address')
        base=value.split('|')[0]
        u=urllib.parse.urlparse(base)
        if u.scheme not in ('https','http') or not u.hostname or u.username:raise RuntimeError('Unsupported source address')
        with lock:state.update(status='resolved',resolved=value)
    except Exception as e:
        with lock:state.update(status='error',error=type(e).__name__+': '+str(e)[:200])
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,code,data):
        raw=json.dumps(data).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    def do_POST(self):
        if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN):return self.reply(401,{'error':'Unauthorised'})
        state['last']=time.monotonic()
        try:
            length=int(self.headers.get('Content-Length',0))
            if not 0<=length<=4096:raise ValueError('Request too large')
            body=json.loads(self.rfile.read(length) or b'{}')
            if self.path=='/status':
                with lock:
                    result={k:state[k] for k in ['status','error','job']}
                    result['capabilities']=['catalogue-v1']
                    if state['status']=='catalogue-ready':result['catalogue']=state.get('catalogue',{})
                    result.update(kodi=xbmc.getInfoLabel('System.BuildVersion'),crew=xbmcaddon.Addon('plugin.video.thecrew').getAddonInfo('version'),module=xbmcaddon.Addon('script.module.thecrew').getAddonInfo('version'))
                    result.update({k:state.get(k,0) for k in ['rawCount','providerCount','resolverCount']})
                    result['items']=[{'index':i,'provider':r.get('provider',''),'quality':r.get('quality',''),'host':r.get('source','')} for i,r in enumerate(state['items'])]
                    if state['status']=='resolved':result['url']=state['resolved']
                return self.reply(200,result)
            if self.path=='/quit':
                self.reply(200,{'status':'stopping'});xbmc.executebuiltin('Quit');return
            with lock:
                if state['status'] in ('searching','resolving','cataloguing'):return self.reply(409,{'error':'A source check is already running'})
                if self.path=='/catalogue':
                    state.update(status='cataloguing',error='',catalogue={},job=str(time.time_ns()))
                    threading.Thread(target=catalogue,args=(body,),daemon=True).start()
                elif self.path=='/search':
                    if not isinstance(body.get('title'),str) or not 1<=len(body['title'])<=160:raise ValueError('Invalid title')
                    if not str(body.get('imdb','')).startswith('tt') or not str(body['imdb'])[2:].isdigit():raise ValueError('Invalid title identity')
                    if not 1880<=int(body['year'])<=2200:raise ValueError('Invalid year')
                    state.update(status='searching',error='',items=[],job=str(time.time_ns()))
                    threading.Thread(target=search,args=(body,),daemon=True).start()
                elif self.path=='/resolve':
                    index=int(body['index'])
                    if not 0<=index<len(state['items']):raise ValueError('Invalid source')
                    state.update(status='resolving',error='')
                    threading.Thread(target=resolve,args=(index,),daemon=True).start()
                else:return self.reply(404,{'error':'Unknown operation'})
            self.reply(202,{'status':state['status']})
        except Exception as e:self.reply(400,{'error':str(e)[:160]})
server=HTTPServer(('127.0.0.1',18765),Handler);server.timeout=1
monitor=xbmc.Monitor()
while not monitor.abortRequested():
    server.handle_request()
    if time.monotonic()-state['last']>600:
        xbmc.executebuiltin('Quit');break
server.server_close()
