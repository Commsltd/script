window.connectedLinear=true;
/* Live defaults for the local browser prototype; one decoder at a time. */
let defaults={},liveData={},activePreset='',hls=null,attempt=0,routes=[],watchdog=null,generation=0;
const player=document.createElement('video');player.id='livePlayer';player.controls=true;player.autoplay=true;player.muted=true;player.playsInline=true;
document.getElementById('picture').prepend(player);
const status=document.querySelector('.placeholder');status.innerHTML='<button id="enableSound">▶ Start with sound</button><small id="playStatus">Connecting default feeds…</small>';
document.getElementById('enableSound').onclick=()=>{player.muted=false;player.play().catch(()=>{});};
function showStatus(t){document.getElementById('playStatus').textContent=t;}
function stopVideo(){generation++;clearTimeout(watchdog);if(hls){hls.destroy();hls=null;}player.removeAttribute('src');player.load();}
function tryRoute(){
 stopVideo();const token=generation;
 if(attempt>=routes.length){showStatus('No default video available on this connection. Retrying in 60 seconds.');watchdog=setTimeout(()=>{attempt=0;tryRoute()},60000);return;}
 const r=routes[attempt++];document.getElementById('videoTitle').textContent=r.name;document.getElementById('videoDescription').textContent='Default source · alternatives tried automatically';showStatus('Connecting '+r.name+'…');
 const fail=()=>{if(token===generation)tryRoute()};watchdog=setTimeout(fail,16000);
 player.onplaying=()=>{if(token!==generation)return;clearTimeout(watchdog);showStatus(player.muted?'Playing · select Start with sound to unmute':'Playing with sound');};
 player.onwaiting=()=>{clearTimeout(watchdog);watchdog=setTimeout(fail,16000)};player.onerror=fail;
 if(player.canPlayType('application/vnd.apple.mpegurl')){player.src=r.url;player.play().catch(()=>showStatus('Press Play to start this default feed'));}
 else if(window.Hls&&Hls.isSupported()){hls=new Hls({maxBufferLength:15,maxMaxBufferLength:30,backBufferLength:0,capLevelToPlayerSize:true});hls.on(Hls.Events.ERROR,(_,e)=>{if(e.fatal)fail()});hls.loadSource(r.url);hls.attachMedia(player);hls.on(Hls.Events.MANIFEST_PARSED,()=>player.play().catch(()=>showStatus('Press Play to start this default feed')));}
 else{clearTimeout(watchdog);showStatus('This browser cannot decode HLS. Default source is assigned.');}
}
function dataCards(){
 const config=defaults[state.preset];if(!config)return;
 const cards=config.data.map(key=>{const d=liveData[key];if(!d)return block(key.toUpperCase(),'CONNECTING','<p class="feed-note">Loading the default source.</p>');
 const badge=d.status==='ok'?'UPDATED':d.status.toUpperCase();let body='';
 if(d.rates){body=`<div class="metric"><span>£1 → EUR</span><b>€${(1/d.rates.GBP).toFixed(4)}</b></div><div class="metric"><span>£1 → USD</span><b>$${(d.rates.USD/d.rates.GBP).toFixed(4)}</b></div><p class="feed-note">Daily reference · ${esc(d.date)} · not live market prices</p>`;}
 else if(d.reading){body=`<p class="headline">Planetary Kp: ${esc(d.reading.kp_index??d.reading.estimated_kp??'Unavailable')}</p><p class="feed-note">Observation: ${esc(d.reading.time_tag||'')}</p>`;}
 else if(d.items){body=d.items.slice(0,3).map(i=>`<p class="headline"><a href="${esc(/^https?:\/\//.test(i.url)?i.url:'#')}" target="_blank" rel="noopener">${esc(i.title)}</a></p><p class="source-line">${esc(i.date)}</p>`).join('');}
 else body='<p class="feed-note">Source temporarily unavailable. Retrying automatically.</p>';
 return block(d.source,badge,body+`<p class="source-line">${d.fetchedAt?'Fetched '+esc(new Date(d.fetchedAt).toLocaleTimeString()):''}</p>`);
 }).join('');document.getElementById('feedContent').innerHTML=cards;
 document.getElementById('panelTitle').textContent='Latest from your default feeds';
}
const designRender=render;
render=function(){designRender();dataCards();if(!defaults[state.preset])return;
 document.getElementById('sourceType').textContent='STREAMING DEFAULT';
 document.getElementById('programmeStrip').innerHTML=defaults[state.preset].video.map(c=>`<div class="strip-item"><small>DEFAULT / ALTERNATIVE</small><strong>${esc(c.name)}</strong><span>${c.urls.length} stream route(s)</span></div>`).join('');
 if(state.guide){stopVideo();activePreset='';return;}
 if(activePreset!==state.preset){activePreset=state.preset;routes=defaults[state.preset].video.flatMap(c=>c.urls.map(url=>({name:c.name,url})));attempt=0;tryRoute();}
};
async function refreshData(){try{const r=await fetch('feeds.json',{cache:'no-store'});if(!r.ok)throw Error();liveData=await r.json();dataCards();}catch{showStatus('Data refresh unavailable; retaining previous readings');}}
fetch('default-feeds.json',{cache:'no-store'}).then(r=>r.json()).then(d=>{defaults=d;render();refreshData()}).catch(()=>showStatus('Default configuration unavailable'));
setInterval(refreshData,300000);
document.addEventListener('visibilitychange',()=>{if(document.hidden){stopVideo();activePreset='';}else render();});
window.addEventListener('pagehide',stopVideo);
document.querySelector('.design-label span:last-child').textContent='Connected browser prototype · not installed on Firestick';

// Inspect connected sources without falling back to the obsolete design-only setup sheet.
$('sourceDetails').onclick=()=>panel('Connected defaults',Object.entries(defaults).map(([k,v])=>'<h3>'+esc(presets[k].name)+'</h3><p>'+v.video.map(c=>esc(c.name)).join(' → ')+'</p><p class="feed-note">'+v.data.map(id=>esc(liveData[id]?.source||id)).join(' + ')+'</p>').join('')+'<p class="notice">Feeds refresh every five minutes while the local collector is running. Video routes are tried automatically. This browser version is not installed on Firestick.</p>','connected');
$('chooseContent').onclick=()=>{attempt=attempt%Math.max(1,routes.length);tryRoute();};
$('feedSettings').onclick=$('sourceDetails').onclick;
player.onended=()=>{if(state.auto.enabled)advanceAuto()};
player.onvolumechange=()=>{const b=$('enableSound');b.textContent=player.muted?'▶ Start with sound':'✓ Sound on';showStatus(player.muted?'Playing muted':'Sound enabled');};
