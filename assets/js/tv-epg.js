/* EPlay Web TV EPG — ativado somente quando a área de TV é usada. */
(() => {
  'use strict';

  const CACHE_KEY = 'andplay_web_tv_epg_v2';
  const CACHE_TTL_MS = 2 * 60 * 60 * 1000;
  const REFRESH_MS = 2 * 60 * 60 * 1000;
  const EPG_URLS = [
    'https://iptv-epg.org/files/epg-br.xml',
    'https://raw.githubusercontent.com/limaalef/BrazilTVEPG/main/epg.xml'
  ];
  const XSPORTS_URLS = [
    'https://tvmap.com.br/api/Xsports',
    'https://tvmap.com.br/api/Xsports/Amanha'
  ];

  let epgMap = new Map();
  let activated = false;
  let hydrated = false;
  let refreshTimer = null;
  let activeController = null;
  let refreshPromise = null;

  const FAMILY_ALIASES = [
    { test: /^(?!globonews|gloob|globoplay).*globo/, keys: ['globo', 'tvglobo', 'globobrasil', 'globosp', 'globorj'] },
    { test: /^sbt/, keys: ['sbt', 'sbtbrasil', 'sbtsp', 'sbtrj'] },
    { test: /^record(?!news)/, keys: ['record', 'recordbrasil', 'recordtvbrasil', 'recordsp'] },
    { test: /^band(?!news|sports)/, keys: ['band', 'bandbrasil', 'bandsp', 'bandrj'] },
    { test: /^cultura/, keys: ['cultura', 'tvcultura', 'culturabrasil'] },
    { test: /^redetv/, keys: ['redetv', 'redetvsp', 'redetvrj'] },
    { test: /^tvbrasil/, keys: ['tvbrasil', 'ebc'] }
  ];

  function normalizeKey(raw) {
    return String(raw || '').toLowerCase().normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '').replace(/&/g, 'and').replace(/\+/g, 'plus')
      .replace(/[_-]?local$/g, '').replace(/\.br$/g, '')
      .replace(/\b(?:hd|uhd|4k|fast)\b/g, '').replace(/[^a-z0-9]/g, '');
  }

  function aliasesForChannel(ch) {
    const values = new Set();
    const add = value => { const key = normalizeKey(value); if (key) values.add(key); };
    add(ch?.channelSlug); add(ch?.id); add(ch?.name);
    const seed = normalizeKey(ch?.channelSlug || ch?.id || ch?.name);
    FAMILY_ALIASES.forEach(family => { if (family.test.test(seed)) family.keys.forEach(add); });
    return [...values];
  }

  function parseXmltvDate(raw) {
    const value = String(raw || '').trim();
    if (!value) return 0;
    const base = value.slice(0, 14);
    if (!/^\d{14}$/.test(base)) return 0;
    const tzMatch = value.match(/([+-]\d{2}:?\d{2}|Z)$/i);
    if (tzMatch) {
      const iso = base.replace(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/, '$1-$2-$3T$4:$5:$6')
        + (tzMatch[1].toUpperCase() === 'Z' ? 'Z' : tzMatch[1]);
      const parsed = Date.parse(iso);
      if (Number.isFinite(parsed)) return parsed;
    }
    const parsed = Date.parse(base.replace(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/, '$1-$2-$3T$4:$5:$6-03:00'));
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function sanitizeText(value) {
    return String(value || '').replace(/\s+/g, ' ').trim();
  }

  function parseXmltv(xmlText) {
    if (!xmlText || typeof DOMParser === 'undefined') return new Map();
    const doc = new DOMParser().parseFromString(xmlText, 'text/xml');
    if (doc.querySelector('parsererror')) return new Map();

    const now = Date.now();
    const limitStart = now - 2 * 60 * 60 * 1000;
    const limitEnd = now + 36 * 60 * 60 * 1000;
    const map = new Map();

    doc.querySelectorAll('programme').forEach(node => {
      const channel = node.getAttribute('channel');
      const startMs = parseXmltvDate(node.getAttribute('start'));
      const stopMs = parseXmltvDate(node.getAttribute('stop'));
      const title = sanitizeText(node.querySelector('title')?.textContent);
      const desc = sanitizeText(node.querySelector('desc')?.textContent);
      if (!channel || !title || !startMs || !stopMs || stopMs <= startMs) return;
      if (stopMs < limitStart || startMs > limitEnd) return;
      const key = normalizeKey(channel);
      if (!key) return;
      if (!map.has(key)) map.set(key, []);
      map.get(key).push({ title, desc, startMs, stopMs });
    });

    map.forEach(list => list.sort((a, b) => a.startMs - b.startMs));
    return map;
  }

  function mergeMap(source) {
    source.forEach((programs, key) => {
      const current = epgMap.get(key) || [];
      const merged = [...current, ...programs];
      const unique = new Map(merged.map(p => [p.startMs + ':' + p.stopMs + ':' + p.title, p]));
      epgMap.set(key, [...unique.values()].sort((a, b) => a.startMs - b.startMs));
    });
  }

  function serializeCache() {
    return { savedAt: Date.now(), entries: [...epgMap.entries()] };
  }

  function hydrateCache() {
    if (hydrated) return;
    hydrated = true;
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      const parsed = raw ? JSON.parse(raw) : null;
      if (!parsed || !Array.isArray(parsed.entries)) return;
      epgMap = new Map(parsed.entries.map(([key, value]) => [String(key), Array.isArray(value) ? value : []]));
    } catch (error) {
      epgMap = new Map();
    }
  }

  function cacheStillFresh(savedAt) {
    return Date.now() - Number(savedAt || 0) < CACHE_TTL_MS;
  }

  async function fetchText(url, signal) {
    const response = await fetch(url, {
      method: 'GET', signal, cache: 'no-store',
      headers: { Accept: 'application/xml,text/xml,*/*' }
    });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    return response.text();
  }

  async function fetchXsports(signal) {
    const collected = [];
    for (const url of XSPORTS_URLS) {
      try {
        const response = await fetch(url, { signal, cache: 'no-store', headers: { Accept: 'application/json' } });
        if (!response.ok) continue;
        const root = await response.json();
        const exhibitions = Array.isArray(root?.exhibitions) ? root.exhibitions : [];
        exhibitions.forEach(item => {
          const title = sanitizeText(item?.title);
          const startMs = Date.parse(item?.startDate || '');
          const stopMs = Date.parse(item?.endDate || '');
          if (title && Number.isFinite(startMs) && Number.isFinite(stopMs) && stopMs > startMs) {
            collected.push({ title, desc: sanitizeText(item?.upperDescription), startMs, stopMs });
          }
        });
      } catch (error) {
        if (error?.name === 'AbortError') throw error;
      }
    }
    if (!collected.length) return;
    const unique = new Map(collected.map(p => [p.startMs + ':' + p.stopMs + ':' + p.title, p]));
    const list = [...unique.values()].sort((a, b) => a.startMs - b.startMs);
    ['xsports', 'xsport', 'canalisports', 'xsportsbrasil'].forEach(key => epgMap.set(key, list));
  }

  async function refresh() {
    if (!activated || refreshPromise) return refreshPromise;
    refreshPromise = (async () => {
      if (activeController) activeController.abort();
      activeController = new AbortController();
      let loaded = false;
      for (const url of EPG_URLS) {
        try {
          const xml = await fetchText(url, activeController.signal);
          const parsed = parseXmltv(xml);
          if (parsed.size) { mergeMap(parsed); loaded = true; break; }
        } catch (error) {
          if (error?.name === 'AbortError') return false;
        }
      }
      try { await fetchXsports(activeController.signal); }
      catch (error) { if (error?.name === 'AbortError') return false; }
      if (loaded || epgMap.size) {
        try { localStorage.setItem(CACHE_KEY, JSON.stringify(serializeCache())); } catch (error) {}
        window.dispatchEvent(new CustomEvent('eplay:tv-epg-updated'));
      }
      return loaded;
    })().finally(() => { refreshPromise = null; });
    return refreshPromise;
  }

  function findPrograms(ch) {
    const candidates = aliasesForChannel(ch);
    for (const candidate of candidates) {
      const exact = epgMap.get(candidate);
      if (exact?.length) return exact;
    }
    for (const candidate of candidates) {
      if (candidate.length < 5) continue;
      for (const [key, value] of epgMap.entries()) {
        if ((key.startsWith(candidate) || candidate.startsWith(key)) && value.length) return value;
      }
    }
    return [];
  }

  function toClock(ms) {
    return new Date(ms).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  }

  function getSchedule(ch, dateObj) {
    activate();
    const now = (dateObj instanceof Date ? dateObj : new Date()).getTime();
    const programs = findPrograms(ch);
    let current = null;
    let next = null;

    for (const program of programs) {
      if (program.startMs <= now && now < program.stopMs) current = program;
      else if (program.startMs >= now && (!next || program.startMs < next.startMs)) next = program;
    }

    if (current) {
      const duration = Math.max(60000, current.stopMs - current.startMs);
      const elapsed = Math.max(0, now - current.startMs);
      const progress = Math.min(99, Math.max(1, Math.round((elapsed / duration) * 100)));
      return {
        nowTitle: current.title,
        synopsis: current.desc || 'Transmissão ao vivo.',
        start: toClock(current.startMs),
        end: toClock(current.stopMs),
        timeRange: toClock(current.startMs) + ' • ' + toClock(current.stopMs),
        progress,
        remainingMinutes: Math.max(1, Math.ceil((current.stopMs - now) / 60000)),
        nextTitle: next?.title || 'Programação não informada',
        nextStart: next ? toClock(next.startMs) : '--:--'
      };
    }

    if (next) {
      return {
        nowTitle: 'A Seguir: ' + next.title,
        synopsis: next.desc || 'Em instantes na programação.',
        start: toClock(next.startMs),
        end: toClock(next.stopMs),
        timeRange: toClock(next.startMs) + ' • ' + toClock(next.stopMs),
        progress: 0,
        remainingMinutes: Math.max(1, Math.ceil((next.startMs - now) / 60000)),
        nextTitle: next.title,
        nextStart: toClock(next.startMs)
      };
    }

    const rawNow = sanitizeText(ch?.now);
    const rawNext = Array.isArray(ch?.next) ? ch.next[0] : null;
    if (rawNow) {
      return {
        nowTitle: rawNow,
        synopsis: sanitizeText(ch?.synopsis) || 'Transmissão ao vivo.',
        start: 'Ao Vivo',
        end: rawNext?.s || '--:--',
        timeRange: 'Ao Vivo',
        progress: Number(ch?.prog) || 0,
        remainingMinutes: 30,
        nextTitle: sanitizeText(rawNext?.t) || 'Programação contínua',
        nextStart: sanitizeText(rawNext?.s) || '--:--'
      };
    }
    return null;
  }

  function activate() {
    if (!hydrated) hydrateCache();
    if (activated) return;
    activated = true;
    refresh().catch(() => {});
    clearInterval(refreshTimer);
    refreshTimer = setInterval(() => {
      if (activated && !document.hidden) refresh().catch(() => {});
    }, REFRESH_MS);
  }

  function deactivate() {
    activated = false;
    if (refreshTimer !== null) clearInterval(refreshTimer);
    refreshTimer = null;
    if (activeController) activeController.abort();
    activeController = null;
  }

  window.EPlayTvEpg = { activate, deactivate, refresh, getSchedule };
  window.getChannelLiveSchedule = getSchedule;
})();
