(function initAndPlayAccount() {
  'use strict';

  const SUPABASE_CDN = 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm';
  const WATCHED_KEYS = {
    movies: 'andplay_web_recent_movies',
    series: 'andplay_web_recent_series'
  };
  const PROGRESS_PREFIX = 'andplay_web_vod_progress_';
  const PREFERENCES_KEY = 'andplay_web_preferences_v1';
  const WATCH_STATS_KEY = 'andplay_web_watch_stats_v1';
  const LIVE_HISTORY_KEY = 'andplay_web_live_history_v1';

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

  function mergeWatchStats(remote) {
    if (!remote || remote.version !== 1 || typeof remote.devices !== 'object') return;
    const local = readJson(WATCH_STATS_KEY, { version: 1, devices: {} });
    if (!local || typeof local.devices !== 'object') local.devices = {};

    Object.entries(remote.devices).forEach(([deviceId, remoteBucket]) => {
      const localBucket = local.devices[deviceId];
      if (!localBucket) {
        local.devices[deviceId] = remoteBucket;
        return;
      }

      ['movieSeconds', 'seriesSeconds', 'liveSeconds', 'updatedAt'].forEach(field => {
        localBucket[field] = Math.max(Number(localBucket[field] || 0), Number(remoteBucket[field] || 0));
      });
      localBucket.genreSeconds = localBucket.genreSeconds || {};
      Object.entries(remoteBucket.genreSeconds || {}).forEach(([theme, seconds]) => {
        localBucket.genreSeconds[theme] = Math.max(
          Number(localBucket.genreSeconds[theme] || 0),
          Number(seconds || 0)
        );
      });
    });

    writeJson(WATCH_STATS_KEY, local);
  }

  function mergeTasteProfiles(remote, local) {
    if (!remote && !local) return null;
    const a = remote && typeof remote === 'object' ? remote : {};
    const b = local && typeof local === 'object' ? local : {};
    const result = {
      genres: {},
      types: {},
      recent: []
    };

    new Set([...Object.keys(a.genres || {}), ...Object.keys(b.genres || {})]).forEach(theme => {
      result.genres[theme] = Math.max(
        Number(a.genres?.[theme] || 0),
        Number(b.genres?.[theme] || 0)
      );
    });
    new Set(['movie', 'series']).forEach(type => {
      result.types[type] = Math.max(
        Number(a.types?.[type] || 0),
        Number(b.types?.[type] || 0)
      );
    });

    const recent = [...(a.recent || []), ...(b.recent || [])]
      .filter(item => item?.key)
      .reduce((map, item) => {
        const previous = map.get(item.key);
        if (!previous || Number(item.at || 0) > Number(previous.at || 0)) {
          map.set(item.key, item);
        }
        return map;
      }, new Map());

    result.recent = [...recent.values()]
      .sort((x, y) => Number(y.at || 0) - Number(x.at || 0))
      .slice(0, 80);
    return result;
  }

  function mergeLiveHistory(remote) {
    if (!Array.isArray(remote)) return;
    const map = new Map();

    [...readJson(LIVE_HISTORY_KEY, []), ...remote].forEach(item => {
      if (!item?.id) return;
      const id = String(item.id);
      const current = map.get(id);
      if (!current || Number(item.updatedAt || 0) > Number(current.updatedAt || 0)) {
        map.set(id, { ...item, id });
      }
    });

    writeJson(
      LIVE_HISTORY_KEY,
      [...map.values()]
        .sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0))
        .slice(0, 100)
    );
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
    const remotePrefs = remote || {};
    const merged = { ...remotePrefs, ...local };

    const mergedTaste = mergeTasteProfiles(remotePrefs.taste_profile, local.taste_profile);
    if (mergedTaste) {
      writeJson('andplay_web_taste_v1', mergedTaste);
      merged.taste_profile = mergedTaste;
    }

    if (remotePrefs.watch_stats) mergeWatchStats(remotePrefs.watch_stats);
    if (Array.isArray(remotePrefs.live_history)) mergeLiveHistory(remotePrefs.live_history);
    merged.watch_stats = readJson(WATCH_STATS_KEY, null);
    merged.live_history = readJson(LIVE_HISTORY_KEY, []);

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
    if (merged.series_episode_history && typeof merged.series_episode_history === 'object') {
      try {
        const local = readJson('andplay_web_series_episode_history_v1', {});
        const remote = merged.series_episode_history;
        const combined = { ...(remote || {}), ...(local || {}) };
        localStorage.setItem('andplay_web_series_episode_history_v1', JSON.stringify(combined));
      } catch (e) {}
    }
    if (merged.watch_stats && typeof merged.watch_stats === 'object') {
      mergeWatchStats(merged.watch_stats);
    }
    if (Array.isArray(merged.live_history)) {
      mergeLiveHistory(merged.live_history);
    }
  }

  async function pushAllLocal(client) {
    const now = new Date().toISOString();
    const watchRows = [];
    const activity = readJson('andplay_web_recent_activity_v1', []);
    const activityMap = new Map(
      (Array.isArray(activity) ? activity : [])
        .filter(item => item?.id)
        .map(item => [String(item.type) + ':' + String(item.id), item])
    );
    ['movies', 'series'].forEach(type => {
      readWatched(type).forEach((id, index) => {
        const activityItem = activityMap.get(type + ':' + String(id));
        const activityAt = Number(activityItem?.updatedAt || 0);
        watchRows.push({
          content_type: type === 'movies' ? 'movie' : 'series',
          content_id: String(id),
          sort_order: index,
          updated_at: new Date(activityAt || Date.now()).toISOString()
        });
      });
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

    const pref = {
      ...readLocalPreferences(),
      watch_stats: readJson(WATCH_STATS_KEY, null),
      live_history: readJson(LIVE_HISTORY_KEY, [])
    };
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

    const localActivity = readJson('andplay_web_recent_activity_v1', []);
    const activityMap = new Map(
      (Array.isArray(localActivity) ? localActivity : [])
        .filter(item => item?.id)
        .map(item => [String(item.type) + ':' + String(item.id), item])
    );
    (history.data || []).forEach(row => {
      const key = (row.content_type === 'movie' ? 'movies' : 'series') + ':' + String(row.content_id);
      const existing = activityMap.get(key);
      const remoteAt = Date.parse(row.updated_at || '') || 0;
      if (!existing || remoteAt > Number(existing.updatedAt || 0)) {
        activityMap.set(key, {
          type: row.content_type === 'movie' ? 'movies' : 'series',
          id: String(row.content_id),
          updatedAt: remoteAt
        });
      }
    });
    writeJson(
      'andplay_web_recent_activity_v1',
      [...activityMap.values()]
        .sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0))
        .slice(0, 500)
    );

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
      console.warn('[EPlay Account] Progress delete sync:', error);
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
      return true;
    } catch (error) {
      console.warn('[EPlay Account] Sync error:', error);
      setStatus(error.message || 'Não foi possível sincronizar agora.');
      return false;
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
    const authTabs = document.querySelector('.andplay-account-tabs');
    if (authTabs) {
      authTabs.style.display = ['login', 'signup'].includes(view) ? 'flex' : 'none';
    }
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
      if (label) label.textContent = getDisplayName(currentSession.user);
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
      setAccountSubtab('overview');
      renderProfile();
      setStatus('Conta conectada.', true);
      refreshAccountUsage().catch(() => {});
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
      setAccountSubtab('overview');
      renderProfile();
      setStatus('Conta conectada. Sincronizando...', true);
      await syncNow();
      await refreshAccountUsage();
    } catch (error) {
      setStatus(error.message || 'Não foi possível entrar.');
    }
  }

  function getAuthRedirectUrl() {
    return window.location.origin + window.location.pathname;
  }

  async function signInWithProvider(provider) {
    try {
      setStatus('Abrindo login...');
      const client = await getClient();
      const { error } = await client.auth.signInWithOAuth({
        provider,
        options: { redirectTo: getAuthRedirectUrl() }
      });
      if (error) throw error;
    } catch (error) {
      setStatus(error.message || 'Não foi possível iniciar o login.');
    }
  }

  async function requestPasswordReset() {
    try {
      const email = document.getElementById('accountResetEmail')?.value.trim() ||
        document.getElementById('accountEmail')?.value.trim() || '';
      if (!email) throw new Error('Informe seu email para receber o link de redefinição.');

      setStatus('Enviando instruções...');
      const client = await getClient();
      const { error } = await client.auth.resetPasswordForEmail(email, {
        redirectTo: getAuthRedirectUrl()
      });
      if (error) throw error;

      setStatus('Se este email estiver cadastrado, você receberá as instruções para redefinir a senha.', true);
    } catch (error) {
      setStatus(error.message || 'Não foi possível solicitar a redefinição.');
    }
  }

  async function updatePassword() {
    try {
      const password = document.getElementById('accountNewPassword')?.value || '';
      const confirmation = document.getElementById('accountNewPasswordConfirm')?.value || '';

      if (password.length < 6) {
        throw new Error('Use uma senha de pelo menos 6 caracteres.');
      }
      if (password !== confirmation) {
        throw new Error('As senhas não coincidem.');
      }

      setStatus('Atualizando senha...');
      const client = await getClient();
      const { error } = await client.auth.updateUser({ password });
      if (error) throw error;

      document.getElementById('accountNewPassword').value = '';
      document.getElementById('accountNewPasswordConfirm').value = '';
      setAuthView('account');
      setStatus('Senha atualizada com sucesso.', true);
    } catch (error) {
      setStatus(error.message || 'Não foi possível atualizar a senha.');
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
        setAccountSubtab('overview');
        renderProfile();
        setStatus('Conta criada. Sincronizando...', true);
        await syncNow();
        await refreshAccountUsage();
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

  function getCurrentUser() {
    return currentSession?.user || null;
  }

  function getDisplayName(user = getCurrentUser()) {
    const meta = user?.user_metadata || {};
    return String(
      meta.display_name ||
      meta.full_name ||
      meta.name ||
      user?.email?.split('@')[0] ||
      'Usuário'
    ).trim() || 'Usuário';
  }

  function getAvatarUrl(user = getCurrentUser()) {
    const meta = user?.user_metadata || {};
    const direct = String(meta.avatar_url || meta.picture || meta.avatar || '').trim();
    if (direct) return direct;

    const identities = Array.isArray(user?.identities) ? user.identities : [];
    for (const identity of identities) {
      const data = identity?.identity_data || {};
      const identityAvatar = String(data.avatar_url || data.picture || data.avatar || '').trim();
      if (identityAvatar) return identityAvatar;
    }
    return '';
  }

  function getProfilePlaceholder() {
    return 'data:image/svg+xml;charset=UTF-8,' + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120">' +
        '<rect width="120" height="120" rx="60" fill="#747b86"/>' +
        '<circle cx="60" cy="43" r="23" fill="#c9cdd2"/>' +
        '<path d="M22 106c5-25 20-38 38-38s33 13 38 38" fill="#c9cdd2"/>' +
      '</svg>'
    );
  }

  function formatDuration(seconds) {
    const total = Math.max(0, Math.round(Number(seconds) || 0));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    if (days > 0) return days + 'd ' + hours + 'h';
    if (hours > 0) return hours + 'h ' + minutes + 'min';
    return minutes + ' min';
  }

  function formatDate(value) {
    const date = new Date(Number(value) || value || 0);
    if (Number.isNaN(date.getTime())) return '';
    return date.toLocaleDateString('pt-BR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric'
    });
  }

  async function saveDisplayName() {
    try {
      if (!currentSession) throw new Error('Entre na conta para alterar seu apelido.');
      const input = document.getElementById('accountDisplayName');
      const value = String(input?.value || '').trim().replace(/\s+/g, ' ');
      if (value.length < 2) throw new Error('Use pelo menos 2 caracteres para o nome de exibição.');
      if (value.length > 32) throw new Error('O nome de exibição pode ter no máximo 32 caracteres.');

      setStatus('Salvando nome de exibição...');
      const client = await getClient();
      const { data, error } = await client.auth.updateUser({
        data: { display_name: value }
      });
      if (error) throw error;

      currentSession.user = data.user || currentSession.user;
      renderUser();
      updateAccountUi();
      renderProfile();
      setStatus('Nome de exibição atualizado.', true);
    } catch (error) {
      setStatus(error.message || 'Não foi possível atualizar o nome.');
    }
  }

  async function changeAccountPassword() {
    try {
      if (!currentSession) throw new Error('Entre na conta para alterar sua senha.');
      const current = document.getElementById('accountCurrentPassword')?.value || '';
      const password = document.getElementById('accountNewPasswordInfo')?.value || '';
      const confirm = document.getElementById('accountNewPasswordInfoConfirm')?.value || '';

      if (password.length < 6) throw new Error('Use uma senha de pelo menos 6 caracteres.');
      if (password !== confirm) throw new Error('As senhas não coincidem.');

      setStatus('Atualizando senha...');
      const client = await getClient();
      const attributes = { password };
      if (current) attributes.current_password = current;
      const { error } = await client.auth.updateUser(attributes);
      if (error) throw error;

      ['accountCurrentPassword', 'accountNewPasswordInfo', 'accountNewPasswordInfoConfirm']
        .forEach(id => {
          const el = document.getElementById(id);
          if (el) el.value = '';
        });
      setStatus('Senha alterada com sucesso.', true);
    } catch (error) {
      setStatus(error.message || 'Não foi possível alterar a senha.');
    }
  }

  function setAccountSubtab(tab) {
    document.querySelectorAll('[data-account-subtab]').forEach(button => {
      button.classList.toggle('active', button.dataset.accountSubtab === tab);
    });
    document.querySelectorAll('[data-account-panel]').forEach(panel => {
      panel.style.display = panel.dataset.accountPanel === tab ? 'block' : 'none';
    });
    if (tab === 'overview') renderOverview();
    if (tab === 'history') renderHistory();
    if (tab === 'info') renderProfile();
  }

  function renderProfile() {
    const user = getCurrentUser();
    const avatar = document.getElementById('accountProfileAvatar');
    const name = document.getElementById('accountProfileName');
    const email = document.getElementById('accountProfileEmail');
    const displayName = document.getElementById('accountDisplayName');

    if (avatar) {
      avatar.src = getAvatarUrl(user) || getProfilePlaceholder();
      avatar.onerror = () => {
        avatar.onerror = null;
        avatar.src = getProfilePlaceholder();
      };
      avatar.alt = 'Foto de perfil de ' + getDisplayName(user);
    }
    if (name) name.textContent = getDisplayName(user);
    if (email) email.textContent = user?.email || 'Conta conectada';
    const infoEmail = document.getElementById('accountInfoEmail');
    if (infoEmail) infoEmail.textContent = user?.email || '—';
    if (displayName && document.activeElement !== displayName) displayName.value = getDisplayName(user);
  }

  function renderOverview(snapshot = null) {
    const root = document.getElementById('accountOverview');
    if (!root) return;
    const usage = snapshot || window.__eplayAccountUsage || {
      totals: { movies: 0, series: 0, liveChannels: 0 },
      watchTime: { movieSeconds: 0, seriesSeconds: 0, liveSeconds: 0 },
      genres: []
    };
    const cards = [
      ['🎬', 'Filmes assistidos', usage.totals.movies],
      ['📺', 'Séries assistidas', usage.totals.series],
      ['📡', 'Canais visitados', usage.totals.liveChannels],
      ['⏱', 'Tempo em filmes', formatDuration(usage.watchTime.movieSeconds)],
      ['⏱', 'Tempo em séries', formatDuration(usage.watchTime.seriesSeconds)]
    ];

    root.innerHTML =
      '<div class="account-stats-grid">' +
      cards.map(card =>
        '<div class="account-stat-card">' +
          '<span class="account-stat-icon">' + card[0] + '</span>' +
          '<div><strong>' + escapeHtml(String(card[2])) + '</strong><small>' + escapeHtml(card[1]) + '</small></div>' +
        '</div>'
      ).join('') +
      '</div>' +
      '<p class="account-usage-note">O tempo é contabilizado durante a reprodução ativa. O histórico anterior informa os títulos, mas não possui duração retroativa confiável.</p>' +
      '<div class="account-usage-section">' +
        '<div class="account-usage-head"><h3>Gêneros mais assistidos</h3><span>títulos distintos</span></div>' +
        (usage.genres.length
          ? '<div class="account-genre-list">' + usage.genres.map((genre, index) => {
              const max = Math.max(...usage.genres.map(item => Number(item.count || 0)), 1);
              const width = Math.max(7, Math.round(Number(genre.count || 0) / max * 100));
              return '<div class="account-genre-row">' +
                '<div class="account-genre-label"><strong>' + escapeHtml(genre.name) + '</strong><span>' +
                  escapeHtml(String(genre.count)) + ' títulos' +
                  (Number(genre.seconds || 0) > 0 ? ' • ' + escapeHtml(formatDuration(genre.seconds)) : '') +
                '</span></div>' +
                '<div class="account-genre-bar"><span style="width:' + width + '%"></span></div>' +
              '</div>';
            }).join('') + '</div>'
          : '<div class="account-empty-panel">Ainda não há dados suficientes para identificar seus gêneros mais assistidos.</div>') +
      '</div>';
  }

  function renderHistory(type = 'movies') {
    const root = document.getElementById('accountHistory');
    if (!root) return;
    const usage = window.__eplayAccountUsage || {
      history: { movies: [], series: [], channels: [] }
    };
    const history = usage.history?.[type] || [];
    const tabBar =
      '<div class="account-history-tabs">' +
        '<button type="button" data-history-type="movies" class="' + (type === 'movies' ? 'active' : '') + '">🎬 Filmes</button>' +
        '<button type="button" data-history-type="series" class="' + (type === 'series' ? 'active' : '') + '">📺 Séries</button>' +
        '<button type="button" data-history-type="channels" class="' + (type === 'channels' ? 'active' : '') + '">📡 Canais de TV</button>' +
      '</div>';

    const rows = history.length
      ? '<div class="account-history-list">' + history.map((entry, index) => {
          const poster = entry.type === 'series'
            ? (entry.item?.cover || entry.item?.stream_icon || '')
            : (entry.item?.primaryItem ? (entry.item.primaryItem.stream_icon || entry.item.primaryItem.cover || '') : '');
          const logo = type === 'channels' ? entry.logo : poster;
          const label = type === 'channels' ? (entry.category || 'TV ao vivo') : (entry.type === 'series' ? 'Série' : 'Filme');
          const date = entry.updated_at || entry.updatedAt ? formatDate(entry.updated_at || entry.updatedAt) : '';
          const channelDate = entry.updatedAt ? formatDate(entry.updatedAt) : '';
          return '<button type="button" class="account-history-row" data-history-index="' + index + '">' +
            '<div class="account-history-thumb">' +
              (logo ? '<img src="' + escapeHtml(logo) + '" alt="" loading="lazy" decoding="async" data-hide-on-error>' : '<span>' + (type === 'channels' ? '📡' : '🎬') + '</span>') +
            '</div>' +
            '<div class="account-history-copy"><strong>' + escapeHtml(entry.title || entry.name || 'Conteúdo') + '</strong><small>' +
              escapeHtml(label + (date || channelDate ? ' • ' + (date || channelDate) : '')) +
            '</small></div>' +
            '<span class="account-history-index">' + (type === 'channels' ? (Number(entry.count || 0) + 'x') : '#' + (index + 1)) + '</span>' +
          '</button>';
        }).join('') + '</div>'
      : '<div class="account-empty-panel">Ainda não há registros nesta seção.</div>';

    root.innerHTML = tabBar + rows;
    root.querySelectorAll('[data-history-type]').forEach(button => {
      button.addEventListener('click', () => renderHistory(button.dataset.historyType));
    });
  }

  async function refreshAccountUsage() {
    try {
      const usage = window.AndPlayApp?.loadAccountUsage
        ? await window.AndPlayApp.loadAccountUsage()
        : window.AndPlayApp?.getAccountUsageSnapshot?.();
      if (usage) {
        window.__eplayAccountUsage = usage;
        renderOverview(usage);
        renderHistory();
      }
    } catch (error) {
      console.warn('[EPlay Account] Usage:', error);
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
          <button class="andplay-account-forgot" type="button" id="accountForgotBtn">Esqueci minha senha</button>
          <div class="andplay-account-or"><span>ou continue com</span></div>
          <button class="andplay-account-google" type="button" id="accountGoogleBtn">
            <span class="andplay-account-google-icon">G</span>
            Continuar com Google
          </button>
        </div>

        <div data-account-view="recovery" style="display:none">
          <label>Email<input id="accountResetEmail" type="email" autocomplete="email" placeholder="seu@email.com"></label>
          <button class="andplay-account-primary" type="button" id="accountResetBtn">Enviar link de redefinição</button>
          <button class="andplay-account-secondary" type="button" id="accountRecoveryBackBtn">Voltar para entrar</button>
          <p class="andplay-account-help">Por segurança, a mensagem é a mesma mesmo quando o email não está cadastrado.</p>
        </div>

        <div data-account-view="update-password" style="display:none">
          <div class="andplay-account-recovery-badge">LINK DE RECUPERAÇÃO</div>
          <label>Nova senha<input id="accountNewPassword" type="password" autocomplete="new-password" placeholder="mínimo 6 caracteres"></label>
          <label>Confirme a nova senha<input id="accountNewPasswordConfirm" type="password" autocomplete="new-password" placeholder="repita a senha"></label>
          <button class="andplay-account-primary" type="button" id="accountUpdatePasswordBtn">Atualizar senha</button>
        </div>

        <div data-account-view="signup" style="display:none">
          <label>Email<input id="accountEmailSignup" type="email" autocomplete="email" placeholder="seu@email.com"></label>
          <label>Senha<input id="accountPasswordSignup" type="password" autocomplete="new-password" placeholder="mínimo 6 caracteres"></label>
          <button class="andplay-account-primary" type="button" id="accountSignupBtn">Criar conta</button>
        </div>

        <div data-account-view="account" style="display:none">
          <div class="account-profile-card">
            <img id="accountProfileAvatar" class="account-profile-avatar" src="" alt="">
            <div class="account-profile-copy">
              <strong id="accountProfileName">Usuário</strong>
              <span id="accountProfileEmail">Conta conectada</span>
            </div>
            <button class="account-sync-icon" type="button" id="accountSyncBtn" title="Sincronizar agora" aria-label="Sincronizar agora">↻</button>
          </div>

          <div class="account-subtabs" role="tablist" aria-label="Seções da conta">
            <button type="button" data-account-subtab="overview" class="active" role="tab">Resumo</button>
            <button type="button" data-account-subtab="history" role="tab">Histórico</button>
            <button type="button" data-account-subtab="info" role="tab">Minhas Informações</button>
          </div>

          <section data-account-panel="overview" id="accountOverview" role="tabpanel"></section>
          <section data-account-panel="history" id="accountHistory" role="tabpanel" style="display:none"></section>
          <section data-account-panel="info" id="accountInfo" role="tabpanel" style="display:none">
            <div class="account-info-section">
              <div class="account-info-head">
                <h3>Perfil</h3>
                <span>Como você aparece no EPlay</span>
              </div>
              <label>Nome de Exibição (Apelido)
                <input id="accountDisplayName" type="text" maxlength="32" autocomplete="nickname" placeholder="Como você quer ser chamado">
              </label>
              <button class="andplay-account-primary account-info-save" type="button" id="accountDisplayNameBtn">Salvar nome</button>
              <div class="account-info-readonly">
                <span>Email</span><strong id="accountInfoEmail">—</strong>
              </div>
            </div>

            <div class="account-info-section">
              <div class="account-info-head">
                <h3>Senha</h3>
                <span>Altere sua senha quando quiser</span>
              </div>
              <label>Senha atual <span class="account-optional">(opcional)</span>
                <input id="accountCurrentPassword" type="password" autocomplete="current-password" placeholder="sua senha atual">
              </label>
              <label>Nova senha
                <input id="accountNewPasswordInfo" type="password" autocomplete="new-password" placeholder="mínimo 6 caracteres">
              </label>
              <label>Confirmar nova senha
                <input id="accountNewPasswordInfoConfirm" type="password" autocomplete="new-password" placeholder="repita a senha">
              </label>
              <button class="andplay-account-primary account-info-save" type="button" id="accountChangePasswordBtn">Alterar senha</button>
              <p class="account-info-note">Contas Google também podem definir uma senha para acesso por email e senha.</p>
            </div>

            <button class="andplay-account-secondary account-logout-btn" type="button" id="accountLogoutBtn">Sair da conta</button>
          </section>
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

    document.getElementById('accountForgotBtn')?.addEventListener('click', () => {
      const sourceEmail = document.getElementById('accountEmail')?.value.trim() || '';
      const resetEmail = document.getElementById('accountResetEmail');
      if (resetEmail && sourceEmail) resetEmail.value = sourceEmail;
      setAuthView('recovery');
      setStatus('');
      resetEmail?.focus();
    });

    document.getElementById('accountResetBtn')?.addEventListener('click', requestPasswordReset);
    document.getElementById('accountRecoveryBackBtn')?.addEventListener('click', () => {
      setAuthView('login');
      setStatus('');
    });
    document.getElementById('accountUpdatePasswordBtn')?.addEventListener('click', updatePassword);

    document.getElementById('accountSyncBtn')?.addEventListener('click', async () => {
      setStatus('Sincronizando conta...');
      const success = await syncNow();
      await refreshAccountUsage();
      if (success) setStatus('Conta sincronizada.', true);
    });
    document.getElementById('accountLogoutBtn')?.addEventListener('click', signOut);
    document.querySelectorAll('[data-account-subtab]').forEach(button => {
      button.addEventListener('click', () => setAccountSubtab(button.dataset.accountSubtab));
    });
    document.getElementById('accountDisplayNameBtn')?.addEventListener('click', saveDisplayName);
    document.getElementById('accountChangePasswordBtn')?.addEventListener('click', changeAccountPassword);

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
    const user = getCurrentUser();
    const el = document.getElementById('andplayAccountUser');
    if (el) el.textContent = getDisplayName(user) + (user?.email ? ' • ' + user.email : '');
    renderProfile();
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

      client.auth.onAuthStateChange((event, session) => {
        currentSession = session || null;
        renderUser();
        updateAccountUi();

        if (event === 'PASSWORD_RECOVERY') {
          setTimeout(() => {
            openModal();
            setAuthView('update-password');
            setStatus('Digite sua nova senha abaixo.', true);
            document.getElementById('accountNewPassword')?.focus();
          }, 0);
          return;
        }

        setTimeout(() => {
          if (currentSession) syncNow();
        }, 0);
      });
    } catch (error) {
      console.warn('[EPlay Account] Initialization:', error);
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
