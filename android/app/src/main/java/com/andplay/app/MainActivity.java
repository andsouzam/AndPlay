package com.andplay.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import com.andplay.app.provider.ProviderManager;
import java.util.Arrays;
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
import android.webkit.CookieManager;
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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import android.view.MotionEvent;

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
    private View btnHeaderOptions;
    private View btnDrawerOptions;
    private FrameLayout pipContainer;
    private TextView pipChannelName;
    private TextView pipProgramTitle;
    private LinearLayout btnNavMovies, btnNavSeries, btnNavSports, btnNavEpg;
    private RecyclerView channelsRail;
    private RecyclerView sportsRail;
    private RecyclerView moviesRail;
    private RecyclerView seriesRail;

    // Fullscreen Views
    private FrameLayout fullscreenLayout;
    private LinearLayout topChannelBadge;
    private TextView topChNum, topChName;
    private LinearLayout osdBanner;
    private TextView osdChNum, osdChName, osdClock, osdNowTitle, osdRemaining, osdSynopsis, osdNextProgram;
    private ProgressBar osdProgressBar;

    // Lateral EPG Drawer
    private LinearLayout epgDrawer;
    private TextView drawerHeaderTitle;
    private RecyclerView drawerCatsRecycler;
    private RecyclerView drawerChannelsRecycler;

    // VOD Views
    private LinearLayout vodLayout;
    private TextView vodHeroTitle, vodHeroRating, vodHeroYear, vodHeroGenre, vodHeroPlot;
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
    private List<Channel.StreamFallback> currentChannelFallbacks = new ArrayList<>();
    private int currentFallbackIdx = 0;

    private Movie activeVodMovie = null;
    private Series activeVodSeries = null;
    private Episode activeVodEpisode = null;
    private String activeVodSeasonNum = "1";
    private Map<String, List<Episode>> currentSeriesEpisodesMap = new HashMap<>();
    private String currentVodType = "movies";
    private OkHttpClient sharedOkHttpClient;

    public List<Channel> getAllChannels() {
        return allChannels;
    }

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
        btnHeaderOptions = findViewById(R.id.btnHeaderOptions);
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
        seriesRail = findViewById(R.id.seriesRail);

        // Fullscreen
        fullscreenLayout = findViewById(R.id.fullscreenLayout);
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
        drawerHeaderTitle = findViewById(R.id.drawerHeaderTitle);
        btnDrawerOptions = findViewById(R.id.btnDrawerOptions);
        drawerCatsRecycler = findViewById(R.id.drawerCatsRecycler);
        drawerChannelsRecycler = findViewById(R.id.drawerChannelsRecycler);
        drawerCatsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        drawerChannelsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));

        // VOD
        vodLayout = findViewById(R.id.vodLayout);
        vodHeroTitle = findViewById(R.id.vodHeroTitle);
        vodHeroRating = findViewById(R.id.vodHeroRating);
        vodHeroYear = findViewById(R.id.vodHeroYear);
        vodHeroGenre = findViewById(R.id.vodHeroGenre);
        vodHeroPlot = findViewById(R.id.vodHeroPlot);
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

    public static class StreamDns implements Dns {
        private static final Map<String, List<InetAddress>> CACHE = new ConcurrentHashMap<>();

        static {
            try {
                CACHE.put("svd.cazetv.shop", Arrays.asList(
                        InetAddress.getByName("172.67.135.64"),
                        InetAddress.getByName("104.21.6.203")
                ));
                CACHE.put("cdn1.s22-cloudfront-net.lat", Arrays.asList(
                        InetAddress.getByName("104.21.96.54"),
                        InetAddress.getByName("172.67.173.73")
                ));
            } catch (Exception ignored) {}
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            if (CACHE.containsKey(hostname)) {
                return CACHE.get(hostname);
            }
            try {
                List<InetAddress> sys = Dns.SYSTEM.lookup(hostname);
                if (sys != null && !sys.isEmpty()) return sys;
            } catch (UnknownHostException ignored) {}

            try {
                URL url = new URL("https://1.1.1.1/dns-query?name=" + hostname + "&type=A");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/dns-json");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    r.close();
                    JSONObject obj = new JSONObject(sb.toString());
                    if (obj.has("Answer")) {
                        JSONArray ans = obj.getJSONArray("Answer");
                        List<InetAddress> ips = new ArrayList<>();
                        for (int i = 0; i < ans.length(); i++) {
                            JSONObject a = ans.getJSONObject(i);
                            if (a.has("data") && a.optInt("type") == 1) {
                                ips.add(InetAddress.getByName(a.getString("data")));
                            }
                        }
                        if (!ips.isEmpty()) {
                            CACHE.put(hostname, ips);
                            return ips;
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (hostname.contains("cazetv.shop") || hostname.contains("streamverde")) {
                List<InetAddress> ips = CACHE.get("svd.cazetv.shop");
                if (ips != null) return ips;
            }
            if (hostname.contains("s22-cloudfront-net") || hostname.endsWith(".lat")) {
                List<InetAddress> ips = CACHE.get("cdn1.s22-cloudfront-net.lat");
                if (ips != null) return ips;
            }

            throw new UnknownHostException("Não foi possível resolver host: " + hostname);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(markerClass = UnstableApi.class)
    private void initUnifiedPlayer() {
        // Infla o player único que será acoplado no PiP ou em Tela Cheia
        unifiedPlayerBox = (FrameLayout) LayoutInflater.from(this).inflate(R.layout.player_box, null);
        unifiedExoPlayerView = unifiedPlayerBox.findViewById(R.id.unifiedExoPlayerView);
        unifiedEmbedWebView = unifiedPlayerBox.findViewById(R.id.unifiedEmbedWebView);

        // Configuração de alto desempenho do ExoPlayer com OkHttp e DNS inteligente
        sharedOkHttpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .dns(new StreamDns())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

        OkHttpDataSource.Factory httpDataSourceFactory = new OkHttpDataSource.Factory(sharedOkHttpClient)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpDataSourceFactory);

        exoPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
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

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w("EPlayPlayer", "ExoPlayer erro: " + error.getMessage() + ", tentando próximo fallback...");
                mainHandler.post(() -> tryNextFallback());
            }
        });
        unifiedExoPlayerView.setPlayer(exoPlayer);

        // Cookies de terceiros para Cloudflare e validação de tokens
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(unifiedEmbedWebView, true);
        }

        // Configuração Avançada do WebView com User-Agent limpo de navegador padrão Android
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
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

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
                if (request != null && !request.isForMainFrame()) {
                    // Subframes, iframes de vídeo e scripts não são bloqueados para evitar erro de adblock
                    return false;
                }
                String url = request != null ? request.getUrl().toString() : "";
                if (url.startsWith("file://")
                        || url.contains("rdcanais.net")
                        || url.contains("v2.rdembed.sbs")
                        || url.contains("streamverde.net")
                        || url.contains("cazetv.shop")
                        || url.contains("tvacabo.top")
                        || url.contains("tvacabo.free.nf")
                        || url.contains("bolodechocolate.fit")
                        || url.contains("esportesembed.net")
                        || url.contains("localhost.tattoo")
                        || url.contains("globo.com")
                        || url.contains("jwpcdn.com")
                        || url.contains("about:blank")) {
                    return false;
                }
                // Bloqueia qualquer redirect para sites de apostas, anúncios ou popunders de frame principal
                Log.w("EPlayAdBlock", "Bloqueado redirect externo de janela principal: " + url);
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return super.shouldInterceptRequest(view, request);
                String url = request.getUrl().toString();

                // 1. Intercepta player.js do localhost.tattoo para garantir autoplay e remover botão de pause
                if (url.contains("localhost.tattoo") && url.contains("player.js")) {
                    try {
                        Request okReq = new Request.Builder()
                                .url(url)
                                .header("Referer", "https://localhost.tattoo/")
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                .build();
                        Response okRes = sharedOkHttpClient.newCall(okReq).execute();
                        if (okRes.isSuccessful() && okRes.body() != null) {
                            String originalJs = okRes.body().string();
                            String injection = "\n;(function(){\n" +
                                    "  var css = '.jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-flag-touch .jw-display-icon-container, .jw-flag-touch .jw-display-icon-display, .jw-flag-touch .jw-icon-playback, .plyr__control--overlaid, .plyr__controls, .vjs-big-play-button, .vjs-control-bar { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; }';\n" +
                                    "  var st = document.createElement('style');\n" +
                                    "  st.textContent = css;\n" +
                                    "  (document.head || document.documentElement).appendChild(st);\n" +
                                    "  function forceStart() {\n" +
                                    "    try {\n" +
                                    "      if (typeof jwplayer === 'function') {\n" +
                                    "        var p = jwplayer();\n" +
                                    "        if (p && typeof p.play === 'function') {\n" +
                                    "          p.setMute(false);\n" +
                                    "          p.setVolume(100);\n" +
                                    "          var s = typeof p.getState === 'function' ? p.getState() : '';\n" +
                                    "          if (s === 'paused' || s === 'idle') { p.play(); }\n" +
                                    "          if (s === 'playing') { if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted(); }\n" +
                                    "        }\n" +
                                    "      }\n" +
                                    "    } catch(e) {}\n" +
                                    "    try {\n" +
                                    "      var v = document.querySelector('video');\n" +
                                    "      if (v) {\n" +
                                    "        v.muted = false;\n" +
                                    "        v.volume = 1.0;\n" +
                                    "        if (v.paused) { v.play().catch(function(){}); }\n" +
                                    "        else if (v.currentTime > 0) { if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted(); }\n" +
                                    "      }\n" +
                                    "    } catch(e) {}\n" +
                                    "  }\n" +
                                    "  setInterval(forceStart, 600);\n" +
                                    "  document.addEventListener('DOMContentLoaded', forceStart);\n" +
                                    "  window.addEventListener('load', forceStart);\n" +
                                    "})();\n";
                            byte[] modifiedBytes = (originalJs + injection).getBytes(StandardCharsets.UTF_8);
                            return new WebResourceResponse("application/javascript", "UTF-8", new ByteArrayInputStream(modifiedBytes));
                        }
                    } catch (Exception e) {
                        Log.w("EPlay", "Erro ao interceptar player.js: " + e.getMessage());
                    }
                }

                // 2. Intercepta hotstar.css e player-v3.1.min.css para ocultar ícones de playback
                if (url.contains("hotstar.css") || url.contains("player-v3.1.min.css")) {
                    try {
                        Request okReq = new Request.Builder()
                                .url(url)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                                .build();
                        Response okRes = sharedOkHttpClient.newCall(okReq).execute();
                        if (okRes.isSuccessful() && okRes.body() != null) {
                            String originalCss = okRes.body().string();
                            String hideCss = "\n.jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-flag-touch .jw-display-icon-container, .jw-flag-touch .jw-display-icon-display, .jw-flag-touch .jw-icon-playback { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; }\n";
                            byte[] cssBytes = (originalCss + hideCss).getBytes(StandardCharsets.UTF_8);
                            return new WebResourceResponse("text/css", "UTF-8", new ByteArrayInputStream(cssBytes));
                        }
                    } catch (Exception e) {
                        Log.w("EPlay", "Erro ao interceptar CSS do player: " + e.getMessage());
                    }
                }

                // 3. Intercepta página principal do rdcanais.net para conceder allow="autoplay *" no iframe
                if (request.isForMainFrame() && url.contains("rdcanais.net")) {
                    try {
                        Request okReq = new Request.Builder()
                                .url(url)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                .build();
                        Response okRes = sharedOkHttpClient.newCall(okReq).execute();
                        if (okRes.isSuccessful() && okRes.body() != null) {
                            String html = okRes.body().string();
                            html = html.replace("allow=\"encrypted-media\"", "allow=\"autoplay *; encrypted-media *; fullscreen *; picture-in-picture *\"");
                            byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
                            return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(htmlBytes));
                        }
                    } catch (Exception e) {
                        Log.w("EPlay", "Erro ao interceptar rdcanais HTML: " + e.getMessage());
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.w("EPlayPlayer", "WebView erro no frame principal, tentando próximo fallback...");
                    mainHandler.post(() -> tryNextFallback());
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Injeta script seguro para áudio, remoção de botões sobrepostos e detecção de reprodução
                String antiAdAndPlaybackScript = "(function() {" +
                        "try {" +
                        "  if (!window.__eplay_h) {" +
                        "    window.__eplay_h = true;" +
                        "    window.open = function() { return { focus: function(){}, close: function(){}, closed: false, location: { href: '' } }; };" +
                        "  }" +
                        "} catch(e) {}" +
                        "try {" +
                        "  var css = '.jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-flag-touch .jw-display-icon-container, .jw-flag-touch .jw-display-icon-display, .jw-flag-touch .jw-icon-playback, .plyr__control--overlaid, .plyr__controls, .vjs-big-play-button, .vjs-control-bar, button[data-plyr=\"play\"] { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; }';" +
                        "  var st = document.createElement('style');" +
                        "  st.textContent = css;" +
                        "  (document.head || document.documentElement).appendChild(st);" +
                        "} catch(e) {}" +
                        "function fixIframesAndAudio() {" +
                        "  try {" +
                        "    var ifrs = document.querySelectorAll('iframe');" +
                        "    for (var j = 0; j < ifrs.length; j++) {" +
                        "      var ifr = ifrs[j];" +
                        "      if (!ifr.hasAttribute('allow') || ifr.getAttribute('allow').indexOf('autoplay') === -1) {" +
                        "        ifr.setAttribute('allow', 'autoplay *; encrypted-media *; fullscreen *; picture-in-picture *');" +
                        "        ifr.allow = 'autoplay *; encrypted-media *; fullscreen *; picture-in-picture *';" +
                        "      }" +
                        "    }" +
                        "  } catch(e) {}" +
                        "  var media = document.querySelectorAll('video, audio');" +
                        "  for (var i = 0; i < media.length; i++) {" +
                        "    var m = media[i];" +
                        "    m.muted = false;" +
                        "    m.volume = 1.0;" +
                        "    if (m.paused) { m.play().catch(function(){}); }" +
                        "    if (m.tagName && m.tagName.toLowerCase() === 'video' && !m.__eplay_v) {" +
                        "      m.__eplay_v = true;" +
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
                        "fixIframesAndAudio();" +
                        "setInterval(fixIframesAndAudio, 600);" +
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

    private static final String PREF_VOD_PROGRESS = "vod_playback_progress_prefs";
    private int vodSaveCounter = 0;
    private final Handler vodProgressHandler = new Handler(Looper.getMainLooper());
    private final Runnable vodProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlayingVod && exoPlayer != null && currentMode == ScreenMode.FULLSCREEN) {
                updateVodProgress();
                vodSaveCounter++;
                if (vodSaveCounter >= 5) {
                    vodSaveCounter = 0;
                    saveCurrentVodProgress();
                }
                vodProgressHandler.postDelayed(this, 1000);
            }
        }
    };

    private void startVodProgressTicker() {
        vodProgressHandler.removeCallbacks(vodProgressRunnable);
        vodProgressHandler.post(vodProgressRunnable);
    }

    private void stopVodProgressTicker() {
        vodProgressHandler.removeCallbacks(vodProgressRunnable);
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "00:00:00";
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String getCurrentVodKey() {
        if (activeVodMovie != null && activeVodMovie.stream_id != null) {
            return "movie_" + activeVodMovie.stream_id;
        }
        if (activeVodEpisode != null && activeVodEpisode.id != null) {
            return "episode_" + activeVodEpisode.id;
        }
        if (activeVodSeries != null && activeVodEpisode != null) {
            return "series_" + activeVodSeries.series_id + "_s" + activeVodSeasonNum + "_e" + activeVodEpisode.episode_num;
        }
        return null;
    }

    private void saveCurrentVodProgress() {
        if (!isPlayingVod || exoPlayer == null) return;
        String key = getCurrentVodKey();
        if (key == null) return;
        long pos = exoPlayer.getCurrentPosition();
        long dur = exoPlayer.getDuration();
        saveVodProgress(key, pos, dur);
    }

    private void saveVodProgress(String key, long positionMs, long durationMs) {
        if (key == null || key.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_VOD_PROGRESS, Context.MODE_PRIVATE);
            // Se assistiu mais de 95% do vídeo ou restam menos de 30 segundos, considera concluído e limpa
            if (durationMs > 0 && (positionMs >= durationMs - 30000 || positionMs >= (long) (durationMs * 0.95))) {
                prefs.edit().remove(key).remove(key + "_dur").remove(key + "_time").apply();
                return;
            }
            // Salva apenas se assistiu pelo menos 10 segundos
            if (positionMs > 10000) {
                prefs.edit()
                        .putLong(key, positionMs)
                        .putLong(key + "_dur", durationMs)
                        .putLong(key + "_time", System.currentTimeMillis())
                        .apply();
            }
        } catch (Exception e) {
            Log.w("EPlayVOD", "Erro ao salvar progresso VOD: " + e.getMessage());
        }
    }

    private long getVodProgress(String key) {
        if (key == null || key.isEmpty()) return 0;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_VOD_PROGRESS, Context.MODE_PRIVATE);
            return prefs.getLong(key, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void clearVodProgress(String key) {
        if (key == null || key.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_VOD_PROGRESS, Context.MODE_PRIVATE);
            prefs.edit().remove(key).remove(key + "_dur").remove(key + "_time").apply();
        } catch (Exception ignored) {}
    }

    private void updateVodProgress() {
        if (!isPlayingVod || exoPlayer == null) return;
        long pos = exoPlayer.getCurrentPosition();
        long dur = exoPlayer.getDuration();
        if (dur > 0) {
            long remaining = Math.max(0, dur - pos);
            int prog = (int) Math.min(100, Math.max(0, (pos * 100) / dur));
            String posStr = formatDuration(pos);
            String durStr = formatDuration(dur);
            String remStr = formatDuration(remaining);
            if (osdRemaining != null) {
                osdRemaining.setText(posStr + " / " + durStr + " (Restam " + remStr + ")");
            }
            if (osdProgressBar != null) {
                osdProgressBar.setProgress(prog);
            }
        } else if (pos > 0) {
            if (osdRemaining != null) {
                osdRemaining.setText(formatDuration(pos) + " / --:--:--");
            }
            if (osdProgressBar != null) {
                osdProgressBar.setProgress(0);
            }
        }
    }

    private void destroyCurrentStream() {
        if (isPlayingVod) {
            saveCurrentVodProgress();
        }
        stopVodProgressTicker();
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
                            pipProgramTitle.setText("🔴 No Ar: " + (epg != null && !"SEM DADOS DE PROGRAMAÇÃO".equals(epg.nowTitle) ? epg.nowTitle : "SEM DADOS DE PROGRAMAÇÃO"));
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

                if (pipContainer != null) {
                    pipContainer.setFocusable(true);
                    pipContainer.setFocusableInTouchMode(true);
                    pipContainer.postDelayed(() -> pipContainer.requestFocus(), 250);
                }
            });

            // Pré-carrega filmes e séries em segundo plano
            try {
                movieCategories = ApiClient.getMovieCategories();
                cachedMovies = ApiClient.getMovies();
                mainHandler.post(this::setupMoviesRail);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                seriesCategories = ApiClient.getSeriesCategories();
                cachedSeries = ApiClient.getSeries();
                mainHandler.post(this::setupSeriesRail);
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
        btnNavMovies.setOnClickListener(v -> {
            destroyCurrentStream();
            isPlayingVod = false;
            openVodExplorer("movies");
        });
        btnNavSeries.setOnClickListener(v -> {
            destroyCurrentStream();
            isPlayingVod = false;
            openVodExplorer("series");
        });
        btnNavSports.setOnClickListener(v -> {
            if (centralScroll != null && sportsRail != null) {
                centralScroll.smoothScrollTo(0, sportsRail.getTop() - 100);
                sportsRail.requestFocus();
            }
        });
        btnNavEpg.setOnClickListener(v -> {
            toggleDrawer();
        });

        if (btnHeaderOptions != null) {
            btnHeaderOptions.setOnClickListener(v -> showProviderOptionsDialog());
            btnHeaderOptions.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    showProviderOptionsDialog();
                    return true;
                }
                return false;
            });
        }

        if (btnDrawerOptions != null) {
            btnDrawerOptions.setOnClickListener(v -> showProviderOptionsDialog());
            btnDrawerOptions.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    showProviderOptionsDialog();
                    return true;
                }
                return false;
            });
        }

        seriesBackBtn.setOnClickListener(v -> {
            destroyCurrentStream();
            isPlayingVod = false;
            setScreenMode(ScreenMode.VOD);
        });
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

    private static boolean isDemoMovie(Movie m) {
        if (m == null) return false;
        String n = m.name != null ? m.name.toLowerCase() : "";
        String t = m.title != null ? m.title.toLowerCase() : "";
        return n.contains("demo") || t.contains("demo");
    }

    private void setupMoviesRail() {
        if (cachedMovies == null || cachedMovies.isEmpty()) return;
        List<Movie> subList = new ArrayList<>();
        for (Movie m : cachedMovies) {
            if (isDemoMovie(m)) continue;
            subList.add(m);
            if (subList.size() >= 25) break;
        }
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

    private static final String PREF_RECENT_SERIES = "andplay_recent_series";

    private void saveRecentSeries(Series series) {
        if (series == null || series.series_id == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_RECENT_SERIES, Context.MODE_PRIVATE);
            String raw = prefs.getString("history", "");
            List<String> list = new ArrayList<>();
            if (!raw.isEmpty()) {
                for (String id : raw.split(",")) {
                    if (!id.trim().isEmpty() && !id.trim().equals(series.series_id)) {
                        list.add(id.trim());
                    }
                }
            }
            list.add(0, series.series_id);
            while (list.size() > 20) {
                list.remove(list.size() - 1);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            prefs.edit().putString("history", sb.toString()).apply();
        } catch (Exception e) {
            Log.w("EPlay", "Erro ao salvar series recente: " + e.getMessage());
        }
    }

    private List<String> getRecentSeriesIds() {
        List<String> list = new ArrayList<>();
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_RECENT_SERIES, Context.MODE_PRIVATE);
            String raw = prefs.getString("history", "");
            if (!raw.isEmpty()) {
                for (String id : raw.split(",")) {
                    if (!id.trim().isEmpty()) {
                        list.add(id.trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void setupSeriesRail() {
        if (seriesRail == null || cachedSeries == null || cachedSeries.isEmpty()) return;
        List<Series> orderedSeries = new ArrayList<>();
        List<String> recentIds = getRecentSeriesIds();
        for (String id : recentIds) {
            for (Series s : cachedSeries) {
                if (s.series_id != null && s.series_id.equals(id)) {
                    if (!orderedSeries.contains(s)) {
                        orderedSeries.add(s);
                    }
                    break;
                }
            }
            if (orderedSeries.size() >= 3) break;
        }

        for (Series s : cachedSeries) {
            String nameLow = s.name != null ? s.name.toLowerCase() : "";
            String titleLow = s.title != null ? s.title.toLowerCase() : "";
            if (nameLow.contains("demo") || titleLow.contains("demo")) continue;
            if (!orderedSeries.contains(s)) {
                orderedSeries.add(s);
            }
            if (orderedSeries.size() >= 25) break;
        }

        List<Movie> converted = new ArrayList<>();
        for (Series s : orderedSeries) {
            Movie pseudo = new Movie();
            pseudo.stream_id = s.series_id;
            pseudo.name = s.name;
            pseudo.title = s.title;
            pseudo.stream_icon = s.cover;
            pseudo.plot = s.plot;
            pseudo.rating = s.rating;
            pseudo.genre = s.genre;
            converted.add(pseudo);
        }

        seriesRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        seriesRail.setAdapter(new MoviePosterAdapter(this, converted, new MoviePosterAdapter.OnMovieActionListener() {
            @Override
            public void onMovieClick(Movie m) {
                for (Series s : orderedSeries) {
                    if (s.series_id != null && s.series_id.equals(m.stream_id)) {
                        openSeriesDetail(s);
                        break;
                    }
                }
            }

            @Override
            public void onMovieFocus(Movie movie) {}
        }));
    }

    private void triggerAutoplayTap() {
        if (unifiedEmbedWebView == null) return;
        int w = unifiedEmbedWebView.getWidth();
        int h = unifiedEmbedWebView.getHeight();
        if (w <= 0 || h <= 0) {
            w = 1280;
            h = 720;
        }
        float x = w / 2.0f;
        float y = h / 2.0f;
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0);
        unifiedEmbedWebView.dispatchTouchEvent(down);
        unifiedEmbedWebView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void setupDrawer() {
        setupDrawerForLiveTv();
    }

    private void setupDrawerForLiveTv() {
        if (drawerHeaderTitle != null) {
            drawerHeaderTitle.setText("GUIA DE PROGRAMAÇÃO");
        }

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
            filterDrawerChannels(cat.category_id, null);
            resetDrawerTimeout();
        });
        drawerCatsRecycler.setAdapter(drawerCatsAdapter);

        // Identifica o canal ativo e a sua categoria
        Channel currentCh = (allChannels != null && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size())
                ? allChannels.get(currentChannelIdx) : null;

        selectedDrawerCatIdx = 0;
        if (currentCh != null && currentCh.key != null) {
            for (int i = 0; i < drawerCats.size(); i++) {
                if (currentCh.key.equals(drawerCats.get(i).category_id)) {
                    selectedDrawerCatIdx = i;
                    break;
                }
            }
        }

        selectDrawerCategory(selectedDrawerCatIdx, currentCh);
    }

    private void setupDrawerForSeries() {
        if (activeVodSeries == null) return;
        if (drawerHeaderTitle != null) {
            drawerHeaderTitle.setText("🍿 " + activeVodSeries.getDisplayTitle());
        }

        if (currentSeriesEpisodesMap == null || currentSeriesEpisodesMap.isEmpty()) {
            executor.execute(() -> {
                try {
                    Map<String, List<Episode>> epMap = ApiClient.getSeriesEpisodes(activeVodSeries.series_id);
                    mainHandler.post(() -> {
                        currentSeriesEpisodesMap = epMap;
                        populateDrawerSeries();
                    });
                } catch (Exception ignored) {}
            });
        } else {
            populateDrawerSeries();
        }
    }

    private void populateDrawerSeries() {
        if (currentSeriesEpisodesMap == null || currentSeriesEpisodesMap.isEmpty()) return;
        drawerCats.clear();
        List<String> seasonKeys = new ArrayList<>(currentSeriesEpisodesMap.keySet());
        seasonKeys.sort((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (Exception e) { return a.compareTo(b); }
        });

        for (String sNum : seasonKeys) {
            drawerCats.add(new Category(sNum, "Temporada " + sNum));
        }

        selectedDrawerCatIdx = 0;
        for (int i = 0; i < seasonKeys.size(); i++) {
            if (seasonKeys.get(i).equals(activeVodSeasonNum)) {
                selectedDrawerCatIdx = i;
                break;
            }
        }

        drawerCatsAdapter = new CategoryPillAdapter(drawerCats, cat -> {
            int idx = drawerCats.indexOf(cat);
            if (idx >= 0) selectedDrawerCatIdx = idx;
            filterDrawerSeriesEpisodes(cat.category_id, null);
            resetDrawerTimeout();
        });
        drawerCatsRecycler.setAdapter(drawerCatsAdapter);

        if (!drawerCats.isEmpty()) {
            drawerCatsAdapter.setSelectedId(drawerCats.get(selectedDrawerCatIdx).category_id);
            drawerCatsRecycler.scrollToPosition(selectedDrawerCatIdx);
            filterDrawerSeriesEpisodes(drawerCats.get(selectedDrawerCatIdx).category_id, activeVodEpisode);
        }
    }

    private void filterDrawerSeriesEpisodes(String seasonNum, Episode targetEp) {
        List<Episode> eps = currentSeriesEpisodesMap != null ? currentSeriesEpisodesMap.get(seasonNum) : null;
        if (eps == null) eps = new ArrayList<>();

        final String activeSeason = seasonNum;
        EpisodeAdapter adapter = new EpisodeAdapter(this, eps, ep -> {
            closeDrawer();
            playSeriesEpisode(activeVodSeries, ep, activeSeason);
        });
        drawerChannelsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        drawerChannelsRecycler.setAdapter(adapter);

        int targetPos = 0;
        if (targetEp != null) {
            targetPos = eps.indexOf(targetEp);
            if (targetPos < 0) {
                for (int i = 0; i < eps.size(); i++) {
                    if (eps.get(i).id != null && eps.get(i).id.equals(targetEp.id)) {
                        targetPos = i;
                        break;
                    }
                }
            }
            if (targetPos < 0) targetPos = 0;
        }

        final int focusIndex = targetPos;
        drawerChannelsRecycler.scrollToPosition(focusIndex);
        drawerChannelsRecycler.postDelayed(() -> {
            RecyclerView.ViewHolder vh = drawerChannelsRecycler.findViewHolderForAdapterPosition(focusIndex);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else if (drawerChannelsRecycler.getChildCount() > 0) {
                View first = drawerChannelsRecycler.getChildAt(0);
                if (first != null) first.requestFocus();
            }
        }, 80);
    }

    private void setupDrawerForMovie() {
        if (drawerHeaderTitle != null) {
            drawerHeaderTitle.setText("🎬 CATÁLOGO DE FILMES");
        }

        drawerCats.clear();
        drawerCats.add(new Category("ALL", "Todos os Filmes"));
        if (movieCategories != null) {
            for (Category c : movieCategories) {
                if (c.category_name != null && !c.category_name.toLowerCase().contains("demo")) {
                    drawerCats.add(c);
                }
            }
        }

        selectedDrawerCatIdx = 0;
        if (activeVodMovie != null && activeVodMovie.category_id != null) {
            for (int i = 0; i < drawerCats.size(); i++) {
                if (activeVodMovie.category_id.equals(drawerCats.get(i).category_id)) {
                    selectedDrawerCatIdx = i;
                    break;
                }
            }
        }

        drawerCatsAdapter = new CategoryPillAdapter(drawerCats, cat -> {
            int idx = drawerCats.indexOf(cat);
            if (idx >= 0) selectedDrawerCatIdx = idx;
            filterDrawerMovies(cat.category_id, null);
            resetDrawerTimeout();
        });
        drawerCatsRecycler.setAdapter(drawerCatsAdapter);

        if (!drawerCats.isEmpty()) {
            drawerCatsAdapter.setSelectedId(drawerCats.get(selectedDrawerCatIdx).category_id);
            drawerCatsRecycler.scrollToPosition(selectedDrawerCatIdx);
            filterDrawerMovies(drawerCats.get(selectedDrawerCatIdx).category_id, activeVodMovie);
        }
    }

    private void filterDrawerMovies(String catId, Movie targetMovie) {
        List<Movie> filtered = new ArrayList<>();
        if (cachedMovies != null) {
            for (Movie m : cachedMovies) {
                if (isDemoMovie(m)) continue;
                if ("ALL".equals(catId) || (m.category_id != null && m.category_id.equals(catId))) {
                    filtered.add(m);
                    if (filtered.size() >= 100) break;
                }
            }
        }

        drawerChannelsRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        drawerChannelsRecycler.setAdapter(new MoviePosterAdapter(this, filtered, true, new MoviePosterAdapter.OnMovieActionListener() {
            @Override
            public void onMovieClick(Movie movie) {
                closeDrawer();
                playMovie(movie);
            }

            @Override
            public void onMovieFocus(Movie movie) {}
        }));

        int targetPos = 0;
        if (targetMovie != null) {
            targetPos = filtered.indexOf(targetMovie);
            if (targetPos < 0) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (filtered.get(i).stream_id != null && filtered.get(i).stream_id.equals(targetMovie.stream_id)) {
                        targetPos = i;
                        break;
                    }
                }
            }
            if (targetPos < 0) targetPos = 0;
        }

        final int focusIndex = targetPos;
        drawerChannelsRecycler.scrollToPosition(focusIndex);
        drawerChannelsRecycler.postDelayed(() -> {
            RecyclerView.ViewHolder vh = drawerChannelsRecycler.findViewHolderForAdapterPosition(focusIndex);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else if (drawerChannelsRecycler.getChildCount() > 0) {
                View first = drawerChannelsRecycler.getChildAt(0);
                if (first != null) first.requestFocus();
            }
        }, 80);
    }

    private void switchDrawerCategory(int delta) {
        if (drawerCats.isEmpty()) return;
        selectedDrawerCatIdx = (selectedDrawerCatIdx + delta + drawerCats.size()) % drawerCats.size();
        Category cat = drawerCats.get(selectedDrawerCatIdx);
        if (drawerCatsAdapter != null) {
            drawerCatsAdapter.setSelectedId(cat.category_id);
        }
        if (drawerCatsRecycler != null) {
            drawerCatsRecycler.smoothScrollToPosition(selectedDrawerCatIdx);
        }
        if (isPlayingVod) {
            if (activeVodSeries != null) {
                filterDrawerSeriesEpisodes(cat.category_id, activeVodEpisode);
            } else if (activeVodMovie != null) {
                filterDrawerMovies(cat.category_id, activeVodMovie);
            } else {
                filterDrawerChannels(cat.category_id, null);
            }
        } else {
            filterDrawerChannels(cat.category_id, null);
        }
        resetDrawerTimeout();
    }

    private void selectDrawerCategory(int index, Channel targetChannel) {
        if (index < 0 || index >= drawerCats.size()) return;
        selectedDrawerCatIdx = index;
        Category cat = drawerCats.get(selectedDrawerCatIdx);
        if (drawerCatsAdapter != null) {
            drawerCatsAdapter.setSelectedId(cat.category_id);
        }
        if (drawerCatsRecycler != null) {
            drawerCatsRecycler.smoothScrollToPosition(selectedDrawerCatIdx);
        }
        filterDrawerChannels(cat.category_id, targetChannel);
        resetDrawerTimeout();
    }

    private void resetDrawerTimeout() {
        drawerHandler.removeCallbacks(drawerHideRunnable);
        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
            drawerHandler.postDelayed(drawerHideRunnable, 8000);
        }
    }

    private void filterDrawerChannels(String catId, Channel targetChannel) {
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

        int targetPos = 0;
        if (targetChannel != null) {
            targetPos = filtered.indexOf(targetChannel);
            if (targetPos < 0) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (filtered.get(i).id != null && filtered.get(i).id.equals(targetChannel.id)) {
                        targetPos = i;
                        break;
                    }
                }
            }
            if (targetPos < 0) targetPos = 0;
        } else if (!allChannels.isEmpty() && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size()) {
            Channel curr = allChannels.get(currentChannelIdx);
            targetPos = filtered.indexOf(curr);
            if (targetPos < 0) targetPos = 0;
        }

        drawerChannelsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        adapter.setCurrentPlayingIdx(targetPos);
        drawerChannelsRecycler.setAdapter(adapter);

        final int focusIndex = targetPos;
        drawerChannelsRecycler.scrollToPosition(focusIndex);
        drawerChannelsRecycler.postDelayed(() -> {
            RecyclerView.ViewHolder vh = drawerChannelsRecycler.findViewHolderForAdapterPosition(focusIndex);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else {
                drawerChannelsRecycler.scrollToPosition(focusIndex);
                drawerChannelsRecycler.postDelayed(() -> {
                    RecyclerView.ViewHolder vh2 = drawerChannelsRecycler.findViewHolderForAdapterPosition(focusIndex);
                    if (vh2 != null && vh2.itemView != null) {
                        vh2.itemView.requestFocus();
                    } else if (drawerChannelsRecycler.getChildCount() > 0) {
                        View first = drawerChannelsRecycler.getChildAt(0);
                        if (first != null) first.requestFocus();
                    }
                }, 80);
            }
        }, 80);
    }

    private void filterDrawerChannels(String catId) {
        filterDrawerChannels(catId, null);
    }

    public void tuneChannel(int idx, boolean showOsd) {
        if (allChannels.isEmpty()) return;
        currentChannelIdx = (idx + allChannels.size()) % allChannels.size();
        Channel ch = allChannels.get(currentChannelIdx);
        isPlayingVod = false;

        LiveSchedule epg = EpgEngine.getLiveSchedule(ch);

        // Atualiza PiP
        pipChannelName.setText(String.format("%03d - %s", currentChannelIdx + 1, ch.name));
        pipProgramTitle.setText("🔴 No Ar: " + (epg != null && !"SEM DADOS DE PROGRAMAÇÃO".equals(epg.nowTitle) ? epg.nowTitle : "SEM DADOS DE PROGRAMAÇÃO"));

        // Atualiza OSD
        updateOsd(ch, currentChannelIdx, epg);

        // Carrega transmissão respeitando a prioridade de provedores do usuário
        currentChannelFallbacks = ch.getFallbacks(this);
        currentFallbackIdx = 0;
        if (!currentChannelFallbacks.isEmpty()) {
            Channel.StreamFallback primary = currentChannelFallbacks.get(0);
            playStream(primary.url, primary.isEmbed);
        }

        if (showOsd && currentMode == ScreenMode.FULLSCREEN) {
            showOsdBannerLoading();
        }
    }

    private void tryNextFallback() {
        if (currentChannelFallbacks == null || currentChannelFallbacks.isEmpty()) return;
        currentFallbackIdx++;
        if (currentFallbackIdx < currentChannelFallbacks.size()) {
            Channel.StreamFallback nextFb = currentChannelFallbacks.get(currentFallbackIdx);
            Log.i("EPlay", "Acionando fallback #" + (currentFallbackIdx + 1) + ": " + nextFb.name + " (" + nextFb.url + ")");
            Toast.makeText(this, "Alternando para: " + nextFb.name, Toast.LENGTH_SHORT).show();
            playStream(nextFb.url, nextFb.isEmbed);
        } else {
            Log.w("EPlay", "Todos os provedores de transmissão falharam para o canal atual.");
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
        playStream(url, isEmbed, 0);
    }

    private void playStream(String url, boolean isEmbed, long startPositionMs) {
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

            mainHandler.postDelayed(() -> {
                if (isPlayingEmbed && !isVideoPlaybackActive) {
                    triggerAutoplayTap();
                }
            }, 1200);
            mainHandler.postDelayed(() -> {
                if (isPlayingEmbed && !isVideoPlaybackActive) {
                    triggerAutoplayTap();
                }
            }, 2400);
        } else {
            unifiedEmbedWebView.stopLoading();
            unifiedEmbedWebView.loadUrl("about:blank");
            unifiedEmbedWebView.setVisibility(View.GONE);

            unifiedExoPlayerView.setVisibility(View.VISIBLE);

            MediaItem item = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(item);
            if (startPositionMs > 0) {
                exoPlayer.seekTo(startPositionMs);
            }
            exoPlayer.prepare();
            exoPlayer.play();
        }
    }

    public void playMovie(Movie movie) {
        if (movie == null) return;
        String key = "movie_" + movie.stream_id;
        long savedPos = getVodProgress(key);

        if (savedPos > 10000) {
            String[] options = new String[] {
                    "▶️ CONTINUAR DE ONDE PAROU (" + formatDuration(savedPos) + ")",
                    "🔄 VOLTAR AO INÍCIO"
            };
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("🎬 " + movie.getDisplayTitle() + "\n(Parou em " + formatDuration(savedPos) + ")")
                    .setItems(options, (dialog, which) -> {
                        dialog.dismiss();
                        if (which == 0) {
                            startMoviePlayback(movie, savedPos);
                        } else {
                            clearVodProgress(key);
                            startMoviePlayback(movie, 0);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            startMoviePlayback(movie, 0);
        }
    }

    private void startMoviePlayback(Movie movie, long startPos) {
        destroyCurrentStream();
        activeVodMovie = movie;
        activeVodSeries = null;
        activeVodEpisode = null;
        isPlayingVod = true;
        setScreenMode(ScreenMode.FULLSCREEN);

        String streamUrl = movie.getStreamUrl(ApiClient.SERVER, ApiClient.USER, ApiClient.PASS);
        playStream(streamUrl, false, startPos);

        // Preenche OSD com dados do filme
        topChNum.setText("VOD");
        topChName.setText(movie.getDisplayTitle());
        osdChNum.setText("FILME");
        osdChName.setText(movie.getDisplayTitle());
        osdNowTitle.setText("🎬 " + movie.getDisplayTitle());
        if (startPos > 0) {
            osdRemaining.setText(formatDuration(startPos) + " / Retomando...");
        } else {
            osdRemaining.setText("00:00:00 / Carregando...");
        }
        osdSynopsis.setText(movie.plot != null && !movie.plot.isEmpty() ? movie.plot : "Filme sob demanda em alta definição.");
        osdNextProgram.setText(movie.genre != null && !movie.genre.isEmpty() ? "Gênero: " + movie.genre : "Áudio Original / Dublado");
        osdProgressBar.setProgress(0);

        startVodProgressTicker();
        showOsdBannerLoading();
    }

    public void playSeriesEpisode(Series series, Episode ep, String seasonNum) {
        if (series == null || ep == null) return;
        String key = ep.id != null ? "episode_" + ep.id : "series_" + (series.series_id != null ? series.series_id : "") + "_s" + seasonNum + "_e" + ep.episode_num;
        long savedPos = getVodProgress(key);

        String fullTitle = series.getDisplayTitle() + " • T" + seasonNum + ":E" + ep.episode_num;
        if (savedPos > 10000) {
            String[] options = new String[] {
                    "▶️ CONTINUAR DE ONDE PAROU (" + formatDuration(savedPos) + ")",
                    "🔄 VOLTAR AO INÍCIO"
            };
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("🍿 " + fullTitle + "\n(Parou em " + formatDuration(savedPos) + ")")
                    .setItems(options, (dialog, which) -> {
                        dialog.dismiss();
                        if (which == 0) {
                            startSeriesEpisodePlayback(series, ep, seasonNum, savedPos);
                        } else {
                            clearVodProgress(key);
                            startSeriesEpisodePlayback(series, ep, seasonNum, 0);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            startSeriesEpisodePlayback(series, ep, seasonNum, 0);
        }
    }

    private void startSeriesEpisodePlayback(Series series, Episode ep, String seasonNum, long startPos) {
        saveRecentSeries(series);
        destroyCurrentStream();
        isPlayingVod = true;
        activeVodSeries = series;
        activeVodEpisode = ep;
        activeVodSeasonNum = seasonNum;
        activeVodMovie = null;
        setScreenMode(ScreenMode.FULLSCREEN);

        String streamUrl = ep.getStreamUrl(ApiClient.SERVER, ApiClient.USER, ApiClient.PASS);
        playStream(streamUrl, false, startPos);

        String fullTitle = series.getDisplayTitle() + " • T" + seasonNum + ":E" + ep.episode_num;
        topChNum.setText("SÉRIE");
        topChName.setText(fullTitle);
        osdChNum.setText("T" + seasonNum);
        osdChName.setText(fullTitle);
        osdNowTitle.setText("🍿 " + ep.getDisplayTitle());
        if (startPos > 0) {
            osdRemaining.setText(formatDuration(startPos) + " / Retomando...");
        } else {
            osdRemaining.setText("00:00:00 / Carregando...");
        }
        osdSynopsis.setText(ep.info != null && ep.info.plot != null && !ep.info.plot.isEmpty() ? ep.info.plot : series.plot);
        osdNextProgram.setText("Próximo episódio disponível na lista.");
        osdProgressBar.setProgress(0);

        startVodProgressTicker();
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

        if (epg != null && !"SEM DADOS DE PROGRAMAÇÃO".equals(epg.nowTitle)) {
            osdNowTitle.setText("🔴 NO AR: " + epg.nowTitle);
            osdRemaining.setText(String.format("Restam ~%d min (%s)", epg.remainingMinutes, epg.timeRange));
            osdSynopsis.setText(epg.synopsis);
            osdNextProgram.setText("A Seguir: " + epg.nextStart + " • " + epg.nextTitle);
            osdProgressBar.setProgress(epg.progress);
        } else {
            osdNowTitle.setText("🔴 NO AR: SEM DADOS DE PROGRAMAÇÃO");
            osdRemaining.setText("--:--");
            osdSynopsis.setText("Grade de programação indisponível para este canal no momento.");
            osdNextProgram.setText("A Seguir: SEM DADOS DE PROGRAMAÇÃO");
            osdProgressBar.setProgress(0);
        }
    }

    public void onPlaybackStarted() {
        isVideoPlaybackActive = true;
        enforceMaxVolume();
        if (isPlayingVod) {
            startVodProgressTicker();
            updateVodProgress();
        }
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
                if (isPlayingVod) {
                    if (activeVodSeries != null) {
                        setupDrawerForSeries();
                    } else if (activeVodMovie != null) {
                        setupDrawerForMovie();
                    } else {
                        setupDrawerForLiveTv();
                    }
                } else {
                    setupDrawerForLiveTv();
                }
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
        if (currentMode == ScreenMode.FULLSCREEN && mode != ScreenMode.FULLSCREEN) {
            if (isPlayingVod) {
                stopVodProgressTicker();
                destroyCurrentStream();
                isPlayingVod = false;
                activeVodMovie = null;
                activeVodEpisode = null;
                activeVodSeries = null;
            }
        }

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
            if (pipContainer != null) {
                pipContainer.postDelayed(() -> {
                    pipContainer.setFocusable(true);
                    pipContainer.requestFocus();
                }, 100);
            }
            setupSeriesRail();
            if ((currentActiveStreamUrl == null || currentActiveStreamUrl.isEmpty()) && !allChannels.isEmpty()) {
                tuneChannel(currentChannelIdx, false);
            }
        } else if (mode == ScreenMode.VOD) {
            closeDrawer();
            hideOsdBanner();
            destroyCurrentStream();
            if (vodCatsRecycler != null) vodCatsRecycler.requestFocus();
        } else if (mode == ScreenMode.SERIES_DETAIL) {
            closeDrawer();
            hideOsdBanner();
            destroyCurrentStream();
            if (seriesBackBtn != null) seriesBackBtn.requestFocus();
        }
    }

    private void openVodExplorer(String type) {
        destroyCurrentStream();
        isPlayingVod = false;
        currentVodType = type;
        setScreenMode(ScreenMode.VOD);

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
            if (isDemoMovie(m)) continue;
            if ("ALL".equals(catId) || (m.category_id != null && m.category_id.equals(catId))) {
                filtered.add(m);
                if (filtered.size() >= 120) break;
            }
        }

        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 6));
        vodGridRecycler.setAdapter(new MoviePosterAdapter(this, filtered, true, new MoviePosterAdapter.OnMovieActionListener() {
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
            String nameLow = s.name != null ? s.name.toLowerCase() : "";
            String titleLow = s.title != null ? s.title.toLowerCase() : "";
            if (nameLow.contains("demo") || titleLow.contains("demo")) continue;
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

        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 6));
        vodGridRecycler.setAdapter(new MoviePosterAdapter(this, converted, true, new MoviePosterAdapter.OnMovieActionListener() {
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
                    currentSeriesEpisodesMap = episodesMap;
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
            } else if (keyCode == KeyEvent.KEYCODE_GUIDE || keyCode == KeyEvent.KEYCODE_INFO) {
                if (currentMode == ScreenMode.FULLSCREEN) {
                    toggleDrawer();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                showProviderOptionsDialog();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (exoPlayer != null) {
                    if (exoPlayer.isPlaying()) exoPlayer.pause();
                    else exoPlayer.play();
                    if (isPlayingVod) updateVodProgress();
                    showOsdBanner(3000);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (isPlayingVod && exoPlayer != null) {
                    long dur = exoPlayer.getDuration();
                    long target = dur > 0 ? Math.min(dur, exoPlayer.getCurrentPosition() + 15000) : exoPlayer.getCurrentPosition() + 15000;
                    exoPlayer.seekTo(target);
                    updateVodProgress();
                    showOsdBanner(5000);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                if (isPlayingVod && exoPlayer != null) {
                    long target = Math.max(0, exoPlayer.getCurrentPosition() - 15000);
                    exoPlayer.seekTo(target);
                    updateVodProgress();
                    showOsdBanner(5000);
                    return true;
                }
            }

            // CENÁRIO 1: GAVETA LATERAL ESTÁ ABERTA (qualquer modo de tela)
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
                // UP e DOWN navegam normalmente pelos itens do RecyclerView
            } else if (currentMode == ScreenMode.FULLSCREEN) {
                // CENÁRIO 2: GAVETA LATERAL ESTÁ FECHADA EM TELA CHEIA
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

                        // ENTER / OK confirma a troca imediata se estiver zapeando, abre a gaveta se o OSD já estiver visível, ou exibe o OSD se oculto
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (pendingZapChannelIdx >= 0) {
                                confirmPendingZapChannel();
                                return true;
                            } else if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                                openDrawer();
                                return true;
                            } else {
                                if (isPlayingEmbed) {
                                    triggerAutoplayTap();
                                }
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
                        // Em VOD (Filme ou Série):
                        // D-pad Esquerdo abre a gaveta lateral com episódios da série ou lista de filmes!
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            openDrawer();
                            return true;
                        }
                        // D-pad Direito avança 30s
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (exoPlayer != null && exoPlayer.getDuration() > 0) {
                                long target = Math.min(exoPlayer.getDuration(), exoPlayer.getCurrentPosition() + 30000);
                                exoPlayer.seekTo(target);
                                updateVodProgress();
                                showOsdBanner(5000);
                                return true;
                            }
                        }
                        // ENTER abre a gaveta se OSD já estiver visível, senão exibe o OSD
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                                openDrawer();
                                return true;
                            } else {
                                updateVodProgress();
                                showOsdBanner(5000);
                                return true;
                            }
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            updateVodProgress();
                            showOsdBanner(5000);
                            return true;
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
                stopVodProgressTicker();
                destroyCurrentStream();
                isPlayingVod = false;
                ScreenMode target = (previousMode == ScreenMode.SERIES_DETAIL ? ScreenMode.SERIES_DETAIL : ScreenMode.VOD);
                activeVodMovie = null;
                activeVodEpisode = null;
                setScreenMode(target);
                return true;
            }
            setScreenMode(ScreenMode.CENTRAL);
            return true;
        }

        if (currentMode == ScreenMode.SERIES_DETAIL) {
            destroyCurrentStream();
            isPlayingVod = false;
            setScreenMode(ScreenMode.VOD);
            return true;
        }

        if (currentMode == ScreenMode.VOD) {
            destroyCurrentStream();
            isPlayingVod = false;
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

    private void showProviderOptionsDialog() {
        List<String> currentPriority = ProviderManager.getPriorityList(this);

        String[] options = new String[] {
                "⭐ 1º StreamVerde | 2º RDCanais | 3º RDEmbed",
                "⚡ 1º RDCanais | 2º RDEmbed | 3º StreamVerde (Padrão)",
                "🚀 1º RDEmbed | 2º StreamVerde | 3º RDCanais",
                "🟢 1º StreamVerde | 2º RDEmbed | 3º RDCanais",
                "🛠️ Escolher Provedor Primário (1º Lugar)..."
        };

        int selectedIndex = 1;
        if (currentPriority.size() >= 2) {
            String p0 = currentPriority.get(0);
            String p1 = currentPriority.get(1);
            if (ProviderManager.PROVIDER_STREAMVERDE.equals(p0) && ProviderManager.PROVIDER_RDCANAIS.equals(p1)) {
                selectedIndex = 0;
            } else if (ProviderManager.PROVIDER_RDCANAIS.equals(p0) && ProviderManager.PROVIDER_RDEMBED.equals(p1)) {
                selectedIndex = 1;
            } else if (ProviderManager.PROVIDER_RDEMBED.equals(p0)) {
                selectedIndex = 2;
            } else if (ProviderManager.PROVIDER_STREAMVERDE.equals(p0) && ProviderManager.PROVIDER_RDEMBED.equals(p1)) {
                selectedIndex = 3;
            }
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("⚙️ Prioridade dos Provedores de TV")
                .setSingleChoiceItems(options, selectedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        ProviderManager.setPriorityList(MainActivity.this, Arrays.asList(
                                ProviderManager.PROVIDER_STREAMVERDE,
                                ProviderManager.PROVIDER_RDCANAIS,
                                ProviderManager.PROVIDER_RDEMBED
                        ));
                        applyProviderChange("StreamVerde (streamverde.net)");
                    } else if (which == 1) {
                        ProviderManager.setPriorityList(MainActivity.this, Arrays.asList(
                                ProviderManager.PROVIDER_RDCANAIS,
                                ProviderManager.PROVIDER_RDEMBED,
                                ProviderManager.PROVIDER_STREAMVERDE
                        ));
                        applyProviderChange("RDCanais (rdcanais.net)");
                    } else if (which == 2) {
                        ProviderManager.setPriorityList(MainActivity.this, Arrays.asList(
                                ProviderManager.PROVIDER_RDEMBED,
                                ProviderManager.PROVIDER_STREAMVERDE,
                                ProviderManager.PROVIDER_RDCANAIS
                        ));
                        applyProviderChange("RDEmbed (v2.rdembed.sbs)");
                    } else if (which == 3) {
                        ProviderManager.setPriorityList(MainActivity.this, Arrays.asList(
                                ProviderManager.PROVIDER_STREAMVERDE,
                                ProviderManager.PROVIDER_RDEMBED,
                                ProviderManager.PROVIDER_RDCANAIS
                        ));
                        applyProviderChange("StreamVerde (streamverde.net)");
                    } else if (which == 4) {
                        showCustomProviderOrderDialog();
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void showCustomProviderOrderDialog() {
        String[] providers = new String[] {
                "StreamVerde (streamverde.net)",
                "RDCanais (rdcanais.net)",
                "RDEmbed (v2.rdembed.sbs)"
        };
        final String[] provIds = new String[] {
                ProviderManager.PROVIDER_STREAMVERDE,
                ProviderManager.PROVIDER_RDCANAIS,
                ProviderManager.PROVIDER_RDEMBED
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Escolha o 1º Provedor (Primário)")
                .setItems(providers, (dialog, which) -> {
                    String chosen1 = provIds[which];
                    showSecondaryProviderDialog(chosen1);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showSecondaryProviderDialog(String primaryId) {
        List<String> remaining = new ArrayList<>();
        if (!ProviderManager.PROVIDER_STREAMVERDE.equals(primaryId)) remaining.add(ProviderManager.PROVIDER_STREAMVERDE);
        if (!ProviderManager.PROVIDER_RDCANAIS.equals(primaryId)) remaining.add(ProviderManager.PROVIDER_RDCANAIS);
        if (!ProviderManager.PROVIDER_RDEMBED.equals(primaryId)) remaining.add(ProviderManager.PROVIDER_RDEMBED);

        String[] labels = new String[remaining.size()];
        for (int i = 0; i < remaining.size(); i++) {
            labels[i] = ProviderManager.getProviderDisplayName(remaining.get(i));
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Escolha o 2º Provedor (Secundário)")
                .setItems(labels, (dialog, which) -> {
                    String chosen2 = remaining.get(which);
                    String chosen3 = "";
                    for (String r : remaining) {
                        if (!r.equals(chosen2)) {
                            chosen3 = r;
                            break;
                        }
                    }
                    ProviderManager.setPriorityList(MainActivity.this, Arrays.asList(primaryId, chosen2, chosen3));
                    applyProviderChange(ProviderManager.getProviderDisplayName(primaryId));
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void applyProviderChange(String primaryName) {
        Toast.makeText(this, "Prioridade salva! 1º: " + primaryName, Toast.LENGTH_SHORT).show();
        // Se houver canal sintonizado, recarrega com o novo provedor primário
        if (!allChannels.isEmpty() && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size() && !isPlayingVod) {
            destroyCurrentStream();
            tuneChannel(currentChannelIdx, currentMode == ScreenMode.FULLSCREEN);
        }
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
        if (isPlayingVod) {
            saveCurrentVodProgress();
        }
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
