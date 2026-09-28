(function initAndPlayAccount() {
  'use strict';

  const SUPABASE_CDN = 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm';
  const WATCHED_KEYS = {
    movies: 'andplay_web_recent_movies',
    series: 'andplay_web_recent_series'
  };
  const PROGRESS_PREFIX = 'andplay_web_vod_progress_';
  const PREFERENCES_KEY = 'andplay_web_preferences_v1';

  let supabaseClientPromise = null;
  let currentSession = null;
  let syncing = false;
  let syncQueued = false;
  let syncTimer = null;

  function config() {
    return window.ANDPLAY_SUPABASE_CONFIG || {};
  }

  function isConfigured() {
    const c = config();
    return Boolean(c.url && c.publishableKey);
  }

  async function getClient() {
    if (!isConfigured()) {
      throw new Error('A conta ainda não foi configurada. Preencha o Supabase em assets/js/supabase-config.js.');
    }
    if (!supabaseClientPromise) {
      supabaseClientPromise = import(SUPABASE_CDN).then(({ createClient }) =>
        createClient(config().url, config().publishableKey)
      );
    }
    return supabaseClientPromise;
  }

  function readJson(key, fallback) {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) {
      return fallback;
    }
  }

  function writeJson(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {}
  }

  function readWatched(type) {
    const raw = readJson(WATCHED_KEYS[type], []);
    return Array.isArray(raw) ? raw.map(String) : [];
  }

  function mergeWatched(type, remoteIds) {
    const local = readWatched(type);
    const merged = [];
    [...local, ...(Array.isArray(remoteIds) ? remoteIds : [])].forEach(id => {
      const value = String(id);
      if (value && !merged.includes(value)) merged.push(value);
    });
    writeJson(WATCHED_KEYS[type], merged.slice(0, 500));
  }

  function readLocalProgress() {
    const rows = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key || !key.startsWith(PROGRESS_PREFIX)) continue;
      const entry = readJson(key, null);
      if (!entry || !Number.isFinite(Number(entry.position))) continue;

      const suffix = key.slice(PROGRESS_PREFIX.length);
      const type = suffix.startsWith('series_') ? 'series' : 'movie';
      const contentId = suffix.replace(/^(series_|movie_)/, '');
      if (!contentId) continue;

      rows.push({
        content_type: type,
        content_id: String(contentId),
        position: Number(entry.position) || 0,
        duration: Number(entry.duration) || 0,
        title: String(entry.title || ''),
        poster: String(entry.poster || ''),
        series_id: entry.seriesId ? String(entry.seriesId) : null,
        season_num: entry.seasonNum ? Number(entry.seasonNum) : null,
        episode_num: entry.episodeNum ? Number(entry.episodeNum) : null,
        updated_at: new Date(Number(entry.updatedAt) || Date.now()).toISOString()
      });
    }
    return rows;
  }

  function readLocalPreferences() {
    return readJson(PREFERENCES_KEY, {});
  }

  function mergePreferences(remote) {
    const local = readLocalPreferences();
    const merged = { ...(remote || {}), ...(local || {}) };
    writeJson(PREFERENCES_KEY, merged);
    if (Object.prototype.hasOwnProperty.call(merged, 'skip_intro_auto')) {
      try { localStorage.setItem('andplay_web_skip_intro_auto', merged.skip_intro_auto ? '1' : '0'); } catch (e) {}
    }
    if (Object.prototype.hasOwnProperty.call(merged, 'sidebar_open')) {
      try { localStorage.setItem('andplay_sidebar_open', merged.sidebar_open ? '1' : '0'); } catch (e) {}
    }
    if (merged.taste_profile && typeof merged.taste_profile === 'object') {
      try {
        localStorage.setItem('andplay_web_taste_v1', JSON.stringify(merged.taste_profile));
      } catch (e) {}
    }
  }

  async function pushAllLocal(client) {
    const now = new Date().toISOString();
    const watchRows = [];
    ['movies', 'series'].forEach(type => {
      readWatched(type).forEach((id, index) => watchRows.push({
        content_type: type === 'movies' ? 'movie' : 'series',
        content_id: String(id),
        sort_order: index,
        updated_at: now
      }));
    });

    if (watchRows.length) {
      const { error } = await client.from('watch_history').upsert(watchRows, {
        onConflict: 'user_id,content_type,content_id'
      });
      if (error) throw error;
    }

    const progressRows = readLocalProgress();
    if (progressRows.length) {
      const { error } = await client.from('watch_progress').upsert(progressRows, {
        onConflict: 'user_id,content_type,content_id'
      });
      if (error) throw error;
    }

    const pref = readLocalPreferences();
    const { error: prefError } = await client.from('user_preferences').upsert({
      preferences: pref,
      updated_at: now
    }, { onConflict: 'user_id' });
    if (prefError) throw prefError;
  }

  async function pullRemote(client) {
    const history = await client.from('watch_history')
      .select('content_type,content_id,sort_order,updated_at')
      .order('sort_order', { ascending: true });
    if (history.error) throw history.error;

    const movieIds = (history.data || [])
      .filter(r => r.content_type === 'movie')
      .map(r => r.content_id);
    const seriesIds = (history.data || [])
      .filter(r => r.content_type === 'series')
      .map(r => r.content_id);
    mergeWatched('movies', movieIds);
    mergeWatched('series', seriesIds);

    const progress = await client.from('watch_progress')
      .select('content_type,content_id,position,duration,title,poster,series_id,season_num,episode_num,updated_at')
      .order('updated_at', { ascending: false });
    if (progress.error) throw progress.error;

    (progress.data || []).forEach(remote => {
      const localKey = PROGRESS_PREFIX + (remote.content_type === 'series' ? 'series_' : 'movie_') + remote.content_id;
      const local = readJson(localKey, null);
      const remoteTime = Date.parse(remote.updated_at || '') || 0;
      const localTime = Number(local?.updatedAt) || 0;
      if (!local || remoteTime > localTime) {
        writeJson(localKey, {
          position: Number(remote.position) || 0,
          duration: Number(remote.duration) || 0,
          title: String(remote.title || ''),
          poster: String(remote.poster || ''),
          seriesId: remote.series_id || '',
          seasonNum: remote.season_num || '',
          episodeNum: remote.episode_num || '',
          updatedAt: remoteTime || Date.now()
        });
      }
    });
    const prefs = await client.from('user_preferences')
      .select('preferences,updated_at')
      .maybeSingle();
    if (prefs.error) throw prefs.error;
    if (prefs.data?.preferences) mergePreferences(prefs.data.preferences);
  }

  async function deleteRemoteProgress(type, id) {
    if (!currentSession) return;
    try {
      const client = await getClient();
      const { error } = await client.from('watch_progress')
        .delete()
        .eq('content_type', type)
        .eq('content_id', String(id));
      if (error) throw error;
    } catch (error) {
      console.warn('[AndPlay Account] Progress delete sync:', error);
    }
  }

  async function syncNow() {
    if (syncing || !currentSession) return;
    syncing = true;
    try {
      const client = await getClient();
      await pullRemote(client);
      await pushAllLocal(client);
      updateAccountUi();
      if (typeof window.AndPlayApp?.refreshAfterAccountSync === 'function') {
        window.AndPlayApp.refreshAfterAccountSync();
      }
    } catch (error) {
      console.warn('[AndPlay Account] Sync error:', error);
      setStatus(error.message || 'Não foi possível sincronizar agora.');
    } finally {
      syncing = false;
      syncQueued = false;
    }
  }

  function queueSync(delayMs = 1500) {
    if (!currentSession) return;
    if (syncQueued || syncTimer) return;
    syncQueued = true;
    syncTimer = setTimeout(() => {
      syncTimer = null;
      syncNow();
    }, Math.max(500, Number(delayMs) || 1500));
  }

  function setStatus(message, good = false) {
    const el = document.getElementById('andplayAccountStatus');
    if (el) {
      el.textContent = message || '';
      el.classList.toggle('good', !!good);
    }
  }

  function setAuthView(view) {
    document.querySelectorAll('[data-account-view]').forEach(el => {
      el.style.display = el.dataset.accountView === view ? 'block' : 'none';
    });
    document.querySelectorAll('[data-account-tab]').forEach(el => {
      el.classList.toggle('active', el.dataset.accountTab === view);
    });
  }
  function updateAccountUi() {
    const button = document.getElementById('accountBtn');
    const label = document.getElementById('accountBtnLabel');
    if (!button) return;

    if (!isConfigured()) {
      if (label) label.textContent = 'Conta';
      button.title = 'Conta e sincronização';
      return;
    }

    if (currentSession?.user?.email) {
      if (label) label.textContent = currentSession.user.email.split('@')[0];
      button.title = 'Conta sincronizada • ' + currentSession.user.email;
    } else {
      if (label) label.textContent = 'Entrar';
      button.title = 'Entrar para sincronizar seu EPlay';
    }
  }

  function openModal() {
    const modal = document.getElementById('andplayAccountModal');
    if (!modal) return;
    modal.style.display = 'flex';
    document.body.classList.add('account-modal-open');
    updateAccountUi();
    if (!isConfigured()) {
      setAuthView('setup');
      setStatus('A conta está pronta, mas falta conectar um projeto Supabase Free.');
    } else if (currentSession) {
      setAuthView('account');
      setStatus('Conta conectada.', true);
    } else {
      setAuthView('login');
      setStatus('');
    }
  }

  function closeModal() {
    const modal = document.getElementById('andplayAccountModal');
    if (modal) modal.style.display = 'none';
    document.body.classList.remove('account-modal-open');
  }

  async function signIn() {
    try {
      const email = document.getElementById('accountEmail')?.value.trim();
      const password = document.getElementById('accountPassword')?.value || '';
      if (!email || !password) throw new Error('Informe email e senha.');
      setStatus('Entrando...');
      const client = await getClient();
      const { data, error } = await client.auth.signInWithPassword({ email, password });
      if (error) throw error;
      currentSession = data.session;
      setAuthView('account');
      setStatus('Conta conectada. Sincronizando...', true);
      await syncNow();
    } catch (error) {
      setStatus(error.message || 'Não foi possível entrar.');
    }
  }

  async function signInWithProvider(provider) {
    try {
      setStatus('Abrindo login...');
      const client = await getClient();
      const redirectTo = window.location.origin + window.location.pathname;
      const { error } = await client.auth.signInWithOAuth({
        provider,
        options: { redirectTo }
      });
      if (error) throw error;
    } catch (error) {
      setStatus(error.message || 'Não foi possível iniciar o login.');
    }
  }

  async function signUp() {
    try {
      const email = document.getElementById('accountEmail')?.value.trim();
      const password = document.getElementById('accountPassword')?.value || '';
      if (!email || password.length < 6) {
        throw new Error('Use um email válido e uma senha de pelo menos 6 caracteres.');
      }
      setStatus('Criando conta...');
      const client = await getClient();
      const { data, error } = await client.auth.signUp({
        email,
        password,
        options: {
          emailRedirectTo: window.location.origin + window.location.pathname
        }
      });
      if (error) throw error;
      currentSession = data.session;
      if (currentSession) {
        setAuthView('account');
        setStatus('Conta criada. Sincronizando...', true);
        await syncNow();
      } else {
        setAuthView('login');
        setStatus('Conta criada. Confira o email para confirmar o acesso.', true);
      }
    } catch (error) {
      setStatus(error.message || 'Não foi possível criar a conta.');
    }
  }
  async function signOut() {
    try {
      const client = await getClient();
      const { error } = await client.auth.signOut();
      if (error) throw error;
      currentSession = null;
      setAuthView('login');
      setStatus('Você saiu da conta.');
      updateAccountUi();
    } catch (error) {
      setStatus(error.message || 'Não foi possível sair.');
    }
  }

  function buildModal() {
    if (document.getElementById('andplayAccountModal')) return;

    const modal = document.createElement('div');
    modal.id = 'andplayAccountModal';
    modal.className = 'andplay-account-modal';
    modal.style.display = 'none';
    modal.innerHTML = `
      <div class="andplay-account-dialog" role="dialog" aria-modal="true" aria-labelledby="andplayAccountTitle">
        <button class="andplay-account-close" type="button" aria-label="Fechar">×</button>
        <div class="andplay-account-head">
          <div class="andplay-account-kicker">EPLAY</div>
          <h2 id="andplayAccountTitle">Minha conta</h2>
          <p>Sincronize histórico, progresso e preferências entre seus dispositivos.</p>
        </div>

        <div class="andplay-account-tabs">
          <button type="button" data-account-tab="login">Entrar</button>
          <button type="button" data-account-tab="signup">Criar conta</button>
        </div>

        <div data-account-view="login">
          <label>Email<input id="accountEmail" type="email" autocomplete="email" placeholder="seu@email.com"></label>
          <label>Senha<input id="accountPassword" type="password" autocomplete="current-password" placeholder="••••••••"></label>
          <button class="andplay-account-primary" type="button" id="accountLoginBtn">Entrar</button>
          <div class="andplay-account-or"><span>ou continue com</span></div>
          <button class="andplay-account-google" type="button" id="accountGoogleBtn">
            <span class="andplay-account-google-icon">G</span>
            Continuar com Google
          </button>
        </div>

        <div data-account-view="signup" style="display:none">
          <label>Email<input id="accountEmailSignup" type="email" autocomplete="email" placeholder="seu@email.com"></label>
          <label>Senha<input id="accountPasswordSignup" type="password" autocomplete="new-password" placeholder="mínimo 6 caracteres"></label>
          <button class="andplay-account-primary" type="button" id="accountSignupBtn">Criar conta</button>
        </div>

        <div data-account-view="account" style="display:none">
          <div class="andplay-account-user" id="andplayAccountUser"></div>
          <div class="andplay-account-sync-row">
            <span>Sincronização na nuvem</span>
            <button type="button" id="accountSyncBtn">Sincronizar agora</button>
          </div>
          <button class="andplay-account-secondary" type="button" id="accountLogoutBtn">Sair da conta</button>
        </div>

        <div data-account-view="setup" style="display:none">
          <div class="andplay-account-setup">
            <strong>Conta pronta para ativação</strong>
            <p>Falta apenas conectar um projeto Supabase Free em <code>assets/js/supabase-config.js</code>.</p>
          </div>
        </div>

        <div id="andplayAccountStatus" class="andplay-account-status" aria-live="polite"></div>
        <div class="andplay-account-foot">O EPlay continua funcionando sem login. A conta sincroniza somente seus dados de uso; vídeos e catálogo continuam fora da nuvem.</div>
      </div>
    `;
    document.body.appendChild(modal);
  }
  function bindUi() {
    buildModal();

    document.getElementById('accountBtn')?.addEventListener('click', openModal);
    document.getElementById('accountModalBtn')?.addEventListener('click', openModal);
    document.querySelector('.andplay-account-close')?.addEventListener('click', closeModal);

    const modal = document.getElementById('andplayAccountModal');
    modal?.addEventListener('click', event => {
      if (event.target === modal) closeModal();
    });

    document.querySelectorAll('[data-account-tab]').forEach(button => {
      button.addEventListener('click', () => setAuthView(button.dataset.accountTab));
    });

    document.getElementById('accountLoginBtn')?.addEventListener('click', async () => {
      const email = document.getElementById('accountEmail');
      const password = document.getElementById('accountPassword');
      const email2 = document.getElementById('accountEmailSignup');
      const password2 = document.getElementById('accountPasswordSignup');
      if (email && email2) email2.value = email.value;
      if (password && password2) password2.value = password.value;
      await signIn();
    });

    document.getElementById('accountSignupBtn')?.addEventListener('click', async () => {
      const email = document.getElementById('accountEmailSignup');
      const password = document.getElementById('accountPasswordSignup');
      const emailLogin = document.getElementById('accountEmail');
      const passwordLogin = document.getElementById('accountPassword');
      if (emailLogin && email) emailLogin.value = email.value;
      if (passwordLogin && password) passwordLogin.value = password.value;
      await signUp();
    });

    document.getElementById('accountGoogleBtn')?.addEventListener('click', () => signInWithProvider('google'));

    document.getElementById('accountSyncBtn')?.addEventListener('click', syncNow);
    document.getElementById('accountLogoutBtn')?.addEventListener('click', signOut);

    window.addEventListener('andplay:local-change', event => {
      const detail = event.detail || {};
      if (detail.kind === 'progress-cleared' && detail.mediaType && detail.id) {
        deleteRemoteProgress(detail.mediaType, detail.id);
      } else if (detail.kind === 'progress') {
        queueSync(30000);
      } else {
        queueSync(1500);
      }
    });
    updateAccountUi();
  }

  function renderUser() {
    const el = document.getElementById('andplayAccountUser');
    if (el) el.textContent = currentSession?.user?.email || 'Conta conectada';
  }

  async function init() {
    bindUi();
    if (!isConfigured()) return;

    try {
      const client = await getClient();
      const sessionResult = await client.auth.getSession();
      currentSession = sessionResult.data?.session || null;
      renderUser();
      updateAccountUi();

      client.auth.onAuthStateChange((_event, session) => {
        currentSession = session || null;
        renderUser();
        updateAccountUi();
        setTimeout(() => {
          if (currentSession) syncNow();
        }, 0);
      });
    } catch (error) {
      console.warn('[AndPlay Account] Initialization:', error);
    }
  }

  window.AndPlayAccount = {
    isConfigured,
    isSignedIn: () => Boolean(currentSession),
    open: openModal,
    syncNow,
    queueSync,
    queueSyncWatched: queueSync,
    queueSyncProgress: queueSync,
    queueSyncPreference: queueSync
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();
