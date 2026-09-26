package com.andplay.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.widget.NestedScrollView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.andplay.app.adapter.CategoryPillAdapter;
import com.andplay.app.adapter.ChannelRailAdapter;
import com.andplay.app.adapter.EpisodeAdapter;
import com.andplay.app.adapter.MoviePosterAdapter;
import com.andplay.app.adapter.SportsRailAdapter;
import com.andplay.app.api.ApiClient;
import com.andplay.app.epg.EpgEngine;
import com.andplay.app.model.Category;
import com.andplay.app.model.Channel;
import com.andplay.app.model.Episode;
import com.andplay.app.model.LiveSchedule;
import com.andplay.app.model.Movie;
import com.andplay.app.model.Series;
import com.andplay.app.model.SportsEvent;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    public enum ScreenMode {
        CENTRAL, FULLSCREEN, VOD, SERIES_DETAIL
    }

    private ScreenMode currentMode = ScreenMode.CENTRAL;
    private ScreenMode previousMode = ScreenMode.CENTRAL;

    // Single Unified Video Player (reutilizado entre PiP e Tela Cheia sem recarregar o vídeo)
    private FrameLayout unifiedPlayerBox;
    private PlayerView unifiedExoPlayerView;
    private WebView unifiedEmbedWebView;
    private ExoPlayer exoPlayer;

    private FrameLayout pipPlayerHost;
    private FrameLayout fullscreenPlayerHost;

    // Central Views
    private LinearLayout centralLayout;
    private NestedScrollView centralScroll;
    private TextView headerClock;
    private TextView headerDate;
    private FrameLayout pipContainer;
    private TextView pipChannelName;
    private TextView pipProgramTitle;
    private LinearLayout btnNavMovies, btnNavSeries, btnNavSports, btnNavEpg;
    private RecyclerView channelsRail;
    private RecyclerView sportsRail;
    private RecyclerView moviesRail;

    // Fullscreen Views
    private FrameLayout fullscreenLayout;
    private TextView floatingBackBtn;
    private LinearLayout topChannelBadge;
    private TextView topChNum, topChName;
    private LinearLayout osdBanner;
    private TextView osdChNum, osdChName, osdClock, osdNowTitle, osdRemaining, osdSynopsis, osdNextProgram;
    private ProgressBar osdProgressBar;

    // Lateral EPG Drawer
    private LinearLayout epgDrawer;
    private RecyclerView drawerCatsRecycler;
    private RecyclerView drawerChannelsRecycler;

    // VOD Views
    private LinearLayout vodLayout;
    private TextView vodBackBtn, vodSectionTitle;
    private ImageView vodHeroPoster;
    private TextView vodHeroTitle, vodHeroRating, vodHeroYear, vodHeroGenre, vodHeroPlot, vodHeroWatchBtn;
    private RecyclerView vodCatsRecycler;
    private RecyclerView vodGridRecycler;

    // Series Detail Views
    private LinearLayout seriesDetailLayout;
    private TextView seriesBackBtn, seriesDetailTitle;
    private RecyclerView seriesSeasonsRecycler;
    private RecyclerView seriesEpisodesRecycler;

    // Loading Overlay
    private LinearLayout loadingLayout;
    private TextView loadingText;

    // State & Data
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<Channel> allChannels = new ArrayList<>();
    private List<SportsEvent> allSports = new ArrayList<>();
    private List<Movie> cachedMovies = new ArrayList<>();
    private List<Category> movieCategories = new ArrayList<>();
    private List<Series> cachedSeries = new ArrayList<>();
    private List<Category> seriesCategories = new ArrayList<>();

    private int currentChannelIdx = 0;
    private String currentActiveStreamUrl = "";
    private boolean isPlayingEmbed = false;
    private boolean isPlayingVod = false;
    private boolean isVideoPlaybackActive = false;

    private Movie activeVodMovie = null;
    private Series activeVodSeries = null;
    private String currentVodType = "movies";

    private final Handler osdHandler = new Handler(Looper.getMainLooper());
    private final Runnable osdHideRunnable = () -> {
        hideOsdBanner();
    };

    private final Handler drawerHandler = new Handler(Looper.getMainLooper());
    private final Runnable drawerHideRunnable = () -> {
        closeDrawer();
    };

    private CategoryPillAdapter drawerCatsAdapter;
    private final List<Category> drawerCats = new ArrayList<>();
    private int selectedDrawerCatIdx = 0;

    private int pendingZapChannelIdx = -1;
    private final Handler zapHandler = new Handler(Looper.getMainLooper());
    private final Runnable zapConfirmRunnable = () -> {
        confirmPendingZapChannel();
    };

    private long lastBackAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bindViews();
        hideSystemUI();
        enforceMaxVolume();
        initUnifiedPlayer();
        initClock();

        loadInitialData();
        setupCentralButtons();
    }

    private void bindViews() {
        // Hosts de vídeo
        pipPlayerHost = findViewById(R.id.pipPlayerHost);
        fullscreenPlayerHost = findViewById(R.id.fullscreenPlayerHost);

        // Central
        centralLayout = findViewById(R.id.centralLayout);
        centralScroll = findViewById(R.id.centralScroll);
        headerClock = findViewById(R.id.headerClock);
        headerDate = findViewById(R.id.headerDate);
        pipContainer = findViewById(R.id.pipContainer);
        pipChannelName = findViewById(R.id.pipChannelName);
        pipProgramTitle = findViewById(R.id.pipProgramTitle);

        btnNavMovies = findViewById(R.id.btnNavMovies);
        btnNavSeries = findViewById(R.id.btnNavSeries);
        btnNavSports = findViewById(R.id.btnNavSports);
        btnNavEpg = findViewById(R.id.btnNavEpg);

        channelsRail = findViewById(R.id.channelsRail);
        sportsRail = findViewById(R.id.sportsRail);
        moviesRail = findViewById(R.id.moviesRail);

        // Fullscreen
        fullscreenLayout = findViewById(R.id.fullscreenLayout);
        floatingBackBtn = findViewById(R.id.floatingBackBtn);
        topChannelBadge = findViewById(R.id.topChannelBadge);
        topChNum = findViewById(R.id.topChNum);
        topChName = findViewById(R.id.topChName);

        osdBanner = findViewById(R.id.osdBanner);
        osdChNum = findViewById(R.id.osdChNum);
        osdChName = findViewById(R.id.osdChName);
        osdClock = findViewById(R.id.osdClock);
        osdNowTitle = findViewById(R.id.osdNowTitle);
        osdRemaining = findViewById(R.id.osdRemaining);
        osdSynopsis = findViewById(R.id.osdSynopsis);
        osdNextProgram = findViewById(R.id.osdNextProgram);
        osdProgressBar = findViewById(R.id.osdProgressBar);

        // EPG Drawer
        epgDrawer = findViewById(R.id.epgDrawer);
        drawerCatsRecycler = findViewById(R.id.drawerCatsRecycler);
        drawerChannelsRecycler = findViewById(R.id.drawerChannelsRecycler);

        // VOD
        vodLayout = findViewById(R.id.vodLayout);
        vodBackBtn = findViewById(R.id.vodBackBtn);
        vodSectionTitle = findViewById(R.id.vodSectionTitle);
        vodHeroPoster = findViewById(R.id.vodHeroPoster);
        vodHeroTitle = findViewById(R.id.vodHeroTitle);
        vodHeroRating = findViewById(R.id.vodHeroRating);
        vodHeroYear = findViewById(R.id.vodHeroYear);
        vodHeroGenre = findViewById(R.id.vodHeroGenre);
        vodHeroPlot = findViewById(R.id.vodHeroPlot);
        vodHeroWatchBtn = findViewById(R.id.vodHeroWatchBtn);
        vodCatsRecycler = findViewById(R.id.vodCatsRecycler);
        vodGridRecycler = findViewById(R.id.vodGridRecycler);

        // Series Detail
        seriesDetailLayout = findViewById(R.id.seriesDetailLayout);
        seriesBackBtn = findViewById(R.id.seriesBackBtn);
        seriesDetailTitle = findViewById(R.id.seriesDetailTitle);
        seriesSeasonsRecycler = findViewById(R.id.seriesSeasonsRecycler);
        seriesEpisodesRecycler = findViewById(R.id.seriesEpisodesRecycler);

        // Loading
        loadingLayout = findViewById(R.id.loadingLayout);
        loadingText = findViewById(R.id.loadingText);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(markerClass = UnstableApi.class)
    private void initUnifiedPlayer() {
        // Infla o player único que será acoplado no PiP ou em Tela Cheia
        unifiedPlayerBox = (FrameLayout) LayoutInflater.from(this).inflate(R.layout.player_box, null);
        unifiedExoPlayerView = unifiedPlayerBox.findViewById(R.id.unifiedExoPlayerView);
        unifiedEmbedWebView = unifiedPlayerBox.findViewById(R.id.unifiedEmbedWebView);

        // Configuração do ExoPlayer
        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && exoPlayer.getPlayWhenReady()) {
                    mainHandler.post(() -> onPlaybackStarted());
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    mainHandler.post(() -> onPlaybackStarted());
                }
            }
        });
        unifiedExoPlayerView.setPlayer(exoPlayer);

        // Configuração Avançada do WebView com Proteção Total Contra Anúncios
        WebSettings ws = unifiedEmbedWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false); // Autoplay garantido
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setSupportMultipleWindows(false); // Impede popups
        ws.setJavaScriptCanOpenWindowsAutomatically(false); // Bloqueia abertura automática de abas
        ws.setUserAgentString(ws.getUserAgentString() + " EPlayTV/2.0 (SmartTV/Projector; CableBox)");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        unifiedEmbedWebView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onVideoStarted() {
                mainHandler.post(() -> onPlaybackStarted());
            }
        }, "AndroidPlayback");

        unifiedEmbedWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Bloqueio definitivo de popups e novas janelas
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("EPlayPlayer", consoleMessage.message());
                return true;
            }
        });

        unifiedEmbedWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Permite apenas o player e seus domínios legítimos de stream
                if (url.startsWith("file://")
                        || url.contains("rdcanais.net")
                        || url.contains("v2.rdembed.sbs")
                        || url.contains("streamverde.net")
                        || url.contains("cazetv.shop")
                        || url.contains("tvacabo.top")
                        || url.contains("tvacabo.free.nf")
                        || url.contains("bolodechocolate.fit")
                        || url.contains("esportesembed.net")
                        || url.contains("about:blank")) {
                    return false;
                }
                // Bloqueia qualquer redirect para sites de apostas, anúncios ou popunders
                Log.w("EPlayAdBlock", "Bloqueado redirect externo: " + url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Injeta script que trava popups/anúncios, força volume máximo e detecta início da reprodução real do vídeo
                String antiAdAndPlaybackScript = "(function() {" +
                        "window.open = function() { return null; };" +
                        "window.alert = function() { };" +
                        "window.confirm = function() { return false; };" +
                        "document.addEventListener('click', function(e) {" +
                        "  var a = e.target.closest('a');" +
                        "  if (a && a.target === '_blank') { e.preventDefault(); e.stopPropagation(); }" +
                        "}, true);" +
                        "function setMaxAudioAndHook() {" +
                        "  var media = document.querySelectorAll('video, audio');" +
                        "  for (var i = 0; i < media.length; i++) {" +
                        "    var m = media[i];" +
                        "    m.muted = false;" +
                        "    m.volume = 1.0;" +
                        "    if (m.tagName && m.tagName.toLowerCase() === 'video' && !m.__eplay_h) {" +
                        "      m.__eplay_h = true;" +
                        "      m.addEventListener('playing', function() {" +
                        "        if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted();" +
                        "      });" +
                        "      m.addEventListener('timeupdate', function() {" +
                        "        if (m.currentTime > 0.3 && !m.__eplay_sent) {" +
                        "          m.__eplay_sent = true;" +
                        "          if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted();" +
                        "        }" +
                        "      });" +
                        "    }" +
                        "    if (!m.paused && m.currentTime > 0) {" +
                        "      if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted();" +
                        "    }" +
                        "  }" +
                        "}" +
                        "setMaxAudioAndHook();" +
                        "setInterval(setMaxAudioAndHook, 800);" +
                        "})();";
                view.evaluateJavascript(antiAdAndPlaybackScript, null);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e("EPlay", "WebView RenderProcessGone crash=" + detail.didCrash());
                destroyCurrentStream();
                return true;
            }
        });

        // Inicializa o player no container PiP da Central
        pipPlayerHost.addView(unifiedPlayerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void enforceMaxVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0);
            }
        } catch (Exception e) {
            Log.w("EPlay", "Ajuste de volume maximo: " + e.getMessage());
        }
        if (exoPlayer != null) {
            exoPlayer.setVolume(1.0f);
        }
    }

    private void attachPlayerToHost(FrameLayout targetHost) {
        if (unifiedPlayerBox == null || targetHost == null) return;
        ViewGroup parent = (ViewGroup) unifiedPlayerBox.getParent();
        if (parent != targetHost) {
            if (parent != null) {
                parent.removeView(unifiedPlayerBox);
            }
            targetHost.addView(unifiedPlayerBox, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private void destroyCurrentStream() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        if (unifiedEmbedWebView != null) {
            unifiedEmbedWebView.stopLoading();
            unifiedEmbedWebView.loadUrl("about:blank");
            unifiedEmbedWebView.clearHistory();
        }
        currentActiveStreamUrl = "";
    }

    private void initClock() {
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM", new Locale("pt", "BR"));

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Date now = new Date();
                String timeStr = timeFormat.format(now);
                String dateStr = dateFormat.format(now).toUpperCase();

                if (headerClock != null) headerClock.setText(timeStr);
                if (headerDate != null) headerDate.setText(dateStr);
                if (osdClock != null) osdClock.setText(timeStr);

                mainHandler.postDelayed(this, 1000);
            }
        });
    }

    private void loadInitialData() {
        showLoading("Carregando canais...");
        executor.execute(() -> {
            allChannels = ApiClient.loadLocalChannels(this);
            allSports = ApiClient.getLiveSports();

            mainHandler.post(() -> {
                hideLoading();
                setupChannelsRail();
                setupSportsRail();
                setupDrawer();

                // Inicializa o sincronizador de EPG real de TV
                EpgEngine.init(MainActivity.this);
                EpgEngine.setUpdateListener(() -> {
                    mainHandler.post(() -> {
                        if (!allChannels.isEmpty() && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size()) {
                            Channel ch = allChannels.get(currentChannelIdx);
                            LiveSchedule epg = EpgEngine.getLiveSchedule(ch);
                            updateOsd(ch, currentChannelIdx, epg);
                            pipProgramTitle.setText("🔴 No Ar: " + (epg != null ? epg.nowTitle : (ch.now != null ? ch.now : "Ao Vivo")));
                        }
                        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE && !drawerCats.isEmpty()) {
                            Category cat = drawerCats.get(selectedDrawerCatIdx);
                            filterDrawerChannels(cat.category_id);
                        }
                    });
                });

                if (!allChannels.isEmpty()) {
                    tuneChannel(0, false);
                }
            });

            // Pré-carrega filmes em segundo plano
            try {
                movieCategories = ApiClient.getMovieCategories();
                cachedMovies = ApiClient.getMovies();
                mainHandler.post(this::setupMoviesRail);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void setupCentralButtons() {
        // Clicar ou teclar Enter no miniplayer -> expande imediatamente para Tela Cheia sem recarregar o stream
        pipContainer.setOnClickListener(v -> setScreenMode(ScreenMode.FULLSCREEN));
        pipContainer.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                setScreenMode(ScreenMode.FULLSCREEN);
                return true;
            }
            return false;
        });

        // 4 Botões Diretos: Cima (Filmes / Séries) >> Baixo (Jogos / Guia)
        btnNavMovies.setOnClickListener(v -> openVodExplorer("movies"));
        btnNavSeries.setOnClickListener(v -> openVodExplorer("series"));
        btnNavSports.setOnClickListener(v -> {
            if (centralScroll != null && sportsRail != null) {
                centralScroll.smoothScrollTo(0, sportsRail.getTop() - 100);
                sportsRail.requestFocus();
            }
        });
        btnNavEpg.setOnClickListener(v -> {
            toggleDrawer();
        });

        floatingBackBtn.setOnClickListener(v -> setScreenMode(ScreenMode.CENTRAL));
        vodBackBtn.setOnClickListener(v -> setScreenMode(ScreenMode.CENTRAL));
        seriesBackBtn.setOnClickListener(v -> setScreenMode(ScreenMode.VOD));
    }

    private void setupChannelsRail() {
        channelsRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        channelsRail.setAdapter(new ChannelRailAdapter(this, allChannels, false, (ch, idx) -> {
            // Se for o mesmo canal já em execução, apenas redimensiona para tela cheia
            if (idx == currentChannelIdx && (currentActiveStreamUrl != null && !currentActiveStreamUrl.isEmpty())) {
                setScreenMode(ScreenMode.FULLSCREEN);
                return;
            }
            // Canal diferente: destrói player anterior e sintoniza novo
            destroyCurrentStream();
            tuneChannel(idx, true);
            setScreenMode(ScreenMode.FULLSCREEN);
        }));
    }

    private void setupSportsRail() {
        sportsRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        sportsRail.setAdapter(new SportsRailAdapter(this, allSports, ev -> playSportsEvent(ev)));
    }

    private void setupMoviesRail() {
        if (cachedMovies == null || cachedMovies.isEmpty()) return;
        List<Movie> subList = cachedMovies.subList(0, Math.min(25, cachedMovies.size()));
        moviesRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        moviesRail.setAdapter(new MoviePosterAdapter(this, subList, new MoviePosterAdapter.OnMovieActionListener() {
            @Override
            public void onMovieClick(Movie movie) {
                playMovie(movie);
            }

            @Override
            public void onMovieFocus(Movie movie) {}
        }));
    }

    private void setupDrawer() {
        drawerCats.clear();
        drawerCats.add(new Category("ALL", "Todos"));
        drawerCats.add(new Category("open_tv", "Abertos"));
        drawerCats.add(new Category("sports", "Esportes"));
        drawerCats.add(new Category("movies", "Filmes 24H"));
        drawerCats.add(new Category("kids", "Infantil"));
        drawerCats.add(new Category("variety", "Variedades"));
        drawerCats.add(new Category("channels_24h", "24 Horas"));

        drawerCatsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        drawerCatsAdapter = new CategoryPillAdapter(drawerCats, cat -> {
            int idx = drawerCats.indexOf(cat);
            if (idx >= 0) selectedDrawerCatIdx = idx;
            filterDrawerChannels(cat.category_id);
            resetDrawerTimeout();
        });
        drawerCatsRecycler.setAdapter(drawerCatsAdapter);

        drawerChannelsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        filterDrawerChannels("ALL");
    }

    private void switchDrawerCategory(int delta) {
        if (drawerCats.isEmpty()) return;
        selectedDrawerCatIdx = (selectedDrawerCatIdx + delta + drawerCats.size()) % drawerCats.size();
        selectDrawerCategory(selectedDrawerCatIdx);
    }

    private void selectDrawerCategory(int index) {
        if (index < 0 || index >= drawerCats.size()) return;
        selectedDrawerCatIdx = index;
        Category cat = drawerCats.get(selectedDrawerCatIdx);
        if (drawerCatsAdapter != null) {
            drawerCatsAdapter.setSelectedId(cat.category_id);
        }
        if (drawerCatsRecycler != null) {
            drawerCatsRecycler.smoothScrollToPosition(selectedDrawerCatIdx);
        }
        filterDrawerChannels(cat.category_id);
        resetDrawerTimeout();
    }

    private void resetDrawerTimeout() {
        drawerHandler.removeCallbacks(drawerHideRunnable);
        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
            drawerHandler.postDelayed(drawerHideRunnable, 8000);
        }
    }

    private void filterDrawerChannels(String catId) {
        List<Channel> filtered = new ArrayList<>();
        for (Channel ch : allChannels) {
            if ("ALL".equals(catId) || (ch.key != null && ch.key.equals(catId))) {
                filtered.add(ch);
            }
        }
        ChannelRailAdapter adapter = new ChannelRailAdapter(this, filtered, true, (ch, idx) -> {
            int realIdx = allChannels.indexOf(ch);
            int targetIdx = realIdx >= 0 ? realIdx : idx;
            // Se for o mesmo canal que já está tocando, apenas fecha a gaveta e garante tela cheia
            if (targetIdx == currentChannelIdx && (currentActiveStreamUrl != null && !currentActiveStreamUrl.isEmpty())) {
                closeDrawer();
                setScreenMode(ScreenMode.FULLSCREEN);
                return;
            }
            // Canal diferente: destrói conexões anteriores e sintoniza
            destroyCurrentStream();
            tuneChannel(targetIdx, true);
            closeDrawer();
            setScreenMode(ScreenMode.FULLSCREEN);
        });
        adapter.setCurrentPlayingIdx(currentChannelIdx);
        drawerChannelsRecycler.setAdapter(adapter);

        drawerChannelsRecycler.post(() -> {
            if (drawerChannelsRecycler.getChildCount() > 0) {
                View first = drawerChannelsRecycler.getChildAt(0);
                if (first != null) first.requestFocus();
            }
        });
    }

    public void tuneChannel(int idx, boolean showOsd) {
        if (allChannels.isEmpty()) return;
        currentChannelIdx = (idx + allChannels.size()) % allChannels.size();
        Channel ch = allChannels.get(currentChannelIdx);
        isPlayingVod = false;

        LiveSchedule epg = EpgEngine.getLiveSchedule(ch);

        // Atualiza PiP
        pipChannelName.setText(String.format("%03d - %s", currentChannelIdx + 1, ch.name));
        pipProgramTitle.setText("🔴 No Ar: " + (epg != null ? epg.nowTitle : (ch.now != null ? ch.now : "Ao Vivo")));

        // Atualiza OSD
        updateOsd(ch, currentChannelIdx, epg);

        // Carrega transmissão
        List<Channel.StreamFallback> fallbacks = ch.getFallbacks();
        if (!fallbacks.isEmpty()) {
            Channel.StreamFallback primary = fallbacks.get(0);
            playStream(primary.url, primary.isEmbed);
        }

        if (showOsd && currentMode == ScreenMode.FULLSCREEN) {
            showOsdBannerLoading();
        }
    }

    private void stepZapChannel(int step) {
        if (allChannels.isEmpty()) return;
        if (pendingZapChannelIdx < 0) {
            pendingZapChannelIdx = currentChannelIdx;
        }
        // Ordem crescente no dpad cima (+1), decrescente no dpad baixo (-1)
        pendingZapChannelIdx = (pendingZapChannelIdx + step + allChannels.size()) % allChannels.size();

        Channel previewCh = allChannels.get(pendingZapChannelIdx);
        LiveSchedule epg = EpgEngine.getLiveSchedule(previewCh);
        updateOsd(previewCh, pendingZapChannelIdx, epg);
        showOsdBanner(5000);

        zapHandler.removeCallbacks(zapConfirmRunnable);
        zapHandler.postDelayed(zapConfirmRunnable, 3000);
    }

    private void confirmPendingZapChannel() {
        zapHandler.removeCallbacks(zapConfirmRunnable);
        if (pendingZapChannelIdx >= 0) {
            int target = pendingZapChannelIdx;
            pendingZapChannelIdx = -1;
            if (target != currentChannelIdx) {
                destroyCurrentStream();
                tuneChannel(target, true);
            } else {
                showOsdBanner(3000);
            }
        }
    }

    private void cancelPendingZap() {
        zapHandler.removeCallbacks(zapConfirmRunnable);
        pendingZapChannelIdx = -1;
        hideOsdBanner();
    }

    private void playStream(String url, boolean isEmbed) {
        isVideoPlaybackActive = false;
        currentActiveStreamUrl = url;
        isPlayingEmbed = isEmbed;
        enforceMaxVolume();

        if (isEmbed) {
            if (exoPlayer != null) {
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
            }
            unifiedExoPlayerView.setVisibility(View.GONE);
            unifiedEmbedWebView.setVisibility(View.VISIBLE);

            // Garante autoplay no parâmetro da URL
            String autoplayUrl = url + (url.contains("?") ? "&" : "?") + "autoplay=1";
            unifiedEmbedWebView.loadUrl(autoplayUrl);
        } else {
            unifiedEmbedWebView.stopLoading();
            unifiedEmbedWebView.loadUrl("about:blank");
            unifiedEmbedWebView.setVisibility(View.GONE);

            unifiedExoPlayerView.setVisibility(View.VISIBLE);

            MediaItem item = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(item);
            exoPlayer.prepare();
            exoPlayer.play();
        }
    }

    public void playMovie(Movie movie) {
        if (movie == null) return;
        destroyCurrentStream();
        activeVodMovie = movie;
        isPlayingVod = true;
        setScreenMode(ScreenMode.FULLSCREEN);

        String streamUrl = movie.getStreamUrl(ApiClient.SERVER, ApiClient.USER, ApiClient.PASS);
        playStream(streamUrl, false);

        // Preenche OSD com dados do filme
        topChNum.setText("VOD");
        topChName.setText(movie.getDisplayTitle());
        osdChNum.setText("FILME");
        osdChName.setText(movie.getDisplayTitle());
        osdNowTitle.setText("🎬 " + movie.getDisplayTitle());
        osdRemaining.setText(movie.year != null ? movie.year : "");
        osdSynopsis.setText(movie.plot != null ? movie.plot : "Filme sob demanda em alta definição.");
        osdNextProgram.setText("Áudio Original / Dublado");
        osdProgressBar.setProgress(100);

        showOsdBannerLoading();
    }

    public void playSeriesEpisode(Series series, Episode ep, String seasonNum) {
        if (series == null || ep == null) return;
        destroyCurrentStream();
        isPlayingVod = true;
        setScreenMode(ScreenMode.FULLSCREEN);

        String streamUrl = ep.getStreamUrl(ApiClient.SERVER, ApiClient.USER, ApiClient.PASS);
        playStream(streamUrl, false);

        String fullTitle = series.getDisplayTitle() + " • T" + seasonNum + ":E" + ep.episode_num;
        topChNum.setText("SÉRIE");
        topChName.setText(fullTitle);
        osdChNum.setText("T" + seasonNum);
        osdChName.setText(fullTitle);
        osdNowTitle.setText("🍿 " + ep.getDisplayTitle());
        osdRemaining.setText(ep.getDurationText());
        osdSynopsis.setText(ep.info != null && ep.info.plot != null ? ep.info.plot : series.plot);
        osdNextProgram.setText("Próximo episódio disponível na lista.");
        osdProgressBar.setProgress(100);

        showOsdBannerLoading();
    }

    public void playSportsEvent(SportsEvent ev) {
        if (ev == null) return;
        destroyCurrentStream();
        isPlayingVod = false;
        setScreenMode(ScreenMode.FULLSCREEN);

        if (!ev.fallbacks.isEmpty()) {
            Channel.StreamFallback fb = ev.fallbacks.get(0);
            playStream(fb.url, fb.isEmbed);
        }

        topChNum.setText("AO VIVO");
        topChName.setText(ev.getDisplayName());
        osdChNum.setText("JOGO");
        osdChName.setText(ev.getDisplayName());
        osdNowTitle.setText("⚽ " + ev.getDisplayLeague());
        osdRemaining.setText(ev.matchTime != null ? ev.matchTime : "Ao Vivo");
        osdSynopsis.setText(ev.getDisplayName() + " - Transmissão oficial ao vivo com cobertura em tempo real.");
        osdNextProgram.setText("Compactos e melhores momentos ao final da partida.");
        osdProgressBar.setProgress(100);

        showOsdBannerLoading();
    }

    private void updateOsd(Channel ch, int chIdx, LiveSchedule epg) {
        if (ch == null) return;
        topChNum.setText(String.format("CH %03d", chIdx + 1));
        topChName.setText(ch.name);

        osdChNum.setText(String.format("%03d", chIdx + 1));
        osdChName.setText(ch.name);

        if (epg != null) {
            osdNowTitle.setText("🔴 NO AR: " + epg.nowTitle);
            osdRemaining.setText(String.format("Restam ~%d min (%s)", epg.remainingMinutes, epg.timeRange));
            osdSynopsis.setText(epg.synopsis);
            osdNextProgram.setText("A Seguir: " + epg.nextStart + " • " + epg.nextTitle);
            osdProgressBar.setProgress(epg.progress);
        } else {
            osdNowTitle.setText("🔴 NO AR: " + (ch.now != null ? ch.now : "Transmissão Ao Vivo"));
            osdRemaining.setText("Ao Vivo");
            osdSynopsis.setText("Transmissão contínua em tempo real.");
            osdNextProgram.setText("Programação contínua.");
            osdProgressBar.setProgress(50);
        }
    }

    public void onPlaybackStarted() {
        isVideoPlaybackActive = true;
        enforceMaxVolume();
        if (currentMode == ScreenMode.FULLSCREEN && osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
            scheduleOsdHide(5000);
        }
    }

    public void scheduleOsdHide(int durationMs) {
        osdHandler.removeCallbacks(osdHideRunnable);
        osdHandler.postDelayed(osdHideRunnable, durationMs);
    }

    public void showOsdBannerLoading() {
        osdHandler.removeCallbacks(osdHideRunnable);
        if (floatingBackBtn != null) floatingBackBtn.setVisibility(View.VISIBLE);
        if (topChannelBadge != null) topChannelBadge.setVisibility(View.VISIBLE);
        if (osdBanner != null) osdBanner.setVisibility(View.VISIBLE);

        // Fallback de segurança: se a reprodução não disparar em 15s, agenda fechamento
        osdHandler.postDelayed(() -> {
            if (!isVideoPlaybackActive && currentMode == ScreenMode.FULLSCREEN) {
                scheduleOsdHide(5000);
            }
        }, 15000);
    }

    public void showOsdBanner(int durationMs) {
        osdHandler.removeCallbacks(osdHideRunnable);
        if (floatingBackBtn != null) floatingBackBtn.setVisibility(View.VISIBLE);
        if (topChannelBadge != null) topChannelBadge.setVisibility(View.VISIBLE);
        if (osdBanner != null) osdBanner.setVisibility(View.VISIBLE);

        if (isVideoPlaybackActive) {
            scheduleOsdHide(durationMs > 0 ? durationMs : 5000);
        } else {
            showOsdBannerLoading();
        }
    }

    public void hideOsdBanner() {
        osdHandler.removeCallbacks(osdHideRunnable);
        if (floatingBackBtn != null) floatingBackBtn.setVisibility(View.GONE);
        if (topChannelBadge != null) topChannelBadge.setVisibility(View.GONE);
        if (osdBanner != null) osdBanner.setVisibility(View.GONE);
    }

    public void openDrawer() {
        drawerHandler.removeCallbacks(drawerHideRunnable);
        if (epgDrawer != null) {
            epgDrawer.setVisibility(View.VISIBLE);
            hideOsdBanner();
            resetDrawerTimeout();

            epgDrawer.post(() -> {
                // Re-filtra e adapta dimensões dos canais agora que a gaveta tem seus 410dp medidos
                if (drawerCats != null && !drawerCats.isEmpty() && selectedDrawerCatIdx < drawerCats.size()) {
                    filterDrawerChannels(drawerCats.get(selectedDrawerCatIdx).category_id);
                }

                int pos = Math.max(0, currentChannelIdx);
                drawerChannelsRecycler.scrollToPosition(pos);
                drawerChannelsRecycler.postDelayed(() -> {
                    RecyclerView.ViewHolder vh = drawerChannelsRecycler.findViewHolderForAdapterPosition(pos);
                    if (vh != null && vh.itemView != null) {
                        vh.itemView.requestFocus();
                    } else if (drawerChannelsRecycler.getChildCount() > 0) {
                        View first = drawerChannelsRecycler.getChildAt(0);
                        if (first != null) first.requestFocus();
                    }
                }, 80);
            });
        }
    }

    public void closeDrawer() {
        drawerHandler.removeCallbacks(drawerHideRunnable);
        if (epgDrawer != null) {
            epgDrawer.setVisibility(View.GONE);
        }
    }

    public void toggleDrawer() {
        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
        } else {
            openDrawer();
        }
    }

    public void setScreenMode(ScreenMode mode) {
        previousMode = currentMode;
        currentMode = mode;

        centralLayout.setVisibility(mode == ScreenMode.CENTRAL ? View.VISIBLE : View.GONE);
        fullscreenLayout.setVisibility(mode == ScreenMode.FULLSCREEN ? View.VISIBLE : View.GONE);
        vodLayout.setVisibility(mode == ScreenMode.VOD ? View.VISIBLE : View.GONE);
        seriesDetailLayout.setVisibility(mode == ScreenMode.SERIES_DETAIL ? View.VISIBLE : View.GONE);

        if (mode == ScreenMode.FULLSCREEN) {
            // Reanexa o player unificado no host de tela cheia sem recarregar o vídeo
            attachPlayerToHost(fullscreenPlayerHost);
            if (isVideoPlaybackActive) {
                showOsdBanner(5000);
            } else {
                showOsdBannerLoading();
            }
        } else if (mode == ScreenMode.CENTRAL) {
            // Reanexa o player unificado no host do PiP sem recarregar o vídeo
            closeDrawer();
            hideOsdBanner();
            attachPlayerToHost(pipPlayerHost);
            pipContainer.requestFocus();
        } else if (mode == ScreenMode.VOD) {
            closeDrawer();
            hideOsdBanner();
            if (exoPlayer != null && !isPlayingVod) exoPlayer.pause();
            vodBackBtn.requestFocus();
        }
    }

    private void openVodExplorer(String type) {
        currentVodType = type;
        setScreenMode(ScreenMode.VOD);
        vodSectionTitle.setText("movies".equals(type) ? "🎬 Filmes" : "📺 Séries");

        if ("movies".equals(type)) {
            loadMoviesCatalog();
        } else {
            loadSeriesCatalog();
        }
    }

    private void loadMoviesCatalog() {
        if (!cachedMovies.isEmpty()) {
            renderVodContent(movieCategories, cachedMovies);
            return;
        }

        showLoading("Carregando catálogo de filmes...");
        executor.execute(() -> {
            try {
                movieCategories = ApiClient.getMovieCategories();
                cachedMovies = ApiClient.getMovies();
                mainHandler.post(() -> {
                    hideLoading();
                    renderVodContent(movieCategories, cachedMovies);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, "Erro ao carregar filmes: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadSeriesCatalog() {
        if (!cachedSeries.isEmpty()) {
            renderSeriesContent(seriesCategories, cachedSeries);
            return;
        }

        showLoading("Carregando catálogo de séries...");
        executor.execute(() -> {
            try {
                seriesCategories = ApiClient.getSeriesCategories();
                cachedSeries = ApiClient.getSeries();
                mainHandler.post(() -> {
                    hideLoading();
                    renderSeriesContent(seriesCategories, cachedSeries);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, "Erro ao carregar séries: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void renderVodContent(List<Category> categories, List<Movie> movies) {
        List<Category> pills = new ArrayList<>();
        pills.add(new Category("ALL", "🌟 Todas as Categorias"));
        if (categories != null) {
            for (Category c : categories) {
                if (c.category_name != null && !c.category_name.toLowerCase().contains("demo")) {
                    pills.add(c);
                }
            }
        }

        vodCatsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        vodCatsRecycler.setAdapter(new CategoryPillAdapter(pills, cat -> filterMoviesByCat(cat.category_id, movies)));

        filterMoviesByCat("ALL", movies);
    }

    private void filterMoviesByCat(String catId, List<Movie> all) {
        List<Movie> filtered = new ArrayList<>();
        for (Movie m : all) {
            if ("ALL".equals(catId) || (m.category_id != null && m.category_id.equals(catId))) {
                filtered.add(m);
                if (filtered.size() >= 120) break;
            }
        }

        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 5));
        vodGridRecycler.setAdapter(new MoviePosterAdapter(this, filtered, new MoviePosterAdapter.OnMovieActionListener() {
            @Override
            public void onMovieClick(Movie movie) {
                playMovie(movie);
            }

            @Override
            public void onMovieFocus(Movie movie) {
                updateVodHero(movie);
            }
        }));

        if (!filtered.isEmpty()) {
            updateVodHero(filtered.get(0));
        }
    }

    private void renderSeriesContent(List<Category> categories, List<Series> seriesList) {
        List<Category> pills = new ArrayList<>();
        pills.add(new Category("ALL", "🌟 Todas as Categorias"));
        if (categories != null) {
            for (Category c : categories) {
                if (c.category_name != null && !c.category_name.toLowerCase().contains("demo")) {
                    pills.add(c);
                }
            }
        }

        vodCatsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        vodCatsRecycler.setAdapter(new CategoryPillAdapter(pills, cat -> filterSeriesByCat(cat.category_id, seriesList)));

        filterSeriesByCat("ALL", seriesList);
    }

    private void filterSeriesByCat(String catId, List<Series> all) {
        List<Movie> converted = new ArrayList<>();
        List<Series> rawFiltered = new ArrayList<>();
        for (Series s : all) {
            if ("ALL".equals(catId) || (s.category_id != null && s.category_id.equals(catId))) {
                rawFiltered.add(s);
                Movie pseudo = new Movie();
                pseudo.stream_id = s.series_id;
                pseudo.name = s.name;
                pseudo.title = s.title;
                pseudo.stream_icon = s.cover;
                pseudo.plot = s.plot;
                pseudo.rating = s.rating;
                pseudo.genre = s.genre;
                converted.add(pseudo);
                if (converted.size() >= 120) break;
            }
        }

        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 5));
        vodGridRecycler.setAdapter(new MoviePosterAdapter(this, converted, new MoviePosterAdapter.OnMovieActionListener() {
            @Override
            public void onMovieClick(Movie m) {
                for (Series s : rawFiltered) {
                    if (s.series_id != null && s.series_id.equals(m.stream_id)) {
                        openSeriesDetail(s);
                        break;
                    }
                }
            }

            @Override
            public void onMovieFocus(Movie movie) {
                updateVodHero(movie);
            }
        }));

        if (!converted.isEmpty()) {
            updateVodHero(converted.get(0));
        }
    }

    private void updateVodHero(Movie m) {
        if (m == null) return;
        vodHeroTitle.setText(m.getDisplayTitle());
        vodHeroRating.setText(m.rating != null && !m.rating.isEmpty() ? "★ " + m.rating : "★ 7.5");
        vodHeroYear.setText(m.year != null ? m.year : "");
        vodHeroGenre.setText(m.genre != null ? m.genre : "");
        vodHeroPlot.setText(m.plot != null ? m.plot : "Sinopse disponível ao reproduzir o título.");

        String poster = m.getPosterUrl();
        if (poster != null && !poster.isEmpty()) {
            Glide.with(this).load(poster).diskCacheStrategy(DiskCacheStrategy.ALL).into(vodHeroPoster);
        }

        vodHeroWatchBtn.setText("series".equals(currentVodType) ? "▶ VER EPISÓDIOS (OK)" : "▶ ASSISTIR (OK)");
        vodHeroWatchBtn.setOnClickListener(v -> {
            if ("movies".equals(currentVodType)) {
                playMovie(m);
            }
        });
    }

    private void openSeriesDetail(Series series) {
        activeVodSeries = series;
        setScreenMode(ScreenMode.SERIES_DETAIL);
        seriesDetailTitle.setText("📺 " + series.getDisplayTitle() + " • Temporadas");

        showLoading("Carregando episódios...");
        executor.execute(() -> {
            try {
                Map<String, List<Episode>> episodesMap = ApiClient.getSeriesEpisodes(series.series_id);
                mainHandler.post(() -> {
                    hideLoading();
                    renderSeriesEpisodes(series, episodesMap);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, "Erro ao carregar episódios: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void renderSeriesEpisodes(Series series, Map<String, List<Episode>> episodesMap) {
        List<Category> seasonPills = new ArrayList<>();
        List<String> seasonKeys = new ArrayList<>(episodesMap.keySet());
        seasonKeys.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (Exception e) {
                return a.compareTo(b);
            }
        });

        for (String sNum : seasonKeys) {
            seasonPills.add(new Category(sNum, "Temporada " + sNum));
        }

        seriesSeasonsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        seriesSeasonsRecycler.setAdapter(new CategoryPillAdapter(seasonPills, cat -> {
            displaySeasonEpisodes(series, episodesMap.get(cat.category_id), cat.category_id);
        }));

        if (!seasonKeys.isEmpty()) {
            displaySeasonEpisodes(series, episodesMap.get(seasonKeys.get(0)), seasonKeys.get(0));
        }
    }

    private void displaySeasonEpisodes(Series series, List<Episode> eps, String seasonNum) {
        if (eps == null) eps = new ArrayList<>();
        seriesEpisodesRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        seriesEpisodesRecycler.setAdapter(new EpisodeAdapter(this, eps, ep -> {
            playSeriesEpisode(series, ep, seasonNum);
        }));
    }

    private void showLoading(String msg) {
        if (loadingLayout != null) {
            loadingText.setText(msg);
            loadingLayout.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoading() {
        if (loadingLayout != null) {
            loadingLayout.setVisibility(View.GONE);
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (action == KeyEvent.ACTION_DOWN) {
            // Teclas de controle de TV por Assinatura / Receptor
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                if (currentMode == ScreenMode.FULLSCREEN && !isPlayingVod) {
                    stepZapChannel(1);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                if (currentMode == ScreenMode.FULLSCREEN && !isPlayingVod) {
                    stepZapChannel(-1);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_GUIDE || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
                if (currentMode == ScreenMode.FULLSCREEN) {
                    toggleDrawer();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (exoPlayer != null) {
                    if (exoPlayer.isPlaying()) exoPlayer.pause();
                    else exoPlayer.play();
                    showOsdBanner(3000);
                    return true;
                }
            }

            // Em tela cheia:
            if (currentMode == ScreenMode.FULLSCREEN) {
                // CENÁRIO 1: GAVETA LATERAL ESTÁ ABERTA
                if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
                    resetDrawerTimeout();

                    // D-pad Esquerdo e Direito alternam entre grupos/categorias de canais (sem fechar a gaveta)
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        switchDrawerCategory(-1);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        switchDrawerCategory(1);
                        return true;
                    }
                    // UP e DOWN navegam normalmente pelos canais do RecyclerView
                }
                // CENÁRIO 2: GAVETA LATERAL ESTÁ FECHADA
                else {
                    if (!isPlayingVod) {
                        // D-pad Esquerdo abre a gaveta lateral
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (pendingZapChannelIdx >= 0) {
                                cancelPendingZap();
                            }
                            openDrawer();
                            return true;
                        }

                        // D-pad Cima (+) alterna canais em ordem crescente (+1) com confirmação após 3s
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            stepZapChannel(1);
                            return true;
                        }

                        // D-pad Baixo (-) alterna canais em ordem decrescente (-1) com confirmação após 3s
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            stepZapChannel(-1);
                            return true;
                        }

                        // ENTER / OK confirma a troca imediata se estiver zapeando, ou exibe o OSD se oculto
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (pendingZapChannelIdx >= 0) {
                                confirmPendingZapChannel();
                                return true;
                            } else if (osdBanner != null && osdBanner.getVisibility() != View.VISIBLE) {
                                showOsdBanner(5000);
                                return true;
                            } else {
                                showOsdBanner(5000);
                                return true;
                            }
                        }

                        // Prolonga o OSD se estiver visível e reprodução ativa
                        if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                            if (isVideoPlaybackActive) {
                                scheduleOsdHide(5000);
                            }
                        }
                    } else {
                        // Em VOD (Filme ou Série), teclas mostram o banner de informações
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            showOsdBanner(5000);
                            return true;
                        }
                    }
                }
            }
        }

        // Botão Voltar (Back) com pilha inteligente
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP) {
            if (handleBack()) return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean handleBack() {
        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return true;
        }

        if (currentMode == ScreenMode.FULLSCREEN) {
            if (pendingZapChannelIdx >= 0) {
                cancelPendingZap();
                return true;
            }
            if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                hideOsdBanner();
                return true;
            }
            if (isPlayingVod) {
                setScreenMode(previousMode == ScreenMode.SERIES_DETAIL ? ScreenMode.SERIES_DETAIL : ScreenMode.VOD);
                return true;
            }
            setScreenMode(ScreenMode.CENTRAL);
            return true;
        }

        if (currentMode == ScreenMode.SERIES_DETAIL) {
            setScreenMode(ScreenMode.VOD);
            return true;
        }

        if (currentMode == ScreenMode.VOD) {
            setScreenMode(ScreenMode.CENTRAL);
            return true;
        }

        // Raiz do app (Central): exige 2 toques rápidos para sair
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAt < 2000) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Pressione Voltar novamente para sair", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        enforceMaxVolume();
        if (exoPlayer != null && !isPlayingEmbed) exoPlayer.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyCurrentStream();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        executor.shutdown();
    }
}
