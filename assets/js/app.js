const CONFIG = window.ANDPLAY_PUBLIC_CONFIG || {
      server: '',
      user: '',
      pass: ''
    };

    const BATCH_SIZE = 60;
    const API_TIMEOUT_MS = 60000;

    // Estado da aplicação
    let currentMode = 'home'; // 'home', 'movies', 'series' ou 'live'
    let isWatchedView = false;
    let watchedReturnMode = 'home';
    let isFavoritesView = false;
    let favoriteReturnMode = 'home';
    let isHandlingPopstate = false;
    let pendingRoute = null;
    let isRouterNavigating = false;
    let routerInitialized = false;
    const WATCHED_LIMIT = 500;
    const WATCHED_MOVIES_STORAGE_KEY = 'andplay_web_watched_movies_v1';
    const WATCHED_SERIES_STORAGE_KEY = 'andplay_web_watched_series_v1';
    const WATCHED_ACTIVITY_KEY = 'andplay_web_watched_activity_v1';
    const FAVORITES_STORAGE_KEY = 'andplay_web_favorites_v1';

    let fullMoviesCache = null;
    let fullSeriesCache = null;
    let fullLiveCache = null;
    let fullMoviesCacheSavedAt = 0;
    let fullSeriesCacheSavedAt = 0;
    let liveCategories = [];
    let moviePosterMap = new Map(); // Mapa inteligente para recuperar imagens 4K
    let movieCategories = [];
    let seriesCategories = [];
    let _loadingMovieCategoriesPromise = null;
    let _loadingSeriesCategoriesPromise = null;
    let _loadingMoviesPromise = null;
    let _loadingSeriesPromise = null;

    // Gerenciamento e filtragem de Conteúdo Adulto (+18)
    const adultCategoryIds = new Set();

    function isAdultContentEnabled() {
      return localStorage.getItem('andplay_pref_adult_content') === '1';
    }

    function isAdultCategoryName(rawName) {
      if (!rawName || typeof rawName !== 'string') return false;
      const s = rawName.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
      return s.includes('adult') ||
        s.includes('+18') ||
        s.includes('18+') ||
        s.includes('privacy') ||
        s.includes('onlyfans') ||
        s.includes('only fans') ||
        s.includes('xxx') ||
        s.includes('hentai') ||
        s.includes('erotic') ||
        s.includes('porn') ||
        s.includes('playboy') ||
        s.includes('sexy');
    }

    function updateAdultCategoryIds() {
      adultCategoryIds.clear();
      const allCats = [
        ...(Array.isArray(movieCategories) ? movieCategories : []),
        ...(Array.isArray(seriesCategories) ? seriesCategories : [])
      ];
      allCats.forEach(cat => {
        if (cat && cat.category_id && isAdultCategoryName(cat.category_name)) {
          adultCategoryIds.add(String(cat.category_id));
        }
      });
    }

    function isAdultCategoryId(catId) {
      if (!catId) return false;
      if (catId === 'other' || catId === 'adult') return true;
      return adultCategoryIds.has(String(catId));
    }

    function isAdultItem(item) {
      if (!item) return false;
      const source = item.primaryItem || item.item || item;

      const cId = String(item.category_id || source.category_id || '');
      if (cId && adultCategoryIds.has(cId)) return true;

      const cIds = item.category_ids || source.category_ids;
      if (Array.isArray(cIds) && cIds.some(id => adultCategoryIds.has(String(id)))) return true;

      const catName = item.category_name || source.category_name || item.categoryLabel || source.categoryLabel || item.subCategory || source.subCategory || '';
      if (catName && isAdultCategoryName(catName)) return true;

      if ((item.categoryKey === 'other' || source.categoryKey === 'other') && (item.subCategory === 'Adulto' || isAdultCategoryName(item.name || source.name))) return true;

      const genre = item.genre || source.genre || item.genre_name || source.genre_name || '';
      if (genre && isAdultCategoryName(genre)) return true;

      return false;
    }

    // Cache persistente do catálogo: mantém a última versão disponível entre sessões.
    const CATALOG_DB_NAME = 'andplay_web_cache_v1';
    const CATALOG_DB_VERSION = 1;
    const CATALOG_STORE = 'catalogs';
    const CATALOG_TTL_MS = 6 * 60 * 60 * 1000;
    const LIVE_CATALOG_TTL_MS = 2 * 60 * 1000;
    let catalogDbPromise = null;

    function openCatalogDb() {
      if (catalogDbPromise) return catalogDbPromise;
      catalogDbPromise = new Promise((resolve, reject) => {
        if (!window.indexedDB) {
          resolve(null);
          return;
        }
        const request = indexedDB.open(CATALOG_DB_NAME, CATALOG_DB_VERSION);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains(CATALOG_STORE)) {
            db.createObjectStore(CATALOG_STORE);
          }
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      }).catch(() => null);
      return catalogDbPromise;
    }

    async function readCatalogCache(type) {
      const db = await openCatalogDb();
      if (!db) return null;
      return new Promise(resolve => {
        try {
          const tx = db.transaction(CATALOG_STORE, 'readonly');
          const request = tx.objectStore(CATALOG_STORE).get(type);
          request.onsuccess = () => {
            const value = request.result;
            const ttl = type === 'live' ? LIVE_CATALOG_TTL_MS : CATALOG_TTL_MS;
            const savedAt = Number(value?.savedAt || 0);
            if (type === 'movies') fullMoviesCacheSavedAt = savedAt;
            if (type === 'series') fullSeriesCacheSavedAt = savedAt;
            if (!value || !Array.isArray(value.data) || Date.now() - savedAt > ttl) {
              resolve(null);
              return;
            }
            resolve(value.data);
          };
          request.onerror = () => resolve(null);
        } catch (e) {
          resolve(null);
        }
      });
    }

    async function readCatalogCacheStale(type) {
      const db = await openCatalogDb();
      if (!db) return null;
      return new Promise(resolve => {
        try {
          const tx = db.transaction(CATALOG_STORE, 'readonly');
          const request = tx.objectStore(CATALOG_STORE).get(type);
          request.onsuccess = () => {
            const value = request.result;
            const savedAt = Number(value?.savedAt || 0);
            if (type === 'movies') fullMoviesCacheSavedAt = savedAt;
            if (type === 'series') fullSeriesCacheSavedAt = savedAt;
            resolve(value && Array.isArray(value.data) ? value.data : null);
          };
          request.onerror = () => resolve(null);
        } catch (e) {
          resolve(null);
        }
      });
    }

    async function writeCatalogCache(type, data, meta = {}) {
      if (!Array.isArray(data) || data.length === 0) return;
      const db = await openCatalogDb();
      if (!db) return;
      const savedAt = Date.now();
      if (type === 'movies') fullMoviesCacheSavedAt = savedAt;
      if (type === 'series') fullSeriesCacheSavedAt = savedAt;
      await new Promise(resolve => {
        try {
          const tx = db.transaction(CATALOG_STORE, 'readwrite');
          tx.oncomplete = () => resolve();
          tx.onerror = () => resolve();
          tx.onabort = () => resolve();
          tx.objectStore(CATALOG_STORE).put({ savedAt, data, ...meta }, type);
        } catch (e) {
          resolve();
        }
      });
    }

    let currentMediaList = [];
    let currentFilteredList = [];
    let renderedCount = 0;
    let activeVideoUrl = '';
    let searchDebounceTimer = null;
    let globalSearchRequestId = 0;
    let globalSearchCatalogCache = null;
    let infiniteScrollRaf = null;
    let liveLoadGeneration = 0;

    // Home: novidades e histórico mistos.
    let homeCatalogPromise = null;
    let homeCatalogRenderTimer = null;
    let viewTransitionTimer = null;
    let homeFeaturedItems = [];
    let cachedHomeFeaturedItems = null;
    let cachedHomeCatalogRails = { movie: null, series: null, interleaved: null };
    let cachedHomeRecommendations = null;
    let homeFeaturedIndex = 0;
    let homeFeaturedTimer = null;
    let homeDisplayUsage = new Map();
    let lastRenderedRailItemKeys = new Set();
    let homeLastRevalidationAt = 0;
    let homeWatchedCatalogFallback = { movies: [], series: [] };
    const HOME_REVALIDATE_COOLDOWN_MS = 5 * 60 * 1000;
    const HOME_FEATURED_LIMIT = 10;
    const HOME_WATCHED_LIMIT = 12;
    const HOME_MAX_APPEARANCES_PER_TITLE = 3;

    // Instância HLS ativa
    let activeHls = null;

    // Cache da série aberta atualmente
    let currentSeriesData = null;

    const elements = {
      logoBtn: document.getElementById('logoBtn'),
      tabHomeBtn: document.getElementById('tabHomeBtn'),
      tabMoviesBtn: document.getElementById('tabMoviesBtn'),
      mobileHomeBtn: document.getElementById('mobileHomeBtn'),
      mobileMoviesBtn: document.getElementById('mobileMoviesBtn'),
      mobileSeriesBtn: document.getElementById('mobileSeriesBtn'),
      mobileLiveBtn: document.getElementById('mobileLiveBtn'),
      mobileWatchedBtn: document.getElementById('mobileWatchedBtn'),
      mobileFavoritesBtn: document.getElementById('mobileFavoritesBtn'),
      mobileAccountBtn: document.getElementById('mobileAccountBtn'),
      homeDashboard: document.getElementById('homeDashboard'),
      homeFeaturedTrack: document.getElementById('homeFeaturedTrack'),
      homeFeaturedPrev: document.getElementById('homeFeaturedPrev'),
      homeFeaturedNext: document.getElementById('homeFeaturedNext'),
      homeFeaturedDots: document.getElementById('homeFeaturedDots'),
      homeFeaturedLoading: document.getElementById('homeFeaturedLoading'),
      homeRailsLoading: document.getElementById('homeRailsLoading'),
      homeWatchedSection: document.getElementById('homeWatchedSection'),
      homeWatchedRail: document.getElementById('homeWatchedRail'),
      homeWatchedPrev: document.getElementById('homeWatchedPrev'),
      homeWatchedAllBtn: document.getElementById('homeWatchedAllBtn'),
      homeWatchedNext: document.getElementById('homeWatchedNext'),
      homeRecommendationsSection: document.getElementById('homeRecommendationsSection'),
      homeRecommendationsRail: document.getElementById('homeRecommendationsRail'),
      homeRecommendationsNext: document.getElementById('homeRecommendationsNext'),
      homeRecommendationsPrev: document.getElementById('homeRecommendationsPrev'),
      homeSeriesSection: document.getElementById('homeSeriesSection'),
      homeSeriesRails: document.getElementById('homeSeriesRails'),
      homeMoviesSection: document.getElementById('homeMoviesSection'),
      homeMoviesRails: document.getElementById('homeMoviesRails'),
      contentPage: document.getElementById('contentPage'),
      contentPageBackBtn: document.getElementById('contentPageBackBtn'),
      contentPageKicker: document.getElementById('contentPageKicker'),
      contentPageBackdrop: document.getElementById('contentPageBackdrop'),
      contentPagePoster: document.getElementById('contentPagePoster'),
      contentPageType: document.getElementById('contentPageType'),
      contentPageTitle: document.getElementById('contentPageTitle'),
      contentPageMeta: document.getElementById('contentPageMeta'),
      contentPageGenres: document.getElementById('contentPageGenres'),
      contentPagePlot: document.getElementById('contentPagePlot'),
      contentPageDirector: document.getElementById('contentPageDirector'),
      contentPageCast: document.getElementById('contentPageCast'),
      contentMoviePanel: document.getElementById('contentMoviePanel'),
      contentMovieVersions: document.getElementById('contentMovieVersions'),
      contentSeriesPanel: document.getElementById('contentSeriesPanel'),
      contentSeriesHeading: document.getElementById('contentSeriesHeading'),
      contentSeasonSelect: document.getElementById('contentSeasonSelect'),
      contentSeriesVersionSwitcher: document.getElementById('contentSeriesVersionSwitcher'),
      contentEpisodesList: document.getElementById('contentEpisodesList'),
      contentRelatedPanel: document.getElementById('contentRelatedPanel'),
      contentRelatedHeading: document.getElementById('contentRelatedHeading'),
      contentRelatedGrid: document.getElementById('contentRelatedGrid'),
      tabSeriesBtn: document.getElementById('tabSeriesBtn'),
      tabLiveBtn: document.getElementById('tabLiveBtn'),
      tabWatchedBtn: document.getElementById('tabWatchedBtn'),
      tabFavoritesBtn: document.getElementById('tabFavoritesBtn'),
      contentFavoriteBtn: document.getElementById('contentFavoriteBtn'),
      categorySelect: document.getElementById('categorySelect'),
      searchInput: document.getElementById('searchInput'),
      resetCategoryBtn: document.getElementById('resetCategoryBtn'),
      mediaGrid: document.getElementById('mediaGrid'),
      loading: document.getElementById('loading'),
      loadingText: document.getElementById('loadingText'),
      categoryLabel: document.getElementById('categoryLabel'),
      mediaCount: document.getElementById('mediaCount'),
      loadMoreContainer: document.getElementById('loadMoreContainer'),
      loadMoreBtn: document.getElementById('loadMoreBtn'),

      // Movies Hub
      moviesHub: document.getElementById('moviesHub'),
      moviesHero: document.getElementById('moviesHero'),
      moviesHeroTrack: document.getElementById('moviesHeroTrack'),
      moviesHeroPrev: document.getElementById('moviesHeroPrev'),
      moviesHeroNext: document.getElementById('moviesHeroNext'),
      moviesHeroDots: document.getElementById('moviesHeroDots'),
      moviesHeroLoading: document.getElementById('moviesHeroLoading'),
      moviesGenrePills: document.getElementById('moviesGenrePills'),
      moviesViewCuratedBtn: document.getElementById('moviesViewCuratedBtn'),
      moviesViewGridBtn: document.getElementById('moviesViewGridBtn'),
      moviesSurpriseBtn: document.getElementById('moviesSurpriseBtn'),
      moviesSortWrap: document.getElementById('moviesSortWrap'),
      moviesSortSelect: document.getElementById('moviesSortSelect'),
      moviesFilter4kBtn: document.getElementById('moviesFilter4kBtn'),
      moviesFilterDubBtn: document.getElementById('moviesFilterDubBtn'),
      moviesFilterLegBtn: document.getElementById('moviesFilterLegBtn'),
      moviesCuratedRails: document.getElementById('moviesCuratedRails'),

      // Series Hub
      seriesHub: document.getElementById('seriesHub'),
      seriesHero: document.getElementById('seriesHero'),
      seriesHeroTrack: document.getElementById('seriesHeroTrack'),
      seriesHeroPrev: document.getElementById('seriesHeroPrev'),
      seriesHeroNext: document.getElementById('seriesHeroNext'),
      seriesHeroDots: document.getElementById('seriesHeroDots'),
      seriesHeroLoading: document.getElementById('seriesHeroLoading'),
      seriesGenrePills: document.getElementById('seriesGenrePills'),
      seriesViewCuratedBtn: document.getElementById('seriesViewCuratedBtn'),
      seriesViewGridBtn: document.getElementById('seriesViewGridBtn'),
      seriesSurpriseBtn: document.getElementById('seriesSurpriseBtn'),
      seriesSortWrap: document.getElementById('seriesSortWrap'),
      seriesSortSelect: document.getElementById('seriesSortSelect'),
      seriesFilterDubBtn: document.getElementById('seriesFilterDubBtn'),
      seriesFilterLegBtn: document.getElementById('seriesFilterLegBtn'),
      seriesCuratedRails: document.getElementById('seriesCuratedRails'),

      // Live Hub
      liveHub: document.getElementById('liveHub'),
      liveCategoryPills: document.getElementById('liveCategoryPills'),
      liveFilterAllBtn: document.getElementById('liveFilterAllBtn'),
      liveFilterNowBtn: document.getElementById('liveFilterNowBtn'),
      liveFilterMatchesBtn: document.getElementById('liveFilterMatchesBtn'),
      liveFilterChannelsBtn: document.getElementById('liveFilterChannelsBtn'),
      liveSpotlightSection: document.getElementById('liveSpotlightSection'),
      liveSpotlightTrack: document.getElementById('liveSpotlightTrack'),

      // Favorites & Watched Hubs
      favoritesHub: document.getElementById('favoritesHub'),
      favTotalBadge: document.getElementById('favTotalBadge'),
      favCountAll: document.getElementById('favCountAll'),
      favCountMovies: document.getElementById('favCountMovies'),
      favCountSeries: document.getElementById('favCountSeries'),
      favClearBtn: document.getElementById('favClearBtn'),
      favPillAll: document.getElementById('favPillAll'),
      favPillMovies: document.getElementById('favPillMovies'),
      favPillSeries: document.getElementById('favPillSeries'),

      watchedHub: document.getElementById('watchedHub'),
      watchedTotalBadge: document.getElementById('watchedTotalBadge'),
      watchedSyncNotice: document.getElementById('watchedSyncNotice'),
      watchedSyncNoticeBtn: document.getElementById('watchedSyncNoticeBtn'),
      watchedCountAll: document.getElementById('watchedCountAll'),
      watchedCountResume: document.getElementById('watchedCountResume'),
      watchedCountDone: document.getElementById('watchedCountDone'),
      watchedCountMovies: document.getElementById('watchedCountMovies'),
      watchedCountSeries: document.getElementById('watchedCountSeries'),
      watchedClearBtn: document.getElementById('watchedClearBtn'),
      watchedPillAll: document.getElementById('watchedPillAll'),
      watchedPillResume: document.getElementById('watchedPillResume'),
      watchedPillDone: document.getElementById('watchedPillDone'),
      watchedPillMovies: document.getElementById('watchedPillMovies'),
      watchedPillSeries: document.getElementById('watchedPillSeries'),

      // User Dashboard & Navigation
      tabUserBtn: document.getElementById('tabUserBtn'),
      accountBtn: document.getElementById('accountBtn'),
      userDashboard: document.getElementById('userDashboard'),
      userAvatarDisplay: document.getElementById('userAvatarDisplay'),
      userAvatarEditBtn: document.getElementById('userAvatarEditBtn'),
      userAvatarPicker: document.getElementById('userAvatarPicker'),
      userProfileName: document.getElementById('userProfileName'),
      userProfileBadge: document.getElementById('userProfileBadge'),
      userProfileEmail: document.getElementById('userProfileEmail'),
      userHeroSyncBtn: document.getElementById('userHeroSyncBtn'),
      userHeroAuthBtn: document.getElementById('userHeroAuthBtn'),
      statWatchTime: document.getElementById('statWatchTime'),
      statMoviesCount: document.getElementById('statMoviesCount'),
      statSeriesCount: document.getElementById('statSeriesCount'),
      statFavCount: document.getElementById('statFavCount'),
      statLiveCount: document.getElementById('statLiveCount'),
      userTabBtnAccount: document.getElementById('userTabBtnAccount'),
      userTabBtnPreferences: document.getElementById('userTabBtnPreferences'),
      userTabBtnShortcuts: document.getElementById('userTabBtnShortcuts'),
      userTabBtnStorage: document.getElementById('userTabBtnStorage'),
      userPanelAccount: document.getElementById('userPanelAccount'),
      userPanelPreferences: document.getElementById('userPanelPreferences'),
      userPanelShortcuts: document.getElementById('userPanelShortcuts'),
      userPanelStorage: document.getElementById('userPanelStorage'),
      userAccountConnectedBlock: document.getElementById('userAccountConnectedBlock'),
      userConnectedEmail: document.getElementById('userConnectedEmail'),
      userConnectedId: document.getElementById('userConnectedId'),
      userConnectedSyncStatus: document.getElementById('userConnectedSyncStatus'),
      userChangePassBtn: document.getElementById('userChangePassBtn'),
      userLogoutBtn: document.getElementById('userLogoutBtn'),
      userDeleteDataBtn: document.getElementById('userDeleteDataBtn'),
      userDeleteAccountBtn: document.getElementById('userDeleteAccountBtn'),
      userAccountAuthBlock: document.getElementById('userAccountAuthBlock'),
      userAuthTabLogin: document.getElementById('userAuthTabLogin'),
      userAuthTabRegister: document.getElementById('userAuthTabRegister'),
      userAuthTabRecovery: document.getElementById('userAuthTabRecovery'),
      userAuthEmailInput: document.getElementById('userAuthEmailInput'),
      userAuthPassGroup: document.getElementById('userAuthPassGroup'),
      userAuthPassInput: document.getElementById('userAuthPassInput'),
      userAuthStatusMsg: document.getElementById('userAuthStatusMsg'),
      userAuthSubmitBtn: document.getElementById('userAuthSubmitBtn'),
      userAuthGoogleGroup: document.getElementById('userAuthGoogleGroup'),
      userAuthGoogleBtn: document.getElementById('userAuthGoogleBtn'),

      // Dedicated Auth Screen (Login Obrigatório)
      authScreen: document.getElementById('authScreen'),
      authLoadingOverlay: document.getElementById('authLoadingOverlay'),
      authScreenTabLogin: document.getElementById('authScreenTabLogin'),
      authScreenTabRegister: document.getElementById('authScreenTabRegister'),
      authScreenTabRecovery: document.getElementById('authScreenTabRecovery'),
      authScreenEmailInput: document.getElementById('authScreenEmailInput'),
      authScreenPassGroup: document.getElementById('authScreenPassGroup'),
      authScreenPassInput: document.getElementById('authScreenPassInput'),
      authScreenStatusMsg: document.getElementById('authScreenStatusMsg'),
      authScreenSubmitBtn: document.getElementById('authScreenSubmitBtn'),
      authScreenGoogleGroup: document.getElementById('authScreenGoogleGroup'),
      authScreenGoogleBtn: document.getElementById('authScreenGoogleBtn'),
      userPrefSkipIntro: document.getElementById('userPrefSkipIntro'),
      userPrefAutoNext: document.getElementById('userPrefAutoNext'),
      userPrefTvMode: document.getElementById('userPrefTvMode'),
      userPrefAdultContent: document.getElementById('userPrefAdultContent'),
      userPrefQualitySelect: document.getElementById('userPrefQualitySelect'),
      userPrefAudioSelect: document.getElementById('userPrefAudioSelect'),
      userShortcutWatchedCard: document.getElementById('userShortcutWatchedCard'),
      userShortcutFavCard: document.getElementById('userShortcutFavCard'),
      userShortcutLiveCard: document.getElementById('userShortcutLiveCard'),
      userStorageUsage: document.getElementById('userStorageUsage'),
      userClearCacheBtn: document.getElementById('userClearCacheBtn'),
      userExportFavsBtn: document.getElementById('userExportFavsBtn'),
      userResetAllDataBtn: document.getElementById('userResetAllDataBtn'),

      // Video Modal
      videoModal: document.getElementById('videoModal'),
      modalTitle: document.getElementById('modalTitle'),
      videoPlayer: document.getElementById('videoPlayer'),
      subtitleOverlay: document.getElementById('eplaySubtitleOverlay'),
      embedPlayer: document.getElementById('embedPlayer'),
      closeVideoModal: document.getElementById('closeVideoModal'),
      downloadBtn: document.getElementById('downloadBtn'),
      skipIntroBtn: document.getElementById('skipIntroBtn'),
      skipIntroSource: document.getElementById('skipIntroSource'),
      skipIntroAutoLabel: document.getElementById('skipIntroAutoLabel'),
      skipIntroAutoToggle: document.getElementById('skipIntroAutoToggle'),
      modalFormat: document.getElementById('modalFormat'),
      subOptionsPanel: document.getElementById('subOptionsPanel'),
      toggleSubPanelBtn: document.getElementById('toggleSubPanelBtn'),
      closeSubPanelBtn: document.getElementById('closeSubPanelBtn'),
      subCandidateSelect: document.getElementById('subCandidateSelect'),
      searchCustomSubBtn: document.getElementById('searchCustomSubBtn'),
      subSelect: document.getElementById('subSelect'),
      subSyncControls: document.getElementById('subSyncControls'),
      subOffsetDisplay: document.getElementById('subOffsetDisplay'),
      subDelayMinusBtn: document.getElementById('subDelayMinusBtn'),
      subDelayPlusBtn: document.getElementById('subDelayPlusBtn'),
      subFileInput: document.getElementById('subFileInput'),
      audioSyncControls: document.getElementById('audioSyncControls'),
      audioOffsetInput: document.getElementById('audioOffsetInput'),
      audioDelayMinusLargeBtn: document.getElementById('audioDelayMinusLargeBtn'),
      audioDelayMinusBtn: document.getElementById('audioDelayMinusBtn'),
      audioDelayPlusBtn: document.getElementById('audioDelayPlusBtn'),
      audioDelayPlusLargeBtn: document.getElementById('audioDelayPlusLargeBtn'),
      audioSyncResetBtn: document.getElementById('audioSyncResetBtn'),
      hybridModeBadge: document.getElementById('hybridModeBadge'),
      toggleAudioSyncBtn: document.getElementById('toggleAudioSyncBtn'),
      movieVersionSwitcher: document.getElementById('movieVersionSwitcher'),
      panelVersionSection: document.getElementById('panelVersionSection'),
      panelVersionSwitcher: document.getElementById('panelVersionSwitcher'),
      panelVersionHint: document.getElementById('panelVersionHint'),
      syncLiveBtn: document.getElementById('syncLiveBtn'),
      toggleLatencyModeBtn: document.getElementById('toggleLatencyModeBtn'),
      liveSyncToast: document.getElementById('liveSyncToast'),
      videoErrorOverlay: document.getElementById('videoErrorOverlay'),
      videoErrorTitle: document.getElementById('videoErrorTitle'),
      videoErrorMessage: document.getElementById('videoErrorMessage'),
      videoErrorActions: document.getElementById('videoErrorActions'),

      // Movie Info Sidebar (Painel Lateral Esquerdo)
      movieInfoSidebar: document.getElementById('movieInfoSidebar'),
      toggleMovieInfoBtn: document.getElementById('toggleMovieInfoBtn'),
      closeInfoSidebarBtn: document.getElementById('closeInfoSidebarBtn'),
      sidebarPoster: document.getElementById('sidebarPoster'),
      sidebarTitle: document.getElementById('sidebarTitle'),
      sidebarYear: document.getElementById('sidebarYear'),
      sidebarMetaDot: document.getElementById('sidebarMetaDot'),
      sidebarDuration: document.getElementById('sidebarDuration'),
      sidebarRating: document.getElementById('sidebarRating'),
      sidebarGenres: document.getElementById('sidebarGenres'),
      sidebarPlot: document.getElementById('sidebarPlot'),
      sidebarDirector: document.getElementById('sidebarDirector'),
      sidebarDirectorContainer: document.getElementById('sidebarDirectorContainer'),
      sidebarCast: document.getElementById('sidebarCast'),
      sidebarCastContainer: document.getElementById('sidebarCastContainer'),

      // Movie Version Modal
      movieVersionModal: document.getElementById('movieVersionModal'),
      closeMovieVersionModal: document.getElementById('closeMovieVersionModal'),
      versionModalPoster: document.getElementById('versionModalPoster'),
      versionModalTitle: document.getElementById('versionModalTitle'),
      versionModalMeta: document.getElementById('versionModalMeta'),
      versionOptionsList: document.getElementById('versionOptionsList'),

      // IMDb Visual Search Modal
      imdbSearchModal: document.getElementById('imdbSearchModal'),
      closeImdbSearchModal: document.getElementById('closeImdbSearchModal'),
      imdbSearchTargetDesc: document.getElementById('imdbSearchTargetDesc'),
      imdbSearchType: document.getElementById('imdbSearchType'),
      imdbSearchInput: document.getElementById('imdbSearchInput'),
      doImdbSearchBtn: document.getElementById('doImdbSearchBtn'),
      imdbMemoryBanner: document.getElementById('imdbMemoryBanner'),
      imdbMemoryInfo: document.getElementById('imdbMemoryInfo'),
      clearMemoryBtn: document.getElementById('clearMemoryBtn'),
      imdbSearchResults: document.getElementById('imdbSearchResults'),

      // Series Modal
      seriesModal: document.getElementById('seriesModal'),
      closeSeriesModal: document.getElementById('closeSeriesModal'),
      seriesModalTitle: document.getElementById('seriesModalTitle'),
      seriesPoster: document.getElementById('seriesPoster'),
      seriesTitle: document.getElementById('seriesTitle'),
      seriesRating: document.getElementById('seriesRating'),
      seriesGenre: document.getElementById('seriesGenre'),
      seriesYear: document.getElementById('seriesYear'),
      seriesPlot: document.getElementById('seriesPlot'),
      seasonSelect: document.getElementById('seasonSelect'),
      seriesVersionSwitcher: document.getElementById('seriesVersionSwitcher'),
      episodesList: document.getElementById('episodesList')
    };

    // ==========================================
    // FUNÇÕES DE TRATAMENTO DE TÍTULO E IMAGEM (4K)
    // ==========================================

    // Remove numeração (ex: '1 - '), '4K', tags [HDR], [DV], anos (2001) para parear com a versão que tem capa
    function cleanTitleKey(title) {
      if (!title) return '';
      return title
        .replace(/^[0-9]+\s*[-–—]\s*/, '')      // '1 - ', '02 - '
        .replace(/\b4k\b/gi, '')                // '4K', '4k'
        .replace(/\[.*?\]/g, '')                // '[HDR]', '[DV]', '[DUBLADO]', '[L]'
        .replace(/\(.*?\)/g, '')                // '(2001)', '(2024)'
        .toLowerCase()
        .normalize('NFD').replace(/[\u0300-\u036f]/g, '') // remove acentos
        .replace(/[^a-z0-9\s]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    }

    // Normalizador de busca (permite buscar com ou sem acentos, ignorando 4K ou prefixos)
    function normalizeSearch(text) {
      if (!text) return '';
      return text
        .toLowerCase()
        .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
        .replace(/[^a-z0-9\s]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    }

    // Correções de metadados para itens mal etiquetados no servidor IPTV
    const KNOWN_STREAM_CORRECTIONS = {
      // 1. A Bela e a Fera (1991) - Animação Clássica Disney (Cat 621 Animação - estava incorretamente como 2017)
      4443349: {
        name: 'A Bela e a Fera (1991)',
        year: '1991',
        poster: 'https://images.metahub.space/poster/medium/tt0101414/img',
        imdbId: 'tt0101414'
      },
      // 2. A Bela e a Fera (2014) - Versão Francesa com Léa Seydoux e Vincent Cassel (Cat 622 Fantasia, 113 min - estava como 2017)
      4428986: {
        name: 'A Bela e a Fera (2014)',
        year: '2014',
        poster: 'https://images.metahub.space/poster/medium/tt2316801/img',
        imdbId: 'tt2316801'
      },
      // 3. Vamos, Time! (2024) - Comédia mexicana "Gracias, equipo" (estava incorretamente como "Time!" com capa de Questão de Tempo no servidor IPTV)
      6071584: {
        name: 'Vamos, Time! (2024)',
        year: '2024',
        originalName: 'Gracias, equipo / Time!',
        poster: 'https://images.metahub.space/poster/medium/tt38616652/img',
        imdbId: 'tt38616652'
      },
      6071588: {
        name: 'Vamos, Time! [L] (2024)',
        year: '2024',
        originalName: 'Gracias, equipo / Time!',
        poster: 'https://images.metahub.space/poster/medium/tt38616652/img',
        imdbId: 'tt38616652'
      },
      6071596: {
        name: 'Vamos, Time! [Lançamento] (2024)',
        year: '2024',
        originalName: 'Gracias, equipo / Time!',
        poster: 'https://images.metahub.space/poster/medium/tt38616652/img',
        imdbId: 'tt38616652'
      },
      6072337: {
        name: 'Vamos, Time! 4K [DV][HDR] (2024)',
        year: '2024',
        originalName: 'Gracias, equipo / Time!',
        poster: 'https://images.metahub.space/poster/medium/tt38616652/img',
        imdbId: 'tt38616652'
      },
      // 4. Questão de Tempo (2013) - Romance com Rachel McAdams e Domhnall Gleeson (título original: "About Time")
      4425572: {
        name: 'Questão de Tempo (2013)',
        year: '2013',
        originalName: 'About Time',
        poster: 'https://image.tmdb.org/t/p/w600_and_h900_bestv2/uqEzxvGDYNzoQE7rayv7gRXBomt.jpg',
        imdbId: 'tt2194499'
      },
      // 5. A Morte de Robin Hood (2026) - Ação/Aventura com Hugh Jackman e Jodie Comer (The Death of Robin Hood)
      5976936: {
        name: 'A Morte de Robin Hood [L] (2026)',
        year: '2026',
        imdbId: 'tt32273171'
      },
      6078673: {
        name: 'A Morte de Robin Hood [Lançamento] (2026)',
        year: '2026',
        imdbId: 'tt32273171'
      },
      6078678: {
        name: 'A Morte de Robin Hood (2026)',
        year: '2026',
        imdbId: 'tt32273171'
      },
      6246677: {
        name: 'A Morte de Robin Hood 4K (2026)',
        year: '2026',
        imdbId: 'tt32273171'
      }
    };

    // Recupera a melhor imagem para um filme (Resolve a questão do Harry Potter e filmes 4K sem capa)
    function getBestPosterUrl(movie) {
      if (!movie) return '';
      const streamId = movie.stream_id || (movie.primaryItem && movie.primaryItem.stream_id);
      if (streamId) {
        const userPoster = localStorage.getItem(`andplay_poster_override_${streamId}`);
        if (userPoster) return userPoster;
        if (KNOWN_STREAM_CORRECTIONS[streamId]?.poster) {
          return KNOWN_STREAM_CORRECTIONS[streamId].poster;
        }
      }
      if (movie.poster && movie.poster.startsWith('http')) return movie.poster;
      if (movie.stream_icon && movie.stream_icon.startsWith('http')) return movie.stream_icon;
      if (movie.primaryItem) {
        if (movie.primaryItem.poster && movie.primaryItem.poster.startsWith('http')) return movie.primaryItem.poster;
        if (movie.primaryItem.stream_icon && movie.primaryItem.stream_icon.startsWith('http')) return movie.primaryItem.stream_icon;
      }
      const raw = movie.name || movie.title || (movie.primaryItem && (movie.primaryItem.name || movie.primaryItem.title)) || '';
      const clean = cleanTitleKey(raw);
      const yMatch = raw.match(/\((19\d\d|20\d\d)\)/);
      const y = yMatch ? yMatch[1] : (movie.year ? String(movie.year).trim() : '');
      const keyWithYear = y ? `${clean}_${y}` : clean;
      if (keyWithYear && moviePosterMap.has(keyWithYear)) {
        return moviePosterMap.get(keyWithYear);
      }
      if (clean && moviePosterMap.has(clean)) {
        return moviePosterMap.get(clean);
      }
      return '';
    }

    // Detecta se o navegador/dispositivo suporta nativamente decodificação de HEVC / H.265 em MP4
    let _hevcSupportCached = null;
    function isHevcSupported() {
      if (_hevcSupportCached !== null) return _hevcSupportCached;
      try {
        const video = document.createElement('video');
        if (!video || typeof video.canPlayType !== 'function') {
          _hevcSupportCached = false;
          return false;
        }
        const hvc1 = video.canPlayType('video/mp4; codecs="hvc1.1.6.L93.B0"');
        const hev1 = video.canPlayType('video/mp4; codecs="hev1.1.6.L93.B0"');
        _hevcSupportCached = Boolean((hvc1 && hvc1 !== '') || (hev1 && hev1 !== ''));
        return _hevcSupportCached;
      } catch (_) {
        _hevcSupportCached = false;
        return false;
      }
    }

    // Detecta se uma versão é Dublada, Legendada, 4K Dublada ou 4K Legendada
    function detectMovieVersion(item) {
      const name = item.name || item.title || '';
      const catId = String(item.category_id || '');
      const is4K = (catId === '765') || /\b4k\b/i.test(name) || /\[(?:HDR|DV|Hybrid)\]/i.test(name);
      const isLeg = (catId === '630') || /\[\s*(?:L|LEG|LEGENDADO)\s*\]/i.test(name) || /\b(legendado)\b/i.test(name);

      if (is4K) {
        if (isLeg) return { type: '4k_leg', label: '4K Legendado', badge: '4K LEG', icon: '✨', desc: 'Resolução 4K Ultra HD • Áudio Original com Legenda' };
        return { type: '4k_dub', label: '4K Ultra HD', badge: '4K DUB', icon: '✨', desc: 'Resolução 4K Ultra HD • Dublado em Português' };
      }
      if (isLeg) {
        return { type: 'legendado', label: 'Legendado', badge: 'LEG', icon: '💬', desc: 'Áudio Original com Legendas em Português' };
      }
      return { type: 'dublado', label: 'Dublado', badge: 'DUB', icon: '🔊', desc: 'Áudio Dublado em Português' };
    }

    // Gera todas as versões reproduzíveis de um filme, sintetizando opções híbridas quando aplicável
    function getMovieAllPlayableVersions(groupOrMovie, rawVersions) {
      const baseVersions = (rawVersions && rawVersions.length > 0)
        ? [...rawVersions]
        : (groupOrMovie?.versions && groupOrMovie.versions.length > 0)
          ? [...groupOrMovie.versions]
          : [{
              item: groupOrMovie,
              versionInfo: (typeof detectMovieVersion === 'function') ? detectMovieVersion(groupOrMovie) : { type: 'dublado', label: 'Dublado', badge: 'DUB', icon: '🔊', desc: 'Áudio Dublado em Português' },
              streamId: groupOrMovie?.stream_id || groupOrMovie?.streamId,
              ext: groupOrMovie?.container_extension || 'mp4'
            }];

      const fourKLegVer = baseVersions.find(v => v.versionInfo && v.versionInfo.type === '4k_leg');
      const fourKDubVer = baseVersions.find(v => v.versionInfo && v.versionInfo.type === '4k_dub');
      const legVer = baseVersions.find(v => v.versionInfo && v.versionInfo.type === 'legendado');
      const dubVer = baseVersions.find(v => v.versionInfo && v.versionInfo.type === 'dublado');

      // Se NÃO houver 4K Legendado nativo, mas existirem 4K Dublado e Legendado:
      // sintetiza a versão Híbrida 4K Legendado (Vídeo 4K + Áudio Legendado)
      if (!fourKLegVer && fourKDubVer && legVer) {
        const alreadyHasHybrid = baseVersions.some(v => v.isHybrid && v.hybridType === '4k_leg_hybrid');
        if (!alreadyHasHybrid) {
          baseVersions.push({
            isHybrid: true,
            hybridType: '4k_leg_hybrid',
            isHybrid4kLeg: true,
            videoVersion: fourKDubVer,
            audioVersion: legVer,
            streamId: `hybrid_4k_leg_${fourKDubVer.streamId}_${legVer.streamId}`,
            ext: fourKDubVer.ext || 'mp4',
            item: fourKDubVer.item,
            versionInfo: {
              type: '4k_leg_hybrid',
              label: '4K Legendado (Híbrido)',
              badge: '4K HÍBRIDO',
              icon: '✨',
              desc: 'Vídeo 4K Ultra HD + Áudio Legendado Sincronizado'
            }
          });
        }
      }

      // Se existirem Legendado e Dublado: sintetiza a opção de Vídeo Legendado (com legenda fixa) + Áudio Dublado
      if (legVer && dubVer) {
        const alreadyHasLegDub = baseVersions.some(v => v.isHybrid && v.hybridType === 'leg_dub_hybrid');
        if (!alreadyHasLegDub) {
          baseVersions.push({
            isHybrid: true,
            hybridType: 'leg_dub_hybrid',
            isHybridLegDub: true,
            videoVersion: legVer,
            audioVersion: dubVer,
            streamId: `hybrid_leg_dub_${legVer.streamId}_${dubVer.streamId}`,
            ext: legVer.ext || 'mp4',
            item: legVer.item,
            versionInfo: {
              type: 'leg_dub_hybrid',
              label: 'Legenda Fixa + Dublado (Híbrido)',
              badge: 'LEG+DUB',
              icon: '✨',
              desc: 'Vídeo Legendado com Legenda no Frame + Áudio Dublado'
            }
          });
        }
      }

      return baseVersions;
    }

    // Seleciona automaticamente a melhor versão de filme conforme as preferências salvas na conta do usuário
    // (quando o usuário escolhe Legendado, Legendado vira o padrão da conta)
    function pickPreferredMovieVersion(versions) {
      if (!Array.isArray(versions) || versions.length === 0) return null;
      if (versions.length === 1) return versions[0];

      const canHevc = isHevcSupported();
      const prefAudio = (typeof getPreferredAudioPreference === 'function') ? getPreferredAudioPreference() : 'dub';
      let prefQuality = (typeof getPreferredQualityPreference === 'function') ? getPreferredQualityPreference() : (canHevc ? '4k' : 'fhd');

      const fourKLegNative = versions.find(v => v.versionInfo?.type === '4k_leg');
      const fourKLegHybrid = versions.find(v => v.versionInfo?.type === '4k_leg_hybrid');
      const fhdLeg = versions.find(v => v.versionInfo?.type === 'legendado');
      const fourKDub = versions.find(v => v.versionInfo?.type === '4k_dub');
      const fhdDub = versions.find(v => v.versionInfo?.type === 'dublado');
      const legDubHybrid = versions.find(v => v.versionInfo?.type === 'leg_dub_hybrid');

      // Se o dispositivo não suporta HEVC (H.265) e existem versões Full HD (H.264),
      // prioriza Full HD para garantir reprodução imediata e evitar erro de mídia incompatível
      if (prefQuality === '4k' && !canHevc && (fhdDub || fhdLeg || legDubHybrid)) {
        prefQuality = 'fhd';
      }

      if (prefAudio === 'leg') {
        if (prefQuality === '4k' && canHevc) {
          if (fourKLegNative) return fourKLegNative;
          if (fourKLegHybrid) return fourKLegHybrid;
          if (fhdLeg) return fhdLeg;
          if (fourKDub) return fourKDub;
          if (fhdDub) return fhdDub;
          if (legDubHybrid) return legDubHybrid;
        } else {
          if (fhdLeg) return fhdLeg;
          if (fourKLegNative && canHevc) return fourKLegNative;
          if (fourKLegHybrid && canHevc) return fourKLegHybrid;
          if (fhdDub) return fhdDub;
          if (fourKDub && canHevc) return fourKDub;
          if (legDubHybrid) return legDubHybrid;
        }
      } else {
        if (prefQuality === '4k' && canHevc) {
          if (fourKDub) return fourKDub;
          if (fhdDub) return fhdDub;
          if (legDubHybrid) return legDubHybrid;
          if (fourKLegNative) return fourKLegNative;
          if (fourKLegHybrid) return fourKLegHybrid;
          if (fhdLeg) return fhdLeg;
        } else {
          if (fhdDub) return fhdDub;
          if (fourKDub && canHevc) return fourKDub;
          if (legDubHybrid) return legDubHybrid;
          if (fhdLeg) return fhdLeg;
          if (fourKLegNative && canHevc) return fourKLegNative;
          if (fourKLegHybrid && canHevc) return fourKLegHybrid;
        }
      }

      return versions[0];
    }

    // Seleciona automaticamente a melhor versão de série conforme a preferência de áudio salva na conta
    function pickPreferredSeriesVersion(versions) {
      if (!Array.isArray(versions) || versions.length === 0) return null;
      if (versions.length === 1) return versions[0];

      const prefAudio = (typeof getPreferredAudioPreference === 'function') ? getPreferredAudioPreference() : 'dub';
      if (prefAudio === 'leg') {
        const leg = versions.find(v => v.versionInfo?.type === 'legendado');
        if (leg) return leg;
        const dub = versions.find(v => v.versionInfo?.type === 'dublado');
        if (dub) return dub;
      } else {
        const dub = versions.find(v => v.versionInfo?.type === 'dublado');
        if (dub) return dub;
        const leg = versions.find(v => v.versionInfo?.type === 'legendado');
        if (leg) return leg;
      }
      return versions[0];
    }

    // Identifica se uma mídia/versão é legendada (com legenda impressa/embutida no frame)
    function isLegendadoMedia(itemOrMeta) {
      if (!itemOrMeta) return false;
      // 4K Legendado Híbrido utiliza imagem 4K limpa sem legenda impressa -> requer legenda externa
      if (itemOrMeta.isHybrid4kLeg || itemOrMeta.selectedVersion?.isHybrid4kLeg || itemOrMeta.versionInfo?.type === '4k_leg_hybrid' || itemOrMeta.selectedVersion?.versionInfo?.type === '4k_leg_hybrid') {
        return false;
      }
      // Legenda Fixa + Dublado Híbrido utiliza frame legendado -> dispensa legenda externa
      if (itemOrMeta.isHybridLegDub || itemOrMeta.selectedVersion?.isHybridLegDub || itemOrMeta.versionInfo?.type === 'leg_dub_hybrid' || itemOrMeta.selectedVersion?.versionInfo?.type === 'leg_dub_hybrid') {
        return true;
      }
      if (itemOrMeta.selectedVersion && itemOrMeta.selectedVersion.versionInfo) {
        const t = itemOrMeta.selectedVersion.versionInfo.type;
        if (t === 'legendado' || t === '4k_leg') return true;
      }
      if (itemOrMeta.versionInfo && itemOrMeta.versionInfo.type) {
        const t = itemOrMeta.versionInfo.type;
        if (t === 'legendado' || t === '4k_leg') return true;
      }
      const raw = itemOrMeta.rawTitle || itemOrMeta.name || itemOrMeta.title || (itemOrMeta.item && (itemOrMeta.item.name || itemOrMeta.item.title)) || '';
      if (/\[\s*(?:l|leg|legendado)\s*\]/i.test(raw) || /\b(legendado)\b/i.test(raw)) return true;
      const catId = String(itemOrMeta.category_id || (itemOrMeta.item && itemOrMeta.item.category_id) || '');
      if (catId === '630' || catId === '671') return true;
      return false;
    }

    // Detecta se uma versão de série é Dublada ou Legendada
    function detectSeriesVersion(item) {
      const name = item.name || item.title || '';
      const catId = String(item.category_id || '');
      const isLeg = (catId === '671') || /\[\s*(?:L|LEG|LEGENDADO)\s*\]/i.test(name) || /\b(legendado)\b/i.test(name);
      if (isLeg) {
        return { type: 'legendado', label: 'Legendado', badge: 'LEG', icon: '💬', desc: 'Áudio Original com Legenda' };
      }
      return { type: 'dublado', label: 'Dublado', badge: 'DUB', icon: '🔊', desc: 'Áudio Dublado em Português' };
    }

    // Título limpo e elegante para exibição no card (remove ano, 4K, tags [dub], etc.)
    function cleanDisplayTitle(rawTitle) {
      if (!rawTitle) return '';
      return rawTitle
        .replace(/^[0-9]+\s*[-–—]\s*/, '')      // '1 - ', '02 - '
        .replace(/\b4k\b/gi, '')                // '4K', '4k'
        .replace(/\[\s*(?:l|leg|legendado|dub|dublado|lan[cç]amentos?|hdr|dv|hybrid|cinema|rec|corrigido)\s*\]/gi, '')
        .replace(/\s*\(\s*(?:19|20)\d{2}(?:\s*[-–—]\s*(?:(?:19|20)\d{2})?)?\s*\)/gi, '') // Remove '(ano)', e.g. '(2024)', '(1999)', '(2018-2022)'
        .replace(/\s*\[\s*(?:19|20)\d{2}(?:\s*[-–—]\s*(?:(?:19|20)\d{2})?)?\s*\]/gi, '') // Remove '[ano]'
        .replace(/\s*\(\s*(?:l|leg|legendado|dub|dublado|lan[cç]amentos?|4k|hdr|dv)\s*\)/gi, '')
        .replace(/\s+/g, ' ')
        .trim();
    }

    function getItemYear(item) {
      if (!item) return 0;
      let y = item.year;
      if (!y && item.primaryItem) y = item.primaryItem.year;
      if (!y && item.releaseDate) y = String(item.releaseDate).substring(0, 4);
      if (!y && item.primaryItem?.releaseDate) y = String(item.primaryItem.releaseDate).substring(0, 4);
      if (!y) {
        const raw = item.rawName || item.name || item.title || item.primaryItem?.name || item.primaryItem?.title || '';
        const match = String(raw).match(/\b(19\d\d|20\d\d)\b/);
        if (match) y = match[1];
      }
      const num = parseInt(y, 10);
      return (!isNaN(num) && num >= 1900 && num <= 2100) ? num : 0;
    }

    function compareByReleaseYear(a, b) {
      const yearA = getItemYear(a);
      const yearB = getItemYear(b);
      if (yearB !== yearA) return yearB - yearA; // Mais recentes primeiro (2025, 2024, 2023...)
      const timeA = Number(a.added || a.last_modified || a.primaryItem?.added || a.primaryItem?.last_modified || 0);
      const timeB = Number(b.added || b.last_modified || b.primaryItem?.added || b.primaryItem?.last_modified || 0);
      if (timeB !== timeA) return timeB - timeA;
      const idA = Number(a.stream_id || a.series_id || a.primaryItem?.stream_id || 0);
      const idB = Number(b.stream_id || b.series_id || b.primaryItem?.stream_id || 0);
      return idB - idA;
    }

    function isSpecialMovieCategory(catId) {
      const c = Number(catId);
      return (c === 630 || c === 765 || c === 632); // 630: Legendados, 765: 4K, 632: Lançamentos
    }

    function isAnimationMovieCategory(catId) {
      const c = Number(catId);
      return (c === 621 || c === 764); // 621: Animação, 764: Infantis
    }

    function isLancamentoMovieItem(item) {
      return Number(item.category_id) === 632 || /\[\s*lan[cç]amentos?\s*\]/i.test(item.name || item.title || '');
    }

    // Agrupa filmes duplicados (Dublado, Legendado e 4K) em um único card inteligente
    function groupMoviesByTitle(moviesList) {
      const groups = [];
      const cleanToGroupIndices = new Map();

      moviesList.forEach(item => {
        const raw = item.name || item.title || '';
        const clean = cleanTitleKey(raw);
        if (!clean) return;

        const yMatch = raw.match(/\((19\d\d|20\d\d)\)/);
        const itemYear = yMatch ? yMatch[1] : (item.year && String(item.year).trim().length === 4 ? String(item.year).trim() : '');
        const versionInfo = detectMovieVersion(item);
        const isLanc = isLancamentoMovieItem(item);
        const isAnim = isAnimationMovieCategory(item.category_id);
        const isSpec = isSpecialMovieCategory(item.category_id) || (versionInfo.type !== 'dublado') || isLanc;

        let matchedGroup = null;

        if (cleanToGroupIndices.has(clean)) {
          const candidateIndices = cleanToGroupIndices.get(clean);
          for (const idx of candidateIndices) {
            const g = groups[idx];

            // 1. Verificação de Ano: Não agrupar filmes com anos diferentes (evita fundir refilmagens como Bela e a Fera, Cinderela, etc.)
            if (itemYear && g.year) {
              if (Math.abs(Number(itemYear) - Number(g.year)) > 1) {
                continue; // Anos diferentes -> São filmes/refilmagens distintas!
              }
            } else if (itemYear && !g.year) {
              if (!g.isSpecial) continue;
            } else if (!itemYear && g.year) {
              if (!isSpec) continue;
            }

            // 2. Separação de Animação vs Live-Action:
            // Desenho animado (Cat 621/764) NUNCA se funde com filme live-action de outro gênero
            if (isAnim && g.hasLiveActionGenre) {
              continue;
            }
            if (!isAnim && !isSpec && g.isAnimation) {
              continue;
            }

            // 3. Verificação de versão do mesmo tipo (Dublado com Dublado):
            // Duas mídias com o MESMO tipo de versão (ex: duas dubladas) só se agrupam se uma delas for [LANÇAMENTO]!
            // Se nenhuma for [Lançamento], são produções/refilmagens/cortes diferentes e devem ficar separados!
            const existingVer = g.versions.find(v => v.versionInfo.type === versionInfo.type);
            if (existingVer) {
              const existingIsLanc = existingVer.isLanc;
              if (!isLanc && !existingIsLanc) {
                continue; // Duas mídias dubladas regulares -> Refilmagens ou produções diferentes!
              }
            }

            matchedGroup = g;
            break;
          }
        }

        if (matchedGroup) {
          const existingVer = matchedGroup.versions.find(v => v.versionInfo.type === versionInfo.type);
          if (!existingVer) {
            matchedGroup.versions.push({
              item,
              versionInfo,
              streamId: item.stream_id,
              ext: item.container_extension || null,
              isLanc
            });
          } else if (existingVer.isLanc && !isLanc) {
            existingVer.item = item;
            existingVer.streamId = item.stream_id;
            existingVer.ext = item.container_extension || null;
            existingVer.versionInfo = versionInfo;
            existingVer.isLanc = false;
          }

          const cid = Number(item.category_id);
          if (cid && !matchedGroup.category_ids.includes(cid)) {
            matchedGroup.category_ids.push(cid);
          }
          if (Array.isArray(item.category_ids)) {
            item.category_ids.forEach(c => {
              const numC = Number(c);
              if (numC && !matchedGroup.category_ids.includes(numC)) matchedGroup.category_ids.push(numC);
            });
          }
          if (!matchedGroup.poster && item.stream_icon && item.stream_icon.startsWith('http')) {
            matchedGroup.poster = item.stream_icon;
          }
          if (!matchedGroup.year && itemYear) {
            matchedGroup.year = itemYear;
          }
          const itAdd = Number(item.added || item.last_modified || 0);
          if (itAdd > Number(matchedGroup.added || 0)) {
            matchedGroup.added = item.added;
          }
          if (isAnim) {
            matchedGroup.isAnimation = true;
          }
          if (!isSpec && !isAnim) {
            matchedGroup.hasLiveActionGenre = true;
          }
          if (!matchedGroup.plot && item.plot) matchedGroup.plot = item.plot;
          if (!matchedGroup.cast && (item.cast || item.actors)) matchedGroup.cast = item.cast || item.actors;
          if (!matchedGroup.director && item.director) matchedGroup.director = item.director;
          if (!matchedGroup.genre && item.genre) matchedGroup.genre = item.genre;
          if (!matchedGroup.duration && (item.duration || item.episode_run_time)) matchedGroup.duration = item.duration || item.episode_run_time;

          if (/\[\s*lan[cç]amentos?\s*\]/i.test(matchedGroup.originalName) && !/\[\s*lan[cç]amentos?\s*\]/i.test(raw)) {
            matchedGroup.originalName = raw;
            matchedGroup.name = cleanDisplayTitle(raw);
            matchedGroup.primaryItem = item;
          } else if (versionInfo.type === 'dublado' || versionInfo.type === '4k_dub') {
            matchedGroup.originalName = raw;
            matchedGroup.name = cleanDisplayTitle(raw);
            matchedGroup.primaryItem = item;
            matchedGroup.stream_id = item.stream_id;
          }
        } else {
          const catIds = [Number(item.category_id)].filter(Boolean);
          if (Array.isArray(item.category_ids)) {
            item.category_ids.forEach(c => {
              const numC = Number(c);
              if (numC && !catIds.includes(numC)) catIds.push(numC);
            });
          }
          const hasLive = (!isSpec && !isAnim);
          const newGroup = {
            isGroup: true,
            cleanKey: clean,
            name: cleanDisplayTitle(raw),
            originalName: item.originalName || raw,
            year: itemYear,
            category_id: item.category_id,
            category_ids: catIds,
            rating: item.rating,
            rating_5based: item.rating_5based,
            added: item.added,
            last_modified: item.last_modified,
            stream_id: item.stream_id,
            container_extension: item.container_extension || null,
            plot: item.plot,
            cast: item.cast || item.actors,
            director: item.director,
            genre: item.genre,
            duration: item.duration || item.episode_run_time,
            episode_run_time: item.episode_run_time,
            poster: (item.stream_icon && item.stream_icon.startsWith('http')) ? item.stream_icon : '',
            primaryItem: item,
            isAnimation: isAnim,
            hasLiveActionGenre: hasLive,
            isSpecial: isSpec,
            versions: [{
              item,
              versionInfo,
              streamId: item.stream_id,
              ext: item.container_extension || null,
              isLanc
            }]
          };
          const newIdx = groups.length;
          groups.push(newGroup);
          if (!cleanToGroupIndices.has(clean)) {
            cleanToGroupIndices.set(clean, []);
          }
          cleanToGroupIndices.get(clean).push(newIdx);
        }
      });

      // Ordenar por ano de lançamento / estreia (mais recentes primeiro)
      groups.sort(compareByReleaseYear);

      return groups;
    }

    // Agrupa séries duplicadas (Dublado, Legendado e Lançamento) em um único card inteligente
    function groupSeriesByTitle(seriesList) {
      const groups = [];
      const cleanToGroupIndices = new Map();

      seriesList.forEach(item => {
        const raw = item.name || item.title || '';
        const clean = cleanTitleKey(raw);
        if (!clean) return;

        const yMatch = raw.match(/\((19\d\d|20\d\d)\)/);
        const itemYear = yMatch ? yMatch[1] : (item.year ? String(item.year).trim() : (item.releaseDate ? item.releaseDate.substring(0, 4) : ''));
        const versionInfo = detectSeriesVersion(item);
        const isLanc = /\[\s*lan[cç]amentos?\s*\]/i.test(raw);

        let matchedGroup = null;

        if (cleanToGroupIndices.has(clean)) {
          const candidateIndices = cleanToGroupIndices.get(clean);
          for (const idx of candidateIndices) {
            const g = groups[idx];

            // 1. Verificação de Ano
            if (itemYear && g.year) {
              if (Math.abs(Number(itemYear) - Number(g.year)) > 1) {
                continue;
              }
            }

            // 2. Duas séries do mesmo tipo só se agrupam se uma delas for Lançamento
            const existingVer = g.versions.find(v => v.versionInfo.type === versionInfo.type);
            if (existingVer) {
              const existingIsLanc = existingVer.isLanc;
              if (!isLanc && !existingIsLanc) {
                continue;
              }
            }

            matchedGroup = g;
            break;
          }
        }

        if (matchedGroup) {
          const existingVer = matchedGroup.versions.find(v => v.versionInfo.type === versionInfo.type);
          if (!existingVer) {
            matchedGroup.versions.push({
              item,
              versionInfo,
              seriesId: item.series_id,
              isLanc
            });
          } else if (existingVer.isLanc && !isLanc) {
            existingVer.item = item;
            existingVer.seriesId = item.series_id;
            existingVer.versionInfo = versionInfo;
            existingVer.isLanc = false;
          }

          const cid = Number(item.category_id);
          if (cid && !matchedGroup.category_ids.includes(cid)) {
            matchedGroup.category_ids.push(cid);
          }
          if (Array.isArray(item.category_ids)) {
            item.category_ids.forEach(c => {
              const numC = Number(c);
              if (numC && !matchedGroup.category_ids.includes(numC)) matchedGroup.category_ids.push(numC);
            });
          }
          if (!matchedGroup.cover && item.cover && item.cover.startsWith('http')) {
            matchedGroup.cover = item.cover;
          }
          if (!matchedGroup.imdbId && isValidImdbId(item.imdb_id || item.imdbId)) {
            matchedGroup.imdbId = item.imdb_id || item.imdbId;
            matchedGroup.imdb_id = matchedGroup.imdbId;
          }
          if (!matchedGroup.malId && normalizePositiveId(item.mal_id || item.malId)) {
            matchedGroup.malId = item.mal_id || item.malId;
            matchedGroup.mal_id = matchedGroup.malId;
          }
          if (!matchedGroup.year && itemYear) {
            matchedGroup.year = itemYear;
          }
          if (!matchedGroup.cast && (item.cast || item.actors)) matchedGroup.cast = item.cast || item.actors;
          if (!matchedGroup.director && item.director) matchedGroup.director = item.director;
          if (!matchedGroup.genre && item.genre) matchedGroup.genre = item.genre;
          if (!matchedGroup.plot && item.plot) matchedGroup.plot = item.plot;
          const itAdd = Number(item.last_modified || item.added || 0);
          if (itAdd > Number(matchedGroup.added || 0)) {
            matchedGroup.added = item.added;
            matchedGroup.last_modified = item.last_modified;
          }
          if (/\[\s*lan[cç]amentos?\s*\]/i.test(matchedGroup.originalName) && !/\[\s*lan[cç]amentos?\s*\]/i.test(raw)) {
            matchedGroup.originalName = raw;
            matchedGroup.name = cleanDisplayTitle(raw);
            matchedGroup.primaryItem = item;
          } else if (versionInfo.type === 'dublado') {
            matchedGroup.originalName = raw;
            matchedGroup.name = cleanDisplayTitle(raw);
            matchedGroup.primaryItem = item;
            matchedGroup.series_id = item.series_id;
          }
        } else {
          const catIds = [Number(item.category_id)].filter(Boolean);
          if (Array.isArray(item.category_ids)) {
            item.category_ids.forEach(c => {
              const numC = Number(c);
              if (numC && !catIds.includes(numC)) catIds.push(numC);
            });
          }

          const newGroup = {
            isGroup: true,
            cleanKey: clean,
            name: cleanDisplayTitle(raw),
            originalName: raw,
            year: itemYear,
            category_id: item.category_id,
            category_ids: catIds,
            rating: item.rating,
            rating_5based: item.rating_5based,
            added: item.added,
            last_modified: item.last_modified,
            series_id: item.series_id,
            imdbId: isValidImdbId(item.imdb_id || item.imdbId) ? (item.imdb_id || item.imdbId) : (readSeriesImdbCache()[clean] || ''),
            imdb_id: isValidImdbId(item.imdb_id || item.imdbId) ? (item.imdb_id || item.imdbId) : (readSeriesImdbCache()[clean] || ''),
            malId: normalizePositiveId(item.mal_id || item.malId),
            mal_id: normalizePositiveId(item.mal_id || item.malId),
            plot: item.plot,
            cast: item.cast || item.actors,
            director: item.director,
            genre: item.genre,
            cover: item.cover || item.stream_icon || '',
            primaryItem: item,
            versions: [{
              item,
              versionInfo,
              seriesId: item.series_id,
              isLanc
            }]
          };
          const newIdx = groups.length;
          groups.push(newGroup);
          if (!cleanToGroupIndices.has(clean)) {
            cleanToGroupIndices.set(clean, []);
          }
          cleanToGroupIndices.get(clean).push(newIdx);
        }
      });

      // Ordenar por ano de lançamento / estreia (mais recentes primeiro)
      groups.sort(compareByReleaseYear);

      return groups;
    }

    // ==========================================
    // INICIALIZAÇÃO
    // ==========================================
    async function init() {
      document.addEventListener('error', event => {
        const img = event.target;
        if (!(img instanceof HTMLImageElement)) return;
        if (img.dataset.posterError) {
          onPosterError(img, img.dataset.posterError);
          return;
        }
        if (img.dataset.hideOnError) {
          img.style.display = 'none';
          const fallback = img.nextElementSibling;
          if (img.dataset.hideShowFallbackOnError && fallback) fallback.style.display = 'flex';
          return;
        }
        if (img.dataset.dimOnError) img.style.opacity = '0.3';
        if (img.dataset.fadeOnError) img.style.opacity = '0.2';
        if (img.dataset.hideShowFallbackOnError && img.nextElementSibling) {
          img.style.display = 'none';
          img.nextElementSibling.style.display = 'flex';
        }
      }, true);

      document.addEventListener('click', event => {
        const searchAll = event.target.closest('[data-search-all]');
        if (searchAll) selectAllMedia();
        const closePlayerBtn = event.target.closest('[data-close-player]');
        if (closePlayerBtn) closePlayer();
      }, true);

      // Navegação principal
      elements.tabHomeBtn?.addEventListener('click', () => showHome());
      elements.tabMoviesBtn.addEventListener('click', () => switchMode('movies', true));
      elements.tabSeriesBtn.addEventListener('click', () => switchMode('series', true));
      if (elements.tabWatchedBtn) {
        elements.tabWatchedBtn.addEventListener('click', () => {
          if (isWatchedView) return;
          showWatchedContent();
        });
      }
      elements.tabFavoritesBtn?.addEventListener('click', () => {
        if (isFavoritesView) restoreFavoritesView();
        else showFavoritesContent();
      });
      if (elements.tabLiveBtn) {
        elements.tabLiveBtn.addEventListener('click', () => switchMode('live', true));
      }
      elements.tabUserBtn?.addEventListener('click', () => showUserPage());
      elements.accountBtn?.addEventListener('click', () => showUserPage());
      elements.contentFavoriteBtn?.addEventListener('click', () => toggleCurrentFavorite());

      // Eventos
      elements.categorySelect.addEventListener('change', (e) => onCategoryChange(e.target.value));
      elements.searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          if (elements.searchInput.value) {
            elements.searchInput.value = '';
            onSearch('');
          } else {
            showHome();
          }
        }
      });
      elements.searchInput.addEventListener('input', (e) => {
        const value = e.target.value;
        if (searchDebounceTimer) clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
          searchDebounceTimer = null;
          onSearch(value);
        }, 180);
      });
      elements.resetCategoryBtn.addEventListener('click', selectAllMedia);
      elements.logoBtn.addEventListener('click', showHome);
      elements.loadMoreBtn.addEventListener('click', renderNextBatch);

      // Navegação mobile: mesma hierarquia da navegação desktop.
      elements.mobileHomeBtn?.addEventListener('click', () => showHome());
      elements.mobileMoviesBtn?.addEventListener('click', () => switchMode('movies', true));
      elements.mobileSeriesBtn?.addEventListener('click', () => switchMode('series', true));
      elements.mobileLiveBtn?.addEventListener('click', () => switchMode('live', true));
      elements.mobileWatchedBtn?.addEventListener('click', () => {
        if (isWatchedView) return;
        showWatchedContent();
      });
      elements.mobileFavoritesBtn?.addEventListener('click', () => {
        if (isFavoritesView) restoreFavoritesView();
        else showFavoritesContent();
      });
      elements.mobileAccountBtn?.addEventListener('click', () => showUserPage());
      elements.homeFeaturedPrev?.addEventListener('click', () => moveHomeFeatured(-1));
      elements.homeFeaturedNext?.addEventListener('click', () => moveHomeFeatured(1));
      elements.homeFeaturedDots?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-home-slide]');
        if (button) showHomeFeatured(Number(button.dataset.homeSlide));
      });
      elements.homeWatchedAllBtn?.addEventListener('click', () => {
        showWatchedContent().catch(() => {});
      });
      elements.contentPageBackBtn?.addEventListener('click', () => restoreFromContentPage());
      elements.contentPage?.addEventListener('click', (e) => {
        const target = e.target.closest('[data-context-search]');
        if (target) {
          const term = target.getAttribute('data-context-search');
          const isPerson = target.getAttribute('data-context-person') === 'true';
          if (term) {
            searchByContextTerm(term, isPerson);
          }
        }
      });
      elements.contentSeasonSelect?.addEventListener('change', (e) => renderSeasonEpisodes(e.target.value));

      // Video Modal
      elements.closeVideoModal.addEventListener('click', closePlayer);
      if (elements.syncLiveBtn) {
        elements.syncLiveBtn.addEventListener('click', forceLiveSync);
      }
      if (elements.toggleLatencyModeBtn) {
        elements.toggleLatencyModeBtn.addEventListener('click', toggleLatencyMode);
      }
      elements.videoModal.addEventListener('click', (e) => {
        if (e.target === elements.videoModal) closePlayer();
      });
      elements.toggleSubPanelBtn.addEventListener('click', toggleSubOptionsPanel);
      elements.closeSubPanelBtn.addEventListener('click', closeSubOptionsPanel);
      if (elements.skipIntroBtn) {
        elements.skipIntroBtn.addEventListener('click', handleSkipIntroClick);
      }
      if (elements.skipIntroAutoToggle) {
        elements.skipIntroAutoToggle.addEventListener('change', (e) => {
          setAutoSkipIntroEnabled(e.target.checked);
          updateSkipIntroButton();
        });
      }

      // Painel Lateral de Informações (Ficha Técnica)
      if (elements.toggleMovieInfoBtn) {
        elements.toggleMovieInfoBtn.addEventListener('click', () => toggleMovieInfoSidebar());
      }
      if (elements.closeInfoSidebarBtn) {
        elements.closeInfoSidebarBtn.addEventListener('click', () => toggleMovieInfoSidebar(false));
      }
      elements.subSelect.addEventListener('change', (e) => onSubtitleSelectChange(e.target.value));
      elements.subCandidateSelect.addEventListener('change', (e) => onCandidateSelectChange(e.target.value));
      elements.searchCustomSubBtn.addEventListener('click', openImdbSearchModal);
      elements.subDelayMinusBtn.addEventListener('click', () => changeSubtitleOffset(-0.5));
      elements.subDelayPlusBtn.addEventListener('click', () => changeSubtitleOffset(0.5));

      // Sincronia de Áudio no Modo Híbrido
      if (elements.audioDelayMinusLargeBtn) {
        elements.audioDelayMinusLargeBtn.addEventListener('click', () => adjustAudioOffset(-500));
      }
      if (elements.audioDelayMinusBtn) {
        elements.audioDelayMinusBtn.addEventListener('click', () => adjustAudioOffset(-50));
      }
      if (elements.audioDelayPlusBtn) {
        elements.audioDelayPlusBtn.addEventListener('click', () => adjustAudioOffset(50));
      }
      if (elements.audioDelayPlusLargeBtn) {
        elements.audioDelayPlusLargeBtn.addEventListener('click', () => adjustAudioOffset(500));
      }
      if (elements.audioSyncResetBtn) {
        elements.audioSyncResetBtn.addEventListener('click', () => setAudioOffset(0));
      }
      if (elements.audioOffsetInput) {
        elements.audioOffsetInput.addEventListener('change', (e) => {
          const raw = e.target.value.replace(/ms/ig, '').trim();
          const parsed = parseInt(raw, 10);
          setAudioOffset(Number.isFinite(parsed) ? parsed : 0);
        });
        elements.audioOffsetInput.addEventListener('keydown', (e) => {
          e.stopPropagation();
          if (e.key === 'Enter') {
            e.target.blur();
          }
        });
      }
      if (elements.toggleAudioSyncBtn) {
        elements.toggleAudioSyncBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          if (!elements.audioSyncControls) return;
          const isHidden = (elements.audioSyncControls.style.display === 'none');
          elements.audioSyncControls.style.display = isHidden ? 'inline-flex' : 'none';
        });
      }

      // IMDb Visual Search Modal
      elements.closeImdbSearchModal.addEventListener('click', closeImdbSearchModal);
      elements.imdbSearchModal.addEventListener('click', (e) => {
        if (e.target === elements.imdbSearchModal) closeImdbSearchModal();
      });
      elements.doImdbSearchBtn.addEventListener('click', () => executeImdbSearch());
      elements.imdbSearchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') executeImdbSearch();
      });
      elements.imdbSearchType.addEventListener('change', () => executeImdbSearch());
      elements.clearMemoryBtn.addEventListener('click', clearMediaMatch);

      // Carregamento de legendas (.srt ou .vtt)
      elements.subFileInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files[0]) {
          loadSubtitleFromFile(e.target.files[0]);
        }
      });

      // Arrastar e soltar legenda (.srt) direto na tela do vídeo
      const dropZone = elements.videoModal.querySelector('.video-container');
      dropZone.addEventListener('dragover', (e) => e.preventDefault());
      dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        if (e.dataTransfer.files && e.dataTransfer.files[0]) {
          const file = e.dataTransfer.files[0];
          if (file.name.toLowerCase().endsWith('.srt') || file.name.toLowerCase().endsWith('.vtt')) {
            loadSubtitleFromFile(file);
          }
        }
      });

      // Series Modal
      elements.closeSeriesModal.addEventListener('click', closeSeriesModal);
      elements.seriesModal.addEventListener('click', (e) => {
        if (e.target === elements.seriesModal) closeSeriesModal();
      });
      elements.seasonSelect.addEventListener('change', (e) => renderSeasonEpisodes(e.target.value));

      // Movie Version Modal
      elements.closeMovieVersionModal.addEventListener('click', closeMovieVersionModal);
      elements.movieVersionModal.addEventListener('click', (e) => {
        if (e.target === elements.movieVersionModal) closeMovieVersionModal();
      });

      setupTvRemoteNavigation();

      // Infinite scroll: limita o trabalho a no máximo um ciclo por frame.
      window.addEventListener('scroll', () => {
        if (infiniteScrollRaf !== null) return;
        infiniteScrollRaf = requestAnimationFrame(() => {
          infiniteScrollRaf = null;
          if (window.innerHeight + window.scrollY >= document.body.offsetHeight - 600) {
            if (renderedCount < currentFilteredList.length) {
              renderNextBatch();
            }
          }
        });
      }, { passive: true });

      // Previne arraste nativo de imagens em carrosséis e cards (evita travar o mouse e solavancos)
      window.addEventListener('dragstart', (e) => {
        if (e.target.closest('.home-featured-track, .home-theme-scroller, .home-watched-rail, .media-grid, .home-title-card, .home-watched-card, .media-card')) {
          e.preventDefault();
        }
      });

      // Sincronização remota de preferências via Supabase: silenciosa para não perturbar a tela ativa
      window.addEventListener('andplay:remote-preferences-synced', () => {
        // Silencioso: não recriar a tela da Home enquanto o usuário está nela
      });

      initSidebarState();
      initAuthScreen();

      // Aguarda a verificação da conta do Supabase
      await window.AndPlayAccount?.ready?.();

      if (!window.AndPlayAccount?.isSignedIn?.()) {
        pendingRoute = getRouteFromLocation();
        showLoginScreen();
        return;
      }

      hideLoginScreen();

      // Wiring do botão "💻 Modo Web" no header da TV
      const btnExitTv = document.getElementById('btnExitTvMode');
      if (btnExitTv) {
        btnExitTv.addEventListener('click', () => {
          if (window.exitTvMode) window.exitTvMode();
        });
      }

      initRouter();
      const initialRoute = pendingRoute || getRouteFromLocation();
      pendingRoute = null;
      if (initialRoute && initialRoute !== '#/inicio' && initialRoute !== '#/' && initialRoute !== '') {
        await navigateRoute(initialRoute, false);
      } else {
        const isTvMode = window.location.pathname.endsWith('/tvmode') || window.location.pathname.endsWith('/tvmode/') || window.location.pathname.includes('/tvmode') || window.location.hash.includes('tvmode') || window.location.search.includes('tvmode');
        if (isTvMode && window.initTvCableBox) {
          window.initTvCableBox();
          return;
        }
        showHome();
      }
    }

    // ==========================================
    // ROTEAMENTO SPA (DEEP LINKING COM PROTEÇÃO DE LOGIN)
    // ==========================================
    function getRouteFromLocation() {
      const hash = window.location.hash || '';
      if (hash && hash.startsWith('#/')) {
        return hash;
      }
      if (hash && hash.startsWith('#')) {
        return '#/' + hash.replace(/^#\/?/, '');
      }
      const pathname = window.location.pathname.replace(/\\/g, '/');
      const parts = pathname.split('/').filter(Boolean);
      let routeParts = parts;
      if (parts.length > 0 && parts[0].toLowerCase() === 'andplay') {
        routeParts = parts.slice(1);
      }
      if (routeParts.length > 0) {
        return '#/' + routeParts.join('/');
      }
      return '#/inicio';
    }

    function setRouteHash(hash, push = true, state = null) {
      if (!hash) hash = '#/inicio';
      if (!hash.startsWith('#/')) {
        hash = '#/' + hash.replace(/^#?\/?/, '');
      }
      if (window.location.hash === hash) {
        if (state !== null) {
          try { window.history.replaceState(state, '', hash); } catch (_) {}
        }
        return;
      }
      isRouterNavigating = true;
      try {
        if (push) {
          window.history.pushState(state, '', hash);
        } else {
          window.history.replaceState(state, '', hash);
        }
      } catch (_) {
        window.location.hash = hash;
      }
      setTimeout(() => { isRouterNavigating = false; }, 100);
    }

    function parseRoute(routeStr) {
      if (!routeStr) return { type: 'home' };
      const clean = String(routeStr).replace(/\\/g, '/').replace(/^#\/?/, '').replace(/^\/+/, '').replace(/\/+$/, '');
      const parts = clean.split('/').map(decodeURIComponent).filter(Boolean);
      if (parts.length === 0) return { type: 'home' };

      const first = parts[0].toLowerCase();

      if (first === 'inicio' || first === 'home') {
        return { type: 'home' };
      }
      if (first === 'filmes' || first === 'movies') {
        if (parts.length === 1) return { type: 'movies' };
      }
      if (first === 'series' && parts.length === 1) {
        return { type: 'series' };
      }
      if (first === 'tv' || first === 'canais' || first === 'live') {
        return { type: 'live' };
      }
      if (first === 'esportes' || first === 'sports') {
        return { type: 'sports' };
      }
      if (first === 'favoritos' || first === 'favorites') {
        return { type: 'favorites' };
      }
      if (first === 'assistidos' || first === 'watched') {
        return { type: 'watched' };
      }
      if (first === 'conta' || first === 'usuario' || first === 'user') {
        return { type: 'user' };
      }
      if (first === 'tvmode') {
        return { type: 'tvmode' };
      }
      // Filme: #/filme/:id ou #/movie/:id ou #/filmes/:id
      if ((first === 'filme' || first === 'movie' || first === 'filmes') && parts.length >= 2) {
        return { type: 'movie', id: parts[1] };
      }
      // Série com episódio numérico direto: #/series/:id/:season/:ep (ex: /series/123/1/5)
      if (first === 'series' && parts.length === 4 && /^\d+$/.test(parts[2]) && /^\d+$/.test(parts[3])) {
        return {
          type: 'series-episode',
          seriesId: parts[1],
          season: parseInt(parts[2], 10) || 1,
          episode: parseInt(parts[3], 10) || 1
        };
      }
      // Série com episódio nomeado: #/series/:id/temporada/:season/episodio/:ep ou #/series/:id/ep/:ep
      if (first === 'series' && parts.length >= 3) {
        const seriesId = parts[1];
        let season = 1;
        let ep = 1;
        let hasEpisodeParam = false;
        for (let i = 2; i < parts.length; i += 2) {
          const key = (parts[i] || '').toLowerCase();
          const val = parts[i + 1];
          if ((key === 'temporada' || key === 'season' || key === 'temp' || key === 's') && val) {
            season = parseInt(val, 10) || 1;
          }
          if ((key === 'episodio' || key === 'episode' || key === 'ep' || key === 'e') && val) {
            ep = parseInt(val, 10) || 1;
            hasEpisodeParam = true;
          }
        }
        if (hasEpisodeParam) {
          return { type: 'series-episode', seriesId, season, episode: ep };
        }
      }
      // Série individual: #/series/:id
      if (first === 'series' && parts.length >= 2) {
        return { type: 'series-detail', id: parts[1] };
      }

      return { type: 'home' };
    }

    async function navigateRoute(routeStr, pushHistory = true) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        pendingRoute = routeStr || getRouteFromLocation();
        showLoginScreen('login');
        return;
      }

      const route = parseRoute(routeStr);
      if (route.type === 'home') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        showHome();
        setRouteHash('#/inicio', pushHistory);
      } else if (route.type === 'movies') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        await switchMode('movies');
        setRouteHash('#/filmes', pushHistory);
      } else if (route.type === 'series') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        await switchMode('series');
        setRouteHash('#/series', pushHistory);
      } else if (route.type === 'live') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        await switchMode('live');
        setRouteHash('#/tv', pushHistory);
      } else if (route.type === 'sports') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        showHome();
        const sportsSection = document.getElementById('homeSportsSection') || document.querySelector('.sports-rail-container');
        if (sportsSection) sportsSection.scrollIntoView({ behavior: 'smooth' });
        setRouteHash('#/esportes', pushHistory);
        document.title = 'Esportes - EPlay';
      } else if (route.type === 'favorites') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        await showFavoritesContent();
        setRouteHash('#/favoritos', pushHistory);
      } else if (route.type === 'watched') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        await showWatchedContent();
        setRouteHash('#/assistidos', pushHistory);
      } else if (route.type === 'user') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        if (contentPageOpen) restoreFromContentPage();
        showUserPage();
        setRouteHash('#/conta', pushHistory);
      } else if (route.type === 'tvmode') {
        if (window.enterTvMode) window.enterTvMode();
        setRouteHash('#/tvmode', pushHistory);
      } else if (route.type === 'movie') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        showLoading('Carregando filme...');
        try {
          let movies = fullMoviesCache;
          if (!Array.isArray(movies) || movies.length === 0) {
            movies = await loadFullMovies();
          }
          const targetId = String(route.id);
          const found = (movies || []).find(m =>
            String(m.stream_id) === targetId ||
            String(m.primaryItem?.stream_id) === targetId ||
            (m.versions && m.versions.some(v => String(v.streamId) === targetId))
          );
          hideLoading();
          if (found) {
            await openMoviePage(found);
            setRouteHash('#/filme/' + targetId, pushHistory);
          } else {
            await switchMode('movies');
            if (window.showPlayerToast) window.showPlayerToast('Filme não encontrado no catálogo');
          }
        } catch (err) {
          hideLoading();
          await switchMode('movies');
        }
      } else if (route.type === 'series-detail') {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') closePlayer();
        showLoading('Carregando série...');
        try {
          let seriesList = fullSeriesCache;
          if (!Array.isArray(seriesList) || seriesList.length === 0) {
            seriesList = await loadFullSeries();
          }
          const targetId = String(route.id);
          const found = (seriesList || []).find(s =>
            String(s.series_id) === targetId ||
            (s.versions && s.versions.some(v => String(v.seriesId) === targetId))
          );
          hideLoading();
          if (found) {
            await openSeriesPage(found);
            setRouteHash('#/series/' + targetId, pushHistory);
          } else {
            await switchMode('series');
            if (window.showPlayerToast) window.showPlayerToast('Série não encontrada no catálogo');
          }
        } catch (err) {
          hideLoading();
          await switchMode('series');
        }
      } else if (route.type === 'series-episode') {
        showLoading('Carregando episódio...');
        try {
          let seriesList = fullSeriesCache;
          if (!Array.isArray(seriesList) || seriesList.length === 0) {
            seriesList = await loadFullSeries();
          }
          const targetId = String(route.seriesId);
          const found = (seriesList || []).find(s =>
            String(s.series_id) === targetId ||
            (s.versions && s.versions.some(v => String(v.seriesId) === targetId))
          );
          if (found) {
            await openSeriesPage(found);
            const targetVer = found.versions?.[0] || found;
            const realSeriesId = targetVer.seriesId || targetVer.series_id || targetId;
            const data = await getOrFetchSeriesInfo(realSeriesId);
            hideLoading();
            const seasonEps = (data?.episodes && data.episodes[route.season]) || [];
            const matchedEp = seasonEps.find(e => Number(e.episode_num) === Number(route.episode));
            if (matchedEp) {
              await playSeriesEpisode(matchedEp, route.season);
              setRouteHash(`#/series/${targetId}/temporada/${route.season}/episodio/${route.episode}`, pushHistory);
            } else {
              setRouteHash('#/series/' + targetId, pushHistory);
            }
          } else {
            hideLoading();
            await switchMode('series');
          }
        } catch (err) {
          hideLoading();
          await switchMode('series');
        }
      }
    }

    function initRouter() {
      if (routerInitialized) return;
      routerInitialized = true;

      window.addEventListener('hashchange', () => {
        if (isRouterNavigating) return;
        if (!window.AndPlayAccount?.isSignedIn?.()) {
          pendingRoute = window.location.hash;
          showLoginScreen();
          return;
        }
        navigateRoute(window.location.hash, false);
      });

      window.handleAppRouteFromHistory = function () {
        if (isRouterNavigating) return;
        if (!window.AndPlayAccount?.isSignedIn?.()) {
          pendingRoute = window.location.hash;
          showLoginScreen();
          return;
        }
        navigateRoute(window.location.hash, false);
      };
    }

    // ==========================================
    // NAVEGAÇÃO POR CONTROLE REMOTO (D-PAD / ANDROID TV / PROJETOR)
    // ==========================================
    function setupTvRemoteNavigation() {
      // Expõe manipulador do botão 'Voltar' do controle remoto do Android / Projetor
      window.handleAndroidBack = function () {
        if (elements.videoModal && elements.videoModal.style.display === 'flex') {
          closePlayer();
          return true;
        }
        if (elements.seriesModal && elements.seriesModal.style.display === 'flex') {
          closeSeriesModal();
          return true;
        }
        if (elements.movieVersionModal && elements.movieVersionModal.style.display === 'flex') {
          closeMovieVersionModal();
          return true;
        }
        if (elements.imdbSearchModal && elements.imdbSearchModal.style.display === 'flex') {
          closeImdbSearchModal();
          return true;
        }
        if (elements.contentPage && !elements.contentPage.hidden) {
          restoreFromContentPage();
          return true;
        }
        return false;
      };

      window.addEventListener('keydown', (e) => {
        const key = e.key;

        // Botão Voltar (Escape / Android Back)
        if (key === 'Escape' || key === 'Back' || e.keyCode === 4) {
          if (window.handleAndroidBack()) {
            e.preventDefault();
            return;
          }
        }

        const isVideoModalOpen = elements.videoModal && elements.videoModal.style.display === 'flex';

        // Controle durante reprodução de vídeo
        if (isVideoModalOpen) {
          const activeEl = document.activeElement;
          const isButtonOrInput = activeEl && (activeEl.tagName === 'BUTTON' || activeEl.tagName === 'SELECT' || activeEl.tagName === 'INPUT');

          if (key === ' ' || key === 'MediaPlayPause' || (key === 'Enter' && !isButtonOrInput)) {
            e.preventDefault();
            if (elements.videoPlayer.paused) {
              elements.videoPlayer.play().catch(() => { });
            } else {
              elements.videoPlayer.pause();
            }
            return;
          }

          if (key === 'ArrowLeft' && !isButtonOrInput) {
            e.preventDefault();
            if (e.shiftKey && currentHybridState && currentHybridState.active) {
              adjustAudioOffset(e.ctrlKey ? -500 : -50);
              return;
            }
            elements.videoPlayer.currentTime = Math.max(0, elements.videoPlayer.currentTime - 10);
            return;
          }

          if (key === 'ArrowRight' && !isButtonOrInput) {
            e.preventDefault();
            if (e.shiftKey && currentHybridState && currentHybridState.active) {
              adjustAudioOffset(e.ctrlKey ? 500 : 50);
              return;
            }
            elements.videoPlayer.currentTime = Math.min(elements.videoPlayer.duration || 999999, elements.videoPlayer.currentTime + 10);
            return;
          }

          if (key === 'ArrowDown' && !isButtonOrInput) {
            e.preventDefault();
            const serverBtns = elements.movieVersionSwitcher ? Array.from(elements.movieVersionSwitcher.querySelectorAll('button')) : [];
            if (serverBtns.length > 0) {
              serverBtns[0].focus();
            } else if (elements.toggleMovieInfoBtn) {
              elements.toggleMovieInfoBtn.focus();
            }
            return;
          }

          if (isButtonOrInput) {
            const serverBtns = elements.movieVersionSwitcher ? Array.from(elements.movieVersionSwitcher.querySelectorAll('button')) : [];
            const curServerIdx = serverBtns.indexOf(activeEl);

            if (curServerIdx !== -1) {
              if (key === 'ArrowRight') {
                e.preventDefault();
                if (curServerIdx + 1 < serverBtns.length) serverBtns[curServerIdx + 1].focus();
                return;
              }
              if (key === 'ArrowLeft') {
                e.preventDefault();
                if (curServerIdx > 0) serverBtns[curServerIdx - 1].focus();
                return;
              }
              if (key === 'ArrowUp') {
                e.preventDefault();
                elements.videoPlayer.focus();
                return;
              }
            } else if (key === 'ArrowUp') {
              e.preventDefault();
              elements.videoPlayer.focus();
              return;
            }
          }

          return;
        }

        // Navegação na grade e cabeçalho quando nenhum modal está aberto
        const isAnyModalOpen = (elements.seriesModal && elements.seriesModal.style.display === 'flex') ||
          (elements.movieVersionModal && elements.movieVersionModal.style.display === 'flex') ||
          (elements.imdbSearchModal && elements.imdbSearchModal.style.display === 'flex');

        if (!isAnyModalOpen) {
          const active = document.activeElement;
          const cards = Array.from(elements.mediaGrid.querySelectorAll('.media-card'));
          const curIndex = cards.indexOf(active);

          // Navegação no cabeçalho (Abas e Seletor)
          if (active === elements.tabHomeBtn) {
            if (key === 'ArrowRight') { e.preventDefault(); elements.tabMoviesBtn.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); elements.categorySelect.focus(); return; }
          } else if (active === elements.tabMoviesBtn) {
            if (key === 'ArrowLeft') { e.preventDefault(); elements.tabHomeBtn?.focus(); return; }
            if (key === 'ArrowRight') { e.preventDefault(); elements.tabSeriesBtn.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); elements.categorySelect.focus(); return; }
          } else if (active === elements.tabSeriesBtn) {
            if (key === 'ArrowLeft') { e.preventDefault(); elements.tabMoviesBtn.focus(); return; }
            if (key === 'ArrowRight' && elements.tabLiveBtn) { e.preventDefault(); elements.tabLiveBtn.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); elements.categorySelect.focus(); return; }
          } else if (active === elements.tabLiveBtn) {
            if (key === 'ArrowLeft') { e.preventDefault(); elements.tabSeriesBtn.focus(); return; }
            if (key === 'ArrowRight') { e.preventDefault(); elements.tabWatchedBtn?.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); elements.categorySelect.focus(); return; }
          } else if (active === elements.tabWatchedBtn) {
            if (key === 'ArrowLeft') { e.preventDefault(); elements.tabLiveBtn?.focus(); return; }
            if (key === 'ArrowRight') { e.preventDefault(); elements.tabFavoritesBtn?.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); if (cards.length > 0) cards[0].focus(); return; }
          } else if (active === elements.tabFavoritesBtn) {
            if (key === 'ArrowLeft') { e.preventDefault(); elements.tabWatchedBtn?.focus(); return; }
            if (key === 'ArrowRight' && elements.searchInput) { e.preventDefault(); elements.searchInput.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); if (cards.length > 0) cards[0].focus(); return; }
          } else if (active === elements.searchInput) {
            const hasText = Boolean(active.value && active.value.length > 0);
            if (!hasText) {
              if (key === 'ArrowLeft') { e.preventDefault(); elements.tabFavoritesBtn?.focus(); return; }
              if (key === 'ArrowRight') {
                e.preventDefault();
                if (elements.categorySelect && elements.categorySelect.style.display !== 'none') elements.categorySelect.focus();
                else if (elements.accountBtn) elements.accountBtn.focus();
                return;
              }
            }
            if (key === 'ArrowDown') { e.preventDefault(); if (cards.length > 0) cards[0].focus(); return; }
            if (key === 'ArrowUp') { e.preventDefault(); elements.tabFavoritesBtn?.focus(); return; }
          } else if (active === elements.categorySelect) {
            if (key === 'ArrowUp') {
              e.preventDefault();
              if (currentMode === 'live' && elements.tabLiveBtn) elements.tabLiveBtn.focus();
              else if (currentMode === 'series') elements.tabSeriesBtn.focus();
              else elements.tabMoviesBtn.focus();
              return;
            }
            if (key === 'ArrowLeft') { e.preventDefault(); elements.searchInput?.focus(); return; }
            if (key === 'ArrowRight') { e.preventDefault(); elements.accountBtn?.focus(); return; }
            if (key === 'ArrowDown') { e.preventDefault(); if (cards.length > 0) cards[0].focus(); return; }
          } else if (active === elements.accountBtn) {
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (elements.categorySelect && elements.categorySelect.style.display !== 'none') elements.categorySelect.focus();
              else if (elements.searchInput) elements.searchInput.focus();
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (elements.userHeroSyncBtn && elements.userDashboard?.style.display !== 'none') elements.userHeroSyncBtn.focus();
              else if (cards.length > 0) cards[0].focus();
              return;
            }
          }

          if (curIndex !== -1) {
            // Calcula quantas colunas existem na grade dinamicamente
            const gridWidth = elements.mediaGrid.clientWidth || window.innerWidth;
            const cardWidth = active.offsetWidth || 180;
            const gap = 22;
            const cols = Math.max(1, Math.floor((gridWidth + gap) / (cardWidth + gap)));

            if (key === 'ArrowRight') {
              e.preventDefault();
              if (curIndex + 1 < cards.length) {
                cards[curIndex + 1].focus();
              } else if (renderedCount < currentFilteredList.length) {
                renderNextBatch();
                setTimeout(() => {
                  const updatedCards = elements.mediaGrid.querySelectorAll('.media-card');
                  if (updatedCards[curIndex + 1]) updatedCards[curIndex + 1].focus();
                }, 60);
              }
            } else if (key === 'ArrowLeft') {
              e.preventDefault();
              if (curIndex > 0) {
                cards[curIndex - 1].focus();
              }
            } else if (key === 'ArrowDown') {
              e.preventDefault();
              const nextRowIdx = curIndex + cols;
              if (nextRowIdx < cards.length) {
                cards[nextRowIdx].focus();
              } else if (renderedCount < currentFilteredList.length) {
                renderNextBatch();
                setTimeout(() => {
                  const updatedCards = elements.mediaGrid.querySelectorAll('.media-card');
                  if (updatedCards[nextRowIdx]) updatedCards[nextRowIdx].focus();
                }, 60);
              }
            } else if (key === 'ArrowUp') {
              e.preventDefault();
              const prevRowIdx = curIndex - cols;
              if (prevRowIdx >= 0) {
                cards[prevRowIdx].focus();
              } else {
                if (elements.categorySelect) elements.categorySelect.focus();
              }
            } else if (key === 'Enter' || key === ' ') {
              e.preventDefault();
              active.click();
            }
          }
        }
      });
    }

    // ==========================================
    // HISTÓRICO "ASSISTIDOS" — equivalente ao APK
    // Salva somente os IDs, em ordem do mais recente para o mais antigo.
    // ==========================================
    const USER_PREFERENCES_LOCAL_KEY = 'andplay_web_preferences_v1';

    function saveLocalPreference(key, value) {
      try {
        const raw = localStorage.getItem(USER_PREFERENCES_LOCAL_KEY);
        const prefs = raw ? JSON.parse(raw) : {};
        prefs[key] = value;
        localStorage.setItem(USER_PREFERENCES_LOCAL_KEY, JSON.stringify(prefs));
        window.dispatchEvent(new CustomEvent('andplay:local-change', {
          detail: { kind: 'preference', key, value }
        }));
      } catch (e) {}
    }

    function getPreferredAudioPreference() {
      const local = localStorage.getItem('andplay_preferred_audio');
      if (local) return local;
      try {
        const prefs = JSON.parse(localStorage.getItem(USER_PREFERENCES_LOCAL_KEY) || '{}');
        if (prefs.preferred_audio) return prefs.preferred_audio;
      } catch (_) {}
      return 'dub'; // Padrão inicial: Dublado. Quando o usuário escolhe Legendado, Legendado vira o padrão.
    }

    function getPreferredQualityPreference() {
      const local = localStorage.getItem('andplay_preferred_quality');
      if (local) return local;
      try {
        const prefs = JSON.parse(localStorage.getItem(USER_PREFERENCES_LOCAL_KEY) || '{}');
        if (prefs.preferred_quality) return prefs.preferred_quality;
      } catch (_) {}
      return isHevcSupported() ? '4k' : 'fhd'; // Padrão: 4K se o hardware suportar HEVC, senão Full HD (H.264)
    }

    function setPreferredAudioPreference(val, sync = true) {
      if (!val) return;
      localStorage.setItem('andplay_preferred_audio', val);
      if (elements.userPrefAudioSelect) elements.userPrefAudioSelect.value = val;
      if (sync) {
        saveLocalPreference('preferred_audio', val);
        window.AndPlayAccount?.queueSyncPreference?.();
      }
    }

    function setPreferredQualityPreference(val, sync = true) {
      if (!val) return;
      localStorage.setItem('andplay_preferred_quality', val);
      if (elements.userPrefQualitySelect) elements.userPrefQualitySelect.value = val;
      if (sync) {
        saveLocalPreference('preferred_quality', val);
        window.AndPlayAccount?.queueSyncPreference?.();
      }
    }

    // Salva a versão explicitamente escolhida pelo usuário como o novo padrão da conta
    function applyUserChosenVersionPreference(versionObj) {
      if (!versionObj || !versionObj.versionInfo) return;
      const t = versionObj.versionInfo.type;
      if (t === '4k_leg' || t === '4k_leg_hybrid') {
        setPreferredAudioPreference('leg');
        setPreferredQualityPreference('4k');
      } else if (t === '4k_dub') {
        setPreferredAudioPreference('dub');
        setPreferredQualityPreference('4k');
      } else if (t === 'legendado') {
        setPreferredAudioPreference('leg');
      } else if (t === 'dublado') {
        setPreferredAudioPreference('dub');
      }
    }

    const USER_TASTE_KEY = 'andplay_web_taste_v1';
    const HOME_RATING_CACHE_KEY = 'andplay_web_imdb_ratings_v1';
    const HOME_RATING_TTL_MS = 7 * 24 * 60 * 60 * 1000;
    const LIVE_HISTORY_KEY = 'andplay_web_live_history_v1';
    const WATCH_STATS_KEY = 'andplay_web_watch_stats_v1';
    const WATCH_DEVICE_ID_KEY = 'andplay_web_device_id_v1';
    const WATCH_STATS_VERSION = 1;
    const LIVE_HISTORY_LIMIT = 100;
    let homeRatingCacheMemory = null;

    function getWatchDeviceId() {
      try {
        let id = localStorage.getItem(WATCH_DEVICE_ID_KEY);
        if (!id) {
          id = (crypto?.randomUUID ? crypto.randomUUID() : 'device-' + Date.now() + '-' + Math.random().toString(36).slice(2));
          localStorage.setItem(WATCH_DEVICE_ID_KEY, id);
        }
        return id;
      } catch (e) {
        return 'device-fallback';
      }
    }

    function readWatchStats() {
      try {
        const raw = localStorage.getItem(WATCH_STATS_KEY);
        const parsed = raw ? JSON.parse(raw) : null;
        if (!parsed || parsed.version !== WATCH_STATS_VERSION || typeof parsed.devices !== 'object') {
          return { version: WATCH_STATS_VERSION, devices: {} };
        }
        return parsed;
      } catch (e) {
        return { version: WATCH_STATS_VERSION, devices: {} };
      }
    }

    function writeWatchStats(stats) {
      try {
        localStorage.setItem(WATCH_STATS_KEY, JSON.stringify(stats));
        window.AndPlayAccount?.queueSyncProgress?.(30000);
      } catch (e) {}
    }

    function getWatchDeviceBucket(stats) {
      const deviceId = getWatchDeviceId();
      if (!stats.devices[deviceId] || typeof stats.devices[deviceId] !== 'object') {
        stats.devices[deviceId] = {
          movieSeconds: 0,
          seriesSeconds: 0,
          liveSeconds: 0,
          genreSeconds: {},
          updatedAt: 0
        };
      }
      return stats.devices[deviceId];
    }

    function getAggregateWatchStats() {
      const stats = readWatchStats();
      const aggregate = {
        movieSeconds: 0,
        seriesSeconds: 0,
        liveSeconds: 0,
        genreSeconds: {}
      };

      Object.values(stats.devices || {}).forEach(bucket => {
        aggregate.movieSeconds += Number(bucket?.movieSeconds || 0);
        aggregate.seriesSeconds += Number(bucket?.seriesSeconds || 0);
        aggregate.liveSeconds += Number(bucket?.liveSeconds || 0);
        Object.entries(bucket?.genreSeconds || {}).forEach(([theme, seconds]) => {
          aggregate.genreSeconds[theme] = Number(aggregate.genreSeconds[theme] || 0) + Number(seconds || 0);
        });
      });

      return aggregate;
    }

    function recordWatchSeconds(type, seconds, item) {
      const value = Math.max(0, Math.min(Number(seconds) || 0, 30));
      if (value < 0.5) return;

      const normalizedType = type === 'series' ? 'series' : (type === 'live' ? 'live' : 'movie');
      const stats = readWatchStats();
      const bucket = getWatchDeviceBucket(stats);
      const field = normalizedType === 'series' ? 'seriesSeconds' : (normalizedType === 'live' ? 'liveSeconds' : 'movieSeconds');
      bucket[field] = Number(bucket[field] || 0) + value;

      if (normalizedType !== 'live' && item) {
        getHomeThemesForItem(item).forEach((theme, index) => {
          const weight = index === 0 ? 1 : 0.72;
          bucket.genreSeconds[theme] = Number(bucket.genreSeconds[theme] || 0) + value * weight;
        });
      }

      bucket.updatedAt = Date.now();
      writeWatchStats(stats);
    }

    function readLiveHistory() {
      try {
        const raw = localStorage.getItem(LIVE_HISTORY_KEY);
        const parsed = raw ? JSON.parse(raw) : [];
        return Array.isArray(parsed) ? parsed : [];
      } catch (e) {
        return [];
      }
    }

    function writeLiveHistory(history) {
      try {
        localStorage.setItem(LIVE_HISTORY_KEY, JSON.stringify(history.slice(0, LIVE_HISTORY_LIMIT)));
        window.AndPlayAccount?.queueSyncWatched?.(1500);
      } catch (e) {}
    }

    function recordLiveChannelHistory(channel) {
      if (!channel) return;
      const id = String(channel.id || channel.channelSlug || channel.name || '');
      if (!id) return;

      const previous = readLiveHistory().filter(item => String(item.id) !== id);
      previous.unshift({
        id,
        name: String(channel.name || 'Canal de TV'),
        logo: String(channel.logo || ''),
        category: String(channel.categoryLabel || channel.category || ''),
        updatedAt: Date.now(),
        count: Number(readLiveHistory().find(item => String(item.id) === id)?.count || 0) + 1
      });
      writeLiveHistory(previous);
      saveLocalPreference('live_history', previous.slice(0, LIVE_HISTORY_LIMIT));
    }

    function formatAccountDuration(seconds) {
      const total = Math.max(0, Math.round(Number(seconds) || 0));
      const days = Math.floor(total / 86400);
      const hours = Math.floor((total % 86400) / 3600);
      const minutes = Math.floor((total % 3600) / 60);
      if (days > 0) return days + 'd ' + hours + 'h';
      if (hours > 0) return hours + 'h ' + minutes + 'min';
      return minutes + ' min';
    }

    function getAccountUsageSnapshot() {
      let activity = [];
      try {
        const raw = localStorage.getItem(WATCHED_ACTIVITY_KEY);
        const parsed = raw ? JSON.parse(raw) : [];
        activity = Array.isArray(parsed) ? parsed : [];
      } catch (e) {}

      const activityTime = (type, id) => {
        const targetType = type === 'movie' ? 'movies' : 'series';
        const entry = activity.find(item =>
          item && item.type === targetType && String(item.id) === String(id)
        );
        return Number(entry?.updatedAt || 0);
      };

      const movies = getWatchedIds('movies').map((id, index) => ({
        id,
        type: 'movie',
        order: index,
        updatedAt: activityTime('movie', id),
        item: findHistoryCatalogItem('movie', id) || buildHistoryFallbackGroup('movie', id)
      }));
      const series = getWatchedIds('series').map((id, index) => ({
        id,
        type: 'series',
        order: index,
        updatedAt: activityTime('series', id),
        item: findHistoryCatalogItem('series', id) || buildHistoryFallbackGroup('series', id)
      }));

      const allWatched = [...movies, ...series].map(entry => ({
        ...entry,
        title: cleanDisplayTitle(entry.item?.name || entry.item?.title || remoteHistoryMetadata[entry.type === 'series' ? 'series' : 'movies'].get(String(entry.id))?.title || (entry.type === 'series' ? 'Série ' + entry.id : 'Filme ' + entry.id)),
        poster: entry.type === 'series'
          ? (entry.item?.cover || entry.item?.stream_icon || remoteHistoryMetadata.series.get(String(entry.id))?.poster || '')
          : (getBestPosterUrl(entry.item?.primaryItem || entry.item || {}) || entry.item?.poster || entry.item?.stream_icon || remoteHistoryMetadata.movies.get(String(entry.id))?.poster || ''),
        genres: getHomeThemesForItem(entry.item || {})
      }));

      const watchTime = getAggregateWatchStats();
      const genreCounts = {};
      allWatched.forEach(entry => {
        entry.genres.forEach(theme => {
          genreCounts[theme] = Number(genreCounts[theme] || 0) + 1;
        });
      });

      return {
        totals: {
          movies: movies.length,
          series: series.length,
          liveChannels: readLiveHistory().length,
          liveSessions: readLiveHistory().reduce((sum, item) => sum + Number(item.count || 0), 0)
        },
        watchTime,
        genres: Object.entries(genreCounts)
          .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'pt-BR'))
          .slice(0, 12)
          .map(([name, count]) => ({
            name,
            count,
            seconds: Number(watchTime.genreSeconds?.[name] || 0)
          })),
        history: {
          movies: movies.map(entry => ({ ...entry, title: entry.item?.name || entry.item?.title || 'Filme ' + entry.id }))
            .slice(0, 50),
          series: series.map(entry => ({ ...entry, title: entry.item?.name || entry.item?.title || 'Série ' + entry.id }))
            .slice(0, 50),
          channels: readLiveHistory().slice(0, 50)
        }
      };
    }

    function mergeWatchStats(remote) {
      if (!remote || remote.version !== WATCH_STATS_VERSION || typeof remote.devices !== 'object') return;
      const local = readWatchStats();
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
          localBucket.genreSeconds[theme] = Math.max(Number(localBucket.genreSeconds[theme] || 0), Number(seconds || 0));
        });
      });
      writeWatchStats(local);
    }

    function mergeLiveHistory(remote) {
      if (!Array.isArray(remote)) return;
      const map = new Map();
      [...readLiveHistory(), ...remote].forEach(item => {
        if (!item?.id) return;
        const id = String(item.id);
        const current = map.get(id);
        if (!current || Number(item.updatedAt || 0) > Number(current.updatedAt || 0)) {
          map.set(id, { ...item, id });
        }
      });
      writeLiveHistory([...map.values()].sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0)));
    }

    function readTasteProfile() {
      try {
        const raw = localStorage.getItem(USER_TASTE_KEY);
        const parsed = raw ? JSON.parse(raw) : null;
        return {
          genres: (parsed && parsed.genres && typeof parsed.genres === 'object') ? parsed.genres : {},
          types: (parsed && parsed.types && typeof parsed.types === 'object') ? parsed.types : { movie: 0, series: 0 },
          eras: (parsed && parsed.eras && typeof parsed.eras === 'object') ? parsed.eras : {},
          recent: Array.isArray(parsed?.recent) ? parsed.recent : []
        };
      } catch (e) {
        return { genres: {}, types: { movie: 0, series: 0 }, eras: {}, recent: [] };
      }
    }

    function writeTasteProfile(profile) {
      try {
        localStorage.setItem(USER_TASTE_KEY, JSON.stringify(profile));
        saveLocalPreference('taste_profile', profile);
      } catch (e) {}
    }

    function findCatalogItemForTaste(type, id) {
      if (type === 'series') {
        return (fullSeriesCache || []).find(item => String(item.series_id) === String(id)) || null;
      }
      return (fullMoviesCache || []).find(item => movieGroupMatchesWatchedId(item, id)) || null;
    }

    function recordTasteFromPlayback(type, id) {
      const normalizedType = type === 'series' ? 'series' : 'movie';
      const normalizedId = String(id ?? '');
      if (!normalizedId) return;

      const item = findCatalogItemForTaste(normalizedType, normalizedId);
      if (!item) return;

      const themeItem = normalizedType === 'series'
        ? {
            type: 'series',
            year: item.year,
            genre: item.genre || item.genre_name || '',
            imdbId: item.imdbId || item.imdb_id || '',
            added: item.added,
            last_modified: item.last_modified
          }
        : {
            type: 'movie',
            year: item.year || item.primaryItem?.year,
            primaryItem: item.primaryItem || item,
            genre: item.genre || item.primaryItem?.genre || item.genre_name || '',
            imdbId: item.imdbId || item.imdb_id || item.primaryItem?.imdbId || item.primaryItem?.imdb_id || '',
            added: item.added,
            last_modified: item.last_modified
          };

      const themes = getHomeThemesForItem(themeItem);
      if (!themes.length) return;

      const profile = readTasteProfile();
      const contentKey = normalizedType + ':' + normalizedId;
      const recentHit = profile.recent.find(entry =>
        entry && entry.key === contentKey && Date.now() - Number(entry.at || 0) < 12 * 60 * 60 * 1000
      );
      if (recentHit) return;

      profile.types[normalizedType] = Number(profile.types[normalizedType] || 0) + 1;

      // Detecta época / década de preferência
      const y = getItemYear(themeItem);
      if (y > 0) {
        profile.eras = profile.eras || {};
        const era = y >= 2023 ? '2020s' : (y >= 2010 ? '2010s' : (y >= 2000 ? '2000s' : 'classicos'));
        profile.eras[era] = Number(profile.eras[era] || 0) + 1;
      }

      themes.forEach((theme, index) => {
        profile.genres[theme] = Number(profile.genres[theme] || 0) + (index === 0 ? 3 : 1.5);
      });

      profile.recent.unshift({ key: contentKey, at: Date.now() });
      profile.recent = profile.recent.slice(0, 80);

      // Evita que histórico antigo domine eternamente
      const values = Object.entries(profile.genres);
      if (values.length > 40) {
        values.sort((a, b) => Number(b[1]) - Number(a[1]));
        profile.genres = Object.fromEntries(values.slice(0, 40));
      }

      writeTasteProfile(profile);
    }

    function getTasteThemeWeight(theme) {
      return Number(readTasteProfile().genres?.[theme] || 0);
    }

    function getTasteTopThemes(limit = 5) {
      const profile = readTasteProfile();
      const combined = { ...(profile.genres || {}) };

      // Enriquecer dinamicamente com os assistidos recentes
      const history = getNormalizedRemoteHistory();
      if (Array.isArray(history)) {
        history.slice(0, 15).forEach((entry, idx) => {
          const item = resolveHomeWatchedItem(entry.type, entry.id);
          if (item) {
            const themes = getHomeThemesForItem(item);
            const w = Math.max(1, 4 - Math.floor(idx / 4));
            themes.forEach(t => {
              combined[t] = Number(combined[t] || 0) + w;
            });
          }
        });
      }

      return Object.entries(combined)
        .filter(([, weight]) => Number(weight) > 0)
        .sort((a, b) => Number(b[1]) - Number(a[1]))
        .slice(0, limit)
        .map(([theme]) => theme);
    }

    function getTasteTypePreference() {
      const profile = readTasteProfile();
      let movieScore = Number(profile.types?.movie || 0);
      let seriesScore = Number(profile.types?.series || 0);

      // Enriquecer com os assistidos recentes (peso decrescente por recência)
      const history = getNormalizedRemoteHistory();
      if (Array.isArray(history)) {
        history.slice(0, 30).forEach((entry, idx) => {
          const recencyWeight = Math.max(0.6, 2.0 - (idx * 0.06));
          if (entry.type === 'movie') movieScore += recencyWeight;
          else if (entry.type === 'series') seriesScore += recencyWeight;
        });
      }

      const total = movieScore + seriesScore;
      if (total === 0) {
        return { dominant: 'mixed', movieRatio: 0.5, seriesRatio: 0.5 };
      }

      const movieRatio = movieScore / total;
      const seriesRatio = seriesScore / total;

      // Se um dos lados tiver pelo menos 62%, domina o topo da tela
      if (movieRatio >= 0.62) {
        return { dominant: 'movie', movieRatio, seriesRatio };
      } else if (seriesRatio >= 0.62) {
        return { dominant: 'series', movieRatio, seriesRatio };
      } else {
        // Misturado: 50%/50%, 60%/40%, 40%/60%
        return { dominant: 'mixed', movieRatio, seriesRatio };
      }
    }

    function readHomeRatingCache() {
      if (homeRatingCacheMemory) return homeRatingCacheMemory;
      try {
        const raw = localStorage.getItem(HOME_RATING_CACHE_KEY);
        const parsed = raw ? JSON.parse(raw) : {};
        homeRatingCacheMemory = parsed && typeof parsed === 'object' ? parsed : {};
      } catch (e) {
        homeRatingCacheMemory = {};
      }
      return homeRatingCacheMemory;
    }

    function getHomeRatingInfo(item) {
      const sourceItem = item?.item || item;
      const imdbId = sourceItem?.imdbId || sourceItem?.imdb_id || item?.imdbId || item?.imdb_id || '';
      const cache = readHomeRatingCache();
      const cached = imdbId ? cache[String(imdbId)] : null;
      const cachedRating = cached && Date.now() - Number(cached.fetchedAt || 0) < HOME_RATING_TTL_MS
        ? Number(cached.rating || 0)
        : 0;

      if (cachedRating > 0) {
        return { value: cachedRating, source: 'IMDb' };
      }

      const catalogRating = Number(sourceItem?.rating || item?.rating || 0);
      return catalogRating > 0
        ? { value: catalogRating, source: '' }
        : { value: 0, source: '' };
    }

    async function enrichHomeRatings() {
      const cache = readHomeRatingCache();
      const candidates = [...getHomeCatalogItems('series'), ...getHomeCatalogItems('movie')]
        .filter(item => item.imdbId || item.item?.imdbId || item.item?.imdb_id)
        .sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0))
        .slice(0, 36);

      const queue = candidates.filter(item => {
        const imdbId = item.imdbId || item.item?.imdbId || item.item?.imdb_id;
        const saved = imdbId ? cache[String(imdbId)] : null;
        return !saved || Date.now() - Number(saved.fetchedAt || 0) >= HOME_RATING_TTL_MS;
      });

      let cursor = 0;
      async function worker() {
        while (cursor < queue.length) {
          const item = queue[cursor++];
          const imdbId = item.imdbId || item.item?.imdbId || item.item?.imdb_id;
          if (!imdbId) continue;

          try {
            const catalogType = item.type === 'series' ? 'series' : 'movie';
            const response = await fetch('https://v3-cinemeta.strem.io/meta/' + catalogType + '/' + encodeURIComponent(imdbId) + '.json');
            if (!response.ok) continue;
            const data = await response.json();
            const meta = data?.meta;
            if (!meta) continue;

            const rating = Number(meta.imdbRating || 0);
            const genres = Array.isArray(meta.genres) ? meta.genres : [];
            const cast = Array.isArray(meta.cast) ? meta.cast : [];
            const director = Array.isArray(meta.director) ? meta.director : (meta.director ? [meta.director] : []);
            cache[String(imdbId)] = {
              rating: rating > 0 ? rating : 0,
              genres,
              cast,
              director,
              fetchedAt: Date.now()
            };
          } catch (e) {}
        }
      }

      await Promise.all([worker(), worker(), worker(), worker()]);
      homeRatingCacheMemory = cache;
      try {
        localStorage.setItem(HOME_RATING_CACHE_KEY, JSON.stringify(cache));
      } catch (e) {}
    }

    function getWatchedStorageKey(type) {
      return type === 'series' ? WATCHED_SERIES_STORAGE_KEY : WATCHED_MOVIES_STORAGE_KEY;
    }

    function getNormalizedRemoteHistory(type = null) {
      const remoteType = type === 'series' ? 'series' : (type === 'movies' ? 'movies' : undefined);
      const remote = window.AndPlayAccount?.getRemoteWatchHistory?.(remoteType) || [];
      const output = [];
      const seenSeries = new Set();

      remote.forEach(entry => {
        const entryType = entry?.type === 'series' ? 'series' : 'movie';
        let id = String(entry?.id || '').trim();
        if (!id) return;

        // O histórico correto de séries usa series_id. Apenas dados legados podem
        // conter o ID do episódio; nesse caso o watch_progress já sincronizado informa
        // qual série aquele episódio pertence.
        if (entryType === 'series' && !findHistoryCatalogItem('series', id)) {
          const progress = getVodProgress('series', id);
          let seriesId = String(progress?.seriesId || '').trim();
          if (!seriesId) {
            const rp = window.AndPlayAccount?.getRemoteWatchProgress?.() || [];
            const found = rp.find(p => String(p.content_id) === id && p.series_id);
            if (found && found.series_id) seriesId = String(found.series_id).trim();
          }
          if (seriesId) id = seriesId;
        }

        if (entryType === 'series') {
          if (seenSeries.has(id)) return;
          seenSeries.add(id);
        }
        output.push({ ...entry, type: entryType, id });
      });

      return output.sort((a, b) =>
        Number(b.updatedAt || 0) - Number(a.updatedAt || 0)
      );
    }

    function getWatchedIds(type) {
      const normalizedType = type === 'series' ? 'series' : 'movies';
      const ids = [];
      const seen = new Set();

      const addId = value => {
        const id = String(value ?? '').trim();
        if (!id || seen.has(id)) return;
        seen.add(id);
        ids.push(id);
      };

      const useRemoteHistory =
        window.AndPlayAccount?.isSignedIn?.() &&
        window.AndPlayAccount?.isRemoteWatchHistoryLoaded?.();

      if (useRemoteHistory) {
        getNormalizedRemoteHistory(normalizedType === 'series' ? 'series' : 'movies')
          .forEach(entry => addId(entry.id));
        return ids.slice(0, WATCHED_LIMIT);
      }

      try {
        const raw = localStorage.getItem(getWatchedStorageKey(normalizedType));
        const parsed = raw ? JSON.parse(raw) : [];
        if (Array.isArray(parsed)) parsed.forEach(addId);
      } catch (e) {}

      try {
        const rawActivity = localStorage.getItem(WATCHED_ACTIVITY_KEY);
        const activity = rawActivity ? JSON.parse(rawActivity) : [];
        if (Array.isArray(activity)) {
          activity
            .filter(item => {
              const itemType = item?.type === 'movies' || item?.type === 'movie' ? 'movies' : 'series';
              return itemType === normalizedType;
            })
            .forEach(item => addId(item?.id));
        }
      } catch (e) {}

      try {
        const progressPrefix = VOD_PROGRESS_PREFIX + (normalizedType === 'series' ? 'series_' : 'movie_');
        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i);
          if (!key || !key.startsWith(progressPrefix)) continue;
          addId(key.slice(progressPrefix.length));
          if (ids.length >= WATCHED_LIMIT) break;
        }
      } catch (e) {}

      return ids.slice(0, WATCHED_LIMIT);
    }

    function removeLocalWatched(type, id) {
      const normalizedType = type === 'series' ? 'series' : 'movies';
      const normalizedId = String(id ?? '').trim();
      if (!normalizedId) return;

      try {
        const historyKey = getWatchedStorageKey(normalizedType);
        const history = JSON.parse(localStorage.getItem(historyKey) || '[]');
        localStorage.setItem(historyKey, JSON.stringify(
          Array.isArray(history) ? history.filter(value => String(value) !== normalizedId) : []
        ));
      } catch (e) {}

      try {
        const activity = JSON.parse(localStorage.getItem(WATCHED_ACTIVITY_KEY) || '[]');
        localStorage.setItem(WATCHED_ACTIVITY_KEY, JSON.stringify(
          Array.isArray(activity)
            ? activity.filter(item => !(item && item.type === normalizedType && String(item.id) === normalizedId))
            : []
        ));
      } catch (e) {}

      // O progresso é apagado junto para que, sem login, ele também não faça
      // o título reaparecer no histórico automaticamente.
      try {
        const prefix = VOD_PROGRESS_PREFIX + (normalizedType === 'series' ? 'series_' : 'movie_');
        localStorage.removeItem(prefix + normalizedId);
        if (normalizedType === 'series') {
          for (let i = localStorage.length - 1; i >= 0; i--) {
            const key = localStorage.key(i);
            if (!key || !key.startsWith(VOD_PROGRESS_PREFIX + 'series_')) continue;
            const progress = getVodProgress('series', key.slice((VOD_PROGRESS_PREFIX + 'series_').length));
            if (String(progress?.seriesId || '') === normalizedId) localStorage.removeItem(key);
          }
        }
      } catch (e) {}

      window.dispatchEvent(new CustomEvent('andplay:local-change', {
        detail: { kind: 'watched-removed', mediaType: normalizedType, id: normalizedId }
      }));
    }

    async function removeWatched(type, id) {
      const normalizedType = type === 'series' ? 'series' : 'movies';
      const normalizedId = String(id ?? '').trim();
      if (!normalizedId) return;

      removeLocalWatched(normalizedType, normalizedId);
      if (window.AndPlayAccount?.removeWatched) {
        await window.AndPlayAccount.removeWatched(normalizedType, normalizedId);
      }
      if (isWatchedView) showWatchedContent();
    }

    function saveWatchedId(type, id) {
      if (id === null || id === undefined || String(id).trim() === '') return;
      const normalizedId = String(id);
      try {
        const list = getWatchedIds(type).filter(item => item !== normalizedId);
        list.unshift(normalizedId);
        localStorage.setItem(getWatchedStorageKey(type), JSON.stringify(list.slice(0, WATCHED_LIMIT)));

        const activityRaw = localStorage.getItem(WATCHED_ACTIVITY_KEY);
        const activity = activityRaw ? JSON.parse(activityRaw) : [];
        const nextActivity = Array.isArray(activity)
          ? activity.filter(item => !(item && item.type === type && String(item.id) === normalizedId))
          : [];
        nextActivity.unshift({ type, id: normalizedId, updatedAt: Date.now() });
        localStorage.setItem(WATCHED_ACTIVITY_KEY, JSON.stringify(nextActivity.slice(0, WATCHED_LIMIT)));

        recordTasteFromPlayback(type, normalizedId);

        window.AndPlayAccount?.recordWatched?.(type, normalizedId).catch(() => {});
        window.dispatchEvent(new CustomEvent('andplay:local-change', {
          detail: { kind: 'watched', mediaType: type, id: normalizedId }
        }));
      } catch (e) {}
    }

    // ==========================================
    // CONTINUAR DE ONDE PAROU — equivalente ao APK
    // Salva progresso VOD localmente no navegador.
    // ==========================================
    const VOD_PROGRESS_PREFIX = 'andplay_web_vod_progress_';
    const VOD_PROGRESS_MIN_SECONDS = 5;
    const VOD_PROGRESS_COMPLETE_REMAINING_SECONDS = 30;
    const VOD_PROGRESS_COMPLETE_PERCENT = 0.95;
    let vodProgressSaveTimer = null;

    function getVodProgressKey(type, id) {
      if (id === null || id === undefined || String(id).trim() === '') return '';
      return VOD_PROGRESS_PREFIX + (type === 'series' ? 'series_' : 'movie_') + String(id);
    }

    function getVodProgress(type, id) {
      const key = getVodProgressKey(type, id);
      if (!key) return null;
      try {
        const raw = localStorage.getItem(key);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (!parsed || !Number.isFinite(Number(parsed.position))) return null;
        return {
          position: Math.max(0, Number(parsed.position)),
          duration: Number(parsed.duration) > 0 ? Number(parsed.duration) : 0,
          updatedAt: Number(parsed.updatedAt) || 0,
          title: String(parsed.title || ''),
          poster: String(parsed.poster || ''),
          seriesId: String(parsed.seriesId || ''),
          seasonNum: parsed.seasonNum ? Number(parsed.seasonNum) : 0,
          episodeNum: parsed.episodeNum ? Number(parsed.episodeNum) : 0
        };
      } catch (e) {
        return null;
      }
    }

    const remoteHistoryMetadata = {
      movies: new Map(),
      series: new Map()
    };
    let remoteSeriesHistoryRevalidationAt = 0;
    const REMOTE_SERIES_HISTORY_REVALIDATION_COOLDOWN_MS = 5 * 60 * 1000;

    function getLatestSeriesProgress(seriesId) {
      let latest = null;
      const target = String(seriesId || '');
      if (!target) return null;
      try {
        const direct = getVodProgress('series', target);
        if (direct && direct.title) {
          latest = direct;
        }

        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i);
          if (!key || !key.startsWith(VOD_PROGRESS_PREFIX + 'series_')) continue;
          const entry = readJson(key, null);
          if (!entry) continue;
          const isMatch = String(entry.seriesId || '') === target || key === (VOD_PROGRESS_PREFIX + 'series_' + target);
          if (!isMatch) continue;
          if (!latest || Number(entry.updatedAt || 0) > Number(latest.updatedAt || 0)) {
            latest = {
              position: Math.max(0, Number(entry.position) || 0),
              duration: Number(entry.duration) > 0 ? Number(entry.duration) : 0,
              updatedAt: Number(entry.updatedAt) || 0,
              title: String(entry.title || ''),
              poster: String(entry.poster || ''),
              seriesId: String(entry.seriesId || target),
              seasonNum: Number(entry.seasonNum) || 0,
              episodeNum: Number(entry.episodeNum) || 0
            };
          }
        }
      } catch (e) {}

      // Fallback para históricos em outro navegador ou em um cache local que
      // ainda não recebeu o watch_progress remoto. O progresso remoto continua
      // usando episode_id como content_id e series_id como vínculo da série.
      if (!latest || !latest.title) {
        try {
          const remoteProgress = window.AndPlayAccount?.getRemoteWatchProgress?.() || [];
          remoteProgress
            .filter(item =>
              item?.content_type === 'series' &&
              (String(item?.series_id || '') === target || String(item?.content_id || '') === target) &&
              Number.isFinite(Date.parse(item?.updated_at || ''))
            )
            .sort((a, b) => Date.parse(b.updated_at || '') - Date.parse(a.updated_at || ''))
            .some(item => {
              latest = {
                position: Math.max(0, Number(item.position) || 0),
                duration: Number(item.duration) > 0 ? Number(item.duration) : 0,
                updatedAt: Date.parse(item.updated_at || '') || 0,
                title: String(item.title || ''),
                poster: String(item.poster || ''),
                seriesId: String(item.series_id || target),
                seasonNum: Number(item.season_num) || 0,
                episodeNum: Number(item.episode_num) || 0
              };
              return true;
            });
        } catch (e) {}
      }

      return latest;
    }

    function getHistoryProgressFallback(type, id) {
      if (type === 'series') {
        const direct = getVodProgress('series', id);
        const latest = getLatestSeriesProgress(id);
        if (direct && latest) {
          return (Number(direct.updatedAt || 0) >= Number(latest.updatedAt || 0)) ? direct : latest;
        }
        return direct || latest || null;
      }
      return getVodProgress('movie', id);
    }

    function getSeriesHistoryTitle(progress) {
      const rawTitle = String(progress?.title || '').trim();
      if (!rawTitle) return '';

      // O progresso de episódio pode ser salvo como "Nome da série - Episódio",
      // "Nome da série • T01:E01", "Nome da série T01:E01", "Nome S01E01", etc.
      // Remove sufixos de temporada/episódio para obter o nome limpo da série.
      let cleaned = rawTitle
        .replace(/\s*[•·-]\s*(?:T|S|TEMP|TEMPORADA|EP|EPISODIO|EPISÓDIO)?\s*\d+.*$/i, '')
        .replace(/\s+(?:T\d+:E\d+|S\d+E\d+|T\d+\s*E\d+|S\d+\s*E\d+).*$/i, '')
        .replace(/\s+(?:TEMPORADA|TEMP|SEASON)\s*\d+.*$/i, '')
        .replace(/\s+(?:EPISODIO|EPISÓDIO|EP)\s*\d+.*$/i, '')
        .trim();

      const separatorIndex = cleaned.indexOf(' - ');
      if (separatorIndex > 0) {
        const seriesTitle = cleaned.slice(0, separatorIndex).trim();
        if (seriesTitle) return seriesTitle;
      }
      return cleaned || rawTitle;
    }

    function buildHistoryFallbackGroup(type, id) {
      const key = String(id || '');
      if (!key) return null;
      const metadata = remoteHistoryMetadata[type === 'series' ? 'series' : 'movies'].get(key) || {};
      const progress = getHistoryProgressFallback(type, key);
      const progressTitle = type === 'series'
        ? getSeriesHistoryTitle(progress)
        : String(progress?.title || '').trim();
      let title = String(metadata.title || progressTitle || '').trim();
      let poster = String(metadata.poster || progress?.poster || '').trim();

      // Se for série e ainda não tiver título/pôster, tenta buscar pelo seriesId vinculado
      if (type === 'series') {
        const linkedSeriesId = String(progress?.seriesId || metadata.series_id || metadata.seriesId || '').trim();
        if (linkedSeriesId && linkedSeriesId !== key) {
          const linkedMeta = remoteHistoryMetadata.series.get(linkedSeriesId) || {};
          if (!title && linkedMeta.title) title = linkedMeta.title;
          if (!poster && linkedMeta.poster) poster = linkedMeta.poster;
        }
        if (!title) {
          const epState = getSeriesEpisodeState({ id: key });
          if (epState?.title) {
            title = getSeriesHistoryTitle(epState);
          }
        }
      }

      if (type === 'series') {
        const seriesId = String(metadata.series_id || metadata.seriesId || progress?.seriesId || key);
        const displayTitle = title || 'Série ' + key;
        const item = {
          isGroup: true,
          series_id: seriesId,
          name: displayTitle,
          title: displayTitle,
          cover: poster,
          stream_icon: poster,
          plot: metadata.plot || '',
          genre: metadata.genre || '',
          year: metadata.year || progress?.year || '',
          rating: metadata.rating || '',
          primaryItem: {
            series_id: seriesId,
            name: displayTitle,
            cover: poster,
            stream_icon: poster
          },
          versions: [{
            item: {
              series_id: seriesId,
              name: displayTitle,
              cover: poster,
              stream_icon: poster
            },
            versionInfo: detectSeriesVersion({ name: displayTitle, category_id: metadata.category_id || '' }),
            seriesId
          }]
        };
        return item;
      }

      const item = {
        isGroup: true,
        stream_id: key,
        name: title || 'Filme ' + key,
        title: title || 'Filme ' + key,
        poster,
        stream_icon: poster,
        plot: metadata.plot || '',
        genre: metadata.genre || '',
        year: metadata.year || '',
        rating: metadata.rating || '',
        primaryItem: {
          stream_id: key,
          name: title || 'Filme ' + key,
          stream_icon: poster,
          poster
        },
        versions: [{
          item: {
            stream_id: key,
            name: title || 'Filme ' + key,
            stream_icon: poster,
            poster
          },
          versionInfo: detectMovieVersion({ name: title || '' }),
          streamId: key,
          ext: metadata.ext || 'mp4'
        }]
      };
      return item;
    }

    function findHistoryCatalogItem(type, id) {
      const key = String(id || '');
      if (!key) return null;
      if (type === 'movie') {
        const catalogs = [
          ...(Array.isArray(fullMoviesCache) ? fullMoviesCache : []),
          ...(Array.isArray(homeWatchedCatalogFallback.movies) ? homeWatchedCatalogFallback.movies : [])
        ];
        return catalogs.find(item => movieGroupMatchesWatchedId(item, key)) || null;
      }

      const catalogs = [
        ...(Array.isArray(fullSeriesCache) ? fullSeriesCache : []),
        ...(Array.isArray(homeWatchedCatalogFallback.series) ? homeWatchedCatalogFallback.series : [])
      ];
      let found = catalogs.find(item => seriesGroupMatchesWatchedId(item, key)) || null;
      if (found) return found;

      // Se id for um episódio ou versão, tenta resolver o seriesId pelo progresso ou histórico
      const progress = getVodProgress('series', key);
      if (progress?.seriesId && String(progress.seriesId) !== key) {
        found = catalogs.find(item => seriesGroupMatchesWatchedId(item, String(progress.seriesId))) || null;
        if (found) return found;
      }

      const rp = window.AndPlayAccount?.getRemoteWatchProgress?.() || [];
      const remoteProg = rp.find(p => String(p.content_id) === key && p.series_id && String(p.series_id) !== key);
      if (remoteProg?.series_id) {
        found = catalogs.find(item => seriesGroupMatchesWatchedId(item, String(remoteProg.series_id))) || null;
        if (found) return found;
      }

      return null;
    }

    async function hydrateRemoteHistoryMetadata(limit = 12) {
      const history = getNormalizedRemoteHistory();

      // O cache de séries pode ter sido criado antes de itens históricos entrarem
      // ou depois de uma atualização do catálogo. Revalida o catálogo completo uma
      // vez quando houver um series_id do histórico que não esteja nele.
      const missingSeriesHistory = history.some(entry =>
        entry.type === 'series' && !findHistoryCatalogItem('series', String(entry.id || ''))
      );
      const canRevalidateSeriesHistory =
        Date.now() - remoteSeriesHistoryRevalidationAt >= REMOTE_SERIES_HISTORY_REVALIDATION_COOLDOWN_MS;
      if (missingSeriesHistory && canRevalidateSeriesHistory) {
        remoteSeriesHistoryRevalidationAt = Date.now();
        try {
          await loadFullSeries(true);
        } catch (e) {}
      }

      const targets = history.filter(entry => {
        const type = entry.type === 'series' ? 'series' : 'movie';
        const id = String(entry.id || '');
        if (!id) return false;
        if (findHistoryCatalogItem(type, id)) return false;
        const bucket = remoteHistoryMetadata[type === 'series' ? 'series' : 'movies'];
        if (bucket.has(id)) return false;
        const progress = getHistoryProgressFallback(type, id);
        return !progress?.title || !progress?.poster;
      }).slice(0, Math.max(0, Number(limit) || 0));

      let cursor = 0;
      const workers = Array.from({ length: Math.min(4, targets.length) }, async () => {
        while (cursor < targets.length) {
          const entry = targets[cursor++];
          const type = entry.type === 'series' ? 'series' : 'movie';
          const id = String(entry.id || '');
          let targetId = id;
          if (type === 'series') {
            const progress = getVodProgress('series', id);
            if (progress?.seriesId) targetId = String(progress.seriesId).trim();
            else {
              const rp = window.AndPlayAccount?.getRemoteWatchProgress?.() || [];
              const found = rp.find(p => String(p.content_id) === id && p.series_id);
              if (found?.series_id) targetId = String(found.series_id).trim();
            }
          }
          try {
            const data = type === 'movie'
              ? await xtreamApi('get_vod_info', '&vod_id=' + encodeURIComponent(id))
              : await xtreamApi('get_series_info', '&series_id=' + encodeURIComponent(targetId));
            const info = data?.info || data || {};
            const title = String(info.name || info.title || '').trim();
            const poster = String(
              info.movie_image || info.cover_big || info.cover || info.stream_icon || info.poster || ''
            ).trim();
            if (title || poster) {
              const metaObj = {
                title,
                poster,
                plot: String(info.plot || '').trim(),
                genre: String(info.genre || '').trim(),
                year: String(info.year || info.releaseDate || '').slice(0, 4),
                rating: String(info.rating || info.rating_5based || '').trim(),
                series_id: type === 'series' ? String(info.series_id || targetId || id) : '',
                ext: String(info.container_extension || 'mp4')
              };
              const bucket = remoteHistoryMetadata[type === 'series' ? 'series' : 'movies'];
              bucket.set(id, metaObj);
              if (targetId && targetId !== id) bucket.set(targetId, metaObj);
            }
          } catch (e) {}
        }
      });

      await Promise.all(workers);
    }

    function clearVodProgress(type, id) {
      const key = getVodProgressKey(type, id);
      if (!key) return;
      try {
        localStorage.removeItem(key);
        window.dispatchEvent(new CustomEvent('andplay:local-change', {
          detail: { kind: 'progress-cleared', mediaType: type, id: String(id) }
        }));
      } catch (e) {}
    }

    function saveVodProgress(type, id, position, duration, title = '', extra = null) {
      const key = getVodProgressKey(type, id);
      const pos = Number(position);
      const dur = Number(duration);
      if (!key || !Number.isFinite(pos) || pos <= VOD_PROGRESS_MIN_SECONDS) return;

      try {
        if (dur > 0 && (pos >= Math.max(0, dur - VOD_PROGRESS_COMPLETE_REMAINING_SECONDS) || pos >= dur * VOD_PROGRESS_COMPLETE_PERCENT)) {
          localStorage.removeItem(key);
          window.dispatchEvent(new CustomEvent('andplay:local-change', {
            detail: { kind: 'progress-cleared', mediaType: type, id: String(id) }
          }));
          return;
        }

        const record = {
          position: pos,
          duration: dur > 0 ? dur : 0,
          updatedAt: Date.now(),
          title: String(title || ''),
          poster: String(extra?.poster || ''),
          seriesId: String(extra?.seriesId || ''),
          seasonNum: Number(extra?.seasonNum) || 0,
          episodeNum: Number(extra?.episodeNum) || 0
        };
        localStorage.setItem(key, JSON.stringify(record));
        window.dispatchEvent(new CustomEvent('andplay:local-change', {
          detail: { kind: 'progress', mediaType: type, id: String(id), record }
        }));
      } catch (e) {}
    }

    function getCurrentVodProgressMeta() {
      const meta = currentPlaybackMeta;
      if (!meta || (meta.mediaType !== 'movie' && meta.mediaType !== 'series')) return null;
      const id = meta.streamId || meta.mediaMeta?.streamId || meta.mediaMeta?.stream_id;
      if (id === null || id === undefined || String(id).trim() === '') return null;
      return {
        type: meta.mediaType,
        id: String(id),
        title: meta.title || meta.mediaMeta?.title || '',
        poster: String(meta.mediaMeta?.poster || meta.mediaMeta?.groupOrMovie?.cover || ''),
        seriesId: String(meta.mediaMeta?.seriesId || meta.mediaMeta?.selectedVersion?.seriesId || meta.mediaMeta?.ep?.series_id || ''),
        seasonNum: Number(meta.seasonNum || meta.mediaMeta?.seasonNum || meta.mediaMeta?.season) || 0,
        episodeNum: Number(meta.episodeNum || meta.mediaMeta?.episodeNum) || 0
      };
    }

    let watchTimeTrackingTimer = null;
    let watchTimeTrackingMeta = null;
    let watchTimeLastTick = 0;

    function getWatchTimeItem(meta) {
      if (!meta) return null;
      const item = findCatalogItemForTaste(meta.type, meta.id);
      if (!item) return null;
      return meta.type === 'series'
        ? { type: 'series', item, genre: item.genre || item.genre_name || '', imdbId: item.imdbId || item.imdb_id || '' }
        : { type: 'movie', item, primaryItem: item.primaryItem || item, genre: item.genre || item.primaryItem?.genre || item.genre_name || '', imdbId: item.imdbId || item.imdb_id || item.primaryItem?.imdbId || item.primaryItem?.imdb_id || '' };
    }

    function flushWatchTimeTracking() {
      if (!watchTimeTrackingMeta) return;
      const now = Date.now();
      if (!watchTimeLastTick) {
        watchTimeLastTick = now;
        return;
      }

      const delta = Math.min(15, Math.max(0, (now - watchTimeLastTick) / 1000));
      watchTimeLastTick = now;
      if (!delta || document.hidden) return;

      if (watchTimeTrackingMeta.isPlaying && watchTimeTrackingMeta.isPlaying()) {
        recordWatchSeconds(
          watchTimeTrackingMeta.type,
          delta,
          watchTimeTrackingMeta.item
        );
      }
    }

    function startWatchTimeTracking(type, id, item, isPlaying) {
      stopWatchTimeTracking(false);
      if (id === null || id === undefined || String(id).trim() === '') return;
      watchTimeTrackingMeta = {
        type: type === 'series' ? 'series' : (type === 'live' ? 'live' : 'movie'),
        id: String(id),
        item,
        isPlaying
      };
      watchTimeLastTick = Date.now();
      watchTimeTrackingTimer = setInterval(flushWatchTimeTracking, 5000);
    }

    function stopWatchTimeTracking(save = true) {
      if (save) flushWatchTimeTracking();
      if (watchTimeTrackingTimer) clearInterval(watchTimeTrackingTimer);
      watchTimeTrackingTimer = null;
      watchTimeTrackingMeta = null;
      watchTimeLastTick = 0;
    }

    function saveCurrentVodProgress() {
      const meta = getCurrentVodProgressMeta();
      if (!meta || !elements.videoPlayer) return;
      flushWatchTimeTracking();

      const position = Number(elements.videoPlayer.currentTime) || 0;
      const duration = Number(elements.videoPlayer.duration) || 0;
      const completed = duration > 0 && (
        position >= Math.max(0, duration - VOD_PROGRESS_COMPLETE_REMAINING_SECONDS) ||
        position >= duration * VOD_PROGRESS_COMPLETE_PERCENT
      );

      if (completed && meta.type === 'series' && meta.mediaMeta?.ep) {
        markSeriesEpisodeWatched(meta.mediaMeta.ep, meta.seasonNum, duration);
      }

      saveVodProgress(meta.type, meta.id, position, duration, meta.title, meta);
    }

    function startVodProgressTracking() {
      if (vodProgressSaveTimer) clearInterval(vodProgressSaveTimer);
      vodProgressSaveTimer = null;
      const meta = getCurrentVodProgressMeta();
      if (!meta || !elements.videoPlayer) {
        stopWatchTimeTracking(false);
        return;
      }

      startWatchTimeTracking(
        meta.type,
        meta.id,
        getWatchTimeItem(meta),
        () => Boolean(elements.videoPlayer && !elements.videoPlayer.paused && !elements.videoPlayer.ended)
      );

      vodProgressSaveTimer = setInterval(() => {
        saveCurrentVodProgress();
      }, 5000);
    }

    function stopVodProgressTracking(save = true) {
      if (save) saveCurrentVodProgress();
      stopWatchTimeTracking(save);
      if (vodProgressSaveTimer) clearInterval(vodProgressSaveTimer);
      vodProgressSaveTimer = null;
    }

    // ==========================================
    // PULAR ABERTURA — timestamps comunitários reais
    // Não usa tempo fixo nem estimativa de duração.
    // SkipDB é a fonte geral (IMDb); AniSkip é fallback para
    // anime quando o catálogo fornecer um MAL ID exato.
    // ==========================================
    const SKIP_INTRO_CACHE_KEY = 'andplay_web_skip_intro_cache_v1';
    const SKIP_INTRO_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
    const SKIP_INTRO_CACHE_MAX_ENTRIES = 200;
    const SKIP_INTRO_AUTO_STORAGE_KEY = 'andplay_web_skip_intro_auto';
    const SKIPDB_SEGMENTS_URL = 'https://api.skipdb.tv/api/segments';
    const SKIPDB_TITLE_SEARCH_URL = 'https://api.skipdb.tv/api/titles/search';
    const ANISKIP_TIMES_URL = 'https://api.aniskip.com/v2/skip-times';
    let skipIntroLookupTimer = null;
    let skipIntroLookupKey = '';
    let skipIntroEventCleanup = null;

    function normalizeImdbId(value) {
      const match = String(value || '').trim().match(/\btt\d+\b/i);
      return match ? match[0] : '';
    }

    function isValidImdbId(value) {
      return /^tt\d+$/.test(normalizeImdbId(value));
    }

    function normalizePositiveId(value) {
      const n = Number(value);
      return Number.isInteger(n) && n > 0 ? String(n) : '';
    }

    function getExactSeriesExternalIds(mediaMeta) {
      const meta = mediaMeta || {};
      const imdbCandidates = [
        meta.imdbId,
        meta.imdb_id,
        meta.seriesImdbId,
        meta.groupOrMovie?.imdbId,
        meta.groupOrMovie?.imdb_id,
        meta.selectedVersion?.item?.imdb_id,
        meta.ep?.info?.imdb_id,
        meta.ep?.info?.imdbId
      ];

      let imdbId = '';
      for (const candidate of imdbCandidates) {
        const normalized = normalizeImdbId(candidate);
        if (normalized) {
          imdbId = normalized;
          break;
        }
      }

      if (!imdbId) {
        try {
          const saved = getSavedMediaMatch('series', { seriesName: meta.seriesName || meta.title || '' });
          const savedId = normalizeImdbId(saved?.id || saved?.imdb_id || saved?.imdbId);
          if (savedId) imdbId = savedId;
        } catch (e) {}
      }

      if (!imdbId) {
        const currentGroupId = normalizeImdbId(
          currentSeriesGroup?.imdbId ||
          currentSeriesGroup?.imdb_id ||
          currentActiveSeriesVersion?.item?.imdb_id ||
          currentActiveSeriesVersion?.item?.imdbId
        );
        if (currentGroupId) imdbId = currentGroupId;
      }

      const malCandidates = [
        meta.malId,
        meta.mal_id,
        meta.malID,
        meta.seriesMalId,
        meta.ep?.info?.malId,
        meta.ep?.info?.mal_id
      ];
      const malId = malCandidates.map(normalizePositiveId).find(Boolean) || '';

      return { imdbId, malId };
    }

    function normalizeSkipSearchTitle(value) {
      return String(value || '')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, ' ')
        .trim();
    }

    async function resolveSkipExternalIds(mediaMeta) {
      const direct = getExactSeriesExternalIds(mediaMeta);
      if (direct.imdbId || direct.malId) return direct;

      const fastCleanKey = cleanTitleKey(mediaMeta?.seriesName || mediaMeta?.title || '');
      if (fastCleanKey) {
        const cachedFastId = readSeriesImdbCache()[fastCleanKey];
        if (cachedFastId) return { ...direct, imdbId: cachedFastId };
      }

      const rawTitle = mediaMeta?.seriesName || mediaMeta?.title || '';
      const query = String(rawTitle || '').trim();
      if (!query) return direct;

      // O Xtream frequentemente não entrega IMDb/MAL em get_series_info.
      // Nesse caso, resolve o título pelo Cinemeta, que já é usado na ficha
      // técnica do próprio player e consegue encontrar títulos traduzidos.
      try {
        const searchStr = parseTitleInfo(query) || cleanTitleKey(query);
        if (searchStr) {
          const searchUrl = `https://v3-cinemeta.strem.io/catalog/series/top/search=${encodeURIComponent(searchStr)}.json`;
          const data = await fetchJsonWithTimeout(searchUrl, 6000);
          const metas = Array.isArray(data?.metas) ? data.metas : [];

          if (metas.length) {
            const expected = normalizeSkipSearchTitle(searchStr);
            const exact = metas.find(item => normalizeSkipSearchTitle(item?.name || '') === expected);
            const year = String(mediaMeta?.year || '').trim();
            const withYear = year
              ? metas.find(item =>
                  String(item?.releaseInfo || '').includes(year) ||
                  String(item?.year || '').includes(year)
                )
              : null;
            const chosen = exact || withYear || metas[0];
            const imdbId = normalizeImdbId(chosen?.id);
            if (imdbId) {
              if (fastCleanKey) writeSeriesImdbCache(fastCleanKey, imdbId);
              return { ...direct, imdbId };
            }
          }
        }
      } catch (e) {}

      // Último fallback: pesquisa direta no SkipDB.
      try {
        const params = new URLSearchParams({ q: query });
        const data = await fetchJsonWithTimeout(SKIPDB_TITLE_SEARCH_URL + '?' + params.toString(), 6000);
        const candidates = [
          ...(Array.isArray(data?.results) ? data.results : []),
          ...(Array.isArray(data?.local) ? data.local : [])
        ]
          .map(item => ({
            ...item,
            imdbId: normalizeImdbId(item?.imdb_id || item?.imdbId || item?.id),
            title: item?.name || item?.title || '',
            mediaType: String(item?.media_type || item?.mediaType || '').toLowerCase()
          }))
          .filter(item => item.imdbId && (!item.mediaType || item.mediaType === 'series'));

        if (!candidates.length) return direct;

        const expected = normalizeSkipSearchTitle(query);
        const exact = candidates.find(item => normalizeSkipSearchTitle(item.title) === expected);
        const chosen = exact || (candidates.length === 1 ? candidates[0] : null);
        if (chosen?.imdbId) {
          if (fastCleanKey) writeSeriesImdbCache(fastCleanKey, chosen.imdbId);
          return { ...direct, imdbId: chosen.imdbId };
        }
        return direct;
      } catch (e) {
        return direct;
      }
    }

    function getSkipIntroCache() {
      try {
        const raw = localStorage.getItem(SKIP_INTRO_CACHE_KEY);
        const cache = raw ? JSON.parse(raw) : {};
        return cache && typeof cache === 'object' ? cache : {};
      } catch (e) {
        return {};
      }
    }

    function getCachedSkipIntro(cacheKey) {
      const cache = getSkipIntroCache();
      const entry = cache[cacheKey];
      if (!entry || !Number.isFinite(Number(entry.savedAt))) return null;
      if (Date.now() - Number(entry.savedAt) > SKIP_INTRO_CACHE_TTL_MS) return null;
      return entry.segment || null;
    }

    function saveCachedSkipIntro(cacheKey, segment) {
      try {
        const cache = getSkipIntroCache();
        cache[cacheKey] = { savedAt: Date.now(), segment };

        const keys = Object.keys(cache);
        if (keys.length > SKIP_INTRO_CACHE_MAX_ENTRIES) {
          keys.sort((a, b) => Number(cache[a]?.savedAt || 0) - Number(cache[b]?.savedAt || 0));
          keys.slice(0, keys.length - SKIP_INTRO_CACHE_MAX_ENTRIES).forEach(k => delete cache[k]);
        }

        localStorage.setItem(SKIP_INTRO_CACHE_KEY, JSON.stringify(cache));
      } catch (e) {}
    }

    async function fetchJsonWithTimeout(url, timeoutMs = 8000) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(url, {
          method: 'GET',
          signal: controller.signal,
          cache: 'no-store'
        });
        if (!response.ok) return null;
        return await response.json();
      } catch (e) {
        return null;
      } finally {
        clearTimeout(timer);
      }
    }

    function validateSkipSegment(startSeconds, endSeconds, durationSeconds = 0) {
      const start = Number(startSeconds);
      const end = Number(endSeconds);
      const duration = Number(durationSeconds);

      if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
      if (start < 0 || end <= start) return null;

      // Quando a duração ainda não está disponível, aceita apenas um marcador
      // plausível de abertura. Assim o lookup pode acontecer antes do playback,
      // mas não aceita timestamps absurdamente distantes ou longos.
      if (!Number.isFinite(duration) || duration <= 0) {
        if (start > 15 * 60 || end - start > 5 * 60) return null;
        return { start, end };
      }

      if (start >= duration) return null;
      const safeEnd = Math.min(end, duration);
      if (safeEnd <= start) return null;
      return { start, end: safeEnd };
    }

    async function fetchSkipDbIntro(imdbId, seasonNum, episodeNum, durationSeconds) {
      const duration = Number(durationSeconds);
      const cacheKey = ['skipdb', imdbId, seasonNum, episodeNum, Math.round(duration)].join(':');
      const cached = getCachedSkipIntro(cacheKey);
      if (cached) return cached;

      const params = new URLSearchParams({
        imdb_id: imdbId,
        season: String(Number(seasonNum)),
        episode: String(Number(episodeNum)),
        type: 'intro',
        adjust: 'conservative'
      });
      if (duration > 0) {
        params.set('duration', String(duration));
      }

      const data = await fetchJsonWithTimeout(SKIPDB_SEGMENTS_URL + '?' + params.toString());
      let intro = data?.segments?.intro;
      if (!intro && duration > 0) {
        const agnosticParams = new URLSearchParams({
          imdb_id: imdbId,
          season: String(Number(seasonNum)),
          episode: String(Number(episodeNum)),
          type: 'intro',
          adjust: 'conservative'
        });
        const agnosticData = await fetchJsonWithTimeout(SKIPDB_SEGMENTS_URL + '?' + agnosticParams.toString());
        intro = agnosticData?.segments?.intro;
      }

      if (!intro) {
        if (duration > 0) {
          const zeroCached = getCachedSkipIntro(['skipdb', imdbId, seasonNum, episodeNum, 0].join(':'));
          if (zeroCached) {
            const revalidated = validateSkipSegment(zeroCached.start, zeroCached.end, duration);
            if (revalidated) {
              const resegment = { ...zeroCached, ...revalidated };
              saveCachedSkipIntro(cacheKey, resegment);
              return resegment;
            }
          }
        }
        return null;
      }

      const matchType = String(intro.match || '');
      if (!['exact', 'shifted', 'agnostic', 'out-of-range'].includes(matchType)) return null;
      const confidence = Number(intro.confidence);

      const valid = validateSkipSegment(Number(intro.start_ms) / 1000, Number(intro.end_ms) / 1000, duration);
      if (!valid) return null;

      const segment = {
        ...valid,
        source: 'SkipDB',
        confidence,
        match: matchType
      };
      saveCachedSkipIntro(cacheKey, segment);
      return segment;
    }

    async function fetchAniSkipIntro(malId, episodeNum, durationSeconds) {
      const duration = Number(durationSeconds);
      const cacheKey = ['aniskip', malId, episodeNum, Math.round(duration)].join(':');
      const cached = getCachedSkipIntro(cacheKey);
      if (cached) return cached;

      const baseUrl = ANISKIP_TIMES_URL + '/' + encodeURIComponent(malId) + '/' + encodeURIComponent(Number(episodeNum));

      // Primeiro tenta a duração real do arquivo. Se o AniSkip não encontrar
      // uma correspondência por causa de uma edição/versão diferente, tenta
      // sem filtro de duração e só aceita um resultado próximo o bastante.
      const exactParams = new URLSearchParams();
      exactParams.append('types', 'op');
      exactParams.set('episodeLength', String(duration));
      let data = await fetchJsonWithTimeout(baseUrl + '?' + exactParams.toString());

      if (!data?.found || !Array.isArray(data.results)) {
        const fallbackParams = new URLSearchParams();
        fallbackParams.append('types', 'op');
        fallbackParams.set('episodeLength', '0');
        data = await fetchJsonWithTimeout(baseUrl + '?' + fallbackParams.toString());
      }

      if (!data?.found || !Array.isArray(data.results)) return null;

      const op = data.results.find(item => item?.skipType === 'op' && item?.interval);
      if (!op) {
        if (duration > 0) {
          const zeroCached = getCachedSkipIntro(['aniskip', malId, episodeNum, 0].join(':'));
          if (zeroCached) {
            const revalidated = validateSkipSegment(zeroCached.start, zeroCached.end, duration);
            if (revalidated) {
              const resegment = { ...zeroCached, ...revalidated };
              saveCachedSkipIntro(cacheKey, resegment);
              return resegment;
            }
          }
        }
        return null;
      }

      const sourceDuration = Number(op.episodeLength || 0);
      const durationDelta = sourceDuration > 0 ? Math.abs(duration - sourceDuration) : 0;

      // Sem duração compatível, não fazemos um deslocamento grande no escuro.
      // Isso evita que um marcador de uma edição diferente provoque um salto errado.
      if (sourceDuration > 0 && durationDelta > 20) return null;

      const valid = validateSkipSegment(op.interval.startTime, op.interval.endTime, duration);
      if (!valid) return null;

      const segment = {
        ...valid,
        source: 'AniSkip',
        confidence: null,
        match: sourceDuration > 0 && durationDelta > 0 ? 'community-near' : 'community'
      };
      saveCachedSkipIntro(cacheKey, segment);
      return segment;
    }

    function isAutoSkipIntroEnabled() {
      try {
        return localStorage.getItem(SKIP_INTRO_AUTO_STORAGE_KEY) === '1';
      } catch (e) {
        return false;
      }
    }

    function setAutoSkipIntroEnabled(enabled) {
      try {
        localStorage.setItem(SKIP_INTRO_AUTO_STORAGE_KEY, enabled ? '1' : '0');
      } catch (e) {}
      saveLocalPreference('skip_intro_auto', !!enabled);
      if (elements.skipIntroAutoToggle) elements.skipIntroAutoToggle.checked = !!enabled;
      window.dispatchEvent(new CustomEvent('eplay:skip-auto-changed', {
        detail: { enabled: !!enabled }
      }));
      if (enabled) {
        scheduleSkipIntroLookup(0);
        maybeAutoSkipIntro();
      }
    }

    function updateSkipIntroAutoUi(mediaType = currentPlaybackMeta?.mediaType) {
      const visible = mediaType === 'series';
      if (elements.skipIntroAutoLabel) elements.skipIntroAutoLabel.style.display = visible ? 'inline-flex' : 'none';
      if (elements.skipIntroAutoToggle) elements.skipIntroAutoToggle.checked = isAutoSkipIntroEnabled();
    }

    function resetSkipIntroUi() {
      skipIntroRequestSeq++;
      skipIntroLookupKey = '';
      if (typeof skipIntroEventCleanup === 'function') {
        skipIntroEventCleanup();
        skipIntroEventCleanup = null;
      }
      if (skipIntroLookupTimer) {
        clearTimeout(skipIntroLookupTimer);
        skipIntroLookupTimer = null;
      }
      updateSkipIntroAutoUi();
      skipIntroState = { segment: null, source: '', used: false };
      if (elements.skipIntroBtn) {
        const wasVisible = elements.skipIntroBtn.dataset.eplayVisible === '1';
        elements.skipIntroBtn.style.display = 'none';
        elements.skipIntroBtn.dataset.eplayVisible = '0';
        elements.skipIntroBtn.disabled = false;
        elements.skipIntroBtn.textContent = '⏭️ Pular abertura';
        elements.skipIntroBtn.title = 'Pular a abertura usando timestamps comunitários verificados';
        if (wasVisible) {
          window.dispatchEvent(new CustomEvent('eplay:skip-state', { detail: { visible: false } }));
        }
      }
      if (elements.skipIntroSource) {
        elements.skipIntroSource.style.display = 'none';
        elements.skipIntroSource.textContent = '';
        elements.skipIntroSource.title = '';
      }
    }

    function updateSkipIntroButton() {
      const btn = elements.skipIntroBtn;
      const segment = skipIntroState.segment;
      const player = elements.videoPlayer;

      if (!btn || !segment || currentPlaybackMeta?.mediaType !== 'series' || !player) {
        if (btn) {
          const wasVisible = btn.dataset.eplayVisible === '1';
          btn.style.display = 'none';
          btn.dataset.eplayVisible = '0';
          if (wasVisible) {
            window.dispatchEvent(new CustomEvent('eplay:skip-state', { detail: { visible: false } }));
          }
        }
        return;
      }

      const currentTime = Number(player.currentTime);
      if (!Number.isFinite(currentTime)) {
        const wasVisible = btn.dataset.eplayVisible === '1';
        btn.style.display = 'none';
        btn.dataset.eplayVisible = '0';
        if (wasVisible) {
          window.dispatchEvent(new CustomEvent('eplay:skip-state', { detail: { visible: false } }));
        }
        return;
      }

      if (currentTime < segment.start || (segment.start <= 5 && currentTime < 5)) {
        skipIntroState.used = false;
      }

      // Se a abertura começa nos primeiros 5 segundos do episódio (ex: segundo 0 ou 1),
      // o botão já deve estar disponível desde o início da reprodução até o fim da abertura!
      const startThreshold = segment.start <= 5 ? 0 : segment.start;
      const insideSegment = currentTime >= startThreshold && currentTime < segment.end;
      const shouldShow = insideSegment && !skipIntroState.used;
      const wasVisible = btn.dataset.eplayVisible === '1';
      btn.style.display = shouldShow ? 'inline-flex' : 'none';
      btn.dataset.eplayVisible = shouldShow ? '1' : '0';
      if (wasVisible !== shouldShow) {
        window.dispatchEvent(new CustomEvent('eplay:skip-state', { detail: { visible: shouldShow } }));
      }
      if (shouldShow) {
        const sourceText = segment.source === 'SkipDB'
          ? `SkipDB • confiança ${Math.round((segment.confidence || 0) * 100)}%`
          : 'AniSkip • dados da comunidade';
        btn.title = `Pular de ${formatResumeTime(segment.start)} até ${formatResumeTime(segment.end)} • ${sourceText}`;
      }
    }

    async function loadSkipIntroForCurrentEpisode() {
      const playback = currentPlaybackMeta;
      if (!playback || playback.mediaType !== 'series' || !elements.videoPlayer) {
        resetSkipIntroUi();
        return;
      }

      const rawDuration = Number(elements.videoPlayer.duration);
      const duration = Number.isFinite(rawDuration) && rawDuration > 0 ? rawDuration : 0;

      const ids = await resolveSkipExternalIds(playback.mediaMeta);
      const seasonNum = normalizePositiveId(playback.seasonNum);
      const episodeNum = normalizePositiveId(playback.episodeNum);
      if (!seasonNum || !episodeNum || (!ids.imdbId && !ids.malId)) {
        resetSkipIntroUi();
        return;
      }

      const requestSeq = ++skipIntroRequestSeq;
      const existingSegment = skipIntroState.segment;
      updateSkipIntroAutoUi('series');

      let segment = null;
      try {
        if (ids.imdbId) {
          segment = await fetchSkipDbIntro(ids.imdbId, seasonNum, episodeNum, duration);
        }
        if (!segment && ids.malId) {
          segment = await fetchAniSkipIntro(ids.malId, episodeNum, duration);
        }
      } catch (e) {
        segment = null;
      }

      if (requestSeq !== skipIntroRequestSeq || currentPlaybackMeta !== playback) return;

      const chosenSegment = segment || existingSegment;

      if (chosenSegment) {
        skipIntroState = {
          segment: chosenSegment,
          source: chosenSegment.source,
          used: (existingSegment && existingSegment.start === chosenSegment.start && existingSegment.end === chosenSegment.end) ? skipIntroState.used : false
        };
        if (elements.skipIntroSource) {
          elements.skipIntroSource.style.display = 'inline';
          elements.skipIntroSource.textContent = chosenSegment.source === 'SkipDB'
            ? 'Dados comunitários via SkipDB • ODbL 1.0'
            : 'Dados comunitários via AniSkip';
          elements.skipIntroSource.title = 'Timestamp fornecido por uma base comunitária de skip times.';
        }
        maybeAutoSkipIntro();
        updateSkipIntroButton();

        // O marcador pode chegar alguns instantes depois do início da reprodução.
        // Rechecamos rapidamente para não perder a janela da abertura por uma condição de corrida.
        if (isAutoSkipIntroEnabled()) {
          [0, 250, 800].forEach(delay => {
            setTimeout(() => {
              if (currentPlaybackMeta === playback) {
                maybeAutoSkipIntro();
                updateSkipIntroButton();
              }
            }, delay);
          });
        }
      } else {
        skipIntroState = { segment: null, source: '', used: false };
        updateSkipIntroButton();
      }
    }

    function scheduleSkipIntroLookup(delay = 180) {
      if (skipIntroLookupTimer) {
        clearTimeout(skipIntroLookupTimer);
        skipIntroLookupTimer = null;
      }

      skipIntroLookupTimer = setTimeout(async () => {
        skipIntroLookupTimer = null;

        const playback = currentPlaybackMeta;
        const player = elements.videoPlayer;
        if (!playback || playback.mediaType !== 'series' || !player) return;

        const rawDuration = Number(player.duration);
        const duration = Number.isFinite(rawDuration) && rawDuration > 0 ? rawDuration : 0;

        const ids = await resolveSkipExternalIds(playback.mediaMeta);
        const seasonNum = normalizePositiveId(playback.seasonNum);
        const episodeNum = normalizePositiveId(playback.episodeNum);
        if (!seasonNum || !episodeNum || (!ids.imdbId && !ids.malId)) return;

        const key = [
          playback.streamId || '',
          ids.imdbId || '',
          ids.malId || '',
          seasonNum,
          episodeNum,
          Math.round(duration)
        ].join(':');

        if (skipIntroLookupKey === key && skipIntroState.segment) return;
        skipIntroLookupKey = key;
        loadSkipIntroForCurrentEpisode().catch(() => {});
      }, Math.max(0, Number(delay) || 0));
    }

    function maybeAutoSkipIntro() {
      const segment = skipIntroState.segment;
      const player = elements.videoPlayer;
      if (!segment || !isAutoSkipIntroEnabled() || skipIntroState.used || !player || player.paused || currentPlaybackMeta?.mediaType !== 'series') return;

      const currentTime = Number(player.currentTime);
      const startThreshold = segment.start <= 5 ? 0 : segment.start;
      if (!Number.isFinite(currentTime) || currentTime < startThreshold || currentTime >= segment.end) return;

      const target = Math.min(segment.end, Number(player.duration) || segment.end);
      if (!Number.isFinite(target) || target <= currentTime) return;

      skipIntroState.used = true;
      try {
        player.currentTime = target;
        updateSkipIntroButton();
        saveCurrentVodProgress();
        window.EPlayPlayerUI?.showToast?.('⏭ Abertura pulada automaticamente');
      } catch (e) {}
    }

    function handleSkipIntroClick() {
      const segment = skipIntroState.segment;
      const player = elements.videoPlayer;
      if (!segment || !player || currentPlaybackMeta?.mediaType !== 'series') return;

      const target = Math.min(segment.end, Number(player.duration) || segment.end);
      if (!Number.isFinite(target) || target <= segment.start) return;

      try {
        player.currentTime = target;
        skipIntroState.used = true;
        updateSkipIntroButton();
        saveCurrentVodProgress();
        window.EPlayPlayerUI?.showToast?.('⏭ Abertura pulada');
      } catch (e) {}
    }

    function formatResumeTime(seconds) {
      const total = Math.max(0, Math.floor(Number(seconds) || 0));
      const h = Math.floor(total / 3600);
      const m = Math.floor((total % 3600) / 60);
      const s = total % 60;
      if (h > 0) return [h, String(m).padStart(2, '0'), String(s).padStart(2, '0')].join(':');
      return [String(m).padStart(2, '0'), String(s).padStart(2, '0')].join(':');
    }

    function showResumePrompt(title, savedPos, onContinue, onRestart, onCancel = () => {}) {
      const oldOverlay = document.querySelector('.andplay-resume-overlay');
      if (oldOverlay) oldOverlay.remove();

      const overlay = document.createElement('div');
      overlay.className = 'andplay-resume-overlay';
      overlay.setAttribute('role', 'dialog');
      overlay.setAttribute('aria-modal', 'true');
      overlay.innerHTML = `
        <div class="andplay-resume-dialog">
          <div class="andplay-resume-title">${escapeHtml(title || 'Conteúdo')}</div>
          <div class="andplay-resume-info">Você parou em <strong style="color:#ffc107;">${formatResumeTime(savedPos)}</strong>. Deseja continuar de onde parou?</div>
          <div class="andplay-resume-actions">
            <button type="button" class="primary" data-resume-continue>▶ Continuar de onde parou</button>
            <button type="button" data-resume-restart>🔄 Voltar ao início</button>
            <button type="button" class="cancel" data-resume-cancel>Cancelar</button>
          </div>
        </div>
      `;

      const close = (callback) => {
        document.removeEventListener('keydown', onKeyDown);
        overlay.remove();
        callback();
      };
      const onKeyDown = (event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          close(onCancel);
        }
      };

      overlay.querySelector('[data-resume-continue]').addEventListener('click', () => close(() => onContinue(savedPos)));
      overlay.querySelector('[data-resume-restart]').addEventListener('click', () => close(() => onRestart()));
      overlay.querySelector('[data-resume-cancel]').addEventListener('click', () => close(onCancel));
      document.addEventListener('keydown', onKeyDown);
      document.body.appendChild(overlay);
      setTimeout(() => overlay.querySelector('[data-resume-continue]')?.focus(), 0);
    }

    function startVodWithResume(type, id, title, onStart) {
      const saved = getVodProgress(type, id);
      if (saved && saved.position > VOD_PROGRESS_MIN_SECONDS) {
        showResumePrompt(
          title,
          saved.position,
          (position) => onStart(position),
          () => {
            clearVodProgress(type, id);
            onStart(0);
          }
        );
        return;
      }
      onStart(0);
    }

    function movieGroupMatchesWatchedId(group, id) {
      const wanted = String(id);
      if (!group) return false;
      if (group.stream_id != null && String(group.stream_id) === wanted) return true;
      if (group.primaryItem?.stream_id != null && String(group.primaryItem.stream_id) === wanted) return true;
      return Array.isArray(group.versions)
        && group.versions.some(v => v?.streamId != null && String(v.streamId) === wanted);
    }

    function getSeriesWatchedId(group) {
      return String(group?.series_id || group?.primaryItem?.series_id || '').trim();
    }

    function seriesGroupMatchesWatchedId(group, id) {
      const wanted = String(id);
      if (!group) return false;
      if (group.series_id != null && String(group.series_id) === wanted) return true;
      if (group.id != null && String(group.id) === wanted) return true;
      if (group.primaryItem?.series_id != null && String(group.primaryItem.series_id) === wanted) return true;
      if (group.primaryItem?.id != null && String(group.primaryItem.id) === wanted) return true;
      return Array.isArray(group.versions)
        && group.versions.some(v =>
          (v?.seriesId != null && String(v.seriesId) === wanted) ||
          (v?.item?.series_id != null && String(v.item.series_id) === wanted) ||
          (v?.item?.id != null && String(v.item.id) === wanted)
        );
    }

    function readFavorites() {
      try {
        const raw = localStorage.getItem(FAVORITES_STORAGE_KEY);
        const parsed = raw ? JSON.parse(raw) : [];
        if (!Array.isArray(parsed)) return [];
        return parsed.map(item => ({
          type: item?.type === 'series' ? 'series' : 'movie',
          id: String(item?.id || '').trim(),
          title: String(item?.title || '').trim(),
          poster: String(item?.poster || '').trim(),
          updatedAt: Number(item?.updatedAt || 0) || 0
        })).filter(item => item.id);
      } catch (e) {
        return [];
      }
    }

    function writeFavorites(items) {
      try {
        localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify(
          (Array.isArray(items) ? items : []).slice(0, 500)
        ));
      } catch (e) {}
    }

    function getFavoriteId(type, item) {
      if (!item) return '';
      if (type === 'series') {
        return String(item.series_id || item.primaryItem?.series_id || item.versions?.[0]?.seriesId || '').trim();
      }
      return String(
        item.stream_id ||
        item.primaryItem?.stream_id ||
        item.versions?.[0]?.streamId ||
        ''
      ).trim();
    }

    function isFavorite(type, item) {
      const id = getFavoriteId(type, item);
      if (!id) return false;
      return readFavorites().some(entry => entry.type === type && entry.id === id);
    }

    function updateFavoriteButton(type = currentContentPageType, item = currentContentPageItem) {
      const btn = elements.contentFavoriteBtn;
      if (!btn) return;
      const favorite = isFavorite(type, item);
      btn.classList.toggle('is-favorite', favorite);
      btn.setAttribute('aria-pressed', favorite ? 'true' : 'false');
      btn.textContent = favorite ? '★ Remover dos favoritos' : '☆ Adicionar aos favoritos';
      btn.title = favorite ? 'Remover este título dos favoritos' : 'Adicionar este título aos favoritos';
    }

    function toggleCurrentFavorite() {
      const type = currentContentPageType === 'series' ? 'series' : 'movie';
      const item = currentContentPageItem;
      const id = getFavoriteId(type, item);
      if (!id) return;

      const current = readFavorites();
      const index = current.findIndex(entry => entry.type === type && entry.id === id);
      if (index >= 0) {
        current.splice(index, 1);
      } else {
        const poster = type === 'series'
          ? String(item?.cover || item?.stream_icon || '').trim()
          : String(item?.poster || getBestPosterUrl(item?.primaryItem || item) || item?.stream_icon || '').trim();
        current.unshift({
          type,
          id,
          title: String(item?.name || item?.title || (type === 'series' ? 'Série' : 'Filme')).trim(),
          poster,
          updatedAt: Date.now()
        });
      }
      writeFavorites(current);
      updateFavoriteButton(type, item);

      const favoriteEntry = current.find(entry => entry.type === type && entry.id === id);
      const active = Boolean(favoriteEntry);
      const poster = favoriteEntry?.poster || (type === 'series'
        ? String(item?.cover || item?.stream_icon || '').trim()
        : String(item?.poster || getBestPosterUrl(item?.primaryItem || item) || item?.stream_icon || '').trim());
      window.AndPlayAccount?.setFavorite?.(type, id, active, {
        title: favoriteEntry?.title || String(item?.name || item?.title || '').trim(),
        poster
      }).then?.(() => {
        if (isFavoritesView) showFavoritesContent();
      });
    }

    function buildFavoriteFallbackGroup(entry) {
      const title = entry?.title || (entry?.type === 'series' ? 'Série ' + entry.id : 'Filme ' + entry.id);
      const poster = entry?.poster || '';
      if (entry?.type === 'series') {
        return {
          isGroup: true,
          series_id: entry.id,
          name: title,
          title,
          cover: poster,
          stream_icon: poster,
          primaryItem: { series_id: entry.id, name: title, cover: poster, stream_icon: poster },
          versions: [{ item: { series_id: entry.id, name: title, cover: poster }, seriesId: entry.id }]
        };
      }
      return {
        isGroup: true,
        stream_id: entry.id,
        name: title,
        title,
        poster,
        stream_icon: poster,
        primaryItem: { stream_id: entry.id, name: title, stream_icon: poster }
      };
    }

    // ==========================================
    // FAVORITOS HUB & CONTROLES
    // ==========================================
    let currentFavFilter = 'all'; // 'all' | 'movies' | 'series'
    let fullFavoritesList = [];

    function updateFavoritesCounters() {
      const favs = readFavorites();
      const movieCount = favs.filter(f => f.type === 'movie').length;
      const seriesCount = favs.filter(f => f.type === 'series').length;
      if (elements.favCountAll) elements.favCountAll.textContent = favs.length;
      if (elements.favCountMovies) elements.favCountMovies.textContent = movieCount;
      if (elements.favCountSeries) elements.favCountSeries.textContent = seriesCount;
      if (elements.favTotalBadge) elements.favTotalBadge.textContent = `${favs.length} ${favs.length === 1 ? 'item' : 'itens'}`;
      if (elements.statFavCount) elements.statFavCount.textContent = favs.length;
    }

    function applyFavFilter(filter) {
      currentFavFilter = filter;
      document.querySelectorAll('.fav-filter-pill').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.favFilter === filter);
      });
      if (filter === 'movies') {
        currentMediaList = fullFavoritesList.filter(item => item._searchType === 'movie');
      } else if (filter === 'series') {
        currentMediaList = fullFavoritesList.filter(item => item._searchType === 'series');
      } else {
        currentMediaList = fullFavoritesList;
      }
      applyFilterAndRender(elements.searchInput?.value || '');
    }

    async function clearAllFavorites() {
      if (!confirm('Deseja realmente remover todos os títulos da sua lista de favoritos?')) return;
      writeFavorites([]);
      fullFavoritesList = [];
      currentMediaList = [];
      updateFavoritesCounters();
      applyFilterAndRender('');
      if (window.AndPlayAccount?.refreshFavorites) {
        window.AndPlayAccount.refreshFavorites().catch(() => {});
      }
    }

    function removeSingleFavoriteItem(type, id) {
      const normalizedType = type === 'series' ? 'series' : 'movie';
      const normalizedId = String(id || '').trim();
      if (!normalizedId) return;

      const current = readFavorites();
      const next = current.filter(entry => !(entry.type === normalizedType && String(entry.id) === normalizedId));
      writeFavorites(next);
      window.AndPlayAccount?.setFavorite?.(normalizedType, normalizedId, false)?.catch?.(() => {});
    }

    let favoritesHubInitialized = false;
    function initFavoritesHub() {
      if (favoritesHubInitialized) return;
      favoritesHubInitialized = true;

      elements.favPillAll?.addEventListener('click', () => applyFavFilter('all'));
      elements.favPillMovies?.addEventListener('click', () => applyFavFilter('movies'));
      elements.favPillSeries?.addEventListener('click', () => applyFavFilter('series'));
      elements.favClearBtn?.addEventListener('click', clearAllFavorites);
    }

    async function showFavoritesContent() {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      favoriteReturnMode = ['home', 'movies', 'series', 'live'].includes(currentMode) ? currentMode : 'home';
      isFavoritesView = true;
      isWatchedView = false;
      currentMode = 'favorites';
      setRouteHash('#/favoritos');
      document.title = 'Favoritos - EPlay';

      hideLoading();
      if (elements.moviesHub) elements.moviesHub.style.display = 'none';
      if (elements.seriesHub) elements.seriesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (elements.watchedHub) elements.watchedHub.style.display = 'none';
      if (elements.userDashboard) elements.userDashboard.style.display = 'none';

      if (moviesHeroTimer) { clearInterval(moviesHeroTimer); moviesHeroTimer = null; }
      if (seriesHeroTimer) { clearInterval(seriesHeroTimer); seriesHeroTimer = null; }
      window.EPlayTvEpg?.deactivate();

      if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
      if (elements.loadMoreContainer) elements.loadMoreContainer.style.removeProperty('display');
      setHomeDashboardVisible(false);
      elements.contentPage?.classList.remove('is-active');
      if (elements.contentPage) elements.contentPage.hidden = true;
      document.querySelector('.status-bar')?.style.removeProperty('display');
      document.querySelector('main')?.style.removeProperty('display');

      document.querySelectorAll('.nav-tabs .nav-tab').forEach(b => b.classList.remove('active'));
      elements.tabFavoritesBtn?.classList.add('active');
      elements.tabUserBtn?.classList.remove('active');
      elements.accountBtn?.classList.remove('active');
      document.querySelectorAll('.mobile-bottom-nav button').forEach(b => b.classList.remove('active'));
      elements.mobileFavoritesBtn?.classList.add('active');

      startViewTransition();
      window.scrollTo({ top: 0, behavior: 'instant' });

      if (elements.searchInput) {
        elements.searchInput.value = '';
        elements.searchInput.placeholder = 'Pesquisar nos seus favoritos...';
      }
      elements.resetCategoryBtn.style.display = 'none';
      elements.categorySelect.value = 'ALL';
      elements.categorySelect.disabled = true;
      elements.categorySelect.style.display = 'none';

      initFavoritesHub();
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'block';

      const favorites = readFavorites().sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0));
      updateFavoritesCounters();

      const hasMovies = favorites.some(entry => entry.type === 'movie');
      const hasSeries = favorites.some(entry => entry.type === 'series');

      const [movieCatalog, seriesCatalog] = await Promise.all([
        hasMovies ? loadFullMovies() : Promise.resolve(fullMoviesCache || []),
        hasSeries ? loadFullSeries() : Promise.resolve(fullSeriesCache || [])
      ]);

      if (!isFavoritesView) return;

      const ordered = favorites.map(entry => {
        const match = entry.type === 'movie'
          ? (movieCatalog || []).find(item => movieGroupMatchesWatchedId(item, entry.id))
          : (seriesCatalog || []).find(item => seriesGroupMatchesWatchedId(item, entry.id));
        const resolved = match || buildFavoriteFallbackGroup(entry);
        resolved._searchType = entry.type;
        return resolved;
      });

      fullFavoritesList = ordered;
      currentFavFilter = 'all';
      document.querySelectorAll('.fav-filter-pill').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.favFilter === 'all');
      });

      currentMediaList = ordered;
      elements.categoryLabel.textContent = '★ Favoritos';
      applyFilterAndRender('');

      if (!ordered.length) {
        elements.mediaGrid.innerHTML =
          '<div class="eplay-favorites-empty">' +
            '<div class="eplay-favorites-empty-icon">☆</div>' +
            '<div style="font-size: 16px; font-weight: 700; color: #fff; margin-top: 6px;">Sua lista de favoritos está vazia.</div>' +
            '<small style="margin-top: 4px;">Abra qualquer filme ou série e clique em “Adicionar aos favoritos” para salvar aqui.</small>' +
          '</div>';
        elements.mediaCount.textContent = '0 favoritos';
        elements.loadMoreContainer.style.display = 'none';
      }
    }

    function restoreFavoritesView() {
      if (!isFavoritesView) return;
      const returnMode = favoriteReturnMode;
      isFavoritesView = false;
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'none';
      if (moviesHeroTimer) { clearInterval(moviesHeroTimer); moviesHeroTimer = null; }
      if (seriesHeroTimer) { clearInterval(seriesHeroTimer); seriesHeroTimer = null; }
      if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
      if (elements.loadMoreContainer) elements.loadMoreContainer.style.removeProperty('display');
      elements.tabFavoritesBtn?.classList.remove('active');
      elements.mobileFavoritesBtn?.classList.remove('active');
      elements.searchInput.value = '';
      elements.categorySelect.disabled = false;

      if (returnMode === 'home') { showHome(); return; }
      if (returnMode === 'live') { switchMode('live', true); return; }
      currentMode = returnMode === 'series' ? 'series' : 'movies';
      elements.tabHomeBtn?.classList.remove('active');
      elements.tabMoviesBtn.classList.toggle('active', currentMode === 'movies');
      elements.tabSeriesBtn.classList.toggle('active', currentMode === 'series');
      elements.tabLiveBtn?.classList.remove('active');
      document.querySelectorAll('.mobile-bottom-nav button').forEach(button => button.classList.remove('active'));
      if (currentMode === 'movies') elements.mobileMoviesBtn?.classList.add('active');
      else elements.mobileSeriesBtn?.classList.add('active');
      if (currentMode === 'movies') {
        elements.categorySelect.style.removeProperty('display');
        elements.categoryLabel.textContent = 'Catálogo Geral: Todos os Filmes';
        elements.searchInput.placeholder = 'Pesquisar filmes por título, gênero, ator ou diretor...';
        currentMediaList = fullMoviesCache || [];
      } else {
        elements.categorySelect.style.removeProperty('display');
        elements.categoryLabel.textContent = 'Catálogo Geral: Todas as Séries';
        elements.searchInput.placeholder = 'Pesquisar séries por título, gênero ou elenco...';
        currentMediaList = fullSeriesCache || [];
      }
      applyFilterAndRender('');
    }

    // ==========================================
    // ASSISTIDOS HUB & CONTROLES
    // ==========================================
    let currentWatchedFilter = 'all'; // 'all' | 'resume' | 'done' | 'movies' | 'series'
    let fullWatchedList = [];

    function getItemProgressPct(item) {
      if (!item) return 0;
      const isMovie = item._searchType === 'movie';
      const id = isMovie
        ? (item.stream_id ?? item.primaryItem?.stream_id ?? item.id)
        : (item.series_id ?? item.primaryItem?.series_id ?? item.id);
      const prog = getVodProgress(isMovie ? 'movie' : 'series', id);
      if (!prog || !prog.duration || prog.duration <= 0) return 0;
      return Math.max(0, Math.min(100, Math.round((prog.position / prog.duration) * 100)));
    }

    function updateWatchedCounters() {
      const all = fullWatchedList;
      const resumeCount = all.filter(item => {
        const p = getItemProgressPct(item);
        return p > 0 && p < 95;
      }).length;
      const doneCount = all.filter(item => {
        const p = getItemProgressPct(item);
        return p >= 95;
      }).length;
      const movieCount = all.filter(item => item._searchType === 'movie').length;
      const seriesCount = all.filter(item => item._searchType === 'series').length;

      if (elements.watchedCountAll) elements.watchedCountAll.textContent = all.length;
      if (elements.watchedCountResume) elements.watchedCountResume.textContent = resumeCount;
      if (elements.watchedCountDone) elements.watchedCountDone.textContent = doneCount;
      if (elements.watchedCountMovies) elements.watchedCountMovies.textContent = movieCount;
      if (elements.watchedCountSeries) elements.watchedCountSeries.textContent = seriesCount;
      if (elements.watchedTotalBadge) elements.watchedTotalBadge.textContent = `${all.length} ${all.length === 1 ? 'item' : 'itens'}`;
      if (elements.statMoviesCount) elements.statMoviesCount.textContent = movieCount;
      if (elements.statSeriesCount) elements.statSeriesCount.textContent = seriesCount;
    }

    function applyWatchedFilter(filter) {
      currentWatchedFilter = filter;
      document.querySelectorAll('.watched-filter-pill').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.watchedFilter === filter);
      });
      if (filter === 'resume') {
        currentMediaList = fullWatchedList.filter(item => {
          const p = getItemProgressPct(item);
          return p > 0 && p < 95;
        });
      } else if (filter === 'done') {
        currentMediaList = fullWatchedList.filter(item => {
          const p = getItemProgressPct(item);
          return p >= 95;
        });
      } else if (filter === 'movies') {
        currentMediaList = fullWatchedList.filter(item => item._searchType === 'movie');
      } else if (filter === 'series') {
        currentMediaList = fullWatchedList.filter(item => item._searchType === 'series');
      } else {
        currentMediaList = fullWatchedList;
      }
      applyFilterAndRender(elements.searchInput?.value || '');
    }

    async function clearAllWatchedHistory() {
      if (!confirm('Deseja realmente limpar todo o histórico de filmes e séries assistidos?')) return;
      try {
        const toRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (k && k.startsWith(VOD_PROGRESS_PREFIX)) toRemove.push(k);
        }
        toRemove.forEach(k => localStorage.removeItem(k));
        localStorage.removeItem('andplay_web_watch_history_pending_v1');
        localStorage.removeItem('andplay_watched_movies_v1');
        localStorage.removeItem('andplay_watched_series_v1');
      } catch (e) {}

      fullWatchedList = [];
      currentMediaList = [];
      updateWatchedCounters();
      applyFilterAndRender('');
    }

    function removeSingleWatchedItem(type, id) {
      const normalizedType = type === 'series' ? 'series' : 'movie';
      const normalizedId = String(id || '').trim();
      if (!normalizedId) return;

      try {
        const vodKey = getVodProgressKey(normalizedType, normalizedId);
        if (vodKey) localStorage.removeItem(vodKey);

        if (normalizedType === 'series') {
          const toRemove = [];
          for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k && k.includes(`series_${normalizedId}`)) toRemove.push(k);
          }
          toRemove.forEach(k => localStorage.removeItem(k));
        }

        const rawPending = localStorage.getItem('andplay_web_watch_history_pending_v1');
        if (rawPending) {
          const parsed = JSON.parse(rawPending);
          if (Array.isArray(parsed)) {
            const nextPending = parsed.filter(item => !(item.type === normalizedType && String(item.id) === normalizedId));
            localStorage.setItem('andplay_web_watch_history_pending_v1', JSON.stringify(nextPending));
          }
        }

        const legacyKey = normalizedType === 'series' ? 'andplay_watched_series_v1' : 'andplay_watched_movies_v1';
        const rawLegacy = localStorage.getItem(legacyKey);
        if (rawLegacy) {
          const parsed = JSON.parse(rawLegacy);
          if (Array.isArray(parsed)) {
            const nextLegacy = parsed.filter(item => String(item.id || item) !== normalizedId);
            localStorage.setItem(legacyKey, JSON.stringify(nextLegacy));
          }
        }
      } catch (e) {
        console.warn('[EPlay] Erro ao remover assistido local:', e);
      }

      window.AndPlayAccount?.removeWatched?.(normalizedType, normalizedId)?.catch?.(() => {});
    }

    let watchedHubInitialized = false;
    function initWatchedHub() {
      if (watchedHubInitialized) return;
      watchedHubInitialized = true;

      elements.watchedPillAll?.addEventListener('click', () => applyWatchedFilter('all'));
      elements.watchedPillResume?.addEventListener('click', () => applyWatchedFilter('resume'));
      elements.watchedPillDone?.addEventListener('click', () => applyWatchedFilter('done'));
      elements.watchedPillMovies?.addEventListener('click', () => applyWatchedFilter('movies'));
      elements.watchedPillSeries?.addEventListener('click', () => applyWatchedFilter('series'));
      elements.watchedClearBtn?.addEventListener('click', clearAllWatchedHistory);
      elements.watchedSyncNoticeBtn?.addEventListener('click', () => showUserPage());
    }

    function getAllWatchHistoryEntries() {
      const map = new Map();

      // 1. Remote watch history se disponível via AndPlayAccount
      const remote = getNormalizedRemoteHistory();
      remote.forEach(entry => {
        const id = String(entry.id || '').trim();
        if (!id) return;
        const type = entry.type === 'series' ? 'series' : 'movie';
        map.set(`${type}:${id}`, { ...entry, type, id });
      });

      // 2. Histórico pendente em localStorage
      try {
        const rawPending = localStorage.getItem('andplay_web_watch_history_pending_v1');
        if (rawPending) {
          const parsed = JSON.parse(rawPending);
          if (Array.isArray(parsed)) {
            parsed.forEach(item => {
              let id = String(item?.id || '').trim();
              if (!id) return;
              const type = item.type === 'series' ? 'series' : 'movie';
              if (type === 'series' && !findHistoryCatalogItem('series', id)) {
                const progress = getVodProgress('series', id);
                let seriesId = String(progress?.seriesId || '').trim();
                if (!seriesId) {
                  const rp = window.AndPlayAccount?.getRemoteWatchProgress?.() || [];
                  const found = rp.find(p => String(p.content_id) === id && p.series_id);
                  if (found && found.series_id) seriesId = String(found.series_id).trim();
                }
                if (seriesId) id = seriesId;
              }
              const key = `${type}:${id}`;
              if (!map.has(key)) {
                map.set(key, { type, id, updatedAt: Number(item.updatedAt) || Date.now() });
              }
            });
          }
        }
      } catch (e) {}

      // 3. Progresso individual salvo em andplay_web_vod_progress_*
      try {
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (!k || !k.startsWith(VOD_PROGRESS_PREFIX)) continue;
          const sub = k.slice(VOD_PROGRESS_PREFIX.length);
          const isSeries = sub.startsWith('series_');
          const type = isSeries ? 'series' : 'movie';
          let id = sub.replace(/^(series_|movie_)/, '');
          if (!id) continue;
          const progress = getVodProgress(type, id);
          if (isSeries) {
            let seriesId = String(progress?.seriesId || '').trim();
            if (!seriesId) {
              const rp = window.AndPlayAccount?.getRemoteWatchProgress?.() || [];
              const found = rp.find(p => String(p.content_id) === id && p.series_id);
              if (found && found.series_id) seriesId = String(found.series_id).trim();
            }
            if (seriesId) id = seriesId;
          }
          const key = `${type}:${id}`;
          const currentEntry = map.get(key);
          const itemTime = Number(progress?.updatedAt) || Date.now();
          if (!currentEntry) {
            map.set(key, { type, id, updatedAt: itemTime });
          } else if (itemTime > (Number(currentEntry.updatedAt) || 0)) {
            currentEntry.updatedAt = itemTime;
          }
        }
      } catch (e) {}

      // 4. Histórico local legado
      ['movie', 'series'].forEach(t => {
        try {
          const raw = localStorage.getItem(getWatchedStorageKey(t === 'series' ? 'series' : 'movies'));
          if (raw) {
            const list = JSON.parse(raw);
            if (Array.isArray(list)) {
              list.forEach(rawId => {
                const id = String(rawId || '').trim();
                if (!id) return;
                const key = `${t}:${id}`;
                if (!map.has(key)) {
                  map.set(key, { type: t, id, updatedAt: 0 });
                }
              });
            }
          }
        } catch (e) {}
      });

      return Array.from(map.values()).sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0));
    }

    async function showWatchedContent() {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      watchedReturnMode = ['home', 'movies', 'series', 'live'].includes(currentMode) ? currentMode : 'home';
      isWatchedView = true;
      isFavoritesView = false;
      currentMode = 'watched';
      setRouteHash('#/assistidos');
      document.title = 'Histórico - EPlay';

      hideLoading();
      if (elements.moviesHub) elements.moviesHub.style.display = 'none';
      if (elements.seriesHub) elements.seriesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'none';
      if (elements.userDashboard) elements.userDashboard.style.display = 'none';

      if (moviesHeroTimer) { clearInterval(moviesHeroTimer); moviesHeroTimer = null; }
      if (seriesHeroTimer) { clearInterval(seriesHeroTimer); seriesHeroTimer = null; }
      window.EPlayTvEpg?.deactivate();

      if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
      if (elements.loadMoreContainer) elements.loadMoreContainer.style.removeProperty('display');
      setHomeDashboardVisible(false);
      elements.contentPage?.classList.remove('is-active');
      if (elements.contentPage) elements.contentPage.hidden = true;
      document.querySelector('.status-bar')?.style.removeProperty('display');
      document.querySelector('main')?.style.removeProperty('display');

      document.querySelectorAll('.nav-tabs .nav-tab').forEach(b => b.classList.remove('active'));
      elements.tabWatchedBtn?.classList.add('active');
      elements.tabUserBtn?.classList.remove('active');
      elements.accountBtn?.classList.remove('active');
      document.querySelectorAll('.mobile-bottom-nav button').forEach(b => b.classList.remove('active'));
      elements.mobileWatchedBtn?.classList.add('active');

      startViewTransition();
      window.scrollTo({ top: 0, behavior: 'instant' });

      elements.searchInput.value = '';
      elements.resetCategoryBtn.style.display = 'none';
      elements.categorySelect.value = 'ALL';
      elements.categorySelect.disabled = true;
      elements.categorySelect.style.display = 'none';
      elements.categoryLabel.textContent = '👁 Assistidos';
      elements.searchInput.placeholder = 'Pesquisar nos filmes e séries assistidos...';

      initWatchedHub();
      if (elements.watchedHub) elements.watchedHub.style.display = 'block';

      const isAccountSignedIn = Boolean(window.AndPlayAccount?.isSignedIn?.());
      if (elements.watchedSyncNotice) {
        elements.watchedSyncNotice.style.display = isAccountSignedIn ? 'none' : 'flex';
      }

      if (isAccountSignedIn) {
        try {
          await window.AndPlayAccount.refreshWatchHistory?.();
        } catch (e) {
          console.warn('[EPlay Watched] Sync history warning:', e);
        }
      }

      const history = getAllWatchHistoryEntries();
      const hasMovies = history.some(entry => entry.type === 'movie');
      const hasSeries = history.some(entry => entry.type === 'series');

      const [movieCatalog, seriesCatalog] = await Promise.all([
        hasMovies ? loadFullMovies() : Promise.resolve(fullMoviesCache || []),
        hasSeries ? loadFullSeries() : Promise.resolve(fullSeriesCache || [])
      ]);

      if (!isWatchedView) return;

      const movieMap = new Map();
      const seriesMap = new Map();
      (movieCatalog || []).forEach(item => {
        const id = item.stream_id || item.primaryItem?.stream_id;
        if (id != null) movieMap.set(String(id), item);
        (item.versions || []).forEach(version => {
          if (version?.streamId != null && !movieMap.has(String(version.streamId))) {
            movieMap.set(String(version.streamId), item);
          }
        });
      });
      (seriesCatalog || []).forEach(item => {
        const id = item.series_id ?? item.primaryItem?.series_id;
        if (id != null) seriesMap.set(String(id), item);
        (item.versions || []).forEach(version => {
          const seriesId = version?.seriesId ?? version?.item?.series_id;
          if (seriesId != null && !seriesMap.has(String(seriesId))) {
            seriesMap.set(String(seriesId), item);
          }
        });
      });

      const ordered = [];
      history.forEach(entry => {
        const key = String(entry.id || '');
        if (!key) return;
        const match = entry.type === 'series'
          ? (seriesMap.get(key) || buildHistoryFallbackGroup('series', key))
          : (movieMap.get(key) || buildHistoryFallbackGroup('movie', key));
        if (!match) return;
        if (!match._searchType) match._searchType = entry.type;
        if (!ordered.some(item => item === match || (item._searchType === entry.type && String(
          entry.type === 'series'
            ? (item.series_id ?? item.primaryItem?.series_id ?? item.id)
            : (item.stream_id ?? item.primaryItem?.stream_id ?? item.id)
        ) === key))) {
          ordered.push(match);
        }
      });

      fullWatchedList = ordered;
      currentWatchedFilter = 'all';
      document.querySelectorAll('.watched-filter-pill').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.watchedFilter === 'all');
      });

      updateWatchedCounters();
      currentMediaList = ordered;
      applyFilterAndRender('');

      if (ordered.length === 0) {
        elements.mediaGrid.innerHTML =
          '<div style="grid-column:1/-1;text-align:center;color:#888;padding:55px 20px;">' +
            '<div style="font-size:42px;margin-bottom:12px;">👁</div>' +
            '<div style="font-size:17px;color:#fff;font-weight:700;">Nenhum conteúdo assistido recentemente.</div>' +
            '<div style="font-size:12px;margin-top:8px;">Os títulos que você iniciar aparecerão aqui automaticamente com barra de progresso.</div>' +
          '</div>';
        elements.mediaCount.textContent = '0 assistidos';
        elements.loadMoreContainer.style.display = 'none';
      }
    }

    function restoreCatalogView() {
      if (!isWatchedView) return;
      const returnMode = watchedReturnMode;
      isWatchedView = false;
      if (elements.watchedHub) elements.watchedHub.style.display = 'none';
      elements.tabWatchedBtn?.classList.remove('active');
      elements.searchInput.value = '';
      elements.resetCategoryBtn.style.display = 'none';
      elements.categorySelect.value = 'ALL';
      elements.categorySelect.disabled = false;

      if (returnMode === 'home') { showHome(); return; }
      if (returnMode === 'live') { switchMode('live', true); return; }

      currentMode = returnMode === 'series' ? 'series' : 'movies';
      if (currentMode === 'movies') {
        elements.tabMoviesBtn.classList.add('active');
        elements.tabSeriesBtn.classList.remove('active');
        elements.categoryLabel.textContent = 'Catálogo Geral: Todos os Filmes';
        elements.searchInput.placeholder = 'Pesquisar filmes por título, gênero, ator ou diretor...';
        currentMediaList = fullMoviesCache || [];
      } else {
        elements.tabMoviesBtn.classList.remove('active');
        elements.tabSeriesBtn.classList.add('active');
        elements.categoryLabel.textContent = 'Catálogo Geral: Todas as Séries';
        elements.searchInput.placeholder = 'Pesquisar séries por título, gênero ou elenco...';
        currentMediaList = fullSeriesCache || [];
      }
      elements.categorySelect.style.removeProperty('display');
      applyFilterAndRender('');
    }

    // ==========================================
    // TELA DO USUÁRIO INDEPENDENTE (USER DASHBOARD)
    // ==========================================
    let userReturnMode = 'home';
    let userDashboardInitialized = false;

    function renderUserPage() {
      if (!elements.userDashboard) return;

      // 1. Avatar
      const savedAvatar = localStorage.getItem('andplay_user_avatar') || '👤';
      if (elements.userAvatarDisplay) elements.userAvatarDisplay.textContent = savedAvatar;

      // 2. Status do Usuário
      const isSignedIn = Boolean(window.AndPlayAccount?.isSignedIn?.());
      const currentUser = window.AndPlayAccount?.getCurrentUser?.() || null;
      if (isSignedIn && currentUser) {
        const name = window.AndPlayAccount?.getDisplayName?.(currentUser) || currentUser.email?.split('@')[0] || 'Usuário EPlay';
        if (elements.userProfileName) elements.userProfileName.textContent = name;
        if (elements.userProfileEmail) elements.userProfileEmail.textContent = currentUser.email || 'Conta sincronizada';
        if (elements.userProfileBadge) {
          elements.userProfileBadge.className = 'user-status-badge badge-connected';
          elements.userProfileBadge.textContent = '● Nuvem Conectada';
        }
        if (elements.userConnectedEmail) elements.userConnectedEmail.textContent = currentUser.email || '-';
        if (elements.userConnectedId) elements.userConnectedId.textContent = currentUser.id ? currentUser.id.slice(0, 20) + '...' : '-';
        if (elements.userConnectedSyncStatus) elements.userConnectedSyncStatus.textContent = 'Sincronizado';
        if (elements.userAccountConnectedBlock) elements.userAccountConnectedBlock.style.display = 'block';
        if (elements.userAccountAuthBlock) elements.userAccountAuthBlock.style.display = 'none';
        if (elements.userHeroAuthBtn) elements.userHeroAuthBtn.textContent = 'Minha Conta';
      } else {
        if (elements.userProfileName) elements.userProfileName.textContent = 'Usuário EPlay';
        if (elements.userProfileEmail) elements.userProfileEmail.textContent = 'Perfil local neste aparelho (sem sincronização)';
        if (elements.userProfileBadge) {
          elements.userProfileBadge.className = 'user-status-badge badge-guest';
          elements.userProfileBadge.textContent = '○ Modo Local';
        }
        if (elements.userAccountConnectedBlock) elements.userAccountConnectedBlock.style.display = 'none';
        if (elements.userAccountAuthBlock) elements.userAccountAuthBlock.style.display = 'block';
        if (elements.userHeroAuthBtn) elements.userHeroAuthBtn.textContent = 'Conectar Conta';
      }

      // 3. Estatísticas em Tempo Real
      let totalSeconds = 0;
      try {
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (k && k.startsWith(VOD_PROGRESS_PREFIX)) {
            const raw = localStorage.getItem(k);
            if (raw) {
              const parsed = JSON.parse(raw);
              if (parsed && Number(parsed.position) > 0) {
                totalSeconds += Number(parsed.position);
              }
            }
          }
        }
        const stats = JSON.parse(localStorage.getItem('andplay_web_watch_stats_v1') || '{}');
        if (stats && stats.devices) {
          Object.values(stats.devices).forEach(d => {
            if (d && d.movieSeconds) totalSeconds += Number(d.movieSeconds);
            if (d && d.seriesSeconds) totalSeconds += Number(d.seriesSeconds);
          });
        }
      } catch (e) {}
      const hours = Math.floor(totalSeconds / 3600);
      const minutes = Math.floor((totalSeconds % 3600) / 60);
      if (elements.statWatchTime) {
        elements.statWatchTime.textContent = hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
      }

      const historyEntries = getAllWatchHistoryEntries();
      const movieCount = historyEntries.filter(e => e.type === 'movie').length;
      const seriesCount = historyEntries.filter(e => e.type === 'series').length;
      if (elements.statMoviesCount) elements.statMoviesCount.textContent = movieCount;
      if (elements.statSeriesCount) elements.statSeriesCount.textContent = seriesCount;

      const favs = readFavorites();
      if (elements.statFavCount) elements.statFavCount.textContent = favs.length;

      try {
        const liveHist = JSON.parse(localStorage.getItem('andplay_web_live_history_v1') || '[]');
        if (elements.statLiveCount) elements.statLiveCount.textContent = Array.isArray(liveHist) ? liveHist.length : 0;
      } catch (e) {
        if (elements.statLiveCount) elements.statLiveCount.textContent = 0;
      }

      // 4. Preferências
      if (elements.userPrefSkipIntro) {
        elements.userPrefSkipIntro.checked = localStorage.getItem('andplay_web_skip_intro_auto') === '1';
      }
      if (elements.userPrefAutoNext) {
        elements.userPrefAutoNext.checked = localStorage.getItem('andplay_web_auto_next') !== '0';
      }
      if (elements.userPrefTvMode) {
        elements.userPrefTvMode.checked = document.body.classList.contains('tv-mode');
      }
      if (elements.userPrefAdultContent) {
        elements.userPrefAdultContent.checked = isAdultContentEnabled();
      }
      if (elements.userPrefQualitySelect) {
        elements.userPrefQualitySelect.value = getPreferredQualityPreference();
      }
      if (elements.userPrefAudioSelect) {
        elements.userPrefAudioSelect.value = getPreferredAudioPreference();
      }

      // 5. Uso do Armazenamento
      try {
        let totalBytes = 0;
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          totalBytes += (k.length + (localStorage.getItem(k)?.length || 0)) * 2;
        }
        if (elements.userStorageUsage) {
          elements.userStorageUsage.textContent = totalBytes > 1048576
            ? `${(totalBytes / (1024 * 1024)).toFixed(2)} MB`
            : `${(totalBytes / 1024).toFixed(1)} KB`;
        }
      } catch (e) {}
    }

    function initUserDashboard() {
      if (userDashboardInitialized) return;
      userDashboardInitialized = true;

      // 1. Avatar Picker
      elements.userAvatarEditBtn?.addEventListener('click', (e) => {
        e.stopPropagation();
        if (!elements.userAvatarPicker) return;
        const isHidden = elements.userAvatarPicker.style.display === 'none';
        elements.userAvatarPicker.style.display = isHidden ? 'flex' : 'none';
      });

      document.addEventListener('click', (e) => {
        if (elements.userAvatarPicker && !e.target.closest('#userAvatarPicker') && !e.target.closest('#userAvatarEditBtn')) {
          elements.userAvatarPicker.style.display = 'none';
        }
      });

      elements.userAvatarPicker?.querySelectorAll('.user-avatar-opt').forEach(opt => {
        opt.addEventListener('click', () => {
          const av = opt.dataset.avatar || '👤';
          localStorage.setItem('andplay_user_avatar', av);
          if (elements.userAvatarDisplay) elements.userAvatarDisplay.textContent = av;
          elements.userAvatarPicker.style.display = 'none';
        });
      });

      // 2. Abas do Usuário
      document.querySelectorAll('.user-nav-pill').forEach(pill => {
        pill.addEventListener('click', () => {
          const panelName = pill.dataset.userPanel;
          document.querySelectorAll('.user-nav-pill').forEach(p => p.classList.toggle('active', p === pill));
          document.querySelectorAll('.user-tab-panel').forEach(panel => {
            const isMatch = panel.id === `userPanel${panelName.charAt(0).toUpperCase() + panelName.slice(1)}`;
            panel.style.display = isMatch ? 'block' : 'none';
            panel.classList.toggle('active', isMatch);
          });
        });
      });

      // 3. Abas de Autenticação
      let currentAuthTab = 'login';
      document.querySelectorAll('.user-auth-tab').forEach(tab => {
        tab.addEventListener('click', () => {
          currentAuthTab = tab.dataset.authTab;
          document.querySelectorAll('.user-auth-tab').forEach(t => t.classList.toggle('active', t === tab));
          if (elements.userAuthPassGroup) {
            elements.userAuthPassGroup.style.display = currentAuthTab === 'recovery' ? 'none' : 'flex';
          }
          if (elements.userAuthSubmitBtn) {
            if (currentAuthTab === 'login') elements.userAuthSubmitBtn.textContent = 'Entrar na Conta';
            else if (currentAuthTab === 'register') elements.userAuthSubmitBtn.textContent = 'Criar Conta Gratuita';
            else elements.userAuthSubmitBtn.textContent = 'Enviar Link de Recuperação';
          }
          if (elements.userAuthGoogleGroup) {
            elements.userAuthGoogleGroup.style.display = currentAuthTab === 'recovery' ? 'none' : 'block';
          }
          if (elements.userAuthStatusMsg) elements.userAuthStatusMsg.textContent = '';
        });
      });

      // 4. Submissão de Autenticação
      elements.userAuthSubmitBtn?.addEventListener('click', async () => {
        const email = elements.userAuthEmailInput?.value?.trim() || '';
        const password = elements.userAuthPassInput?.value || '';
        const statusEl = elements.userAuthStatusMsg;
        if (!email) {
          if (statusEl) { statusEl.className = 'user-auth-status error'; statusEl.textContent = 'Informe seu e-mail.'; }
          return;
        }
        if (currentAuthTab !== 'recovery' && (!password || password.length < 6)) {
          if (statusEl) { statusEl.className = 'user-auth-status error'; statusEl.textContent = 'A senha deve ter no mínimo 6 caracteres.'; }
          return;
        }

        try {
          if (statusEl) { statusEl.className = 'user-auth-status'; statusEl.textContent = 'Conectando...'; }
          if (currentAuthTab === 'login') {
            await window.AndPlayAccount?.signInDirect?.(email, password);
            if (statusEl) { statusEl.className = 'user-auth-status success'; statusEl.textContent = 'Conta conectada com sucesso!'; }
          } else if (currentAuthTab === 'register') {
            await window.AndPlayAccount?.signUpDirect?.(email, password);
            if (statusEl) { statusEl.className = 'user-auth-status success'; statusEl.textContent = 'Conta criada com sucesso!'; }
          } else {
            await window.AndPlayAccount?.requestPasswordResetDirect?.(email);
            if (statusEl) { statusEl.className = 'user-auth-status success'; statusEl.textContent = 'Instruções enviadas para seu e-mail.'; }
          }
          renderUserPage();
        } catch (err) {
          if (statusEl) { statusEl.className = 'user-auth-status error'; statusEl.textContent = err.message || 'Falha na autenticação.'; }
        }
      });

      elements.userAuthGoogleBtn?.addEventListener('click', async () => {
        const statusEl = elements.userAuthStatusMsg;
        if (statusEl) {
          statusEl.className = 'user-auth-status';
          statusEl.textContent = 'Iniciando login com Google...';
        }
        try {
          await window.AndPlayAccount?.signInWithGoogle?.();
        } catch (err) {
          if (statusEl) {
            statusEl.className = 'user-auth-status error';
            statusEl.textContent = err.message || 'Erro ao iniciar login com Google.';
          }
        }
      });

      // 5. Botões de Hero & Sessão
      elements.userHeroSyncBtn?.addEventListener('click', async () => {
        const btn = elements.userHeroSyncBtn;
        const originalText = btn.textContent;
        btn.textContent = '⏳ Sincronizando...';
        btn.disabled = true;
        try {
          await window.AndPlayAccount?.syncNow?.();
          btn.textContent = '✓ Sincronizado!';
        } catch (e) {
          btn.textContent = '✓ Salvo localmente';
        }
        setTimeout(() => { btn.textContent = originalText; btn.disabled = false; renderUserPage(); }, 1400);
      });

      elements.userHeroAuthBtn?.addEventListener('click', () => {
        elements.userTabBtnAccount?.click();
        elements.userAuthEmailInput?.focus();
      });

      elements.userLogoutBtn?.addEventListener('click', async () => {
        if (!confirm('Deseja realmente sair da sua conta? Seus dados locais permanecerão salvos.')) return;
        cachedHomeFeaturedItems = null;
        cachedHomeCatalogRails = { movie: null, series: null, interleaved: null };
        cachedHomeRecommendations = null;
        await window.AndPlayAccount?.signOutDirect?.();
        showLoginScreen('login');
      });

      elements.userChangePassBtn?.addEventListener('click', () => {
        const email = elements.userConnectedEmail?.textContent;
        if (email && email !== '-') {
          window.AndPlayAccount?.requestPasswordResetDirect?.(email)
            .then(() => alert(`Enviamos um link de redefinição de senha para ${email}.`))
            .catch(err => alert(err.message || 'Não foi possível solicitar redefinição.'));
        }
      });

      elements.userDeleteDataBtn?.addEventListener('click', async () => {
        if (!confirm('Deseja realmente apagar TODOS os seus dados salvos?\n\nIsso removerá todo o histórico de filmes e séries assistidos, favoritos salvos e progresso de reprodução, tanto deste dispositivo quanto da nuvem.\n\nSua conta continuará ativa.')) return;

        const btn = elements.userDeleteDataBtn;
        const originalText = btn.textContent;
        btn.textContent = '⏳ Deletando dados...';
        btn.disabled = true;

        try {
          await window.AndPlayAccount?.deleteUserData?.();

          // Resetar caches e estados do app.js
          try {
            fullMoviesCache = null;
            fullSeriesCache = null;
            cachedHomeFeaturedItems = null;
            cachedHomeCatalogRails = { movie: null, series: null, interleaved: null };
            cachedHomeRecommendations = null;
          } catch (e) {}

          alert('Todos os seus dados salvos foram apagados com sucesso do banco de dados e deste aparelho!');
          renderUserPage();
          if (currentMode === 'home') {
            showHome();
          }
        } catch (err) {
          alert('Erro ao deletar dados: ' + (err.message || 'Falha na conexão.'));
        } finally {
          btn.textContent = originalText;
          btn.disabled = false;
        }
      });

      elements.userDeleteAccountBtn?.addEventListener('click', async () => {
        const confirm1 = confirm('ATENÇÃO: Deseja realmente EXCLUIR SUA CONTA permanentemente?\n\nEsta ação é irreversível. Todos os seus dados, histórico, favoritos, preferências e seu acesso serão apagados para sempre.');
        if (!confirm1) return;

        const confirm2 = prompt('Para confirmar a exclusão definitiva, digite "DELETAR" abaixo:');
        if (confirm2 !== 'DELETAR') {
          alert('Exclusão cancelada. O texto digitado não confere.');
          return;
        }

        const btn = elements.userDeleteAccountBtn;
        const originalText = btn.textContent;
        btn.textContent = '⏳ Excluindo conta...';
        btn.disabled = true;

        try {
          await window.AndPlayAccount?.deleteUserAccount?.();
          alert('Sua conta e todos os dados associados foram completamente excluídos.');
          showLoginScreen('login');
        } catch (err) {
          alert('Erro ao excluir conta: ' + (err.message || 'Falha na requisição.'));
          btn.textContent = originalText;
          btn.disabled = false;
        }
      });

      // 6. Preferências
      elements.userPrefSkipIntro?.addEventListener('change', (e) => {
        localStorage.setItem('andplay_web_skip_intro_auto', e.target.checked ? '1' : '0');
        window.AndPlayAccount?.queueSyncPreference?.();
      });

      elements.userPrefAutoNext?.addEventListener('change', (e) => {
        localStorage.setItem('andplay_web_auto_next', e.target.checked ? '1' : '0');
        window.AndPlayAccount?.queueSyncPreference?.();
      });

      elements.userPrefTvMode?.addEventListener('change', (e) => {
        const isTv = e.target.checked;
        document.body.classList.toggle('tv-mode', isTv);
        localStorage.setItem('andplay_tv_mode', isTv ? '1' : '0');
      });

      elements.userPrefAdultContent?.addEventListener('change', (e) => {
        const enabled = e.target.checked;
        localStorage.setItem('andplay_pref_adult_content', enabled ? '1' : '0');
        try {
          const prefs = JSON.parse(localStorage.getItem('andplay_web_preferences_v1') || '{}');
          prefs.adult_content = enabled;
          localStorage.setItem('andplay_web_preferences_v1', JSON.stringify(prefs));
        } catch (_) {}
        window.AndPlayAccount?.queueSyncPreference?.();

        globalSearchCatalogCache = null;

        const currentCat = elements.categorySelect?.value;
        if (!enabled && isAdultCategoryId(currentCat)) {
          elements.categorySelect.value = 'ALL';
          if (elements.resetCategoryBtn) elements.resetCategoryBtn.style.display = 'none';
        }

        if (currentMode === 'movies') {
          populateCategoriesSelect(movieCategories, 'Filmes');
          if (elements.categorySelect.value === 'ALL' && isMoviesCuratedMode) {
            renderMoviesHub();
          } else {
            applyMoviesFiltersAndSort();
          }
        } else if (currentMode === 'series') {
          populateCategoriesSelect(seriesCategories, 'Séries');
          if (elements.categorySelect.value === 'ALL' && isSeriesCuratedMode) {
            renderSeriesHub();
          } else {
            applySeriesFiltersAndSort();
          }
        } else if (currentMode === 'live') {
          populateLiveCategoriesSelect();
          renderLiveCategoryPills();
          applyLiveFiltersAndSort();
        }
      });

      elements.userPrefQualitySelect?.addEventListener('change', (e) => {
        setPreferredQualityPreference(e.target.value);
      });

      elements.userPrefAudioSelect?.addEventListener('change', (e) => {
        setPreferredAudioPreference(e.target.value);
      });

      // 7. Atalhos
      elements.userShortcutWatchedCard?.addEventListener('click', () => showWatchedContent());
      elements.userShortcutFavCard?.addEventListener('click', () => showFavoritesContent());
      elements.userShortcutLiveCard?.addEventListener('click', () => switchMode('live', true));

      // 8. Armazenamento
      elements.userClearCacheBtn?.addEventListener('click', () => {
        const catalogKeys = [
          'stale_movies_catalog_v1', 'stale_series_catalog_v1',
          'stale_movie_categories_v1', 'stale_series_categories_v1',
          'andplay_home_recs_cache_v1', 'andplay_home_ratings_v1'
        ];
        catalogKeys.forEach(k => localStorage.removeItem(k));
        fullMoviesCache = null;
        fullSeriesCache = null;
        cachedHomeFeaturedItems = null;
        cachedHomeCatalogRails = { movie: null, series: null, interleaved: null };
        cachedHomeRecommendations = null;
        alert('Cache de catálogos limpo com sucesso! Os catálogos serão recarregados da fonte na próxima consulta.');
        renderUserPage();
      });

      elements.userExportFavsBtn?.addEventListener('click', () => {
        const favs = readFavorites();
        const blob = new Blob([JSON.stringify(favs, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `eplay-favoritos-${new Date().toISOString().slice(0, 10)}.json`;
        a.click();
        URL.revokeObjectURL(url);
      });

      elements.userResetAllDataBtn?.addEventListener('click', () => {
        if (!confirm('ATENÇÃO: Isso apagará todas as preferências, histórico de assistidos e favoritos locais deste navegador.\n\nDeseja continuar?')) return;
        localStorage.clear();
        window.location.reload();
      });
    }

    // ==========================================
    // SISTEMA DE LOGIN OBRIGATÓRIO (AUTH GATE)
    // ==========================================
    let currentAuthScreenTab = 'login';
    let authScreenInitialized = false;

    function setAuthScreenTab(tab) {
      currentAuthScreenTab = tab;
      const tabs = [
        { btn: elements.authScreenTabLogin, name: 'login' },
        { btn: elements.authScreenTabRegister, name: 'register' },
        { btn: elements.authScreenTabRecovery, name: 'recovery' }
      ];
      tabs.forEach(t => {
        if (t.btn) t.btn.classList.toggle('active', t.name === tab);
      });

      if (elements.authScreenPassGroup) {
        elements.authScreenPassGroup.style.display = tab === 'recovery' ? 'none' : 'flex';
      }
      if (elements.authScreenSubmitBtn) {
        if (tab === 'login') elements.authScreenSubmitBtn.textContent = 'Entrar na Conta';
        else if (tab === 'register') elements.authScreenSubmitBtn.textContent = 'Criar Conta Gratuita';
        else elements.authScreenSubmitBtn.textContent = 'Enviar Link de Recuperação';
      }
      if (elements.authScreenGoogleGroup) {
        elements.authScreenGoogleGroup.style.display = tab === 'recovery' ? 'none' : 'block';
      }
      if (elements.authScreenStatusMsg) {
        elements.authScreenStatusMsg.textContent = '';
        elements.authScreenStatusMsg.className = 'auth-status-msg';
      }
    }

    function showLoginScreen(tab = 'login') {
      if (elements.authLoadingOverlay) {
        elements.authLoadingOverlay.style.display = 'none';
      }
      document.body.classList.remove('auth-pending');
      document.body.classList.remove('is-authenticated');
      document.body.classList.add('not-authenticated');

      // Interromper qualquer reprodução ativa ou modal
      try {
        if (typeof closePlayer === 'function') closePlayer();
        if (typeof closeSeriesModal === 'function') closeSeriesModal();
        if (typeof closeMovieVersionModal === 'function') closeMovieVersionModal();
      } catch (e) {}

      if (elements.authScreen) {
        elements.authScreen.style.display = 'flex';
      }

      setAuthScreenTab(tab);
      setTimeout(() => {
        elements.authScreenEmailInput?.focus();
      }, 60);
    }

    function hideLoginScreen() {
      if (elements.authLoadingOverlay) {
        elements.authLoadingOverlay.style.display = 'none';
      }
      document.body.classList.remove('auth-pending');
      document.body.classList.remove('not-authenticated');
      document.body.classList.add('is-authenticated');

      if (elements.authScreen) {
        elements.authScreen.style.display = 'none';
      }
    }

    async function handleAuthScreenSubmit() {
      const email = elements.authScreenEmailInput?.value?.trim() || '';
      const password = elements.authScreenPassInput?.value || '';
      const statusEl = elements.authScreenStatusMsg;
      const submitBtn = elements.authScreenSubmitBtn;

      if (!email) {
        if (statusEl) {
          statusEl.className = 'auth-status-msg error';
          statusEl.textContent = 'Informe seu e-mail.';
        }
        elements.authScreenEmailInput?.focus();
        return;
      }

      if (currentAuthScreenTab !== 'recovery' && (!password || password.length < 6)) {
        if (statusEl) {
          statusEl.className = 'auth-status-msg error';
          statusEl.textContent = 'A senha deve ter no mínimo 6 caracteres.';
        }
        elements.authScreenPassInput?.focus();
        return;
      }

      if (statusEl) {
        statusEl.className = 'auth-status-msg loading';
        statusEl.textContent = currentAuthScreenTab === 'recovery' ? 'Enviando...' : 'Autenticando...';
      }
      if (submitBtn) submitBtn.disabled = true;

      try {
        if (currentAuthScreenTab === 'login') {
          await window.AndPlayAccount?.signInDirect?.(email, password);
          if (statusEl) {
            statusEl.className = 'auth-status-msg success';
            statusEl.textContent = 'Login efetuado com sucesso!';
          }
          hideLoginScreen();
          onUserAuthenticated();
        } else if (currentAuthScreenTab === 'register') {
          await window.AndPlayAccount?.signUpDirect?.(email, password);
          if (statusEl) {
            statusEl.className = 'auth-status-msg success';
            statusEl.textContent = 'Conta criada com sucesso!';
          }
          if (window.AndPlayAccount?.isSignedIn?.()) {
            hideLoginScreen();
            onUserAuthenticated();
          } else {
            if (statusEl) {
              statusEl.className = 'auth-status-msg success';
              statusEl.textContent = 'Conta criada! Verifique seu e-mail para confirmar o acesso.';
            }
          }
        } else {
          await window.AndPlayAccount?.requestPasswordResetDirect?.(email);
          if (statusEl) {
            statusEl.className = 'auth-status-msg success';
            statusEl.textContent = 'Instruções enviadas para seu e-mail.';
          }
        }
      } catch (err) {
        if (statusEl) {
          statusEl.className = 'auth-status-msg error';
          statusEl.textContent = err.message || 'Falha na autenticação.';
        }
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    }

    async function onUserAuthenticated() {
      hideLoginScreen();
      renderUserPage();
      const targetRoute = pendingRoute || getRouteFromLocation();
      pendingRoute = null;
      if (!routerInitialized) {
        initRouter();
      }
      if (targetRoute && targetRoute !== '#/inicio' && targetRoute !== '#/' && targetRoute !== '') {
        await navigateRoute(targetRoute, false);
      } else {
        const isTvMode = window.location.pathname.endsWith('/tvmode') || window.location.pathname.endsWith('/tvmode/') || window.location.pathname.includes('/tvmode') || window.location.hash.includes('tvmode') || window.location.search.includes('tvmode');
        if (isTvMode && window.initTvCableBox) {
          window.initTvCableBox();
        } else {
          showHome();
        }
      }
    }

    function onUserLoggedOut() {
      pendingRoute = null;
      showLoginScreen('login');
    }

    function initAuthScreen() {
      if (authScreenInitialized) return;
      authScreenInitialized = true;

      elements.authScreenTabLogin?.addEventListener('click', () => setAuthScreenTab('login'));
      elements.authScreenTabRegister?.addEventListener('click', () => setAuthScreenTab('register'));
      elements.authScreenTabRecovery?.addEventListener('click', () => setAuthScreenTab('recovery'));

      elements.authScreenSubmitBtn?.addEventListener('click', handleAuthScreenSubmit);

      elements.authScreenEmailInput?.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          if (currentAuthScreenTab === 'recovery') {
            handleAuthScreenSubmit();
          } else {
            elements.authScreenPassInput?.focus();
          }
        }
      });

      elements.authScreenPassInput?.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          handleAuthScreenSubmit();
        }
      });

      elements.authScreenGoogleBtn?.addEventListener('click', async () => {
        const statusEl = elements.authScreenStatusMsg;
        if (statusEl) {
          statusEl.className = 'auth-status-msg loading';
          statusEl.textContent = 'Iniciando login com Google...';
        }
        try {
          await window.AndPlayAccount?.signInWithGoogle?.();
        } catch (err) {
          if (statusEl) {
            statusEl.className = 'auth-status-msg error';
            statusEl.textContent = err.message || 'Erro ao iniciar login com Google.';
          }
        }
      });

      window.addEventListener('andplay:auth-changed', (event) => {
        const isSignedIn = event.detail?.isSignedIn ?? Boolean(window.AndPlayAccount?.isSignedIn?.());
        if (isSignedIn) {
          onUserAuthenticated();
        } else {
          onUserLoggedOut();
        }
      });

      window.showLoginScreen = showLoginScreen;
      window.hideLoginScreen = hideLoginScreen;
    }

    function showUserPage() {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      userReturnMode = ['home', 'movies', 'series', 'live', 'favorites', 'watched'].includes(currentMode) ? currentMode : 'home';
      currentMode = 'user';
      isFavoritesView = false;
      isWatchedView = false;
      setRouteHash('#/conta');
      document.title = 'Minha Conta - EPlay';

      if (moviesHeroTimer) { clearInterval(moviesHeroTimer); moviesHeroTimer = null; }
      if (seriesHeroTimer) { clearInterval(seriesHeroTimer); seriesHeroTimer = null; }
      clearInterval(homeFeaturedTimer); homeFeaturedTimer = null;
      window.EPlayTvEpg?.deactivate();

      hideLoading();
      if (elements.moviesHub) elements.moviesHub.style.display = 'none';
      if (elements.seriesHub) elements.seriesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'none';
      if (elements.watchedHub) elements.watchedHub.style.display = 'none';
      setHomeDashboardVisible(false);
      elements.contentPage?.classList.remove('is-active');
      if (elements.contentPage) elements.contentPage.hidden = true;
      if (elements.mediaGrid) elements.mediaGrid.style.display = 'none';
      if (elements.loadMoreContainer) elements.loadMoreContainer.style.display = 'none';
      document.querySelector('.status-bar')?.style.setProperty('display', 'none');
      document.querySelector('main')?.style.removeProperty('display');
      if (elements.categorySelect) elements.categorySelect.style.display = 'none';

      document.querySelectorAll('.nav-tabs .nav-tab').forEach(b => b.classList.remove('active'));
      elements.tabUserBtn?.classList.add('active');
      elements.accountBtn?.classList.add('active');
      document.querySelectorAll('.mobile-bottom-nav button').forEach(b => b.classList.remove('active'));
      elements.mobileAccountBtn?.classList.add('active');

      if (elements.userDashboard) elements.userDashboard.style.display = 'block';

      startViewTransition();
      window.scrollTo({ top: 0, behavior: 'instant' });

      initUserDashboard();
      renderUserPage();
    }
    window.showUserPage = showUserPage;

    // ==========================================
    // HOME / DASHBOARD
    // ==========================================
    function getHomeItemPoster(item) {
      if (!item) return '';
      if (item.type === 'movie') return getBestPosterUrl(item.primaryItem || item);
      return item.cover || item.stream_icon || '';
    }

    function getHomeItemTime(item) {
      const target = item?.primaryItem || item?.item || item;
      const raw = target?.added || target?.last_modified || target?.created_at || item?.added || item?.last_modified || 0;
      if (!raw) return 0;
      const num = Number(raw);
      if (!isNaN(num) && num > 0) {
        return num < 1e11 ? num * 1000 : num;
      }
      const parsed = Date.parse(raw);
      return isNaN(parsed) ? 0 : parsed;
    }

    function pickRandomSample(arr, count) {
      if (!Array.isArray(arr) || !arr.length || count <= 0) return [];
      if (arr.length <= count) return arr.slice();
      const copy = arr.slice();
      const selected = [];
      for (let i = 0; i < count; i++) {
        const idx = Math.floor(Math.random() * copy.length);
        selected.push(copy[idx]);
        copy.splice(idx, 1);
      }
      return selected;
    }

    function shuffleArray(arr) {
      if (!Array.isArray(arr) || arr.length <= 1) return arr ? arr.slice() : [];
      const copy = arr.slice();
      for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
      }
      return copy;
    }

    function getHomeFeaturedItems() {
      // Se já temos destaques selecionados e o catálogo não cresceu significativamente,
      // preservamos os mesmos itens para evitar reembaralhamento e piscamento no boot
      if (Array.isArray(cachedHomeFeaturedItems) && cachedHomeFeaturedItems.length > 0) {
        const currentCatalogSize = (Array.isArray(fullMoviesCache) ? fullMoviesCache.length : 0) +
          (Array.isArray(fullSeriesCache) ? fullSeriesCache.length : 0);
        if (cachedHomeFeaturedItems.length >= HOME_FEATURED_LIMIT || currentCatalogSize <= cachedHomeFeaturedItems.length) {
          const movieMap = new Map();
          (Array.isArray(fullMoviesCache) ? fullMoviesCache : []).forEach(m => {
            const id = String(m.stream_id || m.primaryItem?.stream_id || '');
            if (id) movieMap.set(id, m);
          });
          const seriesMap = new Map();
          (Array.isArray(fullSeriesCache) ? fullSeriesCache : []).forEach(s => {
            const id = String(s.series_id || '');
            if (id) seriesMap.set(id, s);
          });

          cachedHomeFeaturedItems.forEach(item => {
            const fresh = item.type === 'movie' ? movieMap.get(item.id) : seriesMap.get(item.id);
            if (!fresh) return;
            item.item = fresh;
            item.title = cleanDisplayTitle(fresh.name || fresh.title || item.title);
            item.year = fresh.year || item.year;
            item.rating = fresh.rating || item.rating;
            item.poster = item.type === 'movie'
              ? (getHomeItemPoster({ type: 'movie', primaryItem: fresh }) || item.poster)
              : (getHomeItemPoster({ type: 'series', cover: fresh.cover, stream_icon: fresh.stream_icon }) || item.poster);
            item.plot = fresh.plot || item.plot;
            item.added = getHomeItemTime(fresh) || item.added;
          });
          return cachedHomeFeaturedItems;
        }
      }

      const movies = (Array.isArray(fullMoviesCache) ? fullMoviesCache : [])
        .filter(item => !isAdultItem(item))
        .map(item => ({
        type: 'movie',
        id: String(item.stream_id || item.primaryItem?.stream_id || ''),
        item,
        title: cleanDisplayTitle(item.name || item.title || 'Filme'),
        year: item.year || '',
        rating: item.rating || '',
        poster: getHomeItemPoster({ type: 'movie', primaryItem: item }),
        plot: item.plot || '',
        added: getHomeItemTime(item)
      })).filter(item => item.id && item.poster);

      const series = (Array.isArray(fullSeriesCache) ? fullSeriesCache : [])
        .filter(item => !isAdultItem(item))
        .map(item => ({
        type: 'series',
        id: String(item.series_id || ''),
        item,
        title: cleanDisplayTitle(item.name || item.title || 'Série'),
        year: item.year || '',
        rating: item.rating || '',
        poster: getHomeItemPoster({ type: 'series', cover: item.cover, stream_icon: item.stream_icon }),
        plot: item.plot || '',
        added: getHomeItemTime(item)
      })).filter(item => item.id && item.poster);

      const allCatalog = [...movies, ...series];
      if (!allCatalog.length) {
        return cachedHomeFeaturedItems || [];
      }
      if (allCatalog.length <= HOME_FEATURED_LIMIT) {
        cachedHomeFeaturedItems = allCatalog;
        return cachedHomeFeaturedItems;
      }

      // 1. Reservar exatamente 5 vagas no banner para os últimos 5 itens adicionados ao catálogo
      const sortedByAdded = allCatalog.slice().sort((a, b) => (b.added || 0) - (a.added || 0));
      const latestAdded5 = sortedByAdded.slice(0, 5);
      const addedKeys = new Set(latestAdded5.map(x => x.type + ':' + x.id));

      // 2. Selecionar 5 outros destaques (alta nota, aclamados ou populares) entre o restante
      const remainingPool = allCatalog
        .filter(x => !addedKeys.has(x.type + ':' + x.id))
        .sort((a, b) => {
          const rA = Number(a.rating || a.item?.rating || 0);
          const rB = Number(b.rating || b.item?.rating || 0);
          if (rB !== rA) return rB - rA;
          return compareByReleaseYear(a, b);
        });

      // Amostragem dinâmica entre os top 40 destaques para rotatividade a cada recarregamento
      const highlightCandidates = remainingPool.slice(0, Math.min(40, remainingPool.length));
      const highlights5 = pickRandomSample(highlightCandidates, 5);

      // 3. Intercalar no banner: Novidade do Catálogo ⇄ Destaque Aclamado
      const featured = [];
      for (let i = 0; i < 5; i++) {
        if (latestAdded5[i]) featured.push(latestAdded5[i]);
        if (highlights5[i]) featured.push(highlights5[i]);
      }

      cachedHomeFeaturedItems = featured.slice(0, HOME_FEATURED_LIMIT);
      return cachedHomeFeaturedItems;
    }

    function resolveHomeWatchedItem(type, id) {
      const group = findHistoryCatalogItem(type, id) || buildHistoryFallbackGroup(type, id);
      if (!group) return null;

      const progress = getHistoryProgressFallback(type, id);
      const meta = remoteHistoryMetadata[type === 'series' ? 'series' : 'movies'].get(String(id)) || {};
      const fallbackTitle = meta.title || getSeriesHistoryTitle(progress);
      let rawTitle = group.name || group.title || '';
      if (!rawTitle || rawTitle === 'Série ' + id || rawTitle === 'Filme ' + id) {
        if (fallbackTitle) rawTitle = fallbackTitle;
      }
      const title = cleanDisplayTitle(rawTitle || (type === 'series' ? 'Série ' + id : 'Filme ' + id));

      const poster = type === 'series'
        ? (group.cover || group.stream_icon || meta.poster || progress?.poster || '')
        : (getBestPosterUrl(group.primaryItem || group) || group.poster || group.stream_icon || group.cover || meta.poster || progress?.poster || '');

      return {
        type,
        id: String(id),
        item: group,
        title,
        year: group.year || meta.year || progress?.year || '',
        rating: group.rating || meta.rating || '',
        poster,
        progress
      };
    }

    function getHomeWatchedItems() {
      const history = getNormalizedRemoteHistory();
      return history
        .slice(0, HOME_WATCHED_LIMIT)
        .map(entry => resolveHomeWatchedItem(entry.type, entry.id))
        .filter(Boolean);
    }

    const HOME_RAIL_ITEM_LIMIT = 24;
    const HOME_THEME_COUNT = 8;
    const HOME_THEME_MIN_ITEMS = 5;

    const HOME_THEME_ALIASES = [
      { keys: ['action', 'acao', 'adventure', 'aventura'], label: 'Ação e Aventura' },
      { keys: ['comedy', 'comedia'], label: 'Comédia' },
      { keys: ['crime', 'policial', 'investigacao'], label: 'Crime' },
      { keys: ['drama'], label: 'Drama' },
      { keys: ['horror', 'terror'], label: 'Terror' },
      { keys: ['thriller', 'suspense', 'misterio'], label: 'Suspense' },
      { keys: ['romance', 'romantico'], label: 'Romance' },
      { keys: ['science fiction', 'sci-fi', 'scifi', 'sci fi', 'ficcao cientifica', 'ficcao'], label: 'Ficção Científica' },
      { keys: ['fantasy', 'fantasia'], label: 'Fantasia' },
      { keys: ['animation', 'animacao', 'anime', 'desenho'], label: 'Animação' },
      { keys: ['documentary', 'documentario', 'doc'], label: 'Documentários' },
      { keys: ['family', 'familia', 'infantil', 'kids'], label: 'Família' },
      { keys: ['war', 'guerra'], label: 'Guerra' },
      { keys: ['western', 'faroeste'], label: 'Faroeste' },
      { keys: ['music', 'musical', 'musica'], label: 'Música' },
      { keys: ['history', 'historia', 'historico'], label: 'História' }
    ];

    const LIVE_THEME_ALIASES = [
      {
        keys: ['futebol', 'jogo', 'jogos', 'partida', 'soccer', 'football', 'campeonato', 'libertadores', 'brasileirao', 'champions', 'premier', 'copa', 'serie a', 'serie b', 'esporte', 'esportes', 'sports', 'sport', 'premiere', 'sportv', 'espn', 'cazetv', 'tnt', 'combate', 'nba', 'ufc', 'f1', 'formula 1'],
        categories: ['JOGOS', 'sports']
      },
      {
        keys: ['noticia', 'noticias', 'news', 'jornal', 'jornalismo', 'politica', 'economia', 'cnn', 'globonews', 'bandnews', 'jovempan'],
        categories: ['news']
      },
      {
        keys: ['infantil', 'desenho', 'desenhos', 'kids', 'animacao', 'anime', 'cartoon', 'disney', 'nickelodeon', 'gloob', 'discovery kids'],
        categories: ['kids']
      },
      {
        keys: ['tv aberta', 'aberta', 'abertos', 'nacional', 'regional', 'globo', 'sbt', 'record', 'band', 'redetv', 'cultura'],
        categories: ['open_tv']
      },
      {
        keys: ['filme', 'filmes', 'serie', 'series', 'cinema', 'telecine', 'hbo', 'max', 'tnt', 'megapix', 'paramount', 'universal', 'warner', 'axn', 'space'],
        categories: ['movies']
      },
      {
        keys: ['24h', '24 horas', 'classicos', 'retro', 'chaves', 'maratona'],
        categories: ['channels_24h']
      },
      {
        keys: ['reality', 'reality show', 'bbb', 'big brother', 'fazenda', 'de ferias com o ex', 'masterchef'],
        categories: ['reality']
      },
      {
        keys: ['variedades', 'documentario', 'documentarios', 'doc', 'cultura', 'natureza', 'discovery', 'national geographic', 'natgeo', 'history', 'animal planet', 'h&h'],
        categories: ['variety']
      },
      {
        keys: ['adulto', 'adultos', 'porn', 'sexy', 'playboy', 'venus', 'sextreme'],
        categories: ['adult']
      }
    ];

    function getHomeCatalogItems(type) {
      if (type === 'series') {
        return (Array.isArray(fullSeriesCache) ? fullSeriesCache : [])
          .filter(item => !isAdultItem(item))
          .map(item => ({
            type: 'series',
            id: String(item.series_id || ''),
            item,
            title: cleanDisplayTitle(item.name || item.title || 'Série'),
            year: item.year || '',
            rating: item.rating || '',
            poster: getHomeItemPoster({ type: 'series', cover: item.cover, stream_icon: item.stream_icon }),
            plot: item.plot || '',
            genre: item.genre || item.genre_name || '',
            imdbId: item.imdbId || item.imdb_id || '',
            added: getHomeItemTime(item)
          }))
          .filter(item => item.id && item.poster);
      }

      return (Array.isArray(fullMoviesCache) ? fullMoviesCache : [])
        .filter(item => !isAdultItem(item))
        .map(item => ({
          type: 'movie',
          id: String(item.stream_id || item.primaryItem?.stream_id || ''),
          item,
          title: cleanDisplayTitle(item.name || item.title || 'Filme'),
          year: item.year || '',
          rating: item.rating || '',
          poster: getHomeItemPoster({ type: 'movie', primaryItem: item }),
          plot: item.plot || '',
          genre: item.genre || item.primaryItem?.genre || item.genre_name || '',
          imdbId: item.imdbId || item.imdb_id || item.primaryItem?.imdbId || item.primaryItem?.imdb_id || '',
          added: getHomeItemTime(item)
        }))
        .filter(item => item.id && item.poster);
    }

    function getHomeThemesForItem(item) {
      const sourceItem = item?.item || item;
      const rawValues = [
        item?.genre,
        sourceItem?.genre,
        sourceItem?.genre_name,
        sourceItem?.category_name,
        item?.category_name
      ].filter(Boolean);

      // Enriquecer com categoria do catálogo (Xtream API geralmente organiza por category_id)
      const isMovie = item?.type === 'movie' || sourceItem?.stream_id || !sourceItem?.series_id;
      const catList = isMovie ? movieCategories : seriesCategories;
      if (Array.isArray(catList) && catList.length > 0) {
        const cId = String(item?.category_id || sourceItem?.category_id || '');
        if (cId) {
          const found = catList.find(c => String(c.category_id) === cId);
          if (found && found.category_name) rawValues.push(found.category_name);
        }
        const catIds = item?.category_ids || sourceItem?.category_ids;
        if (Array.isArray(catIds)) {
          catIds.forEach(id => {
            const found = catList.find(c => String(c.category_id) === String(id));
            if (found && found.category_name) rawValues.push(found.category_name);
          });
        }
      }

      const imdbId = item?.imdbId || item?.imdb_id || sourceItem?.imdbId || sourceItem?.imdb_id || '';
      if (imdbId) {
        const cached = readHomeRatingCache()[String(imdbId)];
        if (Array.isArray(cached?.genres)) rawValues.push(cached.genres.join(','));
      }

      if (!rawValues.length) return [];

      const fullCombined = rawValues
        .join(' ')
        .replace(/[\[\]",;|/]+/g, ' ')
        .toLowerCase()
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/\s+/g, ' ');

      const matchedThemes = new Set();

      HOME_THEME_ALIASES.forEach(theme => {
        const hasMatch = theme.keys.some(k => {
          if (k.includes(' ')) {
            return fullCombined.includes(k);
          }
          const regex = new RegExp('(?:^|\\s)' + k + '(?:$|\\s)', 'i');
          return regex.test(fullCombined);
        });
        if (hasMatch) {
          matchedThemes.add(theme.label);
        }
      });

      return [...matchedThemes];
    }

    function getHomeItemTasteScore(item) {
      const themes = getHomeThemesForItem(item);
      if (!themes.length) return 0;
      const profile = readTasteProfile();
      return themes.reduce((score, theme, index) => {
        const weight = Number(profile.genres?.[theme] || 0);
        return score + weight * (index === 0 ? 1 : 0.72);
      }, 0);
    }

    function parseCastList(castVal) {
      if (!castVal) return [];
      if (Array.isArray(castVal)) {
        return castVal.map(c => String(c).trim()).filter(c => c && c !== 'N/A' && c !== '-' && c !== 'None');
      }
      if (typeof castVal === 'string') {
        return castVal
          .split(/[,;\/]+/)
          .map(c => c.trim())
          .filter(c => c && c !== 'N/A' && c !== '-' && c !== 'None');
      }
      return [];
    }

    function parseDirectorList(dirVal) {
      if (!dirVal) return [];
      if (Array.isArray(dirVal)) {
        return dirVal.map(d => String(d).trim()).filter(d => d && d !== 'N/A' && d !== '-' && d !== 'None');
      }
      if (typeof dirVal === 'string') {
        return dirVal
          .split(/[,;\/]+/)
          .map(d => d.trim())
          .filter(d => d && d !== 'N/A' && d !== '-' && d !== 'None');
      }
      return [];
    }

    function getItemSearchableText(item) {
      if (!item) return '';
      if (typeof item._searchableText === 'string') return item._searchableText;

      const parts = [];

      // 1. Títulos e identificadores limpos
      if (item.name) parts.push(item.name);
      if (item.title && item.title !== item.name) parts.push(item.title);
      if (item.cleanKey) parts.push(item.cleanKey);
      if (item.originalName) parts.push(item.originalName);

      // 2. Versões agrupadas (Dublado, Legendado, 4K)
      if (Array.isArray(item.versions)) {
        item.versions.forEach(v => {
          const vName = v.item?.name || v.item?.title;
          if (vName) parts.push(vName);
          if (v.versionInfo?.label) parts.push(v.versionInfo.label);
        });
      }

      // 3. Gênero textual direto e categorias
      if (item.genre) parts.push(item.genre);
      if (item.category_name) parts.push(item.category_name);
      if (item.categoryLabel) parts.push(item.categoryLabel);

      // 4. Categorias do catálogo Xtream
      const isMovie = item.type === 'movie' || item._searchType === 'movie' || item.stream_id || (!item.series_id && currentMode === 'movies');
      const catList = isMovie ? movieCategories : seriesCategories;
      if (Array.isArray(catList) && catList.length > 0) {
        const cId = String(item.category_id || '');
        if (cId) {
          const found = catList.find(c => String(c.category_id) === cId);
          if (found && found.category_name) parts.push(found.category_name);
        }
        if (Array.isArray(item.category_ids)) {
          item.category_ids.forEach(id => {
            const found = catList.find(c => String(c.category_id) === String(id));
            if (found && found.category_name) parts.push(found.category_name);
          });
        }
      }

      // 5. Temas mapeados pelo sistema Home e seus sinônimos (ex: Ação -> acao, action, aventura)
      const themes = getHomeThemesForItem(item);
      if (Array.isArray(themes)) {
        themes.forEach(t => {
          parts.push(t);
          const aliasObj = HOME_THEME_ALIASES.find(a => a.label === t);
          if (aliasObj && Array.isArray(aliasObj.keys)) {
            parts.push(...aliasObj.keys);
          }
        });
      }

      // 6. Elenco e Atores
      const rawCast = item.cast || item.actors || item.primaryItem?.cast || item.primaryItem?.actors;
      const castList = parseCastList(rawCast);
      if (castList.length) parts.push(...castList);

      // 7. Diretor
      const rawDir = item.director || item.primaryItem?.director;
      const dirList = parseDirectorList(rawDir);
      if (dirList.length) parts.push(...dirList);

      // 8. Metadados do cache de enriquecimento IMDb (Cinemeta)
      const imdbId = item.imdbId || item.imdb_id || item.primaryItem?.imdbId || item.primaryItem?.imdb_id;
      if (imdbId) {
        const cached = readHomeRatingCache()[String(imdbId)];
        if (cached) {
          if (Array.isArray(cached.genres)) parts.push(...cached.genres);
          if (Array.isArray(cached.cast)) parts.push(...cached.cast);
          if (Array.isArray(cached.director)) parts.push(...cached.director);
        }
      }

      // 9. Tags contextuais dinâmicas
      if (Array.isArray(item._contextualKeywords)) {
        parts.push(...item._contextualKeywords);
      }

      // 10. Ano
      if (item.year) parts.push(String(item.year));

      // 11. Esportes e Canais Ao Vivo
      if (item.channelNumber) {
        parts.push(String(item.channelNumber));
        parts.push(`ch${item.channelNumber}`);
        parts.push(`ch ${item.channelNumber}`);
      }
      if (item.channelNumberFormatted) {
        parts.push(item.channelNumberFormatted);
        parts.push(item.channelNumberFormatted.replace(' ', ''));
      }
      if (item.league) parts.push(item.league);
      if (item.homeTeam) parts.push(item.homeTeam);
      if (item.awayTeam) parts.push(item.awayTeam);
      if (item.sport) parts.push(item.sport);
      if (item.competition) parts.push(item.competition);
      if (item.tournament) parts.push(item.tournament);
      if (item.matchTime) parts.push(item.matchTime);
      if (item.nowTitle) parts.push(item.nowTitle);
      if (item.subCategory) parts.push(item.subCategory);
      if (item.rawCategory) parts.push(item.rawCategory);
      if (Array.isArray(item.nextProgrammes)) {
        item.nextProgrammes.forEach(p => {
          if (p?.t) parts.push(p.t);
        });
      }
      if (Array.isArray(item.fallbacks)) {
        item.fallbacks.forEach(fb => {
          if (fb?.name) parts.push(fb.name);
        });
      }
      if (item.channelSlug) parts.push(item.channelSlug);
      if (item.badge) parts.push(item.badge);

      const catKey = item.categoryKey || item.category_id || '';
      if (catKey) {
        LIVE_THEME_ALIASES.forEach(alias => {
          if (alias.categories.includes(catKey) || (item.isLiveMatch && alias.categories.includes('JOGOS'))) {
            parts.push(...alias.keys);
          }
        });
      }

      const searchable = normalizeSearch(parts.join(' '));
      item._searchableText = searchable;
      return searchable;
    }

    function matchesSearchQuery(item, query) {
      if (!query) return true;
      const q = normalizeSearch(query);
      if (!q) return true;
      const text = getItemSearchableText(item);
      if (!text) return false;
      if (text.includes(q)) return true;

      const tokens = q.split(' ').filter(Boolean);
      if (tokens.length > 1) {
        return tokens.every(token => text.includes(token));
      }
      return false;
    }

    // Amostrador caótico estratificado: extrai uma amostra viva, orgânica e variada do catálogo de 30 mil títulos,
    // sem monopolizar o trilho apenas com anos recentes (2026/2025) e valorizando novidades do acervo e clássicos.
    function sampleChaoticRailItems(candidates, limit = HOME_RAIL_ITEM_LIMIT, options = {}) {
      if (!Array.isArray(candidates) || !candidates.length) return [];
      if (candidates.length <= limit) {
        return shuffleArray(candidates);
      }

      // 1. Identificar novidades adicionadas recentemente ao catálogo (pela data de adição, independente do ano de produção)
      const sortedByTime = candidates.slice().sort((a, b) => (b.added || 0) - (a.added || 0));
      const topAddedCount = Math.max(2, Math.floor(candidates.length * 0.20));
      const addedKeys = new Set(sortedByTime.slice(0, topAddedCount).map(x => (x.type || '') + ':' + (x.id || '')));

      // 2. Classificar candidatos em 4 faixas equilibradas:
      const poolAdded = [];
      const poolRecent = [];
      const poolModern = [];
      const poolClassic = [];

      candidates.forEach(item => {
        const key = (item.type || '') + ':' + (item.id || '');
        const y = getItemYear(item);
        if (addedKeys.has(key)) {
          poolAdded.push(item);
        } else if (y >= 2023) {
          poolRecent.push(item);
        } else if (y >= 2010) {
          poolModern.push(item);
        } else {
          poolClassic.push(item);
        }
      });

      // Cotas equilibradas para compor um trilho rico em diversidade de épocas
      const quotaAdded = Math.max(1, Math.round(limit * 0.25));
      const quotaRecent = Math.max(1, Math.round(limit * 0.30));
      const quotaModern = Math.max(1, Math.round(limit * 0.25));
      const quotaClassic = Math.max(1, limit - quotaAdded - quotaRecent - quotaModern);

      const pickedAdded = pickRandomSample(poolAdded, quotaAdded);
      const pickedRecent = pickRandomSample(poolRecent, quotaRecent);
      const pickedModern = pickRandomSample(poolModern, quotaModern);
      const pickedClassic = pickRandomSample(poolClassic, quotaClassic);

      let combined = [...pickedAdded, ...pickedRecent, ...pickedModern, ...pickedClassic];
      const usedKeys = new Set(combined.map(x => (x.type || '') + ':' + (x.id || '')));

      if (combined.length < limit) {
        const leftover = candidates.filter(x => !usedKeys.has((x.type || '') + ':' + (x.id || '')));
        const extra = pickRandomSample(leftover, limit - combined.length);
        combined.push(...extra);
      }

      // Ordenação orgânica e viva: embaralha para quebrar a ordem cronológica rígida
      // e apresentar uma mistura rica e surpreendente de épocas ao usuário ao longo do trilho
      return shuffleArray(combined).slice(0, limit);
    }

    function getHomeRecommendationMix(items) {
      if (!items.length) return [];

      const profile = readTasteProfile();
      const topThemes = getTasteTopThemes(4);
      const normalizedHistory = getNormalizedRemoteHistory();
      const hasEnoughHistory = (Array.isArray(profile.recent) && profile.recent.length >= 3) ||
                               (Array.isArray(normalizedHistory) && normalizedHistory.length >= 3);

      if (!hasEnoughHistory || !topThemes.length) return [];

      const watchedKeys = new Set([
        ...getWatchedIds('movies').map(id => 'movie:' + id),
        ...getWatchedIds('series').map(id => 'series:' + id)
      ]);

      const ranked = items
        .map(item => {
          const themes = getHomeThemesForItem(item);
          const taste = themes.reduce((sum, theme) => sum + Number(profile.genres?.[theme] || 0), 0);
          const matchesTop = themes.some(theme => topThemes.includes(theme));
          const rating = getHomeRatingInfo(item).value;
          return {
            item,
            taste: taste + (rating > 0 ? rating * 0.18 : 0),
            matchesTop
          };
        })
        .filter(entry => entry.taste > 0 && !watchedKeys.has(entry.item.type + ':' + entry.item.id))
        .sort((a, b) => b.taste - a.taste || compareByReleaseYear(b.item, a.item));

      // Aloca ~75% do trilho para títulos que combinam com os top gêneros do usuário com rotação viva
      const tasteQuota = Math.round(HOME_RAIL_ITEM_LIMIT * 0.75);
      const matchingEntries = ranked.filter(entry => entry.matchesTop);
      const fallbackEntries = ranked.filter(entry => !entry.matchesTop);
      const combinedTastePool = [...matchingEntries, ...fallbackEntries].slice(0, 80).map(e => e.item);
      const sampledTasteItems = sampleChaoticRailItems(combinedTastePool, tasteQuota);

      const used = new Set(sampledTasteItems.map(item => (item.type || '') + ':' + (item.id || '')));

      // Mantém descoberta (15% a 25% do trilho): títulos aclamados fora da zona de conforto com rotação viva
      const discoveryQuota = Math.max(3, Math.min(6, HOME_RAIL_ITEM_LIMIT - sampledTasteItems.length));
      const discoveryCandidates = items
        .filter(item =>
          !used.has((item.type || '') + ':' + (item.id || '')) &&
          !watchedKeys.has((item.type || '') + ':' + (item.id || ''))
        )
        .sort((a, b) => {
          const rA = getHomeRatingInfo(a).value;
          const rB = getHomeRatingInfo(b).value;
          if (rB !== rA) return rB - rA;
          return compareByReleaseYear(a, b);
        })
        .filter(item => !getHomeThemesForItem(item).some(theme => topThemes.includes(theme)));

      const discoveryPool = discoveryCandidates.slice(0, Math.min(60, discoveryCandidates.length));
      const sampledDiscoveryItems = sampleChaoticRailItems(discoveryPool, discoveryQuota);

      // Intercalar suavemente as descobertas ao longo do trilho para criar uma experiência equilibrada e orgânica
      const combinedMix = [];
      const discoveryIndices = [3, 7, 11, 15, 19, 23];
      let ti = 0;
      let di = 0;
      for (let i = 0; i < HOME_RAIL_ITEM_LIMIT; i++) {
        if (discoveryIndices.includes(i) && di < sampledDiscoveryItems.length) {
          combinedMix.push(sampledDiscoveryItems[di++]);
        } else if (ti < sampledTasteItems.length) {
          combinedMix.push(sampledTasteItems[ti++]);
        } else if (di < sampledDiscoveryItems.length) {
          combinedMix.push(sampledDiscoveryItems[di++]);
        }
      }
      return combinedMix.slice(0, HOME_RAIL_ITEM_LIMIT);
    }

    function buildHomeCatalogRails(type) {
      const items = getHomeCatalogItems(type);
      if (!items.length) return [];

      const typeLabel = type === 'series' ? 'Séries' : 'Filmes';

      // 1. Trilho de Novidades: separe sempre 35% do conteúdo voltado para novidades do catálogo,
      //    eles não precisam ser o primeiro a aparecer, devem estar distribuídos ao longo do trilho.
      const railLimit = HOME_RAIL_ITEM_LIMIT; // 24
      const catalogNewsQuota = Math.round(railLimit * 0.35); // 8 itens (35%)
      const generalQuota = railLimit - catalogNewsQuota; // 16 itens (65%)

      // A. Novidades do catálogo: ordenadas por data de adição/modificação no catálogo
      const sortedByAdded = items.slice().sort((a, b) => (b.added || 0) - (a.added || 0));
      // Amostragem entre os top 45 itens mais recentemente adicionados para rotação constante
      const topAddedPool = sortedByAdded.slice(0, Math.min(45, sortedByAdded.length));
      const catalogNewsItems = pickRandomSample(topAddedPool, catalogNewsQuota);
      const usedInNews = new Set(catalogNewsItems.map(x => (x.type || '') + ':' + (x.id || '')));

      // B. Itens gerais (65%): lançamentos recentes e destaques de diversas épocas
      const remainingPool = items.filter(x => !usedInNews.has((x.type || '') + ':' + (x.id || '')));
      const generalItems = sampleChaoticRailItems(remainingPool, generalQuota);

      // C. Distribuir os 35% de novidades do catálogo ao longo do trilho de 24 itens
      // (espaçados homogeneamente a cada 3 cards: índices 1, 4, 7, 10, 13, 16, 19, 22)
      const distributedLatest = new Array(railLimit);
      const newsIndices = [1, 4, 7, 10, 13, 16, 19, 22];

      let ni = 0;
      let gi = 0;
      for (let i = 0; i < railLimit; i++) {
        if (newsIndices.includes(i) && ni < catalogNewsItems.length) {
          distributedLatest[i] = catalogNewsItems[ni++];
        } else if (gi < generalItems.length) {
          distributedLatest[i] = generalItems[gi++];
        } else if (ni < catalogNewsItems.length) {
          distributedLatest[i] = catalogNewsItems[ni++];
        }
      }
      const latestItems = distributedLatest.filter(Boolean);

      const rails = [{
        key: 'latest',
        title: 'Novidades em ' + typeLabel,
        items: latestItems
      }];

      // 2. Mais bem avaliados: amostragem caótica entre os melhores avaliados do catálogo (todas as épocas)
      const ratedCandidates = items
        .filter(item => getHomeRatingInfo(item).value >= 6.0)
        .sort((a, b) => getHomeRatingInfo(b).value - getHomeRatingInfo(a).value);

      if (ratedCandidates.length >= HOME_THEME_MIN_ITEMS) {
        const topRatedPool = ratedCandidates.slice(0, Math.min(150, ratedCandidates.length));
        const ratedItems = sampleChaoticRailItems(topRatedPool, HOME_RAIL_ITEM_LIMIT);
        rails.push({
          key: 'top-rated',
          title: '⭐ Mais bem avaliados • ' + typeLabel,
          items: ratedItems
        });
      }

      // 3. Mapear todos os itens por tema para não re-filtrar 15.000 itens repetidamente
      const themeCounts = new Map();
      const themeItemsMap = new Map();

      items.forEach(item => {
        getHomeThemesForItem(item).forEach(theme => {
          themeCounts.set(theme, (themeCounts.get(theme) || 0) + 1);
          if (!themeItemsMap.has(theme)) themeItemsMap.set(theme, []);
          themeItemsMap.get(theme).push(item);
        });
      });

      const tasteWeights = readTasteProfile().genres || {};
      const eligibleThemes = [...themeCounts.entries()]
        .filter(([, count]) => count >= HOME_THEME_MIN_ITEMS);

      // Temas com afinidade do usuário
      const preferredThemes = eligibleThemes
        .filter(([t]) => Number(tasteWeights[t] || 0) > 0)
        .sort((a, b) => Number(tasteWeights[b[0]] || 0) - Number(tasteWeights[a[0]] || 0) || b[1] - a[1])
        .map(([t]) => t);

      // Temas para exploração/descoberta (fora da zona de conforto)
      const discoveryThemes = eligibleThemes
        .filter(([t]) => !tasteWeights[t] || Number(tasteWeights[t]) === 0)
        .sort((a, b) => b[1] - a[1])
        .map(([t]) => t);

      const discoveryRailCount = Math.max(1, Math.min(2, Math.round(HOME_THEME_COUNT * 0.25)));
      const preferredRailCount = Math.max(1, HOME_THEME_COUNT - discoveryRailCount);

      const chosenThemes = [];

      // Rotação dinâmica de temas: os top 1 ou 2 gêneros preferidos são sempre mantidos como âncoras
      const topFavoriteAnchors = preferredThemes.slice(0, 2);
      topFavoriteAnchors.forEach(t => chosenThemes.push({ theme: t, isDiscovery: false }));

      // Para as vagas preferidas restantes, rotaciona aleatoriamente entre outros gêneros curtidos
      const remainingPreferred = preferredThemes.slice(2);
      if (remainingPreferred.length > 0) {
        const shuffledPreferred = [...remainingPreferred].sort(() => Math.random() - 0.5);
        shuffledPreferred.slice(0, preferredRailCount - chosenThemes.length).forEach(t => {
          chosenThemes.push({ theme: t, isDiscovery: false });
        });
      }

      // Rotação dinâmica nos temas de descoberta (gêneros diferentes a cada visita)
      if (discoveryThemes.length > 0) {
        const shuffledDiscovery = [...discoveryThemes].sort(() => Math.random() - 0.5);
        shuffledDiscovery.slice(0, discoveryRailCount).forEach(t => {
          chosenThemes.push({ theme: t, isDiscovery: true });
        });
      }

      // Completar se faltar trilhos para HOME_THEME_COUNT
      if (chosenThemes.length < HOME_THEME_COUNT) {
        const chosenSet = new Set(chosenThemes.map(x => x.theme));
        eligibleThemes.forEach(([t]) => {
          if (!chosenSet.has(t) && chosenThemes.length < HOME_THEME_COUNT) {
            chosenThemes.push({ theme: t, isDiscovery: !tasteWeights[t] });
            chosenSet.add(t);
          }
        });
      }

      // Para cada tema escolhido, aplicar amostragem caótica estratificada sobre TODOS os títulos do catálogo
      chosenThemes.forEach(({ theme, isDiscovery }) => {
        const allThemedItems = themeItemsMap.get(theme) || [];
        if (allThemedItems.length < HOME_THEME_MIN_ITEMS) return;

        const themedItems = sampleChaoticRailItems(allThemedItems, HOME_RAIL_ITEM_LIMIT, {
          anchorCount: isDiscovery ? 1 : 2
        });

        if (themedItems.length >= HOME_THEME_MIN_ITEMS) {
          const title = isDiscovery
            ? 'Descubra no catálogo • ' + theme
            : (Number(tasteWeights[theme] || 0) > 0 ? 'Porque você assiste • ' + theme : theme + ' • ' + typeLabel);
          rails.push({
            key: theme,
            title,
            isDiscovery,
            items: themedItems
          });
        }
      });

      return rails;
    }

    function renderHomeTitleCard(item) {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'home-title-card';
      card.dataset.itemKey = (item.type || '') + ':' + (item.id || '');
      card._homeItem = item;

      const ratingInfo = getHomeRatingInfo(item);
      const meta = [
        item.year,
        ratingInfo.value > 0 ? '★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' ' + ratingInfo.source : '') : ''
      ].filter(Boolean).join(' • ');

      card.innerHTML =
        '<div class="home-title-poster">' +
          '<img src="' + escapeHtml(item.poster) + '" alt="" loading="lazy" decoding="async" data-hide-on-error draggable="false">' +
          '<span class="home-title-type">' + (item.type === 'series' ? 'SÉRIE' : 'FILME') + '</span>' +
          (ratingInfo.value > 0
            ? '<span class="home-title-rating">★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' <em>' + escapeHtml(ratingInfo.source) + '</em>' : '') + '</span>'
            : '') +
        '</div>' +
        '<strong title="' + escapeHtml(item.title) + '">' + escapeHtml(item.title) + '</strong>' +
        '<small>' + escapeHtml(meta || (item.type === 'series' ? 'Série' : 'Filme')) + '</small>';

      card.addEventListener('click', () => {
        if (item.type === 'movie') onMovieCardClick(item.item);
        else openSeriesPage(item.item);
      });

      return card;
    }

    function updateHomeTitleCard(card, item) {
      if (!card || !item) return;
      card._homeItem = item;
      const ratingInfo = getHomeRatingInfo(item);
      const meta = [
        item.year,
        ratingInfo.value > 0 ? '★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' ' + ratingInfo.source : '') : ''
      ].filter(Boolean).join(' • ');

      const posterImg = card.querySelector('.home-title-poster img');
      if (posterImg && item.poster && posterImg.getAttribute('src') !== item.poster) {
        posterImg.src = item.poster;
      }

      const ratingEl = card.querySelector('.home-title-rating');
      if (ratingInfo.value > 0) {
        const ratingHtml = '★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' <em>' + escapeHtml(ratingInfo.source) + '</em>' : '');
        if (ratingEl) {
          if (ratingEl.innerHTML !== ratingHtml) ratingEl.innerHTML = ratingHtml;
        } else {
          const posterWrap = card.querySelector('.home-title-poster');
          if (posterWrap) {
            const span = document.createElement('span');
            span.className = 'home-title-rating';
            span.innerHTML = ratingHtml;
            posterWrap.appendChild(span);
          }
        }
      } else if (ratingEl) {
        ratingEl.remove();
      }

      const small = card.querySelector('small');
      const expectedMeta = meta || (item.type === 'series' ? 'Série' : 'Filme');
      if (small && small.textContent !== expectedMeta) {
        small.textContent = expectedMeta;
      }
    }

    function filterNonSequentialItems(candidates, previousRailKeys) {
      if (!previousRailKeys || !previousRailKeys.size) return candidates;
      const clean = [];
      const deferred = [];
      (Array.isArray(candidates) ? candidates : []).forEach(item => {
        const key = getHomeDisplayKey(item);
        if (key && previousRailKeys.has(key)) deferred.push(item);
        else clean.push(item);
      });
      if (clean.length >= HOME_THEME_MIN_ITEMS) return clean;
      return [...clean, ...deferred];
    }

    function getRailScrollStep(scroller) {
      const card = scroller?.querySelector('.home-title-card, .home-watched-card');
      if (card) {
        const cardWidth = card.offsetWidth || 180;
        const gap = 13;
        const stride = cardWidth + gap;
        const visibleCards = Math.max(1, Math.floor(scroller.clientWidth / stride));
        const count = Math.max(1, visibleCards > 1 ? visibleCards - 1 : 1);
        return count * stride;
      }
      return Math.max(260, Math.round((scroller?.clientWidth || 800) * 0.72));
    }

    function enableHomeDragToScroll(scroller) {
      if (!scroller || scroller._hasDragScroll) return;
      scroller._hasDragScroll = true;

      scroller.addEventListener('dragstart', e => e.preventDefault());

      let isDown = false;
      let startX = 0;
      let startY = 0;
      let scrollStart = 0;
      let isDragging = false;
      let activePointerId = null;
      let momentumRaf = null;
      const history = [];

      const stopMomentum = () => {
        if (momentumRaf !== null) {
          cancelAnimationFrame(momentumRaf);
          momentumRaf = null;
        }
      };

      scroller.addEventListener('pointerdown', e => {
        if (e.pointerType !== 'mouse' || e.button !== 0) return;
        if (e.target.closest('button.home-watched-menu, .home-watched-menu-panel, .home-rail-arrow, .home-rail-more')) return;

        stopMomentum();
        isDown = true;
        isDragging = false;
        activePointerId = e.pointerId;
        startX = e.clientX;
        startY = e.clientY;
        scrollStart = scroller.scrollLeft;

        history.length = 0;
        history.push({ x: e.clientX, time: performance.now() });

        if (window.getSelection) window.getSelection().removeAllRanges();
        if (document.activeElement && typeof document.activeElement.blur === 'function') {
          document.activeElement.blur();
        }
      });

      scroller.addEventListener('pointermove', e => {
        if (!isDown || e.pointerId !== activePointerId) return;

        const dx = e.clientX - startX;
        const dy = e.clientY - startY;

        if (!isDragging) {
          if (Math.abs(dx) > 6 && Math.abs(dx) > Math.abs(dy)) {
            isDragging = true;
            try { scroller.setPointerCapture(e.pointerId); } catch (_) {}
            scroller.classList.add('is-dragging');
            if (window.getSelection) window.getSelection().removeAllRanges();
            if (document.activeElement && typeof document.activeElement.blur === 'function') {
              document.activeElement.blur();
            }
          } else if (Math.abs(dy) > 10) {
            isDown = false;
            return;
          }
        }

        if (isDragging) {
          if (e.cancelable) e.preventDefault();
          if (window.getSelection) window.getSelection().removeAllRanges();

          const now = performance.now();
          history.push({ x: e.clientX, time: now });
          while (history.length > 5 && now - history[0].time > 100) {
            history.shift();
          }

          scroller.scrollLeft = scrollStart - dx;
        }
      });

      const onPointerEnd = e => {
        if (!isDown || (activePointerId !== null && e.pointerId !== activePointerId)) return;
        isDown = false;
        const pid = activePointerId;
        activePointerId = null;

        if (pid !== null) {
          try { scroller.releasePointerCapture(pid); } catch (_) {}
        }

        if (isDragging) {
          isDragging = false;
          scroller.classList.remove('is-dragging');
          if (document.activeElement && typeof document.activeElement.blur === 'function') {
            document.activeElement.blur();
          }

          const suppressClick = clickEv => {
            clickEv.preventDefault();
            clickEv.stopPropagation();
          };
          window.addEventListener('click', suppressClick, { capture: true, once: true });
          setTimeout(() => window.removeEventListener('click', suppressClick, { capture: true }), 100);

          const now = performance.now();
          const recent = history.filter(p => now - p.time <= 90);
          let velocity = 0;
          if (recent.length >= 2) {
            const first = recent[0];
            const last = recent[recent.length - 1];
            const dt = last.time - first.time;
            if (dt > 12) {
              velocity = (last.x - first.x) / dt;
            }
          }

          if (Math.abs(velocity) > 0.16) {
            let v = velocity * 16;
            v = Math.max(-50, Math.min(50, v));
            const friction = 0.958;
            const maxScroll = Math.max(0, scroller.scrollWidth - scroller.clientWidth);

            const applyMomentum = () => {
              if (Math.abs(v) < 0.25) {
                stopMomentum();
                return;
              }
              scroller.scrollLeft -= v;
              v *= friction;

              if (scroller.scrollLeft <= 0 || scroller.scrollLeft >= maxScroll) {
                stopMomentum();
                return;
              }
              momentumRaf = requestAnimationFrame(applyMomentum);
            };
            momentumRaf = requestAnimationFrame(applyMomentum);
          }
        } else {
          scroller.classList.remove('is-dragging');
        }
      };

      scroller.addEventListener('pointerup', onPointerEnd);
      scroller.addEventListener('pointercancel', onPointerEnd);
    }

    function updateHomeRailControls(scroller, prevBtn, nextBtn) {
      if (!scroller || !prevBtn || !nextBtn) return;
      const maxScrollLeft = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
      const scrollLeft = Math.max(0, Math.min(scroller.scrollLeft, maxScrollLeft));
      const canScrollLeft = maxScrollLeft > 0 && scrollLeft > 6;
      const canScrollRight = maxScrollLeft > 0 && scrollLeft < maxScrollLeft - 6;

      prevBtn.disabled = !canScrollLeft;
      nextBtn.disabled = !canScrollRight;
      prevBtn.classList.toggle('is-hidden', !canScrollLeft);
      nextBtn.classList.toggle('is-hidden', !canScrollRight);

      scroller.classList.toggle('has-scroll-left', canScrollLeft);
      scroller.classList.toggle('is-at-end', !canScrollRight && canScrollLeft);
    }

    function setupHomeRailControls(scroller) {
      const nav = document.createElement('div');
      nav.className = 'home-rail-nav';
      nav.innerHTML =
        '<button type="button" class="home-rail-arrow home-rail-prev is-hidden" aria-label="Itens anteriores">‹</button>' +
        '<button type="button" class="home-rail-arrow home-rail-next is-hidden" aria-label="Mais conteúdo">›</button>';

      const prevBtn = nav.querySelector('.home-rail-prev');
      const nextBtn = nav.querySelector('.home-rail-next');

      prevBtn.addEventListener('click', () => {
        scroller.scrollBy({ left: -getRailScrollStep(scroller), behavior: 'smooth' });
      });
      nextBtn.addEventListener('click', () => {
        scroller.scrollBy({ left: getRailScrollStep(scroller), behavior: 'smooth' });
      });
      scroller.addEventListener('scroll', () => updateHomeRailControls(scroller, prevBtn, nextBtn), { passive: true });

      enableHomeDragToScroll(scroller);

      updateHomeRailControls(scroller, prevBtn, nextBtn);
      requestAnimationFrame(() => updateHomeRailControls(scroller, prevBtn, nextBtn));
      return nav;
    }

    function renderHomeRailBlock(rail, type, savedScrolls) {
      const withinLimit = getHomeItemsWithinDisplayLimit(rail.items);
      const visibleItems = filterNonSequentialItems(withinLimit, lastRenderedRailItemKeys);
      const minItems = rail.key === 'latest' ? 1 : HOME_THEME_MIN_ITEMS;
      if (visibleItems.length < minItems) return null;

      const block = document.createElement('section');
      block.className = 'home-theme-rail';

      const heading = document.createElement('div');
      heading.className = 'home-theme-heading';
      const tasteHint = rail.isDiscovery
        ? '<small>Novos horizontes • Títulos aclamados fora da sua zona de conforto</small>'
        : (rail.key === 'for-you' ? '<small>Baseado no seu histórico • com espaço para descoberta</small>' : '');
      heading.innerHTML =
        '<div class="home-theme-title-wrap">' +
          '<h3>' + escapeHtml(rail.title) + '</h3>' +
          tasteHint +
        '</div>' +
        '<span>' + visibleItems.length + ' títulos</span>';
      block.appendChild(heading);

      const scroller = document.createElement('div');
      scroller.className = 'home-theme-scroller';
      const uniqueKey = type + ':' + rail.key;
      scroller.dataset.railKey = uniqueKey;
      visibleItems.forEach(item => scroller.appendChild(renderHomeTitleCard(item)));

      registerHomeDisplayItems(visibleItems);
      lastRenderedRailItemKeys = new Set(visibleItems.map(item => getHomeDisplayKey(item)).filter(Boolean));

      const prevScroll = savedScrolls.get(uniqueKey);
      if (prevScroll > 0) {
        scroller.scrollLeft = prevScroll;
      }

      block.appendChild(scroller);
      block.appendChild(setupHomeRailControls(scroller));
      return block;
    }

    function renderOrUpdateHomeRailBlock(existingBlock, rail, type, savedScrolls) {
      if (!existingBlock) return renderHomeRailBlock(rail, type, savedScrolls);

      const withinLimit = getHomeItemsWithinDisplayLimit(rail.items);
      const visibleItems = filterNonSequentialItems(withinLimit, lastRenderedRailItemKeys);
      const minItems = rail.key === 'latest' ? 1 : HOME_THEME_MIN_ITEMS;
      if (visibleItems.length < minItems) {
        existingBlock.style.display = 'none';
        return null;
      }
      existingBlock.style.display = '';

      const scroller = existingBlock.querySelector('.home-theme-scroller');
      if (!scroller) return renderHomeRailBlock(rail, type, savedScrolls);

      const currentCards = Array.from(scroller.querySelectorAll('.home-title-card'));
      const isSameCards = currentCards.length === visibleItems.length &&
        currentCards.every((card, i) => card.dataset.itemKey === ((visibleItems[i].type || '') + ':' + (visibleItems[i].id || '')));

      if (isSameCards) {
        visibleItems.forEach((item, i) => updateHomeTitleCard(currentCards[i], item));
        registerHomeDisplayItems(visibleItems);
        lastRenderedRailItemKeys = new Set(visibleItems.map(item => getHomeDisplayKey(item)).filter(Boolean));

        const countSpan = existingBlock.querySelector('.home-theme-heading > span');
        if (countSpan && countSpan.textContent !== visibleItems.length + ' títulos') {
          countSpan.textContent = visibleItems.length + ' títulos';
        }
        return existingBlock;
      }

      // Se os cards mudaram (ex: novos itens), atualiza o scroller sem destruir o container do trilho
      scroller.innerHTML = '';
      visibleItems.forEach(item => scroller.appendChild(renderHomeTitleCard(item)));
      registerHomeDisplayItems(visibleItems);
      lastRenderedRailItemKeys = new Set(visibleItems.map(item => getHomeDisplayKey(item)).filter(Boolean));

      const countSpan = existingBlock.querySelector('.home-theme-heading > span');
      if (countSpan) countSpan.textContent = visibleItems.length + ' títulos';

      const uniqueKey = type + ':' + rail.key;
      const prevScroll = savedScrolls.get(uniqueKey);
      if (prevScroll > 0) {
        scroller.scrollLeft = prevScroll;
      }

      const prevBtn = existingBlock.querySelector('.home-rail-prev');
      const nextBtn = existingBlock.querySelector('.home-rail-next');
      if (prevBtn && nextBtn) updateHomeRailControls(scroller, prevBtn, nextBtn);

      return existingBlock;
    }

    function renderHomeCatalogRails(type, railElement, sectionElement, savedScrolls = new Map()) {
      if (!railElement || !sectionElement) return;

      railElement.querySelectorAll('.home-theme-scroller').forEach(s => {
        if (s.dataset.railKey) {
          const k = s.dataset.railKey.startsWith(type + ':') ? s.dataset.railKey : (type + ':' + s.dataset.railKey);
          savedScrolls.set(k, s.scrollLeft);
        }
      });

      const head = sectionElement.querySelector('.home-content-section-head');
      if (head) head.style.display = '';

      const rails = buildHomeCatalogRails(type);
      if (!rails.length) {
        sectionElement.style.display = 'none';
        return;
      }

      const existingBlocks = Array.from(railElement.querySelectorAll(':scope > .home-theme-rail'));
      const canUpdateInPlace = existingBlocks.length === rails.length &&
        existingBlocks.every((block, idx) => {
          const scroller = block.querySelector('.home-theme-scroller');
          return scroller && scroller.dataset.railKey === (type + ':' + rails[idx].key);
        });

      if (canUpdateInPlace) {
        let renderedRails = 0;
        rails.forEach((rail, idx) => {
          const block = renderOrUpdateHomeRailBlock(existingBlocks[idx], rail, type, savedScrolls);
          if (block) renderedRails++;
        });
        sectionElement.style.display = renderedRails ? '' : 'none';
        return;
      }

      railElement.innerHTML = '';
      let renderedRails = 0;
      rails.forEach(rail => {
        const block = renderHomeRailBlock(rail, type, savedScrolls);
        if (block) {
          railElement.appendChild(block);
          renderedRails++;
        }
      });

      sectionElement.style.display = renderedRails ? '' : 'none';
    }

    function renderHomeInterleavedRails(container, activeSection, otherSection, savedScrolls = new Map(), pref = null) {
      if (!container || !activeSection) return;

      container.querySelectorAll('.home-theme-scroller').forEach(s => {
        if (s.dataset.railKey) savedScrolls.set(s.dataset.railKey, s.scrollLeft);
      });

      if (otherSection) {
        otherSection.style.display = 'none';
        const otherRails = otherSection.querySelector('.home-theme-rails');
        if (otherRails) otherRails.innerHTML = '';
      }

      // Ocultar cabeçalho individual para transformar em feed unificado e contínuo
      const head = activeSection.querySelector('.home-content-section-head');
      if (head) head.style.display = 'none';

      const movieRails = buildHomeCatalogRails('movie').map(r => ({ ...r, _railType: 'movie' }));
      const seriesRails = buildHomeCatalogRails('series').map(r => ({ ...r, _railType: 'series' }));

      if (!movieRails.length && !seriesRails.length) {
        activeSection.style.display = 'none';
        return;
      }

      // Intercalar trilhos de filmes e séries:
      // Se o usuário tiver preferência maior ou igual por filmes, começa com filmes; senão séries.
      const firstList = (pref && pref.movieRatio >= pref.seriesRatio) ? movieRails : seriesRails;
      const secondList = (firstList === movieRails) ? seriesRails : movieRails;

      const interleaved = [];
      const maxLen = Math.max(firstList.length, secondList.length);
      for (let i = 0; i < maxLen; i++) {
        if (i < firstList.length) interleaved.push(firstList[i]);
        if (i < secondList.length) interleaved.push(secondList[i]);
      }

      const existingBlocks = Array.from(container.querySelectorAll(':scope > .home-theme-rail'));
      const canUpdateInPlace = existingBlocks.length === interleaved.length &&
        existingBlocks.every((block, idx) => {
          const scroller = block.querySelector('.home-theme-scroller');
          return scroller && scroller.dataset.railKey === (interleaved[idx]._railType + ':' + interleaved[idx].key);
        });

      if (canUpdateInPlace) {
        let renderedRails = 0;
        interleaved.forEach((rail, idx) => {
          const block = renderOrUpdateHomeRailBlock(existingBlocks[idx], rail, rail._railType, savedScrolls);
          if (block) renderedRails++;
        });
        activeSection.style.display = renderedRails ? '' : 'none';
        return;
      }

      container.innerHTML = '';
      let renderedRails = 0;
      interleaved.forEach(rail => {
        const block = renderHomeRailBlock(rail, rail._railType, savedScrolls);
        if (block) {
          container.appendChild(block);
          renderedRails++;
        }
      });

      activeSection.style.display = renderedRails ? '' : 'none';
    }

    function setupSingleHomeRailArrow(scroller, nextBtn, prevBtn = null) {
      if (!scroller || !nextBtn) return;

      if (typeof nextBtn._homeRailCleanup === 'function') {
        nextBtn._homeRailCleanup();
      }
      if (prevBtn && typeof prevBtn._homeRailCleanup === 'function') {
        prevBtn._homeRailCleanup();
      }

      const update = () => {
        const maxScrollLeft = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
        const scrollLeft = Math.max(0, Math.min(scroller.scrollLeft, maxScrollLeft));
        const canScrollLeft = maxScrollLeft > 0 && scrollLeft > 6;
        const canScrollRight = maxScrollLeft > 0 && scrollLeft < maxScrollLeft - 6;

        nextBtn.disabled = !canScrollRight;
        nextBtn.classList.toggle('is-hidden', !canScrollRight);

        if (prevBtn) {
          prevBtn.disabled = !canScrollLeft;
          prevBtn.classList.toggle('is-hidden', !canScrollLeft);
        }

        scroller.classList.toggle('has-scroll-left', canScrollLeft);
        scroller.classList.toggle('is-at-end', !canScrollRight && canScrollLeft);
      };

      const onNext = () => scroller.scrollBy({ left: getRailScrollStep(scroller), behavior: 'smooth' });
      const onPrev = () => scroller.scrollBy({ left: -getRailScrollStep(scroller), behavior: 'smooth' });

      nextBtn.addEventListener('click', onNext);
      if (prevBtn) prevBtn.addEventListener('click', onPrev);
      scroller.addEventListener('scroll', update, { passive: true });

      enableHomeDragToScroll(scroller);

      let ro = null;
      if (typeof ResizeObserver !== 'undefined') {
        ro = new ResizeObserver(update);
        ro.observe(scroller);
      }

      const cleanup = () => {
        nextBtn.removeEventListener('click', onNext);
        if (prevBtn) prevBtn.removeEventListener('click', onPrev);
        scroller.removeEventListener('scroll', update);
        if (ro) ro.disconnect();
        nextBtn._homeRailCleanup = null;
        if (prevBtn) prevBtn._homeRailCleanup = null;
      };

      nextBtn._homeRailCleanup = cleanup;
      if (prevBtn) prevBtn._homeRailCleanup = cleanup;

      requestAnimationFrame(update);
    }

    function renderHomeRecommendations(savedScrollLeft = 0) {
      const section = elements.homeRecommendationsSection;
      const rail = elements.homeRecommendationsRail;
      if (!section || !rail) return;

      const currentScroll = savedScrollLeft || rail.scrollLeft || 0;

      const allItems = [
        ...getHomeCatalogItems('series'),
        ...getHomeCatalogItems('movie')
      ];

      const recommendations = getHomeRecommendationMix(allItems);
      const visibleRecommendations = getHomeItemsWithinDisplayLimit(recommendations);
      const nonSequential = filterNonSequentialItems(visibleRecommendations, lastRenderedRailItemKeys);

      if (nonSequential.length < HOME_THEME_MIN_ITEMS) {
        section.style.display = 'none';
        rail.innerHTML = '';
        if (typeof elements.homeRecommendationsNext?._homeRailCleanup === 'function') {
          elements.homeRecommendationsNext._homeRailCleanup();
        }
        elements.homeRecommendationsNext?.classList.add('is-hidden');
        if (elements.homeRecommendationsNext) elements.homeRecommendationsNext.disabled = true;
        elements.homeRecommendationsPrev?.classList.add('is-hidden');
        if (elements.homeRecommendationsPrev) elements.homeRecommendationsPrev.disabled = true;
        return;
      }

      section.style.display = '';

      const topThemes = getTasteTopThemes(2);
      const title = section.querySelector('h2');
      const kicker = section.querySelector('.home-section-kicker');
      if (title) {
        title.textContent = topThemes.length
          ? 'Para você • ' + topThemes.join(' + ')
          : 'Para você';
      }
      if (kicker) kicker.remove();

      // Verifica se os cards já renderizados no DOM correspondem aos itens atuais
      const currentCards = Array.from(rail.querySelectorAll('.home-title-card'));
      const isSameItems = currentCards.length === nonSequential.length &&
        currentCards.every((card, i) => card.dataset.itemKey === ((nonSequential[i].type || '') + ':' + (nonSequential[i].id || '')));

      if (isSameItems) {
        nonSequential.forEach((item, index) => {
          updateHomeTitleCard(currentCards[index], item);
        });
        registerHomeDisplayItems(nonSequential);
        lastRenderedRailItemKeys = new Set(nonSequential.map(item => getHomeDisplayKey(item)).filter(Boolean));
        if (currentScroll > 0) rail.scrollLeft = currentScroll;
        return;
      }

      rail.innerHTML = '';
      nonSequential.forEach(item => rail.appendChild(renderHomeTitleCard(item)));
      registerHomeDisplayItems(nonSequential);
      lastRenderedRailItemKeys = new Set(nonSequential.map(item => getHomeDisplayKey(item)).filter(Boolean));

      if (currentScroll > 0) {
        rail.scrollLeft = currentScroll;
      }

      setupSingleHomeRailArrow(rail, elements.homeRecommendationsNext, elements.homeRecommendationsPrev);
    }

    function renderHomeCatalogSections(savedScrolls = new Map()) {
      try {
        renderHomeRecommendations(savedScrolls.get('recommendations') || 0);
      } catch (err) {
        console.error('Erro ao renderizar recomendações da Home:', err);
      }

      requestAnimationFrame(() => {
        if (currentMode !== 'home') return;

        try {
          const pref = getTasteTypePreference();

          if (pref.dominant === 'mixed') {
            renderHomeInterleavedRails(elements.homeSeriesRails, elements.homeSeriesSection, elements.homeMoviesSection, savedScrolls, pref);
          } else if (pref.dominant === 'movie') {
            // Dominância de filmes: reordenar seções no DOM para filmes ficarem no topo
            if (elements.homeMoviesSection && elements.homeSeriesSection && elements.homeSeriesSection.parentNode) {
              elements.homeSeriesSection.parentNode.insertBefore(elements.homeMoviesSection, elements.homeSeriesSection);
            }
            renderHomeCatalogRails('movie', elements.homeMoviesRails, elements.homeMoviesSection, savedScrolls);
            requestAnimationFrame(() => {
              if (currentMode === 'home') {
                renderHomeCatalogRails('series', elements.homeSeriesRails, elements.homeSeriesSection, savedScrolls);
              }
            });
          } else {
            // Dominância de séries: reordenar seções no DOM para séries ficarem no topo
            if (elements.homeSeriesSection && elements.homeMoviesSection && elements.homeMoviesSection.parentNode) {
              elements.homeMoviesSection.parentNode.insertBefore(elements.homeSeriesSection, elements.homeMoviesSection);
            }
            renderHomeCatalogRails('series', elements.homeSeriesRails, elements.homeSeriesSection, savedScrolls);
            requestAnimationFrame(() => {
              if (currentMode === 'home') {
                renderHomeCatalogRails('movie', elements.homeMoviesRails, elements.homeMoviesSection, savedScrolls);
              }
            });
          }
        } catch (err) {
          console.error('Erro ao renderizar trilhos do catálogo na Home:', err);
        }
      });
    }

    function renderHomeFeatured() {
      if (!elements.homeFeaturedTrack) return;
      homeFeaturedItems = getHomeFeaturedItems();
      homeFeaturedIndex = Math.min(homeFeaturedIndex, Math.max(0, homeFeaturedItems.length - 1));

      if (!homeFeaturedItems.length) {
        if (elements.homeFeaturedTrack.children.length > 0) return;
        if (elements.homeFeaturedLoading) {
          elements.homeFeaturedLoading.style.display = 'flex';
          elements.homeFeaturedLoading.textContent = 'Carregando novidades...';
        }
        elements.homeFeaturedTrack.innerHTML = '';
        if (elements.homeFeaturedDots) elements.homeFeaturedDots.innerHTML = '';
        return;
      }

      if (elements.homeFeaturedLoading) elements.homeFeaturedLoading.style.display = 'none';

      // Verifica se os slides já renderizados no DOM correspondem aos itens atuais
      const currentSlides = Array.from(elements.homeFeaturedTrack.querySelectorAll('.home-featured-slide'));
      const isSameItems = currentSlides.length === homeFeaturedItems.length &&
        currentSlides.every((slide, i) => slide.dataset.itemKey === (homeFeaturedItems[i].type + ':' + homeFeaturedItems[i].id));

      if (isSameItems) {
        // Preserva o DOM, a animação e o slide atual; apenas atualiza metadados se necessário
        homeFeaturedItems.forEach((item, index) => {
          const slide = currentSlides[index];
          if (!slide) return;
          const typeLabel = item.type === 'movie' ? 'FILME' : 'SÉRIE';
          const ratingInfo = getHomeRatingInfo(item);
          const metaText = [
            typeLabel,
            item.year,
            ratingInfo.value > 0 ? '★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' ' + ratingInfo.source : '') : ''
          ].filter(Boolean).join('  •  ');
          const metaEl = slide.querySelector('.home-featured-meta');
          if (metaEl && metaEl.textContent !== metaText) {
            metaEl.textContent = metaText;
          }
          const backdropImg = slide.querySelector('.home-featured-backdrop');
          if (backdropImg && item.poster && backdropImg.getAttribute('src') !== item.poster) {
            backdropImg.src = item.poster;
          }
          const posterImg = slide.querySelector('.home-featured-poster');
          if (posterImg && item.poster && posterImg.getAttribute('src') !== item.poster) {
            posterImg.src = item.poster;
          }
        });
        if (!homeFeaturedTimer) startHomeFeaturedTimer();
        return;
      }

      elements.homeFeaturedTrack.innerHTML = '';
      if (elements.homeFeaturedDots) elements.homeFeaturedDots.innerHTML = '';

      homeFeaturedItems.forEach((item, index) => {
        const slide = document.createElement('article');
        slide.className = 'home-featured-slide';
        slide.dataset.itemKey = item.type + ':' + item.id;
        const typeLabel = item.type === 'movie' ? 'FILME' : 'SÉRIE';
        const ratingInfo = getHomeRatingInfo(item);
        const meta = [
          typeLabel,
          item.year,
          ratingInfo.value > 0 ? '★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' ' + ratingInfo.source : '') : ''
        ].filter(Boolean).join('  •  ');

        slide.innerHTML =
          '<img class="home-featured-backdrop" src="' + escapeHtml(item.poster) + '" alt="" loading="' + (index === 0 ? 'eager' : 'lazy') + '" decoding="async" draggable="false">' +
          '<div class="home-featured-shade"></div>' +
          '<div class="home-featured-content">' +
            '<span class="home-featured-kicker">NOVIDADE NO CATÁLOGO • ' + typeLabel + '</span>' +
            '<h1>' + escapeHtml(item.title) + '</h1>' +
            '<div class="home-featured-meta">' + escapeHtml(meta) + '</div>' +
            '<p>' + escapeHtml(item.plot || 'Acabou de chegar ao catálogo do EPlay.') + '</p>' +
            '<button class="home-featured-watch" type="button">▶ Assistir</button>' +
          '</div>' +
          '<div class="home-featured-poster-wrap"><img class="home-featured-poster" src="' + escapeHtml(item.poster) + '" alt="" loading="' + (index === 0 ? 'eager' : 'lazy') + '" decoding="async" draggable="false"></div>';

        const openItem = () => {
          if (item.type === 'movie') onMovieCardClick(item.item);
          else openSeriesPage(item.item);
        };

        slide.addEventListener('click', (event) => {
          if (event.target.closest('.home-featured-watch')) return;
          openItem();
        });

        slide.querySelector('.home-featured-watch')?.addEventListener('click', (event) => {
          event.stopPropagation();
          openItem();
        });
        elements.homeFeaturedTrack.appendChild(slide);

        const dot = document.createElement('button');
        dot.type = 'button';
        dot.dataset.homeSlide = String(index);
        dot.className = 'home-featured-dot';
        dot.setAttribute('aria-label', 'Mostrar ' + item.title);
        elements.homeFeaturedDots?.appendChild(dot);
      });

      setupHomeFeaturedSwipe();
      updateHomeFeaturedPosition();
      startHomeFeaturedTimer();
    }

    function setupHomeFeaturedSwipe() {
      const track = elements.homeFeaturedTrack;
      if (!track || track._swipeInitialized) return;
      track._swipeInitialized = true;

      track.addEventListener('dragstart', e => e.preventDefault());

      let isDown = false;
      let startX = 0;
      let startY = 0;
      let isSwiping = false;
      let trackWidth = 0;
      let activePointerId = null;

      track.addEventListener('pointerdown', e => {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        if (homeFeaturedItems.length < 2) return;
        if (window.getSelection) window.getSelection().removeAllRanges();
        isDown = true;
        isSwiping = false;
        startX = e.clientX;
        startY = e.clientY;
        activePointerId = e.pointerId;
        trackWidth = track.clientWidth || window.innerWidth || 1;
      });

      track.addEventListener('pointermove', e => {
        if (!isDown || e.pointerId !== activePointerId) return;
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        const absX = Math.abs(dx);
        const absY = Math.abs(dy);

        if (!isSwiping) {
          if (absX > 8 && absX >= absY) {
            isSwiping = true;
            track.classList.add('is-swiping');
            if (window.getSelection) window.getSelection().removeAllRanges();
            clearInterval(homeFeaturedTimer);
            homeFeaturedTimer = null;
            try { track.setPointerCapture(e.pointerId); } catch (_) {}
          } else if (absY > 16 && absY > absX * 1.4) {
            isDown = false;
            return;
          }
        }

        if (isSwiping) {
          if (window.getSelection) window.getSelection().removeAllRanges();
          if (e.cancelable) e.preventDefault();
          let deltaPercent = (dx / trackWidth) * 100;
          if ((homeFeaturedIndex === 0 && dx > 0) || (homeFeaturedIndex === homeFeaturedItems.length - 1 && dx < 0)) {
            deltaPercent *= 0.35;
          }
          track.style.transform = 'translate3d(' + (-(homeFeaturedIndex * 100) + deltaPercent) + '%, 0, 0)';
        }
      });

      const onPointerEnd = e => {
        if (!isDown || e.pointerId !== activePointerId) return;
        isDown = false;
        try { track.releasePointerCapture(e.pointerId); } catch (_) {}

        if (isSwiping) {
          isSwiping = false;
          track.classList.remove('is-swiping');
          const dx = e.clientX - startX;

          const suppressClick = ev => {
            ev.preventDefault();
            ev.stopPropagation();
          };
          window.addEventListener('click', suppressClick, { capture: true, once: true });
          setTimeout(() => window.removeEventListener('click', suppressClick, { capture: true }), 80);

          if (dx < -36 && homeFeaturedIndex < homeFeaturedItems.length - 1) {
            moveHomeFeatured(1);
          } else if (dx > 36 && homeFeaturedIndex > 0) {
            moveHomeFeatured(-1);
          } else {
            updateHomeFeaturedPosition();
          }
        }
        startHomeFeaturedTimer();
      };

      track.addEventListener('pointerup', onPointerEnd);
      track.addEventListener('pointercancel', onPointerEnd);

      track.addEventListener('mouseenter', () => {
        clearInterval(homeFeaturedTimer);
        homeFeaturedTimer = null;
      });
      track.addEventListener('mouseleave', () => {
        startHomeFeaturedTimer();
      });
    }

    function updateHomeFeaturedPosition() {
      if (!elements.homeFeaturedTrack) return;
      elements.homeFeaturedTrack.style.transform = 'translate3d(-' + (homeFeaturedIndex * 100) + '%, 0, 0)';
      elements.homeFeaturedDots?.querySelectorAll('.home-featured-dot').forEach((dot, index) => {
        dot.classList.toggle('active', index === homeFeaturedIndex);
      });
    }

    function showHomeFeatured(index) {
      if (!homeFeaturedItems.length) return;
      const total = homeFeaturedItems.length;
      homeFeaturedIndex = ((Number(index) || 0) % total + total) % total;
      updateHomeFeaturedPosition();
      startHomeFeaturedTimer();
    }

    function moveHomeFeatured(delta) {
      showHomeFeatured(homeFeaturedIndex + Number(delta || 0));
    }

    function startHomeFeaturedTimer() {
      // Carrosséis nunca devem avançar sozinhos sem interação do usuário
      if (homeFeaturedTimer) {
        clearInterval(homeFeaturedTimer);
        homeFeaturedTimer = null;
      }
    }

    function getHomeDisplayKey(item) {
      return item ? item.type + ':' + String(item.id) : '';
    }

    function getHomeItemsWithinDisplayLimit(items) {
      return (Array.isArray(items) ? items : []).filter(item => {
        const key = getHomeDisplayKey(item);
        return key && Number(homeDisplayUsage.get(key) || 0) < HOME_MAX_APPEARANCES_PER_TITLE;
      });
    }

    function registerHomeDisplayItems(items) {
      (Array.isArray(items) ? items : []).forEach(item => {
        const key = getHomeDisplayKey(item);
        if (!key) return;
        homeDisplayUsage.set(key, Number(homeDisplayUsage.get(key) || 0) + 1);
      });
    }

    function renderHomeWatched(savedScrollLeft = 0) {
      if (!elements.homeWatchedRail) return;
      const currentScroll = savedScrollLeft || elements.homeWatchedRail.scrollLeft || 0;
      const items = getHomeWatchedItems();

      elements.homeWatchedSection?.style.setProperty('display', items.length ? '' : 'none');

      if (!items.length) {
        if (typeof elements.homeWatchedNext?._homeRailCleanup === 'function') {
          elements.homeWatchedNext._homeRailCleanup();
        }
        if (typeof elements.homeWatchedPrev?._homeRailCleanup === 'function') {
          elements.homeWatchedPrev._homeRailCleanup();
        }
        elements.homeWatchedNext?.classList.add('is-hidden');
        elements.homeWatchedPrev?.classList.add('is-hidden');
        if (elements.homeWatchedNext) elements.homeWatchedNext.disabled = true;
        if (elements.homeWatchedPrev) elements.homeWatchedPrev.disabled = true;
        elements.homeWatchedRail.innerHTML = '<div class="home-empty">Seus filmes e séries assistidos aparecerão aqui.</div>';
        return;
      }

      registerHomeDisplayItems(items);
      lastRenderedRailItemKeys = new Set(items.map(item => getHomeDisplayKey(item)).filter(Boolean));

      const currentCards = Array.from(elements.homeWatchedRail.querySelectorAll('.home-watched-card'));
      const isSameItems = currentCards.length === items.length &&
        currentCards.every((card, i) => card.dataset.itemKey === ((items[i].type || '') + ':' + (items[i].id || '')));

      if (isSameItems) {
        items.forEach((item, index) => {
          const card = currentCards[index];
          if (!card) return;
          const progress = item.progress && item.progress.duration > 0
            ? Math.max(0, Math.min(100, item.progress.position / item.progress.duration * 100))
            : 0;
          const sub = item.type === 'series'
            ? (item.progress?.seasonNum && item.progress?.episodeNum ? 'T' + item.progress.seasonNum + ' • E' + item.progress.episodeNum : 'Série')
            : (progress > 0 && progress < 95 ? 'Retomar em ' + formatResumeTime(item.progress.position) : (item.year || 'Filme'));
          const progressBar = card.querySelector('.home-watched-progress span');
          if (progressBar) progressBar.style.width = progress + '%';
          const subEl = card.querySelector('small');
          if (subEl && subEl.textContent !== sub) subEl.textContent = sub;

          const titleEl = card.querySelector('strong');
          if (titleEl && item.title && titleEl.textContent !== item.title) {
            titleEl.textContent = item.title;
            titleEl.title = item.title;
          }

          const posterWrap = card.querySelector('.home-watched-poster');
          if (posterWrap && item.poster) {
            const img = posterWrap.querySelector('img');
            if (img) {
              if (img.getAttribute('src') !== item.poster) img.src = item.poster;
            } else {
              const icon = posterWrap.querySelector('span:not(.home-watched-type):not(.home-watched-menu-wrap)');
              if (icon) icon.remove();
              const newImg = document.createElement('img');
              newImg.src = item.poster;
              newImg.alt = '';
              newImg.loading = 'lazy';
              newImg.decoding = 'async';
              newImg.setAttribute('data-hide-on-error', '');
              newImg.draggable = false;
              posterWrap.prepend(newImg);
            }
          }
        });
        if (currentScroll > 0) elements.homeWatchedRail.scrollLeft = currentScroll;
        return;
      }

      elements.homeWatchedRail.innerHTML = '';
      elements.homeWatchedPrev?.classList.add('is-hidden');
      elements.homeWatchedPrev && (elements.homeWatchedPrev.disabled = true);
      elements.homeWatchedNext?.classList.add('is-hidden');
      elements.homeWatchedNext && (elements.homeWatchedNext.disabled = true);

      items.forEach(item => {
        const card = document.createElement('div');
        card.className = 'home-watched-card';
        card.dataset.itemKey = (item.type || '') + ':' + (item.id || '');
        card.tabIndex = 0;
        card.setAttribute('role', 'button');
        const progress = item.progress && item.progress.duration > 0
          ? Math.max(0, Math.min(100, item.progress.position / item.progress.duration * 100))
          : 0;
        const sub = item.type === 'series'
          ? (item.progress?.seasonNum && item.progress?.episodeNum ? 'T' + item.progress.seasonNum + ' • E' + item.progress.episodeNum : 'Série')
          : (progress > 0 && progress < 95 ? 'Retomar em ' + formatResumeTime(item.progress.position) : (item.year || 'Filme'));

        card.innerHTML =
          '<div class="home-watched-poster">' +
            (item.poster
              ? '<img src="' + escapeHtml(item.poster) + '" alt="" loading="lazy" decoding="async" data-hide-on-error draggable="false">'
              : '<span>🎬</span>') +
            (progress > 0 && progress < 95 ? '<div class="home-watched-progress"><span style="width:' + progress + '%"></span></div>' : '') +
            '<span class="home-watched-type">' + (item.type === 'movie' ? 'FILME' : 'SÉRIE') + '</span>' +
            '<span class="home-watched-menu-wrap">' +
              '<button type="button" class="home-watched-menu" aria-label="Opções de ' + escapeHtml(item.title) + '" title="Opções">…</button>' +
              '<span class="home-watched-menu-panel">' +
                '<button type="button" class="home-watched-remove">Retirar de Assistidos</button>' +
              '</span>' +
            '</span>' +
          '</div>' +
          '<strong>' + escapeHtml(item.title) + '</strong>' +
          '<small>' + escapeHtml(sub) + '</small>';

        const menuButton = card.querySelector('.home-watched-menu');
        const menuPanel = card.querySelector('.home-watched-menu-panel');
        const removeButton = card.querySelector('.home-watched-remove');
        menuButton?.addEventListener('click', event => {
          event.preventDefault();
          event.stopPropagation();
          document.querySelectorAll('.home-watched-card.is-menu-open').forEach(other => {
            if (other !== card) other.classList.remove('is-menu-open');
          });
          card.classList.toggle('is-menu-open');
        });
        menuPanel?.addEventListener('click', event => event.stopPropagation());
        removeButton?.addEventListener('click', async event => {
          event.preventDefault();
          event.stopPropagation();
          card.classList.remove('is-menu-open');
          await removeWatched(item.type, item.id);
        });

        card.addEventListener('keydown', event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            card.click();
          }
        });
        card.addEventListener('click', () => {
          if (item.type === 'movie') {
            const version = (item.item.versions || []).find(v => String(v.streamId) === String(item.id)) || item.item.versions?.[0];
            if (version) openMoviePage(item.item).catch(() => {});
          } else {
            openSeriesPage(item.item);
          }
        });
        elements.homeWatchedRail.appendChild(card);
      });

      if (currentScroll > 0) {
        elements.homeWatchedRail.scrollLeft = currentScroll;
      }

      setupSingleHomeRailArrow(elements.homeWatchedRail, elements.homeWatchedNext, elements.homeWatchedPrev);
    }

    function setHomeDashboardVisible(visible) {
      if (!elements.homeDashboard) return;
      if (visible) {
        elements.homeDashboard.style.removeProperty('display');
        elements.homeDashboard.style.display = 'block';
        elements.homeDashboard.classList.add('is-active');
      } else {
        elements.homeDashboard.style.display = 'none';
        elements.homeDashboard.classList.remove('is-active');
      }
    }

    function showHomeGridLoading(isLoading) {
      let loader = elements.homeRailsLoading || document.getElementById('homeRailsLoading');
      if (!loader && elements.homeDashboard) {
        loader = document.createElement('div');
        loader.id = 'homeRailsLoading';
        loader.className = 'home-rails-loading';
        loader.innerHTML = '<div class="spinner"></div><span>Carregando catálogo e recomendações...</span>';
        if (elements.homeWatchedSection) {
          elements.homeDashboard.insertBefore(loader, elements.homeWatchedSection);
        } else {
          elements.homeDashboard.appendChild(loader);
        }
        elements.homeRailsLoading = loader;
      }
      if (loader) {
        loader.style.display = isLoading ? 'flex' : 'none';
      }
      if (isLoading) {
        if (elements.homeWatchedSection) elements.homeWatchedSection.style.display = 'none';
        if (elements.homeRecommendationsSection) elements.homeRecommendationsSection.style.display = 'none';
        if (elements.homeSeriesSection) elements.homeSeriesSection.style.display = 'none';
        if (elements.homeMoviesSection) elements.homeMoviesSection.style.display = 'none';
        if (elements.homeFeaturedLoading && (!homeFeaturedItems || !homeFeaturedItems.length)) {
          elements.homeFeaturedLoading.style.display = 'flex';
          elements.homeFeaturedLoading.textContent = 'Carregando novidades...';
        }
      } else {
        if (elements.homeWatchedSection && (getWatchedIds('movies').length || getWatchedIds('series').length)) {
          elements.homeWatchedSection.style.removeProperty('display');
        }
        if (elements.homeRecommendationsSection) elements.homeRecommendationsSection.style.removeProperty('display');
        if (elements.homeSeriesSection) elements.homeSeriesSection.style.removeProperty('display');
        if (elements.homeMoviesSection) elements.homeMoviesSection.style.removeProperty('display');
        if (elements.homeFeaturedLoading) elements.homeFeaturedLoading.style.display = 'none';
      }
    }

    function updateHomeRatingBadges() {
      try {
        const cards = elements.homeDashboard?.querySelectorAll('.home-title-card');
        if (cards && cards.length) {
          cards.forEach(card => {
            let item = card._homeItem;
            if (!item && card.dataset.itemKey) {
              const [type, id] = card.dataset.itemKey.split(':');
              if (type && id) {
                const catalog = getHomeCatalogItems(type);
                item = catalog.find(x => String(x.id) === String(id));
                if (item) card._homeItem = item;
              }
            }
            if (item) {
              updateHomeTitleCard(card, item);
            }
          });
        }
      } catch (err) {
        console.warn('[EPlay Home] Erro ao atualizar avaliações:', err);
      }
    }

    async function loadHomeDashboardData() {
      if (homeCatalogPromise) return homeCatalogPromise;
      homeCatalogPromise = (async () => {
        try {
          const [staleMovies, staleSeries, staleMovieCats, staleSeriesCats] = await Promise.all([
            readCatalogCacheStale('movies'),
            readCatalogCacheStale('series'),
            readCatalogCacheStale('movie_categories'),
            readCatalogCacheStale('series_categories')
          ]);
          if (Array.isArray(staleMovieCats) && staleMovieCats.length && !movieCategories.length) {
            movieCategories = staleMovieCats;
          }
          if (Array.isArray(staleSeriesCats) && staleSeriesCats.length && !seriesCategories.length) {
            seriesCategories = staleSeriesCats;
          }
          updateAdultCategoryIds();
          if (Array.isArray(staleMovies) && staleMovies.length) {
            fullMoviesCache = staleMovies;
            homeWatchedCatalogFallback.movies = staleMovies;
          }
          if (Array.isArray(staleSeries) && staleSeries.length) {
            fullSeriesCache = staleSeries;
            homeWatchedCatalogFallback.series = staleSeries;
          }
          if (!homeWatchedCatalogFallback.movies.length && Array.isArray(fullMoviesCache)) homeWatchedCatalogFallback.movies = fullMoviesCache.slice();
          if (!homeWatchedCatalogFallback.series.length && Array.isArray(fullSeriesCache)) homeWatchedCatalogFallback.series = fullSeriesCache.slice();

          const shouldRevalidate =
            !homeLastRevalidationAt ||
            Date.now() - homeLastRevalidationAt >= HOME_REVALIDATE_COOLDOWN_MS ||
            !fullMoviesCache?.length ||
            !fullSeriesCache?.length;

          await Promise.all([
            loadMovieCategories().catch(() => []),
            loadSeriesCategories().catch(() => [])
          ]);

          if (shouldRevalidate) {
            await Promise.all([
              loadFullMovies(true).catch(() => []),
              loadFullSeries(true).catch(() => [])
            ]);
            homeLastRevalidationAt = Date.now();
          }

          await hydrateRemoteHistoryMetadata(HOME_WATCHED_LIMIT);
          enrichHomeRatings()
            .then(() => updateHomeRatingBadges())
            .catch(() => {});
        } catch (err) {
          console.warn('[EPlay Home] Falha ao carregar catálogo da Home:', err);
        }
      })().finally(() => { homeCatalogPromise = null; });
      return homeCatalogPromise;
    }

    function renderHomeDashboard() {
      showHomeGridLoading(false);
      const savedScrolls = new Map();
      if (elements.homeWatchedRail) {
        savedScrolls.set('watched', elements.homeWatchedRail.scrollLeft);
      }
      if (elements.homeRecommendationsRail) {
        savedScrolls.set('recommendations', elements.homeRecommendationsRail.scrollLeft);
      }
      elements.homeSeriesRails?.querySelectorAll('.home-theme-scroller').forEach(s => {
        if (s.dataset.railKey) {
          const k = s.dataset.railKey.startsWith('series:') || s.dataset.railKey.startsWith('movie:') ? s.dataset.railKey : ('series:' + s.dataset.railKey);
          savedScrolls.set(k, s.scrollLeft);
        }
      });
      elements.homeMoviesRails?.querySelectorAll('.home-theme-scroller').forEach(s => {
        if (s.dataset.railKey) {
          const k = s.dataset.railKey.startsWith('movie:') || s.dataset.railKey.startsWith('series:') ? s.dataset.railKey : ('movie:' + s.dataset.railKey);
          savedScrolls.set(k, s.scrollLeft);
        }
      });

      homeDisplayUsage = new Map();
      lastRenderedRailItemKeys = new Set();
      renderHomeFeatured();
      registerHomeDisplayItems(homeFeaturedItems);
      renderHomeWatched(savedScrolls.get('watched') || 0);
      renderHomeCatalogSections(savedScrolls);
    }

    function scheduleHomeCatalogRender(delay = 40) {
      if (homeCatalogRenderTimer !== null) return;
      homeCatalogRenderTimer = window.setTimeout(() => {
        homeCatalogRenderTimer = null;
        if (currentMode === 'home') renderHomeDashboard();
      }, delay);
    }

    function startViewTransition() {
      document.body.classList.add('eplay-view-changing');
      if (viewTransitionTimer !== null) window.clearTimeout(viewTransitionTimer);
      viewTransitionTimer = window.setTimeout(() => {
        viewTransitionTimer = null;
        document.body.classList.remove('eplay-view-changing');
      }, 180);
    }

    function yieldToBrowser() {
      return new Promise(resolve => window.setTimeout(resolve, 0));
    }

    async function resumeHomeProgress(item) {
      if (!item) return;
      try {
        if (item.type === 'movie') {
          await loadFullMovies();
          const group = (fullMoviesCache || []).find(x => movieGroupMatchesWatchedId(x, item.id));
          if (group) {
            const version = (group.versions || []).find(v => String(v.streamId) === String(item.id)) || group.versions?.[0];
            if (version) {
              playMovieVersion(group, version, group.versions || [version]);
              return;
            }
          }
          switchMode('movies', true);
          elements.searchInput.value = item.title || '';
          onSearch(item.title || '');
          return;
        }

        await loadFullSeries();
        const group = (fullSeriesCache || []).find(x => String(x.series_id) === String(item.seriesId));
        if (!group) {
          switchMode('series', true);
          elements.searchInput.value = item.title || '';
          onSearch(item.title || '');
          return;
        }

        await openSeriesPage(group);
        const seasonKey = String(item.seasonNum || '');
        const eps = currentSeriesData?.episodes?.[seasonKey] || [];
        const ep = eps.find(e => String(e.id) === String(item.id)) ||
          eps.find(e => Number(e.episode_num) === Number(item.episodeNum));
        if (ep && item.seasonNum) {
          playSeriesEpisode(ep, String(item.seasonNum));
        }
      } catch (error) {
        console.warn('[EPlay Home] Não foi possível retomar:', error);
      }
    }

    // ==========================================
    // MOVIES HUB: HUB CINEMATOGRÁFICO DE FILMES
    // ==========================================
    let isMoviesCuratedMode = true;
    let currentMovieGenreFilter = 'ALL';
    let moviesSortBy = 'featured';
    let moviesFilter4K = false;
    let moviesFilterDub = false;
    let moviesFilterLeg = false;
    let moviesHeroItems = [];
    let moviesHeroIndex = 0;
    let moviesHeroTimer = null;
    let moviesHubInitialized = false;

    const MOVIE_GENRE_PILLS = [
      { id: 'ALL', label: '🍿 Todos os Filmes' },
      { id: 'TOP_RATED', label: '⭐ Mais Avaliados' },
      { id: 'RECENT_ADDED', label: '🕒 Recém Adicionados' },
      { id: 'NEW_RELEASES', label: '🆕 Lançamentos' },
      { id: 'FRANCHISES', label: '💥 Grandes Franquias' },
      { id: 'ACTION', label: '💥 Ação', theme: 'Ação e Aventura' },
      { id: 'COMEDY', label: '😂 Comédia', theme: 'Comédia' },
      { id: 'HORROR', label: '😱 Terror & Suspense', theme: 'Suspense e Terror' },
      { id: 'SCIFI', label: '🚀 Ficção Científica', theme: 'Ficção Científica' },
      { id: 'ANIMATION', label: '✨ Animação', theme: 'Animação' },
      { id: 'DRAMA', label: '🎭 Drama', theme: 'Drama' },
      { id: 'ROMANCE', label: '❤️ Romance', theme: 'Romance' },
      { id: 'CLASSICS', label: '👑 Clássicos Cult' },
      { id: '4K', label: '💎 4K Ultra HD', is4k: true }
    ];

    function setupPillsDragScroll(slider) {
      if (!slider) return;
      let isDown = false;
      let startX = 0;
      let scrollLeft = 0;
      let hasDragged = false;

      slider.addEventListener('mousedown', (e) => {
        isDown = true;
        hasDragged = false;
        startX = e.pageX - slider.offsetLeft;
        scrollLeft = slider.scrollLeft;
      });

      slider.addEventListener('mouseleave', () => { isDown = false; });
      slider.addEventListener('mouseup', () => { isDown = false; });

      slider.addEventListener('mousemove', (e) => {
        if (!isDown) return;
        const x = e.pageX - slider.offsetLeft;
        const walk = (x - startX) * 1.5;
        if (Math.abs(walk) > 4) hasDragged = true;
        slider.scrollLeft = scrollLeft - walk;
      });

      slider.addEventListener('click', (e) => {
        if (hasDragged) {
          e.preventDefault();
          e.stopPropagation();
        }
      }, true);
    }

    function initMoviesHub() {
      if (moviesHubInitialized || !elements.moviesHub) return;
      moviesHubInitialized = true;

      elements.moviesViewCuratedBtn?.addEventListener('click', () => {
        isMoviesCuratedMode = true;
        currentMovieGenreFilter = 'ALL';
        elements.moviesViewCuratedBtn.classList.add('active');
        elements.moviesViewGridBtn.classList.remove('active');
        if (elements.searchInput) elements.searchInput.value = '';
        if (elements.moviesHero) elements.moviesHero.style.removeProperty('display');
        if (elements.moviesGenrePills) elements.moviesGenrePills.style.removeProperty('display');
        renderMoviesGenrePills();
        renderMoviesHub();
      });

      elements.moviesViewGridBtn?.addEventListener('click', () => {
        isMoviesCuratedMode = false;
        elements.moviesViewCuratedBtn.classList.remove('active');
        elements.moviesViewGridBtn.classList.add('active');
        renderMoviesHub();
      });

      elements.moviesSurpriseBtn?.addEventListener('click', () => {
        pickSurpriseMovie();
      });

      elements.moviesSortSelect?.addEventListener('change', (e) => {
        moviesSortBy = e.target.value;
        if (isMoviesCuratedMode && currentMovieGenreFilter === 'ALL') {
          isMoviesCuratedMode = false;
          elements.moviesViewCuratedBtn?.classList.remove('active');
          elements.moviesViewGridBtn?.classList.add('active');
        }
        applyMoviesFiltersAndSort();
      });

      elements.moviesFilter4kBtn?.addEventListener('click', () => {
        moviesFilter4K = !moviesFilter4K;
        elements.moviesFilter4kBtn.classList.toggle('active', moviesFilter4K);
        if (isMoviesCuratedMode) {
          isMoviesCuratedMode = false;
          elements.moviesViewCuratedBtn?.classList.remove('active');
          elements.moviesViewGridBtn?.classList.add('active');
        }
        applyMoviesFiltersAndSort();
      });

      elements.moviesFilterDubBtn?.addEventListener('click', () => {
        moviesFilterDub = !moviesFilterDub;
        elements.moviesFilterDubBtn.classList.toggle('active', moviesFilterDub);
        if (isMoviesCuratedMode) {
          isMoviesCuratedMode = false;
          elements.moviesViewCuratedBtn?.classList.remove('active');
          elements.moviesViewGridBtn?.classList.add('active');
        }
        applyMoviesFiltersAndSort();
      });

      elements.moviesFilterLegBtn?.addEventListener('click', () => {
        moviesFilterLeg = !moviesFilterLeg;
        elements.moviesFilterLegBtn.classList.toggle('active', moviesFilterLeg);
        if (isMoviesCuratedMode) {
          isMoviesCuratedMode = false;
          elements.moviesViewCuratedBtn?.classList.remove('active');
          elements.moviesViewGridBtn?.classList.add('active');
        }
        applyMoviesFiltersAndSort();
      });

      elements.moviesHeroPrev?.addEventListener('click', () => moveMoviesHero(-1));
      elements.moviesHeroNext?.addEventListener('click', () => moveMoviesHero(1));
      elements.moviesHeroDots?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-movies-slide]');
        if (button) showMoviesHero(Number(button.dataset.moviesSlide));
      });

      setupPillsDragScroll(elements.moviesGenrePills);
    }

    function getMoviesHeroItems() {
      const movies = (Array.isArray(fullMoviesCache) ? fullMoviesCache : [])
        .filter(item => !isAdultItem(item))
        .map(item => ({
        type: 'movie',
        id: String(item.stream_id || item.primaryItem?.stream_id || ''),
        item,
        title: cleanDisplayTitle(item.name || item.title || 'Filme'),
        year: item.year || '',
        rating: item.rating || '',
        poster: getHomeItemPoster({ type: 'movie', primaryItem: item }),
        plot: item.plot || item.description || '',
        added: getHomeItemTime(item)
      })).filter(item => item.id && item.poster);

      if (!movies.length) return [];

      const sortedByAdded = movies.slice().sort((a, b) => b.added - a.added);
      const topAdded = sortedByAdded.slice(0, 20);

      const topRated = movies
        .filter(m => Number(m.rating) >= 7.0 && m.plot && m.plot.length > 20)
        .sort((a, b) => Number(b.rating) - Number(a.rating))
        .slice(0, 30);

      const selected = [];
      if (topAdded.length > 0) {
        selected.push(...pickRandomSample(topAdded, Math.min(2, topAdded.length)));
      }
      const ratedPool = topRated.filter(r => !selected.some(s => s.id === r.id));
      if (ratedPool.length > 0) {
        selected.push(...pickRandomSample(ratedPool, Math.min(3, ratedPool.length)));
      }

      if (selected.length < 5) {
        const remaining = movies.filter(m => !selected.some(s => s.id === m.id));
        selected.push(...pickRandomSample(remaining, 5 - selected.length));
      }

      return shuffleArray(selected).slice(0, 5);
    }

    function renderMoviesHero() {
      if (!elements.moviesHeroTrack) return;
      moviesHeroItems = getMoviesHeroItems();
      moviesHeroIndex = Math.min(moviesHeroIndex, Math.max(0, moviesHeroItems.length - 1));
      elements.moviesHeroTrack.innerHTML = '';
      if (elements.moviesHeroDots) elements.moviesHeroDots.innerHTML = '';

      if (!moviesHeroItems.length) {
        if (elements.moviesHeroLoading) {
          elements.moviesHeroLoading.style.display = 'flex';
          elements.moviesHeroLoading.textContent = 'Carregando novidades do cinema...';
        }
        return;
      }

      if (elements.moviesHeroLoading) elements.moviesHeroLoading.style.display = 'none';

      moviesHeroItems.forEach((item, index) => {
        const slide = document.createElement('article');
        slide.className = 'movies-hero-slide';
        const ratingInfo = getHomeRatingInfo(item);
        const is4K = item.item?.versions ? item.item.versions.some(v => v.versionInfo?.type?.startsWith('4k')) : /\b4k\b/i.test(item.title);
        const qualityLabel = is4K ? '4K ULTRA HD' : '';
        const topGenre = getHomeThemesForItem(item.item || item)[0] || '';
        const meta = [
          'FILME',
          item.year,
          ratingInfo.value > 0 ? '★ ' + ratingInfo.value.toFixed(1) + (ratingInfo.source ? ' ' + ratingInfo.source : ' IMDb') : '',
          qualityLabel,
          topGenre
        ].filter(Boolean).join('  •  ');

        slide.innerHTML =
          '<img class="movies-hero-backdrop" src="' + escapeHtml(item.poster) + '" alt="" loading="' + (index === 0 ? 'eager' : 'lazy') + '" decoding="async" draggable="false">' +
          '<div class="movies-hero-shade"></div>' +
          '<div class="movies-hero-content">' +
            '<span class="movies-hero-kicker">CINEMA EM DESTAQUE • FILME</span>' +
            '<h1>' + escapeHtml(item.title) + '</h1>' +
            '<div class="movies-hero-meta">' + escapeHtml(meta) + '</div>' +
            '<p>' + escapeHtml(item.plot || 'Disponível no catálogo de filmes do EPlay.') + '</p>' +
            '<div class="movies-hero-actions">' +
              '<button class="movies-hero-watch" type="button">▶ Assistir</button>' +
              '<button class="movies-hero-details" type="button">ℹ Ficha Técnica</button>' +
            '</div>' +
          '</div>' +
          '<div class="movies-hero-poster-wrap"><img class="movies-hero-poster" src="' + escapeHtml(item.poster) + '" alt="" loading="' + (index === 0 ? 'eager' : 'lazy') + '" decoding="async" draggable="false"></div>';

        const openItem = () => {
          if (item.item) onMovieCardClick(item.item);
        };

        slide.addEventListener('click', (event) => {
          if (event.target.closest('.movies-hero-actions')) return;
          openItem();
        });

        slide.querySelector('.movies-hero-watch')?.addEventListener('click', (event) => {
          event.stopPropagation();
          openItem();
        });

        slide.querySelector('.movies-hero-details')?.addEventListener('click', (event) => {
          event.stopPropagation();
          if (item.item) openMoviePage(item.item);
        });

        elements.moviesHeroTrack.appendChild(slide);

        const dot = document.createElement('button');
        dot.type = 'button';
        dot.dataset.moviesSlide = String(index);
        dot.className = 'movies-hero-dot';
        dot.setAttribute('aria-label', 'Mostrar ' + item.title);
        elements.moviesHeroDots?.appendChild(dot);
      });

      setupMoviesHeroSwipe();
      updateMoviesHeroPosition();
      startMoviesHeroTimer();
    }

    function setupMoviesHeroSwipe() {
      const track = elements.moviesHeroTrack;
      if (!track || track._swipeInitialized) return;
      track._swipeInitialized = true;

      track.addEventListener('dragstart', e => e.preventDefault());

      let isDown = false;
      let startX = 0;
      let startY = 0;
      let isSwiping = false;
      let trackWidth = 0;
      let activePointerId = null;

      track.addEventListener('pointerdown', e => {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        if (moviesHeroItems.length < 2) return;
        if (window.getSelection) window.getSelection().removeAllRanges();
        isDown = true;
        isSwiping = false;
        startX = e.clientX;
        startY = e.clientY;
        activePointerId = e.pointerId;
        trackWidth = track.clientWidth || window.innerWidth || 1;
      });

      track.addEventListener('pointermove', e => {
        if (!isDown || e.pointerId !== activePointerId) return;
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        const absX = Math.abs(dx);
        const absY = Math.abs(dy);

        if (!isSwiping) {
          if (absX > 8 && absX >= absY) {
            isSwiping = true;
            track.classList.add('is-swiping');
            if (window.getSelection) window.getSelection().removeAllRanges();
            clearInterval(moviesHeroTimer);
            moviesHeroTimer = null;
            try { track.setPointerCapture(e.pointerId); } catch (_) {}
          } else if (absY > 16 && absY > absX * 1.4) {
            isDown = false;
            return;
          }
        }

        if (isSwiping) {
          if (window.getSelection) window.getSelection().removeAllRanges();
          if (e.cancelable) e.preventDefault();
          let deltaPercent = (dx / trackWidth) * 100;
          if ((moviesHeroIndex === 0 && dx > 0) || (moviesHeroIndex === moviesHeroItems.length - 1 && dx < 0)) {
            deltaPercent *= 0.35;
          }
          track.style.transform = 'translate3d(' + (-(moviesHeroIndex * 100) + deltaPercent) + '%, 0, 0)';
        }
      });

      const onPointerEnd = e => {
        if (!isDown || e.pointerId !== activePointerId) return;
        isDown = false;
        try { track.releasePointerCapture(e.pointerId); } catch (_) {}

        if (isSwiping) {
          isSwiping = false;
          track.classList.remove('is-swiping');
          const dx = e.clientX - startX;

          const suppressClick = ev => {
            ev.preventDefault();
            ev.stopPropagation();
          };
          window.addEventListener('click', suppressClick, { capture: true, once: true });
          setTimeout(() => window.removeEventListener('click', suppressClick, { capture: true }), 80);

          if (dx < -36 && moviesHeroIndex < moviesHeroItems.length - 1) {
            moveMoviesHero(1);
          } else if (dx > 36 && moviesHeroIndex > 0) {
            moveMoviesHero(-1);
          } else {
            updateMoviesHeroPosition();
          }
        }
        startMoviesHeroTimer();
      };

      track.addEventListener('pointerup', onPointerEnd);
      track.addEventListener('pointercancel', onPointerEnd);

      track.addEventListener('mouseenter', () => {
        clearInterval(moviesHeroTimer);
        moviesHeroTimer = null;
      });
      track.addEventListener('mouseleave', () => {
        startMoviesHeroTimer();
      });
    }

    function updateMoviesHeroPosition() {
      if (!elements.moviesHeroTrack) return;
      elements.moviesHeroTrack.style.transform = 'translate3d(-' + (moviesHeroIndex * 100) + '%, 0, 0)';
      elements.moviesHeroDots?.querySelectorAll('.movies-hero-dot').forEach((dot, index) => {
        dot.classList.toggle('active', index === moviesHeroIndex);
      });
    }

    function showMoviesHero(index) {
      if (!moviesHeroItems.length) return;
      const total = moviesHeroItems.length;
      moviesHeroIndex = ((Number(index) || 0) % total + total) % total;
      updateMoviesHeroPosition();
      startMoviesHeroTimer();
    }

    function moveMoviesHero(delta) {
      showMoviesHero(moviesHeroIndex + Number(delta || 0));
    }

    function startMoviesHeroTimer() {
      // Carrosséis nunca devem avançar sozinhos sem interação do usuário
      if (moviesHeroTimer) {
        clearInterval(moviesHeroTimer);
        moviesHeroTimer = null;
      }
    }

    function renderMoviesGenrePills() {
      if (!elements.moviesGenrePills) return;
      elements.moviesGenrePills.innerHTML = '';

      MOVIE_GENRE_PILLS.forEach(pill => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'movies-pill' + (currentMovieGenreFilter === pill.id ? ' active' : '');
        btn.textContent = pill.label;
        btn.setAttribute('role', 'tab');
        btn.setAttribute('aria-selected', currentMovieGenreFilter === pill.id ? 'true' : 'false');

        btn.addEventListener('click', () => {
          currentMovieGenreFilter = pill.id;
          document.querySelectorAll('.movies-pill').forEach(p => p.classList.remove('active'));
          btn.classList.add('active');

          if (pill.id === 'ALL') {
            isMoviesCuratedMode = true;
            elements.moviesViewCuratedBtn?.classList.add('active');
            elements.moviesViewGridBtn?.classList.remove('active');
          } else {
            isMoviesCuratedMode = false;
            elements.moviesViewCuratedBtn?.classList.remove('active');
            elements.moviesViewGridBtn?.classList.add('active');
          }
          renderMoviesHub();
          btn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
        });

        elements.moviesGenrePills.appendChild(btn);
      });
    }

    function buildMoviesHubCuratedRails() {
      const allMovies = getHomeCatalogItems('movie');
      if (!allMovies.length) return [];

      const rails = [];

      // 1. ⭐ Aclamados pela Crítica (IMDb 7.5+)
      const topRated = allMovies
        .filter(m => getHomeRatingInfo(m).value >= 7.5)
        .sort((a, b) => getHomeRatingInfo(b).value - getHomeRatingInfo(a).value);
      if (topRated.length >= HOME_THEME_MIN_ITEMS) {
        const sampledRated = sampleChaoticRailItems(topRated.slice(0, 120), HOME_RAIL_ITEM_LIMIT);
        rails.push({
          key: 'critics-choice',
          title: '⭐ Aclamados pela Crítica (IMDb 7.5+)',
          items: sampledRated
        });
      }

      // 2. 🆕 Recém Adicionados ao Catálogo (35% catálogo novidades com distribuição uniforme)
      const railLimit = HOME_RAIL_ITEM_LIMIT;
      const catalogNewsQuota = Math.round(railLimit * 0.35); // 8 itens
      const generalQuota = railLimit - catalogNewsQuota; // 16 itens
      const sortedByAdded = allMovies.slice().sort((a, b) => (b.added || 0) - (a.added || 0));
      const topAddedPool = sortedByAdded.slice(0, Math.min(50, sortedByAdded.length));
      const catalogNewsItems = pickRandomSample(topAddedPool, catalogNewsQuota);
      const usedInNews = new Set(catalogNewsItems.map(x => (x.type || '') + ':' + (x.id || '')));
      const remainingPool = allMovies.filter(x => !usedInNews.has((x.type || '') + ':' + (x.id || '')));
      const generalItems = sampleChaoticRailItems(remainingPool, generalQuota);

      const distributedLatest = new Array(railLimit);
      const newsIndices = [1, 4, 7, 10, 13, 16, 19, 22];
      let ni = 0, gi = 0;
      for (let i = 0; i < railLimit; i++) {
        if (newsIndices.includes(i) && ni < catalogNewsItems.length) {
          distributedLatest[i] = catalogNewsItems[ni++];
        } else if (gi < generalItems.length) {
          distributedLatest[i] = generalItems[gi++];
        } else if (ni < catalogNewsItems.length) {
          distributedLatest[i] = catalogNewsItems[ni++];
        }
      }
      rails.push({
        key: 'latest-additions',
        title: '🆕 Recém Adicionados ao Catálogo',
        items: distributedLatest.filter(Boolean)
      });

      // 3. 🍿 Lançamentos do Cinema (2024–2026)
      const currentYear = new Date().getFullYear();
      const newReleases = allMovies.filter(m => {
        const y = Number(m.year || 0);
        return y >= currentYear - 2;
      });
      if (newReleases.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'new-releases',
          title: `🍿 Lançamentos do Cinema (${currentYear - 2}–${currentYear})`,
          items: sampleChaoticRailItems(newReleases, HOME_RAIL_ITEM_LIMIT)
        });
      }

      // 4. 💥 Grandes Franquias e Sagas do Cinema
      const franchiseKeywords = [
        'harry potter', 'vingadores', 'avengers', 'velozes e furiosos', 'fast & furious',
        'star wars', 'batman', 'homem-aranha', 'spider-man', 'senhor dos aneis', 'lord of the rings',
        'missao impossivel', 'mission impossible', 'john wick', 'jurassic', 'matrix', 'transformers',
        'panico', 'scream', 'invocacao do mal', 'jogos vorazes', 'hunger games'
      ];
      const franchiseCandidates = allMovies.filter(m => {
        const t = normalizeSearch(m.title || '');
        return franchiseKeywords.some(kw => t.includes(kw));
      });
      if (franchiseCandidates.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'franchises',
          title: '💥 Grandes Franquias e Sagas do Cinema',
          items: sampleChaoticRailItems(franchiseCandidates, HOME_RAIL_ITEM_LIMIT)
        });
      }

      // 5. 💥 Adrenalina Pura: Ação & Aventura
      const actionItems = allMovies.filter(m => getHomeThemesForItem(m).includes('Ação e Aventura'));
      if (actionItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'action',
          title: '💥 Adrenalina Pura: Ação & Aventura',
          items: sampleChaoticRailItems(actionItems, HOME_RAIL_ITEM_LIMIT)
        });
      }

      // 6. 😂 Sessão Pipoca & Comédia
      const comedyItems = allMovies.filter(m => getHomeThemesForItem(m).includes('Comédia'));
      if (comedyItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'comedy',
          title: '😂 Sessão Pipoca: Comédia & Família',
          items: sampleChaoticRailItems(comedyItems, HOME_RAIL_ITEM_LIMIT)
        });
      }

      // 7. 🌌 Viagens Além: Ficção Científica & Fantasia
      const scifiItems = allMovies.filter(m => getHomeThemesForItem(m).includes('Ficção Científica') || getHomeThemesForItem(m).includes('Fantasia'));
      if (scifiItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'scifi',
          title: '🌌 Viagens no Tempo & Ficção Científica',
          items: sampleChaoticRailItems(scifiItems, HOME_RAIL_ITEM_LIMIT)
        });
      }

      // 8. 😱 Tensão & Mistério: Terror e Suspense
      const horrorItems = allMovies.filter(m => getHomeThemesForItem(m).includes('Suspense e Terror'));
      if (horrorItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'horror',
          title: '😱 Noite de Tensão: Terror & Suspense',
          items: sampleChaoticRailItems(horrorItems, HOME_RAIL_ITEM_LIMIT)
        });
      }

      // 9. 👑 Clássicos e Obras Cult
      const classicItems = allMovies.filter(m => {
        const y = Number(m.year || 0);
        return y > 1950 && y < 2012 && getHomeRatingInfo(m).value >= 6.5;
      });
      if (classicItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'classics',
          title: '👑 Clássicos Imperdíveis e Obras Cult',
          items: sampleChaoticRailItems(classicItems, HOME_RAIL_ITEM_LIMIT)
        });
      }

      return rails;
    }

    function renderMoviesCuratedRails() {
      if (!elements.moviesCuratedRails) return;
      elements.moviesCuratedRails.innerHTML = '';
      const rails = buildMoviesHubCuratedRails();
      rails.forEach(rail => {
        const block = renderHomeRailBlock(rail, 'movie', new Map());
        if (block) {
          elements.moviesCuratedRails.appendChild(block);
        }
      });
    }

    function pickSurpriseMovie() {
      const candidates = (fullMoviesCache || []).filter(item => {
        if (isAdultItem(item)) return false;
        const rating = Number(item.rating || 0);
        return rating >= 6.8 || (item.year && Number(item.year) >= 2024);
      });
      const pool = candidates.length > 0 ? candidates : (fullMoviesCache || []).filter(item => !isAdultItem(item));
      if (!pool.length) return;
      const chosen = pool[Math.floor(Math.random() * pool.length)];
      openMoviePage(chosen);
    }

    function applyMoviesFiltersAndSort() {
      hideLoading();
      let list = (fullMoviesCache || []).slice();

      const selectedCat = elements.categorySelect?.value;
      const isExplicitAdultCategory = selectedCat && selectedCat !== 'ALL' && isAdultCategoryId(selectedCat);

      if (selectedCat && selectedCat !== 'ALL') {
        list = list.filter(item => {
          if (String(item.category_id) === String(selectedCat)) return true;
          if (Array.isArray(item.category_ids) && item.category_ids.some(c => String(c) === String(selectedCat))) return true;
          return false;
        });
      } else {
        list = list.filter(item => !isAdultItem(item));
      }

      if (!isExplicitAdultCategory) {
        list = list.filter(item => !isAdultItem(item));
      }

      if (currentMovieGenreFilter !== 'ALL') {
        if (currentMovieGenreFilter === 'TOP_RATED') {
          list = list.filter(m => Number(m.rating || 0) >= 7.0);
        } else if (currentMovieGenreFilter === 'RECENT_ADDED') {
          list = list.sort((a, b) => getHomeItemTime(b) - getHomeItemTime(a));
        } else if (currentMovieGenreFilter === 'NEW_RELEASES') {
          const currentYear = new Date().getFullYear();
          list = list.filter(m => Number(m.year || 0) >= currentYear - 2);
        } else if (currentMovieGenreFilter === 'FRANCHISES') {
          const kwList = ['harry potter', 'vingadores', 'avengers', 'velozes e furiosos', 'star wars', 'batman', 'homem-aranha', 'spider-man', 'senhor dos aneis', 'john wick', 'jurassic', 'matrix', 'transformers', 'panico'];
          list = list.filter(m => {
            const t = normalizeSearch(m.name || m.title || '');
            return kwList.some(k => t.includes(k));
          });
        } else if (currentMovieGenreFilter === 'CLASSICS') {
          list = list.filter(m => {
            const y = Number(m.year || 0);
            return y > 1950 && y < 2012 && Number(m.rating || 0) >= 6.5;
          });
        } else if (currentMovieGenreFilter === '4K') {
          list = list.filter(m => (m.versions && m.versions.some(v => v.versionInfo?.type?.startsWith('4k'))) || /\b4k\b/i.test(m.name || m.title || ''));
        } else {
          const pill = MOVIE_GENRE_PILLS.find(p => p.id === currentMovieGenreFilter);
          if (pill && pill.theme) {
            list = list.filter(m => {
              const themes = getHomeThemesForItem(m);
              return themes.includes(pill.theme);
            });
          }
        }
      }

      const query = normalizeSearch(elements.searchInput?.value || '');
      if (query) {
        list = list.filter(item => matchesSearchQuery(item, query));
      }

      if (moviesFilter4K) {
        list = list.filter(m => (m.versions && m.versions.some(v => v.versionInfo?.type?.startsWith('4k'))) || /\b4k\b/i.test(m.name || m.title || ''));
      }
      if (moviesFilterDub) {
        list = list.filter(m => m.versions ? m.versions.some(v => v.versionInfo?.type === 'dublado' || v.versionInfo?.type === '4k_dub') : !/\[\s*L\s*\]/i.test(m.name || m.title || ''));
      }
      if (moviesFilterLeg) {
        list = list.filter(m => m.versions ? m.versions.some(v => v.versionInfo?.type?.includes('leg')) : /\[\s*L\s*\]/i.test(m.name || m.title || ''));
      }

      if (moviesSortBy === 'added') {
        list.sort((a, b) => getHomeItemTime(b) - getHomeItemTime(a));
      } else if (moviesSortBy === 'year') {
        list.sort((a, b) => (Number(b.year || 0)) - (Number(a.year || 0)));
      } else if (moviesSortBy === 'rating') {
        list.sort((a, b) => (Number(b.rating || 0)) - (Number(a.rating || 0)));
      } else if (moviesSortBy === 'alpha') {
        list.sort((a, b) => cleanDisplayTitle(a.name || a.title || '').localeCompare(cleanDisplayTitle(b.name || b.title || '')));
      }

      currentFilteredList = list;
      elements.mediaGrid.innerHTML = '';
      renderedCount = 0;
      updateCountDisplay();

      if (currentFilteredList.length === 0) {
        elements.mediaGrid.innerHTML = `
          <div style="grid-column: 1/-1; text-align: center; color: #888; padding: 60px 20px;">
            <p style="font-size: 18px; margin-bottom: 12px;">Nenhum filme encontrado com estes filtros.</p>
            <button id="clearMoviesFiltersBtn" class="btn btn-primary">Limpar Filtros e Ver Todos</button>
          </div>
        `;
        document.getElementById('clearMoviesFiltersBtn')?.addEventListener('click', () => {
          currentMovieGenreFilter = 'ALL';
          moviesFilter4K = false;
          moviesFilterDub = false;
          moviesFilterLeg = false;
          moviesSortBy = 'featured';
          if (elements.moviesSortSelect) elements.moviesSortSelect.value = 'featured';
          elements.moviesFilter4kBtn?.classList.remove('active');
          elements.moviesFilterDubBtn?.classList.remove('active');
          elements.moviesFilterLegBtn?.classList.remove('active');
          renderMoviesGenrePills();
          applyMoviesFiltersAndSort();
        });
        elements.loadMoreContainer.style.display = 'none';
        return;
      }

      renderNextBatch();
    }

    function renderMoviesHub() {
      if (currentMode !== 'movies' || isWatchedView || isFavoritesView) {
        if (elements.moviesHub) elements.moviesHub.style.display = 'none'; if (elements.seriesHub) elements.seriesHub.style.display = 'none'; if (elements.liveHub) elements.liveHub.style.display = 'none';
        return;
      }

      initMoviesHub();
      if (elements.seriesHub) elements.seriesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'none';
      elements.moviesHub.style.display = 'block';

      renderMoviesHero();
      renderMoviesGenrePills();

      if (elements.searchInput?.value?.trim()) {
        if (elements.moviesHero) elements.moviesHero.style.display = 'none';
        if (elements.moviesGenrePills) elements.moviesGenrePills.style.display = 'none';
      } else {
        if (elements.moviesHero) elements.moviesHero.style.removeProperty('display');
        if (elements.moviesGenrePills) elements.moviesGenrePills.style.removeProperty('display');
      }

      const inCurated = isMoviesCuratedMode && (!elements.categorySelect || elements.categorySelect.value === 'ALL') && currentMovieGenreFilter === 'ALL' && !elements.searchInput.value && !moviesFilter4K && !moviesFilterDub && !moviesFilterLeg && moviesSortBy === 'featured';

      if (inCurated) {
        elements.moviesCuratedRails.style.display = 'flex';
        elements.mediaGrid.style.display = 'none';
        elements.loadMoreContainer.style.display = 'none';
        document.querySelector('.status-bar')?.style.setProperty('display', 'none');
        elements.moviesViewCuratedBtn?.classList.add('active');
        elements.moviesViewGridBtn?.classList.remove('active');
        renderMoviesCuratedRails();
      } else {
        elements.moviesCuratedRails.style.display = 'none';
        elements.mediaGrid.style.removeProperty('display');
        document.querySelector('.status-bar')?.style.removeProperty('display');
        elements.moviesViewCuratedBtn?.classList.remove('active');
        elements.moviesViewGridBtn?.classList.add('active');
        applyMoviesFiltersAndSort();
      }
    }


    // ==========================================
    // SERIES HUB & IMDB MATCHING (SOB DEMANDA)
    // ==========================================
    const SERIES_IMDB_CACHE_KEY = 'andplay_series_imdb_match_v1';
    let seriesImdbMemoryCache = null;

    function readSeriesImdbCache() {
      if (seriesImdbMemoryCache) return seriesImdbMemoryCache;
      try {
        const raw = localStorage.getItem(SERIES_IMDB_CACHE_KEY);
        seriesImdbMemoryCache = raw ? JSON.parse(raw) : {};
      } catch (_) {
        seriesImdbMemoryCache = {};
      }
      return seriesImdbMemoryCache;
    }

    let _seriesImdbSaveTimer = null;
    function writeSeriesImdbCache(key, imdbId) {
      if (!key || !imdbId) return;
      const cache = readSeriesImdbCache();
      cache[key] = imdbId;
      if (_seriesImdbSaveTimer) return;
      _seriesImdbSaveTimer = setTimeout(() => {
        _seriesImdbSaveTimer = null;
        try {
          localStorage.setItem(SERIES_IMDB_CACHE_KEY, JSON.stringify(cache));
        } catch (_) {}
      }, 500);
    }

    // Match sob demanda: apenas quando o usuário abre ou assiste a série
    async function resolveSeriesImdbId(seriesItemOrGroup) {
      if (!seriesItemOrGroup) return '';
      const existing = normalizeImdbId(seriesItemOrGroup.imdbId || seriesItemOrGroup.imdb_id);
      if (existing) return existing;

      const rawTitle = seriesItemOrGroup.name || seriesItemOrGroup.title || seriesItemOrGroup.originalName || '';
      if (!rawTitle) return '';

      const cleanKey = cleanTitleKey(rawTitle);
      if (!cleanKey) return '';

      const cache = readSeriesImdbCache();
      if (cache[cleanKey]) {
        const cachedId = cache[cleanKey];
        seriesItemOrGroup.imdbId = cachedId;
        seriesItemOrGroup.imdb_id = cachedId;
        return cachedId;
      }

      // Consulta individual e pontual no Cinemeta (~150ms)
      try {
        const query = parseTitleInfo(rawTitle) || cleanKey;
        if (!query) return '';
        const searchUrl = 'https://v3-cinemeta.strem.io/catalog/series/top/search=' + encodeURIComponent(query) + '.json';
        const data = await fetchJsonWithTimeout(searchUrl, 4000);
        const metas = Array.isArray(data?.metas) ? data.metas : [];
        if (metas.length > 0) {
          const expectedYear = String(seriesItemOrGroup.year || '').trim();
          let best = metas[0];
          if (expectedYear) {
            const withYear = metas.find(m => (m.releaseInfo && String(m.releaseInfo).includes(expectedYear)) || (m.year && String(m.year).includes(expectedYear)));
            if (withYear) best = withYear;
          }
          const foundId = normalizeImdbId(best?.id);
          if (foundId) {
            seriesItemOrGroup.imdbId = foundId;
            seriesItemOrGroup.imdb_id = foundId;
            writeSeriesImdbCache(cleanKey, foundId);
            if ((!seriesItemOrGroup.rating || Number(seriesItemOrGroup.rating) <= 0) && best.imdbRating) {
              seriesItemOrGroup.rating = best.imdbRating;
            }
            return foundId;
          }
        }
      } catch (_) {}
      return '';
    }

    let seriesHubInitialized = false;
    let isSeriesCuratedMode = true;
    let currentSeriesGenreFilter = 'ALL';
    let seriesSortBy = 'featured';
    let seriesFilterDub = false;
    let seriesFilterLeg = false;
    let seriesHeroItems = [];
    let seriesHeroIndex = 0;
    let seriesHeroTimer = null;

    function initSeriesHub() {
      if (seriesHubInitialized || !elements.seriesHub) return;
      seriesHubInitialized = true;

      elements.seriesViewCuratedBtn?.addEventListener('click', () => {
        isSeriesCuratedMode = true;
        currentSeriesGenreFilter = 'ALL';
        elements.seriesViewCuratedBtn.classList.add('active');
        elements.seriesViewGridBtn.classList.remove('active');
        if (elements.searchInput) elements.searchInput.value = '';
        if (elements.seriesHero) elements.seriesHero.style.removeProperty('display');
        if (elements.seriesGenrePills) elements.seriesGenrePills.style.removeProperty('display');
        renderSeriesGenrePills();
        renderSeriesHub();
      });

      elements.seriesViewGridBtn?.addEventListener('click', () => {
        isSeriesCuratedMode = false;
        elements.seriesViewCuratedBtn.classList.remove('active');
        elements.seriesViewGridBtn.classList.add('active');
        renderSeriesHub();
      });

      elements.seriesSurpriseBtn?.addEventListener('click', () => {
        pickSurpriseSeries();
      });

      elements.seriesSortSelect?.addEventListener('change', (e) => {
        seriesSortBy = e.target.value;
        if (isSeriesCuratedMode && currentSeriesGenreFilter === 'ALL') {
          isSeriesCuratedMode = false;
          elements.seriesViewCuratedBtn?.classList.remove('active');
          elements.seriesViewGridBtn?.classList.add('active');
        }
        applySeriesFiltersAndSort();
      });

      elements.seriesFilterDubBtn?.addEventListener('click', () => {
        seriesFilterDub = !seriesFilterDub;
        elements.seriesFilterDubBtn.classList.toggle('active', seriesFilterDub);
        if (isSeriesCuratedMode) {
          isSeriesCuratedMode = false;
          elements.seriesViewCuratedBtn?.classList.remove('active');
          elements.seriesViewGridBtn?.classList.add('active');
        }
        applySeriesFiltersAndSort();
      });

      elements.seriesFilterLegBtn?.addEventListener('click', () => {
        seriesFilterLeg = !seriesFilterLeg;
        elements.seriesFilterLegBtn.classList.toggle('active', seriesFilterLeg);
        if (isSeriesCuratedMode) {
          isSeriesCuratedMode = false;
          elements.seriesViewCuratedBtn?.classList.remove('active');
          elements.seriesViewGridBtn?.classList.add('active');
        }
        applySeriesFiltersAndSort();
      });

      elements.seriesHeroPrev?.addEventListener('click', () => moveSeriesHero(-1));
      elements.seriesHeroNext?.addEventListener('click', () => moveSeriesHero(1));
      elements.seriesHeroDots?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-series-slide]');
        if (button) showSeriesHero(Number(button.dataset.seriesSlide));
      });

      setupPillsDragScroll(elements.seriesGenrePills);
    }

    function getSeriesHeroItems() {
      const series = (Array.isArray(fullSeriesCache) ? fullSeriesCache : [])
        .filter(item => !isAdultItem(item))
        .map(item => ({
        type: 'series',
        id: String(item.series_id || item.primaryItem?.series_id || ''),
        item,
        title: cleanDisplayTitle(item.name || item.title || 'Série'),
        year: item.year || '',
        rating: item.rating || '',
        poster: item.cover || item.poster || item.stream_icon || getHomeItemPoster({ type: 'series', primaryItem: item }),
        plot: item.plot || item.description || '',
        added: getHomeItemTime(item),
        genre: item.genre || 'Série'
      })).filter(item => item.id && item.poster);

      if (!series.length) return [];

      const sortedByAdded = series.slice().sort((a, b) => b.added - a.added);
      const topAdded = sortedByAdded.slice(0, 20);

      const topRated = series
        .filter(s => Number(s.rating) >= 7.5 && s.plot && s.plot.length > 20)
        .sort((a, b) => Number(b.rating) - Number(a.rating))
        .slice(0, 30);

      const pool = topRated.length >= 6 ? topRated : series.slice(0, 30);
      const candidates = [...topAdded.slice(0, 8), ...pool.slice(0, 16)];
      const seen = new Set();
      const unique = [];
      for (const item of candidates) {
        if (!seen.has(item.id)) {
          seen.add(item.id);
          unique.push(item);
        }
      }
      return pickRandomSample(unique, Math.min(8, unique.length));
    }

    function renderSeriesHero() {
      if (!elements.seriesHeroTrack) return;
      seriesHeroItems = getSeriesHeroItems();
      seriesHeroIndex = Math.min(seriesHeroIndex, Math.max(0, seriesHeroItems.length - 1));
      elements.seriesHeroTrack.innerHTML = '';
      if (elements.seriesHeroDots) elements.seriesHeroDots.innerHTML = '';

      if (!seriesHeroItems.length) {
        if (elements.seriesHeroLoading) {
          elements.seriesHeroLoading.style.display = 'flex';
          elements.seriesHeroLoading.textContent = 'Carregando novidades de séries...';
        }
        return;
      }

      if (elements.seriesHeroLoading) elements.seriesHeroLoading.style.display = 'none';

      seriesHeroItems.forEach((item, index) => {
        const slide = document.createElement('article');
        slide.className = 'series-hero-slide';
        slide.dataset.seriesIndex = String(index);

        const ratingVal = Number(item.rating || 0);
        const ratingStr = ratingVal > 0 ? '★ ' + ratingVal.toFixed(1) + ' IMDb' : '';
        const metaParts = [ratingStr, item.year, item.genre].filter(Boolean);
        const meta = metaParts.join(' • ');

        slide.innerHTML =
          '<img class="series-hero-backdrop" src="' + escapeHtml(item.poster) + '" alt="" loading="' + (index === 0 ? 'eager' : 'lazy') + '" decoding="async" draggable="false">' +
          '<div class="series-hero-shade"></div>' +
          '<div class="series-hero-content">' +
            '<span class="series-hero-kicker">SÉRIES EM DESTAQUE • TEMPORADAS COMPLETAS</span>' +
            '<h1>' + escapeHtml(item.title) + '</h1>' +
            '<div class="series-hero-meta">' + escapeHtml(meta) + '</div>' +
            '<p>' + escapeHtml(item.plot || 'Disponível no catálogo de séries do EPlay.') + '</p>' +
            '<div class="series-hero-actions">' +
              '<button class="series-hero-watch" type="button">▶ Assistir Temporadas</button>' +
              '<button class="series-hero-details" type="button">ℹ Ficha Técnica</button>' +
            '</div>' +
          '</div>' +
          '<div class="series-hero-poster-wrap"><img class="series-hero-poster" src="' + escapeHtml(item.poster) + '" alt="" loading="' + (index === 0 ? 'eager' : 'lazy') + '" decoding="async" draggable="false"></div>';

        const openItem = () => {
          if (item.item) openSeriesPage(item.item);
        };

        slide.addEventListener('click', (event) => {
          if (event.target.closest('.series-hero-actions')) return;
          openItem();
        });

        slide.querySelector('.series-hero-watch')?.addEventListener('click', (event) => {
          event.stopPropagation();
          openItem();
        });

        slide.querySelector('.series-hero-details')?.addEventListener('click', (event) => {
          event.stopPropagation();
          openItem();
        });

        elements.seriesHeroTrack.appendChild(slide);

        const dot = document.createElement('button');
        dot.type = 'button';
        dot.dataset.seriesSlide = String(index);
        dot.className = 'series-hero-dot';
        dot.setAttribute('aria-label', 'Ir para destaque ' + (index + 1) + ': ' + item.title);
        elements.seriesHeroDots?.appendChild(dot);
      });

      updateSeriesHeroPosition();
      setupSeriesHeroSwipe();
      startSeriesHeroTimer();
    }

    function setupSeriesHeroSwipe() {
      const track = elements.seriesHeroTrack;
      if (!track || track._hasSwipe) return;
      track._hasSwipe = true;

      let isDown = false;
      let startX = 0;
      let startY = 0;
      let currentX = 0;
      let isDragging = false;
      let activePointerId = null;

      const onPointerDown = (e) => {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        if (e.target.closest('.series-hero-actions, .series-hero-arrow, .series-hero-dots')) return;

        if (seriesHeroTimer) clearInterval(seriesHeroTimer);
        seriesHeroTimer = null;

        isDown = true;
        isDragging = false;
        activePointerId = e.pointerId;
        startX = e.clientX;
        startY = e.clientY;
        currentX = e.clientX;
      };

      const onPointerMove = (e) => {
        if (!isDown || e.pointerId !== activePointerId) return;
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        const absX = Math.abs(dx);
        const absY = Math.abs(dy);

        if (!isDragging) {
          if (absX > 8 && absX >= absY) {
            isDragging = true;
            track.classList.add('is-swiping');
            try { track.setPointerCapture(e.pointerId); } catch (_) {}
          } else if (absY > 16 && absY > absX * 1.4) {
            isDown = false;
            return;
          }
        }

        if (isDragging) {
          if (e.cancelable) e.preventDefault();
          currentX = e.clientX;
          const trackWidth = track.clientWidth || 1;
          const offsetPercent = (dx / trackWidth) * 100;
          const basePercent = -seriesHeroIndex * 100;
          let targetPercent = basePercent + offsetPercent;

          if (seriesHeroIndex === 0 && offsetPercent > 0) {
            targetPercent = basePercent + offsetPercent * 0.35;
          } else if (seriesHeroIndex === seriesHeroItems.length - 1 && offsetPercent < 0) {
            targetPercent = basePercent + offsetPercent * 0.35;
          }

          track.style.transform = 'translate3d(' + targetPercent + '%, 0, 0)';
        }
      };

      const onPointerEnd = (e) => {
        if (!isDown || (activePointerId !== null && e.pointerId !== activePointerId)) return;
        isDown = false;
        const pid = activePointerId;
        activePointerId = null;
        if (pid !== null) {
          try { track.releasePointerCapture(pid); } catch (_) {}
        }

        track.classList.remove('is-swiping');

        if (isDragging) {
          isDragging = false;
          const dx = currentX - startX;
          const threshold = Math.max(36, (track.clientWidth || 300) * 0.12);

          if (dx < -threshold && seriesHeroIndex < seriesHeroItems.length - 1) {
            moveSeriesHero(1);
          } else if (dx > threshold && seriesHeroIndex > 0) {
            moveSeriesHero(-1);
          } else {
            updateSeriesHeroPosition();
          }

          const suppressClick = (clickEv) => {
            clickEv.preventDefault();
            clickEv.stopPropagation();
          };
          window.addEventListener('click', suppressClick, { capture: true, once: true });
          setTimeout(() => window.removeEventListener('click', suppressClick, { capture: true }), 100);
        } else {
          updateSeriesHeroPosition();
        }

        startSeriesHeroTimer();
      };

      track.addEventListener('pointerdown', onPointerDown);
      track.addEventListener('pointermove', onPointerMove);
      track.addEventListener('pointerup', onPointerEnd);
      track.addEventListener('pointercancel', onPointerEnd);

      track.addEventListener('mouseenter', () => {
        if (seriesHeroTimer) clearInterval(seriesHeroTimer);
        seriesHeroTimer = null;
      });
      track.addEventListener('mouseleave', () => {
        if (!isDown) startSeriesHeroTimer();
      });
    }

    function updateSeriesHeroPosition() {
      if (!elements.seriesHeroTrack) return;
      elements.seriesHeroTrack.style.transform = 'translate3d(-' + (seriesHeroIndex * 100) + '%, 0, 0)';
      elements.seriesHeroDots?.querySelectorAll('.series-hero-dot').forEach((dot, idx) => {
        dot.classList.toggle('active', idx === seriesHeroIndex);
      });
    }

    function showSeriesHero(index) {
      if (!seriesHeroItems.length) return;
      seriesHeroIndex = (index + seriesHeroItems.length) % seriesHeroItems.length;
      updateSeriesHeroPosition();
    }

    function moveSeriesHero(delta) {
      showSeriesHero(seriesHeroIndex + delta);
    }

    function startSeriesHeroTimer() {
      // Carrosséis nunca devem avançar sozinhos sem interação do usuário
      if (seriesHeroTimer) {
        clearInterval(seriesHeroTimer);
        seriesHeroTimer = null;
      }
    }

    const SERIES_HUB_PILLS = [
      { id: 'ALL', label: 'Todas as Séries', icon: '🌐' },
      { id: 'TOP_RATED', label: 'Aclamadas (IMDb 8.0+)', icon: '⭐' },
      { id: 'RECENT_ADDED', label: 'Recém Chegadas', icon: '🕒' },
      { id: 'NEW_RELEASES', label: 'Novas Temporadas (2024–2026)', icon: '🆕' },
      { id: 'STREAMING_HBO', label: 'HBO & Max Originals', icon: '🟣' },
      { id: 'STREAMING_NETFLIX', label: 'Netflix & Prime', icon: '🔴' },
      { id: 'CRIME_SUSPENSE', label: 'Crime, Mistério & Suspense', icon: '🕵️' },
      { id: 'SCIFI_FANTASY', label: 'Sci-Fi & Fantasia Épica', icon: '🚀' },
      { id: 'COMEDY', label: 'Comédias & Sitcoms', icon: '😂' },
      { id: 'MINISERIES', label: 'Minisséries & Grandes Histórias', icon: '👑' },
      { id: 'ANIMATION', label: 'Animações & Animes', icon: '⚡' }
    ];

    function renderSeriesGenrePills() {
      if (!elements.seriesGenrePills) return;
      elements.seriesGenrePills.innerHTML = '';

      SERIES_HUB_PILLS.forEach(pill => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'series-pill ' + (currentSeriesGenreFilter === pill.id ? 'active' : '');
        btn.dataset.pillId = pill.id;
        btn.innerHTML = pill.icon + ' <span>' + escapeHtml(pill.label) + '</span>';

        btn.addEventListener('click', () => {
          currentSeriesGenreFilter = pill.id;
          document.querySelectorAll('.series-pill').forEach(p => p.classList.remove('active'));
          btn.classList.add('active');

          if (pill.id === 'ALL') {
            isSeriesCuratedMode = true;
            elements.seriesViewCuratedBtn?.classList.add('active');
            elements.seriesViewGridBtn?.classList.remove('active');
          } else {
            isSeriesCuratedMode = false;
            elements.seriesViewCuratedBtn?.classList.remove('active');
            elements.seriesViewGridBtn?.classList.add('active');
          }
          renderSeriesHub();
          btn.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
        });

        elements.seriesGenrePills.appendChild(btn);
      });
    }

    function buildSeriesHubCuratedRails() {
      const allSeries = getHomeCatalogItems('series');
      if (!allSeries.length) return [];

      const rails = [];
      const railLimit = HOME_RAIL_ITEM_LIMIT; // 24

      // 1. Séries do Momento & Mais Maratonadas
      const topMarathon = allSeries.filter(s => {
        const rating = getHomeRatingInfo(s).value;
        const y = Number(s.year || 0);
        return rating >= 7.6 || y >= 2024;
      });
      rails.push({
        key: 'top-marathon',
        title: '🔥 Séries do Momento & Mais Maratonadas',
        items: sampleChaoticRailItems(topMarathon.length >= 10 ? topMarathon : allSeries, railLimit)
      });

      // 2. Recém Chegadas ao Catálogo (35% novidades distribuídas)
      const sortedByAdded = allSeries.slice().sort((a, b) => (b.added || 0) - (a.added || 0));
      const catalogNewsQuota = Math.round(railLimit * 0.35);
      const topAddedPool = sortedByAdded.slice(0, Math.min(45, sortedByAdded.length));
      const newsItems = pickRandomSample(topAddedPool, catalogNewsQuota);
      const usedInNews = new Set(newsItems.map(x => (x.type || '') + ':' + (x.id || '')));
      const remainingPool = allSeries.filter(x => !usedInNews.has((x.type || '') + ':' + (x.id || '')));
      const generalItems = sampleChaoticRailItems(remainingPool, railLimit - catalogNewsQuota);

      const distributedLatest = new Array(railLimit);
      const newsIndices = [1, 4, 7, 10, 13, 16, 19, 22];
      let ni = 0, gi = 0;
      for (let i = 0; i < railLimit; i++) {
        if (newsIndices.includes(i) && ni < newsItems.length) {
          distributedLatest[i] = newsItems[ni++];
        } else if (gi < generalItems.length) {
          distributedLatest[i] = generalItems[gi++];
        } else if (ni < newsItems.length) {
          distributedLatest[i] = newsItems[ni++];
        }
      }
      rails.push({
        key: 'latest-series',
        title: '🆕 Recém Adicionadas ao Catálogo',
        items: distributedLatest.filter(Boolean)
      });

      // 3. Obras-Primas da TV (IMDb 8.0+)
      const masterpieceItems = allSeries.filter(s => getHomeRatingInfo(s).value >= 8.0);
      if (masterpieceItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'masterpieces',
          title: '⭐ Obras-Primas da TV (IMDb 8.0+)',
          items: sampleChaoticRailItems(masterpieceItems, railLimit)
        });
      }

      // 4. Novas Temporadas & Lançamentos (2024–2026)
      const currentYear = new Date().getFullYear();
      const newSeasons = allSeries.filter(s => {
        const y = Number(s.year || 0);
        return y >= currentYear - 2;
      });
      if (newSeasons.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'new-seasons',
          title: '🍿 Novas Temporadas & Lançamentos (' + (currentYear - 2) + '–' + currentYear + ')',
          items: sampleChaoticRailItems(newSeasons, railLimit)
        });
      }

      // 5. Mistério, Crime & Investigação
      const crimeKeywords = ['crime', 'suspense', 'investiga', 'detective', 'policia', 'fbi', 'misterio', 'assassin', 'true crime'];
      const crimeItems = allSeries.filter(s => {
        const themes = getHomeThemesForItem(s);
        if (themes.includes('Suspense e Terror')) return true;
        const g = normalizeSearch(s.item?.genre || s.genre || '');
        const t = normalizeSearch(s.title || '');
        return crimeKeywords.some(kw => g.includes(kw) || t.includes(kw));
      });
      if (crimeItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'crime-suspense',
          title: '🕵️ Mistério, Crime & Investigação',
          items: sampleChaoticRailItems(crimeItems, railLimit)
        });
      }

      // 6. Sci-Fi, Distopia & Fantasia Épica
      const scifiItems = allSeries.filter(s => {
        const themes = getHomeThemesForItem(s);
        if (themes.includes('Ficção Científica') || themes.includes('Fantasia')) return true;
        const g = normalizeSearch(s.item?.genre || s.genre || '');
        return g.includes('ficcao') || g.includes('sci-fi') || g.includes('fantasia');
      });
      if (scifiItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'scifi-fantasy',
          title: '🚀 Sci-Fi, Distopia & Fantasia Épica',
          items: sampleChaoticRailItems(scifiItems, railLimit)
        });
      }

      // 7. Sitcoms & Comédias para Relaxar
      const comedyItems = allSeries.filter(s => {
        const themes = getHomeThemesForItem(s);
        if (themes.includes('Comédia')) return true;
        const g = normalizeSearch(s.item?.genre || s.genre || '');
        return g.includes('comedia') || g.includes('sitcom');
      });
      if (comedyItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'comedy-sitcoms',
          title: '😂 Sitcoms & Comédias para Relaxar',
          items: sampleChaoticRailItems(comedyItems, railLimit)
        });
      }

      // 8. Minisséries & Grandes Produções Dramáticas
      const miniseriesItems = allSeries.filter(s => {
        const g = normalizeSearch(s.item?.genre || s.genre || '');
        const t = normalizeSearch(s.title || '');
        return g.includes('minisserie') || t.includes('minisserie') || t.includes('limited series') || getHomeThemesForItem(s).includes('Drama e Romance');
      });
      if (miniseriesItems.length >= HOME_THEME_MIN_ITEMS) {
        rails.push({
          key: 'miniseries-drama',
          title: '👑 Minisséries & Grandes Histórias',
          items: sampleChaoticRailItems(miniseriesItems, railLimit)
        });
      }

      return rails;
    }

    function renderSeriesCuratedRails() {
      if (!elements.seriesCuratedRails) return;
      elements.seriesCuratedRails.innerHTML = '';
      const rails = buildSeriesHubCuratedRails();
      rails.forEach(rail => {
        const block = renderHomeRailBlock(rail, 'series', new Map());
        if (block) {
          elements.seriesCuratedRails.appendChild(block);
        }
      });
    }

    function pickSurpriseSeries() {
      const candidates = (fullSeriesCache || []).filter(item => {
        if (isAdultItem(item)) return false;
        const rating = Number(item.rating || 0);
        return rating >= 7.8 || (item.year && Number(item.year) >= 2024);
      });
      const pool = candidates.length > 0 ? candidates : (fullSeriesCache || []).filter(item => !isAdultItem(item));
      if (!pool.length) return;
      const chosen = pool[Math.floor(Math.random() * pool.length)];
      openSeriesPage(chosen);
    }

    function applySeriesFiltersAndSort() {
      hideLoading();
      let list = (fullSeriesCache || []).slice();

      const selectedCat = elements.categorySelect?.value;
      const isExplicitAdultCategory = selectedCat && selectedCat !== 'ALL' && isAdultCategoryId(selectedCat);

      if (selectedCat && selectedCat !== 'ALL') {
        list = list.filter(item => {
          if (String(item.category_id) === String(selectedCat)) return true;
          if (Array.isArray(item.category_ids) && item.category_ids.some(c => String(c) === String(selectedCat))) return true;
          return false;
        });
      } else {
        list = list.filter(item => !isAdultItem(item));
      }

      if (!isExplicitAdultCategory) {
        list = list.filter(item => !isAdultItem(item));
      }

      if (currentSeriesGenreFilter !== 'ALL') {
        if (currentSeriesGenreFilter === 'TOP_RATED') {
          list = list.filter(s => Number(s.rating || 0) >= 8.0);
        } else if (currentSeriesGenreFilter === 'RECENT_ADDED') {
          list = list.sort((a, b) => getHomeItemTime(b) - getHomeItemTime(a));
        } else if (currentSeriesGenreFilter === 'NEW_RELEASES') {
          const currentYear = new Date().getFullYear();
          list = list.filter(s => Number(s.year || 0) >= currentYear - 2);
        } else if (currentSeriesGenreFilter === 'STREAMING_HBO') {
          const hboKeywords = ['hbo', 'max', 'game of thrones', 'house of the dragon', 'the last of us', 'succession', 'white lotus', 'euphoria', 'true detective', 'chernobyl', 'sopranos', 'the wire'];
          list = list.filter(s => {
            const t = normalizeSearch(s.name || s.title || '');
            const g = normalizeSearch(s.genre || '');
            return hboKeywords.some(k => t.includes(k) || g.includes(k));
          });
        } else if (currentSeriesGenreFilter === 'STREAMING_NETFLIX') {
          const netflixKeywords = ['stranger things', 'bridgerton', 'wandinha', 'wednesday', 'the crown', 'black mirror', 'dark', 'ozark', 'narcos', 'squid game', 'round 6', 'the boys', 'fallout', 'reacher'];
          list = list.filter(s => {
            const t = normalizeSearch(s.name || s.title || '');
            const g = normalizeSearch(s.genre || '');
            return netflixKeywords.some(k => t.includes(k) || g.includes(k));
          });
        } else if (currentSeriesGenreFilter === 'CRIME_SUSPENSE') {
          list = list.filter(s => {
            const g = normalizeSearch(s.genre || '');
            return g.includes('crime') || g.includes('suspense') || g.includes('policial') || g.includes('misterio');
          });
        } else if (currentSeriesGenreFilter === 'SCIFI_FANTASY') {
          list = list.filter(s => {
            const g = normalizeSearch(s.genre || '');
            return g.includes('ficcao') || g.includes('sci-fi') || g.includes('fantasia');
          });
        } else if (currentSeriesGenreFilter === 'COMEDY') {
          list = list.filter(s => {
            const g = normalizeSearch(s.genre || '');
            return g.includes('comedia') || g.includes('sitcom');
          });
        } else if (currentSeriesGenreFilter === 'MINISERIES') {
          list = list.filter(s => {
            const g = normalizeSearch(s.genre || '');
            const t = normalizeSearch(s.name || s.title || '');
            return g.includes('minisserie') || t.includes('minisserie') || g.includes('drama');
          });
        } else if (currentSeriesGenreFilter === 'ANIMATION') {
          list = list.filter(s => {
            const g = normalizeSearch(s.genre || '');
            return g.includes('animacao') || g.includes('anime');
          });
        }
      }

      if (seriesFilterDub) {
        list = list.filter(s => s.versions ? s.versions.some(v => v.versionInfo.type === 'dublado') : true);
      }
      if (seriesFilterLeg) {
        list = list.filter(s => s.versions ? s.versions.some(v => v.versionInfo.type === 'legendado') : /\[\s*L\s*\]/i.test(s.name || s.title || ''));
      }

      const query = normalizeSearch(elements.searchInput?.value || '');
      if (query) {
        list = list.filter(item => matchesSearchQuery(item, query));
      }

      if (seriesSortBy === 'added') {
        list.sort((a, b) => getHomeItemTime(b) - getHomeItemTime(a));
      } else if (seriesSortBy === 'year') {
        list.sort((a, b) => Number(b.year || 0) - Number(a.year || 0));
      } else if (seriesSortBy === 'rating') {
        list.sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0));
      } else if (seriesSortBy === 'alpha') {
        list.sort((a, b) => (a.name || a.title || '').localeCompare(b.name || b.title || ''));
      }

      currentFilteredList = list;
      elements.mediaGrid.innerHTML = '';
      renderedCount = 0;
      updateCountDisplay();

      if (currentFilteredList.length === 0) {
        elements.mediaGrid.innerHTML =
          '<div style="grid-column: 1 / -1; text-align: center; color: #888; padding: 40px 20px;">' +
            '<p style="font-size: 16px; margin-bottom: 8px;">Nenhuma série encontrada com os filtros selecionados.</p>' +
            '<p style="font-size: 13px; color: #555;">Tente desmarcar filtros ou escolher outro gênero.</p>' +
          '</div>';
        elements.loadMoreContainer.style.display = 'none';
        return;
      }

      renderNextBatch();
    }

    function renderSeriesHub() {
      if (currentMode !== 'series' || isWatchedView || isFavoritesView) {
        if (elements.seriesHub) elements.seriesHub.style.display = 'none';
        if (elements.liveHub) elements.liveHub.style.display = 'none';
        return;
      }

      initSeriesHub();
      if (elements.moviesHub) elements.moviesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (elements.seriesHub) elements.seriesHub.style.display = 'block';

      renderSeriesHero();
      renderSeriesGenrePills();

      if (elements.searchInput?.value?.trim()) {
        if (elements.seriesHero) elements.seriesHero.style.display = 'none';
        if (elements.seriesGenrePills) elements.seriesGenrePills.style.display = 'none';
      } else {
        if (elements.seriesHero) elements.seriesHero.style.removeProperty('display');
        if (elements.seriesGenrePills) elements.seriesGenrePills.style.removeProperty('display');
      }

      const inCurated = isSeriesCuratedMode && (!elements.categorySelect || elements.categorySelect.value === 'ALL') && currentSeriesGenreFilter === 'ALL' && !elements.searchInput.value && !seriesFilterDub && !seriesFilterLeg && seriesSortBy === 'featured';

      if (inCurated) {
        elements.seriesCuratedRails.style.display = 'flex';
        elements.mediaGrid.style.display = 'none';
        elements.loadMoreContainer.style.display = 'none';
        document.querySelector('.status-bar')?.style.setProperty('display', 'none');
        elements.seriesViewCuratedBtn?.classList.add('active');
        elements.seriesViewGridBtn?.classList.remove('active');
        renderSeriesCuratedRails();
      } else {
        elements.seriesCuratedRails.style.display = 'none';
        elements.mediaGrid.style.removeProperty('display');
        document.querySelector('.status-bar')?.style.removeProperty('display');
        elements.seriesViewCuratedBtn?.classList.remove('active');
        elements.seriesViewGridBtn?.classList.add('active');
        applySeriesFiltersAndSort();
      }
    }

function showHome(targetScroll = 0) {
      hideLoading();
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      if (currentMode === 'live') {
        liveLoadGeneration++;
        window.EPlayTvEpg?.deactivate();
      }
      if (elements.moviesHub) elements.moviesHub.style.display = 'none'; if (elements.seriesHub) elements.seriesHub.style.display = 'none'; if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (moviesHeroTimer) {
        clearInterval(moviesHeroTimer);
        moviesHeroTimer = null;
      }
      if (seriesHeroTimer) {
        clearInterval(seriesHeroTimer);
        seriesHeroTimer = null;
      }
      if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
      if (elements.loadMoreContainer) elements.loadMoreContainer.style.removeProperty('display');
      isWatchedView = false;
      isFavoritesView = false;
      contentPageOpen = false;
      currentContentPageType = '';
      currentContentPageItem = null;
      contentPageReturnState = null;
      elements.contentPage?.classList.remove('is-active');
      if (elements.contentPage) {
        elements.contentPage.hidden = true;
        elements.contentPage.style.display = 'none';
        if (elements.contentPageBackdrop) elements.contentPageBackdrop.style.backgroundImage = 'none';
        if (elements.contentPagePoster) elements.contentPagePoster.src = '';
        if (elements.contentMovieVersions) elements.contentMovieVersions.innerHTML = '';
        if (elements.contentEpisodesList) elements.contentEpisodesList.innerHTML = '';
      }
      if (elements.mediaGrid) {
        elements.mediaGrid.innerHTML = '';
      }
      currentMode = 'home';
      setRouteHash('#/inicio');
      document.title = 'EPlay - Filmes, Séries & TV Ao Vivo';
      document.querySelectorAll('.nav-tabs .nav-tab').forEach(button => button.classList.remove('active'));
      elements.tabHomeBtn?.classList.add('active');
      elements.tabFavoritesBtn?.classList.remove('active');
      elements.tabUserBtn?.classList.remove('active');
      elements.accountBtn?.classList.remove('active');
      document.querySelectorAll('.mobile-bottom-nav button').forEach(button => button.classList.remove('active'));
      elements.mobileHomeBtn?.classList.add('active');

      if (elements.userDashboard) elements.userDashboard.style.display = 'none';
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'none';
      if (elements.watchedHub) elements.watchedHub.style.display = 'none';

      globalSearchRequestId++;
      if (elements.searchInput) elements.searchInput.value = '';
      setHomeDashboardVisible(true);
      document.querySelector('.status-bar')?.style.setProperty('display', 'none');
      document.querySelector('main')?.style.setProperty('display', 'none');
      if (elements.categorySelect) {
        elements.categorySelect.disabled = true;
        elements.categorySelect.style.display = 'none';
      }
      startViewTransition();
      window.scrollTo({ top: typeof targetScroll === 'number' ? targetScroll : 0, behavior: 'instant' });
      const hasData = Array.isArray(fullMoviesCache) && fullMoviesCache.length > 0 &&
                      Array.isArray(fullSeriesCache) && fullSeriesCache.length > 0;
      const isAlreadyRendered = Boolean(
        (elements.homeSeriesRails && elements.homeSeriesRails.children.length > 0) ||
        (elements.homeMoviesRails && elements.homeMoviesRails.children.length > 0)
      );

      if (hasData && isAlreadyRendered) {
        showHomeGridLoading(false);
        if (elements.homeWatchedSection && (getWatchedIds('movies').length || getWatchedIds('series').length)) {
          elements.homeWatchedSection.style.removeProperty('display');
        }
        if (elements.homeRecommendationsSection) elements.homeRecommendationsSection.style.removeProperty('display');
        if (elements.homeSeriesSection) elements.homeSeriesSection.style.removeProperty('display');
        if (elements.homeMoviesSection) elements.homeMoviesSection.style.removeProperty('display');
        renderHomeWatched();
        startHomeFeaturedTimer();
        loadHomeDashboardData().catch(error => console.warn('[EPlay Home] Catálogo:', error));
        return;
      }

      if (hasData && !isAlreadyRendered) {
        showHomeGridLoading(false);
        renderHomeDashboard();
        startHomeFeaturedTimer();
        if (typeof targetScroll === 'number' && targetScroll > 0) {
          window.scrollTo({ top: targetScroll, behavior: 'instant' });
        }
        loadHomeDashboardData().catch(error => console.warn('[EPlay Home] Catálogo:', error));
        return;
      }

      showHomeGridLoading(true);
      loadHomeDashboardData()
        .then(() => {
          if (currentMode !== 'home') return;
          showHomeGridLoading(false);
          renderHomeDashboard();
          startHomeFeaturedTimer();
          if (typeof targetScroll === 'number' && targetScroll > 0) {
            window.scrollTo({ top: targetScroll, behavior: 'instant' });
          }
        })
        .catch(error => {
          console.warn('[EPlay Home] Catálogo:', error);
          if (currentMode !== 'home') return;
          showHomeGridLoading(false);
          renderHomeDashboard();
          startHomeFeaturedTimer();
        });
    }

    // ==========================================
    // CONTROLE DE ABAS (FILMES / SÉRIES / AO VIVO)
    // ==========================================
    async function switchMode(mode, forceReload = false) {
      hideLoading();
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      if (mode === 'home') {
        showHome();
        return;
      }
      if (currentMode === 'live' && mode !== 'live') {
        liveLoadGeneration++;
        window.EPlayTvEpg?.deactivate();
      }
      if (mode === 'live') {
        window.EPlayTvEpg?.activate();
      }
      if (mode !== 'movies') {
        if (elements.moviesHub) elements.moviesHub.style.display = 'none'; if (elements.seriesHub) elements.seriesHub.style.display = 'none';
        if (moviesHeroTimer) {
          clearInterval(moviesHeroTimer);
          moviesHeroTimer = null;
        }
        if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
        if (elements.loadMoreContainer) elements.loadMoreContainer.style.removeProperty('display');
      }
      clearInterval(homeFeaturedTimer);
      homeFeaturedTimer = null;
      if (currentMode === mode && !forceReload && !isWatchedView && !isFavoritesView && !contentPageOpen) return;
      isWatchedView = false;
      isFavoritesView = false;
      elements.tabWatchedBtn?.classList.remove('active');
      elements.tabFavoritesBtn?.classList.remove('active');
      elements.tabHomeBtn?.classList.remove('active');
      elements.tabUserBtn?.classList.remove('active');
      elements.accountBtn?.classList.remove('active');
      if (elements.userDashboard) elements.userDashboard.style.display = 'none';
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'none';
      if (elements.watchedHub) elements.watchedHub.style.display = 'none';
      elements.categorySelect.disabled = false;
      elements.categorySelect.style.removeProperty('display');
      setHomeDashboardVisible(false);
      elements.contentPage?.classList.remove('is-active');
      if (elements.contentPage) {
        elements.contentPage.hidden = true;
        elements.contentPage.style.display = 'none';
        if (elements.contentPageBackdrop) elements.contentPageBackdrop.style.backgroundImage = 'none';
        if (elements.contentPagePoster) elements.contentPagePoster.src = '';
        if (elements.contentMovieVersions) elements.contentMovieVersions.innerHTML = '';
        if (elements.contentEpisodesList) elements.contentEpisodesList.innerHTML = '';
      }
      if (currentMode !== mode && elements.mediaGrid) {
        elements.mediaGrid.innerHTML = '';
      }
      contentPageOpen = false;
      currentContentPageType = '';
      currentContentPageItem = null;
      contentPageReturnState = null;
      document.querySelector('.status-bar')?.style.removeProperty('display');
      document.querySelector('main')?.style.removeProperty('display');
      if (mode !== 'movies' && mode !== 'series') {
        if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
        if (elements.loadMoreContainer) elements.loadMoreContainer.style.removeProperty('display');
      }
      if (mode !== 'series') {
        if (elements.seriesHub) elements.seriesHub.style.display = 'none';
        if (seriesHeroTimer) {
          clearInterval(seriesHeroTimer);
          seriesHeroTimer = null;
        }
      }
      if (mode !== 'live') {
        if (elements.liveHub) elements.liveHub.style.display = 'none';
      }
      currentMode = mode;
      if (mode === 'movies') {
        setRouteHash('#/filmes');
        document.title = 'Filmes - EPlay';
      } else if (mode === 'series') {
        setRouteHash('#/series');
        document.title = 'Séries - EPlay';
      } else if (mode === 'live') {
        setRouteHash('#/tv');
        document.title = 'TV Ao Vivo - EPlay';
      }

      elements.tabMoviesBtn.classList.toggle('active', mode === 'movies');
      elements.tabSeriesBtn.classList.toggle('active', mode === 'series');
      if (elements.tabLiveBtn) {
        elements.tabLiveBtn.classList.toggle('active', mode === 'live');
      }

      document.querySelectorAll('.mobile-bottom-nav button').forEach(button => button.classList.remove('active'));
      if (mode === 'movies') elements.mobileMoviesBtn?.classList.add('active');
      if (mode === 'series') elements.mobileSeriesBtn?.classList.add('active');
      if (mode === 'live') elements.mobileLiveBtn?.classList.add('active');

      if (forceReload) {
        if (searchDebounceTimer) {
          clearTimeout(searchDebounceTimer);
          searchDebounceTimer = null;
        }
        elements.searchInput.value = '';
      }

      startViewTransition();
      window.scrollTo({ top: 0, behavior: 'instant' });
      await yieldToBrowser();

      if (currentMode !== mode || isWatchedView || isFavoritesView) return;

      if (mode === 'movies') {
        elements.searchInput.placeholder = 'Pesquisar filmes por título, gênero, ator ou diretor...';
        if (movieCategories.length === 0) {
          await loadMovieCategories();
          if (currentMode !== mode || isWatchedView || isFavoritesView) return;
        } else {
          populateCategoriesSelect(movieCategories, 'Filmes');
        }
        if (fullMoviesCache) {
          currentMediaList = (fullMoviesCache || []).filter(item => !isAdultItem(item));
          elements.categorySelect.value = 'ALL';
          elements.resetCategoryBtn.style.display = 'none';
          elements.categoryLabel.textContent = 'Catálogo Geral: Todos os Filmes';
          renderMoviesHub();
        } else {
          await loadFullMovies();
          if (currentMode !== mode || isWatchedView || isFavoritesView) return;
          renderMoviesHub();
        }
      } else if (mode === 'series') {
        elements.searchInput.placeholder = 'Pesquisar séries por título, gênero ou elenco...';
        if (seriesCategories.length === 0) {
          await loadSeriesCategories();
          if (currentMode !== mode || isWatchedView || isFavoritesView) return;
        } else {
          populateCategoriesSelect(seriesCategories, 'Séries');
        }
        if (fullSeriesCache) {
          currentMediaList = (fullSeriesCache || []).filter(item => !isAdultItem(item));
          elements.categorySelect.value = 'ALL';
          elements.resetCategoryBtn.style.display = 'none';
          elements.categoryLabel.textContent = 'Catálogo Geral: Todas as Séries';
          renderSeriesHub();
        } else {
          await loadFullSeries();
          if (currentMode !== mode || isWatchedView || isFavoritesView) return;
          if (fullSeriesCache && fullSeriesCache.length > 0) {
            currentMediaList = (fullSeriesCache || []).filter(item => !isAdultItem(item));
            elements.categorySelect.value = 'ALL';
            elements.resetCategoryBtn.style.display = 'none';
            elements.categoryLabel.textContent = 'Catálogo Geral: Todas as Séries';
            renderSeriesHub();
          }
        }
      } else if (mode === 'live') {
        elements.searchInput.placeholder = 'Pesquisar canais, jogos, times, ligas ou programas no ar...';
        populateLiveCategoriesSelect();
        if (fullLiveCache) {
          currentMediaList = (fullLiveCache || []).filter(item => !isAdultItem(item) && getChannelGroupRank(item) !== 6);
          elements.categorySelect.value = 'ALL';
          elements.resetCategoryBtn.style.display = 'none';
          elements.categoryLabel.textContent = 'TV & Jogos Ao Vivo: Todos os Canais e Partidas';
          renderLiveHub();
          fetchLiveChannelsFromApi().catch(() => {});
        } else {
          await loadFullLive();
          if (currentMode !== mode || isWatchedView || isFavoritesView) return;
          if (fullLiveCache && fullLiveCache.length > 0) {
            currentMediaList = (fullLiveCache || []).filter(item => !isAdultItem(item) && getChannelGroupRank(item) !== 6);
            elements.categorySelect.value = 'ALL';
            elements.resetCategoryBtn.style.display = 'none';
            elements.categoryLabel.textContent = 'TV & Jogos Ao Vivo: Todos os Canais e Partidas';
            renderLiveHub();
          }
        }
      }
    }

    // ==========================================
    // HELPER CENTRALIZADO DE API XTREAM
    // Unifica as chamadas repetidas a player_api.php num único lugar,
    // facilitando manutenção, tratamento de erro e futuras melhorias (retry, cache, cancelamento).
    // ==========================================
    async function xtreamApi(action, extraParams = '') {
      const url = `${CONFIG.server}/player_api.php?username=${CONFIG.user}&password=${CONFIG.pass}&action=${action}${extraParams}`;
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), API_TIMEOUT_MS);

      try {
        const res = await fetch(url, { signal: controller.signal });
        if (!res.ok) {
          throw new Error(`Falha na API Xtream (${action}): HTTP ${res.status}`);
        }
        return await res.json();
      } catch (err) {
        if (err && err.name === 'AbortError') {
          throw new Error(`Tempo limite da API Xtream excedido (${API_TIMEOUT_MS / 1000}s).`);
        }
        throw err;
      } finally {
        clearTimeout(timeoutId);
      }
    }

    // ==========================================
    // CATEGORIAS
    // ==========================================
    async function loadMovieCategories() {
      if (movieCategories.length > 0) {
        updateAdultCategoryIds();
        populateCategoriesSelect(movieCategories, 'Filmes');
        return movieCategories;
      }
      if (_loadingMovieCategoriesPromise) return _loadingMovieCategoriesPromise;

      _loadingMovieCategoriesPromise = (async () => {
        try {
          const cached = await readCatalogCache('movie_categories');
          if (Array.isArray(cached) && cached.length > 0) {
            movieCategories = cached;
            updateAdultCategoryIds();
            populateCategoriesSelect(movieCategories, 'Filmes');
          }
          const fresh = await xtreamApi('get_vod_categories');
          movieCategories = Array.isArray(fresh) ? fresh : [];
          updateAdultCategoryIds();
          await writeCatalogCache('movie_categories', movieCategories);
          populateCategoriesSelect(movieCategories, 'Filmes');
          return movieCategories;
        } catch (err) {
          console.error('Erro ao carregar categorias de filmes:', err);
          return [];
        } finally {
          _loadingMovieCategoriesPromise = null;
        }
      })();

      return _loadingMovieCategoriesPromise;
    }

    async function loadSeriesCategories() {
      if (seriesCategories.length > 0) {
        updateAdultCategoryIds();
        populateCategoriesSelect(seriesCategories, 'Séries');
        return seriesCategories;
      }
      if (_loadingSeriesCategoriesPromise) return _loadingSeriesCategoriesPromise;

      _loadingSeriesCategoriesPromise = (async () => {
        try {
          const cached = await readCatalogCache('series_categories');
          if (Array.isArray(cached) && cached.length > 0) {
            seriesCategories = cached;
            updateAdultCategoryIds();
            populateCategoriesSelect(seriesCategories, 'Séries');
          }
          const fresh = await xtreamApi('get_series_categories');
          seriesCategories = Array.isArray(fresh) ? fresh : [];
          updateAdultCategoryIds();
          await writeCatalogCache('series_categories', seriesCategories);
          populateCategoriesSelect(seriesCategories, 'Séries');
          return seriesCategories;
        } catch (err) {
          console.error('Erro ao carregar categorias de séries:', err);
          return [];
        } finally {
          _loadingSeriesCategoriesPromise = null;
        }
      })();

      return _loadingSeriesCategoriesPromise;
    }

    function populateCategoriesSelect(categories, typeLabel) {
      elements.categorySelect.innerHTML = '';

      const optAll = document.createElement('option');
      optAll.value = 'ALL';
      optAll.textContent = `🌟 Todas as ${typeLabel} (Catálogo Completo)`;
      elements.categorySelect.appendChild(optAll);

      // Oculta categorias DEMO / testes e categorias adultas quando desativadas nas preferências
      const sourceCategories = Array.isArray(categories) ? categories : [];
      const adultEnabled = isAdultContentEnabled();
      const filteredCategories = sourceCategories.filter(cat => {
        const name = cat && typeof cat.category_name === 'string' ? cat.category_name : '';
        if (!name || name.toLowerCase().includes('demo')) return false;
        if (!adultEnabled && isAdultCategoryName(name)) return false;
        return true;
      });

      filteredCategories.forEach(cat => {
        const opt = document.createElement('option');
        opt.value = cat.category_id;
        opt.textContent = cat.category_name.replace('⚡', '').trim();
        elements.categorySelect.appendChild(opt);
      });
    }

    // ==========================================
    // CANAIS E TRANSMISSÕES AO VIVO (ZERO ANÚNCIOS)
    // ==========================================
    // CANAIS E TRANSMISSÃ•ES AO VIVO (REI DOS EMBEDS - 328 CANAIS)
    // ==========================================
    const KNOWN_NATIVE_STREAMS = {
      'cazetv': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UCjE8gVqE5lM_x3Z_t_9y0YQ', isEmbed: true },
      'sbt': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UCF8iN2C8q3zH_gY7u9j9L0Q', isEmbed: true },
      'band': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UCoa-D_VfckL3VTwBlp42C6Q', isEmbed: true },
      'bandsp': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UCoa-D_VfckL3VTwBlp42C6Q', isEmbed: true },
      'cultura': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UC5m8nNlE0i714tXWcR6oGzg', isEmbed: true },
      'cultura-brasil': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UC5m8nNlE0i714tXWcR6oGzg', isEmbed: true },
      'jovempannews': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UCvha7sO_rQJkH7p1WJ2x57A', isEmbed: true },
      'cnnbrasil': { name: 'Oficial 1080p (0 Delay)', url: 'https://www.youtube-nocookie.com/embed/live_stream?channel=UCvdwhh_fDyWccR42-rReFPQ', isEmbed: true },
      'recordnews': { name: 'HLS Nativo 1080p (0 Delay)', url: 'https://jmp2.uk/plu-6102e04e9ab1db0007a980a1.m3u8', isEmbed: false },
      'bobesponja': { name: 'HLS Nativo 1080p (0 Delay)', url: 'https://jmp2.uk/plu-62545c0b002f4b0007688b61.m3u8', isEmbed: false },
      'avatar': { name: 'HLS Nativo 1080p (0 Delay)', url: 'https://jmp2.uk/plu-6759eeb1bd523200083b4f29.m3u8', isEmbed: false }
    };

    // ALL_REI_CHANNELS agora é carregado sob demanda de ./channels.json (ver getLiveChannelsData()), não mais embutido aqui.


    function getCategoryInfo(rawCat) {
      if (!rawCat) return { key: 'variety', label: 'Variedades', subCategory: 'Variedades' };
      const c = rawCat.toLowerCase();
      if (c.includes('aberto') || c.includes('aberta')) return { key: 'open_tv', label: 'Abertos', subCategory: 'Canais Abertos' };
      if (c.includes('esporte')) return { key: 'sports', label: 'Esportes', subCategory: 'Esportes' };
      if (c.includes('infantil') || c.includes('desenho')) return { key: 'kids', label: 'Infantil', subCategory: rawCat };
      if (c.includes('24 hora') || c.includes('24h')) return { key: 'channels_24h', label: '24 Horas', subCategory: '24 Horas' };
      if (c.includes('adulto')) return { key: 'other', label: 'Outros', subCategory: 'Adulto' };
      return { key: 'variety', label: 'Variedades', subCategory: rawCat };
    }

    // Algoritmo de ranking e agrupamento unificado com o APK Android
    function getChannelGroupRank(ch) {
      if (!ch) return 6;
      const k = (ch.categoryKey || ch.key || '').toLowerCase();
      const c = (ch.categoryLabel || ch.cat || ch.rawCategory || '').toLowerCase();

      // 1. Abertos
      if (k === 'open_tv' || c.includes('aberto')) return 1;

      // 2. Esportes
      if (k === 'sports' || c.includes('esporte')) return 2;

      // 3. Variedades (unificado com Filmes / Séries / Realitys / Notícias / Doc / Miami)
      if (k === 'variety' || k === 'reality' || k === 'movies' || k === 'news'
          || c.includes('variedade') || c.includes('not') || c.includes('doc')
          || c.includes('rie') || c.includes('serie')
          || c.includes('reality') || c.includes('filme')
          || c.includes('geral') || c.includes('ing') || c.includes('miami')) {
        return 3;
      }

      // 4. Infantil
      if (k === 'kids' || c.includes('infantil') || c.includes('desenho')) return 4;

      // 5. 24hrs
      if (k === 'channels_24h' || c.includes('24')) return 5;

      // 6. Outros
      return 6;
    }

    function extractTrailingNumber(s) {
      if (!s) return -1;
      const matches = s.match(/\d+/g);
      if (!matches) return -1;
      return parseInt(matches[matches.length - 1], 10);
    }

    function getChannelSubRank(ch, groupRank) {
      if (!ch) return 0;
      const id = (ch.channelSlug || ch.id || '').toLowerCase().replace(/^canal\//, '').replace(/\.html$/, '');
      const name = (ch.name || '').toLowerCase();

      if (groupRank === 1) {
        // 1. Globo SP como primeiro canal absoluto (Canal 001)
        if (id === 'globosp' || name.startsWith('globo sp')) return 1;
        // 2. Demais canais GLOBO e derivados (ex.: TV Bahia, TV Asa Branca, TV Anhanguera, etc.)
        if (id.startsWith('globo') || name.includes('globo')
            || ['globoal', 'globoba', 'globoam', 'globodf', 'globogo', 'globomg', 'globoms', 'globors'].includes(id)
            || name.includes('asa branca') || name.includes('bahia')
            || name.includes('anhanguera') || name.includes('morena')
            || name.includes('rbs') || name.includes('amazônica') || name.includes('brasília')) {
          return 10;
        }
        // 3. Band SP
        if (id === 'bandsp' || name.startsWith('band sp') || id === 'band') return 20;
        // 4. Record SP
        if (id === 'recordsp' || name.startsWith('record sp') || id === 'record') return 30;
        // 5. SBT
        if (id === 'sbt' || name === 'sbt') return 40;
        // 6. Demais canais abertos
        return 50;
      }

      if (groupRank === 2) {
        // 1. ESPN
        if (id.startsWith('espn') || name.startsWith('espn')) {
          if (id === 'espn' || name === 'espn') return 101;
          const num = extractTrailingNumber(name || id);
          return (num > 0) ? (100 + num) : 199;
        }
        // 2. SporTV
        if (id.startsWith('sportv') || name.startsWith('sportv')) {
          if (id === 'sportv' || name === 'sportv') return 201;
          const num = extractTrailingNumber(name || id);
          return (num > 0) ? (200 + num) : 299;
        }
        // 3. Premiere
        if (id.startsWith('premiere') || name.startsWith('premiere')) {
          if (name.includes('clubes') || id === 'premiere') return 301;
          const num = extractTrailingNumber(name || id);
          return (num > 0) ? (300 + num) : 399;
        }
        return 400;
      }

      if (groupRank === 3) {
        // A Fazenda vai para o final do grupo Variedades
        if (id.startsWith('afazenda') || name.startsWith('a fazenda')) {
          if (id === 'afazenda' || name === 'a fazenda') return 1001;
          const num = extractTrailingNumber(name || id);
          return (num > 0) ? (1000 + num) : 1099;
        }
        return 0;
      }

      return 0;
    }

    function sortChannelsByGroup(list) {
      if (!Array.isArray(list) || list.length === 0) return;
      list.sort((c1, c2) => {
        const r1 = getChannelGroupRank(c1);
        const r2 = getChannelGroupRank(c2);
        if (r1 !== r2) return r1 - r2;

        const sub1 = getChannelSubRank(c1, r1);
        const sub2 = getChannelSubRank(c2, r2);
        if (sub1 !== sub2) return sub1 - sub2;

        const n1 = c1.name || '';
        const n2 = c2.name || '';
        return n1.localeCompare(n2, 'pt-BR');
      });
      list.forEach((ch, idx) => {
        ch.channelNumber = idx + 1;
        ch.channelNumberFormatted = `CH ${String(idx + 1).padStart(3, '0')}`;
        ch.groupRank = getChannelGroupRank(ch);
      });
    }

    // EPG real agora pertence ao módulo TV e só é ativado quando a área de TV é acessada.
    function buildChannelItem(raw) {
      const catInfo = getCategoryInfo(raw.cat);
      const cleanSlug = raw.id.replace(/^canal\//, '').replace(/\.html$/, '');
      const rdSlug = cleanSlug
        .replace(/^telecine-/, 'telecine')
        .replace(/^hbo-/, 'hbo');
      // RDCanais é o player primário: mais leve, troca de canal mais rápida
      // rdembed.sbs fica como fallback caso rdcanais esteja instável
      const fallbacks = [
        { name: 'HD', url: 'https://rdcanais.net/' + rdSlug, isEmbed: true },
        { name: 'Alternativo', url: raw.embed || ('https://v2.rdembed.sbs/' + rdSlug), isEmbed: true }
      ];

      if (KNOWN_NATIVE_STREAMS[raw.id]) {
        fallbacks.unshift(KNOWN_NATIVE_STREAMS[raw.id]);
      }

      const liveEpg = (typeof getChannelLiveSchedule === 'function')
        ? getChannelLiveSchedule(raw)
        : (typeof window !== 'undefined' && typeof window.getChannelLiveSchedule === 'function'
            ? window.getChannelLiveSchedule(raw)
            : null);
      const nowTitle = (liveEpg && liveEpg.nowTitle) ? liveEpg.nowTitle : (raw.now || 'Programação Ao Vivo');
      const nowProgress = liveEpg ? liveEpg.progress : (Number(raw.prog) || 0);
      const synopsis = liveEpg ? liveEpg.synopsis : '';
      const nextProgrammes = liveEpg && liveEpg.nextTitle ? [{ t: liveEpg.nextTitle, s: liveEpg.nextStart }] : (Array.isArray(raw.next) ? raw.next : []);

      return {
        id: 'live_ch_' + raw.id,
        channelSlug: raw.id,
        name: raw.name,
        categoryKey: catInfo.key,
        categoryLabel: catInfo.label,
        subCategory: catInfo.subCategory,
        rawCategory: raw.cat,
        logo: raw.logo,
        badge: catInfo.key === 'channels_24h' ? '24 HORAS' : 'AO VIVO',
        embedUrl: fallbacks[0].url,
        fallbacks: fallbacks,
        hasGuide: true,
        nowTitle: nowTitle,
        nowProgress: nowProgress,
        synopsis: synopsis,
        nextProgrammes: nextProgrammes,
        isChannel: true
      };
    }

    // Catálogo de canais ao vivo: carregado sob demanda (lazy) de ./channels.json,
    // em vez de embutido no HTML. Isso evita baixar/parsear ~85KB de dados que a
    // maioria das sessões (quem só assiste Filmes/Séries) nunca chega a usar.
    let LIVE_CHANNELS = null;
    let _loadingLiveChannelsPromise = null;
    async function getLiveChannelsData() {
      if (LIVE_CHANNELS) return LIVE_CHANNELS;
      if (_loadingLiveChannelsPromise) return _loadingLiveChannelsPromise;
      _loadingLiveChannelsPromise = fetch('channels.json')
        .then(res => {
          if (!res.ok) throw new Error(`Falha ao carregar channels.json: HTTP ${res.status}`);
          return res.json();
        })
        .then(rawChannels => {
          LIVE_CHANNELS = rawChannels.map(buildChannelItem);
          sortChannelsByGroup(LIVE_CHANNELS);
          return LIVE_CHANNELS;
        })
        .catch(err => {
          console.error('Erro ao carregar catálogo de canais ao vivo:', err);
          LIVE_CHANNELS = [];
          return LIVE_CHANNELS;
        })
        .finally(() => { _loadingLiveChannelsPromise = null; });
      return _loadingLiveChannelsPromise;
    }

    function populateLiveCategoriesSelect() {
      elements.categorySelect.innerHTML = '';

      const adultEnabled = isAdultContentEnabled();
      const liveCategories = [
        { id: 'ALL', name: '📺 Todos os Canais e Jogos (328)' },
        { id: 'JOGOS', name: '⚽ Jogos de Hoje & Transmissões' },
        { id: 'open_tv', name: '📡 Abertos (45)' },
        { id: 'sports', name: '🏆 Esportes (114)' },
        { id: 'variety', name: '🎭 Variedades (130)' },
        { id: 'kids', name: '🧸 Infantil (18)' },
        { id: 'channels_24h', name: '⭐ 24 Horas (10)' }
      ];
      if (adultEnabled) {
        liveCategories.push({ id: 'other', name: '🔞 Outros & Adulto (11)' });
      }

      liveCategories.forEach(cat => {
        const opt = document.createElement('option');
        opt.value = cat.id;
        opt.textContent = cat.name;
        elements.categorySelect.appendChild(opt);
      });
      if (!adultEnabled && (currentLiveCategoryFilter === 'other' || currentLiveCategoryFilter === 'adult')) {
        currentLiveCategoryFilter = 'ALL';
      }
      elements.categorySelect.value = currentLiveCategoryFilter || 'ALL';
    }

    // ==========================================
    // LIVE HUB: PÍLULAS, FILTROS RÁPIDOS & SPOTLIGHT
    // ==========================================
    let liveHubInitialized = false;
    let currentLiveCategoryFilter = 'ALL';
    let currentLiveQuickFilter = 'all'; // 'all' | 'now' | 'matches' | 'channels'

    const LIVE_PILL_CATEGORIES = [
      { id: 'ALL', label: 'Todos (328)', icon: '📺' },
      { id: 'JOGOS', label: 'Jogos Hoje', icon: '⚽' },
      { id: 'open_tv', label: 'Abertos (45)', icon: '📡' },
      { id: 'sports', label: 'Esportes (114)', icon: '🏆' },
      { id: 'variety', label: 'Variedades (130)', icon: '🎭' },
      { id: 'kids', label: 'Infantil (18)', icon: '🧸' },
      { id: 'channels_24h', label: '24 Horas (10)', icon: '⭐' },
      { id: 'other', label: 'Outros (11)', icon: '🔞' }
    ];

    function initLiveHub() {
      if (liveHubInitialized || !elements.liveHub) return;
      liveHubInitialized = true;

      const setFilter = (key) => {
        currentLiveQuickFilter = key;
        updateLiveQuickFiltersUI();
        applyLiveFiltersAndSort();
      };

      elements.liveFilterAllBtn?.addEventListener('click', () => setFilter('all'));
      elements.liveFilterNowBtn?.addEventListener('click', () => setFilter('now'));
      elements.liveFilterMatchesBtn?.addEventListener('click', () => setFilter('matches'));
      elements.liveFilterChannelsBtn?.addEventListener('click', () => setFilter('channels'));
    }

    function updateLiveQuickFiltersUI() {
      const chips = [
        { btn: elements.liveFilterAllBtn, key: 'all' },
        { btn: elements.liveFilterNowBtn, key: 'now' },
        { btn: elements.liveFilterMatchesBtn, key: 'matches' },
        { btn: elements.liveFilterChannelsBtn, key: 'channels' }
      ];
      chips.forEach(({ btn, key }) => {
        if (!btn) return;
        btn.classList.toggle('active', currentLiveQuickFilter === key);
      });
    }

    function renderLiveCategoryPills() {
      if (!elements.liveCategoryPills) return;
      elements.liveCategoryPills.innerHTML = '';

      const adultEnabled = isAdultContentEnabled();
      const pills = LIVE_PILL_CATEGORIES.filter(cat => adultEnabled || (cat.id !== 'other' && cat.id !== 'adult'));

      pills.forEach(cat => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `live-pill ${currentLiveCategoryFilter === cat.id ? 'active' : ''}`;
        btn.dataset.liveCategory = cat.id;
        btn.setAttribute('role', 'tab');
        btn.setAttribute('aria-selected', currentLiveCategoryFilter === cat.id ? 'true' : 'false');
        btn.innerHTML = `<span>${cat.icon}</span> <span>${escapeHtml(cat.label)}</span>`;

        btn.addEventListener('click', () => {
          currentLiveCategoryFilter = cat.id;
          elements.liveCategoryPills.querySelectorAll('.live-pill').forEach(p => {
            const isActive = p.dataset.liveCategory === cat.id;
            p.classList.toggle('active', isActive);
            p.setAttribute('aria-selected', isActive ? 'true' : 'false');
          });

          if (elements.categorySelect) {
            elements.categorySelect.value = cat.id;
          }
          if (elements.resetCategoryBtn) {
            elements.resetCategoryBtn.style.display = (cat.id === 'ALL' ? 'none' : 'inline-flex');
          }

          applyLiveFiltersAndSort();
        });

        elements.liveCategoryPills.appendChild(btn);
      });
    }

    function renderLiveSpotlightRail() {
      if (!elements.liveSpotlightSection || !elements.liveSpotlightTrack) return;
      const matches = (fullLiveCache || []).filter(item => item.isLiveMatch);

      if (matches.length === 0) {
        elements.liveSpotlightSection.style.display = 'none';
        return;
      }

      const sorted = [...matches].sort((a, b) => (b.isLiveNow ? 1 : 0) - (a.isLiveNow ? 1 : 0));

      elements.liveSpotlightTrack.innerHTML = '';
      sorted.forEach(item => {
        const card = document.createElement('div');
        card.className = 'live-spotlight-card';
        card.tabIndex = 0;
        card.setAttribute('role', 'button');
        card.setAttribute('aria-label', `${item.name || 'Partida'} - ${item.league || 'Ao Vivo'}`);

        const homeLogo = getTvTeamLogoUrl(item.homeTeam, item.homeLogo);
        const awayLogo = getTvTeamLogoUrl(item.awayTeam, item.awayLogo);
        const isNow = Boolean(item.isLiveNow);

        card.innerHTML = `
          <div class="live-spotlight-teams">
            <div class="live-spotlight-team">
              ${homeLogo ? `<img class="live-spotlight-logo" src="${escapeHtml(homeLogo)}" alt="${escapeHtml(item.homeTeam || '')}" data-dim-on-error loading="lazy">` : `<span style="font-size:28px">⚽</span>`}
              <span class="live-spotlight-team-name">${escapeHtml(item.homeTeam || 'Mandante')}</span>
            </div>
            <div class="live-spotlight-center">
              <span class="live-spotlight-vs">${isNow ? '🔴 AO VIVO' : 'VS'}</span>
            </div>
            <div class="live-spotlight-team">
              ${awayLogo ? `<img class="live-spotlight-logo" src="${escapeHtml(awayLogo)}" alt="${escapeHtml(item.awayTeam || '')}" data-dim-on-error loading="lazy">` : `<span style="font-size:28px">⚽</span>`}
              <span class="live-spotlight-team-name">${escapeHtml(item.awayTeam || 'Visitante')}</span>
            </div>
          </div>
          <div class="live-spotlight-meta">
            <span class="live-spotlight-league">${escapeHtml(item.league || item.categoryLabel || 'Futebol')}</span>
            <span class="live-spotlight-time">${escapeHtml(item.matchTime || (isNow ? 'No Ar' : 'Hoje'))}</span>
          </div>
        `;

        card.addEventListener('click', () => onLiveItemClick(item));
        card.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onLiveItemClick(item);
          }
        });

        elements.liveSpotlightTrack.appendChild(card);
      });

      enableHomeDragToScroll(elements.liveSpotlightTrack);
      elements.liveSpotlightSection.style.display = 'block';
    }

    function applyLiveFiltersAndSort() {
      if (currentMode !== 'live') return;
      const source = fullLiveCache || [];
      const searchTerm = elements.searchInput ? elements.searchInput.value.trim() : '';
      const q = normalizeSearch(searchTerm);

      // 1. Filtro por categoria com alinhamento aos grupos do APK
      let list = source;
      if (currentLiveCategoryFilter === 'JOGOS') {
        list = list.filter(item => item.isLiveMatch);
      } else if (currentLiveCategoryFilter === 'open_tv') {
        list = list.filter(item => getChannelGroupRank(item) === 1);
      } else if (currentLiveCategoryFilter === 'sports') {
        list = list.filter(item => getChannelGroupRank(item) === 2 || item.isLiveMatch);
      } else if (currentLiveCategoryFilter === 'variety') {
        list = list.filter(item => getChannelGroupRank(item) === 3);
      } else if (currentLiveCategoryFilter === 'kids') {
        list = list.filter(item => getChannelGroupRank(item) === 4);
      } else if (currentLiveCategoryFilter === 'channels_24h') {
        list = list.filter(item => getChannelGroupRank(item) === 5);
      } else if (currentLiveCategoryFilter === 'other' || currentLiveCategoryFilter === 'adult') {
        list = list.filter(item => getChannelGroupRank(item) === 6 || isAdultItem(item));
      } else if (currentLiveCategoryFilter !== 'ALL') {
        list = list.filter(item => item.categoryKey === currentLiveCategoryFilter || String(item.category_id) === String(currentLiveCategoryFilter));
      }

      // Se não estiver explicitamente na categoria adulta ('other' ou 'adult'), nunca exibe canais adultos no geral ou na busca
      if (currentLiveCategoryFilter !== 'other' && currentLiveCategoryFilter !== 'adult') {
        list = list.filter(item => getChannelGroupRank(item) !== 6 && !isAdultItem(item));
      }

      // 2. Filtro rápido de exibição
      if (currentLiveQuickFilter === 'now') {
        list = list.filter(item => item.isLiveNow || (item.nowTitle && item.nowTitle !== 'Programação Indisponível' && item.nowTitle !== 'Carregando guia...'));
      } else if (currentLiveQuickFilter === 'matches') {
        list = list.filter(item => item.isLiveMatch);
      } else if (currentLiveQuickFilter === 'channels') {
        list = list.filter(item => !item.isLiveMatch || item.isChannel);
      }

      // 3. Filtro textual contextual
      if (q) {
        list = list.filter(item => matchesSearchQuery(item, q));
      }

      // 4. Ordenação inteligente mantendo o padrão numérico do APK
      list.sort((a, b) => {
        // Se ambos forem partidas: ao vivo primeiro
        if (a.isLiveMatch && b.isLiveMatch) {
          return (b.isLiveNow ? 1 : 0) - (a.isLiveNow ? 1 : 0);
        }
        // Se ambos forem canais: ordenação pelos números correspondentes do APK
        if (a.channelNumber && b.channelNumber) {
          return a.channelNumber - b.channelNumber;
        }
        // Se partida ao vivo vs canal
        if (a.isLiveMatch && a.isLiveNow) return -1;
        if (b.isLiveMatch && b.isLiveNow) return 1;
        if (a.channelNumber) return -1;
        if (b.channelNumber) return 1;
        return 0;
      });

      // 5. Atualizar trilho de destaque (Live Spotlight)
      if (elements.liveSpotlightSection && elements.liveSpotlightTrack) {
        if (!q && (currentLiveCategoryFilter === 'ALL' || currentLiveCategoryFilter === 'JOGOS' || currentLiveCategoryFilter === 'sports' || currentLiveQuickFilter === 'matches')) {
          renderLiveSpotlightRail();
        } else {
          elements.liveSpotlightSection.style.display = 'none';
        }
      }

      // 6. Atualizar Grid de Mídia
      currentFilteredList = list;
      renderedCount = 0;
      elements.mediaGrid.innerHTML = '';

      if (list.length === 0) {
        elements.mediaGrid.innerHTML = `
          <div style="grid-column: 1/-1; text-align: center; padding: 48px 16px; color: #94a3b8;">
            <span style="font-size: 40px; display: block; margin-bottom: 12px;">📡</span>
            <strong style="color: #f1f5f9; font-size: 16px; display: block; margin-bottom: 6px;">Nenhum canal ou jogo encontrado</strong>
            <p style="font-size: 13px; max-width: 420px; margin: 0 auto;">Tente buscar por outro termo (ex: futebol, filme, SporTV, Flamengo) ou ajustar as categorias.</p>
          </div>
        `;
        elements.loadMoreContainer.style.display = 'none';
      } else {
        renderNextBatch();
      }

      // Atualiza contador e rótulo
      updateCountDisplay();
      if (elements.categoryLabel) {
        const catObj = LIVE_PILL_CATEGORIES.find(c => c.id === currentLiveCategoryFilter);
        const catName = catObj ? `${catObj.icon} ${catObj.label}` : 'Todos os Canais';
        if (q) {
          elements.categoryLabel.textContent = `Busca em Ao Vivo: "${searchTerm}" (${list.length})`;
        } else if (currentLiveCategoryFilter === 'ALL') {
          elements.categoryLabel.textContent = 'TV & Jogos Ao Vivo: Todos os Canais e Partidas';
        } else {
          elements.categoryLabel.textContent = `Categoria: ${catName}`;
        }
      }
    }

    function renderLiveHub() {
      if (currentMode !== 'live' || isWatchedView || isFavoritesView) {
        if (elements.liveHub) elements.liveHub.style.display = 'none';
        return;
      }

      initLiveHub();
      if (elements.moviesHub) elements.moviesHub.style.display = 'none';
      if (elements.seriesHub) elements.seriesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'block';

      renderLiveCategoryPills();
      updateLiveQuickFiltersUI();
      applyLiveFiltersAndSort();
    }

    async function fetchLiveSportsEvents() {
      const events = [];
      const API = 'https://api.reidoscanais.st';

      // 1. Busca todos os eventos esportivos da API oficial (ao vivo + jogos de hoje)
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 4000);
        const res = await fetch(`${API}/sports`, {
          signal: controller.signal,
          headers: { 'Accept': 'application/json' }
        });
        clearTimeout(timeoutId);

        if (res.ok) {
          const json = await res.json();
          const list = Array.isArray(json.data) ? json.data : (Array.isArray(json) ? json : []);
          list.forEach(ev => {
            const hasTeams = Boolean(ev.teams && (ev.teams.home || ev.teams.away));
            const homeName = (ev.teams && ev.teams.home && ev.teams.home.name) ? ev.teams.home.name : '';
            const awayName = (ev.teams && ev.teams.away && ev.teams.away.name) ? ev.teams.away.name : '';
            const title = ev.title || (hasTeams ? `${homeName} x ${awayName}` : 'Evento Esportivo');
            if (!title) return;

            const homeTeam = homeName || title.split(/ x | vs /i)[0]?.trim() || 'Time 1';
            const awayTeam = awayName || title.split(/ x | vs /i)[1]?.trim() || 'Time 2';
            const homeLogo = (ev.teams && ev.teams.home && ev.teams.home.logo) ? ev.teams.home.logo : '';
            const awayLogo = (ev.teams && ev.teams.away && ev.teams.away.logo) ? ev.teams.away.logo : '';
            const poster = ev.poster || '';

            const fallbacks = [];
            if (Array.isArray(ev.embeds)) {
              ev.embeds.forEach((emb, i) => {
                const embUrl = emb.embed_url || '';
                if (embUrl) {
                  fallbacks.push({
                    name: emb.provider || emb.quality || `Opção ${i + 1}`,
                    url: embUrl,
                    isEmbed: true
                  });
                }
              });
            }

            // Fallback garantido com RDCanais HD (sem anúncios e 100% estável)
            const catLower = `${ev.category || ''} ${ev.competition || ''} ${title}`.toLowerCase();
            if (catLower.includes('ufc') || catLower.includes('mma') || catLower.includes('luta') || catLower.includes('boxe')) {
              fallbacks.push({ name: 'Combate HD', url: 'https://rdcanais.net/combate', isEmbed: true });
            } else if (catLower.includes('premier') || catLower.includes('champions') || catLower.includes('europa') || catLower.includes('la liga') || catLower.includes('espanh') || catLower.includes('portugal') || catLower.includes('ingl')) {
              fallbacks.push({ name: 'ESPN HD', url: 'https://rdcanais.net/espn', isEmbed: true });
              fallbacks.push({ name: 'TNT Sports', url: 'https://rdcanais.net/tnt', isEmbed: true });
            } else {
              fallbacks.push({ name: 'Premiere HD', url: 'https://rdcanais.net/premiere', isEmbed: true });
              fallbacks.push({ name: 'SporTV HD', url: 'https://rdcanais.net/sportv', isEmbed: true });
            }

            const isLive = ev.status === 'live';
            let matchTime = isLive ? 'AO VIVO' : 'EM BREVE';
            if (ev.start_timestamp) {
              const d = new Date(ev.start_timestamp * 1000);
              matchTime = isLive ? 'AO VIVO' : d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
            } else if (ev.start_time) {
              matchTime = isLive ? 'AO VIVO' : (ev.start_time.slice(11, 16) || 'EM BREVE');
            }

            events.push({
              id: `live_ev_${ev.id || Math.random().toString(36).substring(2, 9)}`,
              name: title,
              homeTeam: homeTeam,
              awayTeam: awayTeam,
              homeLogo: homeLogo,
              awayLogo: awayLogo,
              poster: poster,
              hasTeams: hasTeams,
              matchTime: matchTime,
              league: ev.competition || ev.category || 'Futebol Ao Vivo',
              categoryKey: 'JOGOS',
              categoryLabel: 'Jogos de Hoje',
              isLiveMatch: true,
              isLiveNow: isLive,
              fallbacks: fallbacks
            });
          });
        }
      } catch (err) {
        console.log('api.reidoscanais.st/sports:', err.message);
      }

      // Ordena: partidas ao vivo no topo, seguidas pelas de hoje
      events.sort((a, b) => (b.isLiveNow ? 1 : 0) - (a.isLiveNow ? 1 : 0));

      // 2. Grade de contingência com logos reais e canais RDCanais (se API offline ou sem dados)
      if (events.length === 0) {
        events.push(
          {
            id: 'match_br_1',
            name: 'Grêmio x Palmeiras',
            homeTeam: 'Grêmio',
            awayTeam: 'Palmeiras',
            homeLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/9769.png',
            awayLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/10283.png',
            poster: '',
            hasTeams: true,
            matchTime: 'AO VIVO',
            league: 'Brasileirão Série A',
            categoryKey: 'JOGOS',
            categoryLabel: 'Jogos de Hoje',
            isLiveMatch: true,
            isLiveNow: true,
            fallbacks: [
              { name: 'Premiere HD', url: 'https://rdcanais.net/premiere', isEmbed: true },
              { name: 'SporTV HD', url: 'https://rdcanais.net/sportv', isEmbed: true }
            ]
          },
          {
            id: 'match_br_2',
            name: 'Palmeiras x Flamengo',
            homeTeam: 'Palmeiras',
            awayTeam: 'Flamengo',
            homeLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/10283.png',
            awayLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/9770.png',
            poster: '',
            hasTeams: true,
            matchTime: 'AO VIVO',
            league: 'Brasileirão Série A',
            categoryKey: 'JOGOS',
            categoryLabel: 'Jogos de Hoje',
            isLiveMatch: true,
            isLiveNow: true,
            fallbacks: [
              { name: 'Premiere HD', url: 'https://rdcanais.net/premiere', isEmbed: true },
              { name: 'SporTV HD', url: 'https://rdcanais.net/sportv', isEmbed: true }
            ]
          },
          {
            id: 'match_br_3',
            name: 'Corinthians x São Paulo',
            homeTeam: 'Corinthians',
            awayTeam: 'São Paulo',
            homeLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/9808.png',
            awayLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/10277.png',
            poster: '',
            hasTeams: true,
            matchTime: 'AO VIVO',
            league: 'Brasileirão Série A',
            categoryKey: 'JOGOS',
            categoryLabel: 'Jogos de Hoje',
            isLiveMatch: true,
            isLiveNow: true,
            fallbacks: [
              { name: 'Premiere 2', url: 'https://rdcanais.net/premiere2', isEmbed: true },
              { name: 'SporTV 2', url: 'https://rdcanais.net/sportv2', isEmbed: true }
            ]
          },
          {
            id: 'match_br_4',
            name: 'Manchester City x Sunderland',
            homeTeam: 'Manchester City',
            awayTeam: 'Sunderland',
            homeLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/8456.png',
            awayLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/8472.png',
            poster: '',
            hasTeams: true,
            matchTime: '10:00',
            league: 'Premier League',
            categoryKey: 'JOGOS',
            categoryLabel: 'Jogos de Hoje',
            isLiveMatch: true,
            isLiveNow: false,
            fallbacks: [
              { name: 'ESPN HD', url: 'https://rdcanais.net/espn', isEmbed: true },
              { name: 'TNT Sports', url: 'https://rdcanais.net/tnt', isEmbed: true }
            ]
          },
          {
            id: 'match_br_5',
            name: 'Real Madrid x Barcelona',
            homeTeam: 'Real Madrid',
            awayTeam: 'Barcelona',
            homeLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/8633.png',
            awayLogo: 'https://images.fotmob.com/image_resources/logo/teamlogo/8634.png',
            poster: '',
            hasTeams: true,
            matchTime: '16:00',
            league: 'La Liga / Champions League',
            categoryKey: 'JOGOS',
            categoryLabel: 'Jogos de Hoje',
            isLiveMatch: true,
            isLiveNow: false,
            fallbacks: [
              { name: 'ESPN HD', url: 'https://rdcanais.net/espn', isEmbed: true },
              { name: 'TNT Sports', url: 'https://rdcanais.net/tnt', isEmbed: true }
            ]
          }
        );
      }

      return events;
    }

    async function fetchLiveChannelsFromApi() {
      if (currentMode !== 'live' && !document.body.classList.contains('tv-mode')) return;
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        const res = await fetch('https://api.reidoscanais.st/channels', {
          signal: controller.signal,
          headers: { 'Accept': 'application/json' }
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const json = await res.json();
          if (json && json.success && Array.isArray(json.data) && fullLiveCache) {
            const map = new Map(json.data.map(c => [c.id, c]));
            let updated = false;
            fullLiveCache.forEach(item => {
              if (item.channelSlug && map.has(item.channelSlug)) {
                const fresh = map.get(item.channelSlug);
                if (fresh.epg && fresh.epg.current) {
                  item.nowTitle = fresh.epg.current.title || item.nowTitle;
                  item._searchableText = null;
                  if (fresh.epg.current.start_time && fresh.epg.current.end_time) {
                    const elapsed = Date.now() / 1000 - fresh.epg.current.start_time;
                    const duration = fresh.epg.current.end_time - fresh.epg.current.start_time;
                    item.nowProgress = duration > 0 ? Math.round((elapsed / duration) * 100) : 0;
                  }
                  if (fresh.epg.next) item.nextProgrammes = [{ t: fresh.epg.next.title, s: fresh.epg.next.start_time }];
                }
                if (Array.isArray(fresh.embeds) && fresh.embeds.length > 0 && item.fallbacks) {
                  const apiEmbeds = fresh.embeds.filter(e => e.embed_url).map(e => ({ name: e.provider || e.quality || 'HD', url: e.embed_url, isEmbed: true }));
                  if (apiEmbeds.length > 0) item.fallbacks = apiEmbeds;
                }
                updated = true;
              }
            });
            if (updated && currentMode === 'live') {
              if (!document.body.classList.contains('tv-mode')) {
                applyLiveFiltersAndSort();
              } else {
                applyFilterAndRender(elements.searchInput.value);
              }
            }
          }
        }
      } catch (err) { /* silencioso */ }
    }

    async function fetchXtreamLiveStreams() {
      try {
        const raw = await xtreamApi('get_live_streams');
        if (!Array.isArray(raw) || raw.length === 0) return [];

        return raw.map(s => ({
          id: `xtream_live_${s.stream_id}`,
          name: s.name || 'Canal Ao Vivo',
          categoryKey: 'sports',
          categoryLabel: 'Canais Xtream',
          logo: s.stream_icon || '',
          badge: 'SINAL XTREAM',
          hlsUrl: `${CONFIG.server}/live/${CONFIG.user}/${CONFIG.pass}/${s.stream_id}.m3u8`,
          fallbacks: [
            { name: 'Nativo HLS (0 Anúncios)', url: `${CONFIG.server}/live/${CONFIG.user}/${CONFIG.pass}/${s.stream_id}.m3u8`, isEmbed: false },
            { name: 'TS Stream', url: `${CONFIG.server}/live/${CONFIG.user}/${CONFIG.pass}/${s.stream_id}.ts`, isEmbed: false }
          ],
          isChannel: true
        }));
      } catch (e) {
        return [];
      }
    }

    async function loadFullLive() {
      const loadGeneration = ++liveLoadGeneration;
      const liveAreaActive = currentMode === 'live' || document.body.classList.contains('tv-mode');
      if (!liveAreaActive) return fullLiveCache || [];
      window.EPlayTvEpg?.activate();
      let hasPersistentCache = false;
      try {
        const cached = await readCatalogCache('live');
        if (Array.isArray(cached) && cached.length > 0) {
          fullLiveCache = cached;
          currentMediaList = fullLiveCache;
          hasPersistentCache = true;
          if (currentMode === 'live' && !document.body.classList.contains('tv-mode')) {
            renderLiveHub();
          }
        }
      } catch (e) {}

      if (!hasPersistentCache) {
        showLoading('Carregando TV Ao Vivo, canais 24H e transmissões de esportes...');
      }
      try {
        const [liveEvents, xtreamStreams, curatedChannels] = await Promise.all([
          fetchLiveSportsEvents(),
          fetchXtreamLiveStreams(),
          getLiveChannelsData()
        ]);

        if (loadGeneration !== liveLoadGeneration || (currentMode !== 'live' && !document.body.classList.contains('tv-mode'))) {
          return fullLiveCache || [];
        }

        const allItems = [];

        // 1. Jogos e transmissões ao vivo
        if (Array.isArray(liveEvents)) {
          allItems.push(...liveEvents);
        }

        // 2. Canais Xtream se existirem na conta
        if (Array.isArray(xtreamStreams) && xtreamStreams.length > 0) {
          allItems.push(...xtreamStreams);
        }

        // 3. Canais Curados 24H (carregados sob demanda de channels.json)
        curatedChannels.forEach(ch => {
          allItems.push({
            ...ch,
            isChannel: true
          });
        });

        fullLiveCache = allItems;
        await writeCatalogCache('live', fullLiveCache);
        currentMediaList = fullLiveCache;
        elements.categorySelect.value = 'ALL';
        elements.resetCategoryBtn.style.display = 'none';
        elements.categoryLabel.textContent = 'TV & Jogos Ao Vivo: Todos os Canais e Partidas';
        if (currentMode === 'live' && !document.body.classList.contains('tv-mode')) {
          renderLiveHub();
        } else {
          applyFilterAndRender('');
        }

        // Atualiza guia EPG dos canais somente enquanto a área de TV estiver ativa.
        if (currentMode === 'live' || document.body.classList.contains('tv-mode')) {
          fetchLiveChannelsFromApi().catch(() => {});
        }
      } catch (err) {
        if (!hasPersistentCache && elements.loading) {
          elements.loading.innerHTML = `<p style="color:#e50914;">Erro ao carregar TV Ao Vivo: ${err.message}</p>`;
        }
      } finally {
        hideLoading();
      }
    }

    // ==========================================
    // CARREGAMENTO DE DADOS (FILMES / SÉRIES)
    // ==========================================
    async function loadFullMovies(forceRefresh = false) {
      const hasMemoryCache = Array.isArray(fullMoviesCache) && fullMoviesCache.length > 0;
      const memoryFresh = hasMemoryCache && Date.now() - Number(fullMoviesCacheSavedAt || 0) <= CATALOG_TTL_MS;
      if (!forceRefresh && memoryFresh) {
        currentMediaList = fullMoviesCache;
        if (currentMode === 'movies' && !document.body.classList.contains('tv-mode')) renderMoviesHub();
        return fullMoviesCache;
      }
      if (!forceRefresh && hasMemoryCache) {
        currentMediaList = fullMoviesCache;
        if (currentMode === 'movies' && !document.body.classList.contains('tv-mode')) renderMoviesHub();
        void loadFullMovies(true);
        return fullMoviesCache;
      }
      if (_loadingMoviesPromise) return _loadingMoviesPromise;

      _loadingMoviesPromise = (async () => {
        let hasPersistentCache = false;
        let hasStaleCache = false;
        try {
          const cached = await readCatalogCache('movies');
          if (Array.isArray(cached) && cached.length > 0) {
            fullMoviesCache = cached;
            currentMediaList = fullMoviesCache;
            hasPersistentCache = true;
            if (currentMode === 'movies' && !document.body.classList.contains('tv-mode')) {
              renderMoviesHub();
            }
          } else {
            const stale = await readCatalogCacheStale('movies');
            if (Array.isArray(stale) && stale.length > 0) {
              fullMoviesCache = stale;
              currentMediaList = fullMoviesCache;
              hasStaleCache = true;
              if (currentMode === 'movies' && !document.body.classList.contains('tv-mode')) {
                renderMoviesHub();
              }
            }
          }
        } catch (e) {}

        if (!hasPersistentCache && !hasStaleCache && currentMode === 'movies' && !document.body.classList.contains('tv-mode')) {
          showLoading('Carregando catálogo de filmes e recuperando capas 4K...');
        }
        try {
          const rawMovies = await xtreamApi('get_vod_streams');
          const rawList = Array.isArray(rawMovies) ? rawMovies : [];
          if (rawList.length === 0 && fullMoviesCache?.length) {
            return fullMoviesCache;
          }

          // Ocultar itens pertencentes à categoria DEMO
          const catArr = Array.isArray(movieCategories) ? movieCategories : [];
          const demoCatIds = new Set(catArr.filter(c => c && c.category_name && typeof c.category_name === 'string' && c.category_name.toLowerCase().includes('demo')).map(c => String(c.category_id)));
          const validMovies = rawList.filter(m => !demoCatIds.has(String(m.category_id)) && (!m.name || m.name.toLowerCase().trim() !== 'demo'));

          // Ordenar por ano de lançamento / estreia (mais recentes primeiro)
          validMovies.sort(compareByReleaseYear);

          // Cache de overrides de usuário do localStorage em memória (elimina 66.000 chamadas síncronas que travam a TV)
          const userPosterOverrides = new Map();
          const userNameOverrides = new Map();
          try {
            for (let i = 0; i < localStorage.length; i++) {
              const k = localStorage.key(i);
              if (!k) continue;
              if (k.startsWith('andplay_poster_override_')) userPosterOverrides.set(k.replace('andplay_poster_override_', ''), localStorage.getItem(k));
              else if (k.startsWith('andplay_name_override_')) userNameOverrides.set(k.replace('andplay_name_override_', ''), localStorage.getItem(k));
            }
          } catch (e) { }

          // Aplica correções automáticas de metadados e customizações salvas pelo usuário
          validMovies.forEach(m => {
            const override = KNOWN_STREAM_CORRECTIONS[m.stream_id];
            if (override) {
              if (override.name) m.name = override.name;
              if (override.year) m.year = override.year;
              if (override.originalName) m.originalName = override.originalName;
              if (override.poster) {
                m.stream_icon = override.poster;
                m.poster = override.poster;
              }
            } else {
              const sidStr = String(m.stream_id);
              const userPoster = userPosterOverrides.get(sidStr);
              if (userPoster) {
                m.stream_icon = userPoster;
                m.poster = userPoster;
              }
              const userName = userNameOverrides.get(sidStr);
              if (userName) {
                m.name = userName;
              }
            }
          });

          // Construir índice de posters limpos para cobrir filmes 4K sem capa (respeitando ano quando presente)
          moviePosterMap.clear();
          validMovies.forEach(m => {
            if (m.stream_icon && m.stream_icon.startsWith('http')) {
              const raw = m.name || m.title || '';
              const clean = cleanTitleKey(raw);
              const yMatch = raw.match(/\((19\d\d|20\d\d)\)/);
              const y = yMatch ? yMatch[1] : (m.year ? String(m.year).trim() : '');
              const keyWithYear = y ? `${clean}_${y}` : clean;
              if (!moviePosterMap.has(keyWithYear)) {
                moviePosterMap.set(keyWithYear, m.stream_icon);
              }
              if (!moviePosterMap.has(clean)) {
                moviePosterMap.set(clean, m.stream_icon);
              }
            }
          });

          // Unificar versões duplicadas (Dublado, Legendado e 4K) em cards únicos inteligentes
          fullMoviesCache = groupMoviesByTitle(validMovies);
          await writeCatalogCache('movies', fullMoviesCache, {
            sourceCount: rawList.length,
            filteredCount: validMovies.length,
            finalCount: fullMoviesCache.length
          });

          currentMediaList = fullMoviesCache;
          if (elements.categorySelect) elements.categorySelect.value = 'ALL';
          if (elements.resetCategoryBtn) elements.resetCategoryBtn.style.display = 'none';
          if (elements.categoryLabel) elements.categoryLabel.textContent = 'Catálogo Geral: Todos os Filmes';
          const hadPriorData = hasPersistentCache || hasStaleCache || hasMemoryCache;
          if (!hadPriorData && currentMode === 'movies' && !document.body.classList.contains('tv-mode')) {
            renderMoviesHub();
          }
          return fullMoviesCache;
        } catch (err) {
          if ((hasPersistentCache || hasStaleCache) && fullMoviesCache?.length) {
            return fullMoviesCache;
          }
          if (elements.loading) elements.loading.innerHTML = `<p style="color:#e50914;">Erro ao carregar filmes: ${err.message}</p>`;
          throw err;
        } finally {
          hideLoading();
          _loadingMoviesPromise = null;
        }
      })();

      return _loadingMoviesPromise;
    }

    async function loadFullSeries(forceRefresh = false) {
      const hasMemoryCache = Array.isArray(fullSeriesCache) && fullSeriesCache.length > 0;
      const memoryFresh = hasMemoryCache && Date.now() - Number(fullSeriesCacheSavedAt || 0) <= CATALOG_TTL_MS;
      if (!forceRefresh && memoryFresh) {
        currentMediaList = fullSeriesCache;
        if (currentMode === 'series' && !document.body.classList.contains('tv-mode')) renderSeriesHub();
        return fullSeriesCache;
      }
      if (!forceRefresh && hasMemoryCache) {
        currentMediaList = fullSeriesCache;
        if (currentMode === 'series' && !document.body.classList.contains('tv-mode')) renderSeriesHub();
        void loadFullSeries(true);
        return fullSeriesCache;
      }
      if (_loadingSeriesPromise) return _loadingSeriesPromise;

      _loadingSeriesPromise = (async () => {
        let hasPersistentCache = false;
        let hasStaleCache = false;
        try {
          const cached = await readCatalogCache('series');
          if (Array.isArray(cached) && cached.length > 0) {
            fullSeriesCache = cached;
            currentMediaList = fullSeriesCache;
            hasPersistentCache = true;
            if (currentMode === 'series' && !document.body.classList.contains('tv-mode')) {
              renderSeriesHub();
            }
          } else {
            const stale = await readCatalogCacheStale('series');
            if (Array.isArray(stale) && stale.length > 0) {
              fullSeriesCache = stale;
              currentMediaList = fullSeriesCache;
              hasStaleCache = true;
              if (currentMode === 'series' && !document.body.classList.contains('tv-mode')) {
                applyFilterAndRender('');
              }
            }
          }
        } catch (e) {}

        if (!hasPersistentCache && !hasStaleCache && currentMode === 'series' && !document.body.classList.contains('tv-mode')) {
          showLoading('Carregando catálogo de séries completas...');
        }
        try {
          const rawSeries = await xtreamApi('get_series');
          const rawList = Array.isArray(rawSeries) ? rawSeries : [];
          if (rawList.length === 0 && fullSeriesCache?.length) {
            return fullSeriesCache;
          }

          // Ocultar itens pertencentes à categoria DEMO
          const catArr = Array.isArray(seriesCategories) ? seriesCategories : [];
          const demoCatIds = new Set(catArr.filter(c => c && c.category_name && typeof c.category_name === 'string' && c.category_name.toLowerCase().includes('demo')).map(c => String(c.category_id)));
          const validSeries = rawList.filter(s => !demoCatIds.has(String(s.category_id)) && (!s.name || s.name.toLowerCase().trim() !== 'demo'));

          // Ordenar por ano de lançamento / estreia (mais recentes primeiro)
          validSeries.sort(compareByReleaseYear);

          // Unificar versões duplicadas (Dublado, Legendado e Lançamento) em cards únicos
          fullSeriesCache = groupSeriesByTitle(validSeries);
          await writeCatalogCache('series', fullSeriesCache, {
            sourceCount: rawList.length,
            filteredCount: validSeries.length,
            finalCount: fullSeriesCache.length
          });

          currentMediaList = fullSeriesCache;
          if (elements.categorySelect) elements.categorySelect.value = 'ALL';
          if (elements.resetCategoryBtn) elements.resetCategoryBtn.style.display = 'none';
          if (elements.categoryLabel) elements.categoryLabel.textContent = 'Catálogo Geral: Todas as Séries';
          const hadPriorData = hasPersistentCache || hasStaleCache || hasMemoryCache;
          if (!hadPriorData && currentMode === 'series' && !document.body.classList.contains('tv-mode')) {
            renderSeriesHub();
          }
          return fullSeriesCache;
        } catch (err) {
          if ((hasPersistentCache || hasStaleCache) && fullSeriesCache?.length) {
            return fullSeriesCache;
          }
          if (elements.loading) elements.loading.innerHTML = `<p style="color:#e50914;">Erro ao carregar séries: ${err.message}</p>`;
          throw err;
        } finally {
          hideLoading();
          _loadingSeriesPromise = null;
        }
      })();

      return _loadingSeriesPromise;
    }

    function selectAllMedia() {
      if (isWatchedView) {
        restoreCatalogView();
        return;
      }
      if (isFavoritesView) {
        restoreFavoritesView();
        return;
      }
      elements.categorySelect.value = 'ALL';
      elements.resetCategoryBtn.style.display = 'none';
      elements.searchInput.value = '';
      if (currentMode === 'movies') {
        elements.categoryLabel.textContent = 'Catálogo Geral: Todos os Filmes';
        currentMediaList = (fullMoviesCache || []).filter(item => !isAdultItem(item));
        if (elements.moviesHero) elements.moviesHero.style.removeProperty('display');
        if (elements.moviesGenrePills) elements.moviesGenrePills.style.removeProperty('display');
        if (isMoviesCuratedMode) {
          renderMoviesHub();
          return;
        }
      } else if (currentMode === 'series') {
        elements.categoryLabel.textContent = 'Catálogo Geral: Todas as Séries';
        currentMediaList = (fullSeriesCache || []).filter(item => !isAdultItem(item));
        if (elements.seriesHero) elements.seriesHero.style.removeProperty('display');
        if (elements.seriesGenrePills) elements.seriesGenrePills.style.removeProperty('display');
        if (isSeriesCuratedMode) {
          renderSeriesHub();
          return;
        }
      } else if (currentMode === 'live') {
        elements.categoryLabel.textContent = 'TV & Jogos Ao Vivo: Todos os Canais e Partidas';
        currentLiveCategoryFilter = 'ALL';
        currentLiveQuickFilter = 'all';
        renderLiveCategoryPills();
        updateLiveQuickFiltersUI();
        applyLiveFiltersAndSort();
        return;
      }
      applyFilterAndRender('');
    }

    function onCategoryChange(catId) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      if (catId === 'ALL') {
        selectAllMedia();
        return;
      }

      elements.resetCategoryBtn.style.display = 'inline-flex';
      const selectedOption = elements.categorySelect.options[elements.categorySelect.selectedIndex];
      elements.categoryLabel.textContent = `Categoria: ${selectedOption ? selectedOption.textContent : ''}`;
      elements.searchInput.value = '';

      if (currentMode === 'live') {
        currentLiveCategoryFilter = catId;
        renderLiveCategoryPills();
        applyLiveFiltersAndSort();
        return;
      }

      if (elements.moviesCuratedRails) elements.moviesCuratedRails.style.display = 'none';
      if (elements.seriesCuratedRails) elements.seriesCuratedRails.style.display = 'none';
      if (elements.moviesHero) elements.moviesHero.style.display = 'none';
      if (elements.seriesHero) elements.seriesHero.style.display = 'none';
      if (elements.moviesGenrePills) elements.moviesGenrePills.style.display = 'none';
      if (elements.seriesGenrePills) elements.seriesGenrePills.style.display = 'none';
      if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
      document.querySelector('.status-bar')?.style.removeProperty('display');
      elements.moviesViewCuratedBtn?.classList.remove('active');
      elements.moviesViewGridBtn?.classList.add('active');
      elements.seriesViewCuratedBtn?.classList.remove('active');
      elements.seriesViewGridBtn?.classList.add('active');

      const sourceList = (currentMode === 'movies') ? fullMoviesCache : fullSeriesCache;
      if (sourceList) {
        currentMediaList = sourceList.filter(item => {
          if (String(item.category_id) === String(catId)) return true;
          if (Array.isArray(item.category_ids) && item.category_ids.some(c => String(c) === String(catId))) return true;
          return false;
        });
        applyFilterAndRender('');
      }
    }

    // ==========================================
    // ==========================================
    // BUSCA INTELIGENTE
    // ==========================================
    async function performGlobalSearch(term) {
      const q = normalizeSearch(term);
      if (!q) {
        showHome();
        return;
      }

      const requestId = ++globalSearchRequestId;
      currentMode = 'search';
      isWatchedView = false;
      isFavoritesView = false;
      if (elements.moviesHub) elements.moviesHub.style.display = 'none'; if (elements.seriesHub) elements.seriesHub.style.display = 'none'; if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (moviesHeroTimer) {
        clearInterval(moviesHeroTimer);
        moviesHeroTimer = null;
      }
      if (seriesHeroTimer) {
        clearInterval(seriesHeroTimer);
        seriesHeroTimer = null;
      }
      setHomeDashboardVisible(false);
      elements.contentPage?.classList.remove('is-active');
      if (elements.contentPage) elements.contentPage.hidden = true;
      document.querySelector('.status-bar')?.style.removeProperty('display');
      document.querySelector('main')?.style.removeProperty('display');
      if (elements.categorySelect) {
        elements.categorySelect.value = 'ALL';
        elements.categorySelect.disabled = true;
        elements.categorySelect.style.display = 'none';
      }
      if (elements.resetCategoryBtn) elements.resetCategoryBtn.style.display = 'none';
      if (elements.categoryLabel) elements.categoryLabel.textContent = 'Resultados da busca';
      if (elements.searchInput) elements.searchInput.placeholder = 'Pesquisar por título, gênero, ator ou diretor...';
      showLoading('Buscando por: ' + term);

      try {
        const [movies, series] = await Promise.all([
          loadFullMovies(),
          loadFullSeries(),
          loadMovieCategories().catch(() => []),
          loadSeriesCategories().catch(() => [])
        ]);
        if (requestId !== globalSearchRequestId || normalizeSearch(elements.searchInput?.value || '') !== q) {
          hideLoading();
          return;
        }

        if (!globalSearchCatalogCache) {
          globalSearchCatalogCache = [
            ...(Array.isArray(movies) ? movies : []).map(item => ({ ...item, _searchType: 'movie' })),
            ...(Array.isArray(series) ? series : []).map(item => ({ ...item, _searchType: 'series' }))
          ].filter(item => !isAdultItem(item));
        }
        currentMediaList = globalSearchCatalogCache.filter(item => !isAdultItem(item));
        elements.categorySelect.value = 'ALL';
        if (elements.categoryLabel) elements.categoryLabel.textContent = 'Busca: ' + term;
        hideLoading();
        applyFilterAndRender(term);
        void resolveOnlineContextualSearch(term, requestId);
      } catch (error) {
        if (requestId !== globalSearchRequestId) return;
        hideLoading();
        elements.mediaGrid.innerHTML = '<div style="grid-column:1/-1;text-align:center;color:#888;padding:60px 20px;">Não foi possível realizar a busca agora.</div>';
        elements.loadMoreContainer.style.display = 'none';
        elements.mediaCount.textContent = 'Busca indisponível';
        console.warn('[EPlay Search] Falha na busca global:', error);
      } finally {
        if (requestId === globalSearchRequestId) {
          hideLoading();
        }
      }
    }

    let _onlineSearchResolutionToken = 0;
    async function resolveOnlineContextualSearch(query, requestId) {
      const q = normalizeSearch(query);
      if (!q || q.length < 3) return;
      const myToken = ++_onlineSearchResolutionToken;

      try {
        const [movieData, seriesData] = await Promise.all([
          fetchJsonWithTimeout(`https://v3-cinemeta.strem.io/catalog/movie/top/search=${encodeURIComponent(query)}.json`, 3500).catch(() => null),
          fetchJsonWithTimeout(`https://v3-cinemeta.strem.io/catalog/series/top/search=${encodeURIComponent(query)}.json`, 3500).catch(() => null)
        ]);

        if (myToken !== _onlineSearchResolutionToken || requestId !== globalSearchRequestId) return;
        if (normalizeSearch(elements.searchInput?.value || '') !== q) return;

        const candidateMetas = [
          ...(Array.isArray(movieData?.metas) ? movieData.metas : []),
          ...(Array.isArray(seriesData?.metas) ? seriesData.metas : [])
        ];

        if (!candidateMetas.length) return;

        const matchedImdbIds = new Set();
        const matchedCleanKeys = new Set();

        candidateMetas.forEach(meta => {
          if (meta.id) matchedImdbIds.add(String(meta.id).toLowerCase());
          if (meta.imdb_id) matchedImdbIds.add(String(meta.imdb_id).toLowerCase());
          const cKey = cleanTitleKey(meta.name);
          if (cKey) matchedCleanKeys.add(cKey);
        });

        let newlyMatchedCount = 0;
        const catalog = Array.isArray(globalSearchCatalogCache) ? globalSearchCatalogCache : [];

        catalog.forEach(item => {
          const itemImdb = String(item.imdbId || item.imdb_id || item.primaryItem?.imdbId || item.primaryItem?.imdb_id || '').toLowerCase();
          const itemClean = cleanTitleKey(item.name || item.title || '');

          const isMatch = (itemImdb && matchedImdbIds.has(itemImdb)) || (itemClean && matchedCleanKeys.has(itemClean));
          if (isMatch) {
            if (!item._contextualKeywords) item._contextualKeywords = [];
            if (!item._contextualKeywords.includes(q)) {
              item._contextualKeywords.push(q);
              item._searchableText = null;
              newlyMatchedCount++;
            }
          }
        });

        if (newlyMatchedCount > 0 && requestId === globalSearchRequestId && currentMode === 'search') {
          applyFilterAndRender(query);
        }
      } catch (_) {}
    }

    function onSearch(term) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      const q = normalizeSearch(term);
      if (!q) {
        hideLoading();
      }
      if (isWatchedView || isFavoritesView) {
        applyFilterAndRender(term);
        return;
      }
      if (currentMode === 'home' || currentMode === 'search') {
        if (!q) {
          if (currentMode === 'search') showHome();
          return;
        }
        performGlobalSearch(term);
        return;
      }
      if (currentMode === 'movies') {
        if (!q) {
          if (elements.moviesHero) elements.moviesHero.style.removeProperty('display');
          if (elements.moviesGenrePills) elements.moviesGenrePills.style.removeProperty('display');
          if (isMoviesCuratedMode && currentMovieGenreFilter === 'ALL' && !moviesFilter4K && !moviesFilterDub && !moviesFilterLeg && moviesSortBy === 'featured') {
            renderMoviesHub();
            return;
          }
          applyMoviesFiltersAndSort();
          return;
        }
        // Ao buscar em Filmes, sai imediatamente dos trilhos e exibe a grade de resultados (como na Home)
        if (elements.moviesCuratedRails) elements.moviesCuratedRails.style.display = 'none';
        if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
        document.querySelector('.status-bar')?.style.removeProperty('display');
        if (elements.moviesHero) elements.moviesHero.style.display = 'none';
        if (elements.moviesGenrePills) elements.moviesGenrePills.style.display = 'none';
        elements.moviesViewCuratedBtn?.classList.remove('active');
        elements.moviesViewGridBtn?.classList.add('active');
        applyMoviesFiltersAndSort();
        return;
      }
      if (currentMode === 'series') {
        if (!q) {
          if (elements.seriesHero) elements.seriesHero.style.removeProperty('display');
          if (elements.seriesGenrePills) elements.seriesGenrePills.style.removeProperty('display');
          if (isSeriesCuratedMode && currentSeriesGenreFilter === 'ALL' && !seriesFilterDub && !seriesFilterLeg && seriesSortBy === 'featured') {
            renderSeriesHub();
            return;
          }
          applySeriesFiltersAndSort();
          return;
        }
        // Ao buscar em Séries, sai imediatamente dos trilhos e exibe a grade de resultados (como na Home)
        if (elements.seriesCuratedRails) elements.seriesCuratedRails.style.display = 'none';
        if (elements.mediaGrid) elements.mediaGrid.style.removeProperty('display');
        document.querySelector('.status-bar')?.style.removeProperty('display');
        if (elements.seriesHero) elements.seriesHero.style.display = 'none';
        if (elements.seriesGenrePills) elements.seriesGenrePills.style.display = 'none';
        elements.seriesViewCuratedBtn?.classList.remove('active');
        elements.seriesViewGridBtn?.classList.add('active');
        applySeriesFiltersAndSort();
        return;
      }
      if (currentMode === 'live') {
        if (!document.body.classList.contains('tv-mode')) {
          applyLiveFiltersAndSort();
          return;
        }
      }
      applyFilterAndRender(term);
    }

    function applyFilterAndRender(term) {
      hideLoading();
      const q = normalizeSearch(term);
      if (!q) {
        currentFilteredList = currentMediaList;
      } else {
        currentFilteredList = currentMediaList.filter(item => matchesSearchQuery(item, q));
        if (currentMode === 'search') {
          currentFilteredList.sort((a, b) => {
            const aTitle = normalizeSearch(a.name || a.title || '');
            const bTitle = normalizeSearch(b.name || b.title || '');
            const aExact = aTitle === q;
            const bExact = bTitle === q;
            if (aExact !== bExact) return aExact ? -1 : 1;
            const aStarts = aTitle.startsWith(q);
            const bStarts = bTitle.startsWith(q);
            if (aStarts !== bStarts) return aStarts ? -1 : 1;
            const aIncludes = aTitle.includes(q);
            const bIncludes = bTitle.includes(q);
            if (aIncludes !== bIncludes) return aIncludes ? -1 : 1;
            const aRating = Number(a.rating || 0);
            const bRating = Number(b.rating || 0);
            return bRating - aRating;
          });
        }
      }

      elements.mediaGrid.innerHTML = '';
      renderedCount = 0;
      updateCountDisplay();

      if (currentFilteredList.length === 0) {
        const isFiltered = elements.categorySelect.value !== 'ALL';
        elements.mediaGrid.innerHTML = `
          <div style="grid-column: 1/-1; text-align: center; color: #888; padding: 60px 20px;">
            <p style="font-size: 18px; margin-bottom: 12px;">Nenhum título ou transmissão encontrada.</p>
            ${isFiltered ? `<button data-search-all class="btn btn-primary">Buscar em Todo o Catálogo</button>` : ''}
          </div>
        `;
        elements.loadMoreContainer.style.display = 'none';
        return;
      }

      renderNextBatch();
    }

    // ==========================================
    // RENDERIZAÇÃO EM LOTES (INFINITE SCROLL)
    // ==========================================
    function renderNextBatch() {
      const nextBatch = currentFilteredList.slice(renderedCount, renderedCount + BATCH_SIZE);
      const fragment = document.createDocumentFragment();

      nextBatch.forEach(item => {
        const card = document.createElement('div');
        card.className = 'media-card';
        card.tabIndex = 0;
        card.setAttribute('role', 'button');

        const itemSearchType = item._searchType || item.type || '';
        const isLive = itemSearchType ? itemSearchType === 'live' : currentMode === 'live';
        const isMovie = itemSearchType ? itemSearchType === 'movie' : currentMode === 'movies';
        const title = isLive ? (item.name || item.title || '') : cleanDisplayTitle(item.name || item.title || '');
        card.title = title;

        if (isLive) {
          if (item.isLiveMatch) {
            const isNow = item.isLiveNow;
            // SVG inline como fallback garantido (sem dependência de rede)
            const defaultMatchLogo = `data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Ccircle cx='32' cy='32' r='30' fill='%23333'/%3E%3Ctext x='32' y='42' text-anchor='middle' fill='%23888' font-size='28'%3E%E2%9A%BD%3C/text%3E%3C/svg%3E`;
            const homeLogo = getTvTeamLogoUrl(item.homeTeam, item.homeLogo);
            const awayLogo = getTvTeamLogoUrl(item.awayTeam, item.awayLogo);
            let posterVisualHtml = '';
            if (item.poster && (!homeLogo || !awayLogo)) {
              posterVisualHtml = `
                <img src="${escapeHtml(item.poster)}" alt="${escapeHtml(title)}" style="width: 100%; height: 100%; object-fit: cover;" data-hide-on-error loading="lazy">
                <span class="match-time-pill" style="position: absolute; bottom: 8px; left: 50%; transform: translateX(-50%); white-space: nowrap;">${escapeHtml(item.matchTime || 'AO VIVO')}</span>
              `;
            } else {
              posterVisualHtml = `
                <div class="match-vs-container">
                  <div class="match-teams-row">
                    ${homeLogo ? `<img class="match-team-logo" src="${escapeHtml(homeLogo)}" alt="${escapeHtml(item.homeTeam || '')}" data-dim-on-error loading="lazy">` : `<span class="match-team-logo" style="display:flex;align-items:center;justify-content:center;width:52px;height:52px;font-size:32px;background:#333;border-radius:50%">⚽</span>`}
                    <span class="match-vs-tag">VS</span>
                    ${awayLogo ? `<img class="match-team-logo" src="${escapeHtml(awayLogo)}" alt="${escapeHtml(item.awayTeam || '')}" data-dim-on-error loading="lazy">` : `<span class="match-team-logo" style="display:flex;align-items:center;justify-content:center;width:52px;height:52px;font-size:32px;background:#333;border-radius:50%">⚽</span>`}
                  </div>
                  <span class="match-time-pill">${escapeHtml(item.matchTime || 'AO VIVO')}</span>
                </div>
              `;
            }

            card.innerHTML = `
              <div class="poster-wrap" style="aspect-ratio: 16/9; min-height: 140px; background: #161616; position: relative; overflow: hidden;">
                ${posterVisualHtml}
                <div class="${isNow ? 'badge-live' : 'badge-upcoming'}">${isNow ? 'AO VIVO' : 'EM BREVE'}</div>
              </div>
              <div class="card-info">
                <div class="card-title">${escapeHtml(title)}</div>
                <div class="card-meta">
                  <span>${escapeHtml(item.league || item.categoryLabel || 'Esportes')}</span>
                  <span>${item.fallbacks ? item.fallbacks.length + ' opções' : 'Ao Vivo'}</span>
                </div>
              </div>
            `;
          } else {
            // Canal ao vivo: card moderno com logo/capa em destaque, legenda sobreposta fosca e progresso EPG
            card.className = 'media-card channel-media-card';
            const rawLogo = item.logo || item.stream_icon || '';
            const logoUrl = getTvLogoUrl(rawLogo);
            const numLabel = item.channelNumberFormatted || (item.channelNumber ? `CH ${String(item.channelNumber).padStart(3, '0')}` : '');
            const groupText = item.subCategory || item.categoryLabel || item.rawCategory || 'TV';

            const liveSchedule = (typeof getChannelLiveSchedule === 'function')
              ? getChannelLiveSchedule(item)
              : (typeof window !== 'undefined' && typeof window.EPlayTvEpg?.getSchedule === 'function'
                  ? window.EPlayTvEpg.getSchedule(item)
                  : null);
            const nowTitle = (liveSchedule && liveSchedule.nowTitle)
              ? liveSchedule.nowTitle
              : (item.nowTitle && item.nowTitle !== 'Carregando guia...' ? item.nowTitle : 'Programação Ao Vivo');
            const progress = Math.max(0, Math.min(100, liveSchedule ? (Number(liveSchedule.progress) || 0) : (Number(item.nowProgress) || 0)));

            card.innerHTML = `
              <div class="channel-poster-wrap">
                ${numLabel ? `<div class="badge-channel-num">${escapeHtml(numLabel)}</div>` : ''}
                <div class="badge-channel">${escapeHtml(item.badge || 'AO VIVO')}</div>
                ${logoUrl ? `<img class="channel-main-logo" src="${escapeHtml(logoUrl)}" alt="${escapeHtml(title)}" data-hide-show-fallback-on-error loading="lazy">` : ''}
                <div class="poster-fallback" style="${logoUrl ? 'display:none;' : 'display:flex;'}">📺<br>${escapeHtml(title)}</div>
                <div class="channel-overlay-bar">
                  <span class="channel-overlay-name" title="${escapeHtml(title)}">${escapeHtml(title)}</span>
                  <span class="channel-overlay-tag">${escapeHtml(groupText)}</span>
                </div>
              </div>
              <div class="channel-event-bar">
                <div class="channel-event-row">
                  <span class="channel-event-indicator"></span>
                  <span class="channel-event-title" title="${escapeHtml(nowTitle)}">${escapeHtml(nowTitle)}</span>
                </div>
                <div class="channel-progress-track">
                  <div class="channel-progress-fill" style="width: ${progress}%;"></div>
                </div>
                <div class="channel-meta-bottom">
                  <span>${escapeHtml(item.categoryLabel || groupText)}</span>
                  <span>${item.fallbacks ? item.fallbacks.length + ' opções' : 'HD'}</span>
                </div>
              </div>
            `;
          }

          card.addEventListener('click', () => onLiveItemClick(item));
          fragment.appendChild(card);
          return;
        }

        let poster = '';
        let badgeHtml = '';

        if (isMovie) {
          poster = getBestPosterUrl(item.primaryItem || item);
          const has4K = item.versions ? item.versions.some(v => v.versionInfo.type.startsWith('4k')) : (item.category_id == '765' || /\b4k\b/i.test(title));
          const hasLeg = item.versions ? item.versions.some(v => v.versionInfo.type.includes('leg')) : /\[\s*L\s*\]/i.test(title);
          const hasDub = item.versions ? item.versions.some(v => v.versionInfo.type === 'dublado' || v.versionInfo.type === '4k_dub') : true;

          if (item.versions && item.versions.length > 1) {
            const badges = [];
            if (has4K) badges.push('4K');
            if (hasDub) badges.push('DUB');
            if (hasLeg) badges.push('LEG');
            badgeHtml = `<div class="badge-versions">${badges.join(' • ')}</div>`;
          } else if (has4K) {
            badgeHtml = `<div class="badge-4k">4K ULTRA HD</div>`;
          } else if (hasLeg) {
            badgeHtml = `<div class="badge-leg">LEGENDADO</div>`;
          }
        } else {
          poster = item.cover || item.stream_icon || '';
          const hasLeg = item.versions ? item.versions.some(v => v.versionInfo.type === 'legendado') : /\[\s*L\s*\]/i.test(title);
          const hasDub = item.versions ? item.versions.some(v => v.versionInfo.type === 'dublado') : true;

          if (item.versions && item.versions.length > 1) {
            const badges = [];
            if (hasDub) badges.push('DUB');
            if (hasLeg) badges.push('LEG');
            badgeHtml = `<div class="badge-versions">${badges.join(' • ')}</div>`;
          } else if (hasLeg) {
            badgeHtml = `<div class="badge-leg">LEGENDADO</div>`;
          } else {
            badgeHtml = `<div class="badge-series">SÉRIE</div>`;
          }
        }

        const rating = (item.rating && Number(item.rating) > 0) ? Number(item.rating).toFixed(1) : null;
        const year = item.year || (item.releaseDate ? item.releaseDate.substring(0, 4) : '');

        let extraOverlaysHtml = '';
        const rawItemId = isMovie
          ? (item.stream_id ?? item.primaryItem?.stream_id ?? item.id)
          : (item.series_id ?? item.primaryItem?.series_id ?? item.id);
        const itemId = String(rawItemId || '');
        const itemType = isMovie ? 'movie' : 'series';

        if (isFavoritesView) {
          extraOverlaysHtml = `
            <div class="badge-fav-active">★ SALVO</div>
            <button type="button" class="card-quick-action-btn" data-fav-remove="${escapeHtml(itemId)}" data-fav-type="${escapeHtml(itemType)}" title="Remover dos favoritos" aria-label="Remover dos favoritos">✕</button>
          `;
        } else if (isWatchedView) {
          const progPct = getItemProgressPct(item);
          const isDone = progPct >= 95;
          const vodProg = getVodProgress(itemType, itemId);
          let stateLabel = '';
          if (isDone) {
            stateLabel = '✓ CONCLUÍDO';
          } else if (progPct > 0) {
            if (itemType === 'series' && vodProg?.seasonNum && vodProg?.episodeNum) {
              stateLabel = `T${vodProg.seasonNum}:E${vodProg.episodeNum} • ${progPct}%`;
            } else {
              stateLabel = `Retomar ${progPct}%`;
            }
          } else {
            stateLabel = 'Assistido';
          }

          extraOverlaysHtml = `
            ${stateLabel ? `<div class="badge-watched-state ${isDone ? 'done' : ''}">${escapeHtml(stateLabel)}</div>` : ''}
            <div class="card-watched-progress-track">
              <div class="card-watched-progress-fill" style="width: ${progPct}%;"></div>
            </div>
            <button type="button" class="card-quick-action-btn" data-watched-remove="${escapeHtml(itemId)}" data-watched-type="${escapeHtml(itemType)}" title="Remover do histórico" aria-label="Remover do histórico">✕</button>
          `;
        }

        card.innerHTML = `
          <div class="poster-wrap">
            ${poster ? `<img src="${escapeHtml(poster)}" alt="${escapeHtml(title)}" loading="lazy" decoding="async" data-poster-error="${escapeHtml(title)}" draggable="false">` : ''}
            <div class="poster-fallback" style="${poster ? 'display:none;' : ''}">🎬<br>${escapeHtml(title)}</div>
            ${badgeHtml}
            ${rating ? `<div class="rating-badge">★ ${rating}</div>` : ''}
            ${extraOverlaysHtml}
          </div>
          <div class="card-info">
            <div class="card-title">${escapeHtml(title)}</div>
            <div class="card-meta">
              <span>${isMovie ? (item.container_extension || 'MP4').toUpperCase() : 'COMPLETA'}</span>
              <span>${escapeHtml(year)}</span>
            </div>
          </div>
        `;

        if (isFavoritesView) {
          const favRemoveBtn = card.querySelector('[data-fav-remove]');
          if (favRemoveBtn) {
            favRemoveBtn.addEventListener('click', (e) => {
              e.stopPropagation();
              removeSingleFavoriteItem(itemType, itemId);
              card.style.transition = 'opacity 0.25s ease, transform 0.25s ease';
              card.style.opacity = '0';
              card.style.transform = 'scale(0.85)';
              setTimeout(() => {
                card.remove();
                fullFavoritesList = fullFavoritesList.filter(f => {
                  const fId = String(f.stream_id ?? f.series_id ?? f.primaryItem?.stream_id ?? f.primaryItem?.series_id ?? f.id);
                  return !(fId === itemId && (f._searchType || f.type) === itemType);
                });
                currentFilteredList = currentFilteredList.filter(f => {
                  const fId = String(f.stream_id ?? f.series_id ?? f.primaryItem?.stream_id ?? f.primaryItem?.series_id ?? f.id);
                  return !(fId === itemId && (f._searchType || f.type) === itemType);
                });
                currentMediaList = currentMediaList.filter(f => {
                  const fId = String(f.stream_id ?? f.series_id ?? f.primaryItem?.stream_id ?? f.primaryItem?.series_id ?? f.id);
                  return !(fId === itemId && (f._searchType || f.type) === itemType);
                });
                renderedCount = Math.max(0, renderedCount - 1);
                updateFavoritesCounters();
                updateCountDisplay();
                if (currentFilteredList.length === 0) {
                  elements.mediaGrid.innerHTML =
                    '<div class="eplay-favorites-empty">' +
                      '<div class="eplay-favorites-empty-icon">☆</div>' +
                      '<div style="font-size: 16px; font-weight: 700; color: #fff; margin-top: 6px;">Sua lista de favoritos está vazia.</div>' +
                      '<small style="margin-top: 4px;">Abra qualquer filme ou série e clique em “Adicionar aos favoritos” para salvar aqui.</small>' +
                    '</div>';
                  elements.loadMoreContainer.style.display = 'none';
                }
              }, 250);
            });
          }
        }

        if (isWatchedView) {
          const watchedRemoveBtn = card.querySelector('[data-watched-remove]');
          if (watchedRemoveBtn) {
            watchedRemoveBtn.addEventListener('click', (e) => {
              e.stopPropagation();
              removeSingleWatchedItem(itemType, itemId);
              card.style.transition = 'opacity 0.25s ease, transform 0.25s ease';
              card.style.opacity = '0';
              card.style.transform = 'scale(0.85)';
              setTimeout(() => {
                card.remove();
                fullWatchedList = fullWatchedList.filter(w => {
                  const wId = String(w.stream_id ?? w.series_id ?? w.primaryItem?.stream_id ?? w.primaryItem?.series_id ?? w.id);
                  return !(wId === itemId && (w._searchType || w.type) === itemType);
                });
                currentFilteredList = currentFilteredList.filter(w => {
                  const wId = String(w.stream_id ?? w.series_id ?? w.primaryItem?.stream_id ?? w.primaryItem?.series_id ?? w.id);
                  return !(wId === itemId && (w._searchType || w.type) === itemType);
                });
                currentMediaList = currentMediaList.filter(w => {
                  const wId = String(w.stream_id ?? w.series_id ?? w.primaryItem?.stream_id ?? w.primaryItem?.series_id ?? w.id);
                  return !(wId === itemId && (w._searchType || w.type) === itemType);
                });
                renderedCount = Math.max(0, renderedCount - 1);
                updateWatchedCounters();
                updateCountDisplay();
                if (currentFilteredList.length === 0) {
                  elements.mediaGrid.innerHTML =
                    '<div class="eplay-favorites-empty">' +
                      '<div class="eplay-favorites-empty-icon">👁</div>' +
                      '<div style="font-size: 16px; font-weight: 700; color: #fff; margin-top: 6px;">Nenhum conteúdo encontrado nesta categoria.</div>' +
                    '</div>';
                  elements.loadMoreContainer.style.display = 'none';
                }
              }, 250);
            });
          }
        }

        if (isMovie) {
          card.addEventListener('click', () => onMovieCardClick(item));
        } else {
          card.addEventListener('click', () => openSeriesPage(item));
        }

        fragment.appendChild(card);
      });

      elements.mediaGrid.appendChild(fragment);
      renderedCount += nextBatch.length;
      updateCountDisplay();

      const isCuratedActive = (currentMode === 'movies' && isMoviesCuratedMode) || (currentMode === 'series' && isSeriesCuratedMode);
      if (renderedCount < currentFilteredList.length && !isCuratedActive) {
        elements.loadMoreContainer.style.display = 'flex';
        elements.loadMoreBtn.textContent = `Carregar mais (+${Math.min(BATCH_SIZE, currentFilteredList.length - renderedCount)} de ${currentFilteredList.length - renderedCount})`;
      } else {
        elements.loadMoreContainer.style.display = 'none';
      }
    }

    // Fallback de erro de imagem
    function onPosterError(imgElement, rawTitle) {
      imgElement.style.display = 'none';
      const clean = cleanTitleKey(rawTitle);
      const yMatch = (rawTitle || '').match(/\((19\d\d|20\d\d)\)/);
      const y = yMatch ? yMatch[1] : '';
      const keyWithYear = y ? `${clean}_${y}` : clean;
      if (keyWithYear && moviePosterMap.has(keyWithYear)) {
        imgElement.src = moviePosterMap.get(keyWithYear);
        imgElement.style.display = 'block';
        return;
      }
      if (clean && moviePosterMap.has(clean)) {
        imgElement.src = moviePosterMap.get(clean);
        imgElement.style.display = 'block';
        return;
      }
      if (imgElement.nextElementSibling) {
        imgElement.nextElementSibling.style.display = 'flex';
      }
    }

    function updateCountDisplay() {
      const total = currentFilteredList.length;
      const typeLabel = isWatchedView
        ? 'assistido(s)'
        : (isFavoritesView
          ? 'favoritos'
          : (currentMode === 'search' ? 'resultado(s)' : ((currentMode === 'movies') ? 'filme(s)' : ((currentMode === 'live') ? 'canal/partida(s)' : 'série(s)'))));
      if (total === 0) {
        elements.mediaCount.textContent = `0 ${typeLabel}`;
      } else {
        elements.mediaCount.textContent = `Exibindo ${renderedCount} de ${total} ${typeLabel}`;
      }
    }

    function showLoading(text) {
      elements.loadingText.textContent = text;
      elements.loading.style.display = 'flex';
      elements.mediaGrid.innerHTML = '';
      elements.loadMoreContainer.style.display = 'none';
      elements.mediaCount.textContent = 'Carregando...';
    }

    function hideLoading() {
      elements.loading.style.display = 'none';
    }

    // ==========================================
    // REPRODUTOR DE CANAIS E TRANSMISSÕES AO VIVO
    // ==========================================
    function onLiveItemClick(item) {
      openLivePlayer(item, 0);
    }

    // ==========================================
    // MOTOR DE REPRODUÇÃO AO VIVO (LOW LATENCY / ULTRA BUFFER / EPG)
    // ==========================================
    let liveBufferMinimizationEnabled = true; // Ativado por padrão para transmissão em tempo real
    let liveDriftInterval = null;

    function showLiveSyncToast(msg) {
      if (!elements.liveSyncToast) return;
      elements.liveSyncToast.textContent = msg;
      elements.liveSyncToast.style.display = 'block';
      elements.liveSyncToast.style.opacity = '1';
      setTimeout(() => {
        elements.liveSyncToast.style.opacity = '0';
        setTimeout(() => {
          if (elements.liveSyncToast.style.opacity === '0') {
            elements.liveSyncToast.style.display = 'none';
          }
        }, 350);
      }, 2500);
    }

    function toggleLatencyMode() {
      liveBufferMinimizationEnabled = !liveBufferMinimizationEnabled;
      updateLatencyButtonUI();

      if (currentPlaybackMeta && currentPlaybackMeta.mediaType === 'live') {
        const item = currentPlaybackMeta.item;
        const idx = currentPlaybackMeta.fallbackIdx || 0;
        openLivePlayer(item, idx);
      }

      showLiveSyncToast(liveBufferMinimizationEnabled ? '⚡ Buffer Mínimo Ativado (~1s delay)' : '🛡️ Buffer Estável Ativado (~8s delay)');
    }

    function updateLatencyButtonUI() {
      if (!elements.toggleLatencyModeBtn) return;
      if (liveBufferMinimizationEnabled) {
        elements.toggleLatencyModeBtn.innerHTML = '⚡ Buffer Mínimo: 1s';
        elements.toggleLatencyModeBtn.className = 'btn btn-secondary';
        elements.toggleLatencyModeBtn.style.border = '1px solid #ffc107';
        elements.toggleLatencyModeBtn.style.color = '#ffc107';
      } else {
        elements.toggleLatencyModeBtn.innerHTML = '🛡️ Buffer Estável: 8s';
        elements.toggleLatencyModeBtn.className = 'btn btn-secondary';
        elements.toggleLatencyModeBtn.style.border = '1px solid #666';
        elements.toggleLatencyModeBtn.style.color = '#ccc';
      }
    }

    function forceLiveSync() {
      if (!currentPlaybackMeta || currentPlaybackMeta.mediaType !== 'live') return;

      const item = currentPlaybackMeta.item;
      const currentOpt = item.fallbacks ? item.fallbacks[currentPlaybackMeta.fallbackIdx || 0] : null;
      const isEmbed = currentOpt ? Boolean(currentOpt.isEmbed) : (elements.embedPlayer.style.display !== 'none');

      if (isEmbed) {
        if (elements.embedPlayer && elements.embedPlayer.src && elements.embedPlayer.src !== 'about:blank') {
          const cur = elements.embedPlayer.src;
          elements.embedPlayer.src = 'about:blank';
          setTimeout(() => { elements.embedPlayer.src = cur; }, 60);
        }
      } else {
        if (activeHls && activeHls.liveSyncPosition) {
          elements.videoPlayer.currentTime = activeHls.liveSyncPosition;
          elements.videoPlayer.playbackRate = 1.0;
        }
        elements.videoPlayer.play().catch(() => { });
      }

      showLiveSyncToast('⚡ Sincronizado ao vivo em tempo real!');
    }

    function openLivePlayer(item, fallbackIdx = 0) {
      hideVideoErrorOverlay();
      if (videoLoadTimeout) {
        clearTimeout(videoLoadTimeout);
        videoLoadTimeout = null;
      }

      const fallbacks = item.fallbacks && item.fallbacks.length > 0 ? item.fallbacks : [
        {
          name: 'Transmissão Principal',
          url: item.hlsUrl || item.embedUrl || '',
          isEmbed: !item.hlsUrl
        }
      ];

      const currentOpt = fallbacks[fallbackIdx] || fallbacks[0];
      const streamUrl = currentOpt.url;
      const isEmbed = Boolean(currentOpt.isEmbed);
      const title = item.name || item.title || 'Transmissão Ao Vivo';

      currentPlaybackMeta = {
        mediaType: 'live',
        title: title,
        item: item,
        fallbackIdx: fallbackIdx
      };

      // Abre modal de vídeo
      elements.videoModal.style.display = 'flex';
      elements.modalTitle.textContent = `🔴 ${title}`;
      elements.modalFormat.textContent = isEmbed ? 'Sinal: HD Protegido (Zero Anúncios)' : 'Sinal: HLS Nativo 1080p (Zero Anúncios)';

      // Oculta botões irrelevantes para transmissões ao vivo
      elements.toggleSubPanelBtn.style.display = 'none';
      elements.downloadBtn.style.display = 'none';

      // Exibe controles de transmissão ao vivo em tempo real
      if (elements.syncLiveBtn) elements.syncLiveBtn.style.display = 'inline-flex';
      if (elements.toggleLatencyModeBtn) {
        elements.toggleLatencyModeBtn.style.display = 'inline-flex';
        updateLatencyButtonUI();
      }

      const epSecLive = document.getElementById('eplayVersionSection');
      if (epSecLive) epSecLive.style.display = 'none';
      const panelSecLive = elements.panelVersionSection || document.getElementById('panelVersionSection');
      if (panelSecLive) panelSecLive.style.display = 'none';

      // Configura seletor de servidores/canais (movieVersionSwitcher)
      if (elements.movieVersionSwitcher) {
        if (fallbacks.length > 1) {
          elements.movieVersionSwitcher.style.display = 'flex';
          elements.movieVersionSwitcher.innerHTML = '';

          const switcherLabel = document.createElement('span');
          switcherLabel.style.cssText = 'color: #ffc107; font-size: 13px; font-weight: 800; display: flex; align-items: center; margin-right: 8px; letter-spacing: 0.5px;';
          switcherLabel.innerHTML = '⚡ OPÇÕES:';
          elements.movieVersionSwitcher.appendChild(switcherLabel);

          fallbacks.forEach((opt, idx) => {
            const btn = document.createElement('button');
            btn.className = `btn btn-sm ${idx === fallbackIdx ? 'btn-primary' : 'btn-secondary'}`;
            btn.style.cssText = 'font-size: 12px; padding: 5px 14px; border-radius: 16px; white-space: nowrap; font-weight: 700;';
            btn.innerHTML = `${idx === fallbackIdx ? '✓ ' : ''}${escapeHtml(opt.name)}`;
            btn.addEventListener('click', () => {
              openLivePlayer(item, idx);
            });
            elements.movieVersionSwitcher.appendChild(btn);
          });
        } else {
          elements.movieVersionSwitcher.style.display = 'none';
          elements.movieVersionSwitcher.innerHTML = '';
        }
      }

      // Popula barra lateral de informações com dados da partida ou canal e EPG
      populateLiveSidebar(item, currentOpt);

      // Inicia stream
      playLiveStreamUrl(streamUrl, isEmbed, title, item, fallbackIdx);
    }

    function playLiveStreamUrl(streamUrl, isEmbed, title, item, fallbackIdx) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      hideVideoErrorOverlay();

      if (liveDriftInterval) {
        clearInterval(liveDriftInterval);
        liveDriftInterval = null;
      }

      if (activeHls) {
        try { activeHls.destroy(); } catch (e) { }
        activeHls = null;
      }

      if (isEmbed) {
        // Modo Embed (Zero Anúncios com Sandbox Bloqueador de Popups)
        elements.videoPlayer.pause();
        elements.videoPlayer.removeAttribute('src');
        elements.videoPlayer.load();
        elements.videoPlayer.style.display = 'none';

        elements.embedPlayer.style.display = 'block';
        // sandbox removido para evitar detectSandbox do Rei dos Embeds

        // Usar a URL limpa do servidor — parâmetros extras causam redirect para homepage
        elements.embedPlayer.src = streamUrl;
      } else {
        // Modo HLS Nativo (Zero Anúncios com Ultra Low-Latency Tuning)
        elements.embedPlayer.src = 'about:blank';
        elements.embedPlayer.style.display = 'none';

        elements.videoPlayer.style.display = 'block';

        const isHlsStream = /\.m3u8(?:$|[?#])/i.test(streamUrl);

        if (window.Hls && Hls.isSupported() && isHlsStream) {
          const hlsConfig = liveBufferMinimizationEnabled ? {
            enableWorker: true,
            lowLatencyMode: true,
            liveSyncDurationCount: 1,        // Sincroniza a 1 segmento da borda ao vivo (~2s)
            liveMaxLatencyDurationCount: 2, // Se afastar mais que 2 segmentos, sincroniza
            maxBufferLength: 2,             // Buffer máximo de apenas 2 segundos para delay mínimo
            maxMaxBufferLength: 4,          // Teto absoluto de 4 segundos
            backBufferLength: 0,            // Descarta histórico imediatamente para liberar memória
            liveDurationInfinity: true,
            highBufferWatchdogPeriod: 1
          } : {
            enableWorker: true,
            lowLatencyMode: false,
            liveSyncDurationCount: 3,
            liveMaxLatencyDurationCount: 6,
            maxBufferLength: 15,
            maxMaxBufferLength: 30,
            backBufferLength: 10
          };

          activeHls = new Hls(hlsConfig);
          activeHls.loadSource(streamUrl);
          activeHls.attachMedia(elements.videoPlayer);
          activeHls.on(Hls.Events.MANIFEST_PARSED, () => {
            elements.videoPlayer.play().catch(() => { });
          });
          activeHls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
              console.warn('HLS Live Fatal Error:', data.type);
              try { activeHls.destroy(); } catch (e) { }
              activeHls = null;
              if (item && item.fallbacks && item.fallbacks[fallbackIdx + 1]) {
                console.log(`HLS indisponível, alternando automaticamente para Servidor ${fallbackIdx + 2}`);
                openLivePlayer(item, fallbackIdx + 1);
              }
            }
          });

          // Monitor de Drift para transmissão em tempo real (Catch-Up Compensator)
          if (liveBufferMinimizationEnabled) {
            liveDriftInterval = setInterval(() => {
              if (!activeHls || !elements.videoPlayer || elements.videoPlayer.paused) return;
              const target = activeHls.liveSyncPosition;
              if (!target || isNaN(target)) return;
              const current = elements.videoPlayer.currentTime;
              const drift = target - current;

              if (drift > 6.0) {
                // Pula diretamente para a borda ao vivo
                elements.videoPlayer.currentTime = target - 1.0;
                elements.videoPlayer.playbackRate = 1.0;
              } else if (drift > 3.0) {
                // Acelera suavemente para aproximar da transmissão ao vivo sem distorcer som
                elements.videoPlayer.playbackRate = 1.08;
              } else if (drift <= 1.5) {
                elements.videoPlayer.playbackRate = 1.0;
              }
            }, 3000);
          }
        } else {
          elements.videoPlayer.src = streamUrl;
          elements.videoPlayer.play().catch(() => { });
        }
      }
    }

    const DEFAULT_LIVE_LOGO = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Crect width='100' height='100' fill='%231a1a1a' rx='10'/%3E%3Ccircle cx='50' cy='50' r='36' fill='%23222' stroke='%23383838' stroke-width='2'/%3E%3Ctext x='50' y='57' font-size='36' text-anchor='middle'%3E📺%3C/text%3E%3C/svg%3E";

    function populateLiveSidebar(item, currentOpt) {
      if (!elements.movieInfoSidebar) return;

      const title = item.name || item.title || '';
      const category = item.categoryLabel || (item.isLiveMatch ? 'Futebol Ao Vivo' : 'TV Ao Vivo');
      const teamLogo = item.isLiveMatch ? getTvTeamLogoUrl(item.homeTeam, item.homeLogo) : '';
      const channelLogo = getTvLogoUrl(item.logo || item.stream_icon || '');
      const logo = (item.poster && !item.poster.includes('8Q8Q4zE')) ? item.poster : (channelLogo || teamLogo || DEFAULT_LIVE_LOGO);

      if (elements.sidebarPoster) {
        elements.sidebarPoster.onerror = () => { elements.sidebarPoster.src = DEFAULT_LIVE_LOGO; };
        elements.sidebarPoster.src = logo;
      }
      if (elements.sidebarTitle) elements.sidebarTitle.textContent = title;
      if (elements.sidebarDirector) elements.sidebarDirector.textContent = currentOpt.name || 'Servidor HD';
      if (elements.sidebarCast) elements.sidebarCast.textContent = item.league || 'Transmissão Ao Vivo';

      if (elements.sidebarGenres) {
        elements.sidebarGenres.innerHTML = `
          <span class="genre-tag">🔴 AO VIVO</span>
          <span class="genre-tag">${escapeHtml(category)}</span>
          <span class="genre-tag" style="background: rgba(0, 200, 83, 0.2); color: #00e676; border-color: rgba(0, 200, 83, 0.4);">🛡️ ZERO ANÚNCIOS</span>
          <span class="genre-tag" style="background: rgba(255, 193, 7, 0.2); color: #ffc107; border-color: rgba(255, 193, 7, 0.4);">⚡ TEMPO REAL</span>
        `;
      }

      if (elements.sidebarPlot) {
        if (item.isLiveMatch) {
          elements.sidebarPlot.innerHTML = `
            <strong>Partida Ao Vivo:</strong> ${escapeHtml(title)}<br>
            <strong>Competição:</strong> ${escapeHtml(item.league || 'Campeonato')}<br>
            <strong>Horário:</strong> ${escapeHtml(item.matchTime || 'Em andamento')}<br><br>
            ⚡ <em>Modo Tempo Real ativo: O buffer foi minimizado para você assistir com o menor atraso possível. Use o botão '⚡ Tempo Real' para pular para o último segundo da transmissão.</em>
          `;
        } else {
          let epgHtml = '';
          if (item.nowTitle) {
            epgHtml += `<div style="background: rgba(255,82,82,0.15); border: 1px solid rgba(255,82,82,0.4); border-radius: 8px; padding: 10px; margin: 10px 0;">
              <div style="font-size: 11px; font-weight: 700; color: #ff5252; text-transform: uppercase;">🔴 No Ar Agora</div>
              <div style="font-size: 13px; font-weight: 600; color: #fff; margin: 4px 0;">${escapeHtml(item.nowTitle)}</div>
              ${item.nowProgress > 0 ? `<div style="width: 100%; height: 4px; background: rgba(255,255,255,0.2); border-radius: 2px; overflow: hidden; margin-top: 6px;"><div style="width: ${Math.min(100, item.nowProgress)}%; height: 100%; background: #ff5252;"></div></div><div style="font-size: 10px; color: #aaa; margin-top: 4px; text-align: right;">${item.nowProgress}% concluído</div>` : ''}
            </div>`;
          }

          if (Array.isArray(item.nextProgrammes) && item.nextProgrammes.length > 0) {
            epgHtml += `<div style="margin-top: 12px;"><strong style="font-size: 11px; text-transform: uppercase; color: #ffc107;">📋 Próximas Atrações:</strong><div style="display: flex; flex-direction: column; gap: 6px; margin-top: 6px;">`;
            item.nextProgrammes.forEach(np => {
              epgHtml += `<div style="font-size: 12px; color: #bbb; display: flex; gap: 8px;"><span style="color: #ffc107; font-weight: 600; min-width: 42px;">${escapeHtml(np.s || '')}</span><span>${escapeHtml(np.t || '')}</span></div>`;
            });
            epgHtml += `</div></div>`;
          }

          elements.sidebarPlot.innerHTML = `
            <strong>Canal:</strong> ${escapeHtml(title)}<br>
            <strong>Categoria:</strong> ${escapeHtml(category)}<br>
            <strong>Servidor Conectado:</strong> ${escapeHtml(currentOpt.name || 'Servidor 1')}<br>
            ${epgHtml}
            <br>⚡ <em>Transmissão contínua com isolamento total contra anúncios e buffer calibrado para menor latência em tempo real.</em>
          `;
        }
      }
    }

    // ==========================================
    function onMovieCardClick(groupOrMovie) {
      openMoviePage(groupOrMovie).catch(error => {
        console.warn('[EPlay] Não foi possível abrir a página do filme:', error);
      });
    }

    // ==========================================
    // MODO HÍBRIDO (VÍDEO 4K + ÁUDIO LEGENDADO COM SINCRONIA)
    // ==========================================
    let currentHybridState = {
      active: false,
      hybridType: null,
      selectedVersion: null,
      groupOrMovie: null,
      allVersions: null,
      videoVersion: null,
      audioVersion: null,
      audioUrl: null,
      audioOffsetMs: 0,
      audioEl: null,
      durationChecked: false,
      durationMatched: true,
      syncInterval: null
    };

    function getOrCreateHybridAudioPlayer() {
      let audioEl = document.getElementById('hybridAudioPlayer');
      if (!audioEl) {
        audioEl = document.createElement('audio');
        audioEl.id = 'hybridAudioPlayer';
        audioEl.preload = 'auto';
        audioEl.style.display = 'none';
        document.body.appendChild(audioEl);
      }
      return audioEl;
    }

    function setAudioOffset(ms) {
      if (!currentHybridState.active) return;
      const num = parseInt(ms, 10);
      currentHybridState.audioOffsetMs = Number.isFinite(num) ? num : 0;
      updateAudioOffsetUI(currentHybridState.audioOffsetMs);
      syncHybridAudioTime(true);
      const sign = currentHybridState.audioOffsetMs > 0 ? '+' : '';
      if (window.showPlayerToast) {
        window.showPlayerToast(`🔊 Sincronia de Áudio: ${sign}${currentHybridState.audioOffsetMs}ms`, 1800);
      }
    }

    function adjustAudioOffset(deltaMs) {
      if (!currentHybridState.active) return;
      setAudioOffset((currentHybridState.audioOffsetMs || 0) + deltaMs);
    }

    function updateAudioOffsetUI(ms) {
      if (!elements.audioOffsetInput) return;
      const sign = ms > 0 ? '+' : '';
      elements.audioOffsetInput.value = `${sign}${ms}ms`;
    }

    function syncHybridAudioTime(forceSnap = false) {
      if (!currentHybridState.active || !currentHybridState.audioEl) return;
      const audio = currentHybridState.audioEl;
      const video = elements.videoPlayer;
      if (!video || !audio) return;

      const targetTime = Math.max(0, video.currentTime + (currentHybridState.audioOffsetMs / 1000));
      const diff = audio.currentTime - targetTime;
      const absDiff = Math.abs(diff);

      if (forceSnap || absDiff > 0.15) {
        audio.currentTime = targetTime;
        audio.playbackRate = video.playbackRate;
      } else if (absDiff > 0.02) {
        if (diff > 0) {
          audio.playbackRate = Math.max(0.5, video.playbackRate * 0.98);
        } else {
          audio.playbackRate = Math.min(2.0, video.playbackRate * 1.02);
        }
      } else {
        if (audio.playbackRate !== video.playbackRate) {
          audio.playbackRate = video.playbackRate;
        }
      }
    }

    function checkHybridDurationsMatch() {
      if (!currentHybridState.active || currentHybridState.durationChecked) return;
      const audio = currentHybridState.audioEl;
      const video = elements.videoPlayer;
      if (!audio || !video) return;

      const vDur = video.duration;
      const aDur = audio.duration;
      if (!Number.isFinite(vDur) || !Number.isFinite(aDur) || vDur <= 0 || aDur <= 0) {
        return;
      }

      currentHybridState.durationChecked = true;
      const diffSec = Math.abs(vDur - aDur);
      const formatSec = (s) => {
        const m = Math.floor(s / 60);
        const sec = Math.floor(s % 60);
        return `${m}m${sec < 10 ? '0' : ''}${sec}s`;
      };

      if (diffSec <= 3.0) {
        currentHybridState.durationMatched = true;
        if (window.showPlayerToast) {
          window.showPlayerToast(`✨ Streams compatíveis! Duração: ${formatSec(vDur)} (Diferença: ${diffSec.toFixed(1)}s)`, 3000);
        }
      } else {
        currentHybridState.durationMatched = false;
        const diffStr = diffSec.toFixed(1);
        const vStr = formatSec(vDur);
        const aStr = formatSec(aDur);
        console.warn('[EPlay Hybrid] Diferença de duração:', diffStr + 's', vStr, 'vs', aStr);

        // Mostrar diálogo de escolha ao usuário
        const oldDlg = document.querySelector('.andplay-hybrid-warning-overlay');
        if (oldDlg) oldDlg.remove();

        const overlay = document.createElement('div');
        overlay.className = 'andplay-hybrid-warning-overlay';
        overlay.style.cssText = 'position:fixed;inset:0;z-index:999999;display:flex;align-items:center;justify-content:center;padding:24px;background:rgba(0,0,0,0.85);backdrop-filter:blur(4px)';
        overlay.innerHTML = `
          <div style="background:#161d2e;border:1px solid rgba(255,193,7,0.4);border-radius:16px;padding:28px 24px;max-width:440px;width:100%;color:#fff;font-family:inherit">
            <div style="font-size:22px;margin-bottom:10px">⚠️ Streams com durações diferentes</div>
            <div style="color:#aab;font-size:13px;line-height:1.6;margin-bottom:18px">
              A versão de <strong style="color:#ffc107">vídeo</strong> tem <strong style="color:#fff">${vStr}</strong>
              e a de <strong style="color:#ffc107">áudio</strong> tem <strong style="color:#fff">${aStr}</strong>
              (diferença de <strong style="color:#ff6b6b">${diffStr}s</strong>).<br><br>
              Streams com cortes diferentes podem ficar <strong style="color:#ff6b6b">fora de sincronia</strong> com o tempo.
              Você pode continuar assim e ajustar manualmente o atraso do áudio, ou usar a faixa nativa.
            </div>
            <div style="display:flex;flex-direction:column;gap:10px">
              <button type="button" id="hybridWarnContinue" style="padding:12px 16px;background:linear-gradient(135deg,rgba(79,195,247,.2),rgba(33,150,243,.1));border:1.5px solid #4fc3f7;border-radius:10px;color:#4fc3f7;font-weight:700;font-size:13px;cursor:pointer;text-align:left">
                ▶ Continuar assim mesmo &nbsp;<small style="font-weight:400;color:#8f99aa">• Ajuste o atraso do áudio nas opções do player</small>
              </button>
              <button type="button" id="hybridWarnFallback" style="padding:12px 16px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.15);border-radius:10px;color:#ccc;font-weight:600;font-size:13px;cursor:pointer;text-align:left">
                🔄 Usar faixa nativa &nbsp;<small style="font-weight:400;color:#8f99aa">• Sem modo híbrido</small>
              </button>
            </div>
          </div>
        `;

        const close = () => overlay.remove();

        overlay.querySelector('#hybridWarnContinue').addEventListener('click', () => {
          close();
          currentHybridState.durationMatched = true; // permitir continuar
          if (window.showPlayerToast) {
            window.showPlayerToast(`⚠️ Diferença de ${diffStr}s — ajuste o atraso do áudio se necessário`, 4000);
          }
        });

        overlay.querySelector('#hybridWarnFallback').addEventListener('click', () => {
          close();
          if (window.showPlayerToast) {
            window.showPlayerToast('Alternando para faixa nativa...', 2500);
          }
          setTimeout(() => {
            if (!currentHybridState.active) return;
            const fallbackVer = currentHybridState.audioVersion || currentHybridState.videoVersion;
            if (fallbackVer && currentHybridState.groupOrMovie) {
              switchLiveMovieVersion(currentHybridState.groupOrMovie, fallbackVer, currentHybridState.allVersions);
            }
          }, 400);
        });

        document.body.appendChild(overlay);
      }
    }

    function startHybridMoviePlayback(groupOrMovie, selectedVersion, allVersions, startPosition = 0) {
      stopHybridAudio();

      const videoVer = selectedVersion.videoVersion;
      const audioVer = selectedVersion.audioVersion;
      if (!videoVer || !audioVer) return;

      const audioExt = audioVer.ext || 'mp4';
      const audioUrl = `${CONFIG.server}/movie/${CONFIG.user}/${CONFIG.pass}/${audioVer.streamId}.${audioExt}`;

      const audioEl = getOrCreateHybridAudioPlayer();
      audioEl.src = audioUrl;
      audioEl.preload = 'auto';

      const masterVol = (elements.videoPlayer && Number.isFinite(elements.videoPlayer.volume)) ? elements.videoPlayer.volume : 0.85;
      audioEl.volume = masterVol;
      audioEl.muted = false;

      // Silencia a imagem de vídeo para reproduzir apenas a faixa de áudio selecionada
      if (elements.videoPlayer) {
        elements.videoPlayer.muted = true;
      }

      currentHybridState = {
        active: true,
        hybridType: selectedVersion.hybridType,
        selectedVersion,
        groupOrMovie,
        allVersions,
        videoVersion: videoVer,
        audioVersion: audioVer,
        audioUrl,
        audioOffsetMs: 0,
        audioEl,
        durationChecked: false,
        durationMatched: true,
        syncInterval: null
      };

      if (elements.hybridModeBadge) elements.hybridModeBadge.style.display = 'inline-flex';
      if (elements.audioSyncControls) elements.audioSyncControls.style.display = 'inline-flex';
      const epAudioSyncStart = document.getElementById('eplayAudioSyncMenu');
      if (epAudioSyncStart) epAudioSyncStart.style.display = 'block';
      updateAudioOffsetUI(0);
      if (window.updatePlayerVolumeUI) window.updatePlayerVolumeUI();

      const onAudioLoaded = () => {
        audioEl.removeEventListener('loadedmetadata', onAudioLoaded);
        checkHybridDurationsMatch();
        const curPos = (elements.videoPlayer ? elements.videoPlayer.currentTime : 0) || startPosition || 0;
        if (curPos > 0) audioEl.currentTime = curPos;
        if (elements.videoPlayer && !elements.videoPlayer.paused) {
          audioEl.play().catch(() => {});
        }
      };
      audioEl.addEventListener('loadedmetadata', onAudioLoaded);

      if (elements.videoPlayer) {
        const onVideoLoaded = () => {
          elements.videoPlayer.removeEventListener('loadedmetadata', onVideoLoaded);
          checkHybridDurationsMatch();
        };
        elements.videoPlayer.addEventListener('loadedmetadata', onVideoLoaded);
      }

      if (currentHybridState.syncInterval) clearInterval(currentHybridState.syncInterval);
      currentHybridState.syncInterval = setInterval(() => {
        if (currentHybridState.active && elements.videoPlayer && !elements.videoPlayer.paused) {
          syncHybridAudioTime(false);
        }
      }, 350);

      if (window.showPlayerToast) {
        const is4kLeg = (selectedVersion.hybridType === '4k_leg_hybrid');
        const desc = is4kLeg ? 'Vídeo 4K Ultra HD + Áudio Legendado' : 'Vídeo Legendado + Áudio Dublado';
        window.showPlayerToast(`✨ Modo Híbrido Ativado: ${desc}`, 3500);
      }
    }

    function stopHybridAudio() {
      if (currentHybridState.syncInterval) {
        clearInterval(currentHybridState.syncInterval);
        currentHybridState.syncInterval = null;
      }
      if (currentHybridState.audioEl) {
        try {
          currentHybridState.audioEl.pause();
          currentHybridState.audioEl.src = '';
        } catch (_) {}
      }
      currentHybridState = {
        active: false,
        hybridType: null,
        selectedVersion: null,
        groupOrMovie: null,
        allVersions: null,
        videoVersion: null,
        audioVersion: null,
        audioUrl: null,
        audioOffsetMs: 0,
        audioEl: null,
        durationChecked: false,
        durationMatched: true,
        syncInterval: null
      };

      if (elements.videoPlayer) {
        elements.videoPlayer.muted = false;
      }

      if (elements.hybridModeBadge) elements.hybridModeBadge.style.display = 'none';
      if (elements.audioSyncControls) elements.audioSyncControls.style.display = 'none';
      const epAudioSyncStop = document.getElementById('eplayAudioSyncMenu');
      if (epAudioSyncStop) epAudioSyncStop.style.display = 'none';
      // Fechar diálogo de aviso de diferença de duração se ainda estiver aberto
      document.querySelector('.andplay-hybrid-warning-overlay')?.remove();
      if (window.updatePlayerVolumeUI) window.updatePlayerVolumeUI();
    }

    window.isHybridAudioActive = () => currentHybridState.active;
    window.getHybridVolume = () => (currentHybridState.audioEl ? currentHybridState.audioEl.volume : (elements.videoPlayer ? elements.videoPlayer.volume : 0.85));
    window.isHybridMuted = () => (currentHybridState.audioEl ? currentHybridState.audioEl.muted : false);
    window.setHybridVolume = (v) => {
      if (currentHybridState.audioEl) {
        currentHybridState.audioEl.volume = v;
        currentHybridState.audioEl.muted = (v === 0);
      }
    };
    window.toggleHybridMute = () => {
      if (currentHybridState.audioEl) {
        currentHybridState.audioEl.muted = !currentHybridState.audioEl.muted;
      }
    };
    window.adjustAudioOffset = adjustAudioOffset;
    window.setAudioOffset = setAudioOffset;

    function openMovieVersionModal(groupOrMovie, versions) {
      const playableVersions = getMovieAllPlayableVersions(groupOrMovie, versions);
      if (playableVersions && playableVersions.length) {
        const preferred = pickPreferredMovieVersion(playableVersions);
        if (preferred) {
          closeMovieVersionModal();
          playMovieVersion(groupOrMovie, preferred, playableVersions);
          return;
        }
      }

      const title = groupOrMovie.name || groupOrMovie.title || 'Filme';
      const poster = groupOrMovie.poster || getBestPosterUrl(groupOrMovie.primaryItem || groupOrMovie);
      const year = groupOrMovie.year || '';
      const rating = (groupOrMovie.rating && Number(groupOrMovie.rating) > 0) ? `★ ${Number(groupOrMovie.rating).toFixed(1)}` : '';

      elements.versionModalTitle.textContent = title;
      elements.versionModalPoster.src = poster;
      elements.versionModalMeta.textContent = [year, rating].filter(Boolean).join(' • ');
      elements.versionOptionsList.innerHTML = '';

      const orderMap = { 'dublado': 1, 'legendado': 2, '4k_dub': 3, '4k_leg': 4, '4k_leg_hybrid': 5, 'leg_dub_hybrid': 6 };
      const sortedVersions = [...playableVersions].sort((a, b) => (orderMap[a.versionInfo.type] || 99) - (orderMap[b.versionInfo.type] || 99));

      sortedVersions.forEach(v => {
        const btn = document.createElement('button');
        btn.className = 'version-btn';
        btn.innerHTML = `
          <div style="display: flex; align-items: center; gap: 14px;">
            <span style="font-size: 24px;">${v.versionInfo.icon}</span>
            <div style="text-align: left;">
              <div style="font-weight: 700; font-size: 14px; color: #fff;">${escapeHtml(v.versionInfo.label)}</div>
              <div style="font-size: 11px; color: #aaa; margin-top: 2px;">${escapeHtml(v.versionInfo.desc)}</div>
            </div>
          </div>
          <span style="font-size: 13px; color: #ffc107; font-weight: 700; display: flex; align-items: center; gap: 4px;">Assistir ▶</span>
        `;
        btn.addEventListener('click', () => {
          closeMovieVersionModal();
          applyUserChosenVersionPreference(v);
          playMovieVersion(groupOrMovie, v, playableVersions);
        });
        elements.versionOptionsList.appendChild(btn);
      });

      elements.movieVersionModal.style.display = 'flex';
    }

    function closeMovieVersionModal() {
      if (elements.movieVersionModal) {
        elements.movieVersionModal.style.display = 'none';
      }
    }

    function playMovieVersion(groupOrMovie, selectedVersion, allVersions) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      const openedFromContentPage = contentPageOpen && currentContentPageType === 'movie';
      const effectivePlayableVersions = getMovieAllPlayableVersions(groupOrMovie, allVersions);
      const isHybrid = !!selectedVersion.isHybrid;
      const effectiveVideoStreamId = isHybrid ? selectedVersion.videoVersion.streamId : selectedVersion.streamId;
      const watchedId = groupOrMovie?.stream_id || groupOrMovie?.primaryItem?.stream_id || effectiveVideoStreamId;
      if (watchedId != null) saveWatchedId('movies', watchedId);

      // Se container_extension for null/ausente, o servidor redireciona .mp4 para HTTP (Mixed Content).
      // Nesses casos, usar .ts que é servido diretamente pelo servidor IPTV sem redirect.
      const rawExt = isHybrid ? selectedVersion.videoVersion.ext : selectedVersion.ext;
      const ext = rawExt || 'ts';
      const videoUrl = `${CONFIG.server}/movie/${CONFIG.user}/${CONFIG.pass}/${effectiveVideoStreamId}.${ext}`;
      const baseTitle = groupOrMovie.name || groupOrMovie.title || 'Filme';
      const displayTitle = (effectivePlayableVersions && effectivePlayableVersions.length > 1)
        ? `${baseTitle} (${selectedVersion.versionInfo?.label || 'Dublado'})`
        : baseTitle;

      setupPlayerVersionSwitcher(groupOrMovie, selectedVersion, effectivePlayableVersions);

      const item = selectedVersion.item || selectedVersion || {};
      const poster = groupOrMovie.poster || item.stream_icon || item.poster || '';
      const year = groupOrMovie.year || item.year || '';
      const plot = groupOrMovie.plot || item.plot || '';
      const cast = groupOrMovie.cast || item.cast || item.actors || groupOrMovie.actors || '';
      const director = groupOrMovie.director || item.director || '';
      const genre = groupOrMovie.genre || item.genre || '';
      const rating = groupOrMovie.rating || item.rating || groupOrMovie.rating_5based || item.rating_5based || '';
      const duration = groupOrMovie.duration || item.duration || groupOrMovie.episode_run_time || item.episode_run_time || '';

      const startPlayback = (startPosition) => {
        saveWatchedId('movies', watchedId);
        openPlayer(displayTitle, videoUrl, 'movie', {
          ...item,
          rawTitle: baseTitle,
          title: baseTitle,
          groupOrMovie,
          selectedVersion,
          allVersions: effectivePlayableVersions,
          fromContentPage: openedFromContentPage,
          poster,
          year,
          plot,
          cast,
          director,
          genre,
          rating,
          duration,
          streamId: effectiveVideoStreamId
        }, startPosition);

        setupPlayerVersionSwitcher(groupOrMovie, selectedVersion, effectivePlayableVersions);
        const mId = groupOrMovie?.stream_id || groupOrMovie?.primaryItem?.stream_id || effectiveVideoStreamId;
        if (mId) setRouteHash('#/filme/' + mId);
        document.title = `${displayTitle} - EPlay`;

        if (isHybrid) {
          startHybridMoviePlayback(groupOrMovie, selectedVersion, effectivePlayableVersions, startPosition);
        } else {
          stopHybridAudio();
          if (isLegendadoMedia(selectedVersion)) {
            disableActiveSubtitle('Formato: MP4 • Legenda impressa no frame');
          }
        }
      };

      startVodWithResume('movie', effectiveVideoStreamId, displayTitle, startPlayback);
    }

    function setupPlayerVersionSwitcher(groupOrMovie, activeVersion, allVersions) {
      if (elements.movieVersionSwitcher) {
        elements.movieVersionSwitcher.style.display = 'none';
        elements.movieVersionSwitcher.innerHTML = '';
      }

      const effectiveVersions = getMovieAllPlayableVersions(groupOrMovie, allVersions);
      const epSec = document.getElementById('eplayVersionSection');
      const epBadge = document.getElementById('eplayVersionBadge');
      const epItems = document.getElementById('eplayVersionItems');
      const panelSec = elements.panelVersionSection || document.getElementById('panelVersionSection');
      const panelSwitcher = elements.panelVersionSwitcher || document.getElementById('panelVersionSwitcher');
      const panelHint = elements.panelVersionHint || document.getElementById('panelVersionHint');

      const epAudioBtn = document.getElementById('eplayAudioBtn');
      const activeLabel = activeVersion?.versionInfo?.label || (groupOrMovie?.name && isLegendadoMedia(groupOrMovie) ? 'Legendado' : 'Dublado');
      if (epAudioBtn) {
        epAudioBtn.style.display = 'inline-flex';
        epAudioBtn.title = `Áudio / Versão: ${activeLabel}`;
        epAudioBtn.setAttribute('aria-label', `Áudio e Versão (${activeLabel})`);
      }
      if (epBadge) epBadge.textContent = activeLabel;
      if (panelHint) panelHint.textContent = `(Atual: ${activeLabel})`;

      if (!effectiveVersions || effectiveVersions.length <= 1) {
        if (epSec) epSec.style.display = 'block';
        if (epItems) {
          epItems.innerHTML = `
            <div class="eplay-version-item active" style="cursor: default; opacity: 0.9;">
              <span style="display:flex;align-items:center;gap:7px;">
                <span style="font-size:14px;">🎧</span>
                <span>${escapeHtml(activeLabel)} (Padrão)</span>
              </span>
              <span class="eplay-version-check" style="font-size: 10px; color: #aaa;">Faixa única</span>
            </div>
            <div style="font-size: 10px; color: #888; padding: 4px 6px; line-height: 1.3;">
              Este título possui uma única versão de áudio disponível no catálogo.
            </div>
          `;
        }
        if (panelSec) panelSec.style.display = 'none';
        if (panelSwitcher) panelSwitcher.innerHTML = '';
        return;
      }

      if (epSec) epSec.style.display = 'block';
      const orderMap = { 'dublado': 1, 'legendado': 2, '4k_dub': 3, '4k_leg': 4, '4k_leg_hybrid': 5, 'leg_dub_hybrid': 6 };
      const sorted = [...effectiveVersions].sort((a, b) => (orderMap[a.versionInfo?.type] || 99) - (orderMap[b.versionInfo?.type] || 99));
      if (epItems) {
        epItems.innerHTML = '';
        sorted.forEach(v => {
          const isCurrent = (v.streamId === activeVersion.streamId || (v.isHybrid && activeVersion.isHybrid && v.versionInfo?.type === activeVersion.versionInfo?.type));
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = `eplay-version-item${isCurrent ? ' active' : ''}`;
          btn.innerHTML = `
            <span style="display:flex;align-items:center;gap:7px;pointer-events:none;">
              <span style="font-size:14px;">${v.versionInfo?.icon || '🎬'}</span>
              <span>${escapeHtml(v.versionInfo?.label || 'Versão')}</span>
            </span>
            ${isCurrent ? '<span class="eplay-version-check">✓</span>' : ''}
          `;
          btn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (isCurrent) return;
            applyUserChosenVersionPreference(v);
            switchLiveMovieVersion(groupOrMovie, v, effectiveVersions);
            if (window.showPlayerToast) {
              window.showPlayerToast(`Áudio / Versão alterado para ${v.versionInfo?.label}`);
            }
          });
          epItems.appendChild(btn);
        });
      }

      if (panelSec) panelSec.style.display = 'flex';
      if (panelSwitcher) {
        panelSwitcher.innerHTML = '';
        sorted.forEach(v => {
          const isCurrent = (v.streamId === activeVersion.streamId || (v.isHybrid && activeVersion.isHybrid && v.versionInfo?.type === activeVersion.versionInfo?.type));
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = `btn ${isCurrent ? 'btn-primary' : 'btn-secondary'}`;
          btn.style.cssText = 'padding: 5px 12px; font-size: 11px; display: inline-flex; align-items: center; gap: 5px; cursor: pointer; border-radius: 6px; font-weight: 600;';
          btn.innerHTML = `${isCurrent ? '✓ ' : ''}${v.versionInfo?.icon || '🎬'} ${escapeHtml(v.versionInfo?.label || 'Versão')}`;
          btn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (isCurrent) return;
            applyUserChosenVersionPreference(v);
            switchLiveMovieVersion(groupOrMovie, v, effectiveVersions);
            if (window.showPlayerToast) {
              window.showPlayerToast(`Áudio / Versão alterado para ${v.versionInfo?.label}`);
            }
          });
          panelSwitcher.appendChild(btn);
        });
      }
    }

    function switchLiveMovieVersion(groupOrMovie, targetVersion, allVersions) {
      const currentPos = elements.videoPlayer ? elements.videoPlayer.currentTime : 0;
      const wasPaused = elements.videoPlayer ? elements.videoPlayer.paused : false;
      saveCurrentVodProgress();
      stopVodProgressTracking(false);

      const effectiveVersions = getMovieAllPlayableVersions(groupOrMovie, allVersions);
      const isHybrid = !!targetVersion.isHybrid;
      const effectiveVideoStreamId = isHybrid ? targetVersion.videoVersion.streamId : targetVersion.streamId;
      const ext = (isHybrid ? targetVersion.videoVersion.ext : targetVersion.ext) || 'mp4';
      const newUrl = `${CONFIG.server}/movie/${CONFIG.user}/${CONFIG.pass}/${effectiveVideoStreamId}.${ext}`;
      const baseTitle = groupOrMovie.name || groupOrMovie.title || 'Filme';
      const newTitle = `${baseTitle} (${targetVersion.versionInfo.label})`;

      activeVideoUrl = newUrl;
      elements.modalTitle.textContent = newTitle;
      elements.downloadBtn.href = newUrl;
      elements.videoPlayer.src = newUrl;

      if (currentSubContext && currentSubContext.mediaMeta) {
        currentSubContext.mediaMeta.selectedVersion = targetVersion;
      }
      if (currentPlaybackMeta) {
        currentPlaybackMeta.streamId = effectiveVideoStreamId;
        currentPlaybackMeta.url = newUrl;
        currentPlaybackMeta.title = newTitle;
        if (currentPlaybackMeta.mediaMeta) currentPlaybackMeta.mediaMeta.selectedVersion = targetVersion;
      }

      setupPlayerVersionSwitcher(groupOrMovie, targetVersion, effectiveVersions);

      // Salva a escolha do usuário na conta para ser usada sempre como padrão futuro
      applyUserChosenVersionPreference(targetVersion);

      if (isHybrid) {
        startHybridMoviePlayback(groupOrMovie, targetVersion, effectiveVersions, currentPos);
        if (targetVersion.isHybrid4kLeg) {
          autoFetchSubtitle(baseTitle, 'movie', currentPlaybackMeta ? currentPlaybackMeta.mediaMeta : { title: baseTitle, selectedVersion: targetVersion });
        } else if (targetVersion.isHybridLegDub) {
          disableActiveSubtitle('Formato: MP4 • Legenda impressa no frame');
        }
      } else {
        stopHybridAudio();
        const isLeg = isLegendadoMedia(targetVersion);
        if (isLeg) {
          disableActiveSubtitle('Legenda externa desativada (Vídeo já possui legenda impressa no frame)');
          closeSubOptionsPanel();
        } else {
          if (elements.subSelect && elements.subSelect.options[0] && elements.subSelect.options[0].value === 'none') {
            elements.subSelect.options[0].textContent = 'Desativada';
          }
        }
      }

      function onLoaded() {
        elements.videoPlayer.removeEventListener('loadedmetadata', onLoaded);
        if (currentPos > 0) {
          elements.videoPlayer.currentTime = currentPos;
        }
        if (!wasPaused) {
          elements.videoPlayer.play().catch(() => { });
        }
        startVodProgressTracking();
      }

      elements.videoPlayer.addEventListener('loadedmetadata', onLoaded);
      const retryTvLikeAutoplay = () => {
        elements.videoPlayer.play().catch(() => { });
        elements.videoPlayer.removeEventListener('canplay', retryTvLikeAutoplay);
      };
      elements.videoPlayer.addEventListener('canplay', retryTvLikeAutoplay, { once: true });
      elements.modalFormat.textContent = `Versão alterada para ${targetVersion.versionInfo.label}`;
    }

    // Cache de temporadas/episódios de séries para alternância rápida sem recarregar tela
    const seriesDataCache = new Map();

    async function getOrFetchSeriesInfo(seriesId) {
      if (!seriesId) return null;
      const key = String(seriesId);
      if (seriesDataCache.has(key)) {
        return seriesDataCache.get(key);
      }
      const data = await xtreamApi('get_series_info', `&series_id=${encodeURIComponent(seriesId)}`);
      if (data && data.episodes) {
        seriesDataCache.set(key, data);
      }
      return data;
    }

    function setupPlayerSeriesVersionSwitcher(seriesGroup, activeVersion, seasonNum, epNum) {
      if (elements.movieVersionSwitcher) {
        elements.movieVersionSwitcher.style.display = 'none';
        elements.movieVersionSwitcher.innerHTML = '';
      }

      const versions = seriesGroup?.versions || [];
      const epSec = document.getElementById('eplayVersionSection');
      const epBadge = document.getElementById('eplayVersionBadge');
      const epItems = document.getElementById('eplayVersionItems');
      const panelSec = elements.panelVersionSection || document.getElementById('panelVersionSection');
      const panelSwitcher = elements.panelVersionSwitcher || document.getElementById('panelVersionSwitcher');
      const panelHint = elements.panelVersionHint || document.getElementById('panelVersionHint');

      const epAudioBtn = document.getElementById('eplayAudioBtn');
      const activeLabel = activeVersion?.versionInfo?.label || (seriesGroup?.name && isLegendadoMedia(seriesGroup) ? 'Legendado' : 'Dublado');
      if (epAudioBtn) {
        epAudioBtn.style.display = 'inline-flex';
        epAudioBtn.title = `Áudio / Versão: ${activeLabel}`;
        epAudioBtn.setAttribute('aria-label', `Áudio e Versão (${activeLabel})`);
      }
      if (epBadge) epBadge.textContent = activeLabel;
      if (panelHint) panelHint.textContent = `(Atual: ${activeLabel})`;

      if (!versions || versions.length <= 1) {
        if (epSec) epSec.style.display = 'block';
        if (epItems) {
          epItems.innerHTML = `
            <div class="eplay-version-item active" style="cursor: default; opacity: 0.9;">
              <span style="display:flex;align-items:center;gap:7px;">
                <span style="font-size:14px;">🎧</span>
                <span>${escapeHtml(activeLabel)} (Padrão)</span>
              </span>
              <span class="eplay-version-check" style="font-size: 10px; color: #aaa;">Faixa única</span>
            </div>
            <div style="font-size: 10px; color: #888; padding: 4px 6px; line-height: 1.3;">
              Esta série possui uma única versão de áudio disponível no catálogo.
            </div>
          `;
        }
        if (panelSec) panelSec.style.display = 'none';
        if (panelSwitcher) panelSwitcher.innerHTML = '';
        return;
      }

      if (epSec) epSec.style.display = 'block';
      if (epItems) {
        epItems.innerHTML = '';
        sorted.forEach(v => {
          const isCurrent = activeVersion && (v.seriesId === activeVersion.seriesId);
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = `eplay-version-item${isCurrent ? ' active' : ''}`;
          btn.innerHTML = `
            <span style="display:flex;align-items:center;gap:7px;pointer-events:none;">
              <span style="font-size:14px;">${v.versionInfo?.icon || '🎬'}</span>
              <span>${escapeHtml(v.versionInfo?.label || 'Versão')}</span>
            </span>
            ${isCurrent ? '<span class="eplay-version-check">✓</span>' : ''}
          `;
          btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (isCurrent) return;
            applyUserChosenVersionPreference(v);
            if (window.showPlayerToast) {
              window.showPlayerToast(`Alternando áudio para ${v.versionInfo?.label}...`);
            }
            await switchSeriesEpisodeInPlayer(v, seasonNum, epNum);
          });
          epItems.appendChild(btn);
        });
      }

      if (panelSec) panelSec.style.display = 'flex';
      if (panelSwitcher) {
        panelSwitcher.innerHTML = '';
        sorted.forEach(v => {
          const isCurrent = activeVersion && (v.seriesId === activeVersion.seriesId);
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = `btn ${isCurrent ? 'btn-primary' : 'btn-secondary'}`;
          btn.style.cssText = 'padding: 5px 12px; font-size: 11px; display: inline-flex; align-items: center; gap: 5px; cursor: pointer; border-radius: 6px; font-weight: 600;';
          btn.innerHTML = `${isCurrent ? '✓ ' : ''}${v.versionInfo?.icon || '🎬'} ${escapeHtml(v.versionInfo?.label || 'Versão')}`;
          btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (isCurrent) return;
            applyUserChosenVersionPreference(v);
            if (window.showPlayerToast) {
              window.showPlayerToast(`Alternando áudio para ${v.versionInfo?.label}...`);
            }
            await switchSeriesEpisodeInPlayer(v, seasonNum, epNum);
          });
          panelSwitcher.appendChild(btn);
        });
      }
    }

    async function switchSeriesEpisodeInPlayer(targetVersion, seasonNum, epNum) {
      if (!targetVersion || !currentSeriesGroup) return;
      const currentPos = elements.videoPlayer ? elements.videoPlayer.currentTime : 0;
      const wasPaused = elements.videoPlayer ? elements.videoPlayer.paused : false;
      saveCurrentVodProgress();
      stopVodProgressTracking(false);

      try {
        const data = await getOrFetchSeriesInfo(targetVersion.seriesId);
        const seasonEps = (data?.episodes && data.episodes[seasonNum]) || [];
        const matchedEp = seasonEps.find(e => Number(e.episode_num) === Number(epNum)) || seasonEps[0];
        if (!matchedEp) {
          if (window.showPlayerToast) {
            window.showPlayerToast(`⚠️ Episódio não encontrado em ${targetVersion.versionInfo.label}`, 3000);
          }
          return;
        }

        currentActiveSeriesVersion = targetVersion;
        const ext = matchedEp.container_extension || 'mp4';
        const newUrl = `${CONFIG.server}/series/${CONFIG.user}/${CONFIG.pass}/${matchedEp.id}.${ext}`;
        const sName = currentSeriesGroup.name || data.info?.name || 'Série';
        const epTitle = matchedEp.title || `Episódio ${matchedEp.episode_num}`;
        const newTitle = `${sName} - ${epTitle} (${targetVersion.versionInfo.label})`;

        activeVideoUrl = newUrl;
        elements.modalTitle.textContent = newTitle;
        elements.downloadBtn.href = newUrl;
        elements.videoPlayer.src = newUrl;

        if (currentPlaybackMeta) {
          currentPlaybackMeta.streamId = matchedEp.id;
          currentPlaybackMeta.url = newUrl;
          currentPlaybackMeta.title = newTitle;
          currentPlaybackMeta.selectedVersion = targetVersion;
          currentPlaybackMeta.versionInfo = targetVersion.versionInfo;
          currentPlaybackMeta.ep = matchedEp;
        }

        setupPlayerSeriesVersionSwitcher(currentSeriesGroup, currentActiveSeriesVersion, seasonNum, epNum);

        function onLoaded() {
          elements.videoPlayer.removeEventListener('loadedmetadata', onLoaded);
          if (currentPos > 0) {
            try { elements.videoPlayer.currentTime = currentPos; } catch (_) {}
          }
          if (!wasPaused) {
            elements.videoPlayer.play().catch(() => {});
          }
          startVodProgressTracking('series', matchedEp.id, newTitle);
        }
        elements.videoPlayer.addEventListener('loadedmetadata', onLoaded, { once: true });
        elements.videoPlayer.load();

        if (window.showPlayerToast) {
          window.showPlayerToast(`✨ Áudio alterado para: ${targetVersion.versionInfo.label}`, 2500);
        }
      } catch (err) {
        console.error('[EPlay Series] Erro ao alternar versão do episódio no player:', err);
        if (window.showPlayerToast) {
          window.showPlayerToast(`❌ Erro ao alternar versão: ${err.message}`, 3000);
        }
      }
    }

    // ==========================================
    // REPRODUÇÃO DE VÍDEO (FILMES E EPISÓDIOS)
    // ==========================================
    let currentRawSubtitleText = '';
    let currentSubtitleLabel = '';
    let currentSubtitleOffset = 0.0;
    let currentParsedCues = [];
    let currentSubtitleActive = false;
    let lastActiveCueHtml = null;
    let currentSubContext = {
      rawTitle: '',
      mediaType: 'movie',
      mediaMeta: null,
      cleanTitle: '',
      targetYear: null,
      catalogType: 'movie',
      candidates: []
    };

    function parseSubtitleTimestamp(timeStr) {
      if (!timeStr) return null;
      const clean = timeStr.trim().replace(',', '.');
      const parts = clean.split(':');
      if (parts.length === 3) {
        const h = parseFloat(parts[0]) || 0;
        const m = parseFloat(parts[1]) || 0;
        const s = parseFloat(parts[2]) || 0;
        return h * 3600 + m * 60 + s;
      } else if (parts.length === 2) {
        const m = parseFloat(parts[0]) || 0;
        const s = parseFloat(parts[1]) || 0;
        return m * 60 + s;
      } else if (parts.length === 1) {
        const s = parseFloat(parts[0]);
        return Number.isFinite(s) ? s : null;
      }
      return null;
    }

    function parseSubtitleTextToCues(rawText, offsetSeconds = 0) {
      if (!rawText || typeof rawText !== 'string') return [];
      const normalized = rawText
        .replace(/^\uFEFF/, '')
        .replace(/\r\n/g, '\n')
        .replace(/\r/g, '\n');

      const cues = [];
      const blocks = normalized.trim().split(/\n\s*\n+/);

      for (let b = 0; b < blocks.length; b++) {
        const block = blocks[b].trim();
        if (!block || block.startsWith('NOTE') || block === 'WEBVTT' || block.startsWith('WEBVTT\n')) {
          continue;
        }
        const lines = block.split('\n');
        let timeIndex = -1;
        for (let l = 0; l < lines.length; l++) {
          if (lines[l].includes('-->')) {
            timeIndex = l;
            break;
          }
        }
        if (timeIndex === -1) continue;

        const parts = lines[timeIndex].split('-->');
        if (parts.length !== 2) continue;

        const startMatch = parts[0].trim().match(/(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?/);
        const endMatch = parts[1].trim().match(/(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?/);
        if (!startMatch || !endMatch) continue;

        const parsedStart = parseSubtitleTimestamp(startMatch[0]);
        const parsedEnd = parseSubtitleTimestamp(endMatch[0]);
        if (parsedStart === null || parsedEnd === null) continue;

        let start = parsedStart + offsetSeconds;
        let end = parsedEnd + offsetSeconds;
        if (end <= start) continue;
        if (start < 0) start = 0;

        const textLines = lines.slice(timeIndex + 1);
        const rawCueText = textLines.join('\n').trim();
        if (!rawCueText) continue;

        cues.push({
          start,
          end,
          text: rawCueText
        });
      }

      cues.sort((a, b) => a.start - b.start);
      return cues;
    }

    function formatCueText(txt) {
      if (!txt) return '';
      const div = document.createElement('div');
      div.textContent = txt;
      let safe = div.innerHTML;
      return safe.replace(/&lt;(\/?)i&gt;/gi, '<$1i>')
                 .replace(/&lt;(\/?)b&gt;/gi, '<$1b>')
                 .replace(/&lt;(\/?)u&gt;/gi, '<$1u>');
    }

    function updateSubtitleOverlay() {
      const overlay = elements.subtitleOverlay || document.getElementById('eplaySubtitleOverlay');
      if (!overlay) return;

      if (!currentSubtitleActive || !currentParsedCues.length || !elements.videoPlayer) {
        if (overlay.style.display !== 'none') {
          overlay.style.display = 'none';
          overlay.innerHTML = '';
          lastActiveCueHtml = null;
        }
        return;
      }

      const curTime = elements.videoPlayer.currentTime;
      const activeCues = [];
      for (let i = 0; i < currentParsedCues.length; i++) {
        const cue = currentParsedCues[i];
        if (curTime >= cue.start && curTime <= cue.end) {
          activeCues.push(cue);
        } else if (cue.start > curTime) {
          break;
        }
      }

      if (activeCues.length === 0) {
        if (overlay.style.display !== 'none') {
          overlay.style.display = 'none';
          overlay.innerHTML = '';
          lastActiveCueHtml = null;
        }
        return;
      }

      const html = activeCues
        .map(c => `<div class="eplay-subtitle-cue">${formatCueText(c.text)}</div>`)
        .join('');

      if (html !== lastActiveCueHtml) {
        overlay.innerHTML = html;
        lastActiveCueHtml = html;
      }
      if (overlay.style.display !== 'block') {
        overlay.style.display = 'block';
      }
    }

    function disableActiveSubtitle(reasonMessage = '') {
      currentRawSubtitleText = '';
      currentSubtitleLabel = '';
      currentSubtitleOffset = 0.0;
      currentParsedCues = [];
      currentSubtitleActive = false;
      lastActiveCueHtml = null;
      updateSubtitleOverlay();

      if (elements.subOffsetDisplay) elements.subOffsetDisplay.textContent = '0.0s';
      if (elements.subSyncControls) elements.subSyncControls.style.display = 'none';
      if (elements.subSelect) {
        elements.subSelect.value = 'none';
        const isLeg = isLegendadoMedia(currentSubContext?.mediaMeta) || isLegendadoMedia({ name: currentSubContext?.rawTitle });
        const firstOpt = elements.subSelect.options[0];
        if (firstOpt && firstOpt.value === 'none') {
          firstOpt.textContent = isLeg ? 'Desativada (Legenda já no vídeo)' : 'Desativada';
        }
      }

      if (elements.videoPlayer) {
        const oldTracks = elements.videoPlayer.querySelectorAll('track');
        oldTracks.forEach(t => {
          if (t.src && t.src.startsWith('blob:')) {
            try { URL.revokeObjectURL(t.src); } catch (_) {}
          }
          t.remove();
        });
        if (elements.videoPlayer.textTracks) {
          for (let i = 0; i < elements.videoPlayer.textTracks.length; i++) {
            try {
              elements.videoPlayer.textTracks[i].mode = 'disabled';
            } catch (e) { }
          }
        }
      }

      if (reasonMessage && elements.modalFormat) {
        elements.modalFormat.textContent = reasonMessage;
      }
    }

    // ==========================================
    // TRATAMENTO DE STREAMS INDISPONÍVEIS (503 / 404 / TIMEOUT)
    // ==========================================
    const BROKEN_STREAM_EXPIRY_MS = 4 * 60 * 60 * 1000; // 4 horas de validade

    function markStreamBroken(streamId) {
      if (!streamId) return;
      try {
        localStorage.setItem(`andplay_broken_stream_${streamId}`, Date.now().toString());
      } catch (e) { }
    }

    function clearStreamBroken(streamId) {
      if (!streamId) return;
      try {
        localStorage.removeItem(`andplay_broken_stream_${streamId}`);
      } catch (e) { }
    }

    function isStreamMarkedBroken(streamId) {
      if (!streamId) return false;
      try {
        const val = localStorage.getItem(`andplay_broken_stream_${streamId}`);
        if (!val) return false;
        const time = Number(val);
        if (Date.now() - time > BROKEN_STREAM_EXPIRY_MS) {
          localStorage.removeItem(`andplay_broken_stream_${streamId}`);
          return false;
        }
        return true;
      } catch (e) {
        return false;
      }
    }

    let videoLoadTimeout = null;
    let currentPlaybackMeta = null;
    let skipIntroRequestSeq = 0;
    let skipIntroState = { segment: null, source: '', used: false };


    function retryCurrentVideoSource(automatic = false) {
      if (!currentPlaybackMeta || !activeVideoUrl || !elements.videoPlayer) return false;
      if (automatic) {
        if (Number(currentPlaybackMeta.autoRetryCount || 0) >= 2) return false;
        currentPlaybackMeta.autoRetryCount = (currentPlaybackMeta.autoRetryCount || 0) + 1;

        // 1ª tentativa automática: trocar .mp4 por .ts para evitar Mixed Content e problemas
        //   de redirect HTTP. O servidor IPTV serve .ts diretamente sem redirect.
        if (currentPlaybackMeta.autoRetryCount === 1) {
          const baseUrl = String(activeVideoUrl).split('?')[0];
          if (baseUrl.endsWith('.mp4')) {
            const tsUrl = baseUrl.replace(/\.mp4$/, '.ts');
            activeVideoUrl = tsUrl;
            currentPlaybackMeta.url = tsUrl;
            hideVideoErrorOverlay();
            elements.videoPlayer.src = tsUrl;
            try { elements.videoPlayer.load(); } catch (e) {}
            elements.videoPlayer.play().catch(() => {});
            startVideoLoadTimeout();
            return true;
          }
        }
        // 2ª tentativa: recarregar a URL original com cache-buster
      }

      hideVideoErrorOverlay();
      const baseUrl = String(activeVideoUrl).split('?retry=')[0];
      const separator = baseUrl.includes('?') ? '&' : '?';
      const retryUrl = baseUrl + separator + 'retry=' + Date.now();
      elements.videoPlayer.src = retryUrl;
      try { elements.videoPlayer.load(); } catch (e) {}
      elements.videoPlayer.play().catch(() => {});
      startVideoLoadTimeout();
      return true;
    }

    function startVideoLoadTimeout() {
      if (videoLoadTimeout) clearTimeout(videoLoadTimeout);
      if (currentPlaybackMeta?.mediaType === 'live') return;
      // Uma tentativa automática resolve falhas transitórias que ocorrem no primeiro acesso ao stream.
      videoLoadTimeout = setTimeout(() => {
        if (currentPlaybackMeta?.mediaType === 'live') return;
        if (elements.videoPlayer && elements.videoPlayer.readyState === 0 && elements.videoModal.style.display === 'flex') {
          // Se for 4K e travou no carregamento, tenta fallback automático para Full HD antes de exibir erro
          const currentMeta = currentPlaybackMeta;
          const mediaMeta = currentMeta?.mediaMeta;
          const allVers = mediaMeta?.allVersions || [];
          const currentV = mediaMeta?.selectedVersion;
          if (currentV && (currentV.versionInfo?.type?.includes('4k') || currentV.isHybrid)) {
            const isLeg = isLegendadoMedia(currentV);
            const fhdFallback = allVers.find(v => v.versionInfo?.type === (isLeg ? 'legendado' : 'dublado'))
                             || allVers.find(v => v.versionInfo?.type === 'dublado')
                             || allVers.find(v => v.versionInfo?.type === 'legendado');
            if (fhdFallback && fhdFallback.streamId !== currentV.streamId) {
              console.warn('[EPlay Player] Timeout na versão 4K. Alternando automaticamente para Full HD:', fhdFallback);
              if (window.showPlayerToast) {
                window.showPlayerToast('Alternando para versão Full HD...');
              }
              switchLiveMovieVersion(mediaMeta.groupOrMovie || currentMeta, fhdFallback, allVers);
              return;
            }
          }
          if (retryCurrentVideoSource(true)) return;
          showVideoErrorOverlay('timeout', 'O servidor de transmissão demorou muito para responder (tempo limite esgotado). O link pode estar inacessível ou fora do ar.');
        }
      }, 14000);
    }

    function hideVideoErrorOverlay() {
      if (videoLoadTimeout) {
        clearTimeout(videoLoadTimeout);
        videoLoadTimeout = null;
      }
      if (elements.videoErrorOverlay) {
        elements.videoErrorOverlay.style.display = 'none';
        elements.videoErrorActions.innerHTML = '';
      }
    }

    function showVideoErrorOverlay(errorType = 'server_503', customMsg = '') {
      if (!elements.videoErrorOverlay) return;

      const meta = currentPlaybackMeta || {};
      const isSeries = (meta.mediaType === 'series');
      const streamId = meta.streamId || (meta.mediaMeta && (meta.mediaMeta.stream_id || meta.mediaMeta.streamId));

      if (streamId) {
        markStreamBroken(streamId);
      }

      elements.videoErrorTitle.textContent = isSeries
        ? 'Episódio Temporariamente Indisponível'
        : 'Vídeo Temporariamente Indisponível';

      let msg = customMsg || 'O servidor de transmissão reportou que este arquivo está indisponível ou inacessível no momento (Erro 503 / Stream Temporarily Unavailable).';
      elements.videoErrorMessage.textContent = msg;
      elements.modalFormat.textContent = 'Erro de Reprodução: Stream indisponível no servidor (503)';

      elements.videoErrorActions.innerHTML = '';

      // 1. Tentar Versão Alternativa (Dublado <-> Legendado)
      if (isSeries && currentSeriesGroup && currentSeriesGroup.versions && currentSeriesGroup.versions.length > 1) {
        const altVersions = currentSeriesGroup.versions.filter(v => v.seriesId !== currentActiveSeriesVersion?.seriesId);
        altVersions.forEach(v => {
          const btn = document.createElement('button');
          btn.className = 'btn btn-primary';
          btn.style.padding = '8px 16px';
          btn.style.fontSize = '12px';
          btn.innerHTML = `${v.versionInfo.icon} Tentar na Versão ${escapeHtml(v.versionInfo.label)}`;
          btn.addEventListener('click', () => {
            switchSeriesEpisodeToVersion(v, meta.seasonNum, meta.episodeNum);
          });
          elements.videoErrorActions.appendChild(btn);
        });
      } else if (!isSeries && meta.mediaMeta?.allVersions && meta.mediaMeta.allVersions.length > 1) {
        const currentVId = meta.mediaMeta.selectedVersion?.streamId;
        const altMovieVersions = meta.mediaMeta.allVersions.filter(v => v.streamId !== currentVId);
        altMovieVersions.forEach(v => {
          const btn = document.createElement('button');
          btn.className = 'btn btn-primary';
          btn.style.padding = '8px 16px';
          btn.style.fontSize = '12px';
          btn.innerHTML = `${v.versionInfo.icon} Tentar Versão ${escapeHtml(v.versionInfo.label)}`;
          btn.addEventListener('click', () => {
            hideVideoErrorOverlay();
            switchLiveMovieVersion(meta.mediaMeta.groupOrMovie, v, meta.mediaMeta.allVersions);
          });
          elements.videoErrorActions.appendChild(btn);
        });
      }

      // 2. Próximo Episódio (Séries)
      if (isSeries && currentSeriesData?.episodes && meta.seasonNum && meta.episodeNum) {
        const seasonEps = currentSeriesData.episodes[meta.seasonNum] || [];
        const nextEp = seasonEps.find(e => Number(e.episode_num) === Number(meta.episodeNum) + 1);
        if (nextEp) {
          const nextBtn = document.createElement('button');
          nextBtn.className = 'btn btn-secondary';
          nextBtn.style.padding = '8px 16px';
          nextBtn.style.fontSize = '12px';
          nextBtn.innerHTML = `⏭️ Pular para Episódio ${nextEp.episode_num}`;
          nextBtn.addEventListener('click', () => {
            hideVideoErrorOverlay();
            playSeriesEpisode(nextEp, meta.seasonNum);
          });
          elements.videoErrorActions.appendChild(nextBtn);
        }
      }

      // 3. Tentar Novamente
      const retryBtn = document.createElement('button');
      retryBtn.className = 'btn btn-secondary';
      retryBtn.style.padding = '8px 16px';
      retryBtn.style.fontSize = '12px';
      retryBtn.innerHTML = '🔄 Tentar Novamente';
      retryBtn.addEventListener('click', () => {
        elements.modalFormat.textContent = 'Reconectando ao servidor...';
        retryCurrentVideoSource(false);
      });
      elements.videoErrorActions.appendChild(retryBtn);

      // 4. Voltar / Fechar
      const backBtn = document.createElement('button');
      backBtn.className = 'btn btn-secondary';
      backBtn.style.padding = '8px 16px';
      backBtn.style.fontSize = '12px';
      backBtn.innerHTML = isSeries ? '📋 Voltar aos Episódios' : '❌ Fechar Player';
      backBtn.addEventListener('click', () => {
        closePlayer();
      });
      elements.videoErrorActions.appendChild(backBtn);

      elements.videoErrorOverlay.style.display = 'flex';
    }

    async function switchSeriesEpisodeToVersion(targetVersion, seasonNum, epNum) {
      hideVideoErrorOverlay();
      applyUserChosenVersionPreference(targetVersion);
      await switchSeriesEpisodeInPlayer(targetVersion, seasonNum, epNum);
    }

    function openPlayer(title, url, mediaType = 'movie', mediaMeta = null, startPosition = 0) {
      stopVodProgressTracking();
      // Destrói imediatamente qualquer loading de página ou catálogo anterior
      hideLoading();

      // Pausa e cancela todos os timers de segundo plano para poupar 100% dos recursos para o player
      if (moviesHeroTimer) { clearInterval(moviesHeroTimer); moviesHeroTimer = null; }
      if (seriesHeroTimer) { clearInterval(seriesHeroTimer); seriesHeroTimer = null; }
      if (homeFeaturedTimer) { clearInterval(homeFeaturedTimer); homeFeaturedTimer = null; }
      window.EPlayTvEpg?.deactivate();

      const safeStartPosition = Number.isFinite(Number(startPosition)) ? Math.max(0, Number(startPosition)) : 0;
      activeVideoUrl = url;
      currentPlaybackMeta = {
        title,
        url,
        mediaType,
        mediaMeta,
        seasonNum: mediaMeta?.seasonNum || mediaMeta?.season,
        episodeNum: mediaMeta?.episodeNum,
        streamId: mediaMeta?.streamId || mediaMeta?.stream_id,
        autoRetryCount: 0,
        startPosition: safeStartPosition
      };
      resetSkipIntroUi();
      updateSkipIntroAutoUi(mediaType);
      hideVideoErrorOverlay();

      elements.modalTitle.textContent = title;
      elements.videoPlayer.src = url;
      elements.downloadBtn.href = url;
      elements.videoModal.classList.add('eplay-player-page');
      document.body.classList.add('eplay-player-open');
      elements.videoModal.style.display = 'flex';
      try {
        if (!window.history.state || window.history.state.page !== 'player') {
          window.history.pushState({ page: 'player', title }, '');
        }
      } catch (_) {}
      elements.modalFormat.textContent = 'Carregando vídeo...';

      if (mediaType === 'movie') {
        const movieGroup = mediaMeta?.groupOrMovie || (mediaMeta?.streamId && fullMoviesCache?.find(m => String(m.stream_id) === String(mediaMeta.streamId) || (m.versions && m.versions.some(v => String(v.streamId) === String(mediaMeta.streamId)))));
        setupPlayerVersionSwitcher(movieGroup || mediaMeta, mediaMeta?.selectedVersion || mediaMeta, mediaMeta?.allVersions || movieGroup?.versions);
      } else if (mediaType === 'series') {
        const seriesGrp = currentSeriesGroup || (mediaMeta?.seriesId && fullSeriesCache?.find(s => String(s.series_id) === String(mediaMeta.seriesId) || (s.versions && s.versions.some(v => String(v.seriesId) === String(mediaMeta.seriesId)))));
        setupPlayerSeriesVersionSwitcher(seriesGrp || mediaMeta, currentActiveSeriesVersion || mediaMeta?.selectedVersion, mediaMeta?.seasonNum || mediaMeta?.season, mediaMeta?.episodeNum);
      } else {
        const epAudioBtn = document.getElementById('eplayAudioBtn');
        if (epAudioBtn) {
          epAudioBtn.style.display = 'inline-flex';
          epAudioBtn.title = 'Áudio: Transmissão Original';
          epAudioBtn.setAttribute('aria-label', 'Áudio da Transmissão');
        }
        const epSec = document.getElementById('eplayVersionSection');
        if (epSec) epSec.style.display = 'block';
        const epBadge = document.getElementById('eplayVersionBadge');
        if (epBadge) epBadge.textContent = 'Ao Vivo';
        const epItems = document.getElementById('eplayVersionItems');
        if (epItems) {
          epItems.innerHTML = `
            <div class="eplay-version-item active" style="cursor: default; opacity: 0.9;">
              <span style="display:flex;align-items:center;gap:7px;">
                <span style="font-size:14px;">📡</span>
                <span>Áudio Original da Transmissão</span>
              </span>
              <span class="eplay-version-check" style="font-size: 10px; color: #00e676;">Ao Vivo</span>
            </div>
          `;
        }
      }

      startVideoLoadTimeout();

      // Reset de legendas e do painel
      currentRawSubtitleText = '';
      currentSubtitleLabel = '';
      currentSubtitleOffset = 0.0;
      currentParsedCues = [];
      currentSubtitleActive = false;
      lastActiveCueHtml = null;
      updateSubtitleOverlay();
      elements.subOffsetDisplay.textContent = '0.0s';
      elements.subSyncControls.style.display = 'none';
      closeSubOptionsPanel();
      elements.subCandidateSelect.innerHTML = '<option value="">Identificando...</option>';

      const isLeg = isLegendadoMedia(mediaMeta) || isLegendadoMedia({ name: title });
      elements.subSelect.innerHTML = isLeg
        ? '<option value="none" selected>Desativada (Legenda já no vídeo)</option>'
        : '<option value="none" selected>Desativada</option>';

      // Limpar faixas de vídeo anteriores
      const oldTracks = elements.videoPlayer.querySelectorAll('track');
      oldTracks.forEach(t => {
        if (t.src && t.src.startsWith('blob:')) {
          try { URL.revokeObjectURL(t.src); } catch (_) {}
        }
        t.remove();
      });
      if (elements.videoPlayer.textTracks) {
        for (let i = 0; i < elements.videoPlayer.textTracks.length; i++) {
          try {
            elements.videoPlayer.textTracks[i].mode = 'disabled';
          } catch (e) { }
        }
      }

      const applyResumePosition = () => {
        const target = currentPlaybackMeta?.startPosition || 0;
        if (target <= 0 || !elements.videoPlayer) return;
        const duration = elements.videoPlayer.duration;
        if (!Number.isFinite(duration) || duration <= 0 || target >= duration - 1) return;
        try {
          elements.videoPlayer.currentTime = Math.min(target, Math.max(0, duration - 1));
          if (elements.modalFormat) elements.modalFormat.textContent = `Retomando em ${formatResumeTime(target)}...`;
        } catch (e) {}
      };

      if (safeStartPosition > 0) {
        elements.videoPlayer.addEventListener('loadedmetadata', applyResumePosition, { once: true });
        if (elements.videoPlayer.readyState >= 1) setTimeout(applyResumePosition, 0);
      }

      if (mediaType === 'series') {
        skipIntroLookupKey = '';
        // Começa a consulta imediatamente com IMDb/MAL, mesmo que a duração
        // do arquivo ainda não esteja disponível. Depois o resultado é refinado
        // automaticamente quando metadata/durationchange chegarem.
        scheduleSkipIntroLookup(0);

        const triggerSkipIntroLookup = () => scheduleSkipIntroLookup(150);
        elements.videoPlayer.addEventListener('loadedmetadata', triggerSkipIntroLookup, { once: true });
        elements.videoPlayer.addEventListener('durationchange', triggerSkipIntroLookup);
        elements.videoPlayer.addEventListener('loadeddata', triggerSkipIntroLookup, { once: true });
        elements.videoPlayer.addEventListener('canplay', triggerSkipIntroLookup, { once: true });
        skipIntroEventCleanup = () => {
          elements.videoPlayer.removeEventListener('loadedmetadata', triggerSkipIntroLookup);
          elements.videoPlayer.removeEventListener('durationchange', triggerSkipIntroLookup);
          elements.videoPlayer.removeEventListener('loadeddata', triggerSkipIntroLookup);
          elements.videoPlayer.removeEventListener('canplay', triggerSkipIntroLookup);
        };
        if (elements.videoPlayer.readyState >= 1) triggerSkipIntroLookup();
      }

      elements.videoPlayer.play().catch(() => { });
      startVodProgressTracking();

      // Popular a barra lateral esquerda de informações (Ficha Técnica)
      populateMovieSidebar(mediaMeta);

      // Iniciar busca inteligente de legendas
      autoFetchSubtitle(title, mediaType, mediaMeta);
    }

    function closePlayer() {
      if (videoLoadTimeout) {
        clearTimeout(videoLoadTimeout);
        videoLoadTimeout = null;
      }
      hideVideoErrorOverlay();

      if (activeHls) {
        try { activeHls.destroy(); } catch (e) { }
        activeHls = null;
      }

      if (liveDriftInterval) {
        clearInterval(liveDriftInterval);
        liveDriftInterval = null;
      }
      if (elements.syncLiveBtn) elements.syncLiveBtn.style.display = 'none';
      if (elements.toggleLatencyModeBtn) elements.toggleLatencyModeBtn.style.display = 'none';
      if (elements.liveSyncToast) elements.liveSyncToast.style.display = 'none';

      stopVodProgressTracking();
      resetSkipIntroUi();

      if (elements.embedPlayer) {
        elements.embedPlayer.src = 'about:blank';
        elements.embedPlayer.style.display = 'none';
      }
      elements.videoPlayer.style.display = 'block';

      // Restaura botões para filmes e séries
      elements.toggleSubPanelBtn.style.display = 'inline-flex';
      elements.downloadBtn.style.display = 'inline-flex';

      const lastMeta = currentPlaybackMeta;
      window.EPlaySeriesNavigation = null;
      window.dispatchEvent(new CustomEvent('eplay:series-context'));
      currentPlaybackMeta = null;

      stopHybridAudio();

      elements.videoModal.style.display = 'none';
      elements.videoModal.classList.remove('eplay-player-page');
      document.body.classList.remove('eplay-player-open');
      elements.videoPlayer.pause();
      elements.videoPlayer.src = '';
      activeVideoUrl = '';

      if (!isHandlingPopstate && window.history.state && window.history.state.page === 'player') {
        try { window.history.back(); } catch (_) {}
      }

      if (elements.movieVersionSwitcher) {
        elements.movieVersionSwitcher.style.display = 'none';
        elements.movieVersionSwitcher.innerHTML = '';
      }
      const epSecClose = document.getElementById('eplayVersionSection');
      if (epSecClose) epSecClose.style.display = 'none';
      const epItemsClose = document.getElementById('eplayVersionItems');
      if (epItemsClose) epItemsClose.innerHTML = '';
      const epAudioSyncClose = document.getElementById('eplayAudioSyncMenu');
      if (epAudioSyncClose) epAudioSyncClose.style.display = 'none';
      const epAudioBtnClose = document.getElementById('eplayAudioBtn');
      if (epAudioBtnClose) epAudioBtnClose.style.display = 'none';
      const panelSecClose = elements.panelVersionSection || document.getElementById('panelVersionSection');
      if (panelSecClose) panelSecClose.style.display = 'none';
      const panelSwitcherClose = elements.panelVersionSwitcher || document.getElementById('panelVersionSwitcher');
      if (panelSwitcherClose) panelSwitcherClose.innerHTML = '';

      if (contentPageOpen) {
        if (currentContentPageType === 'movie' && currentContentPageItem) {
          const mId = currentContentPageItem.stream_id || currentContentPageItem.primaryItem?.stream_id || (currentContentPageItem.versions && currentContentPageItem.versions[0]?.streamId);
          if (mId) setRouteHash('#/filme/' + mId);
          document.title = (currentContentPageItem.name || currentContentPageItem.title || 'Filme') + ' - EPlay';
        } else if (currentContentPageType === 'series' && currentSeriesGroup) {
          const sId = currentSeriesGroup.series_id || (currentSeriesGroup.versions && currentSeriesGroup.versions[0]?.seriesId);
          if (sId) setRouteHash('#/series/' + sId);
          document.title = (currentSeriesGroup.name || currentSeriesGroup.title || 'Série') + ' - EPlay';
        }
      } else {
        if (isWatchedView) setRouteHash('#/assistidos');
        else if (isFavoritesView) setRouteHash('#/favoritos');
        else if (currentMode === 'movies') setRouteHash('#/filmes');
        else if (currentMode === 'series') setRouteHash('#/series');
        else if (currentMode === 'live') setRouteHash('#/tv');
        else setRouteHash('#/inicio');
      }

      // Limpar dados da barra lateral de informações
      if (elements.sidebarPoster) elements.sidebarPoster.src = '';
      if (elements.sidebarTitle) elements.sidebarTitle.textContent = '';
      if (elements.sidebarGenres) elements.sidebarGenres.innerHTML = '';
      if (elements.sidebarPlot) elements.sidebarPlot.textContent = 'Carregando sinopse...';
      if (elements.sidebarDirector) elements.sidebarDirector.textContent = '-';
      if (elements.sidebarCast) elements.sidebarCast.textContent = '-';

      // Se estávamos assistindo uma série, atualiza a lista de episódios e retorna à página dedicada.
      if (lastMeta?.mediaType === 'series' && lastMeta.seasonNum && currentSeriesData) {
        renderSeasonEpisodes(lastMeta.seasonNum);
      }

      if (lastMeta?.mediaMeta?.fromContentPage && contentPageReturnState === null) {
        contentPageOpen = true;
        currentContentPageType = lastMeta.mediaType;
      }

      // Limpar legendas anteriores e fechar painel
      closeSubOptionsPanel();
      currentRawSubtitleText = '';
      currentSubtitleLabel = '';
      currentSubtitleOffset = 0.0;
      currentParsedCues = [];
      currentSubtitleActive = false;
      lastActiveCueHtml = null;
      updateSubtitleOverlay();
      const oldTracks = elements.videoPlayer.querySelectorAll('track');
      oldTracks.forEach(t => {
        if (t.src && t.src.startsWith('blob:')) {
          try { URL.revokeObjectURL(t.src); } catch (_) {}
        }
        t.remove();
      });
      elements.modalFormat.textContent = 'Formato: MP4 • Link Direto';
      elements.subSyncControls.style.display = 'none';
      elements.subCandidateSelect.innerHTML = '<option value="">Identificando...</option>';
      elements.subSelect.innerHTML = '<option value="none">Desativada</option>';
      if (elements.subFileInput) elements.subFileInput.value = '';

      // Sair de tela cheia e restaurar orientação se estiver ativa
      if (document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement) {
        try {
          if (document.exitFullscreen) document.exitFullscreen();
          else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
        } catch (_) {}
      }
      if (elements.videoModal) {
        elements.videoModal.classList.remove('eplay-fullscreen');
      }
      const playerContainer = elements.videoPlayer?.closest('.video-container');
      if (playerContainer) {
        playerContainer.classList.remove('eplay-fullscreen');
      }
      document.body.classList.remove('eplay-fullscreen-active');
      try {
        if (screen.orientation && typeof screen.orientation.unlock === 'function') {
          screen.orientation.unlock();
        }
      } catch (_) {}
    }

    // Eventos do player de vídeo para tratamento de disponibilidade
    elements.videoPlayer.addEventListener('error', () => {
      // Ignora erro se for transmissão ao vivo, se videoPlayer estiver oculto ou sem src válido
      if (currentPlaybackMeta?.mediaType === 'live') return;
      if (!elements.videoPlayer.src || elements.videoPlayer.src === 'about:blank' || elements.videoPlayer.style.display === 'none') return;

      if (videoLoadTimeout) {
        clearTimeout(videoLoadTimeout);
        videoLoadTimeout = null;
      }
      // Fallback automático inteligente para filmes quando versão 4K/HEVC falha no player (incompatibilidade de codec ou servidor)
      const currentMeta = currentPlaybackMeta;
      const mediaMeta = currentMeta?.mediaMeta;
      const allVers = mediaMeta?.allVersions || [];
      const currentV = mediaMeta?.selectedVersion;
      if (currentV && (currentV.versionInfo?.type?.includes('4k') || currentV.isHybrid)) {
        const isLeg = isLegendadoMedia(currentV);
        const fhdFallback = allVers.find(v => v.versionInfo?.type === (isLeg ? 'legendado' : 'dublado'))
                         || allVers.find(v => v.versionInfo?.type === 'dublado')
                         || allVers.find(v => v.versionInfo?.type === 'legendado');
        if (fhdFallback && fhdFallback.streamId !== currentV.streamId) {
          console.warn('[EPlay Player] Versão 4K/HEVC incompatível com este dispositivo. Fazendo fallback automático para Full HD (H.264):', fhdFallback);
          if (window.showPlayerToast) {
            window.showPlayerToast('Formato 4K incompatível. Alternando para Full HD...');
          }
          switchLiveMovieVersion(mediaMeta.groupOrMovie || currentMeta, fhdFallback, allVers);
          return;
        }
      }

      // Primeira falha transitória: refaz a requisição uma vez antes de expor
      // o erro. Isso elimina a necessidade de clicar manualmente em "Tentar Novamente".
      if (retryCurrentVideoSource(true)) return;

      const errorMessage = mediaError?.code === 2
        ? 'A conexão com o servidor foi interrompida. Tente novamente ou use outra fonte.'
        : mediaError?.code === 3
          ? 'O navegador não conseguiu decodificar este vídeo. Tente outra versão ou fonte.'
          : mediaError?.code === 4
            ? 'Este formato de vídeo não é compatível com o navegador.'
            : 'Não foi possível iniciar a reprodução desta fonte. Tente novamente ou escolha outra fonte.';
      showVideoErrorOverlay('error', errorMessage);
    });

    elements.videoPlayer.addEventListener('loadeddata', () => {
      if (videoLoadTimeout) {
        clearTimeout(videoLoadTimeout);
        videoLoadTimeout = null;
      }
      hideVideoErrorOverlay();
      const sid = currentPlaybackMeta?.streamId;
      if (sid) clearStreamBroken(sid);
      elements.modalFormat.textContent = 'Formato: MP4 • Link Direto';
    });

    elements.videoPlayer.addEventListener('play', () => {
      if (currentHybridState.active && currentHybridState.audioEl && currentHybridState.audioEl.paused) {
        currentHybridState.audioEl.play().catch(() => {});
      }
    });

    elements.videoPlayer.addEventListener('pause', () => {
      if (currentHybridState.active && currentHybridState.audioEl && !currentHybridState.audioEl.paused) {
        currentHybridState.audioEl.pause();
      }
    });

    elements.videoPlayer.addEventListener('waiting', () => {
      if (currentHybridState.active && currentHybridState.audioEl) {
        currentHybridState.audioEl.pause();
      }
    });

    elements.videoPlayer.addEventListener('ratechange', () => {
      if (currentHybridState.active && currentHybridState.audioEl) {
        currentHybridState.audioEl.playbackRate = elements.videoPlayer.playbackRate;
      }
    });

    elements.videoPlayer.addEventListener('playing', () => {
      if (videoLoadTimeout) {
        clearTimeout(videoLoadTimeout);
        videoLoadTimeout = null;
      }
      hideVideoErrorOverlay();
      const sid = currentPlaybackMeta?.streamId;
      if (sid) clearStreamBroken(sid);
      if (currentPlaybackMeta?.mediaType === 'series') {
        maybeAutoSkipIntro();
        updateSkipIntroButton();
        if (!skipIntroState.segment) scheduleSkipIntroLookup(0);
      }
      if (currentHybridState.active && currentHybridState.audioEl) {
        syncHybridAudioTime(true);
        currentHybridState.audioEl.play().catch(() => {});
      }
    });

    elements.videoPlayer.addEventListener('timeupdate', () => {
      maybeAutoSkipIntro();
      updateSkipIntroButton();
      updateSubtitleOverlay();
      if (currentHybridState.active) {
        syncHybridAudioTime(false);
      }
    });
    elements.videoPlayer.addEventListener('seeking', () => {
      updateSkipIntroButton();
      updateSubtitleOverlay();
      if (currentHybridState.active) {
        syncHybridAudioTime(true);
      }
    });
    elements.videoPlayer.addEventListener('seeked', () => {
      if (skipIntroState.segment && elements.videoPlayer.currentTime < skipIntroState.segment.start) {
        skipIntroState.used = false;
      }
      maybeAutoSkipIntro();
      updateSkipIntroButton();
      updateSubtitleOverlay();
      if (currentHybridState.active) {
        syncHybridAudioTime(true);
      }
    });

    elements.videoPlayer.addEventListener('ended', () => {
      const meta = getCurrentVodProgressMeta();
      if (meta?.type === 'series' && currentPlaybackMeta?.mediaMeta?.ep) {
        markSeriesEpisodeWatched(currentPlaybackMeta.mediaMeta.ep, meta.seasonNum, elements.videoPlayer.duration);
      }
      if (meta) clearVodProgress(meta.type, meta.id);
      stopVodProgressTracking(false);
      if (currentHybridState.active && currentHybridState.audioEl) {
        currentHybridState.audioEl.pause();
      }
    });

    window.addEventListener('pagehide', () => {
      saveCurrentVodProgress();
      if (typeof saveCurrentTvVodProgress === 'function') saveCurrentTvVodProgress();
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') {
        saveCurrentVodProgress();
        if (typeof saveCurrentTvVodProgress === 'function') saveCurrentTvVodProgress();
      }
    });

    // ==========================================
    // PAINEL LATERAL DE INFORMAÇÕES (FICHA TÉCNICA)
    // ==========================================
    let movieSidebarOpen = true;

    function formatDurationString(val) {
      if (!val) return '';
      const str = String(val).trim();
      if (!str || str === '0' || str === '00:00:00') return '';

      // Formato hh:mm:ss ou mm:ss
      if (str.includes(':')) {
        const parts = str.split(':').map(Number);
        if (parts.length === 3) {
          const h = parts[0];
          const m = parts[1];
          if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
          return `${m}m`;
        } else if (parts.length === 2) {
          const m = parts[0];
          if (m >= 60) {
            const h = Math.floor(m / 60);
            const remM = m % 60;
            return `${h}h ${String(remM).padStart(2, '0')}m`;
          }
          return `${m}m`;
        }
      }

      const num = parseInt(str, 10);
      if (isNaN(num) || num <= 0) return '';

      // Se valor for maior que 360, geralmente está em segundos
      if (num > 360) {
        const h = Math.floor(num / 3600);
        const m = Math.floor((num % 3600) / 60);
        if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
        return `${m}m`;
      }

      // Minutos
      if (num >= 60) {
        const h = Math.floor(num / 60);
        const m = num % 60;
        return `${h}h ${String(m).padStart(2, '0')}m`;
      }
      return `${num}m`;
    }

    function toggleMovieInfoSidebar(forceState = null) {
      if (!elements.movieInfoSidebar) return;
      if (forceState !== null) {
        movieSidebarOpen = !!forceState;
      } else {
        movieSidebarOpen = !movieSidebarOpen;
      }

      if (movieSidebarOpen) {
        elements.movieInfoSidebar.classList.remove('collapsed');
        if (elements.toggleMovieInfoBtn) {
          elements.toggleMovieInfoBtn.innerHTML = 'ℹ️ Ocultar Ficha';
          elements.toggleMovieInfoBtn.style.borderColor = 'var(--primary)';
        }
      } else {
        elements.movieInfoSidebar.classList.add('collapsed');
        if (elements.toggleMovieInfoBtn) {
          elements.toggleMovieInfoBtn.innerHTML = 'ℹ️ Ficha Técnica';
          elements.toggleMovieInfoBtn.style.borderColor = '';
        }
      }

      try {
        localStorage.setItem('andplay_sidebar_open_v2', movieSidebarOpen ? '1' : '0');
      } catch (e) { }
      saveLocalPreference('sidebar_open', !!movieSidebarOpen);
    }

    function initSidebarState() {
      try {
        const saved = localStorage.getItem('andplay_sidebar_open_v2');
        if (saved === '1') {
          movieSidebarOpen = true;
        } else {
          movieSidebarOpen = false;
        }
      } catch (e) {
        movieSidebarOpen = false;
      }
      toggleMovieInfoSidebar(movieSidebarOpen);
    }

    // Dicionário de tradução de gêneros do IMDb (inglês) para exibição amigável em português
    const IMDB_GENRE_TRANSLATIONS = {
      'action': 'Ação',
      'adventure': 'Aventura',
      'animation': 'Animação',
      'biography': 'Biografia',
      'comedy': 'Comédia',
      'crime': 'Crime',
      'documentary': 'Documentário',
      'drama': 'Drama',
      'family': 'Família',
      'fantasy': 'Fantasia',
      'film-noir': 'Noir',
      'history': 'História',
      'horror': 'Terror',
      'music': 'Música',
      'musical': 'Musical',
      'mystery': 'Mistério',
      'romance': 'Romance',
      'sci-fi': 'Ficção Científica',
      'science fiction': 'Ficção Científica',
      'sport': 'Esporte',
      'thriller': 'Suspense',
      'war': 'Guerra',
      'western': 'Faroeste'
    };

    function translateGenreName(name) {
      if (!name) return '';
      const key = String(name).trim().toLowerCase();
      return IMDB_GENRE_TRANSLATIONS[key] || name.trim();
    }

    function populateMovieSidebar(mediaMeta) {
      if (!elements.movieInfoSidebar) return;

      const title = mediaMeta?.title || mediaMeta?.rawTitle || mediaMeta?.name || (elements.modalTitle ? elements.modalTitle.textContent : 'Vídeo');
      const poster = mediaMeta?.poster || mediaMeta?.stream_icon || (mediaMeta?.primaryItem && (mediaMeta.primaryItem.stream_icon || mediaMeta.primaryItem.poster)) || '';
      const year = mediaMeta?.year ? String(mediaMeta.year).substring(0, 4) : '';
      const rating = mediaMeta?.rating || mediaMeta?.rating_5based;
      const duration = mediaMeta?.duration || mediaMeta?.episode_run_time;
      const plot = mediaMeta?.plot;
      const director = mediaMeta?.director;
      const cast = mediaMeta?.cast || mediaMeta?.actors;
      const genre = mediaMeta?.genre;

      // Título
      if (elements.sidebarTitle) {
        elements.sidebarTitle.textContent = title;
      }

      // Capa / Pôster
      if (elements.sidebarPoster) {
        if (poster) {
          elements.sidebarPoster.src = poster;
          elements.sidebarPoster.style.display = 'block';
        } else {
          elements.sidebarPoster.style.display = 'none';
        }
      }

      // Ano de Lançamento
      if (elements.sidebarYear) {
        elements.sidebarYear.textContent = year ? year : 'Ano N/D';
      }

      // Duração formatada
      if (elements.sidebarDuration) {
        const durStr = formatDurationString(duration);
        if (durStr) {
          elements.sidebarDuration.textContent = '⏱️ ' + durStr;
          elements.sidebarDuration.style.display = 'inline';
          if (elements.sidebarMetaDot) elements.sidebarMetaDot.style.display = 'inline';
        } else {
          elements.sidebarDuration.style.display = 'none';
          if (elements.sidebarMetaDot) elements.sidebarMetaDot.style.display = 'none';
        }
      }

      // Nota / Avaliação
      if (elements.sidebarRating) {
        if (rating && Number(rating) > 0) {
          elements.sidebarRating.innerHTML = `★ ${Number(rating).toFixed(1)}`;
          elements.sidebarRating.style.display = 'inline-flex';
        } else {
          elements.sidebarRating.style.display = 'none';
        }
      }

      // Gêneros em badges/pills
      if (elements.sidebarGenres) {
        elements.sidebarGenres.innerHTML = '';
        if (genre) {
          const gList = String(genre).split(/[,/|]/).map(g => g.trim()).filter(Boolean);
          if (gList.length > 0) {
            elements.sidebarGenres.innerHTML = gList.slice(0, 5).map(g => `<span class="genre-pill">${escapeHtml(translateGenreName(g))}</span>`).join('');
            elements.sidebarGenres.style.display = 'flex';
          } else {
            elements.sidebarGenres.style.display = 'none';
          }
        } else {
          elements.sidebarGenres.style.display = 'none';
        }
      }

      // Sinopse
      if (elements.sidebarPlot) {
        if (plot && String(plot).trim() && String(plot).trim().length > 15) {
          elements.sidebarPlot.textContent = String(plot).trim();
        } else {
          elements.sidebarPlot.innerHTML = '<span style="color:#888; font-style: italic;">Buscando ficha técnica no IMDb...</span>';
        }
      }

      // Direção
      if (elements.sidebarDirector && elements.sidebarDirectorContainer) {
        if (director && String(director).trim()) {
          elements.sidebarDirector.textContent = String(director).trim();
          elements.sidebarDirectorContainer.style.display = 'flex';
        } else {
          elements.sidebarDirector.textContent = '-';
          elements.sidebarDirectorContainer.style.display = 'flex';
        }
      }

      // Elenco
      if (elements.sidebarCast && elements.sidebarCastContainer) {
        if (cast && String(cast).trim()) {
          elements.sidebarCast.textContent = String(cast).trim();
          elements.sidebarCastContainer.style.display = 'flex';
        } else {
          elements.sidebarCast.textContent = '-';
          elements.sidebarCastContainer.style.display = 'flex';
        }
      }

      // Dispara busca e enriquecimento no IMDb em segundo plano (sem travar nem pausar o vídeo)
      fetchImdbSidebarMetadataAsync(title, mediaMeta?.mediaType || 'movie', mediaMeta);

      const streamId = mediaMeta?.streamId || mediaMeta?.stream_id;
      if (mediaMeta?.mediaType !== 'series' && streamId) {
        enrichMovieMetadataAsync(streamId, title);
      }
    }

    async function fetchImdbSidebarMetadataAsync(title, mediaType, mediaMeta) {
      const currentStreamId = mediaMeta?.streamId || mediaMeta?.stream_id;
      const catalogType = (mediaType === 'series') ? 'series' : 'movie';

      try {
        let imdbId = null;

        // 1. Tentar encontrar ID IMDb já mapeado ou salvo
        if (mediaType !== 'series' && currentStreamId && KNOWN_STREAM_CORRECTIONS[currentStreamId]?.imdbId) {
          imdbId = KNOWN_STREAM_CORRECTIONS[currentStreamId].imdbId;
        } else if (mediaMeta?.imdbId || mediaMeta?.imdb_id) {
          imdbId = mediaMeta.imdbId || mediaMeta.imdb_id;
        } else {
          const targetKeySource = (mediaType === 'series') ? mediaMeta : (mediaMeta?.rawTitle || title);
          const saved = getSavedMediaMatch(mediaType, targetKeySource);
          if (saved && saved.id && saved.id.startsWith('tt')) {
            imdbId = saved.id;
          }
        }

        // 2. Se não tiver ID IMDb direto, busca pelo catálogo Cinemeta
        if (!imdbId) {
          let searchStr = mediaMeta?.rawTitle || mediaMeta?.seriesName || title || '';
          searchStr = parseTitleInfo(searchStr);
          if (!searchStr) searchStr = cleanTitleKey(title);

          if (searchStr) {
            const searchUrl = `https://v3-cinemeta.strem.io/catalog/${catalogType}/top/search=${encodeURIComponent(searchStr)}.json`;
            const sRes = await fetch(searchUrl);
            if (sRes.ok) {
              const sData = await sRes.json();
              const metas = sData?.metas || [];
              if (metas.length > 0) {
                const expectedYear = mediaMeta?.year || '';
                let best = metas[0];
                if (expectedYear) {
                  const withYear = metas.find(m => (m.releaseInfo && m.releaseInfo.includes(expectedYear)) || (m.year && String(m.year).includes(expectedYear)));
                  if (withYear) best = withYear;
                }
                imdbId = best.id;
              }
            }
          }
        }

        if (!imdbId) {
          if (elements.sidebarPlot && elements.sidebarPlot.textContent.includes('Buscando')) {
            elements.sidebarPlot.textContent = 'Sinopse não disponível para este título.';
          }
          return;
        }

        // 3. Buscar os detalhes completos do IMDb no Cinemeta
        const metaUrl = `https://v3-cinemeta.strem.io/meta/${catalogType}/${imdbId}.json`;
        const mRes = await fetch(metaUrl);
        if (!mRes.ok) return;
        const mData = await mRes.json();
        const meta = mData?.meta;
        if (!meta) return;

        // 4. Se o usuário já fechou o vídeo ou trocou de mídia enquanto baixava, descarta
        if (currentPlaybackMeta?.streamId && currentStreamId && String(currentPlaybackMeta.streamId) !== String(currentStreamId)) {
          return;
        }

        // 5. Aplicar os dados na ficha técnica lateral esquerda

        // Sinopse: se estiver vazia ou com texto de carregando/não disponível, atualiza
        if (meta.description && elements.sidebarPlot) {
          const curPlot = elements.sidebarPlot.textContent || '';
          if (!curPlot || curPlot.includes('processamento') || curPlot.includes('não disponível') || curPlot.includes('Buscando') || curPlot.includes('Carregando') || curPlot.length < 20) {
            elements.sidebarPlot.textContent = meta.description;
          }
        }

        // Direção
        if (elements.sidebarDirector && elements.sidebarDirectorContainer) {
          let dirStr = '';
          if (Array.isArray(meta.director)) {
            dirStr = meta.director.filter(Boolean).join(', ');
          } else if (typeof meta.director === 'string') {
            dirStr = meta.director;
          }
          if (dirStr && (elements.sidebarDirectorContainer.style.display === 'none' || elements.sidebarDirector.textContent === '-' || elements.sidebarDirector.textContent.includes('Carregando'))) {
            elements.sidebarDirector.textContent = dirStr;
            elements.sidebarDirectorContainer.style.display = 'flex';
          }
        }

        // Elenco Principal
        if (elements.sidebarCast && elements.sidebarCastContainer) {
          let castStr = '';
          if (Array.isArray(meta.cast)) {
            castStr = meta.cast.filter(Boolean).slice(0, 10).join(', ');
          } else if (typeof meta.cast === 'string') {
            castStr = meta.cast;
          }
          if (castStr && (elements.sidebarCastContainer.style.display === 'none' || elements.sidebarCast.textContent === '-' || elements.sidebarCast.textContent.includes('Carregando'))) {
            elements.sidebarCast.textContent = castStr;
            elements.sidebarCastContainer.style.display = 'flex';
          }
        }

        // Duração
        if (elements.sidebarDuration) {
          const curDurHidden = elements.sidebarDuration.style.display === 'none';
          if (curDurHidden && meta.runtime) {
            const dStr = formatDurationString(meta.runtime);
            if (dStr) {
              elements.sidebarDuration.textContent = '⏱️ ' + dStr;
              elements.sidebarDuration.style.display = 'inline';
              if (elements.sidebarMetaDot) elements.sidebarMetaDot.style.display = 'inline';
            }
          }
        }

        // Nota IMDb
        if (elements.sidebarRating) {
          const curRatingHidden = elements.sidebarRating.style.display === 'none';
          if (curRatingHidden && meta.imdbRating && Number(meta.imdbRating) > 0) {
            elements.sidebarRating.innerHTML = `★ ${Number(meta.imdbRating).toFixed(1)}`;
            elements.sidebarRating.style.display = 'inline-flex';
          }
        }

        // Gêneros
        if (elements.sidebarGenres) {
          const curGenresHidden = elements.sidebarGenres.style.display === 'none' || elements.sidebarGenres.children.length === 0;
          if (curGenresHidden) {
            const genreList = Array.isArray(meta.genres) ? meta.genres : (Array.isArray(meta.genre) ? meta.genre : (meta.genre ? [meta.genre] : []));
            if (genreList.length > 0) {
              elements.sidebarGenres.innerHTML = genreList.slice(0, 5).map(g => `<span class="genre-pill">${escapeHtml(translateGenreName(g))}</span>`).join('');
              elements.sidebarGenres.style.display = 'flex';
            }
          }
        }

        // Pôster
        if (elements.sidebarPoster) {
          if ((!elements.sidebarPoster.src || elements.sidebarPoster.style.display === 'none') && meta.poster) {
            elements.sidebarPoster.src = meta.poster;
            elements.sidebarPoster.style.display = 'block';
          }
        }

        // Ano se não informado
        if (elements.sidebarYear && (elements.sidebarYear.textContent === 'Ano N/D' || !elements.sidebarYear.textContent)) {
          const y = meta.year || meta.releaseInfo;
          if (y) elements.sidebarYear.textContent = String(y).substring(0, 4);
        }

      } catch (err) {
        console.warn('Erro ao carregar metadados do IMDb em segundo plano:', err);
      }
    }

    async function enrichMovieMetadataAsync(streamId, expectedTitle) {
      if (!streamId) return;
      try {
        const data = await xtreamApi('get_vod_info', `&vod_id=${streamId}`);
        const info = data?.info;
        if (!info) return;

        if (currentPlaybackMeta?.streamId && String(currentPlaybackMeta.streamId) !== String(streamId)) return;

        if (info.plot && elements.sidebarPlot && (!elements.sidebarPlot.textContent || elements.sidebarPlot.textContent.includes('não disponível') || elements.sidebarPlot.textContent.includes('processamento') || elements.sidebarPlot.textContent.includes('Buscando'))) {
          elements.sidebarPlot.textContent = info.plot;
        }
        if (info.director && elements.sidebarDirector && elements.sidebarDirectorContainer && (elements.sidebarDirectorContainer.style.display === 'none' || elements.sidebarDirector.textContent === '-')) {
          elements.sidebarDirector.textContent = info.director;
          elements.sidebarDirectorContainer.style.display = 'flex';
        }
        const castStr = info.actors || info.cast;
        if (castStr && elements.sidebarCast && elements.sidebarCastContainer && (elements.sidebarCastContainer.style.display === 'none' || elements.sidebarCast.textContent === '-')) {
          elements.sidebarCast.textContent = castStr;
          elements.sidebarCastContainer.style.display = 'flex';
        }
        if (info.duration && elements.sidebarDuration && elements.sidebarDuration.style.display === 'none') {
          const dStr = formatDurationString(info.duration);
          if (dStr) {
            elements.sidebarDuration.textContent = '⏱️ ' + dStr;
            elements.sidebarDuration.style.display = 'inline';
            if (elements.sidebarMetaDot) elements.sidebarMetaDot.style.display = 'inline';
          }
        }
        if (info.genre && elements.sidebarGenres && (elements.sidebarGenres.style.display === 'none' || elements.sidebarGenres.children.length === 0)) {
          const genres = String(info.genre).split(/[,/|]/).map(g => g.trim()).filter(Boolean);
          if (genres.length > 0) {
            elements.sidebarGenres.innerHTML = genres.slice(0, 5).map(g => `<span class="genre-pill">${escapeHtml(translateGenreName(g))}</span>`).join('');
            elements.sidebarGenres.style.display = 'flex';
          }
        }
        if (elements.sidebarPoster && (!elements.sidebarPoster.src || elements.sidebarPoster.style.display === 'none') && (info.cover_big || info.movie_image)) {
          elements.sidebarPoster.src = info.cover_big || info.movie_image;
          elements.sidebarPoster.style.display = 'block';
        }
      } catch (err) {
        // Ignora erros de rede na consulta secundária
      }
    }

    function toggleSubOptionsPanel() {
      const isCurrentlyOpen = elements.subOptionsPanel.style.display === 'block';
      if (isCurrentlyOpen) {
        closeSubOptionsPanel();
      } else {
        elements.subOptionsPanel.style.display = 'block';
        elements.toggleSubPanelBtn.innerHTML = '💬 Ocultar Legendas';
        elements.toggleSubPanelBtn.style.borderColor = 'var(--primary)';
      }
    }

    function closeSubOptionsPanel() {
      if (elements.subOptionsPanel) elements.subOptionsPanel.style.display = 'none';
      if (elements.toggleSubPanelBtn) {
        elements.toggleSubPanelBtn.innerHTML = '💬 Opções de Legenda';
        elements.toggleSubPanelBtn.style.borderColor = '';
      }
    }

    // Extrai o título limpo para busca no catálogo
    function parseTitleInfo(rawTitle) {
      if (!rawTitle) return '';

      return rawTitle
        .replace(/^[0-9]+\s*[-–—]\s*/, '')      // '1 - ', '02 - '
        .replace(/\b(4k|uhd|hdr|dv|hdcam|cinema|dublado|dub|legendado|leg|lancamento|lançamento)\b/gi, '')
        .replace(/\[.*?\]/g, '')                // '[HDR]', '[DV]', '[L]'
        .replace(/\(.*?\)/g, '')                // '(2017)', etc.
        .toLowerCase()
        .normalize('NFD').replace(/[\u0300-\u036f]/g, '') // remove acentos
        .replace(/[^a-z0-9\s]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    }

    // Pontua relevância do título sem descartar por ano (cadastros IPTV frequentemente têm ano errado)
    function scoreCandidate(meta, cleanTitle) {
      let score = 100;
      if (!meta || !meta.name) return 0;

      const metaClean = meta.name
        .toLowerCase()
        .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
        .replace(/[^a-z0-9\s]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();

      // Equivalências e traduções comuns diretas
      if (metaClean === cleanTitle) {
        score += 150;
      } else if ((cleanTitle === 'questao de tempo' || cleanTitle === 'about time') && (metaClean === 'about time' || metaClean === 'questao de tempo')) {
        score += 180;
      } else if (cleanTitle === 'vamos time' && (metaClean === 'gracias equipo' || metaClean === 'go team' || metaClean === 'vamos time')) {
        score += 150;
      } else if (cleanTitle === 'a fera' && metaClean === 'beast') {
        score += 150;
      } else if (cleanTitle === 'carros' && metaClean === 'cars') {
        score += 150;
      } else if (cleanTitle === 'panico' && metaClean === 'scream') {
        score += 150;
      } else if (metaClean.startsWith(cleanTitle) || cleanTitle.startsWith(metaClean)) {
        score += 60;
      } else if (metaClean.includes(cleanTitle) || cleanTitle.includes(metaClean)) {
        score += 40;
      }

      // Priorizar títulos mais concisos (evita que títulos longos fiquem na frente do filme principal)
      const metaWords = metaClean.split(' ').filter(Boolean).length;
      const queryWords = cleanTitle.split(' ').filter(Boolean).length;
      const wordDiff = Math.abs(metaWords - queryWords);
      score -= (wordDiff * 8);

      return score;
    }

    // ==========================================
    // MEMÓRIA E PERSISTÊNCIA DE VÍNCULOS IMDB (LOCALSTORAGE)
    // ==========================================
    function getMediaStorageKey(mediaType, titleOrMeta) {
      if (mediaType === 'series') {
        const sName = (typeof titleOrMeta === 'object' && titleOrMeta && titleOrMeta.seriesName)
          ? titleOrMeta.seriesName
          : String(titleOrMeta || '');
        return `andplay_match_series_${cleanTitleKey(sName)}`;
      } else {
        const mName = (typeof titleOrMeta === 'object' && titleOrMeta && titleOrMeta.rawTitle)
          ? titleOrMeta.rawTitle
          : String(titleOrMeta || '');
        return `andplay_match_movie_${cleanTitleKey(mName)}`;
      }
    }

    function getSavedMediaMatch(mediaType, titleOrMeta) {
      try {
        const key = getMediaStorageKey(mediaType, titleOrMeta);
        const raw = localStorage.getItem(key);
        if (!raw) return null;
        return JSON.parse(raw);
      } catch (e) {
        return null;
      }
    }

    function saveMediaMatch(mediaType, titleOrMeta, matchData) {
      try {
        const key = getMediaStorageKey(mediaType, titleOrMeta);
        localStorage.setItem(key, JSON.stringify(matchData));
      } catch (e) {
        console.error('Erro ao salvar vínculo no localStorage:', e);
      }
    }

    function removeMediaMatch(mediaType, titleOrMeta) {
      try {
        const key = getMediaStorageKey(mediaType, titleOrMeta);
        localStorage.removeItem(key);
      } catch (e) {
        console.error('Erro ao remover vínculo do localStorage:', e);
      }
    }

    // Busca automática no OpenSubtitles via Stremio Cinemeta
    async function autoFetchSubtitle(rawTitle, mediaType, mediaMeta) {
      try {
        const catalogType = (mediaType === 'series') ? 'series' : 'movie';
        let baseSearch = rawTitle;
        if (mediaType === 'series' && mediaMeta && mediaMeta.seriesName) {
          baseSearch = mediaMeta.seriesName;
        }

        const clean = parseTitleInfo(baseSearch);
        currentSubContext = {
          rawTitle,
          mediaType,
          mediaMeta,
          cleanTitle: clean,
          catalogType,
          candidates: [],
          allSubtitles: []
        };

        // 1. VERIFICAR SE JÁ EXISTE CORREÇÃO CONHECIDA OU VÍNCULO MEMORIZADO NO LOCALSTORAGE
        const targetKeySource = (mediaType === 'series') ? mediaMeta : rawTitle;
        let savedMatch = null;
        let tagLabel = '[Verificado]';

        if (mediaType !== 'series') {
          const sid = mediaMeta?.stream_id ||
            mediaMeta?.selectedVersion?.streamId ||
            mediaMeta?.primaryItem?.stream_id ||
            mediaMeta?.groupOrMovie?.stream_id;
          if (sid && KNOWN_STREAM_CORRECTIONS[sid]?.imdbId) {
            const corr = KNOWN_STREAM_CORRECTIONS[sid];
            savedMatch = {
              id: corr.imdbId,
              name: corr.name.replace(/\s*\[.*?\]/g, '').replace(/\s*\(.*?\)/g, '').trim(),
              year: corr.year || '',
              poster: corr.poster || '',
              catalogType: 'movie'
            };
          }
        }

        if (!savedMatch) {
          savedMatch = getSavedMediaMatch(mediaType, targetKeySource);
          tagLabel = '[Memorizado]';
        }

        if (!savedMatch) {
          if (mediaType === 'series') {
            const cleanKey = cleanTitleKey(mediaMeta?.seriesName || rawTitle || '');
            const seriesImdb = normalizeImdbId(
              mediaMeta?.imdbId ||
              mediaMeta?.imdb_id ||
              (cleanKey ? readSeriesImdbCache()[cleanKey] : '')
            );
            if (seriesImdb) {
              savedMatch = {
                id: seriesImdb,
                name: (mediaMeta?.seriesName || clean || rawTitle).replace(/\s*\[.*?\]/g, '').trim(),
                year: mediaMeta?.year || '',
                poster: mediaMeta?.poster || '',
                catalogType: 'series'
              };
              tagLabel = '[IMDb]';
            }
          } else {
            const movieImdb = normalizeImdbId(
              mediaMeta?.imdbId ||
              mediaMeta?.imdb_id ||
              mediaMeta?.groupOrMovie?.imdbId ||
              mediaMeta?.groupOrMovie?.imdb_id ||
              mediaMeta?.primaryItem?.imdb_id
            );
            if (movieImdb) {
              savedMatch = {
                id: movieImdb,
                name: (mediaMeta?.title || clean || rawTitle).replace(/\s*\[.*?\]/g, '').trim(),
                year: mediaMeta?.year || '',
                poster: mediaMeta?.poster || '',
                catalogType: 'movie'
              };
              tagLabel = '[IMDb]';
            }
          }
        }

        const isLeg = isLegendadoMedia(mediaMeta) || isLegendadoMedia({ name: rawTitle });

        if (savedMatch && savedMatch.id) {
          if (isLeg) {
            elements.modalFormat.textContent = 'Formato: MP4 • Legenda impressa no frame (Legenda externa desativada)';
          } else {
            elements.modalFormat.textContent = `Carregando legendas de ${savedMatch.name} ${tagLabel}...`;
          }

          currentSubContext.candidates = [{
            id: savedMatch.id,
            name: savedMatch.name,
            year: savedMatch.year,
            poster: savedMatch.poster
          }];

          elements.subCandidateSelect.innerHTML = '';
          const opt = document.createElement('option');
          opt.value = savedMatch.id;
          const y = savedMatch.year ? ` (${savedMatch.year})` : '';
          opt.textContent = `🎯 ${savedMatch.name}${y} ${tagLabel}`;
          elements.subCandidateSelect.appendChild(opt);

          const subs = await fetchCandidateSubtitles(savedMatch.id, savedMatch.name, savedMatch.year, savedMatch.catalogType || catalogType);
          currentSubContext.allSubtitles = subs;

          // Legendas desativadas por padrão
          renderSubtitleOptions(savedMatch.id, false);

          if (isLeg) {
            disableActiveSubtitle('Formato: MP4 • Legenda impressa no frame');
          } else {
            elements.modalFormat.textContent = subs.length > 0
              ? `Formato: MP4 • Link Direto (${subs.length} legendas disponíveis)`
              : `Formato: MP4 • Link Direto`;
          }
          return;
        }

        if (!clean) {
          elements.modalFormat.textContent = 'Formato: MP4 • Link Direto';
          elements.subCandidateSelect.innerHTML = '<option value="none">Sem título identificado</option>';
          elements.subSelect.innerHTML = '<option value="none">Nenhuma Legenda Encontrada</option>';
          return;
        }

        elements.modalFormat.textContent = 'Buscando opções no catálogo de legendas...';
        await searchAndPopulateCandidates(clean, catalogType);
      } catch (err) {
        console.warn('Erro ao buscar legenda automática:', err);
        elements.modalFormat.textContent = 'Formato: MP4 • Link Direto';
        elements.subCandidateSelect.innerHTML = '<option value="none">Erro na busca</option>';
        elements.subSelect.innerHTML = '<option value="none">Erro na busca de legendas</option>';
      }
    }

    async function searchAndPopulateCandidates(searchTerm, catalogType) {
      elements.subCandidateSelect.innerHTML = '<option value="loading">Buscando títulos...</option>';
      elements.subSelect.innerHTML = '<option value="loading" disabled selected>💬 Buscando legendas...</option>';

      try {
        const searchUrl = `https://v3-cinemeta.strem.io/catalog/${catalogType}/top/search=${encodeURIComponent(searchTerm)}.json`;
        const cinemetaRes = await fetch(searchUrl);
        const cinemetaData = await cinemetaRes.json();

        let metas = (cinemetaData && cinemetaData.metas) ? cinemetaData.metas : [];

        if (metas.length === 0) {
          elements.subCandidateSelect.innerHTML = '<option value="none">Nenhum título encontrado</option>';
          elements.subSelect.innerHTML = '<option value="none">Sem legendas para este título</option>';
          elements.modalFormat.textContent = 'Nenhum título compatível encontrado';
          return;
        }

        // Pontuar candidatos por relevância do nome (sem descartar datas)
        const scored = metas.map(m => ({
          ...m,
          score: scoreCandidate(m, searchTerm)
        })).sort((a, b) => b.score - a.score);

        // Manter até 6 candidatos
        const topCandidates = scored.slice(0, 6);
        currentSubContext.candidates = topCandidates;

        // Atualizar seletor de títulos
        elements.subCandidateSelect.innerHTML = '';
        const allOpt = document.createElement('option');
        allOpt.value = 'all';
        allOpt.textContent = '🌟 Todos os Títulos Detectados';
        elements.subCandidateSelect.appendChild(allOpt);

        topCandidates.forEach((cand) => {
          const opt = document.createElement('option');
          opt.value = cand.id;
          const y = cand.releaseInfo || cand.year || '';
          opt.textContent = `${cand.name} ${y ? `(${y})` : ''}`;
          elements.subCandidateSelect.appendChild(opt);
        });

        // Buscar legendas dos até 3 melhores candidatos em paralelo
        const fetchList = topCandidates.slice(0, 3);
        elements.modalFormat.textContent = 'Buscando legendas em Português...';

        const fetchedResults = await Promise.allSettled(
          fetchList.map(cand => fetchCandidateSubtitles(cand.id, cand.name, cand.releaseInfo || cand.year || '', catalogType))
        );

        let combinedSubs = [];
        fetchedResults.forEach(res => {
          if (res.status === 'fulfilled' && Array.isArray(res.value)) {
            combinedSubs = combinedSubs.concat(res.value);
          }
        });

        currentSubContext.allSubtitles = combinedSubs;

        // Renderizar opções no dropdown
        renderSubtitleOptions('all', false);

        if (isLegendadoMedia(currentSubContext?.mediaMeta) || isLegendadoMedia({ name: currentSubContext?.rawTitle })) {
          disableActiveSubtitle('Formato: MP4 • Legenda impressa no frame');
        }

      } catch (err) {
        console.error('Erro ao buscar candidatos no Cinemeta:', err);
        elements.subCandidateSelect.innerHTML = '<option value="none">Erro ao buscar títulos</option>';
        elements.subSelect.innerHTML = '<option value="none">Erro na busca de legendas</option>';
      }
    }

    async function fetchCandidateSubtitles(imdbId, candidateName, candidateYear, catalogType) {
      try {
        let subQueryId = imdbId;
        if (currentSubContext.mediaType === 'series' && currentSubContext.mediaMeta) {
          const seasonNum = Number(currentSubContext.mediaMeta.seasonNum || currentSubContext.mediaMeta.season || 1);
          const epNum = Number(currentSubContext.mediaMeta.episodeNum || currentSubContext.mediaMeta.episode_num || currentSubContext.mediaMeta.ep?.episode_num || 1);
          subQueryId = `${imdbId}:${seasonNum}:${epNum}`;
        }

        const subApiUrl = `https://opensubtitles-v3.strem.io/subtitles/${catalogType}/${subQueryId}.json`;
        const res = await fetch(subApiUrl);
        const data = await res.json();

        if (!data || !data.subtitles) return [];

        const pt = data.subtitles.filter(s => s.lang === 'pob' || s.lang === 'por');
        const shortTitle = `${candidateName}${candidateYear ? ` ${candidateYear}` : ''}`;

        return pt.map(s => ({
          candidateId: imdbId,
          candidateTitle: shortTitle,
          url: s.url,
          lang: s.lang,
          fileName: (s.subtitleFileName || s.movieReleaseName || 'Legenda').replace(/\.srt$/i, '')
        }));
      } catch (e) {
        return [];
      }
    }

    async function renderSubtitleOptions(filterCandidateId = 'all', autoSelectFirst = false) {
      const allSubs = currentSubContext.allSubtitles || [];
      const filtered = (filterCandidateId === 'all')
        ? allSubs
        : allSubs.filter(s => s.candidateId === filterCandidateId);

      const isLeg = isLegendadoMedia(currentSubContext?.mediaMeta) || isLegendadoMedia({ name: currentSubContext?.rawTitle });

      elements.subSelect.innerHTML = isLeg
        ? '<option value="none" selected>Desativada (Legenda já no vídeo)</option>'
        : '<option value="none" selected>Desativada</option>';

      if (filtered.length === 0) {
        currentParsedCues = [];
        currentSubtitleActive = false;
        lastActiveCueHtml = null;
        updateSubtitleOverlay();
        if (!isLeg) elements.modalFormat.textContent = 'Sem legendas em Português para este filtro';
        return;
      }

      filtered.forEach((sub, idx) => {
        const opt = document.createElement('option');
        opt.value = sub.url;
        const flag = sub.lang === 'por' ? '🇵🇹' : '🇧🇷';
        const titlePrefix = (filterCandidateId === 'all') ? `[${sub.candidateTitle}] ` : '';
        opt.textContent = `${flag} ${titlePrefix}Opção ${idx + 1} (${sub.fileName.substring(0, 24)})`;
        elements.subSelect.appendChild(opt);
      });

      if (autoSelectFirst && !isLeg && filtered.length > 0) {
        elements.subSelect.value = filtered[0].url;
        const initialLabel = (filterCandidateId === 'all')
          ? `${filtered[0].candidateTitle} (Opção 1)`
          : 'Opção 1';
        await loadSubtitleFromUrl(filtered[0].url, initialLabel);
      } else {
        // Por padrão ou se for versão legendada: legendas desativadas
        elements.subSelect.value = 'none';
        elements.subSyncControls.style.display = 'none';
        currentParsedCues = [];
        currentSubtitleActive = false;
        lastActiveCueHtml = null;
        updateSubtitleOverlay();
        if (isLeg) {
          const oldTracks = elements.videoPlayer.querySelectorAll('track');
          oldTracks.forEach(t => t.remove());
          if (elements.videoPlayer.textTracks) {
            for (let i = 0; i < elements.videoPlayer.textTracks.length; i++) {
              try {
                elements.videoPlayer.textTracks[i].mode = 'disabled';
              } catch (e) { }
            }
          }
          elements.modalFormat.textContent = 'Formato: MP4 • Legenda impressa no frame';
        } else {
          elements.modalFormat.textContent = 'Formato: MP4 • Link Direto';
        }
      }
    }

    async function onCandidateSelectChange(selectedVal) {
      if (!selectedVal || selectedVal === 'none' || selectedVal === 'loading') return;

      if (selectedVal === 'all') {
        renderSubtitleOptions('all');
        return;
      }

      // Se já temos legendas desse candidato em cache
      const hasSubs = currentSubContext.allSubtitles.some(s => s.candidateId === selectedVal);
      if (hasSubs) {
        renderSubtitleOptions(selectedVal);
        return;
      }

      const selectedOpt = elements.subCandidateSelect.options[elements.subCandidateSelect.selectedIndex];
      const candName = selectedOpt ? selectedOpt.textContent.trim() : 'Filme';

      if (selectedVal.startsWith('tt')) {
        fetchImdbSidebarMetadataAsync(candName, currentSubContext.mediaType, {
          ...currentSubContext.mediaMeta,
          imdbId: selectedVal
        });
      }

      elements.modalFormat.textContent = `Buscando legendas para ${candName}...`;
      elements.subSelect.innerHTML = '<option value="loading" disabled selected>💬 Carregando opções...</option>';

      const newSubs = await fetchCandidateSubtitles(selectedVal, candName, '', currentSubContext.catalogType || 'movie');
      currentSubContext.allSubtitles = currentSubContext.allSubtitles.concat(newSubs);
      renderSubtitleOptions(selectedVal);
    }

    // ==========================================
    // MODAL DE BUSCA VISUAL NO IMDB COM POSTERS
    // ==========================================
    function openImdbSearchModal() {
      // 1. Pausa o vídeo imediatamente
      if (elements.videoPlayer && !elements.videoPlayer.paused) {
        elements.videoPlayer.pause();
      }

      const isSeries = currentSubContext.mediaType === 'series';
      const targetName = isSeries
        ? (currentSubContext.mediaMeta?.seriesName || currentSubContext.rawTitle)
        : currentSubContext.rawTitle;

      elements.imdbSearchType.value = isSeries ? 'series' : 'movie';
      elements.imdbSearchTargetDesc.textContent = (isSeries ? '📺 Série: ' : '🎬 Filme: ') + (targetName || 'Vídeo');

      const targetKeySource = isSeries ? currentSubContext.mediaMeta : currentSubContext.rawTitle;
      const saved = getSavedMediaMatch(currentSubContext.mediaType, targetKeySource);

      updateMemoryBanner(saved);

      const initialQuery = currentSubContext.cleanTitle || parseTitleInfo(targetName);
      elements.imdbSearchInput.value = initialQuery;

      elements.imdbSearchModal.style.display = 'flex';

      // Executa busca inicial para exibir os posters imediatamente
      executeImdbSearch(initialQuery);
    }

    function closeImdbSearchModal() {
      elements.imdbSearchModal.style.display = 'none';
      if (elements.videoPlayer && elements.videoPlayer.paused && elements.videoModal.style.display === 'flex') {
        elements.videoPlayer.play().catch(() => { });
      }
    }

    function updateMemoryBanner(saved) {
      const isSeries = currentSubContext.mediaType === 'series';
      if (saved && saved.id) {
        elements.imdbMemoryInfo.innerHTML = `
          <span class="imdb-status-badge">✓ Título Memorizado: <strong>${escapeHtml(saved.name)}</strong> (${saved.year || 'IMDb'})</span>
          <span style="color: #aaa; margin-left: 6px;">Vínculo salvo para ${isSeries ? 'todos os episódios desta série' : 'este filme'}.</span>
        `;
        elements.clearMemoryBtn.style.display = 'inline-block';
      } else {
        elements.imdbMemoryInfo.innerHTML = `
          <span style="color: #bbb;">ℹ️ Selecione o poster correto abaixo para memorizar permanentemente para ${isSeries ? 'todos os episódios desta série' : 'este filme'}.</span>
        `;
        elements.clearMemoryBtn.style.display = 'none';
      }
    }

    async function executeImdbSearch(queryOverride) {
      const query = (queryOverride !== undefined ? queryOverride : elements.imdbSearchInput.value || '').trim();
      const catalogType = elements.imdbSearchType.value || 'movie';

      if (!query) {
        elements.imdbSearchResults.innerHTML = '<div style="grid-column: 1 / -1; text-align: center; color: #888; padding: 30px;">Digite um nome ou código IMDb para pesquisar.</div>';
        return;
      }

      elements.imdbSearchResults.innerHTML = '<div style="grid-column: 1 / -1; text-align: center; color: #aaa; padding: 40px;"><div class="spinner" style="margin: 0 auto 15px;"></div>Buscando títulos e posters no catálogo...</div>';

      const isSeries = currentSubContext.mediaType === 'series';
      const targetKeySource = isSeries ? currentSubContext.mediaMeta : currentSubContext.rawTitle;
      const saved = getSavedMediaMatch(currentSubContext.mediaType, targetKeySource);

      try {
        const imdbMatch = query.match(/\btt\d+\b/i);
        let metas = [];

        if (imdbMatch) {
          const ttId = imdbMatch[0].toLowerCase();
          try {
            const metaRes = await fetch(`https://v3-cinemeta.strem.io/meta/${catalogType}/${ttId}.json`);
            const metaData = await metaRes.json();
            if (metaData && metaData.meta) {
              metas = [metaData.meta];
            } else {
              metas = [{ id: ttId, name: query, type: catalogType }];
            }
          } catch (e) {
            metas = [{ id: ttId, name: query, type: catalogType }];
          }
        } else {
          const searchUrl = `https://v3-cinemeta.strem.io/catalog/${catalogType}/top/search=${encodeURIComponent(query)}.json`;
          const res = await fetch(searchUrl);
          const data = await res.json();
          metas = (data && data.metas) ? data.metas : [];
        }

        if (metas.length === 0) {
          elements.imdbSearchResults.innerHTML = `
            <div style="grid-column: 1 / -1; text-align: center; color: #aaa; padding: 30px;">
              <p>Nenhum título encontrado para "<strong>${escapeHtml(query)}</strong>" em ${catalogType === 'series' ? 'Séries' : 'Filmes'}.</p>
              <p style="font-size: 12px; color: #777; margin-top: 6px;">Dica: Tente pesquisar pelo nome original em inglês ou cole o código IMDb (ex: tt13223398).</p>
            </div>
          `;
          return;
        }

        elements.imdbSearchResults.innerHTML = '';

        metas.forEach(item => {
          const isCurrentlySaved = saved && saved.id === item.id;
          const card = document.createElement('div');
          card.className = `imdb-card ${isCurrentlySaved ? 'selected-saved' : ''}`;

          const year = escapeHtml(item.releaseInfo || item.year || '');
          const poster = escapeHtml(item.poster || '');
          const title = item.name || 'Sem título';

          card.innerHTML = `
            <div class="imdb-thumb-container">
              ${poster
              ? `<img class="imdb-thumb" src="${poster}" alt="${escapeHtml(title)}" loading="lazy" data-hide-show-fallback-on-error>
                   <div style="display:none;height:100%;align-items:center;justify-content:center;color:#666;font-size:32px;">🎬</div>`
              : `<div style="display:flex;height:100%;align-items:center;justify-content:center;color:#666;font-size:32px;">🎬</div>`
            }
              ${year ? `<div class="imdb-year-badge">${year}</div>` : ''}
              ${isCurrentlySaved ? `<div style="position:absolute;bottom:6px;left:6px;right:6px;background:rgba(70,211,105,0.92);color:#000;font-size:10px;font-weight:bold;padding:2px 4px;border-radius:3px;text-align:center;">✓ Memorizado</div>` : ''}
            </div>
            <div class="imdb-card-body">
              <div>
                <div class="imdb-card-title" title="${escapeHtml(title)}">${escapeHtml(title)}</div>
                <div class="imdb-card-id">${escapeHtml(item.id)}</div>
              </div>
              <button class="btn ${isCurrentlySaved ? 'btn-secondary' : 'btn-primary'}" style="padding: 6px 8px; font-size: 11px; margin-top: 8px; width: 100%;">
                ${isCurrentlySaved ? '✓ Memorizado' : 'Selecionar & Salvar'}
              </button>
            </div>
          `;

          card.addEventListener('click', () => {
            selectAndMemorizeMedia(item, catalogType);
          });

          elements.imdbSearchResults.appendChild(card);
        });

      } catch (err) {
        console.error('Erro na pesquisa IMDb:', err);
        elements.imdbSearchResults.innerHTML = `<div style="grid-column: 1 / -1; text-align: center; color: #e50914; padding: 30px;">Erro ao buscar resultados: ${err.message}</div>`;
      }
    }

    async function selectAndMemorizeMedia(item, catalogType) {
      const isSeries = currentSubContext.mediaType === 'series';
      const targetKeySource = isSeries ? currentSubContext.mediaMeta : currentSubContext.rawTitle;

      const matchData = {
        id: item.id,
        name: item.name,
        year: item.releaseInfo || item.year || '',
        poster: item.poster || '',
        catalogType: catalogType,
        savedAt: Date.now()
      };

      saveMediaMatch(currentSubContext.mediaType, targetKeySource, matchData);
      updateMemoryBanner(matchData);

      // Salva customização de poster e título para o stream
      if (matchData.poster) {
        const streamId = currentSubContext.mediaMeta?.stream_id || (currentSubContext.mediaMeta?.primaryItem && currentSubContext.mediaMeta.primaryItem.stream_id);
        if (streamId) {
          const candYear = matchData.year ? ` (${matchData.year})` : '';
          localStorage.setItem(`andplay_poster_override_${streamId}`, matchData.poster);
          localStorage.setItem(`andplay_name_override_${streamId}`, `${matchData.name}${candYear}`);
        }
      }

      // Fecha o modal de busca e despausa (retoma o play) imediatamente ao voltar ao player
      closeImdbSearchModal();
      if (elements.videoPlayer && elements.videoPlayer.paused) {
        elements.videoPlayer.play().catch(() => { });
      }

      // Atualiza a ficha técnica com os dados do IMDb selecionado
      fetchImdbSidebarMetadataAsync(matchData.name, currentSubContext.mediaType, {
        ...currentSubContext.mediaMeta,
        imdbId: item.id
      });

      // Atualiza o dropdown de candidatos com o item selecionado e memorizado
      const candYear = matchData.year ? ` (${matchData.year})` : '';
      const candLabel = `🎯 ${matchData.name}${candYear} [Memorizado]`;

      Array.from(elements.subCandidateSelect.options).forEach(opt => {
        if (opt.value === item.id) opt.remove();
      });

      const opt = document.createElement('option');
      opt.value = item.id;
      opt.textContent = candLabel;
      elements.subCandidateSelect.insertBefore(opt, elements.subCandidateSelect.firstChild);
      elements.subCandidateSelect.value = item.id;

      // Carregar as legendas desse candidato
      elements.modalFormat.textContent = `Buscando legendas de "${matchData.name}"...`;
      elements.subSelect.innerHTML = '<option value="loading" disabled selected>💬 Carregando opções...</option>';

      const newSubs = await fetchCandidateSubtitles(item.id, matchData.name, matchData.year, catalogType);
      currentSubContext.allSubtitles = newSubs;

      const isLeg = isLegendadoMedia(currentSubContext?.mediaMeta) || isLegendadoMedia({ name: currentSubContext?.rawTitle });

      // Auto-selecionar Opção 1 saindo da opção de legenda desativada (apenas se NÃO for mídia com legenda no frame)
      await renderSubtitleOptions(item.id, !isLeg);

      // Garante que o play está ativo ao voltar ao player
      if (elements.videoPlayer && elements.videoPlayer.paused) {
        elements.videoPlayer.play().catch(() => { });
      }

      if (isLeg) {
        disableActiveSubtitle('Legenda externa desativada (Vídeo possui legenda no frame)');
      } else if (newSubs.length === 0) {
        elements.modalFormat.textContent = `Nenhuma legenda em Português encontrada para ${matchData.name}`;
      }

      // Abre o painel de legendas para facilitar a seleção de versão pelo usuário
      if (elements.subOptionsPanel.style.display !== 'block') {
        toggleSubOptionsPanel();
      }
    }

    function clearMediaMatch() {
      const isSeries = currentSubContext.mediaType === 'series';
      const targetKeySource = isSeries ? currentSubContext.mediaMeta : currentSubContext.rawTitle;
      removeMediaMatch(currentSubContext.mediaType, targetKeySource);
      const sid = currentSubContext.mediaMeta?.stream_id || (currentSubContext.mediaMeta?.primaryItem && currentSubContext.mediaMeta.primaryItem.stream_id);
      if (sid) {
        try {
          localStorage.removeItem(`andplay_poster_override_${sid}`);
          localStorage.removeItem(`andplay_name_override_${sid}`);
        } catch (e) { }
      }
      updateMemoryBanner(null);

      // Reexecuta busca normal no modal
      executeImdbSearch(elements.imdbSearchInput.value);

      // Recarrega candidatos padrão no player
      autoFetchSubtitle(currentSubContext.rawTitle, currentSubContext.mediaType, currentSubContext.mediaMeta);
    }

    async function onSubtitleSelectChange(url) {
      if (!url || url === 'none') {
        disableActiveSubtitle('Legenda desativada');
        return;
      }

      const selectedOpt = elements.subSelect.options[elements.subSelect.selectedIndex];
      const label = selectedOpt ? selectedOpt.textContent.split('(')[0].trim() : 'Legenda';
      await loadSubtitleFromUrl(url, label);
    }

    async function loadSubtitleFromUrl(url, label) {
      try {
        elements.modalFormat.textContent = `Carregando ${label}...`;
        const res = await fetch(url);
        const srtText = await res.text();
        currentRawSubtitleText = srtText;
        currentSubtitleLabel = label;
        currentSubtitleOffset = 0.0;
        elements.subOffsetDisplay.textContent = '0.0s';
        elements.subSyncControls.style.display = 'inline-flex';
        applySubtitleText(srtText, label);
      } catch (err) {
        console.error('Erro ao baixar arquivo de legenda:', err);
        elements.modalFormat.textContent = 'Erro ao carregar esta legenda';
      }
    }

    function changeSubtitleOffset(delta) {
      if (!currentRawSubtitleText) return;
      currentSubtitleOffset = parseFloat((currentSubtitleOffset + delta).toFixed(1));
      const sign = currentSubtitleOffset > 0 ? '+' : '';
      elements.subOffsetDisplay.textContent = `${sign}${currentSubtitleOffset.toFixed(1)}s`;
      applySubtitleText(currentRawSubtitleText, currentSubtitleLabel);
    }

    function applySubtitleText(text, label = 'Português') {
      const cleanText = (text || '').replace(/^\uFEFF/, '');
      currentParsedCues = parseSubtitleTextToCues(cleanText, currentSubtitleOffset);
      currentSubtitleActive = currentParsedCues.length > 0;
      lastActiveCueHtml = null;
      updateSubtitleOverlay();

      // Limpar faixas anteriores e revogar Blob URLs para evitar memory leak
      const oldTracks = elements.videoPlayer.querySelectorAll('track');
      oldTracks.forEach(t => {
        if (t.src && t.src.startsWith('blob:')) {
          try { URL.revokeObjectURL(t.src); } catch (_) {}
        }
        t.remove();
      });

      try {
        let vttContent = cleanText;
        if (!cleanText.startsWith('WEBVTT') || currentSubtitleOffset !== 0) {
          vttContent = srtToVtt(cleanText, currentSubtitleOffset);
        }
        const blob = new Blob([vttContent], { type: 'text/vtt' });
        const trackUrl = URL.createObjectURL(blob);
        const track = document.createElement('track');
        track.kind = 'subtitles';
        track.label = label;
        track.srclang = 'pt';
        track.src = trackUrl;
        elements.videoPlayer.appendChild(track);
        track.addEventListener('load', () => {
          try {
            if (track.track) track.track.mode = 'hidden';
          } catch (_) {}
        });
      } catch (_) {}

      const offsetText = currentSubtitleOffset !== 0 ? ` (sync: ${currentSubtitleOffset > 0 ? '+' : ''}${currentSubtitleOffset}s)` : '';
      elements.modalFormat.textContent = `💬 ${label} ativa${offsetText}`;
    }

    function srtToVtt(srtText, offsetSeconds = 0) {
      let vtt = "WEBVTT\n\n";
      const normalized = (srtText || '').replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      const blocks = normalized.trim().split(/\n\n+/);

      function shiftTime(timeStr, offset) {
        const clean = timeStr.trim();
        if (!offset) return clean.replace(',', '.');
        const parts = clean.split(':');
        if (parts.length < 2) return clean.replace(',', '.');
        let h = 0, m = 0, s = 0;
        if (parts.length === 2) {
          m = parseInt(parts[0], 10) || 0;
          s = parseFloat(parts[1].replace(',', '.')) || 0;
        } else {
          h = parseInt(parts[0], 10) || 0;
          m = parseInt(parts[1], 10) || 0;
          s = parseFloat(parts[2].replace(',', '.')) || 0;
        }
        let total = h * 3600 + m * 60 + s + offset;
        if (total < 0) total = 0;
        const newH = Math.floor(total / 3600).toString().padStart(2, '0');
        const newM = Math.floor((total % 3600) / 60).toString().padStart(2, '0');
        const secVal = (total % 60).toFixed(3);
        const newS = secVal.padStart(6, '0');
        return `${newH}:${newM}:${newS}`;
      }

      blocks.forEach(block => {
        const lines = block.split('\n');
        if (lines.length >= 2) {
          let timeIndex = 0;
          if (/^\d+$/.test(lines[0].trim())) {
            timeIndex = 1;
          }
          if (lines[timeIndex] && lines[timeIndex].includes('-->')) {
            const timeParts = lines[timeIndex].split('-->');
            if (timeParts.length === 2) {
              const start = shiftTime(timeParts[0], offsetSeconds);
              const end = shiftTime(timeParts[1], offsetSeconds);
              const textLines = lines.slice(timeIndex + 1).join('\n');
              vtt += `${start} --> ${end}\n${textLines}\n\n`;
            }
          }
        }
      });
      return vtt;
    }

    function loadSubtitleFromFile(file) {
      const reader = new FileReader();
      reader.onload = (e) => {
        currentRawSubtitleText = e.target.result;
        currentSubtitleLabel = file.name.replace(/\.(srt|vtt)$/i, '');
        currentSubtitleOffset = 0.0;
        elements.subOffsetDisplay.textContent = '0.0s';
        elements.subSyncControls.style.display = 'inline-flex';

        // Adicionar no select
        const opt = document.createElement('option');
        opt.value = 'custom_file';
        opt.textContent = `📁 ${currentSubtitleLabel}`;
        elements.subSelect.appendChild(opt);
        elements.subSelect.value = 'custom_file';

        applySubtitleText(currentRawSubtitleText, currentSubtitleLabel);
      };
      reader.readAsText(file);
    }

    // ==========================================
    // DETALHES E EPISÓDIOS DA SÉRIE
    // ==========================================
    let currentSeriesGroup = null;
    let currentActiveSeriesVersion = null;
    let currentContentPageType = '';
    let currentContentPageItem = null;
    let contentPageReturnState = null;
    let contentPageOpen = false;

    const SERIES_EPISODE_HISTORY_KEY = 'andplay_web_series_episode_history_v1';

    function readSeriesEpisodeHistory() {
      try {
        const raw = localStorage.getItem(SERIES_EPISODE_HISTORY_KEY);
        const parsed = raw ? JSON.parse(raw) : {};
        return parsed && typeof parsed === 'object' ? parsed : {};
      } catch (e) {
        return {};
      }
    }

    function writeSeriesEpisodeHistory(history, sync = true) {
      try {
        localStorage.setItem(SERIES_EPISODE_HISTORY_KEY, JSON.stringify(history || {}));
        if (sync) saveLocalPreference('series_episode_history', history || {});
      } catch (e) {}
    }

    function getSeriesEpisodeState(ep) {
      const id = String(ep?.id || '');
      if (!id) return null;
      return readSeriesEpisodeHistory()[id] || null;
    }

    function markSeriesEpisodeWatched(ep, seasonNum, playbackDuration = 0) {
      const id = String(ep?.id || '');
      if (!id) return;
      const history = readSeriesEpisodeHistory();
      const duration = Number(playbackDuration || ep.info?.duration_secs || ep.info?.duration_seconds || 0) || 0;
      history[id] = {
        status: 'watched',
        position: duration,
        duration,
        title: String(ep.title || 'Episódio ' + (ep.episode_num || '')),
        seasonNum: Number(seasonNum) || 0,
        episodeNum: Number(ep.episode_num) || 0,
        updatedAt: Date.now()
      };
      const ids = Object.keys(history);
      if (ids.length > 2000) {
        ids.sort((x, y) => Number(history[y]?.updatedAt || 0) - Number(history[x]?.updatedAt || 0));
        ids.slice(2000).forEach(key => delete history[key]);
      }
      writeSeriesEpisodeHistory(history);
      if (contentPageOpen && currentContentPageType === 'series') {
        requestAnimationFrame(() => renderSeasonEpisodes(String(seasonNum)));
      }
    }

    function getEpisodePlaybackState(ep) {
      const id = String(ep?.id || '');
      if (!id) return { status: 'new', position: 0, duration: 0, remaining: 0 };

      const historyState = getSeriesEpisodeState(ep);
      const progress = getVodProgress('series', id);
      const position = Number(progress?.position || historyState?.position || 0);
      const duration = Number(progress?.duration || historyState?.duration || 0);
      const hasProgress = position > VOD_PROGRESS_MIN_SECONDS && duration > 0;
      const watchedAt = Number(historyState?.updatedAt || 0);
      const progressIsNewer = Number(progress?.updatedAt || 0) > watchedAt;
      const watched = historyState?.status === 'watched' && !progressIsNewer;

      if (watched) {
        return {
          status: 'watched',
          position: duration || position,
          duration,
          remaining: 0
        };
      }

      return {
        status: hasProgress ? 'resume' : 'new',
        position,
        duration,
        remaining: duration > 0 ? Math.max(0, duration - position) : 0
      };
    }

    function showContentPageShell(type, item) {
      if (!elements.contentPage) return;
      currentContentPageType = type;
      currentContentPageItem = item;
      contentPageOpen = true;

      // Cancela loadings anteriores
      hideLoading();

      // Desativa e destrói hubs e timers anteriores para manter a página única
      if (elements.moviesHub) elements.moviesHub.style.display = 'none';
      if (elements.seriesHub) elements.seriesHub.style.display = 'none';
      if (elements.liveHub) elements.liveHub.style.display = 'none';
      if (elements.favoritesHub) elements.favoritesHub.style.display = 'none';
      if (elements.watchedHub) elements.watchedHub.style.display = 'none';
      if (elements.userDashboard) elements.userDashboard.style.display = 'none';
      if (elements.mediaGrid) elements.mediaGrid.style.display = 'none';
      if (elements.loadMoreContainer) elements.loadMoreContainer.style.display = 'none';
      if (moviesHeroTimer) { clearInterval(moviesHeroTimer); moviesHeroTimer = null; }
      if (seriesHeroTimer) { clearInterval(seriesHeroTimer); seriesHeroTimer = null; }
      if (homeFeaturedTimer) { clearInterval(homeFeaturedTimer); homeFeaturedTimer = null; }

      setHomeDashboardVisible(false);
      document.querySelector('.status-bar')?.style.setProperty('display', 'none');
      document.querySelector('main')?.style.setProperty('display', 'none');
      if (elements.categorySelect) elements.categorySelect.disabled = true;

      elements.contentPage.style.display = 'block';
      elements.contentPage.hidden = false;
      elements.contentPage.classList.add('is-active');
      window.scrollTo({ top: 0, behavior: 'instant' });

      const title = item?.name || item?.title || (type === 'series' ? 'Série' : 'Filme');
      const poster = type === 'series'
        ? (item?.cover || item?.stream_icon || '')
        : (item?.poster || getBestPosterUrl(item?.primaryItem || item) || item?.stream_icon || '');

      elements.contentPageKicker.textContent = type === 'series' ? 'SÉRIE' : 'FILME';
      elements.contentPageType.textContent = type === 'series' ? 'SÉRIE' : 'FILME';
      elements.contentPageTitle.textContent = title;
      elements.contentPagePoster.src = poster;
      elements.contentPageBackdrop.style.backgroundImage = poster ? 'url("' + String(poster).replace(/"/g, '%22') + '")' : 'none';

      const ratingInfo = getHomeRatingInfo(item);
      const year = item?.year || item?.releaseDate?.substring?.(0, 4) || '';
      elements.contentPageMeta.textContent = [
        year,
        ratingInfo.value > 0 ? '★ ' + ratingInfo.value.toFixed(1) + ' ' + ratingInfo.source : ''
      ].filter(Boolean).join(' • ');

      const genres = getHomeThemesForItem(item);
      elements.contentPageGenres.innerHTML = genres.map(genre =>
        '<button type="button" class="eplay-content-genre" data-context-search="' + escapeHtml(genre) + '" title="Filtrar por ' + escapeHtml(genre) + '">' + escapeHtml(genre) + '</button>'
      ).join('');
      elements.contentPagePlot.textContent = item?.plot || item?.description || 'Sinopse não disponível.';
      renderContentPageDirector(item);
      renderContentPageCast(item);
      updateFavoriteButton(type, item);
    }

    function renderContentPageDirector(item) {
      if (!elements.contentPageDirector) return;
      const imdbId = item?.imdbId || item?.imdb_id || item?.primaryItem?.imdbId || item?.primaryItem?.imdb_id || '';
      const cached = imdbId ? readHomeRatingCache()[String(imdbId)] : null;
      const directors = parseDirectorList(
        item?.director ||
        item?.primaryItem?.director ||
        cached?.director
      );
      if (!directors.length) {
        elements.contentPageDirector.style.display = 'none';
        elements.contentPageDirector.innerHTML = '';
        return;
      }
      elements.contentPageDirector.style.display = 'flex';
      elements.contentPageDirector.innerHTML =
        '<span class="eplay-content-meta-label">Direção:</span>' +
        directors.map(dir =>
          '<button type="button" class="eplay-director-badge" data-context-search="' + escapeHtml(dir) + '" data-context-person="true" title="Buscar títulos dirigidos por ' + escapeHtml(dir) + '">' +
            escapeHtml(dir) +
          '</button>'
        ).join('');
    }

    function renderContentPageCast(item) {
      scheduleRelatedTitles(item);
      if (!elements.contentPageCast) return;
      const imdbId = item?.imdbId || item?.imdb_id || item?.primaryItem?.imdbId || item?.primaryItem?.imdb_id || '';
      const cached = imdbId ? readHomeRatingCache()[String(imdbId)] : null;
      const castList = parseCastList(
        item?.cast ||
        item?.actors ||
        item?.primaryItem?.cast ||
        item?.primaryItem?.actors ||
        cached?.cast
      );
      if (!castList.length) {
        elements.contentPageCast.style.display = 'none';
        elements.contentPageCast.innerHTML = '';
        return;
      }
      const topCast = castList.slice(0, 12);
      elements.contentPageCast.style.display = 'flex';
      elements.contentPageCast.innerHTML =
        '<span class="eplay-content-meta-label">Elenco:</span>' +
        topCast.map(actor =>
          '<button type="button" class="eplay-cast-badge" data-context-search="' + escapeHtml(actor) + '" data-context-person="true" title="Buscar títulos com ' + escapeHtml(actor) + '">' +
            escapeHtml(actor) +
          '</button>'
        ).join('');
    }

    // ==========================================
    // RECOMENDADOS: "Já que gostou de X, veja estes"
    // Pontua filmes E séries (misturados) por estilos, elenco e direção em comum.
    // ==========================================
    const RELATED_TITLES_LIMIT = 15;
    const RELATED_WEIGHTS = { genre: 1, cast: 3, director: 4 };
    const relatedThemesCache = new WeakMap();
    let relatedTitlesTimer = null;
    let relatedLastItem = null;

    function relatedPersonKey(name) {
      return String(name || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9 ]/g, '').replace(/\s+/g, ' ').trim();
    }

    function getRelatedProfile(entry, ratingCache) {
      const src = entry?.item || entry;
      const imdbId = entry?.imdbId || entry?.imdb_id || src?.imdbId || src?.imdb_id || src?.primaryItem?.imdbId || src?.primaryItem?.imdb_id || '';
      const cached = imdbId ? ratingCache[String(imdbId)] : null;

      let themes = src && typeof src === 'object' ? relatedThemesCache.get(src) : null;
      if (!themes) {
        themes = getHomeThemesForItem(entry);
        if (src && typeof src === 'object' && themes.length) relatedThemesCache.set(src, themes);
      }

      const castRaw = parseCastList(src?.cast || src?.actors || src?.primaryItem?.cast || src?.primaryItem?.actors || cached?.cast);
      const dirRaw = parseDirectorList(src?.director || src?.primaryItem?.director || cached?.director);
      const cast = new Map();
      castRaw.forEach(n => { const k = relatedPersonKey(n); if (k) cast.set(k, n); });
      const directors = new Map();
      dirRaw.forEach(n => { const k = relatedPersonKey(n); if (k) directors.set(k, n); });
      return { themes, cast, directors };
    }

    function computeRelatedTitles(currentItem, currentType) {
      const ratingCache = readHomeRatingCache();
      const base = getRelatedProfile(currentItem, ratingCache);
      if (!base.themes.length && !base.cast.size && !base.directors.size) return [];

      const baseSrc = currentItem?.primaryItem || currentItem;
      const currentId = String(
        currentType === 'series'
          ? (currentItem?.series_id || currentItem?.versions?.[0]?.seriesId || baseSrc?.series_id || '')
          : (currentItem?.stream_id || baseSrc?.stream_id || '')
      );
      const currentTitleKey = normalizeSearch(cleanDisplayTitle(currentItem?.name || currentItem?.title || ''));
      const baseThemes = new Set(base.themes);

      const scored = [];
      [...getHomeCatalogItems('movie'), ...getHomeCatalogItems('series')].forEach(entry => {
        if (entry.type === currentType && entry.id === currentId) return;
        if (currentTitleKey && normalizeSearch(entry.title) === currentTitleKey) return;

        const prof = getRelatedProfile(entry, ratingCache);
        const sharedGenres = prof.themes.filter(t => baseThemes.has(t));
        const sharedCast = [];
        prof.cast.forEach((name, key) => { if (base.cast.has(key)) sharedCast.push(name); });
        const sharedDirectors = [];
        prof.directors.forEach((name, key) => { if (base.directors.has(key)) sharedDirectors.push(name); });

        const score = sharedGenres.length * RELATED_WEIGHTS.genre
          + sharedCast.length * RELATED_WEIGHTS.cast
          + sharedDirectors.length * RELATED_WEIGHTS.director;
        if (score <= 0) return;

        let reason = '';
        if (sharedDirectors.length) reason = 'Direção: ' + sharedDirectors[0];
        else if (sharedCast.length) reason = 'Elenco: ' + sharedCast.slice(0, 2).join(', ');
        else reason = sharedGenres.slice(0, 2).join(' • ');

        scored.push({ entry, score, reason, rating: parseFloat(entry.rating) || 0 });
      });

      scored.sort((a, b) => (b.score - a.score) || (b.rating - a.rating) || ((b.entry.added || 0) - (a.entry.added || 0)));
      return scored.slice(0, RELATED_TITLES_LIMIT);
    }

    function renderRelatedTitles(item) {
      const panel = elements.contentRelatedPanel;
      const grid = elements.contentRelatedGrid;
      if (!panel || !grid) return;
      if (!contentPageOpen || currentContentPageItem !== item) return;

      const results = computeRelatedTitles(item, currentContentPageType === 'series' ? 'series' : 'movie');
      grid.innerHTML = '';
      if (!results.length) {
        panel.hidden = true;
        return;
      }

      const shownTitle = cleanDisplayTitle(item?.name || item?.title || '');
      if (elements.contentRelatedHeading) {
        elements.contentRelatedHeading.textContent = shownTitle
          ? 'Já que gostou de "' + shownTitle + '", veja estes'
          : 'Já que gostou, veja estes';
      }

      const fragment = document.createDocumentFragment();
      results.forEach(({ entry, reason }) => {
        const card = renderHomeTitleCard(entry);
        if (reason) {
          const reasonEl = document.createElement('span');
          reasonEl.className = 'eplay-related-reason';
          reasonEl.title = reason;
          reasonEl.textContent = reason;
          card.appendChild(reasonEl);
        }
        fragment.appendChild(card);
      });
      grid.appendChild(fragment);
      panel.hidden = false;
    }

    function scheduleRelatedTitles(item) {
      if (!item || !elements.contentRelatedPanel) return;
      if (relatedLastItem !== item) {
        relatedLastItem = item;
        elements.contentRelatedPanel.hidden = true;
        if (elements.contentRelatedGrid) elements.contentRelatedGrid.innerHTML = '';
      }
      clearTimeout(relatedTitlesTimer);
      // Debounce: metadados (elenco/direção) chegam em etapas e disparam novos renders
      relatedTitlesTimer = setTimeout(() => {
        try { renderRelatedTitles(item); } catch (err) { console.warn('[EPlay] Falha ao montar recomendados:', err); }
      }, 350);
    }

    function searchByContextTerm(term, forceGlobal = false) {
      if (!term) return;
      restoreFromContentPage();
      if (elements.searchInput) {
        elements.searchInput.value = term;
      }
      if (forceGlobal || currentMode === 'home' || currentMode === 'search') {
        performGlobalSearch(term);
      } else {
        onSearch(term);
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    async function enrichMoviePageMetadata(groupOrMovie, streamId) {
      if (!groupOrMovie || !contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
      const imdbId = groupOrMovie.imdbId || groupOrMovie.imdb_id || groupOrMovie.primaryItem?.imdbId || groupOrMovie.primaryItem?.imdb_id;
      const cachedRating = imdbId ? readHomeRatingCache()[String(imdbId)] : null;
      if (cachedRating && (cachedRating.cast?.length || cachedRating.director?.length)) {
        if (!groupOrMovie.cast && cachedRating.cast?.length) groupOrMovie.cast = cachedRating.cast;
        if (!groupOrMovie.director && cachedRating.director?.length) groupOrMovie.director = cachedRating.director;
        groupOrMovie._searchableText = null;
        if (contentPageOpen && currentContentPageItem === groupOrMovie) {
          renderContentPageDirector(groupOrMovie);
          renderContentPageCast(groupOrMovie);
        }
      }

      // 1. Consulta get_vod_info para enriquecer elenco e diretor
      if ((!groupOrMovie.cast || !groupOrMovie.director) && streamId) {
        if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
        try {
          const data = await xtreamApi('get_vod_info', '&vod_id=' + encodeURIComponent(streamId));
          if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
          const info = data?.info;
          if (info) {
            let updated = false;
            const cast = info.cast || info.actors;
            const director = info.director;
            if (cast && !groupOrMovie.cast) {
              groupOrMovie.cast = cast;
              updated = true;
            }
            if (director && !groupOrMovie.director) {
              groupOrMovie.director = director;
              updated = true;
            }
            if (info.genre && !groupOrMovie.genre) {
              groupOrMovie.genre = info.genre;
              updated = true;
            }
            if (info.plot && (!groupOrMovie.plot || groupOrMovie.plot === 'Sinopse não disponível.')) {
              groupOrMovie.plot = info.plot;
              if (contentPageOpen && currentContentPageItem === groupOrMovie && elements.contentPagePlot) {
                elements.contentPagePlot.textContent = info.plot;
              }
            }
            if (updated) {
              groupOrMovie._searchableText = null;
              if (contentPageOpen && currentContentPageItem === groupOrMovie) {
                renderContentPageDirector(groupOrMovie);
                renderContentPageCast(groupOrMovie);
              }
            }
          }
        } catch (_) {}
      }

      // 2. Se ainda faltar elenco/diretor, consulta Cinemeta
      if (!groupOrMovie.cast || !groupOrMovie.director) {
        if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
        try {
          let resolvedImdbId = groupOrMovie.imdbId || groupOrMovie.imdb_id;
          if (!resolvedImdbId) {
            const rawTitle = groupOrMovie.name || groupOrMovie.title || '';
            const searchStr = parseTitleInfo(rawTitle) || cleanTitleKey(rawTitle);
            if (searchStr) {
              const sUrl = `https://v3-cinemeta.strem.io/catalog/movie/top/search=${encodeURIComponent(searchStr)}.json`;
              const sData = await fetchJsonWithTimeout(sUrl, 3500);
              if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
              const metas = Array.isArray(sData?.metas) ? sData.metas : [];
              if (metas.length > 0) {
                resolvedImdbId = metas[0].id || metas[0].imdb_id;
              }
            }
          }
          if (resolvedImdbId) {
            if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
            groupOrMovie.imdbId = resolvedImdbId;
            groupOrMovie.imdb_id = resolvedImdbId;
            const mUrl = `https://v3-cinemeta.strem.io/meta/movie/${encodeURIComponent(resolvedImdbId)}.json`;
            const mData = await fetchJsonWithTimeout(mUrl, 3500);
            if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
            const meta = mData?.meta;
            if (meta) {
              let updated = false;
              if (meta.cast && !groupOrMovie.cast) {
                groupOrMovie.cast = meta.cast;
                updated = true;
              }
              if (meta.director && !groupOrMovie.director) {
                groupOrMovie.director = meta.director;
                updated = true;
              }
              if (meta.description && (!groupOrMovie.plot || groupOrMovie.plot === 'Sinopse não disponível.')) {
                groupOrMovie.plot = meta.description;
                if (contentPageOpen && currentContentPageItem === groupOrMovie && elements.contentPagePlot) {
                  elements.contentPagePlot.textContent = meta.description;
                }
              }
              const cache = readHomeRatingCache();
              cache[String(resolvedImdbId)] = {
                rating: Number(meta.imdbRating || 0) || cache[String(resolvedImdbId)]?.rating || 0,
                genres: Array.isArray(meta.genres) ? meta.genres : (cache[String(resolvedImdbId)]?.genres || []),
                cast: Array.isArray(meta.cast) ? meta.cast : [],
                director: Array.isArray(meta.director) ? meta.director : (meta.director ? [meta.director] : []),
                fetchedAt: Date.now()
              };
              homeRatingCacheMemory = cache;
              try { localStorage.setItem(HOME_RATING_CACHE_KEY, JSON.stringify(cache)); } catch (_) {}

              if (updated) {
                groupOrMovie._searchableText = null;
                if (contentPageOpen && currentContentPageItem === groupOrMovie) {
                  renderContentPageDirector(groupOrMovie);
                  renderContentPageCast(groupOrMovie);
                }
              }
            }
          }
        } catch (_) {}
      }
    }

    async function enrichSeriesPageMetadata(seriesGroupOrItem) {
      if (!seriesGroupOrItem || !contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
      const imdbId = seriesGroupOrItem.imdbId || seriesGroupOrItem.imdb_id || seriesGroupOrItem.primaryItem?.imdbId || seriesGroupOrItem.primaryItem?.imdb_id;
      const cachedRating = imdbId ? readHomeRatingCache()[String(imdbId)] : null;
      if (cachedRating && (cachedRating.cast?.length || cachedRating.director?.length)) {
        if (!seriesGroupOrItem.cast && cachedRating.cast?.length) seriesGroupOrItem.cast = cachedRating.cast;
        if (!seriesGroupOrItem.director && cachedRating.director?.length) seriesGroupOrItem.director = cachedRating.director;
        seriesGroupOrItem._searchableText = null;
        if (contentPageOpen && currentContentPageItem === seriesGroupOrItem) {
          renderContentPageDirector(seriesGroupOrItem);
          renderContentPageCast(seriesGroupOrItem);
        }
      }

      if (!seriesGroupOrItem.cast || !seriesGroupOrItem.director) {
        if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
        try {
          let resolvedImdbId = seriesGroupOrItem.imdbId || seriesGroupOrItem.imdb_id;
          if (!resolvedImdbId) {
            const rawTitle = seriesGroupOrItem.name || seriesGroupOrItem.title || '';
            const searchStr = parseTitleInfo(rawTitle) || cleanTitleKey(rawTitle);
            if (searchStr) {
              const sUrl = `https://v3-cinemeta.strem.io/catalog/series/top/search=${encodeURIComponent(searchStr)}.json`;
              const sData = await fetchJsonWithTimeout(sUrl, 3500);
              if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
              const metas = Array.isArray(sData?.metas) ? sData.metas : [];
              if (metas.length > 0) {
                resolvedImdbId = metas[0].id || metas[0].imdb_id;
              }
            }
          }
          if (resolvedImdbId) {
            if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
            seriesGroupOrItem.imdbId = resolvedImdbId;
            seriesGroupOrItem.imdb_id = resolvedImdbId;
            const mUrl = `https://v3-cinemeta.strem.io/meta/series/${encodeURIComponent(resolvedImdbId)}.json`;
            const mData = await fetchJsonWithTimeout(mUrl, 3500);
            if (!contentPageOpen || (elements.videoModal && elements.videoModal.style.display === 'flex')) return;
            const meta = mData?.meta;
            if (meta) {
              let updated = false;
              if (meta.cast && !seriesGroupOrItem.cast) {
                seriesGroupOrItem.cast = meta.cast;
                updated = true;
              }
              if (meta.director && !seriesGroupOrItem.director) {
                seriesGroupOrItem.director = meta.director;
                updated = true;
              }
              if (meta.description && (!seriesGroupOrItem.plot || seriesGroupOrItem.plot === 'Sinopse não disponível.')) {
                seriesGroupOrItem.plot = meta.description;
                if (contentPageOpen && currentContentPageItem === seriesGroupOrItem && elements.contentPagePlot) {
                  elements.contentPagePlot.textContent = meta.description;
                }
              }
              const cache = readHomeRatingCache();
              cache[String(resolvedImdbId)] = {
                rating: Number(meta.imdbRating || 0) || cache[String(resolvedImdbId)]?.rating || 0,
                genres: Array.isArray(meta.genres) ? meta.genres : (cache[String(resolvedImdbId)]?.genres || []),
                cast: Array.isArray(meta.cast) ? meta.cast : [],
                director: Array.isArray(meta.director) ? meta.director : (meta.director ? [meta.director] : []),
                fetchedAt: Date.now()
              };
              homeRatingCacheMemory = cache;
              try { localStorage.setItem(HOME_RATING_CACHE_KEY, JSON.stringify(cache)); } catch (_) {}

              if (updated) {
                seriesGroupOrItem._searchableText = null;
                if (contentPageOpen && currentContentPageItem === seriesGroupOrItem) {
                  renderContentPageDirector(seriesGroupOrItem);
                  renderContentPageCast(seriesGroupOrItem);
                }
              }
            }
          }
        } catch (_) {}
      }
    }

    function restoreFromContentPage() {
      const state = contentPageReturnState || {
        mode: 'home',
        watched: false,
        favorites: false,
        category: 'ALL',
        search: ''
      };
      contentPageOpen = false;
      currentContentPageType = '';
      currentContentPageItem = null;
      contentPageReturnState = null;

      if (elements.contentPage) {
        elements.contentPage.hidden = true;
        elements.contentPage.classList.remove('is-active');
        elements.contentPage.style.display = 'none';
        if (elements.contentPageBackdrop) elements.contentPageBackdrop.style.backgroundImage = 'none';
        if (elements.contentPagePoster) elements.contentPagePoster.src = '';
        if (elements.contentMovieVersions) elements.contentMovieVersions.innerHTML = '';
        if (elements.contentEpisodesList) elements.contentEpisodesList.innerHTML = '';
      }

      if (!isHandlingPopstate) {
        const returnHash = (state.mode === 'home')
          ? (state.favorites ? '#/favoritos' : '#/inicio')
          : (state.watched ? '#/assistidos' : (state.favorites ? '#/favoritos' : (state.mode === 'movies' ? '#/filmes' : '#/series')));
        setRouteHash(returnHash, false);
      }

      if (state.mode === 'home' && state.favorites) {
        showFavoritesContent();
        return;
      }
      if (state.mode === 'home') {
        showHome(state.scrollY);
        return;
      }

      if (state.watched) {
        showWatchedContent();
        return;
      }
      if (state.favorites) {
        showFavoritesContent();
        return;
      }

      const mode = state.mode === 'series' ? 'series' : 'movies';
      isWatchedView = false;
      isFavoritesView = false;
      currentMode = mode;
      elements.tabHomeBtn?.classList.remove('active');
      elements.tabMoviesBtn.classList.toggle('active', mode === 'movies');
      elements.tabSeriesBtn.classList.toggle('active', mode === 'series');
      elements.tabLiveBtn?.classList.remove('active');
      elements.tabWatchedBtn?.classList.remove('active');
      elements.tabFavoritesBtn?.classList.remove('active');
      document.querySelectorAll('.mobile-bottom-nav button').forEach(button => button.classList.remove('active'));
      if (mode === 'movies') elements.mobileMoviesBtn?.classList.add('active');
      else elements.mobileSeriesBtn?.classList.add('active');

      setHomeDashboardVisible(false);
      document.querySelector('.status-bar')?.style.removeProperty('display');
      document.querySelector('main')?.style.removeProperty('display');
      if (mode === 'movies' && elements.moviesHub) elements.moviesHub.style.removeProperty('display');
      if (mode === 'series' && elements.seriesHub) elements.seriesHub.style.removeProperty('display');
      elements.categorySelect.disabled = false;
      elements.categorySelect.style.removeProperty('display');
      elements.searchInput.value = String(state.search || '');
      currentMediaList = mode === 'movies' ? (fullMoviesCache || []) : (fullSeriesCache || []);

      const savedCategory = String(state.category || 'ALL');
      const hasSavedCategory = savedCategory === 'ALL' || Array.from(elements.categorySelect.options).some(option => option.value === savedCategory);
      elements.categorySelect.value = hasSavedCategory ? savedCategory : 'ALL';
      elements.resetCategoryBtn.style.display = elements.categorySelect.value === 'ALL' ? 'none' : 'inline-flex';
      if (elements.categorySelect.value === 'ALL') {
        elements.categoryLabel.textContent = mode === 'movies'
          ? 'Catálogo Geral: Todos os Filmes'
          : 'Catálogo Geral: Todas as Séries';
      } else {
        const selected = elements.categorySelect.options[elements.categorySelect.selectedIndex];
        elements.categoryLabel.textContent = 'Categoria: ' + (selected ? selected.textContent : '');
        currentMediaList = currentMediaList.filter(item => {
          if (String(item.category_id) === elements.categorySelect.value) return true;
          return Array.isArray(item.category_ids) && item.category_ids.some(id => String(id) === elements.categorySelect.value);
        });
      }
      elements.searchInput.placeholder = mode === 'movies'
        ? 'Pesquisar filmes por título, gênero, ator ou diretor...'
        : 'Pesquisar séries por título, gênero ou elenco...';
      if (mode === 'movies' && !state.search && (!state.category || state.category === 'ALL')) {
        renderMoviesHub();
      } else if (mode === 'series' && !state.search && (!state.category || state.category === 'ALL')) {
        renderSeriesHub();
      } else {
        applyFilterAndRender(elements.searchInput.value);
      }
      if (typeof state.scrollY === 'number' && state.scrollY > 0) {
        window.setTimeout(() => {
          window.scrollTo({ top: state.scrollY, behavior: 'instant' });
        }, 40);
      }
    }

    async function openMoviePage(groupOrMovie) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      contentPageReturnState = {
        mode: currentMode,
        watched: isWatchedView,
        favorites: isFavoritesView,
        category: elements.categorySelect?.value || 'ALL',
        search: elements.searchInput?.value || '',
        scrollY: window.scrollY || document.documentElement.scrollTop || 0
      };
      showContentPageShell('movie', groupOrMovie);

      elements.contentSeriesPanel.hidden = true;
      elements.contentMoviePanel.hidden = false;
      elements.contentMovieVersions.innerHTML = '';

      const rawVersions = groupOrMovie?.versions?.length > 0 ? groupOrMovie.versions : [{
        item: groupOrMovie,
        versionInfo: detectMovieVersion(groupOrMovie),
        streamId: groupOrMovie?.stream_id,
        ext: groupOrMovie?.container_extension || null
      }];
      const versions = getMovieAllPlayableVersions(groupOrMovie, rawVersions);

      // Garante que preferredVer nunca é null — mesmo que versions esteja vazio (situação anormal)
      const preferredVer = pickPreferredMovieVersion(versions) || versions[0] || {
        item: groupOrMovie,
        versionInfo: { type: 'dublado', label: 'Dublado', badge: 'DUB', icon: '▶', desc: 'Clique para assistir' },
        streamId: groupOrMovie?.stream_id || groupOrMovie?.primaryItem?.stream_id,
        ext: groupOrMovie?.container_extension || null
      };

      const canHevc = isHevcSupported();
      const hasDublado = versions.some(v => v.versionInfo?.type === 'dublado' || (canHevc && v.versionInfo?.type === '4k_dub'));
      const dubVer = canHevc
        ? (versions.find(v => v.versionInfo?.type === '4k_dub') || versions.find(v => v.versionInfo?.type === 'dublado'))
        : (versions.find(v => v.versionInfo?.type === 'dublado') || versions.find(v => v.versionInfo?.type === '4k_dub'));
      const displayVer = preferredVer || (hasDublado && dubVer ? dubVer : versions[0]);

      const primaryStreamId = groupOrMovie?.stream_id || groupOrMovie?.primaryItem?.stream_id || versions[0]?.streamId;
      if (primaryStreamId) setRouteHash('#/filme/' + primaryStreamId, true, { page: 'content', type: 'movie', id: primaryStreamId });
      document.title = (groupOrMovie?.name || groupOrMovie?.title || 'Filme') + ' - EPlay';
      void enrichMoviePageMetadata(groupOrMovie, primaryStreamId);

      // Botão Principal de Reprodução Direta
      const primaryBtn = document.createElement('button');
      primaryBtn.type = 'button';
      primaryBtn.className = 'eplay-version-card is-primary';
      primaryBtn.style.cssText = 'background: linear-gradient(135deg, rgba(79, 195, 247, 0.22), rgba(33, 150, 243, 0.12)); border: 2px solid #4fc3f7; transform: scale(1.005); width: 100%;';
      const progressStreamId = preferredVer.isHybrid ? preferredVer.videoVersion.streamId : preferredVer.streamId;
      const progress = getVodProgress('movie', progressStreamId);
      const canResume = progress && progress.position > VOD_PROGRESS_MIN_SECONDS && progress.duration > 0 && progress.position < progress.duration * VOD_PROGRESS_COMPLETE_PERCENT;
      const actionText = canResume
        ? '↻ Retomar • ' + formatResumeTime(progress.position)
        : '▶ Assistir Filme';
      const progressText = canResume
        ? 'Você parou em ' + formatResumeTime(progress.position) + ' • restam ' + formatResumeTime(Math.max(0, progress.duration - progress.position))
        : (hasDublado ? 'Versão Dublada • Alterne para legendado ou 4K no player' : (displayVer.versionInfo?.desc || 'Clique para assistir'));

      const badgeText = hasDublado ? 'DUBLADO' : (displayVer.versionInfo?.badge || 'PADRÃO');

      primaryBtn.innerHTML =
        '<span class="eplay-version-icon" style="font-size: 26px;">' + escapeHtml(displayVer.versionInfo?.icon || '▶') + '</span>' +
        '<span class="eplay-version-copy">' +
          '<strong style="color: #4fc3f7; font-size: 15px;">' + escapeHtml(displayVer.versionInfo?.label || 'Assistir Filme') + ' <span style="font-size: 10px; background: rgba(79,195,247,0.25); color: #4fc3f7; padding: 2px 7px; border-radius: 4px; font-weight: 700; margin-left: 6px;">' + escapeHtml(badgeText) + '</span></strong>' +
          '<small>' + escapeHtml(progressText) + '</small>' +
        '</span>' +
        '<span class="eplay-version-action" style="font-weight: 800; font-size: 14px; color: #4fc3f7;">' + escapeHtml(actionText) + '</span>';
      primaryBtn.addEventListener('click', () => playMovieVersion(groupOrMovie, preferredVer, versions));
      elements.contentMovieVersions.appendChild(primaryBtn);
    }

    async function openSeriesPage(seriesGroupOrItem) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      void resolveSeriesImdbId(seriesGroupOrItem);
      contentPageReturnState = {
        mode: currentMode,
        watched: isWatchedView,
        favorites: isFavoritesView,
        category: elements.categorySelect?.value || 'ALL',
        search: elements.searchInput?.value || '',
        scrollY: window.scrollY || document.documentElement.scrollTop || 0
      };
      currentContentPageType = 'series';
      currentSeriesGroup = seriesGroupOrItem;
      const primarySeriesId = seriesGroupOrItem?.series_id || (seriesGroupOrItem?.versions && seriesGroupOrItem.versions[0]?.seriesId);
      if (primarySeriesId) setRouteHash('#/series/' + primarySeriesId, true, { page: 'content', type: 'series', id: primarySeriesId });
      document.title = (seriesGroupOrItem?.name || seriesGroupOrItem?.title || 'Série') + ' - EPlay';
      showContentPageShell('series', seriesGroupOrItem);
      void enrichSeriesPageMetadata(seriesGroupOrItem);

      elements.contentMoviePanel.hidden = true;
      elements.contentSeriesPanel.hidden = false;
      elements.contentSeriesHeading.textContent = seriesGroupOrItem?.name || seriesGroupOrItem?.title || 'Série';
      elements.contentEpisodesList.innerHTML = '<div class="eplay-page-loading"><div class="spinner"></div>Carregando episódios...</div>';

      const versions = seriesGroupOrItem?.versions || [{
        item: seriesGroupOrItem,
        versionInfo: detectSeriesVersion(seriesGroupOrItem),
        seriesId: seriesGroupOrItem?.series_id
      }];
      const hasDublado = versions.some(v => v.versionInfo?.type === 'dublado');
      const pageVersion = hasDublado ? (versions.find(v => v.versionInfo?.type === 'dublado') || versions[0]) : versions[0];
      setupSeriesVersionSwitcher(versions);
      await loadSeriesVersion(pageVersion);
    }

    async function openSeriesModal(seriesGroupOrItem) {
      currentSeriesGroup = seriesGroupOrItem;
      const title = seriesGroupOrItem.name || seriesGroupOrItem.title || 'Série';
      elements.seriesModalTitle.textContent = title;
      elements.seriesTitle.textContent = title;
      elements.seriesPoster.src = seriesGroupOrItem.cover || '';
      elements.seriesRating.textContent = `★ ${Number(seriesGroupOrItem.rating || 0).toFixed(1)}`;
      elements.seriesGenre.textContent = seriesGroupOrItem.genre || 'Série';
      elements.seriesYear.textContent = seriesGroupOrItem.releaseDate ? seriesGroupOrItem.releaseDate.substring(0, 4) : '';
      elements.seriesPlot.textContent = seriesGroupOrItem.plot || 'Sinopse não disponível.';

      const versions = seriesGroupOrItem.versions || [{
        item: seriesGroupOrItem,
        versionInfo: detectSeriesVersion(seriesGroupOrItem),
        seriesId: seriesGroupOrItem.series_id
      }];

      const hasDublado = versions.some(v => v.versionInfo?.type === 'dublado');
      const pageVersion = hasDublado ? (versions.find(v => v.versionInfo?.type === 'dublado') || versions[0]) : versions[0];
      setupSeriesVersionSwitcher(versions);

      elements.seriesModal.style.display = 'flex';
      await loadSeriesVersion(pageVersion);
    }

    function getSeriesVersionSwitcherElement() {
      return elements.contentSeriesVersionSwitcher || elements.seriesVersionSwitcher;
    }

    function getSeriesSeasonSelectElement() {
      return elements.contentSeasonSelect || elements.seasonSelect;
    }

    function getSeriesEpisodesElement() {
      return elements.contentEpisodesList || elements.episodesList;
    }

    function setupSeriesVersionSwitcher(versions) {
      const switcher = getSeriesVersionSwitcherElement();
      if (!switcher) return;
      // Todas as versões são unificadas e a alternância entre Dublado e Legendado
      // é feita diretamente dentro do player, mantendo a página de séries limpa e leve.
      switcher.style.display = 'none';
      switcher.innerHTML = '';
    }

    function renderSeriesVersionButtons(versions, activeVersion) {
      const switcher = getSeriesVersionSwitcherElement();
      if (!switcher) return;
      switcher.style.display = 'none';
      switcher.innerHTML = '';
    }

    let _seriesVersionLoadToken = 0;
    async function loadSeriesVersion(versionObj, preferredSeason = null) {
      // Se o usuário trocar de versão (dublado/legendado) de novo antes desta responder,
      // a resposta mais antiga é ignorada para não sobrescrever a tela com a versão errada
      const myVersionToken = ++_seriesVersionLoadToken;
      currentActiveSeriesVersion = versionObj;

      const seriesEpisodesList = getSeriesEpisodesElement();
      const seriesSeasonSelect = getSeriesSeasonSelectElement();
      seriesEpisodesList.innerHTML = `<div class="eplay-page-loading"><div class="spinner"></div>Carregando episódios (${escapeHtml(versionObj.versionInfo.label)})...</div>`;
      seriesSeasonSelect.innerHTML = '<option>Carregando...</option>';

      try {
        const data = await getOrFetchSeriesInfo(versionObj.seriesId);
        if (myVersionToken !== _seriesVersionLoadToken) return;
        currentSeriesData = data;
        if (currentSeriesGroup && data?.info) {
          let updated = false;
          const sCast = data.info.cast || data.info.actors;
          const sDirector = data.info.director;
          if (sCast && !currentSeriesGroup.cast) {
            currentSeriesGroup.cast = sCast;
            updated = true;
          }
          if (sDirector && !currentSeriesGroup.director) {
            currentSeriesGroup.director = sDirector;
            updated = true;
          }
          if (data.info.plot && (!currentSeriesGroup.plot || currentSeriesGroup.plot === 'Sinopse não disponível.')) {
            currentSeriesGroup.plot = data.info.plot;
            if (contentPageOpen && currentContentPageItem === currentSeriesGroup && elements.contentPagePlot) {
              elements.contentPagePlot.textContent = data.info.plot;
            }
          }
          if (updated) {
            currentSeriesGroup._searchableText = null;
            if (contentPageOpen && currentContentPageItem === currentSeriesGroup) {
              renderContentPageDirector(currentSeriesGroup);
              renderContentPageCast(currentSeriesGroup);
            }
          }
        }

        const episodesBySeason = currentSeriesData.episodes || {};
        const seasons = Object.keys(episodesBySeason).sort((a, b) => Number(a) - Number(b));

        seriesSeasonSelect.innerHTML = '';
        if (seasons.length === 0) {
          if (myVersionToken !== _seriesVersionLoadToken) return;
          seriesEpisodesList.innerHTML = '<div class="eplay-page-empty">Nenhum episódio cadastrado nesta versão.</div>';
          return;
        }

        seasons.forEach(seasonNum => {
          const opt = document.createElement('option');
          opt.value = seasonNum;
          opt.textContent = `Temporada ${seasonNum} (${episodesBySeason[seasonNum].length} ep)`;
          seriesSeasonSelect.appendChild(opt);
        });

        // Mantém a temporada que o usuário já estava assistindo ou a primeira
        const targetSeason = (preferredSeason && seasons.includes(String(preferredSeason))) ? String(preferredSeason) : seasons[0];
        seriesSeasonSelect.value = targetSeason;
        renderSeasonEpisodes(targetSeason);
      } catch (err) {
        if (myVersionToken !== _seriesVersionLoadToken) return;
        seriesEpisodesList.innerHTML = `<div class="eplay-page-error">Erro ao carregar episódios: ${escapeHtml(err.message)}</div>`;
      }
    }

    function renderSeasonEpisodes(seasonNum) {
      if (!currentSeriesData || !currentSeriesData.episodes) return;
      const episodes = currentSeriesData.episodes[seasonNum] || [];
      const seriesEpisodesList = elements.contentEpisodesList || elements.episodesList;

      seriesEpisodesList.innerHTML = '';
      if (episodes.length === 0) {
        seriesEpisodesList.innerHTML = '<div class="eplay-page-empty">Nenhum episódio encontrado nesta temporada.</div>';
        return;
      }

      episodes.forEach(ep => {
        const epCard = document.createElement('div');
        epCard.className = 'episode-card';

        const ext = ep.container_extension || 'mp4';
        const epUrl = `${CONFIG.server}/series/${CONFIG.user}/${CONFIG.pass}/${ep.id}.${ext}`;
        const epTitle = ep.title || `Episódio ${ep.episode_num}`;
        const thumb = (ep.info && ep.info.movie_image) ? ep.info.movie_image : '';
        const duration = (ep.info && ep.info.duration) ? ep.info.duration : '';
        const plot = (ep.info && ep.info.plot) ? ep.info.plot : 'Sem sinopse.';

        const isBroken = isStreamMarkedBroken(ep.id);
        const playbackState = getEpisodePlaybackState(ep);
        const statusBadge = playbackState.status === 'watched'
          ? '<span class="episode-watch-state is-watched">✓ ASSISTIDO</span>'
          : playbackState.status === 'resume'
            ? '<span class="episode-watch-state is-resume">↻ RETOMAR</span>'
            : '';
        const brokenBadge = isBroken
          ? `<span style="background: rgba(229, 9, 20, 0.2); color: #ff6b6b; border: 1px solid rgba(229, 9, 20, 0.4); padding: 2px 7px; border-radius: 4px; font-size: 10px; font-weight: 600; margin-left: 8px;">⚠️ Indisponível no Servidor</span>`
          : '';
        const watchBtnText = isBroken
          ? '⚠️ Tentar Assistir'
          : playbackState.status === 'watched'
            ? '✓ ASSISTIDO'
            : playbackState.status === 'resume'
              ? '↻ RETOMAR'
              : '▶ Assistir';
        const watchBtnClass = playbackState.status === 'watched' && !isBroken
          ? 'btn btn-secondary episode-watched-btn'
          : playbackState.status === 'resume' && !isBroken
            ? 'btn btn-primary episode-resume-btn'
            : isBroken ? 'btn btn-secondary' : 'btn btn-primary';
        const remainingText = playbackState.status === 'watched'
          ? '✓ Episódio concluído'
          : playbackState.remaining > 0
            ? '⏳ Restam ' + formatResumeTime(playbackState.remaining)
            : (duration ? '⏱ Duração: ' + duration : '');

        epCard.innerHTML = `
          <div class="episode-card-main">
            ${thumb ? `<img class="episode-thumb" src="${escapeHtml(thumb)}" alt="Episódio" data-hide-on-error loading="lazy">` : ''}
            <div class="episode-info">
              <div class="episode-title">
                <span>Episódio ${ep.episode_num}: ${escapeHtml(epTitle)}</span>
                ${statusBadge}
                ${brokenBadge}
              </div>
              <div class="episode-duration-line">${escapeHtml(remainingText)}</div>
              <div class="episode-plot">${escapeHtml(plot)}</div>
            </div>
          </div>
          <div class="episode-actions">
            <button class="${watchBtnClass}" style="padding: 7px 18px; font-size: 12px; font-weight: 700;">${watchBtnText}</button>
            <a class="btn btn-secondary" style="padding: 7px 16px; font-size: 12px;" href="${escapeHtml(epUrl)}" target="_blank" download data-adshield-allow>📥 Baixar</a>
          </div>
        `;

        epCard.querySelector('button').addEventListener('click', (e) => {
          e.stopPropagation();
          playSeriesEpisode(ep, seasonNum);
        });

        seriesEpisodesList.appendChild(epCard);
      });
    }

    async function playSeriesEpisode(ep, seasonNum) {
      if (!window.AndPlayAccount?.isSignedIn?.()) {
        showLoginScreen();
        return;
      }
      const openedFromContentPage = contentPageOpen && currentContentPageType === 'series';
      const watchedId = getSeriesWatchedId(currentSeriesGroup);
      if (watchedId) saveWatchedId('series', watchedId);

      const prefAudio = (typeof getPreferredAudioPreference === 'function') ? getPreferredAudioPreference() : 'dub';
      const legVer = (currentSeriesGroup?.versions || []).find(v => v.versionInfo?.type === 'legendado');
      const dubVer = (currentSeriesGroup?.versions || []).find(v => v.versionInfo?.type === 'dublado');

      // Se o usuário prefere Legendado e existe versão legendada para a série, busca o episódio correspondente
      if (prefAudio === 'leg' && legVer && currentActiveSeriesVersion?.versionInfo?.type !== 'legendado') {
        try {
          const legData = await getOrFetchSeriesInfo(legVer.seriesId);
          const seasonEps = (legData?.episodes && legData.episodes[seasonNum]) || [];
          const matchedEp = seasonEps.find(e => Number(e.episode_num) === Number(ep.episode_num));
          if (matchedEp) {
            currentActiveSeriesVersion = legVer;
            ep = matchedEp;
          }
        } catch (_) {}
      } else if (prefAudio === 'dub' && dubVer && currentActiveSeriesVersion?.versionInfo?.type !== 'dublado') {
        try {
          const dubData = await getOrFetchSeriesInfo(dubVer.seriesId);
          const seasonEps = (dubData?.episodes && dubData.episodes[seasonNum]) || [];
          const matchedEp = seasonEps.find(e => Number(e.episode_num) === Number(ep.episode_num));
          if (matchedEp) {
            currentActiveSeriesVersion = dubVer;
            ep = matchedEp;
          }
        } catch (_) {}
      }

      const activeVer = currentActiveSeriesVersion || (dubVer || legVer || currentSeriesGroup?.versions?.[0]);
      const ext = ep.container_extension || 'mp4';
      const epUrl = `${CONFIG.server}/series/${CONFIG.user}/${CONFIG.pass}/${ep.id}.${ext}`;
      const epTitle = ep.title || `Episódio ${ep.episode_num}`;
      const sInfo = currentSeriesData?.info || {};
      const sName = currentSeriesGroup?.name || sInfo.name || 'Série';
      const versionLabel = (currentSeriesGroup?.versions && currentSeriesGroup.versions.length > 1 && activeVer)
        ? ` (${activeVer.versionInfo.label})`
        : '';

      const epPlot = ep.info?.plot || sInfo.plot || '';
      const epDuration = ep.info?.duration || ep.info?.duration_secs || sInfo.episode_run_time || '';
      const sPoster = ep.info?.movie_image || currentSeriesGroup?.poster || sInfo.cover || '';

      const currentActiveData = seriesDataCache.get(String(activeVer?.seriesId)) || currentSeriesData;
      const seasonEpisodes = (currentActiveData?.episodes && currentActiveData.episodes[seasonNum]) || (currentSeriesData?.episodes?.[seasonNum] || []);
      const currentEpIdx = seasonEpisodes.findIndex(e => String(e.id) === String(ep.id));
      const hasPrevious = seasonEpisodes.some(e => Number(e.episode_num) === Number(ep.episode_num) - 1) || currentEpIdx > 0;
      const hasNext = seasonEpisodes.some(e => Number(e.episode_num) === Number(ep.episode_num) + 1) || (currentEpIdx >= 0 && currentEpIdx < seasonEpisodes.length - 1);

      window.EPlaySeriesNavigation = {
        seasonNum: Number(seasonNum) || 0,
        episodeNum: Number(ep.episode_num) || 0,
        hasPrevious,
        hasNext,
        next: () => {
          const activeD = seriesDataCache.get(String(currentActiveSeriesVersion?.seriesId)) || currentSeriesData;
          const list = activeD?.episodes?.[seasonNum] || [];
          let nextEp = list.find(e => Number(e.episode_num) === Number(ep.episode_num) + 1);
          if (!nextEp) {
            const idx = list.findIndex(e => String(e.id) === String(ep.id));
            if (idx >= 0 && idx < list.length - 1) nextEp = list[idx + 1];
          }
          if (nextEp) playSeriesEpisode(nextEp, seasonNum);
          return !!nextEp;
        },
        previous: () => {
          const activeD = seriesDataCache.get(String(currentActiveSeriesVersion?.seriesId)) || currentSeriesData;
          const list = activeD?.episodes?.[seasonNum] || [];
          let previousEp = list.find(e => Number(e.episode_num) === Number(ep.episode_num) - 1);
          if (!previousEp) {
            const idx = list.findIndex(e => String(e.id) === String(ep.id));
            if (idx > 0) previousEp = list[idx - 1];
          }
          if (previousEp) playSeriesEpisode(previousEp, seasonNum);
          return !!previousEp;
        }
      };
      window.dispatchEvent(new CustomEvent('eplay:series-context'));

      const startPlayback = (startPosition) => {
        saveWatchedId('series', watchedId);
        openPlayer(`${sName} - ${epTitle}${versionLabel}`, epUrl, 'series', {
          title: `${sName} - ${epTitle}`,
          seriesName: sName,
          episodeTitle: epTitle,
          season: seasonNum,
          seasonNum: seasonNum,
          episodeNum: ep.episode_num,
          year: sInfo.release_date ? String(sInfo.release_date).substring(0, 4) : (sInfo.year || ''),
          poster: sPoster,
          plot: epPlot,
          cast: sInfo.cast || sInfo.actors || '',
          director: sInfo.director || '',
          genre: sInfo.genre || '',
          rating: sInfo.rating || sInfo.rating_5based || '',
          duration: epDuration,
          seriesId: activeVer?.seriesId || currentSeriesGroup?.series_id || sInfo.series_id || '',
          imdbId: sInfo.imdb_id || sInfo.imdbId || currentSeriesGroup?.imdbId || currentSeriesGroup?.imdb_id || readSeriesImdbCache()[cleanTitleKey(sName)] || '',
          imdb_id: sInfo.imdb_id || sInfo.imdbId || currentSeriesGroup?.imdb_id || currentSeriesGroup?.imdbId || readSeriesImdbCache()[cleanTitleKey(sName)] || '',
          malId: sInfo.mal_id || sInfo.malId || currentSeriesGroup?.malId || currentSeriesGroup?.mal_id || '',
          mal_id: sInfo.mal_id || sInfo.malId || currentSeriesGroup?.mal_id || currentSeriesGroup?.malId || '',
          selectedVersion: activeVer,
          versionInfo: activeVer ? activeVer.versionInfo : null,
          fromContentPage: openedFromContentPage,
          streamId: ep.id,
          stream_id: ep.id,
          ep
        }, startPosition);

        setupPlayerSeriesVersionSwitcher(currentSeriesGroup, activeVer, seasonNum, ep.episode_num);
        const sId = currentSeriesGroup?.series_id || (currentSeriesGroup?.versions && currentSeriesGroup.versions[0]?.seriesId) || activeVer?.seriesId;
        if (sId && ep?.episode_num) {
          setRouteHash(`#/series/${sId}/temporada/${seasonNum}/episodio/${ep.episode_num}`);
        }
        document.title = `${sName} - T${seasonNum} E${ep.episode_num} - EPlay`;
      };

      startVodWithResume('series', ep.id, `${sName} - ${epTitle}${versionLabel}`, startPlayback);
    }

    function closeSeriesModal() {
      elements.seriesModal.style.display = 'none';
      currentSeriesData = null;
      currentSeriesGroup = null;
      currentActiveSeriesVersion = null;
    }

    function escapeHtml(str) {
      // Ordem importa: & primeiro, senão escapamos as entidades que acabamos de criar.
      return String(str || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    }

    // Alguns logos antigos apontam para reidosembeds.online, que pode ficar
    // indisponível por DNS/ISP. Para os canais mais comuns usamos uma origem pública
    // estável; os demais passam pelo cache/proxy e, por fim, recebem fallback visual.
    // Logos locais para os canais mais exibidos. O APK nativo carrega ch.logo
    // diretamente; na web usamos cópias locais para os logos conhecidos e
    // preservamos a URL original para os demais canais.
    const TV_LOGO_FALLBACKS = {
      globo: './assets/logos/tv/globo.png',
      sbt: './assets/logos/tv/sbt.png',
      record: './assets/logos/tv/record.png',
      band: './assets/logos/tv/band.png',
      sportv: './assets/logos/tv/sportv.png',
      sportv2: './assets/logos/tv/sportv2.png',
      sportv3: './assets/logos/tv/sportv3.png',
      premiere: './assets/logos/tv/premiere.png',
      espn: './assets/logos/tv/espn.png',
      cnnbrasil: './assets/logos/tv/cnnbrasil.png',
      globonews: './assets/logos/tv/globonews.png',
      gloob: './assets/logos/tv/gloob.png',
      cartoonnetwork: './assets/logos/tv/cartoonnetwork.png',
      discoverykids: './assets/logos/tv/discoverykids.png',
      futura: './assets/logos/tv/futura.png',
      hbo: './assets/logos/tv/hbo.png',
      hbo2: './assets/logos/tv/hbo2.png',
      warner: './assets/logos/tv/warner.png',
      tntseries: './assets/logos/tv/tntseries.png',
      tntsports: './assets/logos/tv/tntsports.png',
      tvbrasil: './assets/logos/tv/tvbrasil.png',
      multishow: './assets/logos/tv/multishow.png',
      recordnews: './assets/logos/tv/recordnews.png',
      redetv: './assets/logos/tv/redetv.png',
      space: './assets/logos/tv/space.png',
      tnt: './assets/logos/tv/tnt.png'
    };

    const TV_TEAM_LOGO_FALLBACKS = {
      flamengo: 'https://images.fotmob.com/image_resources/logo/teamlogo/9770.png',
      palmeiras: 'https://images.fotmob.com/image_resources/logo/teamlogo/10283.png',
      corinthians: 'https://images.fotmob.com/image_resources/logo/teamlogo/9808.png',
      'sao-paulo': 'https://images.fotmob.com/image_resources/logo/teamlogo/10277.png',
      gremio: 'https://images.fotmob.com/image_resources/logo/teamlogo/9769.png',
      'real-madrid': 'https://images.fotmob.com/image_resources/logo/teamlogo/8633.png',
      barcelona: 'https://images.fotmob.com/image_resources/logo/teamlogo/8634.png',
      'manchester-city': 'https://images.fotmob.com/image_resources/logo/teamlogo/8456.png',
      sunderland: 'https://images.fotmob.com/image_resources/logo/teamlogo/8472.png'
    };

    function getTvLogoFallbackByKey(rawUrl) {
      try {
        const parsed = new URL(String(rawUrl || ''), window.location.href);
        const fileKey = (parsed.pathname.split('/').pop() || '').replace(/\.[^.]+$/, '').toLowerCase();
        if (TV_LOGO_FALLBACKS[fileKey]) return TV_LOGO_FALLBACKS[fileKey];
        const matched = Object.keys(TV_LOGO_FALLBACKS).find(k => fileKey.startsWith(k));
        return matched ? TV_LOGO_FALLBACKS[matched] : '';
      } catch (e) { return ''; }
    }

    // Mantém o comportamento do APK: URL original quando não existe cópia local.
    function getTvLogoUrl(rawUrl, width = 160, height = 100) {
      const url = String(rawUrl || '').trim();
      if (!url || /^data:/i.test(url)) return url;
      const knownFallback = getTvLogoFallbackByKey(url);
      return knownFallback || url;
    }

    function normalizeTeamLogoKey(name) {
      return String(name || '')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/&/g, 'e')
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
    }

    function getTvTeamLogoUrl(teamName, rawUrl) {
      const key = normalizeTeamLogoKey(teamName);
      return TV_TEAM_LOGO_FALLBACKS[key] || getTvLogoUrl(rawUrl, 90, 90) || '';
    }

    function getTvLogoFallback(name) {
      const clean = String(name || 'TV').replace(/[^A-Za-zÀ-ÿ0-9 ]/g, ' ').trim();
      const parts = clean.split(/\\s+/).filter(Boolean);
      const initials = (parts.length >= 2 ? parts[0][0] + parts[1][0] : clean.slice(0, 3)).toUpperCase() || 'TV';
      const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 90"><rect width="160" height="90" rx="12" fill="#1A2035"/><text x="80" y="55" text-anchor="middle" font-family="Arial,sans-serif" font-size="34" font-weight="800" fill="#FFC107">' + initials + '</text></svg>';
      return 'data:image/svg+xml;charset=UTF-8,' + encodeURIComponent(svg);
    }


    // ==================== SCRIPT TV CABO CONTROLLER ====================
    (function () {
      // Verificação do modo TV (acessado estritamente via sublink /tvmode ou #/tvmode, sem redirecionamento automático)
      const isTvBootMode = window.location.pathname.endsWith('/tvmode') || window.location.pathname.endsWith('/tvmode/') || window.location.pathname.includes('/tvmode') || window.location.hash.includes('tvmode') || window.location.search.includes('tvmode');

      // Configura estado inicial de modo TV
      let isTvMode = isTvBootMode;

      if (isTvBootMode) {
        console.log('[EPlay TV] Iniciando modo TV a Cabo / Receptor Smart TV');
        document.body.classList.add('tv-mode');
      }

      window.addEventListener('hashchange', () => {
        const wantsTv = window.location.hash.includes('tvmode') || window.location.pathname.includes('/tvmode') || window.location.search.includes('tvmode');
        if (wantsTv && !isTvMode) {
          if (window.enterTvMode) window.enterTvMode();
        } else if (!wantsTv && isTvMode) {
          if (window.exitTvMode) window.exitTvMode();
        }
      });

      // Variáveis de Estado do Receptor TV
      let tvChannels = [];
      let tvCurrentIdx = 0;
      let tvCurrentMode = 'central'; // 'central' | 'fullscreen' | 'vod'
      let tvOsdTimer = null;
      let tvClockTimer = null;
      let tvDrawerAutoCloseTimer = null;
      const tvPreloadedPosterUrls = new Set();
      let tvVodList = [];
      let tvVodMode = 'movies';

      // Elementos DOM da TV
      const tvApp = document.getElementById('tvCableApp');
      const tvCentral = document.getElementById('tvCentralScreen');
      const tvFullscreen = document.getElementById('tvFullscreenView');
      const tvVod = document.getElementById('tvVodView');
      const tvPlayerBox = document.getElementById('tvPlayerBox');
      const tvVideo = document.getElementById('tvVideoPlayer');
      const tvEmbed = document.getElementById('tvEmbedPlayer');
      const tvOsd = document.getElementById('tvOsdBanner');
      const tvDrawer = document.getElementById('tvEpgDrawer');
      tvVod?.addEventListener('click', event => {
        const button = event.target.closest('[data-open-tv-vod]');
        if (button) openTvVodExplorer(button.dataset.openTvVod);
      });

      window.addEventListener('eplay:tv-epg-updated', () => {
        if (!isTvMode || !tvApp || tvApp.style.display === 'none') return;
        tvChannels.forEach(ch => {
          const epg = window.EPlayTvEpg?.getSchedule?.(ch);
          if (!epg) return;
          ch.nowTitle = epg.nowTitle || ch.nowTitle;
          ch.nowProgress = Number(epg.progress) || 0;
          ch.synopsis = epg.synopsis || ch.synopsis;
          ch.nextProgrammes = epg.nextTitle ? [{ t: epg.nextTitle, s: epg.nextStart }] : ch.nextProgrammes;
        });
        renderTvFeaturedChannels();
        if (tvCurrentMode === 'fullscreen' && tvChannels[tvCurrentIdx]) {
          updateTvOsd(tvChannels[tvCurrentIdx], tvCurrentIdx, false);
        }
      });

      // Relógio
      function updateTvClock() {
        if (!tvApp || tvApp.style.display === 'none') return;
        const clockEl = document.getElementById('tvClock');
        const dateEl = document.getElementById('tvDate');
        const osdClock = document.getElementById('tvOsdClock');
        const osdDate = document.getElementById('tvOsdDate');
        const now = new Date();
        const timeStr = now.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
        const dateStr = now.toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: 'short' });
        if (clockEl) clockEl.textContent = timeStr;
        if (dateEl) dateEl.textContent = dateStr;
        if (osdClock) osdClock.textContent = timeStr;
        if (osdDate) osdDate.textContent = dateStr.toUpperCase();
      }

      // O relógio só fica ativo enquanto o modo TV estiver aberto.
      function startTvClock() {
        if (tvClockTimer !== null) return;
        updateTvClock();
        tvClockTimer = setInterval(updateTvClock, 1000);
      }

      function stopTvClock() {
        if (tvClockTimer === null) return;
        clearInterval(tvClockTimer);
        tvClockTimer = null;
      }

      function stopTvBackgroundWork() {
        if (window._tvFocusKeepAlive) {
          clearInterval(window._tvFocusKeepAlive);
          window._tvFocusKeepAlive = null;
        }
        if (window._tvEpgRefreshTimer) {
          clearInterval(window._tvEpgRefreshTimer);
          window._tvEpgRefreshTimer = null;
        }
        if (tvOsdTimer) {
          clearTimeout(tvOsdTimer);
          tvOsdTimer = null;
        }
        if (tvDrawerAutoCloseTimer) {
          clearTimeout(tvDrawerAutoCloseTimer);
          tvDrawerAutoCloseTimer = null;
        }
        if (tvZapDebounceTimer) {
          clearTimeout(tvZapDebounceTimer);
          tvZapDebounceTimer = null;
        }
        if (tvVodProgressSaveTimer) {
          clearInterval(tvVodProgressSaveTimer);
          tvVodProgressSaveTimer = null;
        }
      }

      // Inicializa canais e eventos
      window.initTvCableBox = async function () {
        if (!window.AndPlayAccount?.isSignedIn?.()) {
          if (typeof window.showLoginScreen === 'function') window.showLoginScreen();
          return;
        }
        tvApp.style.display = 'flex';
        window.EPlayTvEpg?.activate();
        startTvClock();

        // Carrega canais sob demanda (cacheado após a primeira vez nesta sessão)
        tvChannels = await getLiveChannelsData();

        // Carrega último canal assistido
        try {
          const saved = localStorage.getItem('andplay_last_tv_ch');
          if (saved !== null && !isNaN(parseInt(saved))) {
            tvCurrentIdx = Math.max(0, Math.min(tvChannels.length - 1, parseInt(saved)));
          }
        } catch (e) { }

        // Popula trilhos da Central
        renderTvFeaturedChannels();
        loadTvSportsRail();
        loadTvMoviesRail();
        loadTvSeriesRail();
        setupTvDrawerCategories();

        // Sintoniza primeiro canal no PiP
        if (tvChannels.length > 0) {
          tvTuneChannel(tvCurrentIdx, false);
        }

        // Catálogos VOD são carregados somente quando a respectiva área é aberta.
        // O modo TV não faz pré-carregamento de Filmes/Séries em segundo plano.

        // Registra listener de rolagem e redimensionamento para sincronizar o PiP perfeitamente
        if (tvCentral && !tvCentral._hasScrollSync) {
          tvCentral._hasScrollSync = true;
          // Scroll listener desativado para 60 FPS
          window.addEventListener('resize', syncTvPlayerBoxPosition, { passive: true });
        }

        setTimeout(syncTvPlayerBoxPosition, 80);
        setTimeout(syncTvPlayerBoxPosition, 300);

        // Foca no PiP ao iniciar
        setTimeout(() => {
          const pipCard = document.getElementById('tvPipCard');
          if (pipCard) pipCard.focus();
        }, 200);

        // Recupera o foco do teclado quando o iframe o rouba (cross-origin iframes capturam eventos keydown
        // sem disparar um evento de foco detectável no documento pai, por isso o polling continua necessário aqui).
        // Roda a cada 400ms e, se o modo for fullscreen, força o foco de volta ao overlay #tvKeyCapture.
        // Elemento cacheado fora do loop para evitar um getElementById a cada 400ms pelo resto da sessão.
        if (!window._tvFocusKeepAlive) {
          const kcCached = document.getElementById('tvKeyCapture');
          window._tvFocusKeepAlive = setInterval(() => {
            if (tvCurrentMode !== 'fullscreen') return;
            // Não roubar o foco enquanto a gaveta EPG estiver aberta (ela precisa do foco para navegação)
            if (tvDrawer && !tvDrawer.classList.contains('closed')) return;
            const kc = kcCached || document.getElementById('tvKeyCapture');
            if (!kc) return;
            const ae = document.activeElement;
            // Se o foco não está no overlay, rouba de volta
            if (ae !== kc) {
              kc.focus({ preventScroll: true });
            }
          }, 400);
        }

        // Atualização periódica do EPG em tempo real a cada 60 segundos
        if (!window._tvEpgRefreshTimer) {
          window._tvEpgRefreshTimer = setInterval(() => {
            if (tvPlayingType === 'channel' && tvChannels[tvCurrentIdx]) {
              const ch = tvChannels[tvCurrentIdx];
              const epg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(ch) : null;
              if (epg) {
                const pipEpg = document.getElementById('tvPipEpg');
                if (pipEpg) pipEpg.textContent = `🔴 No Ar: ${epg.nowTitle}`;
                if (tvCurrentMode === 'fullscreen' && tvOsd && !tvOsd.classList.contains('hidden')) {
                  updateTvOsd(ch, tvCurrentIdx, false);
                }
              }
            }
          }, 60000);
        }

        // Dispara o Overlay ao interagir com mouse/touch na tela cheia
        if (!window._tvOsdActivityWired) {
          window._tvOsdActivityWired = true;
          const onUserActivity = () => {
            releaseTvAutoplayMute();
            if (tvCurrentMode === 'fullscreen') {
              triggerTvOsd(5000);
              // Também reinicia a contagem de inatividade da gaveta EPG, se estiver aberta
              if (tvDrawer && !tvDrawer.classList.contains('closed')) {
                triggerTvDrawerAutoClose(8000);
              }
            }
          };
          window.addEventListener('mousemove', onUserActivity, { passive: true });
          window.addEventListener('click', onUserActivity, { passive: true });
          window.addEventListener('pointerdown', onUserActivity, { passive: true });
          window.addEventListener('touchstart', onUserActivity, { passive: true });
          window.addEventListener('keydown', onUserActivity, { passive: true });
        }
      };

      // ============================================================
      // MOTOR DE REPRODUÇÃO E ZAPPING UNIFICADO PARA MODO TV
      // Suporta Canais Ao Vivo (com zapping e debouncer de 3s),
      // Eventos Esportivos Ao Vivo, Filmes VOD e Séries VOD com Episódios
      // ============================================================
      let tvZapDebounceTimer = null;
      let tvPendingIdx = 0;
      let tvAutoplayMuted = false;
      let tvPlayingType = 'channel'; // 'channel' | 'event' | 'vod'
      let tvPreviousMode = 'central'; // 'central' | 'vod'
      let tvCurrentSeriesItem = null;
      let tvCurrentSeason = '1';
      let tvPlayingSeriesMeta = null; // { seriesItem, ep, seasonNum, seriesId, episodesBySeason }
      let tvVodProgressMeta = null;
      let tvVodProgressSaveTimer = null;

      // Helper para formatação de segundos (ex: 01:25:30)
      function formatTvSeconds(sec) {
        if (isNaN(sec) || sec === null || sec < 0) return '00:00';
        const h = Math.floor(sec / 3600);
        const m = Math.floor((sec % 3600) / 60);
        const s = Math.floor(sec % 60);
        if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
        return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
      }

      function saveCurrentTvVodProgress() {
        if (!tvVodProgressMeta || !tvVideo) return;
        saveVodProgress(
          tvVodProgressMeta.type,
          tvVodProgressMeta.id,
          tvVideo.currentTime,
          tvVideo.duration,
          tvVodProgressMeta.title
        );
      }

      function startTvVodProgressTracking(type, id, title) {
        if (tvVodProgressSaveTimer) clearInterval(tvVodProgressSaveTimer);
        tvVodProgressSaveTimer = null;
        tvVodProgressMeta = null;
        if (id === null || id === undefined || String(id).trim() === '' || !tvVideo) return;
        tvVodProgressMeta = { type, id: String(id), title: title || '' };
        const catalogItem = type === 'series'
          ? (tvPlayingSeriesMeta?.seriesItem || null)
          : findCatalogItemForTaste(type, id);
        const themeItem = catalogItem
          ? (type === 'series'
              ? { type: 'series', item: catalogItem, genre: catalogItem.genre || catalogItem.genre_name || '', imdbId: catalogItem.imdbId || catalogItem.imdb_id || '' }
              : { type: 'movie', item: catalogItem, primaryItem: catalogItem.primaryItem || catalogItem, genre: catalogItem.genre || catalogItem.primaryItem?.genre || catalogItem.genre_name || '', imdbId: catalogItem.imdbId || catalogItem.imdb_id || catalogItem.primaryItem?.imdbId || catalogItem.primaryItem?.imdb_id || '' })
          : null;
        startWatchTimeTracking(
          type,
          id,
          themeItem,
          () => Boolean(tvPlayingType === 'vod' && tvVideo && !tvVideo.paused && !tvVideo.ended)
        );
        tvVodProgressSaveTimer = setInterval(() => {
          if (tvPlayingType === 'vod') saveCurrentTvVodProgress();
        }, 5000);
      }

      function stopTvVodProgressTracking(save = true) {
        if (save) saveCurrentTvVodProgress();
        stopWatchTimeTracking(save);
        if (tvVodProgressSaveTimer) clearInterval(tvVodProgressSaveTimer);
        tvVodProgressSaveTimer = null;
        tvVodProgressMeta = null;
      }

      // Tenta iniciar a tag <video>. Se o navegador bloquear autoplay com áudio,
      // inicia sem som e devolve o áudio ao primeiro gesto do usuário.
      function tryTvVideoAutoplay() {
        if (!tvVideo) return;
        const playPromise = tvVideo.play();
        if (playPromise !== undefined) {
          playPromise.catch(() => {
            tvVideo.muted = true;
            tvAutoplayMuted = true;
            const mutedPromise = tvVideo.play();
            if (mutedPromise !== undefined) mutedPromise.catch(() => { });
          });
        }
      }

      // Libera o áudio após uma interação real do usuário, quando o navegador
      // exigiu autoplay silencioso para iniciar a transmissão.
      function releaseTvAutoplayMute() {
        if (!tvVideo || !tvAutoplayMuted) return;
        tvVideo.muted = false;
        tvAutoplayMuted = false;
        tryTvVideoAutoplay();
      }

      // Carrega efetivamente a transmissão no reprodutor unificado (iframe embed ou tag de vídeo)
      // fallbackIdx permite tentar automaticamente o próximo servidor em ch.fallbacks quando o atual falha.
      // startPosition é usado apenas para VOD.
      function tvLoadStream(ch, fallbackIdx = 0, startPosition = 0) {
        if (!ch) return;
        const hasExplicitFallbacks = ch.fallbacks && ch.fallbacks.length > 0;
        const resumePosition = Number.isFinite(Number(startPosition)) ? Math.max(0, Number(startPosition)) : 0;
        const fallback = hasExplicitFallbacks
          ? ch.fallbacks[fallbackIdx]
          : (fallbackIdx === 0 ? { url: 'https://rdcanais.net/' + (ch.channelSlug || ch.id), isEmbed: true } : null);

        // Todos os servidores/fallbacks foram tentados e falharam
        if (!fallback) {
          console.warn('[EPlay TV] Nenhum servidor disponível para o canal:', ch.name);
          const osdProgStatus = document.getElementById('tvOsdProgStatus');
          if (osdProgStatus) {
            osdProgStatus.textContent = '⚠ Sinal indisponível no momento. Tente novamente em instantes.';
            osdProgStatus.style.color = '#ff5252';
          }
          triggerTvOsd(6000);
          return;
        }

        if (fallback.isEmbed) {
          if (resumePosition > 0) {
            console.warn('[EPlay TV] Retomada ignorada para transmissão em embed.');
          }
          if (window._tvActiveHls) {
            window._tvActiveHls.destroy();
            window._tvActiveHls = null;
          }
          tvVideo.style.display = 'none';
          tvVideo.pause();
          tvVideo.onerror = null;
          tvEmbed.style.display = 'block';
          const baseUrl = fallback.url;
          const embedUrl = baseUrl + (baseUrl.includes('?') ? '&' : '?') + 'autoplay=1&playsinline=1&muted=1';
          tvEmbed.src = 'about:blank';
          tvEmbed.onerror = function () {
            console.warn(`[EPlay TV] Servidor ${fallbackIdx + 1} inacessível, alternando automaticamente...`);
            tvLoadStream(ch, fallbackIdx + 1, resumePosition);
          };
          requestAnimationFrame(() => { tvEmbed.src = embedUrl; });
        } else {
          tvEmbed.style.display = 'none';
          tvEmbed.src = 'about:blank';
          tvVideo.style.display = 'block';

          // Suporte a HLS .m3u8 nativo via Hls.js ou HTML5 Video
          if (fallback.url.includes('.m3u8') && typeof Hls !== 'undefined' && Hls.isSupported()) {
            if (window._tvActiveHls) {
              window._tvActiveHls.destroy();
              window._tvActiveHls = null;
            }
            tvVideo.onerror = null;
            window._tvActiveHls = new Hls({ enableWorker: true });
            if (resumePosition > 0) {
              tvVideo.addEventListener('loadedmetadata', () => {
                const dur = tvVideo.duration;
                if (Number.isFinite(dur) && dur > 0 && resumePosition < dur - 1) {
                  try { tvVideo.currentTime = Math.min(resumePosition, dur - 1); } catch (e) {}
                }
              }, { once: true });
            }
            window._tvActiveHls.loadSource(fallback.url);
            window._tvActiveHls.attachMedia(tvVideo);
            window._tvActiveHls.on(Hls.Events.MANIFEST_PARSED, () => {
              if (resumePosition > 0) {
                const dur = tvVideo.duration;
                if (Number.isFinite(dur) && dur > 0 && resumePosition < dur - 1) {
                  try { tvVideo.currentTime = Math.min(resumePosition, dur - 1); } catch (e) {}
                }
              }
              tryTvVideoAutoplay();
            });
            window._tvActiveHls.on(Hls.Events.ERROR, (event, data) => {
              if (data.fatal) {
                console.warn(`[EPlay TV] Erro fatal de HLS no servidor ${fallbackIdx + 1}:`, data.type, '- alternando automaticamente...');
                if (window._tvActiveHls) {
                  try { window._tvActiveHls.destroy(); } catch (e) { }
                  window._tvActiveHls = null;
                }
                tvLoadStream(ch, fallbackIdx + 1, resumePosition);
              }
            });
          } else {
            if (window._tvActiveHls) {
              window._tvActiveHls.destroy();
              window._tvActiveHls = null;
            }
            tvVideo.autoplay = true;
            tvVideo.playsInline = true;
            tvVideo.muted = false;
            if (resumePosition > 0) {
              tvVideo.addEventListener('loadedmetadata', () => {
                const dur = tvVideo.duration;
                if (Number.isFinite(dur) && dur > 0 && resumePosition < dur - 1) {
                  try { tvVideo.currentTime = Math.min(resumePosition, dur - 1); } catch (e) {}
                }
              }, { once: true });
            }
            tvVideo.src = fallback.url;
            tvVideo.load();
            // Único handler (atribuição direta evita empilhar listeners a cada troca de canal)
            tvVideo.onerror = function () {
              console.warn(`[EPlay TV] Erro ao carregar servidor ${fallbackIdx + 1}, alternando automaticamente...`);
              tvLoadStream(ch, fallbackIdx + 1, resumePosition);
            };

            const tryPlay = () => {
              tryTvVideoAutoplay();
            };

            const onReady = () => {
              tvVideo.removeEventListener('canplay', onReady);
              tvVideo.removeEventListener('loadeddata', onReady);
              tryPlay();
            };
            tvVideo.addEventListener('canplay', onReady, { once: true });
            tvVideo.addEventListener('loadeddata', onReady, { once: true });
            tryPlay();
          }
        }

        // Listener de progresso para filmes e séries VOD
        if (tvVideo && !tvVideo._hasTvTimeUpdate) {
          tvVideo._hasTvTimeUpdate = true;
          tvVideo.addEventListener('timeupdate', () => {
            if (tvPlayingType !== 'vod' || !tvVideo.duration) return;
            const cur = tvVideo.currentTime;
            const dur = tvVideo.duration;
            const pct = Math.round((cur / dur) * 100);
            const curStr = formatTvSeconds(cur);
            const durStr = formatTvSeconds(dur);

            const fill = document.getElementById('tvOsdProgFill');
            const status = document.getElementById('tvOsdProgStatus');
            const remaining = document.getElementById('tvOsdProgRemaining');
            const timeRange = document.getElementById('tvOsdTimeRange');

            if (fill) fill.style.width = `${pct}%`;
            if (status) {
              status.textContent = tvVideo.paused ? `⏸ Pausado (${curStr} / ${durStr})` : `▶ Reproduzindo (${curStr} / ${durStr})`;
              status.style.color = tvVideo.paused ? '#ffc107' : '#00e676';
            }
            if (remaining) remaining.textContent = `Restam ~${Math.max(0, Math.round((dur - cur) / 60))} min`;
            if (timeRange) timeRange.textContent = `${curStr} • ${durStr}`;
          });

          if (!tvVideo._hasTvEndedHandler) {
            tvVideo._hasTvEndedHandler = true;
            tvVideo.addEventListener('ended', () => {
              if (tvPlayingType !== 'vod' || !tvVodProgressMeta) return;
              clearVodProgress(tvVodProgressMeta.type, tvVodProgressMeta.id);
              stopTvVodProgressTracking(false);
            });
          }
        }

        // Mantém o foco no overlay de teclado após a troca de canal em fullscreen
        if (tvCurrentMode === 'fullscreen') {
          const kc = document.getElementById('tvKeyCapture');
          if (kc) setTimeout(() => kc.focus({ preventScroll: true }), 100);
        }
      }

      // Confirma a sintonização do canal pendente (chamado após 3s de inatividade ou ao teclar Enter)
      function startTvChannelUsageTracking(ch) {
        if (!ch) return;
        const id = String(ch.id || ch.channelSlug || ch.name || '');
        if (!id) return;
        recordLiveChannelHistory(ch);
        startWatchTimeTracking(
          'live',
          id,
          null,
          () => Boolean(
            tvPlayingType === 'channel' &&
            tvApp &&
            tvApp.style.display !== 'none' &&
            !document.hidden
          )
        );
      }

      function tvCommitPendingChannel() {
        if (tvZapDebounceTimer) {
          clearTimeout(tvZapDebounceTimer);
          tvZapDebounceTimer = null;
        }
        tvCurrentIdx = tvPendingIdx;
        const ch = tvChannels[tvCurrentIdx];
        if (!ch) return;

        try { localStorage.setItem('andplay_last_tv_ch', tvCurrentIdx); } catch (e) { }

        tvPlayingType = 'channel';
        tvPreviousMode = 'central';
        tvPlayingSeriesMeta = null;
        stopWatchTimeTracking();
        startTvChannelUsageTracking(ch);
        tvLoadStream(ch);

        const pipName = document.getElementById('tvPipName');
        const pipEpg = document.getElementById('tvPipEpg');
        const commitEpg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(ch) : null;
        const commitTitle = commitEpg ? commitEpg.nowTitle : (ch.nowTitle || ch.categoryLabel || 'Transmissão Ao Vivo HD');
        if (pipName) pipName.textContent = `${String(tvCurrentIdx + 1).padStart(3, '0')} - ${ch.name}`;
        if (pipEpg) pipEpg.textContent = `🔴 No Ar: ${commitTitle}`;

        updateTvOsd(ch, tvCurrentIdx, false);
      }

      // Sintonizar Canal Imediatamente (sem debounce)
      function tvTuneChannel(idx, showOsdBanner = true) {
        if (!tvChannels || tvChannels.length === 0) return;
        if (tvZapDebounceTimer) {
          clearTimeout(tvZapDebounceTimer);
          tvZapDebounceTimer = null;
        }
        tvPlayingType = 'channel';
        tvPreviousMode = 'central';
        tvPlayingSeriesMeta = null;
        stopWatchTimeTracking();
        tvCurrentIdx = (idx + tvChannels.length) % tvChannels.length;
        tvPendingIdx = tvCurrentIdx;
        const ch = tvChannels[tvCurrentIdx];
        if (!ch) return;

        try { localStorage.setItem('andplay_last_tv_ch', tvCurrentIdx); } catch (e) { }

        startTvChannelUsageTracking(ch);
        tvLoadStream(ch);

        const pipName = document.getElementById('tvPipName');
        const pipEpg = document.getElementById('tvPipEpg');
        const tuneEpg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(ch) : null;
        const tuneTitle = tuneEpg ? tuneEpg.nowTitle : (ch.nowTitle || ch.categoryLabel || 'Transmissão Ao Vivo HD');
        if (pipName) pipName.textContent = `${String(tvCurrentIdx + 1).padStart(3, '0')} - ${ch.name}`;
        if (pipEpg) pipEpg.textContent = `🔴 No Ar: ${tuneTitle}`;

        updateTvOsd(ch, tvCurrentIdx, false);
        updateTvChannelRailState();
        if (showOsdBanner && tvCurrentMode === 'fullscreen') {
          triggerTvOsd();
        }
      }

      // Reproduzir Evento Esportivo Ao Vivo no Player de TV
      function tvPlayLiveEvent(ev) {
        if (!ev) return;
        const fallbacks = (ev.fallbacks && ev.fallbacks.length) ? ev.fallbacks : [
          { name: 'Transmissão HD', url: ev.embedUrl || ev.hlsUrl || '', isEmbed: !ev.hlsUrl }
        ];
        const eventChannel = {
          id: ev.id,
          name: ev.name,
          categoryLabel: ev.league || 'Jogos de Hoje',
          logo: ev.homeLogo || ev.poster || '',
          nowTitle: `${ev.matchTime ? '⏰ ' + ev.matchTime + ' • ' : ''}${ev.name}`,
          synopsis: `${ev.league || 'Futebol'} - ${ev.name}. Partida transmitida ao vivo em alta definição com cobertura em tempo real.`,
          fallbacks: fallbacks
        };

        tvPlayingType = 'event';
        tvPreviousMode = 'central';
        tvPlayingSeriesMeta = null;
        stopWatchTimeTracking();
        tvLoadStream(eventChannel);
        updateTvOsd(eventChannel, 0, false);
        setTvViewMode('fullscreen');
        triggerTvOsd();
      }

      // Reproduzir Filme VOD no Player de TV
      function tvPlayMovie(movie) {
        if (!movie) return;
        const watchedId = movie.stream_id || movie.primaryItem?.stream_id || movie.versions?.[0]?.streamId;
        const rawVersions = movie.versions || [{
          item: movie,
          versionInfo: (typeof detectMovieVersion === 'function') ? detectMovieVersion(movie) : { label: 'Principal' },
          streamId: movie.stream_id,
          ext: movie.container_extension || 'mp4'
        }];
        const playableVersions = (typeof getMovieAllPlayableVersions === 'function')
          ? getMovieAllPlayableVersions(movie, rawVersions)
          : rawVersions;
        const selectedVersion = (typeof pickPreferredMovieVersion === 'function')
          ? (pickPreferredMovieVersion(playableVersions) || playableVersions[0])
          : (playableVersions.find(v => v.versionInfo && v.versionInfo.type === 'dublado') || playableVersions[0]);
        if (!selectedVersion) return;
        const isHybrid = !!selectedVersion.isHybrid;
        const streamId = isHybrid ? selectedVersion.videoVersion.streamId : (selectedVersion.streamId || movie.stream_id);
        if (!streamId) return;
        const ext = (isHybrid ? selectedVersion.videoVersion.ext : selectedVersion.ext) || movie.container_extension || 'mp4';
        const videoUrl = `${CONFIG.server}/movie/${CONFIG.user}/${CONFIG.pass}/${streamId}.${ext}`;
        const baseTitle = movie.name || movie.title || 'Filme';
        const title = (selectedVersion.versionInfo?.label && playableVersions.length > 1)
          ? `${baseTitle} (${selectedVersion.versionInfo.label})`
          : baseTitle;

        const pseudoCh = {
          id: `movie_${streamId}`,
          name: title,
          categoryLabel: movie.genre || 'Filmes VOD',
          logo: movie.poster || movie.stream_icon || '',
          nowTitle: title,
          synopsis: movie.plot || `${movie.year ? 'Ano: ' + movie.year + ' • ' : ''}${movie.rating ? '★ ' + movie.rating + ' • ' : ''}Filme sob demanda em alta definição digital.`,
          fallbacks: [{ name: 'HD', url: videoUrl, isEmbed: false }]
        };

        const startPlayback = (startPosition) => {
          stopTvVodProgressTracking();
          saveWatchedId('movies', watchedId);
          tvPlayingType = 'vod';
          tvPreviousMode = (tvCurrentMode === 'vod') ? 'vod' : 'central';
          tvPlayingSeriesMeta = null;
          setTvViewMode('fullscreen');
          startTvVodProgressTracking('movie', streamId, title);
          tvLoadStream(pseudoCh, 0, startPosition);
          updateTvOsd(pseudoCh, 0, false);
        };

        startVodWithResume('movie', streamId, title, startPlayback);
      }

      // Reproduzir Episódio de Série VOD no Player de TV
      function tvPlaySeriesEpisode(seriesItem, ep, seasonNum) {
        if (!seriesItem || !ep) return;
        const watchedId = seriesItem.series_id || seriesItem.primaryItem?.series_id;
        const ext = ep.container_extension || 'mp4';
        const videoUrl = `${CONFIG.server}/series/${CONFIG.user}/${CONFIG.pass}/${ep.id}.${ext}`;
        const sName = seriesItem.name || seriesItem.title || 'Série';
        const epTitle = ep.title || `Episódio ${ep.episode_num}`;
        const fullTitle = `${sName} • T${seasonNum}:E${ep.episode_num}`;

        const pseudoCh = {
          id: `series_${ep.id}`,
          name: fullTitle,
          categoryLabel: seriesItem.genre || 'Séries VOD',
          logo: ep.info?.movie_image || seriesItem.poster || seriesItem.cover || '',
          nowTitle: `${fullTitle} - ${epTitle}`,
          synopsis: ep.info?.plot || seriesItem.plot || `Temporada ${seasonNum}, Episódio ${ep.episode_num} da série ${sName}.`,
          fallbacks: [{ name: 'HD', url: videoUrl, isEmbed: false }]
        };

        const startPlayback = (startPosition) => {
          stopTvVodProgressTracking();
          saveWatchedId('series', watchedId);
          tvPlayingType = 'vod';
          tvPreviousMode = 'vod';
          tvPlayingSeriesMeta = {
            seriesItem: seriesItem,
            ep: ep,
            seasonNum: String(seasonNum),
            seriesId: seriesItem.series_id || seriesItem.id,
            episodesBySeason: seriesItem._cachedEpisodes || null
          };
          setTvViewMode('fullscreen');
          startTvVodProgressTracking('series', ep.id, fullTitle);
          tvLoadStream(pseudoCh, 0, startPosition);
          updateTvOsd(pseudoCh, 0, false);
        };

        startVodWithResume('series', ep.id, fullTitle, startPlayback);
        // Fecha gaveta se estava aberta (mudança de episódio dentro da gaveta)
        if (tvDrawer && !tvDrawer.classList.contains('closed')) {
          tvDrawer.classList.add('closed');
          clearTvDrawerAutoClose();
          const kc = document.getElementById('tvKeyCapture');
          if (kc) setTimeout(() => kc.focus({ preventScroll: true }), 80);
        }
      }

      // Carregar e Exibir Episódios da Série no Modo TV
      let _tvSeriesDetailToken = 0;
      async function openTvSeriesDetail(seriesItem) {
        // Token de requisição: se o usuário abrir outra série antes desta responder,
        // a resposta desta chamada mais antiga é descartada (evita sobrescrever a tela com dados errados)
        const myDetailToken = ++_tvSeriesDetailToken;
        tvVodMode = 'series_episodes';
        tvCurrentSeriesItem = seriesItem;
        if (tvVod) tvVod.scrollTo({ top: 0, behavior: 'smooth' });

        // Atualiza botão voltar para indicar que volta para as séries
        const backBtn = document.getElementById('tvVodBack');
        if (backBtn) backBtn.textContent = '← VOLTAR ÀS SÉRIES';

        const titleEl = document.getElementById('tvVodSectionTitle');
        if (titleEl) titleEl.textContent = `📺 ${seriesItem.name || 'Série'} • Temporadas & Episódios`;

        // Atualiza preview com dados da série
        updateTvVodPreview(seriesItem);

        // Oculta o botão genérico de "Assistir" da série para não sobrepor ou confundir o usuário
        const heroWatchBtn = document.getElementById('tvVodHeroWatchBtn');
        if (heroWatchBtn) heroWatchBtn.style.display = 'none';

        // Oculta seletor de categorias na tela de episódios
        const catSelect = document.getElementById('tvVodCatSelect');
        if (catSelect) catSelect.style.display = 'none';

        const container = document.getElementById('tvVodRailsContainer');
        if (!container) return;
        container.innerHTML = '<div style="color:#ffc107; font-size:16px; font-weight:700; padding:40px; text-align:center;"><div class="spinner" style="margin: 0 auto 15px;"></div>Carregando temporadas e episódios...</div>';

        const seriesId = seriesItem.series_id || seriesItem.id;
        try {
          const data = await xtreamApi('get_series_info', `&series_id=${seriesId}`);
          // Se outra série já foi aberta enquanto esta resposta estava a caminho, descarta o resultado desatualizado
          if (myDetailToken !== _tvSeriesDetailToken) return;
          const episodesBySeason = data.episodes || {};
          const seasons = Object.keys(episodesBySeason).sort((a, b) => Number(a) - Number(b));

          if (seasons.length === 0) {
            container.innerHTML = '<div style="color:#888; font-size:15px; padding:30px; text-align:center;">Nenhum episódio cadastrado para esta série.</div>';
            return;
          }

          container.innerHTML = '';

          // Barra de seleção de temporadas
          const seasonsBar = document.createElement('div');
          seasonsBar.className = 'tv-series-seasons-bar';
          seasonsBar.id = 'tvSeriesSeasonsBar';

          let currentSeason = seasons[0];
          tvCurrentSeason = currentSeason;

          const episodesContainer = document.createElement('div');
          episodesContainer.className = 'tv-series-episodes-grid';
          episodesContainer.id = 'tvSeriesEpisodesGrid';

          function renderTvSeason(seasonNum) {
            tvCurrentSeason = seasonNum;
            seasonsBar.querySelectorAll('.tv-series-season-btn').forEach(btn => {
              btn.classList.toggle('active', btn.dataset.season === String(seasonNum));
            });

            episodesContainer.innerHTML = '';
            const eps = episodesBySeason[seasonNum] || [];
            eps.forEach(ep => {
              const epCard = document.createElement('div');
              epCard.className = 'tv-vod-ep-card tv-focusable';
              epCard.tabIndex = 0;
              const epTitle = ep.title || `Episódio ${ep.episode_num}`;
              const duration = ep.info?.duration || ep.info?.duration_secs ? `${Math.round((ep.info?.duration_secs || 0) / 60) || ep.info?.duration} min` : '';
              const thumb = ep.info?.movie_image || seriesItem.cover || seriesItem.poster || '';

              epCard.innerHTML = `
            ${thumb ? `<img class="tv-ep-thumb" src="${escapeHtml(thumb)}" alt="" data-hide-on-error loading="lazy">` : ''}
            <div class="tv-ep-body">
              <div class="tv-ep-num">TEMPORADA ${seasonNum} • EPISÓDIO ${ep.episode_num}</div>
              <div class="tv-ep-title">${escapeHtml(epTitle)}</div>
              <div class="tv-ep-meta-row">
                ${duration ? `<span class="tv-ep-duration">⏱ ${duration}</span>` : '<span></span>'}
                <span class="tv-ep-cta">▶ Assistir</span>
              </div>
            </div>
          `;

              epCard.addEventListener('focus', () => {
                const plotEl = document.getElementById('tvVodPreviewPlot');
                if (plotEl) plotEl.textContent = ep.info?.plot || seriesItem.plot || 'Sem sinopse disponível.';
                const titleEl2 = document.getElementById('tvVodPreviewTitle');
                if (titleEl2) titleEl2.textContent = `${seriesItem.name} - T${seasonNum}:E${ep.episode_num} "${epTitle}"`;
              });

              epCard.addEventListener('click', () => {
                tvPlaySeriesEpisode(seriesItem, ep, seasonNum);
              });
              epCard.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  tvPlaySeriesEpisode(seriesItem, ep, seasonNum);
                }
              });

              episodesContainer.appendChild(epCard);
            });

            setTimeout(() => {
              const firstEp = episodesContainer.querySelector('.tv-vod-ep-card');
              if (firstEp) firstEp.focus();
            }, 80);
          }

          seasons.forEach((sNum, sIdx) => {
            const sBtn = document.createElement('button');
            sBtn.className = `tv-series-season-btn tv-focusable ${sIdx === 0 ? 'active' : ''}`;
            sBtn.dataset.season = String(sNum);
            sBtn.tabIndex = 0;
            sBtn.textContent = `Temporada ${sNum} (${(episodesBySeason[sNum] || []).length} ep)`;
            sBtn.addEventListener('click', () => renderTvSeason(sNum));
            sBtn.addEventListener('keydown', (e) => {
              if (e.key === 'Enter') renderTvSeason(sNum);
            });
            seasonsBar.appendChild(sBtn);
          });

          container.appendChild(seasonsBar);
          container.appendChild(episodesContainer);
          renderTvSeason(currentSeason);
        } catch (e) {
          if (myDetailToken !== _tvSeriesDetailToken) return;
          container.innerHTML = `<div style="color:#e50914; padding:30px; text-align:center;">Erro ao carregar episódios: ${escapeHtml(e.message)}</div>`;
        }
      }

      // Zapping de Canais com Debounce de 3 segundos
      // Mostra as informações do canal na hora, mas só carrega o vídeo se o usuário parar por 3s ou teclar Enter.
      // Pode ser cancelado com Esc/Backspace, revertendo ao canal atual sem trocar o vídeo.
      function tvZap(delta) {
        if (!tvChannels || tvChannels.length === 0) return;

        // Se já havia um timer rolando, continua navegando a partir do pendingIdx
        if (tvZapDebounceTimer) {
          clearTimeout(tvZapDebounceTimer);
        } else {
          tvPendingIdx = tvCurrentIdx;
        }

        tvPendingIdx = (tvPendingIdx + delta + tvChannels.length) % tvChannels.length;
        const pendingCh = tvChannels[tvPendingIdx];
        if (!pendingCh) return;

        // Atualiza PiP e OSD INSTANTANEAMENTE com os dados do canal pendente (isPending = true)
        const pipName = document.getElementById('tvPipName');
        const pipEpg = document.getElementById('tvPipEpg');
        const zapEpg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(pendingCh) : null;
        const zapTitle = zapEpg ? zapEpg.nowTitle : (pendingCh.nowTitle || pendingCh.categoryLabel || 'Transmissão Ao Vivo HD');
        if (pipName) pipName.textContent = `${String(tvPendingIdx + 1).padStart(3, '0')} - ${pendingCh.name} (Sintonizando...)`;
        if (pipEpg) pipEpg.textContent = `🔴 No Ar: ${zapTitle}`;

        updateTvOsd(pendingCh, tvPendingIdx, true);
        triggerTvOsd(6000);

        // Debounce de 3 segundos (3000ms): confirma apenas quando o usuário parar de navegar
        tvZapDebounceTimer = setTimeout(() => {
          tvCommitPendingChannel();
        }, 3000);
      }

      // Cancela o zapping pendente: reverte o OSD e o PiP ao canal atual SEM trocar o vídeo
      function tvCancelPendingZap() {
        if (!tvZapDebounceTimer) return; // Nada pendente, ignora
        clearTimeout(tvZapDebounceTimer);
        tvZapDebounceTimer = null;

        // Reverte pendingIdx ao canal atual
        tvPendingIdx = tvCurrentIdx;
        const ch = tvChannels[tvCurrentIdx];
        if (!ch) return;

        // Restaura PiP ao canal atual
        const pipName = document.getElementById('tvPipName');
        const pipEpg = document.getElementById('tvPipEpg');
        const cancelEpg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(ch) : null;
        const cancelTitle = cancelEpg ? cancelEpg.nowTitle : (ch.nowTitle || ch.categoryLabel || 'Transmissão Ao Vivo HD');
        if (pipName) pipName.textContent = `${String(tvCurrentIdx + 1).padStart(3, '0')} - ${ch.name}`;
        if (pipEpg) pipEpg.textContent = `🔴 No Ar: ${cancelTitle}`;

        // Atualiza OSD mostrando o canal atual confirmado (não-pendente)
        updateTvOsd(ch, tvCurrentIdx, false);
        // Mantém o OSD visível por 3s para o usuário ver o canal atual
        triggerTvOsd(3000);
      }

      // OSD Banner - Estilo TV a Cabo / Receptor Moderno
      let _tvOsdButtonsWired = false;
      function wireTvOsdButtons() {
        if (_tvOsdButtonsWired) return;
        _tvOsdButtonsWired = true;

        const btnGuide = document.getElementById('tvOsdBtnGuide');
        const btnPrev = document.getElementById('tvOsdBtnPrev');
        const btnNext = document.getElementById('tvOsdBtnNext');
        const btnCentral = document.getElementById('tvOsdBtnCentral');
        const btnCancelZap = document.getElementById('tvOsdBtnCancelZap');

        if (btnGuide) btnGuide.addEventListener('click', (e) => {
          e.stopPropagation();
          toggleTvDrawer();
        });
        if (btnPrev) btnPrev.addEventListener('click', (e) => {
          e.stopPropagation();
          if (tvPlayingType === 'vod' && tvVideo) {
            tvVideo.currentTime = Math.max(0, tvVideo.currentTime - 10);
            triggerTvOsd(3500);
          } else {
            tvZap(-1);
          }
        });
        if (btnNext) btnNext.addEventListener('click', (e) => {
          e.stopPropagation();
          if (tvPlayingType === 'vod' && tvVideo) {
            tvVideo.currentTime = Math.min(tvVideo.duration || 999999, tvVideo.currentTime + 10);
            triggerTvOsd(3500);
          } else {
            tvZap(1);
          }
        });
        if (btnCentral) btnCentral.addEventListener('click', (e) => {
          e.stopPropagation();
          if (tvPlayingType === 'vod') {
            tvVideo.pause();
            tvVideo.src = '';
            setTvViewMode(tvPreviousMode || 'vod');
            if (tvPreviousMode === 'central') tvTuneChannel(tvCurrentIdx, false);
          } else if (tvPlayingType === 'event') {
            setTvViewMode('central');
            tvTuneChannel(tvCurrentIdx, false);
          } else {
            setTvViewMode('central');
          }
        });
        if (btnCancelZap) btnCancelZap.addEventListener('click', (e) => {
          e.stopPropagation();
          tvCancelPendingZap();
        });
      }

      function updateTvOsd(ch, idx, isPending = false) {
        const formattedNum = String(idx + 1).padStart(3, '0');

        // Botões de ação rápida no OSD adaptados ao tipo de reprodução
        const btnGuide = document.getElementById('tvOsdBtnGuide');
        const btnPrev = document.getElementById('tvOsdBtnPrev');
        const btnNext = document.getElementById('tvOsdBtnNext');
        const btnCentral = document.getElementById('tvOsdBtnCentral');
        const btnCancelZap = document.getElementById('tvOsdBtnCancelZap');

        if (btnCancelZap) btnCancelZap.style.display = isPending ? 'inline-flex' : 'none';

        if (tvPlayingType === 'vod') {
          if (tvPlayingSeriesMeta) {
            // Série: mostrar botão de episódios no OSD
            if (btnGuide) {
              btnGuide.style.display = 'inline-flex';
              btnGuide.innerHTML = '<span class="tv-key-tag">OK</span> Episódios';
            }
          } else {
            if (btnGuide) btnGuide.style.display = 'none';
          }
          if (btnPrev) { btnPrev.style.display = 'inline-flex'; btnPrev.textContent = '⏪ -10s'; }
          if (btnNext) { btnNext.style.display = 'inline-flex'; btnNext.textContent = '⏩ +10s'; }
          if (btnCentral) btnCentral.textContent = '⬅ VOLTAR';
        } else if (tvPlayingType === 'event') {
          if (btnGuide) btnGuide.style.display = 'none';
          if (btnPrev) btnPrev.style.display = 'none';
          if (btnNext) btnNext.style.display = 'none';
          if (btnCentral) btnCentral.textContent = '⬅ CENTRAL';
        } else {
          if (btnGuide) { btnGuide.style.display = 'inline-flex'; btnGuide.innerHTML = '<span class="tv-key-tag">OK</span> Guia EPG'; }
          if (btnPrev) { btnPrev.style.display = 'inline-flex'; btnPrev.textContent = '▲ CANAL -'; }
          if (btnNext) { btnNext.style.display = 'inline-flex'; btnNext.textContent = '▼ CANAL +'; }
          if (btnCentral) btnCentral.textContent = '⬅ CENTRAL';
        }

        // 1. Indicador Rápido de Zapping no Topo
        const topNum = document.getElementById('tvTopChNum');
        const topName = document.getElementById('tvTopChName');
        const topLive = document.getElementById('tvTopChLive');
        if (topNum) topNum.textContent = (tvPlayingType === 'vod') ? 'CINEMA' : (tvPlayingType === 'event' ? 'JOGO' : `CH ${formattedNum}`);
        if (topName) topName.textContent = ch.name;
        if (topLive) {
          if (isPending) {
            topLive.innerHTML = '<span class="tv-pulse" style="background:#ffc107;"></span> SINTONIZANDO (3s)';
            topLive.style.background = 'rgba(255, 193, 7, 0.25)';
            topLive.style.color = '#ffc107';
            topLive.style.borderColor = 'rgba(255, 193, 7, 0.5)';
          } else if (tvPlayingType === 'vod') {
            topLive.innerHTML = '<span class="tv-pulse" style="background:#00e676;"></span> VOD HD';
            topLive.style.background = 'rgba(0, 230, 118, 0.25)';
            topLive.style.color = '#00e676';
            topLive.style.borderColor = 'rgba(0, 230, 118, 0.5)';
          } else {
            topLive.innerHTML = '<span class="tv-pulse"></span> NO AR';
            topLive.style.background = 'rgba(229, 9, 20, 0.25)';
            topLive.style.color = '#ff4d4d';
            topLive.style.borderColor = 'rgba(229, 9, 20, 0.5)';
          }
        }

        // 2. Coluna 1: Canal / Mídia
        const osdNum = document.getElementById('tvOsdNum');
        const osdLogo = document.getElementById('tvOsdLogo');
        const osdName = document.getElementById('tvOsdName');
        const osdCategory = document.getElementById('tvOsdCategory');
        const osdRating = document.getElementById('tvOsdRating');
        const osdLive = document.getElementById('tvOsdLiveBadge');

        if (osdNum) osdNum.textContent = (tvPlayingType === 'vod') ? '🎬' : (tvPlayingType === 'event' ? '⚽' : formattedNum);
        if (osdLogo) {
          const logoUrl = getTvLogoUrl(ch.logo, 180, 120);
          osdLogo.src = logoUrl || getTvLogoFallback(ch.name);
          osdLogo.style.display = 'block';
          osdLogo.onerror = function () {
            this.onerror = null;
            this.src = getTvLogoFallback(ch.name);
          };
        }
        if (osdName) osdName.textContent = ch.name;
        if (osdCategory) osdCategory.textContent = ch.categoryLabel || (tvPlayingType === 'vod' ? 'Catálogo VOD' : 'TV Ao Vivo');
        if (osdLive) {
          if (isPending) {
            osdLive.innerHTML = '⏳ SINTONIZANDO (3s) • OK=Confirmar • ESC=Cancelar';
            osdLive.style.background = 'rgba(255, 152, 0, 0.9)';
            osdLive.style.color = '#000';
          } else if (tvPlayingType === 'vod') {
            osdLive.innerHTML = '<span class="tv-pulse" style="background:#00e676;"></span> VOD HD';
            osdLive.style.background = '#00e676';
            osdLive.style.color = '#000';
          } else {
            osdLive.innerHTML = '<span class="tv-pulse"></span> NO AR';
            osdLive.style.background = '#e50914';
            osdLive.style.color = '#fff';
          }
        }

        // Classificação indicativa inteligente
        if (osdRating) {
          const cat = (ch.categoryLabel || '').toLowerCase();
          if (cat.includes('infantil') || cat.includes('desenho')) {
            osdRating.textContent = 'L';
            osdRating.style.background = '#2e7d32';
          } else if (cat.includes('filme') || cat.includes('série') || cat.includes('terror')) {
            osdRating.textContent = '14';
            osdRating.style.background = '#e65100';
          } else if (cat.includes('esporte') || cat.includes('aberto') || cat.includes('notícia')) {
            osdRating.textContent = '10';
            osdRating.style.background = '#0277bd';
          } else {
            osdRating.textContent = '12';
            osdRating.style.background = '#f57f17';
          }
        }

        // 3. Coluna 2: Guia de Programação EPG / Progresso VOD
        const osdNow = document.getElementById('tvOsdNow');
        const osdTimeRange = document.getElementById('tvOsdTimeRange');
        const osdProgFill = document.getElementById('tvOsdProgFill');
        const osdProgStatus = document.getElementById('tvOsdProgStatus');
        const osdProgRemaining = document.getElementById('tvOsdProgRemaining');
        const osdSynopsis = document.getElementById('tvOsdSynopsis');
        const osdNext = document.getElementById('tvOsdNext');

        if (tvPlayingType === 'vod') {
          const cur = (tvVideo && tvVideo.currentTime) ? tvVideo.currentTime : 0;
          const dur = (tvVideo && tvVideo.duration) ? tvVideo.duration : 0;
          const curStr = formatTvSeconds(cur);
          const durStr = dur > 0 ? formatTvSeconds(dur) : '--:--';
          const pct = dur > 0 ? Math.round((cur / dur) * 100) : 0;

          // Adapta o rótulo "NO AR AGORA" para VOD
          const tagNow = tvOsd ? tvOsd.querySelector('.tv-osd-tag-now') : null;
          if (tagNow) tagNow.textContent = tvPlayingSeriesMeta ? '🎞 EPISÓDIO' : '🎬 ASSISTINDO';

          if (osdNow) osdNow.textContent = ch.nowTitle || ch.name;
          if (osdTimeRange) osdTimeRange.textContent = `${curStr} • ${durStr}`;
          if (osdProgFill) osdProgFill.style.width = `${pct}%`;
          if (osdProgStatus) {
            osdProgStatus.textContent = (tvVideo && tvVideo.paused) ? `⏸ Pausado (${curStr} / ${durStr})` : `▶ Reproduzindo (${curStr} / ${durStr})`;
            osdProgStatus.style.color = (tvVideo && tvVideo.paused) ? '#ffc107' : '#00e676';
          }
          if (osdProgRemaining) {
            osdProgRemaining.textContent = dur > 0 ? `Restam ~${Math.max(0, Math.round((dur - cur) / 60))} min` : 'Cinema Digital HD';
          }
          if (osdSynopsis) osdSynopsis.textContent = ch.synopsis || 'Vídeo sob demanda em alta definição.';
          if (osdNext) osdNext.textContent = '◀ Seta Esquerda (-10s) • Seta Direita (+10s) ▶ • OK Pausa/Play';
        } else {
          // Restaura o rótulo padrão ao vivo
          const tagNow = tvOsd ? tvOsd.querySelector('.tv-osd-tag-now') : null;
          if (tagNow) tagNow.textContent = '🔴 NO AR AGORA';
          // Guia EPG Dinâmico em Tempo Real para Canais e Eventos
          const epg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(ch, new Date()) : null;

          const nowTitle = epg ? epg.nowTitle : (ch.nowTitle || ch.name || 'Programação Ao Vivo HD');
          const timeRange = epg ? epg.timeRange : '--:-- • --:--';
          const progress = epg ? epg.progress : 50;
          const remMin = epg ? epg.remainingMinutes : 30;
          const synopsis = epg ? epg.synopsis : (ch.synopsis || `Assista à transmissão digital oficial de ${ch.name} em alta resolução 1080p, com som estéreo Dolby e sinal contínuo.`);
          const nextStr = (epg && epg.nextTitle) ? `${epg.nextStart} • ${epg.nextTitle}` : (ch.nextProgrammes && ch.nextProgrammes[0] ? `${ch.nextProgrammes[0].s || ''} • ${ch.nextProgrammes[0].t}` : `Continuação da Grade de ${ch.name}`);

          if (osdNow) osdNow.textContent = nowTitle;
          if (osdTimeRange) osdTimeRange.textContent = timeRange;
          if (osdProgFill) osdProgFill.style.width = `${progress}%`;

          if (osdProgStatus) {
            if (isPending) {
              osdProgStatus.textContent = `Aguardando 3s... (OK confirma • ESC cancela)`;
              osdProgStatus.style.color = '#ffc107';
            } else {
              osdProgStatus.textContent = `Em andamento (${progress}%)`;
              osdProgStatus.style.color = '#9ca3af';
            }
          }

          if (osdProgRemaining) {
            if (isPending) {
              osdProgRemaining.textContent = `Pressione OK para sintonizar agora`;
            } else {
              osdProgRemaining.textContent = `Restam ~${remMin} min`;
            }
          }

          if (osdSynopsis) osdSynopsis.textContent = synopsis;
          if (osdNext) osdNext.textContent = `▶ A SEGUIR: ${nextStr}`;
        }
      }

      const floatBack = document.getElementById('tvFloatingBackBtn');
      if (floatBack && !floatBack._wired) {
        floatBack._wired = true;
        floatBack.addEventListener('click', (e) => {
          e.stopPropagation();
          window.handleAndroidBack();
        });
        floatBack.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            window.handleAndroidBack();
          }
        });
      }
      function triggerTvOsd(duration = 5000) {
        if (tvOsdTimer) clearTimeout(tvOsdTimer);
        const topBadge = document.getElementById('tvTopChannelBadge');
        const floatBackBtn = document.getElementById('tvFloatingBackBtn');
        if (tvOsd) tvOsd.classList.remove('hidden');
        if (topBadge) topBadge.classList.remove('hidden');
        if (floatBackBtn) floatBackBtn.classList.remove('hidden');

        tvOsdTimer = setTimeout(() => {
          if (tvOsd) tvOsd.classList.add('hidden');
          if (topBadge) topBadge.classList.add('hidden');
          if (floatBackBtn) floatBackBtn.classList.add('hidden');
        }, duration);
      }

      // Fecha automaticamente a gaveta/overlay do Guia EPG após um período de inatividade
      // (mesmo comportamento de auto-ocultação do overlay de informações/OSD)
      function triggerTvDrawerAutoClose(duration = 8000) {
        if (tvDrawerAutoCloseTimer) clearTimeout(tvDrawerAutoCloseTimer);
        tvDrawerAutoCloseTimer = setTimeout(() => {
          if (tvCurrentMode === 'fullscreen' && tvDrawer && !tvDrawer.classList.contains('closed')) {
            tvDrawer.classList.add('closed');
            const kc = document.getElementById('tvKeyCapture');
            if (kc) setTimeout(() => kc.focus({ preventScroll: true }), 50);
          }
          tvDrawerAutoCloseTimer = null;
        }, duration);
      }
      function clearTvDrawerAutoClose() {
        if (tvDrawerAutoCloseTimer) { clearTimeout(tvDrawerAutoCloseTimer); tvDrawerAutoCloseTimer = null; }
      }

      // Sincroniza dinamicamente a posição e dimensões do PiP com o frame dentro da Central (acompanha a rolagem da página)
      function syncTvPlayerBoxPosition() {
        if (tvCurrentMode !== 'central') return;
        const placeholder = document.getElementById('tvPipPlaceholder');
        const playerBox = document.getElementById('tvPlayerBox');
        if (!placeholder || !playerBox) return;

        const rect = placeholder.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) {
          playerBox.style.top = `${rect.top}px`;
          playerBox.style.left = `${rect.left}px`;
          playerBox.style.width = `${rect.width}px`;
          playerBox.style.height = `${rect.height}px`;

          // Se o frame PiP rolou para fora da área visível (ao rolar os trilhos para baixo), oculta o vídeo para não sobrepor nada
          if (rect.bottom < 40 || rect.top > window.innerHeight - 40) {
            playerBox.style.display = 'none';
          } else {
            playerBox.style.display = 'flex';
          }
        }
      }

      // ============================================================
      // Limpa todo estado persistente de overlay ao trocar de modo
      // Evita que gaveta de séries apareça no EPG de TV e vice-versa
      // ============================================================
      function resetTvOverlayState() {
        // 1. Fecha gaveta lateral e limpa seu conteúdo residual
        if (tvDrawer) {
          tvDrawer.classList.add('closed');
          const drawerTitle = tvDrawer.querySelector('.tv-drawer-title');
          const drawerHint = tvDrawer.querySelector('.tv-drawer-hint');
          const catsEl = document.getElementById('tvDrawerCats');
          const listEl = document.getElementById('tvDrawerChannels');
          if (drawerTitle) drawerTitle.textContent = '📋 GUIA DE CANAIS';
          if (drawerHint) drawerHint.textContent = 'OK para sintonizar • Voltar fecha';
          if (catsEl) catsEl.innerHTML = '';
          if (listEl) listEl.innerHTML = '';
        }

        // 2. Esconde e congela o OSD
        if (tvOsd) tvOsd.classList.add('hidden');
        const topBadge = document.getElementById('tvTopChannelBadge');
        if (topBadge) topBadge.classList.add('hidden');
        const floatBackBtn2 = document.getElementById('tvFloatingBackBtn');
        if (floatBackBtn2) floatBackBtn2.classList.add('hidden');
        if (tvOsdTimer) { clearTimeout(tvOsdTimer); tvOsdTimer = null; }
        clearTvDrawerAutoClose();

        // 3. Limpa hero VOD (evita que poster/título da série apareçam na seção de filmes)
        const heroTitle = document.getElementById('tvVodPreviewTitle');
        const heroMeta = document.getElementById('tvVodPreviewMeta');
        const heroPlot = document.getElementById('tvVodPreviewPlot');
        const heroPoster = document.getElementById('tvVodPreviewPoster');
        const heroBtn = document.getElementById('tvVodHeroWatchBtn');
        if (heroTitle) heroTitle.textContent = 'Selecione um título';
        if (heroMeta) heroMeta.innerHTML = '';
        if (heroPlot) heroPlot.textContent = 'Navegue pelos títulos para ver sinopse e detalhes.';
        if (heroPoster) { heroPoster.src = ''; heroPoster.style.display = 'none'; }
        if (heroBtn) heroBtn.style.display = 'none';

        // 4. Limpa container VOD (evita cartas stale de outra categoria)
        const vodContainer = document.getElementById('tvVodRailsContainer');
        if (vodContainer) vodContainer.innerHTML = '';

        // 5. Re-registra categorias do drawer para canais (apaga as pills de temporadas de série)
        setupTvDrawerCategories();
      }

      // Alternar entre Telas (Central x Tela Cheia x VOD)
      function setTvViewMode(mode) {
        if (mode !== 'fullscreen' && tvPlayingType === 'vod') {
          stopTvVodProgressTracking();
        }
        tvCurrentMode = mode;
        try {
          if (mode === 'fullscreen' || mode === 'vod') {
            window.history.pushState({ tvMode: mode }, '');
          }
        } catch (e) { }
        const tvKc = document.getElementById('tvKeyCapture');
        if (mode === 'fullscreen') {
          tvCentral.style.display = 'none';
          tvVod.style.display = 'none';
          tvFullscreen.style.display = 'flex';
          tvPlayerBox.className = 'tv-player-box fullscreen';
          tvPlayerBox.style.top = '';
          tvPlayerBox.style.left = '';
          tvPlayerBox.style.width = '';
          tvPlayerBox.style.height = '';
          tvPlayerBox.style.display = 'flex';
          // Ativa overlay de captura de teclado (impede iframe de roubar foco)
          if (tvKc) {
            tvKc.style.display = 'block';
            tvKc.style.pointerEvents = 'all';
            setTimeout(() => { tvKc.focus({ preventScroll: true }); }, 150);
          }
          triggerTvOsd();
        } else if (mode === 'central') {
          resetTvOverlayState();
          tvFullscreen.style.display = 'none';
          tvVod.style.display = 'none';
          tvCentral.style.display = 'flex';
          tvPlayerBox.className = 'tv-player-box pip';
          tvPlayerBox.style.display = 'flex';
          if (tvCentral) tvCentral.scrollTo({ top: 0 });
          // Desativa overlay — na central o foco vai para o PiP card
          if (tvKc) { tvKc.style.display = 'none'; tvKc.style.pointerEvents = 'none'; }
          syncTvPlayerBoxPosition();
          const pip = document.getElementById('tvPipCard');
          if (pip) pip.focus();
        } else if (mode === 'vod') {
          resetTvOverlayState();
          tvCentral.style.display = 'none';
          tvFullscreen.style.display = 'none';
          tvVod.style.display = 'flex';
          tvPlayerBox.className = 'tv-player-box pip';
          tvPlayerBox.style.display = 'none';
          if (tvVod) tvVod.scrollTo({ top: 0 });
          if (tvKc) { tvKc.style.display = 'none'; tvKc.style.pointerEvents = 'none'; }
          const backBtn = document.getElementById('tvVodBack');
          if (backBtn) backBtn.focus();
        }
      }

      // Teclas do Controle Remoto (Hardware Android TV / Box)
      window.onTvRemoteKey = function (key) {
        console.log('[EPlay TV Remote]', key);
        if (tvCurrentMode === 'fullscreen') {
          if (tvPlayingType === 'channel') {
            if (key === 'CHANNEL_UP') { tvZap(-1); return; }
            if (key === 'CHANNEL_DOWN') { tvZap(1); return; }
          }
          if (key === 'PLAY_PAUSE') {
            if (tvPlayingType === 'vod' && tvVideo) {
              if (tvVideo.paused) tvVideo.play();
              else tvVideo.pause();
              triggerTvOsd(3000);
              return;
            }
          }
          if (key === 'MENU' || key === 'BACK') {
            window.handleAndroidBack();
            return;
          }
          if (key === 'GUIDE' || key === 'INFO') { toggleTvDrawer(); return; }
        } else if (tvCurrentMode === 'central') {
          if (key === 'MENU') {
            const pip = document.getElementById('tvPipCard');
            if (pip) pip.focus();
            return;
          }
        } else if (tvCurrentMode === 'vod') {
          if (key === 'BACK' || key === 'MENU') {
            window.handleAndroidBack();
            return;
          }
        }
      };

      // Botão Voltar Unificado (Android APK, Smart TV e Web)
      window.handleAndroidBack = function () {
        if (typeof elements !== 'undefined') {
          if (elements.videoModal && elements.videoModal.style.display === 'flex') { closePlayer(); return true; }
          if (elements.seriesModal && elements.seriesModal.style.display === 'flex') { closeSeriesModal(); return true; }
          if (elements.movieVersionModal && elements.movieVersionModal.style.display === 'flex') { closeMovieVersionModal(); return true; }
          if (elements.imdbSearchModal && elements.imdbSearchModal.style.display === 'flex') { closeImdbSearchModal(); return true; }
          if (elements.contentPage && !elements.contentPage.hidden) { restoreFromContentPage(); return true; }
        }

        if (tvCurrentMode === 'fullscreen') {
          if (tvDrawer && !tvDrawer.classList.contains('closed')) {
            tvDrawer.classList.add('closed');
            if (typeof clearTvDrawerAutoClose === 'function') clearTvDrawerAutoClose();
            return true;
          }
          if (typeof tvZapDebounceTimer !== 'undefined' && tvZapDebounceTimer) {
            if (typeof tvCancelPendingZap === 'function') tvCancelPendingZap();
            return true;
          }
          if (tvPlayingType === 'vod') {
            if (tvVideo) { try { tvVideo.pause(); tvVideo.src = ''; } catch (e) { } }
            setTvViewMode(tvPreviousMode || 'vod');
            if (tvPreviousMode === 'central' && typeof tvTuneChannel === 'function') tvTuneChannel(tvCurrentIdx, false);
            return true;
          }
          if (tvPlayingType === 'event') {
            setTvViewMode('central');
            if (typeof tvTuneChannel === 'function') tvTuneChannel(tvCurrentIdx, false);
            return true;
          }
          setTvViewMode('central');
          return true;
        }
        if (tvCurrentMode === 'vod') {
          if (tvVodMode === 'series_episodes') {
            openTvVodExplorer('series');
            return true;
          }
          setTvViewMode('central');
          return true;
        }
        return false;
      };

      // Suporte a popstate do Android WebView (webView.goBack()) e Navegadores Mobile
      window.addEventListener('popstate', function (event) {
        isHandlingPopstate = true;
        try {
          if (elements.videoModal && elements.videoModal.style.display === 'flex') {
            closePlayer();
            return;
          }
          if (elements.seriesModal && elements.seriesModal.style.display === 'flex') {
            closeSeriesModal();
            return;
          }
          if (elements.movieVersionModal && elements.movieVersionModal.style.display === 'flex') {
            closeMovieVersionModal();
            return;
          }
          if (elements.imdbSearchModal && elements.imdbSearchModal.style.display === 'flex') {
            closeImdbSearchModal();
            return;
          }
          if (event?.state && event.state.page === 'content' && elements.contentPage && !elements.contentPage.hidden) {
            return;
          }
          if (elements.contentPage && !elements.contentPage.hidden) {
            restoreFromContentPage();
            return;
          }
          if (window.handleAppRouteFromHistory) {
            window.handleAppRouteFromHistory();
          }
        } finally {
          isHandlingPopstate = false;
        }
      });

      window.onBackPressed = window.handleAndroidBack;

      // Captura do botão Voltar em qualquer nível (window, document, keydown e keyup)
      const captureAndroidBack = function (e) {
        const code = e.keyCode || e.which;
        const isBack = (code === 4 || code === 27 || code === 111 || code === 8 || e.key === 'Back' || e.key === 'Escape' || e.key === 'GoBack' || e.key === 'BrowserBack');
        if (isBack) {
          if (window.handleAndroidBack()) {
            e.preventDefault();
            e.stopPropagation();
          }
        }
      };
      window.addEventListener('keydown', captureAndroidBack, true);
      document.addEventListener('keydown', captureAndroidBack, true);
      window.addEventListener('keyup', captureAndroidBack, true);
      document.addEventListener('backbutton', function (e) {
        if (window.handleAndroidBack()) {
          if (e) { e.preventDefault(); e.stopPropagation(); }
        }
      }, false);

      // Drawer EPG
      function toggleTvDrawer() {
        if (tvDrawer.classList.contains('closed')) {
          // Esconde o OSD banner para não sobrepor a gaveta EPG
          if (tvOsd) tvOsd.classList.add('hidden');
          const topBadge = document.getElementById('tvTopChannelBadge');
          if (topBadge) topBadge.classList.add('hidden');
          const floatBackBtn3 = document.getElementById('tvFloatingBackBtn');
          if (floatBackBtn3) floatBackBtn3.classList.add('hidden');
          if (tvOsdTimer) { clearTimeout(tvOsdTimer); tvOsdTimer = null; }

          tvDrawer.classList.remove('closed');

          if (tvPlayingType === 'vod' && tvPlayingSeriesMeta) {
            // Modo Série em reprodução: renderiza gaveta de episódios/temporadas
            renderTvSeriesDrawer(tvPlayingSeriesMeta);
          } else {
            // Modo Canal ou Filme sem meta de série: garante guia de canais limpo
            const drawerTitle = tvDrawer.querySelector('.tv-drawer-title');
            const drawerHint = tvDrawer.querySelector('.tv-drawer-hint');
            if (drawerTitle) drawerTitle.textContent = '📋 GUIA DE CANAIS';
            if (drawerHint) drawerHint.textContent = 'OK para sintonizar • Voltar fecha';
            // Garante que as pills de temporadas de série foram apagadas
            // e que as categorias de canal foram recriadas limpas
            setupTvDrawerCategories();
            const catsEl = document.getElementById('tvDrawerCats');
            if (catsEl) catsEl.style.display = '';
            renderTvDrawerList('ALL');
          }

          // Foca no item ativo (canal atual ou episódio em reprodução) ou no primeiro item
          setTimeout(() => {
            const activeItem = tvDrawer.querySelector('.tv-drawer-item.active-channel');
            const firstItem = tvDrawer.querySelector('.tv-drawer-item');
            const target = activeItem || firstItem;
            if (target) {
              target.focus();
              target.scrollIntoView({ block: 'center', behavior: 'smooth' });
            }
          }, 80);

          // Inicia contagem de 8s de inatividade para fechar a gaveta automaticamente
          if (tvCurrentMode === 'fullscreen') triggerTvDrawerAutoClose(8000);
        } else {
          tvDrawer.classList.add('closed');
          clearTvDrawerAutoClose();
          // Devolve o foco ao overlay de captura de teclado
          const kc = document.getElementById('tvKeyCapture');
          if (kc) setTimeout(() => kc.focus({ preventScroll: true }), 50);
        }
      }

      // Renderiza Trilho de Canais na Central
      function renderTvFeaturedChannels() {
        const rail = document.getElementById('tvChannelsRail');
        if (!rail || !tvChannels) return;
        rail.innerHTML = '';

        // Seleciona os principais 30 canais em destaque
        const featuredSlugs = ['globo', 'sbt', 'record', 'band', 'sportv', 'sportv2', 'sportv3', 'premiere', 'premiere2', 'espn', 'espn2', 'espn4', 'tnt', 'space', 'telecine-premium', 'telecine-action', 'telecine-pipoca', 'hbo', 'hbo2', 'warner', 'cazetv', 'cnn-brasil', 'globonews', 'discovery-kids', 'cartoon-network'];
        const list = tvChannels.filter(c => featuredSlugs.some(s => c.channelSlug.toLowerCase().includes(s))).concat(tvChannels.slice(0, 15));
        const uniqueList = Array.from(new Set(list.map(c => c.id))).map(id => list.find(c => c.id === id));

        uniqueList.forEach((ch, idx) => {
          const card = document.createElement('div');
          card.className = 'tv-channel-card tv-focusable';
          card.tabIndex = 0;
          const realIdx = tvChannels.indexOf(ch);
          card.dataset.channelIndex = String(realIdx);
          const logoUrl = getTvLogoUrl(ch.logo, 140, 90);
          const logoFallback = getTvLogoFallback(ch.name);
          card.innerHTML = `
        <div class="num">CH ${String(realIdx + 1).padStart(3, '0')}</div>
        <img src="${escapeHtml(logoUrl || logoFallback)}" alt="${escapeHtml(ch.name)}" loading="lazy" decoding="async">
        <div class="name">${escapeHtml(ch.name)}</div>
      `;
          const channelLogo = card.querySelector('img');
          if (channelLogo) {
            channelLogo.onerror = function () {
              this.onerror = null;
              this.src = logoFallback;
            };
          }
          card.addEventListener('click', () => {
            tvTuneChannel(realIdx, true);
            setTvViewMode('fullscreen');
          });
          card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              tvTuneChannel(realIdx, true);
              setTvViewMode('fullscreen');
            }
          });
          rail.appendChild(card);
        });

        updateTvChannelRailState();
      }

      // Mantém o canal sintonizado destacado no rail, como o estado selected do adapter nativo.
      function updateTvChannelRailState() {
        const rail = document.getElementById('tvChannelsRail');
        if (!rail) return;

        rail.querySelectorAll('.tv-channel-card').forEach(card => {
          const idx = Number(card.dataset.channelIndex);
          const active = Number.isInteger(idx) && idx === tvCurrentIdx;
          card.classList.toggle('active-channel', active);
          card.setAttribute('aria-current', active ? 'true' : 'false');

          let badge = card.querySelector('.tv-channel-live-badge');
          if (active) {
            if (!badge) {
              badge = document.createElement('span');
              badge.className = 'tv-channel-live-badge';
              badge.textContent = 'NO AR';
              card.appendChild(badge);
            }
          } else if (badge) {
            badge.remove();
          }
        });
      }

      // Pré-carrega algumas capas à frente do foco, inspirado no preload do MoviePosterAdapter.
      function preloadTvRailPosters(cards, currentIndex, limit = 15) {
        const start = Math.max(0, currentIndex + 1);
        const end = Math.min(cards.length, start + limit);
        for (let i = start; i < end; i++) {
          const img = cards[i].querySelector('img');
          const src = img && img.currentSrc ? img.currentSrc : (img && img.src ? img.src : '');
          if (!src || src.startsWith('data:') || tvPreloadedPosterUrls.has(src)) continue;
          tvPreloadedPosterUrls.add(src);
          const preload = new Image();
          preload.decoding = 'async';
          preload.src = src;
        }
      }

      // Renderiza Trilho de Jogos
      async function loadTvSportsRail() {
        const rail = document.getElementById('tvSportsRail');
        if (!rail) return;
        rail.innerHTML = '<div style="color:#888; font-size:13px; padding:10px;">Carregando partidas de hoje...</div>';

        let sports = [];
        if (typeof fetchLiveSportsEvents === 'function') {
          try { sports = await fetchLiveSportsEvents(); } catch (e) { }
        }

        rail.innerHTML = '';
        if (!sports || sports.length === 0) {
          rail.innerHTML = '<div style="color:#666; font-size:13px; padding:10px;">Nenhuma partida agendada no momento.</div>';
          return;
        }

        sports.forEach(ev => {
          const card = document.createElement('div');
          card.className = 'tv-match-card tv-focusable';
          card.tabIndex = 0;
          const isNow = ev.isLiveNow;
          const homeLogo = getTvTeamLogoUrl(ev.homeTeam, ev.homeLogo);
          const awayLogo = getTvTeamLogoUrl(ev.awayTeam, ev.awayLogo);
          const homeName = ev.homeTeam || 'Time 1';
          const awayName = ev.awayTeam || 'Time 2';
          const homeFallback = escapeHtml(homeName.slice(0, 3).toUpperCase());
          const awayFallback = escapeHtml(awayName.slice(0, 3).toUpperCase());
          const centerText = ev.score || (isNow && String(ev.matchTime || '').toUpperCase() === 'AO VIVO' ? 'VS' : (ev.matchTime || 'VS'));
          const badgeText = isNow ? '🔴 AO VIVO' : ('⏰ ' + (ev.matchTime || 'HOJE'));
          const homeCell = '<div class="tv-match-team"><img src="' + escapeHtml(homeLogo || getTvLogoFallback(homeName)) + '" alt="' + escapeHtml(homeName) + '" loading="lazy" decoding="async"><span class="tv-match-logo-fallback" style="display:none;">' + homeFallback + '</span></div>';
          const awayCell = '<div class="tv-match-team"><img src="' + escapeHtml(awayLogo || getTvLogoFallback(awayName)) + '" alt="' + escapeHtml(awayName) + '" loading="lazy" decoding="async"><span class="tv-match-logo-fallback" style="display:none;">' + awayFallback + '</span></div>';
          card.innerHTML = '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px; min-width:0;">'
            + '<span class="tv-match-status ' + (isNow ? 'live' : '') + '">' + escapeHtml(badgeText) + '</span>'
            + '<span class="tv-match-league">' + escapeHtml(ev.league || 'Futebol') + '</span></div>'
            + '<div class="tv-match-teams">' + homeCell
            + '<span class="tv-match-score">' + escapeHtml(centerText) + '</span>'
            + awayCell + '</div>'
            + '<div class="tv-match-title">' + escapeHtml(ev.name || (homeName + ' x ' + awayName)) + '</div>';
          card.querySelectorAll('.tv-match-team img').forEach((img) => {
            img.addEventListener('error', function () {
              this.onerror = null;
              const fallback = this.nextElementSibling;
              this.style.display = 'none';
              if (fallback) fallback.style.display = 'flex';
            });
          });
          card.addEventListener('click', () => {
            tvPlayLiveEvent(ev);
          });
          card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              tvPlayLiveEvent(ev);
            }
          });
          rail.appendChild(card);
        });
      }

      // Renderiza Trilho de Filmes
      function loadTvMoviesRail() {
        const rail = document.getElementById('tvMoviesRail');
        if (!rail) return;
        const movies = (typeof fullMoviesCache !== 'undefined' && Array.isArray(fullMoviesCache)) ? fullMoviesCache.slice(0, 30) : [];
        if (movies.length === 0) {
          setTimeout(loadTvMoviesRail, 1500);
          return;
        }

        rail.innerHTML = '';
        movies.forEach(m => {
          const card = document.createElement('div');
          card.className = 'tv-vod-card tv-focusable';
          card.tabIndex = 0;
          const poster = m.poster || m.stream_icon || m.cover || '';
          const name = m.name || m.title || 'Filme';
          card.innerHTML = `
        <img src="${escapeHtml(poster)}" alt="${escapeHtml(name)}" data-fade-on-error loading="lazy" decoding="async">
        <div class="name">${escapeHtml(name)}</div>
      `;
          card.addEventListener('focus', () => {
            const cards = Array.from(rail.querySelectorAll('.tv-vod-card'));
            preloadTvRailPosters(cards, cards.indexOf(card));
          });
          card.addEventListener('click', () => tvPlayMovie(m));
          card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              tvPlayMovie(m);
            }
          });
          rail.appendChild(card);
        });
      }

      // Trilho de séries equivalente ao rail nativo do APK.
      function loadTvSeriesRail() {
        const rail = document.getElementById('tvSeriesRail');
        if (!rail) return;
        const series = (typeof fullSeriesCache !== 'undefined' && Array.isArray(fullSeriesCache))
          ? fullSeriesCache.slice(0, 30)
          : [];
        if (series.length === 0) {
          setTimeout(loadTvSeriesRail, 1500);
          return;
        }

        rail.innerHTML = '';
        series.forEach(s => {
          const card = document.createElement('div');
          card.className = 'tv-vod-card tv-focusable';
          card.tabIndex = 0;
          const poster = s.cover || s.poster || s.stream_icon || '';
          const name = s.name || s.title || 'Série';
          card.innerHTML = `
            <img src="${escapeHtml(poster)}" alt="${escapeHtml(name)}"
              data-fade-on-error loading="lazy" decoding="async">
            <div class="name">${escapeHtml(name)}</div>
          `;

          card.addEventListener('focus', () => {
            const cards = Array.from(rail.querySelectorAll('.tv-vod-card'));
            preloadTvRailPosters(cards, cards.indexOf(card));
          });
          card.addEventListener('click', () => openTvSeriesDetail(s));
          card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              openTvSeriesDetail(s);
            }
          });
          rail.appendChild(card);
        });
      }

      // Guia Lateral de Categorias
      function setupTvDrawerCategories() {
        const catContainer = document.getElementById('tvDrawerCats');
        if (!catContainer) return;
        const cats = [
          { id: 'ALL', name: 'Todos' },
          { id: 'sports', name: 'Esportes' },
          { id: 'open_tv', name: 'Abertos' },
          { id: 'movies', name: 'Filmes' },
          { id: 'kids', name: 'Infantil' },
          { id: 'news', name: 'Notícias' }
        ];
        catContainer.innerHTML = '';
        cats.forEach((c, idx) => {
          const btn = document.createElement('button');
          btn.className = `tv-epg-cat-pill tv-focusable ${idx === 0 ? 'active' : ''}`;
          btn.textContent = c.name;
          btn.dataset.catId = c.id; // guarda o id para leitura via setas
          btn.addEventListener('click', () => {
            catContainer.querySelectorAll('.tv-epg-cat-pill').forEach(p => p.classList.remove('active'));
            btn.classList.add('active');
            renderTvDrawerList(c.id);
            // Foca no primeiro item após trocar grupo via clique
            setTimeout(() => {
              const first = document.querySelector('#tvEpgDrawer .tv-drawer-item');
              if (first) { first.focus(); first.scrollIntoView({ block: 'nearest' }); }
            }, 50);
          });
          catContainer.appendChild(btn);
        });
      }

      // Alterna o grupo de categorias da gaveta EPG com seta ← (delta=-1) ou → (delta=+1)
      function tvSwitchDrawerCategory(delta) {
        const catContainer = document.getElementById('tvDrawerCats');
        if (!catContainer) return;
        const pills = Array.from(catContainer.querySelectorAll('.tv-epg-cat-pill'));
        if (!pills.length) return;
        const activeIdx = pills.findIndex(p => p.classList.contains('active'));
        const nextIdx = (activeIdx + delta + pills.length) % pills.length;
        // Simula clique na pill alvo (aciona renderTvDrawerList + animação de foco)
        pills[nextIdx].click();
        // Anima a pill para dar feedback visual ao usuário
        pills[nextIdx].style.transform = 'scale(1.12)';
        setTimeout(() => { pills[nextIdx].style.transform = ''; }, 200);
      }

      function renderTvDrawerList(catId) {
        const listEl = document.getElementById('tvDrawerChannels');
        if (!listEl) return;
        listEl.innerHTML = '';
        const filtered = (catId === 'ALL') ? tvChannels : tvChannels.filter(c => c.categoryKey === catId);

        filtered.forEach(ch => {
          const item = document.createElement('div');
          const realIdx = tvChannels.indexOf(ch);
          const isCur = (realIdx === tvCurrentIdx);
          const epg = (typeof getChannelLiveSchedule === 'function') ? getChannelLiveSchedule(ch) : null;
          const curTitle = epg ? epg.nowTitle : (ch.nowTitle || 'Ao Vivo');
          item.className = `tv-drawer-item tv-focusable ${isCur ? 'active-channel' : ''}`;
          item.tabIndex = 0;
          item.innerHTML = `
        <span style="font-size:12px; font-weight:800; color:#ffc107; min-width:32px;">${String(realIdx + 1).padStart(3, '0')}</span>
        <img src="${escapeHtml(getTvLogoUrl(ch.logo, 100, 70) || getTvLogoFallback(ch.name))}" alt="" style="width:36px; height:24px; object-fit:contain;" loading="lazy" decoding="async">
        <div style="flex:1; overflow:hidden;">
          <div style="font-size:13px; font-weight:700; color:#fff; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(ch.name)}</div>
          <div style="font-size:10px; color:#ffc107; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(curTitle)}</div>
        </div>
        ${isCur ? '<span style="font-size:9px; font-weight:800; background:#00e676; color:#000; padding:2px 6px; border-radius:4px;">NO AR</span>' : ''}
      `;
          const drawerLogo = item.querySelector('img');
          if (drawerLogo) {
            drawerLogo.onerror = function () {
              this.onerror = null;
              this.src = getTvLogoFallback(ch.name);
            };
          }
          item.addEventListener('click', () => {
            tvTuneChannel(realIdx, true);
            tvDrawer.classList.add('closed');
            clearTvDrawerAutoClose();
          });
          item.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              tvTuneChannel(realIdx, true);
              tvDrawer.classList.add('closed');
              clearTvDrawerAutoClose();
            }
          });
          listEl.appendChild(item);
        });
      }

      // Renderiza Gaveta de Episódios/Temporadas para Séries em Tela Cheia
      async function renderTvSeriesDrawer(meta) {
        if (!meta) return;

        const drawerTitle = tvDrawer.querySelector('.tv-drawer-title');
        const drawerHint = tvDrawer.querySelector('.tv-drawer-hint');
        const catsEl = document.getElementById('tvDrawerCats');
        const listEl = document.getElementById('tvDrawerChannels');

        if (drawerTitle) drawerTitle.textContent = '[TV] ' + (meta.seriesItem.name || 'Serie');
        if (drawerHint) drawerHint.textContent = 'Temp: Esq/Dir  |  Episodio: Cima/Baixo  |  OK: Assistir';

        // Garante que os episódios estejam carregados
        let episodesBySeason = meta.episodesBySeason;
        if (!episodesBySeason) {
          if (listEl) listEl.innerHTML = '<div style="color:#ffc107;font-size:13px;padding:20px;text-align:center;">Carregando episodios...</div>';
          try {
            const data = await xtreamApi('get_series_info', '&series_id=' + meta.seriesId);
            episodesBySeason = data.episodes || {};
            meta.seriesItem._cachedEpisodes = episodesBySeason;
            meta.episodesBySeason = episodesBySeason;
            if (tvPlayingSeriesMeta) tvPlayingSeriesMeta.episodesBySeason = episodesBySeason;
          } catch (err) {
            if (listEl) listEl.innerHTML = '<div style="color:#e50914;padding:20px;">Erro ao carregar episodios.</div>';
            return;
          }
        }

        const seasons = Object.keys(episodesBySeason).sort(function (a, b) { return Number(a) - Number(b); });
        if (seasons.length === 0) {
          if (listEl) listEl.innerHTML = '<div style="color:#888;padding:20px;text-align:center;">Nenhum episodio encontrado.</div>';
          return;
        }

        let currentSeason = meta.seasonNum || seasons[0];
        if (seasons.indexOf(currentSeason) === -1) currentSeason = seasons[0];

        // Renderiza pills de temporada
        if (catsEl) {
          catsEl.style.display = 'flex';
          catsEl.innerHTML = '';
          seasons.forEach(function (sNum) {
            const pill = document.createElement('button');
            pill.className = 'tv-epg-cat-pill tv-focusable' + (sNum === currentSeason ? ' active' : '');
            pill.dataset.season = sNum;
            pill.textContent = 'T' + sNum;
            pill.addEventListener('click', function () {
              catsEl.querySelectorAll('.tv-epg-cat-pill').forEach(function (p) { p.classList.remove('active'); });
              pill.classList.add('active');
              currentSeason = sNum;
              renderEpisodeList(sNum);
              setTimeout(function () {
                const first = listEl.querySelector('.tv-drawer-item');
                if (first) { first.focus(); first.scrollIntoView({ block: 'nearest' }); }
              }, 50);
            });
            catsEl.appendChild(pill);
          });
        }

        function renderEpisodeList(sNum) {
          if (!listEl) return;
          listEl.innerHTML = '';
          const eps = episodesBySeason[sNum] || [];
          eps.forEach(function (ep) {
            const item = document.createElement('div');
            const isPlaying = (sNum === meta.seasonNum && ep.episode_num === meta.ep.episode_num);
            item.className = 'tv-drawer-item tv-focusable' + (isPlaying ? ' active-channel' : '');
            item.tabIndex = 0;
            const epTitle = ep.title || ('Episodio ' + ep.episode_num);
            const durSec = (ep.info && ep.info.duration_secs) ? Number(ep.info.duration_secs) : 0;
            const durMin = durSec > 0 ? (Math.round(durSec / 60) + ' min') : ((ep.info && ep.info.duration) ? ep.info.duration : '');
            const epNum = String(ep.episode_num).padStart(2, '0');
            const durHTML = durMin ? ('<div style="font-size:10px;color:#aaa;">' + durMin + '</div>') : '';
            const playBadge = isPlaying ? '<span style="font-size:9px;font-weight:900;background:#00e676;color:#000;padding:2px 7px;border-radius:4px;white-space:nowrap;">ASSISTINDO</span>' : '';
            item.innerHTML =
              '<span style="font-size:11px;font-weight:900;color:#ffc107;min-width:30px;">E' + epNum + '</span>' +
              '<div style="flex:1;overflow:hidden;">' +
              '<div style="font-size:13px;font-weight:700;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + escapeHtml(epTitle) + '</div>' +
              durHTML +
              '</div>' +
              playBadge;
            item.addEventListener('click', function () {
              const updated = Object.assign({}, meta.seriesItem, { _cachedEpisodes: episodesBySeason });
              tvPlaySeriesEpisode(updated, ep, sNum);
            });
            item.addEventListener('keydown', function (ev) {
              if (ev.key === 'Enter') { ev.preventDefault(); const updated = Object.assign({}, meta.seriesItem, { _cachedEpisodes: episodesBySeason }); tvPlaySeriesEpisode(updated, ep, sNum); }
            });
            listEl.appendChild(item);
          });
        }

        renderEpisodeList(currentSeason);
      }

      // Encerra imediatamente o stream da Central ao entrar no VOD, economizando banda
      // e mantendo o comportamento do APK (que destrói a conexão ao abrir Filmes/Séries).
      function tvDestroyCurrentStream() {
        stopWatchTimeTracking();
        if (window._tvActiveHls) {
          try { window._tvActiveHls.destroy(); } catch (e) { }
          window._tvActiveHls = null;
        }

        if (tvVideo) {
          try { tvVideo.pause(); } catch (e) { }
          tvVideo.onerror = null;
          tvVideo.removeAttribute('src');
          try { tvVideo.load(); } catch (e) { }
        }

        if (tvEmbed) {
          tvEmbed.onerror = null;
          tvEmbed.src = 'about:blank';
        }
      }

      // Navegação da Central (Ações Rápidas)
      document.addEventListener('DOMContentLoaded', () => {
        // Clique no PiP -> Tela Cheia
        const pipCard = document.getElementById('tvPipCard');
        if (pipCard) {
          pipCard.addEventListener('click', () => setTvViewMode('fullscreen'));
          pipCard.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') setTvViewMode('fullscreen');
          });
        }

        // Botões de Ação da Central (Enter é acionado de forma unificada pelo keydown da central)
        const btnSports = document.getElementById('tvNavSports');
        if (btnSports) {
          btnSports.addEventListener('click', () => {
            const rail = document.getElementById('tvSportsRail');
            if (rail) {
              const first = rail.querySelector('.tv-match-card');
              if (first) {
                first.focus();
                first.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' });
              }
            }
          });
        }

        const btnMovies = document.getElementById('tvNavMovies');
        if (btnMovies) {
          btnMovies.addEventListener('click', () => {
            tvDestroyCurrentStream();
            openTvVodExplorer('movies');
          });
        }

        const btnSeries = document.getElementById('tvNavSeries');
        if (btnSeries) {
          btnSeries.addEventListener('click', () => {
            tvDestroyCurrentStream();
            openTvVodExplorer('series');
          });
        }

        const btnEpg = document.getElementById('tvNavEpg');
        if (btnEpg) {
          btnEpg.addEventListener('click', () => {
            setTvViewMode('fullscreen');
            toggleTvDrawer();
          });
        }

        const vodBackBtn = document.getElementById('tvVodBack');
        if (vodBackBtn) {
          vodBackBtn.addEventListener('click', () => {
            if (tvVodMode === 'series_episodes') {
              openTvVodExplorer('series');
            } else {
              setTvViewMode('central');
            }
          });
          vodBackBtn.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              if (tvVodMode === 'series_episodes') {
                openTvVodExplorer('series');
              } else {
                setTvViewMode('central');
              }
            }
          });
        }
      });

      // VOD Explorer (Filmes & Séries com Filtro por Categoria)
      let _tvVodCurrentCat = 'ALL';
      let _tvVodLoadToken = 0;

      async function openTvVodExplorer(type, targetCatId = 'ALL') {
        const currentToken = ++_tvVodLoadToken;
        tvVodMode = type;
        tvPlayingSeriesMeta = null;
        tvCurrentSeriesItem = null;
        _tvVodCurrentCat = targetCatId;

        const backBtn = document.getElementById('tvVodBack');
        if (backBtn) backBtn.textContent = '← VOLTAR À CENTRAL';

        const titleEl = document.getElementById('tvVodSectionTitle');
        if (titleEl) titleEl.textContent = (type === 'movies') ? '🎬 Filmes' : '📺 Séries';
        setTvViewMode('vod');

        const container = document.getElementById('tvVodRailsContainer');
        if (!container) return;
        container.innerHTML = `<div style="color:#ffc107; font-size:16px; font-weight:700; padding:40px; text-align:center;"><div class="spinner" style="margin: 0 auto 15px;"></div>Carregando catálogo de ${type === 'movies' ? 'filmes' : 'séries'}...</div>`;

        let allItems = (type === 'movies') ? fullMoviesCache : fullSeriesCache;
        if (!allItems || allItems.length === 0) {
          try {
            if (type === 'movies') {
              if (!movieCategories || movieCategories.length === 0) await loadMovieCategories();
              allItems = await loadFullMovies();
            } else {
              if (!seriesCategories || seriesCategories.length === 0) await loadSeriesCategories();
              allItems = await loadFullSeries();
            }
          } catch (err) {
            if (currentToken !== _tvVodLoadToken) return;
            container.innerHTML = `<div style="color:#e50914; padding:30px; text-align:center;">Erro ao carregar catálogo: ${err.message}<br><button class="tv-vod-back-btn tv-focusable" style="margin-top:14px;" data-open-tv-vod="${type}">Tentar Novamente</button></div>`;
            return;
          }
        }

        if (currentToken !== _tvVodLoadToken) return;

        if (!allItems || allItems.length === 0) {
          container.innerHTML = `<div style="color:#888; font-size:15px; padding:30px; text-align:center;">Nenhum título encontrado no catálogo.<br><button class="tv-vod-back-btn tv-focusable" style="margin-top:14px;" data-open-tv-vod="${type}">Tentar Novamente</button></div>`;
          return;
        }

        renderTvVodContent(type, allItems, _tvVodCurrentCat);
      }

      function renderTvVodContent(type, allItems, activeCatId) {
        const container = document.getElementById('tvVodRailsContainer');
        if (!container) return;
        container.innerHTML = '';

        const categories = (type === 'movies') ? (movieCategories || []) : (seriesCategories || []);
        const validCategories = (Array.isArray(categories) ? categories : []).filter(c => c && c.category_name && !String(c.category_name).toLowerCase().includes('demo'));

        // 1. Barra de Categorias Horizontal (Pills para Controle Remoto e Toque)
        const catBar = document.createElement('div');
        catBar.className = 'tv-vod-category-bar';
        catBar.id = 'tvVodCategoryBar';

        const allPill = document.createElement('button');
        allPill.className = `tv-vod-cat-pill tv-focusable ${activeCatId === 'ALL' ? 'active' : ''}`;
        allPill.dataset.catId = 'ALL';
        allPill.tabIndex = 0;
        allPill.textContent = '🌟 Todas as Categorias';
        allPill.addEventListener('click', () => {
          _tvVodCurrentCat = 'ALL';
          renderTvVodContent(type, allItems, 'ALL');
        });
        catBar.appendChild(allPill);

        validCategories.forEach(cat => {
          const cId = String(cat.category_id);
          const pill = document.createElement('button');
          pill.className = `tv-vod-cat-pill tv-focusable ${activeCatId === cId ? 'active' : ''}`;
          pill.dataset.catId = cId;
          pill.tabIndex = 0;
          pill.textContent = (cat.category_name || '').replace('⚡', '').trim() || 'Categoria';
          pill.addEventListener('click', () => {
            _tvVodCurrentCat = cId;
            renderTvVodContent(type, allItems, cId);
          });
          catBar.appendChild(pill);
        });

        container.appendChild(catBar);

        // Atualiza seletor dropdown na barra superior
        const catSelect = document.getElementById('tvVodCatSelect');
        if (catSelect) {
          catSelect.style.display = 'inline-block';
          catSelect.innerHTML = '<option value="ALL">🌟 Todas as Categorias</option>';
          validCategories.forEach(cat => {
            const opt = document.createElement('option');
            opt.value = String(cat.category_id);
            opt.textContent = (cat.category_name || '').replace('⚡', '').trim() || 'Categoria';
            catSelect.appendChild(opt);
          });
          catSelect.value = activeCatId;
          catSelect.onchange = (e) => {
            _tvVodCurrentCat = e.target.value;
            renderTvVodContent(type, allItems, e.target.value);
          };
        }

        // 2. Filtra itens pela categoria selecionada
        const filtered = (activeCatId === 'ALL') ? allItems : allItems.filter(item => {
          if (Array.isArray(item.category_ids)) {
            return item.category_ids.some(cid => String(cid) === activeCatId);
          }
          return String(item.category_id) === activeCatId;
        });

        if (!filtered || filtered.length === 0) {
          const emptyDiv = document.createElement('div');
          emptyDiv.style.cssText = 'color:#888; font-size:15px; padding:40px; text-align:center;';
          emptyDiv.textContent = 'Nenhum título encontrado nesta categoria.';
          container.appendChild(emptyDiv);
          return;
        }

        // 3. Renderiza Grade de Posters VOD (até 120 itens para fluidez máxima)
        const grid = document.createElement('div');
        grid.className = 'tv-vod-grid';

        filtered.slice(0, 120).forEach((item, i) => {
          const card = document.createElement('div');
          card.className = 'tv-vod-poster-card tv-focusable';
          card.tabIndex = 0;
          const poster = item.poster || item.stream_icon || item.cover || '';
          const safeTitle = (item.name || item.title || 'Sem título');
          const ratingNum = parseFloat(String(item.rating || '').replace(',', '.'));
          const safeRating = (!isNaN(ratingNum) && ratingNum > 0) ? ratingNum.toFixed(1) : '7.5';

          const img = document.createElement('img');
          img.src = poster;
          img.alt = safeTitle;
          img.loading = 'lazy';
          img.decoding = 'async';
          img.onerror = function () { this.style.opacity = '0.2'; };
          card.appendChild(img);

          const infoDiv = document.createElement('div');
          infoDiv.className = 'tv-vod-card-info';
          const nameDiv = document.createElement('div');
          nameDiv.className = 'tv-vod-card-name';
          nameDiv.textContent = safeTitle;
          const yearDiv = document.createElement('div');
          yearDiv.className = 'tv-vod-card-year';
          yearDiv.textContent = `${item.year || ''} ★ ${safeRating}`;
          infoDiv.appendChild(nameDiv);
          infoDiv.appendChild(yearDiv);
          card.appendChild(infoDiv);

          card.addEventListener('focus', () => updateTvVodPreview(item));

          card.addEventListener('click', () => {
            if (type === 'movies') tvPlayMovie(item);
            else openTvSeriesDetail(item);
          });
          card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              if (type === 'movies') tvPlayMovie(item);
              else openTvSeriesDetail(item);
            }
          });

          grid.appendChild(card);
        });

        container.appendChild(grid);
        if (filtered[0]) updateTvVodPreview(filtered[0]);

        // Foca na pill ativa ou no primeiro card
        setTimeout(() => {
          const activePill = catBar.querySelector('.tv-vod-cat-pill.active');
          if (activePill && activeCatId !== 'ALL') {
            activePill.scrollIntoView({ inline: 'center', behavior: 'smooth' });
          }
          const firstCard = container.querySelector('.tv-vod-poster-card');
          if (firstCard) {
            firstCard.focus();
            firstCard.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          }
        }, 100);
      }

      function updateTvVodPreview(item) {
        if (!item) return;
        const title = document.getElementById('tvVodPreviewTitle');
        const meta = document.getElementById('tvVodPreviewMeta');
        const plot = document.getElementById('tvVodPreviewPlot');
        const poster = document.getElementById('tvVodPreviewPoster');
        const watchBtn = document.getElementById('tvVodHeroWatchBtn');

        if (title) title.textContent = item.name || item.title || '';
        if (meta) {
          const ratingNum = parseFloat(String(item.rating || '').replace(',', '.'));
          const ratingBadge = (!isNaN(ratingNum) && ratingNum > 0) ? `★ ${ratingNum.toFixed(1)}` : '';
          const badges = [
            item.year ? `Ano: ${item.year}` : '',
            ratingBadge,
            item.genre || ''
          ].filter(Boolean);
          meta.innerHTML = badges.map(b => `<span class="tv-meta-badge">${b}</span>`).join(' ');
        }
        if (plot) plot.textContent = item.plot || 'Sinopse não disponível para este título.';
        if (poster) {
          poster.src = item.poster || item.stream_icon || item.cover || '';
          poster.style.display = poster.src ? 'block' : 'none';
        }
        if (watchBtn) {
          if (tvVodMode === 'series_episodes') {
            watchBtn.style.display = 'none';
          } else {
            watchBtn.style.display = 'inline-block';
            watchBtn.textContent = (tvVodMode === 'series') ? '▶ VER EPISÓDIOS (OK)' : '▶ ASSISTIR (OK)';
            watchBtn.onclick = () => {
              if (tvVodMode === 'movies') tvPlayMovie(item);
              else if (tvVodMode === 'series') openTvSeriesDetail(item);
            };
          }
        }
      }


      // ============================================================
      // CONTROLE REMOTO / TECLADO COMPLETO (Setas, Enter, Backspace, Ctrl)
      // Funciona tanto no App Android quanto no navegador web (modo TV)
      // ============================================================
      window.addEventListener('keydown', (e) => {
        if (!isTvMode) return;
        const key = e.key;

        // ---- Tecla auxiliar: Ctrl = Menu/Guia ----
        if (key === 'Control') {
          e.preventDefault();
          if (tvCurrentMode === 'fullscreen') {
            toggleTvDrawer();
          } else if (tvCurrentMode === 'central') {
            // Ctrl na central: foca no PiP
            const pip = document.getElementById('tvPipCard');
            if (pip) pip.focus();
          } else if (tvCurrentMode === 'vod') {
            const back = document.getElementById('tvVodBack');
            if (back) back.focus();
          }
          return;
        }

        // ---- Backspace / Escape / Botão Voltar ----
        const isBackKey = (key === 'Backspace' || key === 'Escape' || key === 'Back' || key === 'GoBack' || key === 'BrowserBack' || e.keyCode === 4 || e.keyCode === 27 || e.keyCode === 111 || e.keyCode === 8);
        if (isBackKey) {
          e.preventDefault();
          if (tvCurrentMode === 'fullscreen') {
            // Se estava assistindo filme ou série VOD
            if (tvPlayingType === 'vod') {
              // Prioridade 1: fechar gaveta de episódios/canais se aberta
              if (tvDrawer && !tvDrawer.classList.contains('closed')) {
                tvDrawer.classList.add('closed');
                clearTvDrawerAutoClose();
                const kc = document.getElementById('tvKeyCapture');
                if (kc) setTimeout(() => kc.focus({ preventScroll: true }), 50);
                return;
              }
              // Prioridade 2: voltar para VOD explorer
              if (tvVideo) {
                tvVideo.pause();
                tvVideo.src = '';
              }
              setTvViewMode(tvPreviousMode || 'vod');
              if (tvPreviousMode === 'central') tvTuneChannel(tvCurrentIdx, false);
              return;
            }
            // Se estava assistindo evento esportivo
            if (tvPlayingType === 'event') {
              setTvViewMode('central');
              tvTuneChannel(tvCurrentIdx, false);
              return;
            }
            // Modo TV ao vivo: prioridade 1 = fechar gaveta EPG se aberta
            if (tvDrawer && !tvDrawer.classList.contains('closed')) {
              tvDrawer.classList.add('closed');
              clearTvDrawerAutoClose();
              const kc = document.getElementById('tvKeyCapture');
              if (kc) setTimeout(() => kc.focus({ preventScroll: true }), 50);
              return;
            }
            // Prioridade 2: cancelar zap pendente (sem sair da tela)
            if (tvZapDebounceTimer) {
              tvCancelPendingZap();
              return;
            }
            // Prioridade 3: voltar à central
            setTvViewMode('central');
            return;
          } else if (tvCurrentMode === 'vod') {
            // Se estiver dentro da grade de episódios de uma série, volta para a lista de séries
            if (tvVodMode === 'series_episodes') {
              openTvVodExplorer('series');
            } else {
              setTvViewMode('central');
            }
            return;
          }
          return;
        }

        // ====================================================
        // TELA CHEIA (Player Unificado: TV / Eventos / VOD)
        // ====================================================
        if (tvCurrentMode === 'fullscreen') {
          // 1. Reprodução de Filmes / Séries (VOD): controle de reprodução nativo
          if (tvPlayingType === 'vod') {
            // Gaveta de episódios aberta: delega para o bloco de gaveta abaixo
            if (tvDrawer && !tvDrawer.classList.contains('closed')) {
              // Deixa cair para o bloco de gaveta aberta
            } else {
              // Enter: se OSD oculto → mostra OSD; se OSD visível e série → abre gaveta; senão pausa/play
              if (key === 'Enter') {
                e.preventDefault();
                if (tvOsd && tvOsd.classList.contains('hidden')) {
                  triggerTvOsd(5000);
                } else if (tvPlayingSeriesMeta) {
                  toggleTvDrawer();
                } else {
                  if (tvVideo) {
                    if (tvVideo.paused) tvVideo.play(); else tvVideo.pause();
                    triggerTvOsd(3000);
                  }
                }
                return;
              }
              if (key === ' ') {
                e.preventDefault();
                if (tvVideo) {
                  if (tvVideo.paused) tvVideo.play(); else tvVideo.pause();
                  triggerTvOsd(3000);
                }
                return;
              }
              if (key === 'ArrowRight') {
                e.preventDefault();
                if (tvVideo) {
                  tvVideo.currentTime = Math.min(tvVideo.duration || 999999, tvVideo.currentTime + 10);
                  triggerTvOsd(3000);
                }
                return;
              }
              if (key === 'ArrowLeft') {
                e.preventDefault();
                if (tvVideo) {
                  tvVideo.currentTime = Math.max(0, tvVideo.currentTime - 10);
                  triggerTvOsd(3000);
                }
                return;
              }
              if (key === 'ArrowUp' || key === 'ArrowDown' || key === 'i' || key === 'I' || key === 'Info' || key === 'Guide') {
                e.preventDefault();
                triggerTvOsd(5000);
                return;
              }
              return;
            }
          }

          // 2. Transmissão de Evento Esportivo
          if (tvPlayingType === 'event') {
            if (key === 'ArrowUp' || key === 'ArrowDown' || key === 'i' || key === 'I' || key === 'Info' || key === 'Guide') {
              e.preventDefault();
              triggerTvOsd(5000);
              return;
            }
            if (key === 'Enter' || key === ' ') {
              e.preventDefault();
              triggerTvOsd(5000);
              return;
            }
            return;
          }

          // 3. Canais de TV Ao Vivo (com EPG lateral e Zapping)
          if (tvDrawer && !tvDrawer.classList.contains('closed')) {
            // Qualquer atividade dentro da gaveta reinicia a contagem de 8s de inatividade
            triggerTvDrawerAutoClose(8000);

            const items = Array.from(tvDrawer.querySelectorAll('.tv-drawer-item'));
            const focused = document.activeElement;
            const curIdx = items.indexOf(focused);

            if (key === 'ArrowDown') {
              e.preventDefault();
              if (curIdx < items.length - 1) {
                items[curIdx + 1].focus();
                items[curIdx + 1].scrollIntoView({ block: 'nearest' });
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              if (curIdx > 0) {
                items[curIdx - 1].focus();
                items[curIdx - 1].scrollIntoView({ block: 'nearest' });
              } else {
                const cats = Array.from(tvDrawer.querySelectorAll('.tv-epg-cat-pill'));
                if (cats.length) cats[0].focus();
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              tvSwitchDrawerCategory(-1);
              return;
            }
            if (key === 'ArrowRight') {
              e.preventDefault();
              tvSwitchDrawerCategory(1);
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              if (focused && focused.classList.contains('tv-drawer-item')) focused.click();
              return;
            }
            return;
          }

          // Drawer fechada: zapping clássico de TV a Cabo
          if (key === 'ArrowUp' || key === 'ChannelUp') {
            e.preventDefault();
            tvZap(-1);
            return;
          }
          if (key === 'ArrowDown' || key === 'ChannelDown') {
            e.preventDefault();
            tvZap(1);
            return;
          }
          if (key === 'ArrowLeft') {
            e.preventDefault();
            tvZap(-1);
            return;
          }
          if (key === 'ArrowRight') {
            e.preventDefault();
            tvZap(1);
            return;
          }
          if (key === 'Enter' || key === ' ') {
            e.preventDefault();
            if (tvZapDebounceTimer) {
              clearTimeout(tvZapDebounceTimer);
              tvCommitPendingChannel();
              return;
            }
            if (tvOsd && tvOsd.classList.contains('hidden')) {
              triggerTvOsd(6000);
            } else {
              toggleTvDrawer();
            }
            return;
          }
          if (key === 'i' || key === 'I' || key === 'Info' || key === 'Guide') {
            e.preventDefault();
            if (tvOsd && !tvOsd.classList.contains('hidden')) {
              tvOsd.classList.add('hidden');
              const topBadge = document.getElementById('tvTopChannelBadge');
              if (topBadge) topBadge.classList.add('hidden');
              const floatBackBtn4 = document.getElementById('tvFloatingBackBtn');
              if (floatBackBtn4) floatBackBtn4.classList.add('hidden');
              if (tvOsdTimer) { clearTimeout(tvOsdTimer); tvOsdTimer = null; }
            } else {
              triggerTvOsd(7000);
            }
            return;
          }
        }

        // ====================================================
        // CENTRAL (Hub Principal - Navegação D-Pad em Grid 2D)
        // Zonas: [pip] | [menu-grid 2×3] → [rail canais] → [rail esportes] → [rail filmes]
        // ====================================================
        if (tvCurrentMode === 'central') {
          const focused = document.activeElement;

          const getCards = (railId, selector) => Array.from(document.querySelectorAll(`#${railId} ${selector}`));

          const pip = document.getElementById('tvPipCard');
          const menuIds = ['tvNavMovies', 'tvNavSeries', 'tvNavSports', 'tvNavEpg'];
          const menuItems = menuIds.map(id => document.getElementById(id)).filter(Boolean);
          const menuIdx = menuItems.indexOf(focused);

          const chCards = getCards('tvChannelsRail', '.tv-channel-card');
          const spCards = getCards('tvSportsRail', '.tv-match-card');
          const mvCards = getCards('tvMoviesRail', '.tv-vod-card');
          const svCards = getCards('tvSeriesRail', '.tv-vod-card');

          const chIdx = chCards.indexOf(focused);
          const spIdx = spCards.indexOf(focused);
          const mvIdx = mvCards.indexOf(focused);
          const svIdx = svCards.indexOf(focused);

          const scrollCentralToTop = () => {
            if (!tvCentral) return;
            tvCentral.scrollTo({ top: 0, behavior: 'smooth' });
            setTimeout(syncTvPlayerBoxPosition, 60);
            setTimeout(syncTvPlayerBoxPosition, 160);
            setTimeout(syncTvPlayerBoxPosition, 320);
            setTimeout(syncTvPlayerBoxPosition, 500);
          };

          const scrollCardIntoView = (el, block = 'center', inline = 'center') => {
            if (!el) return;
            el.scrollIntoView({ block, inline, behavior: 'smooth' });
            setTimeout(syncTvPlayerBoxPosition, 60);
            setTimeout(syncTvPlayerBoxPosition, 160);
            setTimeout(syncTvPlayerBoxPosition, 320);
          };

          // 1. Zona PiP
          if (focused === pip || (pip && pip.contains(focused))) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (menuItems.length) {
                menuItems[0].focus();
                scrollCentralToTop();
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (chCards.length) {
                chCards[0].focus();
                scrollCardIntoView(chCards[0], 'center', 'nearest');
              } else if (spCards.length) {
                spCards[0].focus();
                scrollCardIntoView(spCards[0], 'center', 'nearest');
              }
              return;
            }
            if (key === 'ArrowUp' || key === 'ArrowLeft') {
              e.preventDefault();
              scrollCentralToTop();
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              setTvViewMode('fullscreen');
              return;
            }
            return;
          }

          // 2. Zona Menu Grid (4 cards 2x2, mesma disposição da Central do APK)
          if (menuIdx !== -1) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (menuIdx === 0 && menuItems[1]) menuItems[1].focus();
              else if (menuIdx === 2 && menuItems[3]) menuItems[3].focus();
              scrollCentralToTop();
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (menuIdx === 1 && menuItems[0]) menuItems[0].focus();
              else if (menuIdx === 3 && menuItems[2]) menuItems[2].focus();
              else if ((menuIdx === 0 || menuIdx === 2) && pip) pip.focus();
              scrollCentralToTop();
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (menuIdx === 0 && menuItems[2]) {
                menuItems[2].focus();
                scrollCentralToTop();
              } else if (menuIdx === 1 && menuItems[3]) {
                menuItems[3].focus();
                scrollCentralToTop();
              } else if (chCards.length) {
                const targetCh = Math.min(menuIdx, chCards.length - 1);
                chCards[targetCh].focus();
                scrollCardIntoView(chCards[targetCh], 'center', 'nearest');
              } else if (spCards.length) {
                spCards[0].focus();
                scrollCardIntoView(spCards[0], 'center', 'nearest');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              if (menuIdx === 2 && menuItems[0]) {
                menuItems[0].focus();
                scrollCentralToTop();
              } else if (menuIdx === 3 && menuItems[1]) {
                menuItems[1].focus();
                scrollCentralToTop();
              } else if (pip) {
                pip.focus();
                scrollCentralToTop();
              } else {
                scrollCentralToTop();
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          // 3. Zona Trilho de Canais
          if (chIdx !== -1) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (chIdx + 1 < chCards.length) {
                chCards[chIdx + 1].focus();
                scrollCardIntoView(chCards[chIdx + 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (chIdx > 0) {
                chCards[chIdx - 1].focus();
                scrollCardIntoView(chCards[chIdx - 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              // Sobe para o mesmo slot da grade superior (Filmes, Séries, Jogos, Guia).
              const targetMenu = menuItems[Math.min(chIdx, menuItems.length - 1)] || menuItems[0] || pip;
              if (targetMenu) {
                targetMenu.focus();
                scrollCentralToTop();
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              // Desce para o trilho de esportes
              if (spCards.length) {
                const targetSp = Math.min(chIdx, spCards.length - 1);
                spCards[targetSp].focus();
                scrollCardIntoView(spCards[targetSp], 'center', 'nearest');
              } else if (mvCards.length) {
                const targetMv = Math.min(chIdx, mvCards.length - 1);
                mvCards[targetMv].focus();
                scrollCardIntoView(mvCards[targetMv], 'center', 'nearest');
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          // 4. Zona Trilho de Esportes
          if (spIdx !== -1) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (spIdx + 1 < spCards.length) {
                spCards[spIdx + 1].focus();
                scrollCardIntoView(spCards[spIdx + 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (spIdx > 0) {
                spCards[spIdx - 1].focus();
                scrollCardIntoView(spCards[spIdx - 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              // Sobe para o trilho de canais
              if (chCards.length) {
                const targetCh = Math.min(spIdx, chCards.length - 1);
                chCards[targetCh].focus();
                scrollCardIntoView(chCards[targetCh], 'center', 'nearest');
              } else {
                scrollCentralToTop();
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              // Desce para o trilho de filmes.
              if (mvCards.length) {
                const targetMv = Math.min(spIdx, mvCards.length - 1);
                mvCards[targetMv].focus();
                scrollCardIntoView(mvCards[targetMv], 'center', 'nearest');
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          // 5. Zona Trilho de Filmes
          if (mvIdx !== -1) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (mvIdx + 1 < mvCards.length) {
                mvCards[mvIdx + 1].focus();
                scrollCardIntoView(mvCards[mvIdx + 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (mvIdx > 0) {
                mvCards[mvIdx - 1].focus();
                scrollCardIntoView(mvCards[mvIdx - 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              // Sobe para o trilho de esportes
              if (spCards.length) {
                const targetSp = Math.min(mvIdx, spCards.length - 1);
                spCards[targetSp].focus();
                scrollCardIntoView(spCards[targetSp], 'center', 'nearest');
              } else if (chCards.length) {
                const targetCh = Math.min(mvIdx, chCards.length - 1);
                chCards[targetCh].focus();
                scrollCardIntoView(chCards[targetCh], 'center', 'nearest');
              } else {
                scrollCentralToTop();
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              // Desce dos filmes para o trilho de séries, replicando a sequência vertical da Central do APK.
              if (svCards.length) {
                const targetSv = Math.min(mvIdx, svCards.length - 1);
                svCards[targetSv].focus();
                scrollCardIntoView(svCards[targetSv], 'center', 'nearest');
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          // 6. Zona Trilho de Séries
          if (svIdx !== -1) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (svIdx + 1 < svCards.length) {
                svCards[svIdx + 1].focus();
                scrollCardIntoView(svCards[svIdx + 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (svIdx > 0) {
                svCards[svIdx - 1].focus();
                scrollCardIntoView(svCards[svIdx - 1], 'nearest', 'center');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              if (mvCards.length) {
                const targetMv = Math.min(svIdx, mvCards.length - 1);
                mvCards[targetMv].focus();
                scrollCardIntoView(mvCards[targetMv], 'center', 'nearest');
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          // Fallback: qualquer seta posiciona foco no PiP e puxa para o topo
          if (key === 'ArrowUp' || key === 'ArrowDown' || key === 'ArrowLeft' || key === 'ArrowRight') {
            e.preventDefault();
            if (pip) {
              pip.focus();
              scrollCentralToTop();
            }
            return;
          }
        }

        // ====================================================
        // VOD (Grade de Filmes / Séries / Episódios)
        // ====================================================
        if (tvCurrentMode === 'vod') {
          const focused = document.activeElement;
          const backBtn = document.getElementById('tvVodBack');
          const watchBtn = document.getElementById('tvVodHeroWatchBtn');

          const scrollVodToTop = () => {
            if (tvVod) tvVod.scrollTo({ top: 0, behavior: 'smooth' });
          };

          const scrollVodCard = (el, block = 'nearest') => {
            if (!el) return;
            el.scrollIntoView({ block, inline: 'nearest', behavior: 'smooth' });
          };

          // 1. Modo Episódios de Séries
          if (tvVodMode === 'series_episodes') {
            const seasonBtns = Array.from(document.querySelectorAll('#tvSeriesSeasonsBar .tv-series-season-btn'));
            const epCards = Array.from(document.querySelectorAll('#tvSeriesEpisodesGrid .tv-vod-ep-card'));
            const sIdx = seasonBtns.indexOf(focused);
            const epIdx = epCards.indexOf(focused);

            if (focused === backBtn) {
              scrollVodToTop();
              if (key === 'ArrowDown' || key === 'ArrowRight') {
                e.preventDefault();
                if (seasonBtns.length) seasonBtns[0].focus();
                else if (epCards.length) {
                  epCards[0].focus();
                  scrollVodCard(epCards[0], 'nearest');
                }
                return;
              }
              if (key === 'Enter') {
                e.preventDefault();
                openTvVodExplorer('series');
                return;
              }
              return;
            }

            if (sIdx !== -1) {
              if (key === 'ArrowRight') {
                e.preventDefault();
                if (sIdx + 1 < seasonBtns.length) seasonBtns[sIdx + 1].focus();
                return;
              }
              if (key === 'ArrowLeft') {
                e.preventDefault();
                if (sIdx > 0) {
                  seasonBtns[sIdx - 1].focus();
                } else if (backBtn) {
                  backBtn.focus();
                  scrollVodToTop();
                }
                return;
              }
              if (key === 'ArrowDown') {
                e.preventDefault();
                if (epCards.length) {
                  epCards[0].focus();
                  scrollVodCard(epCards[0], 'nearest');
                }
                return;
              }
              if (key === 'ArrowUp') {
                e.preventDefault();
                if (backBtn) {
                  backBtn.focus();
                  scrollVodToTop();
                }
                return;
              }
              if (key === 'Enter') {
                e.preventDefault();
                focused.click();
                return;
              }
              return;
            }

            if (epIdx !== -1) {
              let cols = 3;
              const grid = document.getElementById('tvSeriesEpisodesGrid');
              if (grid && epCards.length > 0) {
                const cardW = epCards[0].offsetWidth || 220;
                const gap = 16;
                cols = Math.max(1, Math.floor((grid.clientWidth + gap) / (cardW + gap)));
              }

              if (key === 'ArrowRight') {
                e.preventDefault();
                if (epIdx + 1 < epCards.length) {
                  epCards[epIdx + 1].focus();
                  scrollVodCard(epCards[epIdx + 1], 'nearest');
                }
                return;
              }
              if (key === 'ArrowLeft') {
                e.preventDefault();
                if (epIdx > 0) {
                  epCards[epIdx - 1].focus();
                  scrollVodCard(epCards[epIdx - 1], 'nearest');
                } else if (seasonBtns.length) {
                  seasonBtns[0].focus();
                  scrollVodToTop();
                } else if (backBtn) {
                  backBtn.focus();
                  scrollVodToTop();
                }
                return;
              }
              if (key === 'ArrowDown') {
                e.preventDefault();
                if (epIdx + cols < epCards.length) {
                  epCards[epIdx + cols].focus();
                  scrollVodCard(epCards[epIdx + cols], 'nearest');
                }
                return;
              }
              if (key === 'ArrowUp') {
                e.preventDefault();
                if (epIdx - cols >= 0) {
                  epCards[epIdx - cols].focus();
                  scrollVodCard(epCards[epIdx - cols], 'nearest');
                } else if (seasonBtns.length) {
                  const activeS = seasonBtns.find(b => b.classList.contains('active')) || seasonBtns[0];
                  activeS.focus();
                  scrollVodToTop();
                } else if (backBtn) {
                  backBtn.focus();
                  scrollVodToTop();
                }
                return;
              }
              if (key === 'Enter') {
                e.preventDefault();
                focused.click();
                return;
              }
              return;
            }

            // Fallback no modo episódios
            if (key === 'ArrowDown' || key === 'ArrowRight' || key === 'ArrowLeft' || key === 'ArrowUp') {
              e.preventDefault();
              if (epCards.length) {
                epCards[0].focus();
                scrollVodCard(epCards[0], 'nearest');
              } else if (backBtn) {
                backBtn.focus();
                scrollVodToTop();
              }
              return;
            }
          }

          // 2. Modo Catálogo Geral (Filmes ou Séries)
          const vodCards = Array.from(document.querySelectorAll('#tvVodRailsContainer .tv-vod-poster-card'));
          const catPills = Array.from(document.querySelectorAll('#tvVodRailsContainer .tv-vod-cat-pill'));
          const vodIdx = vodCards.indexOf(focused);
          const catIdx = catPills.indexOf(focused);

          if (focused === backBtn) {
            scrollVodToTop();
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (watchBtn && watchBtn.style.display !== 'none') watchBtn.focus();
              else if (catPills.length) catPills[0].focus();
              else if (vodCards.length) {
                vodCards[0].focus();
                scrollVodCard(vodCards[0], 'nearest');
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (catPills.length) catPills[0].focus();
              else if (vodCards.length) {
                vodCards[0].focus();
                scrollVodCard(vodCards[0], 'nearest');
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              setTvViewMode('central');
              return;
            }
            return;
          }

          if (focused === watchBtn) {
            scrollVodToTop();
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (backBtn) {
                backBtn.focus();
                scrollVodToTop();
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (catPills.length) catPills[0].focus();
              else if (vodCards.length) {
                vodCards[0].focus();
                scrollVodCard(vodCards[0], 'nearest');
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          if (catIdx !== -1) {
            if (key === 'ArrowRight') {
              e.preventDefault();
              if (catIdx + 1 < catPills.length) {
                catPills[catIdx + 1].focus();
                catPills[catIdx + 1].scrollIntoView({ inline: 'center', behavior: 'smooth' });
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (catIdx > 0) {
                catPills[catIdx - 1].focus();
                catPills[catIdx - 1].scrollIntoView({ inline: 'center', behavior: 'smooth' });
              } else if (backBtn) {
                backBtn.focus();
                scrollVodToTop();
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (vodCards.length) {
                vodCards[0].focus();
                scrollVodCard(vodCards[0], 'nearest');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              if (watchBtn && watchBtn.style.display !== 'none') {
                watchBtn.focus();
                scrollVodToTop();
              } else if (backBtn) {
                backBtn.focus();
                scrollVodToTop();
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          if (vodIdx !== -1) {
            let cols = 5;
            const grid = document.querySelector('#tvVodRailsContainer .tv-vod-grid');
            if (grid && vodCards.length > 0) {
              const cardW = vodCards[0].offsetWidth || 140;
              const gap = 16;
              cols = Math.max(1, Math.floor((grid.clientWidth + gap) / (cardW + gap)));
            }

            if (key === 'ArrowRight') {
              e.preventDefault();
              if (vodIdx + 1 < vodCards.length) {
                vodCards[vodIdx + 1].focus();
                scrollVodCard(vodCards[vodIdx + 1], 'nearest');
              }
              return;
            }
            if (key === 'ArrowLeft') {
              e.preventDefault();
              if (vodIdx > 0) {
                vodCards[vodIdx - 1].focus();
                scrollVodCard(vodCards[vodIdx - 1], 'nearest');
              } else {
                const activePill = catPills.find(p => p.classList.contains('active')) || catPills[0];
                if (activePill) {
                  activePill.focus();
                  scrollVodToTop();
                } else if (watchBtn && watchBtn.style.display !== 'none') {
                  watchBtn.focus();
                  scrollVodToTop();
                } else if (backBtn) {
                  backBtn.focus();
                  scrollVodToTop();
                }
              }
              return;
            }
            if (key === 'ArrowDown') {
              e.preventDefault();
              if (vodIdx + cols < vodCards.length) {
                vodCards[vodIdx + cols].focus();
                scrollVodCard(vodCards[vodIdx + cols], 'nearest');
              }
              return;
            }
            if (key === 'ArrowUp') {
              e.preventDefault();
              if (vodIdx - cols >= 0) {
                vodCards[vodIdx - cols].focus();
                scrollVodCard(vodCards[vodIdx - cols], 'nearest');
              } else {
                const activePill = catPills.find(p => p.classList.contains('active')) || catPills[0];
                if (activePill) {
                  activePill.focus();
                  scrollVodToTop();
                } else if (watchBtn && watchBtn.style.display !== 'none') {
                  watchBtn.focus();
                  scrollVodToTop();
                } else if (backBtn) {
                  backBtn.focus();
                  scrollVodToTop();
                }
              }
              return;
            }
            if (key === 'Enter') {
              e.preventDefault();
              focused.click();
              return;
            }
            return;
          }

          // Fallback VOD: posiciona foco no primeiro card
          if (key === 'ArrowDown' || key === 'ArrowRight' || key === 'ArrowLeft' || key === 'ArrowUp') {
            e.preventDefault();
            if (vodCards.length) {
              vodCards[0].focus();
              scrollVodCard(vodCards[0], 'nearest');
            } else if (backBtn) {
              backBtn.focus();
              scrollVodToTop();
            }
            return;
          }
        }
      });

      // ---- Funções de alternância Web ↔ TV (acionadas pelo botão no header) ----
      window.enterTvMode = async function () {
        if (!window.AndPlayAccount?.isSignedIn?.()) {
          if (typeof window.showLoginScreen === 'function') window.showLoginScreen();
          return;
        }
        if (isTvMode) return; // já está no modo TV
        isTvMode = true;
        console.log('[EPlay TV] Entrando no modo TV via atalho do navegador');
        document.body.classList.add('tv-mode');
        // O modo TV não antecipa catálogos VOD; cada área carrega seus dados somente quando aberta.
        if (window.initTvCableBox) {
          await window.initTvCableBox();
        }
        setTimeout(syncTvPlayerBoxPosition, 100);
      };

      window.exitTvMode = function () {
        if (!isTvMode) return;
        isTvMode = false;
        stopTvClock();
        stopTvBackgroundWork();
        window.EPlayTvEpg?.deactivate();
        console.log('[EPlay TV] Saindo do modo TV, voltando para interface Web');
        document.body.classList.remove('tv-mode');
        const tvApp = document.getElementById('tvCableApp');
        if (tvApp) tvApp.style.display = 'none';
        tvDestroyCurrentStream();
      };

      window.AndPlayApp = {
        refreshAfterAccountSync() {
          // Sincronização de conta em segundo plano: silenciosa.
          // NUNCA recriar ou atualizar a tela enquanto o usuário estiver navegando nela.
        },
        getAccountUsageSnapshot,
        async loadAccountUsage() {
          const tasks = [];
          if (getWatchedIds('movies').length && !fullMoviesCache) {
            tasks.push(loadFullMovies().catch(() => []));
          }
          if (getWatchedIds('series').length && !fullSeriesCache) {
            tasks.push(loadFullSeries().catch(() => []));
          }
          if (tasks.length) await Promise.all(tasks);
          await hydrateRemoteHistoryMetadata(50);
          return getAccountUsageSnapshot();
        },
        mergeWatchStats,
        mergeLiveHistory
      };

    })();

    init().catch(err => {
      console.error('Initialization error:', err);
      if (elements.loading) {
        elements.loading.innerHTML = `<p style="color:#e50914;">Erro na inicialização: ${err.message}</p>`;
      }
    });
