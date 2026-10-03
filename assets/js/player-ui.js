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
  window.showPlayerToast = showToast;
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
    ui.classList.remove('idle');
    const subOverlay = $('eplaySubtitleOverlay');
    if (subOverlay) subOverlay.classList.remove('ui-idle');
    clearTimeout(hideTimer);
    if (!video.paused) hideTimer = setTimeout(() => {
      if (!menu.classList.contains('open')) {
        ui.classList.add('idle');
        const so = $('eplaySubtitleOverlay');
        if (so) so.classList.add('ui-idle');
      }
    }, 3200);
  }
  function toggleMenu() {
    const open = menu.classList.toggle('open');
    const settings = $('eplaySettings');
    if (settings) settings.setAttribute('aria-expanded', open ? 'true' : 'false');
    reveal();
  }
  function closeMenu() {
    if (menu) menu.classList.remove('open');
    const settings = $('eplaySettings');
    if (settings) settings.setAttribute('aria-expanded', 'false');
    const subPanel = $('subOptionsPanel');
    if (subPanel && subPanel.style.display !== 'none') {
      const closeBtn = $('closeSubPanelBtn');
      if (closeBtn) closeBtn.click();
      else subPanel.style.display = 'none';
    }
  }
  function setVolume(v) {
    state.volume = Math.max(0, Math.min(1, v));
    if (window.isHybridAudioActive && window.isHybridAudioActive()) {
      if (window.setHybridVolume) window.setHybridVolume(state.volume);
      localStorage.setItem('andplay_player_volume', String(state.volume));
      updateVolume();
      return;
    }
    video.volume = state.volume; video.muted = state.volume === 0;
    localStorage.setItem('andplay_player_volume', String(state.volume)); updateVolume();
  }
  function updateVolume() {
    const b = $('eplayVolumeBtn'), range = $('eplayVolume');
    const isHybrid = !!(window.isHybridAudioActive && window.isHybridAudioActive());
    const isMuted = isHybrid ? (window.isHybridMuted ? window.isHybridMuted() : false) : video.muted;
    const vol = isHybrid ? (window.getHybridVolume ? window.getHybridVolume() : state.volume) : video.volume;
    if (range) range.value = Math.round(isMuted ? 0 : vol * 100);
    if (b) b.textContent = isMuted || vol === 0 ? '🔇' : vol < .5 ? '🔉' : '🔊';
  }
  window.updatePlayerVolumeUI = updateVolume;
  function toggleMute() {
    if (window.isHybridAudioActive && window.isHybridAudioActive()) {
      if (window.toggleHybridMute) window.toggleHybridMute();
      updateVolume();
      return;
    }
    video.muted = !video.muted; if (!video.muted && video.volume === 0) video.volume = state.volume || .85; updateVolume();
  }
  function getFullscreenElement() {
    return (
      document.fullscreenElement ||
      document.webkitFullscreenElement ||
      document.mozFullScreenElement ||
      document.msFullscreenElement ||
      (video && video.webkitDisplayingFullscreen ? video : null)
    );
  }
  function isFullscreenActive() {
    return !!(
      getFullscreenElement() ||
      modal.classList.contains('eplay-fullscreen') ||
      container.classList.contains('eplay-fullscreen') ||
      document.body.classList.contains('eplay-fullscreen-active')
    );
  }
  function updateFullscreenIcons(active) {
    const fsBtn = $('eplayFullscreen'), fsMenu = $('eplayFsMenu');
    if (fsBtn) {
      fsBtn.textContent = active ? '🗗' : '⛶';
      fsBtn.setAttribute('aria-label', active ? 'Sair da tela cheia' : 'Tela cheia');
      fsBtn.title = active ? 'Sair da tela cheia' : 'Tela cheia';
    }
    if (fsMenu) fsMenu.textContent = active ? '🗗 Sair da tela cheia' : '⛶ Tela cheia';
  }
  async function exitFullscreenInternal() {
    try {
      if (document.exitFullscreen) await document.exitFullscreen();
      else if (document.webkitExitFullscreen) await document.webkitExitFullscreen();
      else if (document.mozCancelFullScreen) await document.mozCancelFullScreen();
      else if (document.msExitFullscreen) await document.msExitFullscreen();
    } catch (_) {}
    if (video && video.webkitDisplayingFullscreen && typeof video.webkitExitFullscreen === 'function') {
      try { video.webkitExitFullscreen(); } catch (_) {}
    }
    try {
      if (screen.orientation && typeof screen.orientation.unlock === 'function') {
        screen.orientation.unlock();
      }
    } catch (_) {}
    modal.classList.remove('eplay-fullscreen');
    container.classList.remove('eplay-fullscreen');
    document.body.classList.remove('eplay-fullscreen-active');
    updateFullscreenIcons(false);
    reveal();
  }
  async function enterFullscreenInternal() {
    let nativeSuccess = false;
    const reqFs = container.requestFullscreen ||
                  container.webkitRequestFullscreen ||
                  container.webkitRequestFullScreen ||
                  container.mozRequestFullScreen ||
                  container.msRequestFullscreen;
    if (reqFs) {
      try {
        await reqFs.call(container);
        nativeSuccess = true;
      } catch (_) {}
    }
    if (!nativeSuccess) {
      const modalReqFs = modal.requestFullscreen ||
                         modal.webkitRequestFullscreen ||
                         modal.webkitRequestFullScreen ||
                         modal.mozRequestFullScreen ||
                         modal.msRequestFullscreen;
      if (modalReqFs) {
        try {
          await modalReqFs.call(modal);
          nativeSuccess = true;
        } catch (_) {}
      }
    }
    // Suporte específico para iOS Safari (iPhone) onde apenas video.webkitEnterFullscreen é suportado
    if (!nativeSuccess && typeof video.webkitEnterFullscreen === 'function') {
      try {
        video.webkitEnterFullscreen();
        nativeSuccess = true;
      } catch (_) {}
    }
    modal.classList.add('eplay-fullscreen');
    container.classList.add('eplay-fullscreen');
    document.body.classList.add('eplay-fullscreen-active');
    updateFullscreenIcons(true);
    try {
      if (screen.orientation && typeof screen.orientation.lock === 'function') {
        await screen.orientation.lock('landscape').catch(() => {});
      }
    } catch (_) {}
    reveal();
  }
  async function fullscreen() {
    closeMenu();
    if (isFullscreenActive()) {
      await exitFullscreenInternal();
    } else {
      await enterFullscreenInternal();
    }
  }
  async function pip() {
    closeMenu();
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
  function openSubtitles() { closeMenu(); const b=$('toggleSubPanelBtn'); if(b) b.click(); else showToast('Opções de legenda indisponíveis'); }
  function openInfo() { closeMenu(); const b=$('toggleMovieInfoBtn'); if(b) b.click(); else $('movieInfoSidebar')?.classList.toggle('collapsed'); }
  function downloadVideo() {
    closeMenu();
    const anchor = $('downloadBtn');
    if (!anchor || !anchor.href || anchor.href.endsWith('#')) {
      showToast('Download indisponível para este vídeo');
      return;
    }
    try {
      anchor.click();
      showToast('Download iniciado');
    } catch {
      window.open(anchor.href, '_blank', 'noopener');
    }
  }
  function build() {
    video.controls = false;
  video.removeAttribute('title');
  video.removeAttribute('aria-label');
  video.setAttribute('playsinline', '');
    const html = [
      '<div class="eplay-player-ui" id="eplayPlayerUi" aria-label="Controles do player">',
      '<div class="eplay-player-top"><div class="eplay-player-heading"><div class="eplay-player-title" id="eplayPlayerTitle">EPlay</div><div class="eplay-player-meta" id="eplayPlayerMeta">Preparando reprodução...</div></div><div class="eplay-player-top-actions"><span class="eplay-live-badge" id="eplayLiveBadge">● AO VIVO</span><button class="eplay-player-close" id="eplayPlayerClose" type="button" aria-label="Fechar player">×</button></div></div>',
      '<div class="eplay-player-center"><button class="eplay-center-play" id="eplayCenterPlay" aria-label="Reproduzir">▶</button></div>',
      '<button class="eplay-skip-intro" id="eplaySkipIntroOverlay" type="button" style="display:none">⏭ Pular abertura</button>',
      '<div class="eplay-player-bottom"><div class="eplay-seek-wrap"><span class="eplay-time" id="eplayCurrentTime">00:00</span><input id="eplaySeek" class="eplay-range" type="range" min="0" max="0" value="0" step="0.1" aria-label="Posição da reprodução"><span class="eplay-time" id="eplayDuration">00:00</span></div>',
      '<div class="eplay-controls"><button class="eplay-control small eplay-series-nav" id="eplayPrevEpisode" aria-label="Episódio anterior" style="display:none">‹ Ant</button><button class="eplay-control small" id="eplayBack10" aria-label="Voltar 10 segundos">−10</button><button class="eplay-control" id="eplayPlay" aria-label="Reproduzir">▶</button><button class="eplay-control small" id="eplayForward10" aria-label="Avançar 10 segundos">+10</button><button class="eplay-control small eplay-series-nav" id="eplayNextEpisode" aria-label="Próximo episódio" style="display:none">Pro ›</button>',
      '<div class="eplay-volume"><button class="eplay-control" id="eplayVolumeBtn" aria-label="Volume">🔊</button><input id="eplayVolume" class="eplay-range" type="range" min="0" max="100" value="85" aria-label="Volume"></div><div class="eplay-spacer"></div>',
      '<button class="eplay-control small eplay-audio-track-btn" id="eplayAudioBtn" aria-label="Áudio e Versão" title="Áudio / Versão">🎧 Áudio</button><button class="eplay-control small" id="eplaySubtitle" aria-label="Legendas">CC</button><button class="eplay-control small" id="eplayInfo" aria-label="Ficha técnica">ⓘ</button><button class="eplay-control small" id="eplayPip" aria-label="Picture-in-Picture">▣</button><button class="eplay-control" id="eplaySettings" aria-label="Configurações" aria-expanded="false">⚙</button><button class="eplay-control" id="eplayFullscreen" aria-label="Tela cheia">⛶</button></div></div>',
      '<div class="eplay-menu" id="eplayMenu"><h4>Configurações de reprodução</h4>',
      '<div class="eplay-version-section" id="eplayVersionSection" style="display:none;"><div class="eplay-menu-row"><span>Áudio / Versão</span><strong id="eplayVersionBadge" style="color:#ffc107;font-size:11px;"></strong></div><div class="eplay-version-list" id="eplayVersionItems"></div></div>',
      '<button id="eplayAudioSyncMenu" style="display:none">🔊 Sincronizar áudio híbrido</button>',
      '<div class="eplay-menu-row"><span>Velocidade</span><strong id="eplaySpeedLabel">1x</strong></div>',
      '<div class="eplay-speed-list"><button data-speed="0.75">0.75x</button><button data-speed="1">1x</button><button data-speed="1.25">1.25x</button><button data-speed="1.5">1.5x</button><button data-speed="1.75">1.75x</button><button data-speed="2">2x</button></div>',
      '<label class="eplay-auto-skip-row" id="eplayAutoSkipRow"><input type="checkbox" id="eplayAutoSkipToggle"><span><b>Pular abertura automaticamente</b><small>Somente quando houver marcador comunitário válido</small></span></label>',
      '<button id="eplaySkipIntroNow" style="display:none">⏭ Pular abertura agora</button>',
      '<button id="eplaySubMenu">💬 Legendas e sincronização</button><button id="eplayInfoMenu">ⓘ Ficha técnica</button><button id="eplayDownloadMenu">⇩ Baixar vídeo</button><button id="eplayPipMenu">▣ Picture-in-Picture</button><button id="eplayLiveSyncMenu" style="display:none">⚡ Sincronizar ao vivo</button><button id="eplayLatencyMenu" style="display:none">⚡ Alternar buffer</button><button id="eplayFsMenu">⛶ Tela cheia</button></div>',
      '<div class="eplay-toast" id="eplayPlayerToast"></div>',
      '<div class="eplay-resume" id="eplayResume"><span id="eplayResumeText">Continuar reprodução?</span><button class="continue" id="eplayResumeContinue">Continuar</button><button class="restart" id="eplayResumeRestart">Do início</button></div>',
      '</div>'
    ].join('');
    container.insertAdjacentHTML('beforeend',html);
    ui=$('eplayPlayerUi'); seek=$('eplaySeek'); currentTime=$('eplayCurrentTime'); duration=$('eplayDuration'); playBtn=$('eplayPlay'); center=$('eplayCenterPlay'); menu=$('eplayMenu'); resume=$('eplayResume');
  }
  build();
  function syncPlaybackSurface() {
    const embedEl = $('embedPlayer');
    const embedVisible = !!(embedEl && embedEl.style.display !== 'none');
    const videoVisible = !!(video && video.style.display !== 'none');
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
  const embedElInit = $('embedPlayer');
  if (embedElInit) sourceObserver.observe(embedElInit, { attributes: true, attributeFilter: ['style', 'src'] });
  syncPlaybackSurface();
  const $on=(id,ev,fn)=>$(id)?.addEventListener(ev,fn);
  $on('eplayPlayerClose','click',()=>{$('closeVideoModal')?.click();});
  $on('eplayPlay','click',playPause); $on('eplayCenterPlay','click',playPause);
  $on('eplayBack10','click',()=>seekBy(-10)); $on('eplayForward10','click',()=>seekBy(10));
  $on('eplayPrevEpisode','click',()=>{ closeMenu(); window.EPlaySeriesNavigation?.previous?.(); }); $on('eplayNextEpisode','click',()=>{ closeMenu(); window.EPlaySeriesNavigation?.next?.(); });
  function openAudioVersionMenu() {
    if (!menu.classList.contains('open')) {
      menu.classList.add('open');
      const settings = $('eplaySettings');
      if (settings) settings.setAttribute('aria-expanded', 'true');
    }
    const versionSection = $('eplayVersionSection');
    if (versionSection && versionSection.style.display !== 'none') {
      versionSection.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
    reveal();
  }
  $on('eplayAudioBtn', 'click', () => {
    if (menu.classList.contains('open') && $('eplayVersionSection')?.style.display !== 'none') {
      closeMenu();
    } else {
      openAudioVersionMenu();
    }
  });
  function syncSkipTools(event){const n=window.EPlaySeriesNavigation;const source=$('skipIntroBtn');const visibleFromEvt=event?.detail&&typeof event.detail.visible==='boolean'?event.detail.visible:null;const available=!!(n && (visibleFromEvt!==null?visibleFromEvt:(source && (source.dataset.eplayVisible==='1'||source.style.display!=='none'))));const autoRow=$('eplayAutoSkipRow'),autoToggle=$('eplayAutoSkipToggle');if(autoRow)autoRow.style.display=n?'flex':'none';if(autoToggle)autoToggle.checked=$('skipIntroAutoToggle')?.checked || localStorage.getItem('andplay_web_skip_intro_auto')==='1';const now=$('eplaySkipIntroNow');if(now)now.style.display=available?'block':'none';const overlay=$('eplaySkipIntroOverlay');if(overlay){overlay.style.display=available?'inline-flex':'none';overlay.disabled=!available;}const liveSync=$('eplayLiveSyncMenu'),lat=$('eplayLatencyMenu');if(liveSync)liveSync.style.display=$('syncLiveBtn')?.style.display!=='none'?'block':'none';if(lat)lat.style.display=$('toggleLatencyModeBtn')?.style.display!=='none'?'block':'none';}
  function syncSeriesNav(){const n=window.EPlaySeriesNavigation;const p=$('eplayPrevEpisode'),x=$('eplayNextEpisode');if(!n){if(p)p.style.display='none';if(x)x.style.display='none';}else{const hasPrev=n.hasPrevious !== undefined ? !!n.hasPrevious : Number(n.episodeNum||0)>1;const hasNext=n.hasNext !== undefined ? !!n.hasNext : true;if(p)p.style.display=hasPrev?'inline-flex':'none';if(x)x.style.display=hasNext?'inline-flex':'none';}syncSkipTools();}
  window.addEventListener('eplay:skip-auto-changed',syncSkipTools);
  window.addEventListener('eplay:skip-state',syncSkipTools);
  window.addEventListener('eplay:series-context',syncSeriesNav); syncSeriesNav();
  $on('eplayVolumeBtn','click',toggleMute); $on('eplayVolume','input',e=>setVolume(Number(e.target.value)/100));
  $on('eplaySubtitle','click',openSubtitles); $on('eplayInfo','click',openInfo); $on('eplayPip','click',pip);
  $on('eplaySettings','click',toggleMenu); $on('eplayDownloadMenu','click',downloadVideo); $on('eplayInfoMenu','click',openInfo); $on('eplaySubMenu','click',openSubtitles); $on('eplayPipMenu','click',pip); $on('eplayFsMenu','click',fullscreen); $on('eplayFullscreen','click',fullscreen);
  $on('eplayAudioSyncMenu','click',()=>{
    closeMenu();
    const p=$('subOptionsPanel');
    if(p&&p.style.display==='none'){
      const b=$('toggleSubPanelBtn');
      if(b)b.click();else p.style.display='block';
    }
    const a=$('audioSyncControls');
    if(a)a.style.display='inline-flex';
    const input=$('audioOffsetInput');
    if(input){input.focus();input.select();}
  });
  const skipIntroNow=()=>{const b=$('skipIntroBtn');if(b&&(b.dataset.eplayVisible==='1'||b.style.display!=='none'))b.click();else showToast('Nenhum marcador de abertura disponível agora');};
  $on('eplaySkipIntroNow','click',skipIntroNow); $on('eplaySkipIntroOverlay','click',skipIntroNow);
  $on('eplayAutoSkipToggle','change',e=>{const b=$('skipIntroAutoToggle');if(b){b.checked=!!e.target.checked;b.dispatchEvent(new Event('change',{bubbles:true}));}syncSkipTools();});
  $on('eplayLiveSyncMenu','click',()=>{$('syncLiveBtn')?.click();closeMenu();});
  $on('eplayLatencyMenu','click',()=>{$('toggleLatencyModeBtn')?.click();closeMenu();});
  const closeMenuFromOutside = e => {
    if (!menu?.classList.contains('open')) return;
    if (e.target.closest('#eplayMenu') || e.target.closest('#eplaySettings') || e.target.closest('#subOptionsPanel')) return;
    closeMenu();
  };
  document.addEventListener('pointerdown', closeMenuFromOutside, true);
  document.addEventListener('click', closeMenuFromOutside, true);
  document.addEventListener('touchstart', closeMenuFromOutside, true);
  seek.addEventListener('input',()=>{if(Number.isFinite(video.duration))video.currentTime=Number(seek.value);setRange();reveal();});
  document.querySelectorAll('[data-speed]').forEach(b=>b.addEventListener('click',()=>applySpeed(b.dataset.speed)));
  let lastMoveTime = 0;
  function throttledReveal() {
    const now = Date.now();
    if (now - lastMoveTime < 100) return;
    lastMoveTime = now;
    reveal();
  }
  container.addEventListener('mousemove', throttledReveal);
  container.addEventListener('touchstart', reveal, { passive: true });
  video.addEventListener('play',()=>{setPlayIcon();reveal()}); video.addEventListener('pause',()=>{setPlayIcon();reveal()}); video.addEventListener('ended',()=>{setPlayIcon();reveal()});
  video.addEventListener('timeupdate',setRange); video.addEventListener('durationchange',setRange); video.addEventListener('loadedmetadata',()=>{setRange();updateTitle()});
  video.addEventListener('volumechange',updateVolume); video.addEventListener('ratechange',()=>applySpeed(video.playbackRate));
  function onFsChange() {
    const active = isFullscreenActive();
    modal.classList.toggle('eplay-fullscreen', active);
    container.classList.toggle('eplay-fullscreen', active);
    document.body.classList.toggle('eplay-fullscreen-active', active);
    updateFullscreenIcons(active);
    if (!active) {
      try {
        if (screen.orientation && typeof screen.orientation.unlock === 'function') {
          screen.orientation.unlock();
        }
      } catch (_) {}
    }
    reveal();
  }
  ['fullscreenchange', 'webkitfullscreenchange', 'mozfullscreenchange', 'MSFullscreenChange'].forEach(evt => {
    document.addEventListener(evt, onFsChange);
  });
  video.addEventListener('webkitbeginfullscreen', onFsChange);
  video.addEventListener('webkitendfullscreen', onFsChange);
  window.addEventListener('keydown',e=>{
    if(modal.style.display==='none')return;
    const tag=(e.target.tagName||'').toLowerCase();if(['input','select','textarea'].includes(tag))return;
    if(e.code==='Space'||e.key===' '){e.preventDefault();e.stopImmediatePropagation();playPause();return}
    if(e.key.toLowerCase()==='k'){e.preventDefault();playPause()} else if(e.key==='ArrowLeft'){
      if(e.shiftKey && window.isHybridAudioActive && window.isHybridAudioActive()){
        e.preventDefault();
        if(window.adjustAudioOffset) window.adjustAudioOffset(e.ctrlKey ? -500 : -50);
        return;
      }
      e.preventDefault();seekBy(-10);
    } else if(e.key==='ArrowRight'){
      if(e.shiftKey && window.isHybridAudioActive && window.isHybridAudioActive()){
        e.preventDefault();
        if(window.adjustAudioOffset) window.adjustAudioOffset(e.ctrlKey ? 500 : 50);
        return;
      }
      e.preventDefault();seekBy(10);
    } else if(e.key==='ArrowUp'){e.preventDefault();setVolume(video.volume+.05)} else if(e.key==='ArrowDown'){e.preventDefault();setVolume(video.volume-.05)} else if(e.key.toLowerCase()==='m'){e.preventDefault();toggleMute()} else if(e.key.toLowerCase()==='f'){e.preventDefault();fullscreen()} else if(e.key.toLowerCase()==='p'){e.preventDefault();pip()} else if(e.key.toLowerCase()==='c'){e.preventDefault();openSubtitles()} else if(e.key.toLowerCase()==='s'){e.preventDefault();skipIntroNow()} else if(e.key==='Escape'){closeMenu();reveal()} else reveal();
  }, true);
  let sx=0,sy=0,st=0,lastTapTime=0,lastTapX=0,lastTapY=0;
  container.addEventListener('touchstart',e=>{
    const t=e.changedTouches[0];sx=t.clientX;sy=t.clientY;st=Date.now();
  },{passive:true});
  container.addEventListener('touchend',e=>{
    const t=e.changedTouches[0],dx=t.clientX-sx,dy=t.clientY-sy,elapsed=Date.now()-st;
    if(Math.abs(dx)>70&&Math.abs(dx)>Math.abs(dy)&&elapsed<800){
      seekBy(dx>0?10:-10);
    } else if(Math.abs(dx)<25&&Math.abs(dy)<25&&elapsed<450){
      const now=Date.now();
      const isInteractive = e.target.closest('button, input, select, a, .eplay-controls, .eplay-menu');
      if (!isInteractive && now - lastTapTime < 320 && Math.abs(t.clientX - lastTapX) < 45 && Math.abs(t.clientY - lastTapY) < 45) {
        lastTapTime = 0;
        fullscreen();
      } else {
        lastTapTime = now;
        lastTapX = t.clientX;
        lastTapY = t.clientY;
        reveal();
      }
    }
  },{passive:true});
  video.volume=state.volume;applySpeed(state.speed);updateVolume();setPlayIcon();setRange();
  function updateTitle(){const title=$('modalTitle')?.textContent||'EPlay';const n=window.EPlaySeriesNavigation;const meta=n?'Temporada '+n.seasonNum+' • Episódio '+n.episodeNum:'';$('eplayPlayerTitle').textContent=title;$('eplayPlayerMeta').textContent=meta;$('eplayPlayerMeta').style.display=meta?'block':'none';$('eplayLiveBadge').style.display=/ao vivo|live|canal/i.test(title)?'inline-block':'none';syncSkipTools();}
  const observer=new MutationObserver(updateTitle);observer.observe($('modalTitle'),{childList:true,characterData:true,subtree:true});window.addEventListener('eplay:series-context',updateTitle);updateTitle();
  window.EPlayPlayerUI={showToast,reveal,seekBy,playPause,fullscreen,pip,openAudioVersionMenu,closeMenu};
})();