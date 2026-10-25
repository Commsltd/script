/* TV presentation only. Tubi owns playback, advertising and consent. */
(() => {
  if (window.ukTvTubi) return;
  const visible = e => !!e && e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().height > 0 && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
  const video = () => document.querySelector('video[data-id="videoPlayerComponent"]');
  const stage = () => video()?.closest('section');
  const playButton = () => stage()?.querySelector('button[aria-label$="keyboard shortcut k"]');
  const notice = () => [...document.querySelectorAll('[role="dialog"],[aria-modal="true"],#onetrust-banner-sdk,#onetrust-pc-sdk')].find(e => visible(e) && e.innerText.trim());
  const accessibleLabel = e => (e.innerText || e.getAttribute('aria-label') || (e.getAttribute('aria-labelledby')||'').split(/\s+/).map(id=>document.getElementById(id)?.innerText||'').join(' ') || e.labels?.[0]?.innerText || e.value || '').trim();
  const buttons = d => [...d.querySelectorAll('button,[role="button"],input[type="button"],input[type="submit"],input[type="checkbox"],input[type="radio"]')]
    .filter(e => (visible(e)||[...(e.labels||[])].some(visible)) && !e.disabled)
    .map(e => ({element:e,label:(['checkbox','radio'].includes(e.type)?(e.checked?'✓ ':'○ '):'')+accessibleLabel(e)})).filter(e => e.label.trim());
  let started = false, changingPlayback=false;
  const revealControls = () => {
    // The mouse handler belongs to the player's overlay, not the outer section.
    // Dispatching to that real element lets Tubi remount its own transport buttons.
    const overlay=[...(stage()?.querySelectorAll('div')||[])].find(e=>Object.keys(e).some(k=>k.startsWith('__reactProps')&&typeof e[k]?.onMouseMove==='function'&&typeof e[k]?.onClick==='function'));
    overlay?.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,clientX:100,clientY:100}));
  };
  const style = document.createElement('style');
  style.id = 'uk-tv-tubi-style';
  style.textContent = `
    html,body { overflow:hidden!important; background:#000!important; }
    [data-uktv-path] { transform:none!important; filter:none!important; contain:none!important; overflow:visible!important; clip-path:none!important; }
    [data-uktv-desktop-button] { opacity:0!important; pointer-events:none!important; }
    [data-uktv-offstage] { opacity:0!important; pointer-events:none!important; }
    [data-uktv-tubi-stage] { position:fixed!important; top:0!important; left:0!important; width:100vw!important; height:100vh!important; max-width:none!important; max-height:none!important; margin:0!important; padding:0!important; z-index:100000!important; background:#000!important; }
    [data-uktv-tubi-stage]>div,[data-uktv-tubi-stage] [data-id="hls"] { width:100%!important; height:100%!important; }
    [data-uktv-tubi-stage] video { width:100%!important; height:100%!important; object-fit:contain!important; }
    [data-uktv-video-path] { width:100%!important; height:100%!important; }
  `;
  (document.head || document.documentElement).appendChild(style);
  const mark=(e,name)=>{if(!e.hasAttribute(name))e.setAttribute(name,'');};
  const fit = () => {
    const s = stage(), v = video();
    if (!s || !v) return false;
    mark(s,'data-uktv-tubi-stage');
    if(!style.isConnected)(document.head||document.documentElement).appendChild(style);
    for(let e=s.parentElement;e;e=e.parentElement){
      mark(e,'data-uktv-path');
      for(const child of e.children)if(child!==s&&!child.contains(s)&&!['SCRIPT','STYLE','LINK'].includes(child.tagName))mark(child,'data-uktv-offstage');
    }
    for(let e=v.parentElement;e&&e!==s;e=e.parentElement)mark(e,'data-uktv-video-path');
    // UK TV supplies playback controls and local lists; the optional desktop signup CTA
    // is not a playable action. Advertising and consent elements are left intact.
    for(const b of s.querySelectorAll('button'))if(/^Sign Up to Save Progress/i.test(b.innerText.trim()))mark(b,'data-uktv-desktop-button');
    if(window.scrollX || window.scrollY)window.scrollTo(0,0);
    return s.getBoundingClientRect().width>=innerWidth-2 && s.getBoundingClientRect().height>=innerHeight-2;
  };
  const snapshot = () => {
    const d=notice();
    if(d) return {notice:{text:d.innerText.trim(),buttons:buttons(d).map(b=>b.label)},ready:false};
    const ready=fit(), v=video();
    // Keep the provider's transport mounted while UK TV supplies the visible UI.
    // Otherwise its fading animation can remove the play button between an OK
    // press and the provider handling it on a slow Firestick.
    if(ready)revealControls();
    const b=playButton();
    if(b){
      const fullscreen=stage()?.querySelector('button[aria-label$="keyboard shortcut f"]');
      let row=b.parentElement;
      while(row&&row!==stage()&&fullscreen&&!row.contains(fullscreen))row=row.parentElement;
      const transport=row?.parentElement;
      if(fullscreen&&row!==stage()&&transport&&transport!==stage()&&!transport.querySelector('video,[data-id="adComponent"],[data-id="captionsComponent"]'))mark(transport,'data-uktv-desktop-button');
    }
    // Tubi may autoplay before our first poll. Latch that too, otherwise a later
    // intentional pause would be mistaken for the first play request.
    if((v&&!v.paused)||(b&&/^Pause\b/i.test(b.getAttribute('aria-label')||'')))started=true;
    // Selecting Play in UK TV is the play request. Invoke Tubi's own button once,
    // never v.play(): the provider must remain in charge of ad/content transitions.
    if(ready&&!started&&b&&!b.disabled&&/^Play\b/i.test(b.getAttribute('aria-label')||'')&&Object.keys(b).some(k=>k.startsWith('__reactProps'))){started=true;b.click();}
    const s=stage();
    return {ready,paused:!v||v.paused,advert:!!s?.querySelector('[data-id="adComponent"] video,[data-id="adComponent"] iframe'),time:v?.currentTime||0,duration:Number.isFinite(v?.duration)?v.duration:0,
      playing:!!v&&!v.paused&&v.readyState>=2,width:v?.videoWidth||0,
      message: !ready ? (document.querySelector('[role="alert"]')?.innerText||'') : '',
      label:b?.getAttribute('aria-label')||'',stage:s?{width:s.getBoundingClientRect().width,height:s.getBoundingClientRect().height}:null};
  };
  window.ukTvTubi = {
    snapshot,
    toggle(){
      if(changingPlayback||notice())return false;
      changingPlayback=true;
      let tries=0;
      const attempt=()=>{
        if(notice()){changingPlayback=false;return;}
        const b=playButton();
        if(b&&!b.disabled){b.click();changingPlayback=false;return;}
        if(++tries>=20){changingPlayback=false;return;}
        revealControls();setTimeout(attempt,150);
      };
      attempt();return true;
    },
    chooseNotice(text,label,index){const d=notice();if(!d||d.innerText.trim()!==text)return false;const b=buttons(d)[index];if(!b||b.label!==label)return false;b.element.click();return true;},
    shortcut(key){
      if(notice()||!stage()||!['k','j','l','c'].includes(key))return false;
      // Dispatch the provider's documented shortcuts without moving Android focus
      // away from the native TV controls. Its own handler owns ad/seek restrictions.
      const code=key.toUpperCase().charCodeAt(0);
      for(const type of ['keydown','keyup']){
        const event=new KeyboardEvent(type,{key,code:'Key'+key.toUpperCase(),keyCode:code,which:code,bubbles:true,cancelable:true});
        document.dispatchEvent(event);
      }
      return true;
    }
  };
})()
