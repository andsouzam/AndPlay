/* EPlay Player 2.0 — controles, acessibilidade e UX sem substituir o motor existente */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const video = $('videoPlayer');
  const modal = $('videoModal');
  if (!video || !modal) return;
  const container = video.closest('.video-container');
  if (!container) return;
  let ui, seek, currentTime, duration, playBtn, center, menu, resume, hideTimer, toastTimer;
  const state = { speed: Number(localStorage.getItem('andplay_player_speed') || 1), volume: Number(localStorage.getItem('andplay_player_volume') || .85) };
  function fmt(sec) {
    if (!Number.isFinite(sec) || sec < 0) return '00:00';
    sec = Math.floor(sec);
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return h ? String(h).padStart(2,'0')+':'+String(m).padStart(2,'0')+':'+String(s).padStart(2,'0') : String(m).padStart(2,'0')+':'+String(s).padStart(2,'0');
  }
  function showToast(msg, ms=1300) {
    const el = $('eplayPlayerToast'); if (!el) return;
    el.textContent = msg; el.classList.add('show'); clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), ms);
  }
  function setRange() {
    if (!seek) return;
    const d = video.duration || 0, t = video.currentTime || 0;
    seek.max = d || 0; seek.value = Math.min(t, d || t);
    seek.style.setProperty('--pct', d ? ((t/d)*100)+'%' : '0%');
    currentTime.textContent = fmt(t); duration.textContent = fmt(d);
  }
  function setPlayIcon() {
    if (playBtn) playBtn.textContent = video.paused ? '▶' : '❚❚';
    if (playBtn) playBtn.setAttribute('aria-label', video.paused ? 'Reproduzir' : 'Pausar');
  }
  function flashCenter(icon) {
    if (!center) return;
    center.textContent = icon; center.classList.add('show');
    setTimeout(() => center.classList.remove('show'), 450);
  }
  function playPause() {
    if (video.paused) video.play().catch(() => showToast('Clique novamente para iniciar'));
    else video.pause();
    flashCenter(video.paused ? '▶' : '❚❚');
  }
  function seekBy(delta) {
    if (!Number.isFinite(video.duration)) return;
    video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + delta));
    showToast((delta > 0 ? '⏩ +' : '⏪ ') + Math.abs(delta) + 's');
    flashCenter(delta > 0 ? '⏩' : '⏪'); reveal();
  }
  function reveal() {
    if (!ui) return;
    ui.classList.remove('idle'); clearTimeout(hideTimer);
    if (!video.paused) hideTimer = setTimeout(() => { if (!menu.classList.contains('open')) ui.classList.add('idle'); }, 3200);
  }
  function toggleMenu() { menu.classList.toggle('open'); reveal(); }
  function setVolume(v) {
    state.volume = Math.max(0, Math.min(1, v)); video.volume = state.volume; video.muted = state.volume === 0;
    localStorage.setItem('andplay_player_volume', String(state.volume)); updateVolume();
  }
  function updateVolume() {
    const b = $('eplayVolumeBtn'), range = $('eplayVolume');
    if (range) range.value = Math.round(video.muted ? 0 : video.volume * 100);
    if (b) b.textContent = video.muted || video.volume === 0 ? '🔇' : video.volume < .5 ? '🔉' : '🔊';
  }
  function toggleMute() { video.muted = !video.muted; if (!video.muted && video.volume === 0) video.volume = state.volume || .85; updateVolume(); }
  async function fullscreen() {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (container.requestFullscreen) await container.requestFullscreen();
      else modal.classList.toggle('eplay-fullscreen');
    } catch { modal.classList.toggle('eplay-fullscreen'); }
  }
  async function pip() {
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else if (document.pictureInPictureEnabled && video.requestPictureInPicture) await video.requestPictureInPicture();
      else showToast('Picture-in-Picture não disponível neste navegador');
    } catch { showToast('Picture-in-Picture não disponível'); }
  }
  function applySpeed(value) {
    const v = Number(value); if (!Number.isFinite(v)) return;
    video.playbackRate = v; localStorage.setItem('andplay_player_speed', String(v));
    document.querySelectorAll('[data-speed]').forEach(b => b.classList.toggle('active', Number(b.dataset.speed) === v));
    const label = $('eplaySpeedLabel'); if (label) label.textContent = v + 'x';
  }
  function openSubtitles() { const b=$('toggleSubPanelBtn'); if(b) b.click(); else showToast('Opções de legenda indisponíveis'); }
  function openInfo() { const b=$('toggleMovieInfoBtn'); if(b) b.click(); else $('movieInfoSidebar')?.classList.toggle('collapsed'); }
  function build() {
    video.controls = false; video.setAttribute('playsinline','');
    const html = [
      '<div class="eplay-player-ui" id="eplayPlayerUi" aria-label="Controles do player">',
      '<div class="eplay-player-top"><div><div class="eplay-player-title" id="eplayPlayerTitle">EPlay</div><div class="eplay-player-meta" id="eplayPlayerMeta">Preparando reprodução...</div></div><div><span class="eplay-live-badge" id="eplayLiveBadge">● AO VIVO</span></div></div>',
      '<div class="eplay-player-center"><button class="eplay-center-play" id="eplayCenterPlay" aria-label="Reproduzir">▶</button></div>',
      '<div class="eplay-player-bottom"><div class="eplay-seek-wrap"><span class="eplay-time" id="eplayCurrentTime">00:00</span><input id="eplaySeek" class="eplay-range" type="range" min="0" max="0" value="0" step="0.1" aria-label="Posição da reprodução"><span class="eplay-time" id="eplayDuration">00:00</span></div>',
      '<div class="eplay-controls"><button class="eplay-control small eplay-series-nav" id="eplayPrevEpisode" aria-label="Episódio anterior" style="display:none">‹E</button><button class="eplay-control" id="eplayPlay" aria-label="Reproduzir">▶</button><button class="eplay-control small" id="eplayBack10" aria-label="Voltar 10 segundos">↶10</button><button class="eplay-control small" id="eplayForward10" aria-label="Avançar 10 segundos">10↷</button><button class="eplay-control small eplay-series-nav" id="eplayNextEpisode" aria-label="Próximo episódio" style="display:none">E›</button>',
      '<div class="eplay-volume"><button class="eplay-control" id="eplayVolumeBtn" aria-label="Volume">🔊</button><input id="eplayVolume" class="eplay-range" type="range" min="0" max="100" value="85" aria-label="Volume"></div><div class="eplay-spacer"></div>',
      '<button class="eplay-control small" id="eplaySubtitle" aria-label="Legendas">CC</button><button class="eplay-control small" id="eplayInfo" aria-label="Ficha técnica">ⓘ</button><button class="eplay-control small" id="eplayPip" aria-label="Picture-in-Picture">▣</button><button class="eplay-control" id="eplaySettings" aria-label="Configurações">⚙</button><button class="eplay-control" id="eplayFullscreen" aria-label="Tela cheia">⛶</button></div></div>',
      '<div class="eplay-menu" id="eplayMenu"><h4>Configurações de reprodução</h4><div class="eplay-menu-row"><span>Velocidade</span><strong id="eplaySpeedLabel">1x</strong></div>',
      '<div class="eplay-speed-list"><button data-speed="0.75">0.75x</button><button data-speed="1">1x</button><button data-speed="1.25">1.25x</button><button data-speed="1.5">1.5x</button><button data-speed="1.75">1.75x</button><button data-speed="2">2x</button></div>',
      '<button id="eplayInfoMenu">ⓘ Ficha técnica</button><button id="eplaySubMenu">💬 Legendas e sincronização</button><button id="eplayPipMenu">▣ Picture-in-Picture</button><button id="eplayFsMenu">⛶ Tela cheia</button></div>',
      '<div class="eplay-toast" id="eplayPlayerToast"></div>',
      '<div class="eplay-resume" id="eplayResume"><span id="eplayResumeText">Continuar reprodução?</span><button class="continue" id="eplayResumeContinue">Continuar</button><button class="restart" id="eplayResumeRestart">Do início</button></div>',
      '</div>'
    ].join('');
    container.insertAdjacentHTML('beforeend',html);
    ui=$('eplayPlayerUi'); seek=$('eplaySeek'); currentTime=$('eplayCurrentTime'); duration=$('eplayDuration'); playBtn=$('eplayPlay'); center=$('eplayCenterPlay'); menu=$('eplayMenu'); resume=$('eplayResume');
  }
  build();
  function syncPlaybackSurface() {
    const embedVisible = !!(window.getComputedStyle($('embedPlayer')).display !== 'none');
    const videoVisible = !!(window.getComputedStyle(video).display !== 'none');
    if (embedVisible || !videoVisible) {
      ui.classList.add('embed-active');
      video.controls = false;
    } else {
      ui.classList.remove('embed-active');
      video.controls = false;
    }
  }
  const sourceObserver = new MutationObserver(syncPlaybackSurface);
  sourceObserver.observe(video, { attributes: true, attributeFilter: ['style', 'src'] });
  sourceObserver.observe($('embedPlayer'), { attributes: true, attributeFilter: ['style', 'src'] });
  syncPlaybackSurface();
  const $on=(id,ev,fn)=>$(id)?.addEventListener(ev,fn);
  $on('eplayPlay','click',playPause); $on('eplayCenterPlay','click',playPause);
  $on('eplayBack10','click',()=>seekBy(-10)); $on('eplayForward10','click',()=>seekBy(10));
  $on('eplayPrevEpisode','click',()=>window.EPlaySeriesNavigation?.previous?.()); $on('eplayNextEpisode','click',()=>window.EPlaySeriesNavigation?.next?.());
  function syncSeriesNav(){const n=window.EPlaySeriesNavigation;const p=$('eplayPrevEpisode'),x=$('eplayNextEpisode');if(!n){if(p)p.style.display='none';if(x)x.style.display='none';return}const season=String(n.seasonNum||'');const ep=Number(n.episodeNum||0);if(p)p.style.display=ep>1?'inline-flex':'none';if(x)x.style.display='inline-flex';}
  window.addEventListener('eplay:series-context',syncSeriesNav); syncSeriesNav();
  $on('eplayVolumeBtn','click',toggleMute); $on('eplayVolume','input',e=>setVolume(Number(e.target.value)/100));
  $on('eplaySubtitle','click',openSubtitles); $on('eplayInfo','click',openInfo); $on('eplayPip','click',pip);
  $on('eplaySettings','click',toggleMenu); $on('eplayInfoMenu','click',openInfo); $on('eplaySubMenu','click',openSubtitles); $on('eplayPipMenu','click',pip); $on('eplayFsMenu','click',fullscreen); $on('eplayFullscreen','click',fullscreen);
  seek.addEventListener('input',()=>{if(Number.isFinite(video.duration))video.currentTime=Number(seek.value);setRange();reveal();});
  document.querySelectorAll('[data-speed]').forEach(b=>b.addEventListener('click',()=>applySpeed(b.dataset.speed)));
  container.addEventListener('mousemove',reveal); container.addEventListener('touchstart',reveal,{passive:true});
  video.addEventListener('play',()=>{setPlayIcon();reveal()}); video.addEventListener('pause',()=>{setPlayIcon();reveal()});
  video.addEventListener('timeupdate',setRange); video.addEventListener('durationchange',setRange); video.addEventListener('loadedmetadata',()=>{setRange();updateTitle()});
  video.addEventListener('volumechange',updateVolume); video.addEventListener('ratechange',()=>applySpeed(video.playbackRate));
  document.addEventListener('fullscreenchange',()=>{modal.classList.toggle('eplay-fullscreen',!!document.fullscreenElement);reveal()});
  window.addEventListener('keydown',e=>{
    if(modal.style.display==='none')return;
    const tag=(e.target.tagName||'').toLowerCase();if(['input','select','textarea'].includes(tag))return;
    if(e.key===' '||e.key.toLowerCase()==='k'){e.preventDefault();playPause()} else if(e.key==='ArrowLeft'){e.preventDefault();seekBy(-10)} else if(e.key==='ArrowRight'){e.preventDefault();seekBy(10)} else if(e.key==='ArrowUp'){e.preventDefault();setVolume(video.volume+.05)} else if(e.key==='ArrowDown'){e.preventDefault();setVolume(video.volume-.05)} else if(e.key.toLowerCase()==='m'){e.preventDefault();toggleMute()} else if(e.key.toLowerCase()==='f'){e.preventDefault();fullscreen()} else if(e.key.toLowerCase()==='p'){e.preventDefault();pip()} else if(e.key.toLowerCase()==='c'){e.preventDefault();openSubtitles()} else if(e.key==='Escape'){menu.classList.remove('open');reveal()} else reveal();
  });
  let sx=0,sy=0,st=0;
  container.addEventListener('touchstart',e=>{const t=e.changedTouches[0];sx=t.clientX;sy=t.clientY;st=Date.now()},{passive:true});
  container.addEventListener('touchend',e=>{const t=e.changedTouches[0],dx=t.clientX-sx,dy=t.clientY-sy;if(Math.abs(dx)>70&&Math.abs(dx)>Math.abs(dy)&&Date.now()-st<800)seekBy(dx>0?10:-10);else if(Math.abs(dx)<25&&Math.abs(dy)<25&&Date.now()-st<450)reveal()},{passive:true});
  video.volume=state.volume;applySpeed(state.speed);updateVolume();setPlayIcon();setRange();
  function updateTitle(){const title=$('modalTitle')?.textContent||'EPlay';const meta=$('modalFormat')?.textContent||'';$('eplayPlayerTitle').textContent=title;$('eplayPlayerMeta').textContent=meta;$('eplayLiveBadge').style.display=/ao vivo|live|canal/i.test(title+' '+meta)?'inline-block':'none'}
  const observer=new MutationObserver(updateTitle);observer.observe($('modalTitle'),{childList:true,characterData:true,subtree:true});observer.observe($('modalFormat'),{childList:true,characterData:true,subtree:true});updateTitle();
  window.EPlayPlayerUI={showToast,reveal,seekBy,playPause,fullscreen,pip};
})();