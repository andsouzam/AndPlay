package com.andplay.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import com.andplay.app.provider.ProviderManager;
import java.util.Arrays;
import java.util.Collections;
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
import android.webkit.WebResourceError;
import android.widget.EditText;
import android.view.inputmethod.EditorInfo;
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

import android.graphics.Color;
import com.andplay.app.adapter.CategoryPillAdapter;
import com.andplay.app.adapter.ChannelRailAdapter;
import com.andplay.app.adapter.EpisodeAdapter;
import com.andplay.app.adapter.MoviePosterAdapter;
import com.andplay.app.adapter.SportsRailAdapter;
import com.andplay.app.adapter.TimelineAdapter;
import com.andplay.app.api.ApiClient;
import com.andplay.app.epg.EpgEngine;
import com.andplay.app.epg.EpgEngine.TimelineProgram;
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
import java.util.Objects;
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
    private FrameLayout centralLayout;
    private NestedScrollView centralScroll;
    private TextView headerClock;
    private TextView headerDate;
    private View btnHeaderOptions;
    private View btnDrawerOptions;
    private FrameLayout pipContainer;
    private View pipOverlayBar;
    private TextView pipChannelName;
    private TextView pipProgramTitle;
    private final Handler pipOverlayHandler = new Handler(Looper.getMainLooper());
    private final Runnable pipOverlayHideRunnable = () -> {
        if (pipOverlayBar != null) {
            pipOverlayBar.setVisibility(View.GONE);
        }
    };

    private void showPipOverlay() {
        if (pipOverlayBar == null) return;
        pipOverlayBar.setVisibility(View.VISIBLE);
        pipOverlayHandler.removeCallbacks(pipOverlayHideRunnable);
        pipOverlayHandler.postDelayed(pipOverlayHideRunnable, 5000);
    }
    private LinearLayout btnNavMovies, btnNavSeries, btnNavSports, btnNavEpg;
    private RecyclerView channelsRail;
    private RecyclerView sportsRail;
    private RecyclerView moviesRail;
    private RecyclerView seriesRail;
    private static final int REQ_SPORTS_HUB = 1001;

    // Fullscreen Views
    private FrameLayout fullscreenLayout;
    private LinearLayout topChannelBadge;
    private TextView topChNum, topChName;
    private LinearLayout osdBanner;
    private TextView osdChNum, osdChName, osdClock, osdNowTitle, osdRemaining, osdSynopsis, osdNextProgram, osdSportsHint;
    private ProgressBar osdProgressBar;

    // Lateral EPG Drawer
    private LinearLayout epgDrawer;
    private TextView drawerHeaderTitle;
    private RecyclerView drawerCatsRecycler;
    private RecyclerView drawerChannelsRecycler;

    // VOD Views
    private LinearLayout vodLayout;
    private TextView vodHeroTitle, vodHeroRating, vodHeroYear, vodHeroGenre, vodHeroPlot;
    private TextView btnVodSearch;
    private TextView btnVodWatched;
    private TextView btnVodPlaylist;
    private List<Movie> activeMoviePlaylistQueue = null;
    private int activeMoviePlaylistIndex = 0;
    private String activeMoviePlaylistName = null;

    public static class MoviePlaylist {
        public String id;
        public String name;
        public List<String> movieIds = new ArrayList<>();
    }

    private boolean isViewingSeries = false;
    private RecyclerView vodCatsRecycler;
    private RecyclerView vodGridRecycler;

    // Series Detail Views
    private LinearLayout seriesDetailLayout;
    private TextView seriesDetailTitle, seriesDetailRating, seriesDetailYear, seriesDetailGenre, seriesDetailPlot;
    private RecyclerView seriesSeasonsRecycler;
    private RecyclerView seriesEpisodesRecycler;
    private List<String> currentSeriesSeasonKeys = new ArrayList<>();
    private int currentSeriesSeasonIdx = 0;

    // Full EPG Guide Views
    private LinearLayout fullGuideLayout;
    private TextView guideClock;
    private TextView guideHeroChannelBadge, guideHeroStatusBadge, guideHeroTime, guideHeroRemaining;
    private TextView guideHeroTitle, guideHeroSynopsis;
    private ProgressBar guideHeroProgress;
    private TextView guidePrevChannelHint, guideChannelTitle, guideNextChannelHint;
    private RecyclerView guideTimelineRecycler;
    private TextView guideNextChannelPreviewLabel;
    private RecyclerView guideNextTimelineRecycler;
    private TimelineAdapter guideTimelineAdapter;
    private int guideSelectedChannelIdx = 0;
    private List<TimelineProgram> guideCurrentTimeline = new ArrayList<>();
    private boolean suppressNextEnterUp = false;
    private ScreenMode previousGuideMode = null;

    // Direct Channel Number Tuning
    private final StringBuilder channelNumberBuffer = new StringBuilder();
    private final Handler channelNumberHandler = new Handler(Looper.getMainLooper());
    private final Runnable channelNumberCommitRunnable = this::commitChannelNumberInput;

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
    private long pendingVodSeekPositionMs = 0;

    private Movie activeVodMovie = null;
    private Series activeVodSeries = null;
    private Episode activeVodEpisode = null;
    private String activeVodSeasonNum = "1";
    private Map<String, List<Episode>> currentSeriesEpisodesMap = new HashMap<>();
    private String currentVodType = "movies";
    private boolean isPlayingSportsEvent = false;
    private SportsEvent activeSportsEvent = null;
    private OkHttpClient sharedOkHttpClient;

    // Sports Standings Overlay
    private View sportsStandingsOverlay;
    private TextView standingsLeagueName;
    private TextView standingsMatchScore;
    private TextView tabStandings;
    private TextView tabRound;
    private ProgressBar standingsProgress;
    private TextView standingsEmptyMsg;
    private LinearLayout standingsTablePanel;
    private LinearLayout roundMatchesPanel;
    private RecyclerView standingsRecycler;
    private RecyclerView roundMatchesRecycler;
    private boolean standingsOverlayVisible = false;
    private String standingsActiveTab = "standings"; // "standings" | "round"


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
    private AlertDialog activeSearchDialog = null;

    // Mosaico (Multi-View 2 ou 4 Telas)
    private LinearLayout mosaicLayout;
    private LinearLayout mosaicRow1, mosaicRow2;
    private TextView btnDrawerMosaic;
    private boolean isMosaicActive = false;
    private int mosaicScreenCount = 2;
    private int mosaicTargetSlotIdx = -1;
    private long lastMosaicBackAt = 0;
    private int mosaicInitialChannelIdx = 0;
    private ScreenMode preMosaicMode = ScreenMode.FULLSCREEN;
    private static final Map<String, byte[]> STATIC_WEB_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private int currentMosaicFocusedIdx = 0;

    private static class MosaicSlotItem {
        FrameLayout slotView;
        FrameLayout playerHost;
        View overlayView;
        Runnable hideOverlayRunnable;
        TextView titleView;
        TextView audioView;
        Channel channel;
        WebView webView;
        ExoPlayer exoPlayer;
        PlayerView playerView;
        boolean isPlayingEmbed;
        boolean isUnavailable = false;
        List<Channel.StreamFallback> fallbacks;
        int currentFallbackIdx = 0;
    }
    private final MosaicSlotItem[] mosaicSlots = new MosaicSlotItem[4];

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
        if (pipPlayerHost != null) {
            pipPlayerHost.setFocusable(false);
            pipPlayerHost.setFocusableInTouchMode(false);
            pipPlayerHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        fullscreenPlayerHost = findViewById(R.id.fullscreenPlayerHost);

        // Central
        centralLayout = findViewById(R.id.centralLayout);
        centralScroll = findViewById(R.id.centralScroll);
        headerClock = findViewById(R.id.headerClock);
        headerDate = findViewById(R.id.headerDate);
        btnHeaderOptions = findViewById(R.id.btnHeaderOptions);
        pipContainer = findViewById(R.id.pipContainer);
        pipOverlayBar = findViewById(R.id.pipOverlayBar);
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
        osdSportsHint  = findViewById(R.id.osdSportsHint);
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
        btnVodSearch = findViewById(R.id.btnVodSearch);
        if (btnVodSearch != null) {
            btnVodSearch.setOnClickListener(v -> showVodSearchDialog());
            btnVodSearch.setOnFocusChangeListener((v, hasFocus) -> {
                btnVodSearch.setTextColor(hasFocus ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
            });
        }
        btnVodWatched = findViewById(R.id.btnVodWatched);
        if (btnVodWatched != null) {
            btnVodWatched.setOnClickListener(v -> showVodWatchedContent());
            btnVodWatched.setOnFocusChangeListener((v, hasFocus) -> {
                btnVodWatched.setTextColor(hasFocus ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
            });
        }
        btnVodPlaylist = findViewById(R.id.btnVodPlaylist);
        if (btnVodPlaylist != null) {
            btnVodPlaylist.setOnClickListener(v -> showMoviePlaylistsDialog());
            btnVodPlaylist.setOnFocusChangeListener((v, hasFocus) -> {
                btnVodPlaylist.setTextColor(hasFocus ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
            });
        }

        // Series Detail
        seriesDetailLayout = findViewById(R.id.seriesDetailLayout);
        seriesDetailTitle = findViewById(R.id.seriesDetailTitle);
        seriesDetailRating = findViewById(R.id.seriesDetailRating);
        seriesDetailYear = findViewById(R.id.seriesDetailYear);
        seriesDetailGenre = findViewById(R.id.seriesDetailGenre);
        seriesDetailPlot = findViewById(R.id.seriesDetailPlot);
        seriesSeasonsRecycler = findViewById(R.id.seriesSeasonsRecycler);
        seriesEpisodesRecycler = findViewById(R.id.seriesEpisodesRecycler);

        // Full EPG Guide
        fullGuideLayout = findViewById(R.id.fullGuideLayout);
        guideClock = findViewById(R.id.guideClock);
        guideHeroChannelBadge = findViewById(R.id.guideHeroChannelBadge);
        guideHeroStatusBadge = findViewById(R.id.guideHeroStatusBadge);
        guideHeroTime = findViewById(R.id.guideHeroTime);
        guideHeroRemaining = findViewById(R.id.guideHeroRemaining);
        guideHeroTitle = findViewById(R.id.guideHeroTitle);
        guideHeroProgress = findViewById(R.id.guideHeroProgress);
        guideHeroSynopsis = findViewById(R.id.guideHeroSynopsis);
        guidePrevChannelHint = findViewById(R.id.guidePrevChannelHint);
        guideChannelTitle = findViewById(R.id.guideChannelTitle);
        guideNextChannelHint = findViewById(R.id.guideNextChannelHint);
        guideTimelineRecycler = findViewById(R.id.guideTimelineRecycler);
        guideTimelineRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        guideNextChannelPreviewLabel = findViewById(R.id.guideNextChannelPreviewLabel);
        guideNextTimelineRecycler = findViewById(R.id.guideNextTimelineRecycler);
        guideNextTimelineRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        // Loading
        loadingLayout = findViewById(R.id.loadingLayout);
        loadingText = findViewById(R.id.loadingText);

        // Mosaico
        mosaicLayout = findViewById(R.id.mosaicLayout);
        mosaicRow1 = findViewById(R.id.mosaicRow1);
        mosaicRow2 = findViewById(R.id.mosaicRow2);
        btnDrawerMosaic = findViewById(R.id.btnDrawerMosaic);
        initMosaicSlots();

        // Sports Standings Overlay
        sportsStandingsOverlay = findViewById(R.id.sportsStandingsOverlay);
        standingsLeagueName    = findViewById(R.id.standingsLeagueName);
        standingsMatchScore    = findViewById(R.id.standingsMatchScore);
        tabStandings           = findViewById(R.id.tabStandings);
        tabRound               = findViewById(R.id.tabRound);
        standingsProgress      = findViewById(R.id.standingsProgress);
        standingsEmptyMsg      = findViewById(R.id.standingsEmptyMsg);
        standingsTablePanel    = findViewById(R.id.standingsTablePanel);
        roundMatchesPanel      = findViewById(R.id.roundMatchesPanel);
        standingsRecycler      = findViewById(R.id.standingsRecycler);
        roundMatchesRecycler   = findViewById(R.id.roundMatchesRecycler);

        if (standingsRecycler != null) {
            standingsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        }
        if (roundMatchesRecycler != null) {
            roundMatchesRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        }

        // Fundo escurecido: toque fecha o overlay
        View standingsDim = findViewById(R.id.standingsDim);
        if (standingsDim != null) {
            standingsDim.setOnClickListener(v -> hideSportsOverlay());
        }

        // Abas
        if (tabStandings != null) {
            tabStandings.setOnClickListener(v -> switchStandingsTab("standings"));
            tabStandings.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) switchStandingsTab("standings");
            });
        }
        if (tabRound != null) {
            tabRound.setOnClickListener(v -> switchStandingsTab("round"));
            tabRound.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) switchStandingsTab("round");
            });
        }
    }

    private void initMosaicSlots() {
        for (int i = 0; i < 4; i++) {
            mosaicSlots[i] = new MosaicSlotItem();
        }
        mosaicSlots[0].slotView = findViewById(R.id.mosaicSlot1);
        mosaicSlots[0].playerHost = findViewById(R.id.mosaicSlotPlayer1);
        mosaicSlots[0].overlayView = findViewById(R.id.mosaicSlotOverlay1);
        mosaicSlots[0].titleView = findViewById(R.id.mosaicSlotTitle1);
        mosaicSlots[0].audioView = findViewById(R.id.mosaicSlotAudio1);

        mosaicSlots[1].slotView = findViewById(R.id.mosaicSlot2);
        mosaicSlots[1].playerHost = findViewById(R.id.mosaicSlotPlayer2);
        mosaicSlots[1].overlayView = findViewById(R.id.mosaicSlotOverlay2);
        mosaicSlots[1].titleView = findViewById(R.id.mosaicSlotTitle2);
        mosaicSlots[1].audioView = findViewById(R.id.mosaicSlotAudio2);

        mosaicSlots[2].slotView = findViewById(R.id.mosaicSlot3);
        mosaicSlots[2].playerHost = findViewById(R.id.mosaicSlotPlayer3);
        mosaicSlots[2].overlayView = findViewById(R.id.mosaicSlotOverlay3);
        mosaicSlots[2].titleView = findViewById(R.id.mosaicSlotTitle3);
        mosaicSlots[2].audioView = findViewById(R.id.mosaicSlotAudio3);

        mosaicSlots[3].slotView = findViewById(R.id.mosaicSlot4);
        mosaicSlots[3].playerHost = findViewById(R.id.mosaicSlotPlayer4);
        mosaicSlots[3].overlayView = findViewById(R.id.mosaicSlotOverlay4);
        mosaicSlots[3].titleView = findViewById(R.id.mosaicSlotTitle4);
        mosaicSlots[3].audioView = findViewById(R.id.mosaicSlotAudio4);

        for (int i = 0; i < 4; i++) {
            final int slotIdx = i;
            MosaicSlotItem slot = mosaicSlots[i];
            if (slot.playerHost != null) {
                slot.playerHost.setFocusable(false);
                slot.playerHost.setFocusableInTouchMode(false);
                slot.playerHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            if (slot.slotView != null) {
                slot.slotView.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && isMosaicActive) {
                        onMosaicSlotFocused(slotIdx);
                    }
                });
                slot.slotView.setOnClickListener(v -> {
                    if (isMosaicActive) {
                        onMosaicSlotClicked(slotIdx);
                    }
                });
                slot.slotView.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        if (isMosaicActive) {
                            onMosaicSlotClicked(slotIdx);
                            return true;
                        }
                    }
                    return false;
                });
            }
        }
    }

    private void showMosaicSlotOverlay(int slotIdx) {
        if (slotIdx < 0 || slotIdx >= 4) return;
        MosaicSlotItem slot = mosaicSlots[slotIdx];
        if (slot == null || slot.overlayView == null) return;
        slot.overlayView.setVisibility(View.VISIBLE);
        if (slot.hideOverlayRunnable != null) {
            mainHandler.removeCallbacks(slot.hideOverlayRunnable);
        }
        slot.hideOverlayRunnable = () -> {
            if (isMosaicActive && slot.overlayView != null) {
                slot.overlayView.setVisibility(View.GONE);
            }
        };
        mainHandler.postDelayed(slot.hideOverlayRunnable, 5000);
    }

    private void setMosaicSlotFocus(int targetIdx) {
        if (targetIdx < 0 || targetIdx >= mosaicScreenCount) return;
        currentMosaicFocusedIdx = targetIdx;
        for (int i = 0; i < 4; i++) {
            if (mosaicSlots[i] != null && mosaicSlots[i].slotView != null) {
                if (i == targetIdx) {
                    mosaicSlots[i].slotView.requestFocus();
                } else {
                    mosaicSlots[i].slotView.clearFocus();
                }
            }
        }
        onMosaicSlotFocused(targetIdx);
    }

    private void moveMosaicFocus(int deltaX, int deltaY) {
        int current = currentMosaicFocusedIdx;
        int target = current;
        if (mosaicScreenCount == 2) {
            if (deltaX < 0) target = 0;
            else if (deltaX > 0) target = 1;
        } else {
            int row = current / 2;
            int col = current % 2;
            if (deltaX != 0) {
                col = Math.max(0, Math.min(1, col + deltaX));
            }
            if (deltaY != 0) {
                row = Math.max(0, Math.min(1, row + deltaY));
            }
            target = row * 2 + col;
        }
        if (target != current) {
            setMosaicSlotFocus(target);
        } else {
            showMosaicSlotOverlay(current);
        }
    }

    private AlertDialog.Builder createThemedDialogBuilder() {
        return new AlertDialog.Builder(this, R.style.Theme_EPlay_Dialog);
    }

    private void styleDialogButtons(AlertDialog dialog) {
        if (dialog == null) return;
        dialog.setOnShowListener(d -> {
            Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            Button neu = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
                if (v instanceof Button) {
                    ((Button) v).setTextColor(hasFocus ? Color.BLACK : Color.WHITE);
                }
            };
            if (pos != null) {
                pos.setBackgroundResource(R.drawable.pill_focus_bg);
                pos.setTextColor(pos.hasFocus() ? Color.BLACK : Color.WHITE);
                pos.setTextSize(12);
                pos.setTypeface(null, Typeface.BOLD);
                pos.setOnFocusChangeListener(focusListener);
            }
            if (neg != null) {
                neg.setBackgroundResource(R.drawable.pill_focus_bg);
                neg.setTextColor(neg.hasFocus() ? Color.BLACK : Color.WHITE);
                neg.setTextSize(12);
                neg.setTypeface(null, Typeface.BOLD);
                neg.setOnFocusChangeListener(focusListener);
            }
            if (neu != null) {
                neu.setBackgroundResource(R.drawable.pill_focus_bg);
                neu.setTextColor(neu.hasFocus() ? Color.BLACK : Color.WHITE);
                neu.setTextSize(12);
                neu.setTypeface(null, Typeface.BOLD);
                neu.setOnFocusChangeListener(focusListener);
            }
        });
    }

    private void showMosaicDialog() {
        closeDrawer();
        String[] options = new String[] {
                "2 Telas (Lado a Lado)",
                "4 Telas (Grade 2x2)"
        };
        AlertDialog dialog = createThemedDialogBuilder()
                .setTitle("⊞ Modo Mosaico (Multi-View)")
                .setItems(options, (d, which) -> {
                    d.dismiss();
                    int count = (which == 0) ? 2 : 4;
                    enterMosaicMode(count);
                })
                .setNegativeButton("Cancelar", null)
                .create();
        styleDialogButtons(dialog);
        dialog.show();
    }

    private void enterMosaicMode(int screenCount) {
        if (isMosaicActive) {
            exitMosaicMode(false);
        }
        isMosaicActive = true;
        mosaicScreenCount = screenCount;
        mosaicInitialChannelIdx = currentChannelIdx;
        preMosaicMode = currentMode;

        closeDrawer();
        hideOsdBanner();
        if (fullGuideLayout != null) fullGuideLayout.setVisibility(View.GONE);

        centralLayout.setVisibility(View.GONE);
        fullscreenLayout.setVisibility(View.GONE);
        vodLayout.setVisibility(View.GONE);
        seriesDetailLayout.setVisibility(View.GONE);

        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        if (unifiedEmbedWebView != null) {
            unifiedEmbedWebView.stopLoading();
            unifiedEmbedWebView.loadUrl("about:blank");
        }

        mosaicLayout.setVisibility(View.VISIBLE);
        if (screenCount == 2) {
            mosaicRow1.setVisibility(View.VISIBLE);
            mosaicRow2.setVisibility(View.GONE);
        } else {
            mosaicRow1.setVisibility(View.VISIBLE);
            mosaicRow2.setVisibility(View.VISIBLE);
        }

        for (int i = 0; i < 4; i++) {
            MosaicSlotItem slot = mosaicSlots[i];
            clearMosaicSlot(slot);
            if (slot.titleView != null) {
                slot.titleView.setText("TELA " + (i + 1) + " - Pressione OK para Canal");
            }
            if (slot.audioView != null) {
                slot.audioView.setText("🔇 MUDO");
                slot.audioView.setTextColor(android.graphics.Color.parseColor("#888888"));
            }
            if (i < screenCount) {
                showMosaicSlotOverlay(i);
            } else {
                if (slot.overlayView != null) {
                    slot.overlayView.setVisibility(View.GONE);
                }
            }
        }

        Channel initialCh = (!allChannels.isEmpty() && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size())
                ? allChannels.get(currentChannelIdx) : null;
        if (initialCh != null) {
            tuneMosaicSlot(0, initialCh);
        }

        currentMosaicFocusedIdx = 0;
        if (mosaicSlots[0].slotView != null) {
            mosaicSlots[0].slotView.postDelayed(() -> {
                if (mosaicSlots[0].slotView != null) {
                    setMosaicSlotFocus(0);
                }
            }, 150);
        }
    }

    private void onMosaicSlotFocused(int focusedIdx) {
        currentMosaicFocusedIdx = focusedIdx;

        // 1. Muta todos os outros slots primeiro para garantir que nunca existam dois fluxos de áudio simultâneos no Chromium
        for (int i = 0; i < 4; i++) {
            if (i != focusedIdx) {
                MosaicSlotItem slot = mosaicSlots[i];
                if (slot.isUnavailable) {
                    if (slot.audioView != null) {
                        slot.audioView.setText("⚠️ INDISPONÍVEL");
                        slot.audioView.setTextColor(android.graphics.Color.parseColor("#FF5555"));
                    }
                } else {
                    if (slot.audioView != null) {
                        slot.audioView.setText("🔇 MUDO");
                        slot.audioView.setTextColor(android.graphics.Color.parseColor("#888888"));
                    }
                    setSlotAudioMuted(slot, true);
                }
                if (slot.hideOverlayRunnable != null) {
                    mainHandler.removeCallbacks(slot.hideOverlayRunnable);
                }
                if (slot.overlayView != null) {
                    slot.overlayView.setVisibility(View.GONE);
                }
            }
        }

        // 2. Desmuta exclusivamente o slot selecionado
        if (focusedIdx >= 0 && focusedIdx < 4) {
            MosaicSlotItem focusedSlot = mosaicSlots[focusedIdx];
            if (focusedSlot.isUnavailable) {
                if (focusedSlot.audioView != null) {
                    focusedSlot.audioView.setText("⚠️ INDISPONÍVEL");
                    focusedSlot.audioView.setTextColor(android.graphics.Color.parseColor("#FF5555"));
                }
            } else {
                if (focusedSlot.audioView != null) {
                    focusedSlot.audioView.setText("🔊 ÁUDIO");
                    focusedSlot.audioView.setTextColor(android.graphics.Color.parseColor("#FFD700"));
                }
                setSlotAudioMuted(focusedSlot, false);
            }
            showMosaicSlotOverlay(focusedIdx);
        }
    }

    private void setSlotAudioMuted(MosaicSlotItem slot, boolean muted) {
        if (slot == null || slot.isUnavailable) return;
        if (slot.exoPlayer != null) {
            slot.exoPlayer.setVolume(muted ? 0.0f : 1.0f);
        }
        if (slot.webView != null) {
            String script = "(function() {" +
                    "  var m = " + (muted ? "true" : "false") + ";" +
                    "  window.__andplay_muted = m;" +
                    "  function apply(win) {" +
                    "    try {" +
                    "      var media = win.document.querySelectorAll('video, audio');" +
                    "      for (var i = 0; i < media.length; i++) {" +
                    "        var v = media[i];" +
                    "        v.muted = m;" +
                    "        v.volume = m ? 0.0 : 1.0;" +
                    "        if (!m && v.paused) { v.play().catch(function(){}); }" +
                    "      }" +
                    "    } catch(e) {}" +
                    "    try {" +
                    "      if (typeof win.jwplayer === 'function') {" +
                    "        var p = win.jwplayer();" +
                    "        if (p && typeof p.setMute === 'function') {" +
                    "          p.setMute(m);" +
                    "          p.setVolume(m ? 0 : 100);" +
                    "          if (!m) {" +
                    "            var s = typeof p.getState === 'function' ? p.getState() : '';" +
                    "            if (s === 'paused' || s === 'idle') p.play();" +
                    "          }" +
                    "        }" +
                    "      }" +
                    "    } catch(e) {}" +
                    "    try {" +
                    "      if (win.__andplay_bitmovin_instances) {" +
                    "        for (var bi = 0; bi < win.__andplay_bitmovin_instances.length; bi++) {" +
                    "          var bp = win.__andplay_bitmovin_instances[bi];" +
                    "          if (bp) {" +
                    "            if (m) {" +
                    "              if (typeof bp.mute === 'function') bp.mute();" +
                    "            } else {" +
                    "              if (typeof bp.unmute === 'function') bp.unmute();" +
                    "              if (typeof bp.setVolume === 'function') bp.setVolume(100);" +
                    "              if (typeof bp.isPaused === 'function' && bp.isPaused()) bp.play();" +
                    "            }" +
                    "          }" +
                    "        }" +
                    "      }" +
                    "    } catch(e) {}" +
                    "    try {" +
                    "      if (win.frames && win.frames.length > 0) {" +
                    "        for (var j = 0; j < win.frames.length; j++) {" +
                    "          try {" +
                    "            win.frames[j].postMessage({ type: 'ANDPLAY_AUDIO_CONTROL', muted: m }, '*');" +
                    "            apply(win.frames[j]);" +
                    "          } catch(err) {}" +
                    "        }" +
                    "      }" +
                    "    } catch(e) {}" +
                    "  }" +
                    "  apply(window);" +
                    "})();";
            slot.webView.evaluateJavascript(script, null);
        }
    }

    private void onMosaicSlotClicked(int slotIdx) {
        mosaicTargetSlotIdx = slotIdx;
        openDrawer();
    }

    private void tuneMosaicSlot(int slotIdx, Channel ch) {
        if (slotIdx < 0 || slotIdx >= 4 || ch == null) return;
        MosaicSlotItem slot = mosaicSlots[slotIdx];
        clearMosaicSlot(slot);
        slot.channel = ch;
        slot.fallbacks = ch.getFallbacks(this);
        slot.currentFallbackIdx = 0;

        showMosaicSlotOverlay(slotIdx);
        playMosaicSlotFallback(slotIdx);
    }

    private void tryNextMosaicFallback(int slotIdx) {
        if (slotIdx < 0 || slotIdx >= 4) return;
        MosaicSlotItem slot = mosaicSlots[slotIdx];
        if (slot == null || slot.isUnavailable || slot.fallbacks == null) return;
        slot.currentFallbackIdx++;
        if (slot.currentFallbackIdx < slot.fallbacks.size()) {
            Log.i("Mosaic", "Slot " + slotIdx + " alternando para fallback " + slot.currentFallbackIdx + ": " + slot.fallbacks.get(slot.currentFallbackIdx).name);
            playMosaicSlotFallback(slotIdx);
        } else {
            Log.w("Mosaic", "Slot " + slotIdx + ": Todos os servidores e fallbacks falharam.");
            if (slot.titleView != null) {
                int chNum = allChannels.indexOf(slot.channel) + 1;
                slot.titleView.setText(String.format(Locale.getDefault(), "TELA %d - %03d %s (Sem Sinal)", slotIdx + 1, chNum, slot.channel != null ? slot.channel.name : ""));
            }
        }
    }

    private void onBitmovinDetectedInMosaic(int slotIdx) {
        if (!isMosaicActive || slotIdx < 0 || slotIdx >= 4) return;
        MosaicSlotItem slot = mosaicSlots[slotIdx];
        if (slot == null || slot.isUnavailable) return;
        slot.isUnavailable = true;
        Log.w("Mosaic", "Bitmovin bloqueado no slot " + slotIdx + ". Conexão finalizada e exibindo mensagem de indisponível.");

        // 1. Encerra a conexão imediatamente e destrói o WebView/Player
        if (slot.webView != null) {
            try {
                slot.webView.stopLoading();
                slot.webView.loadUrl("about:blank");
                slot.webView.clearHistory();
                slot.webView.destroy();
            } catch (Exception ignored) {}
            slot.webView = null;
        }
        if (slot.exoPlayer != null) {
            try {
                slot.exoPlayer.stop();
                slot.exoPlayer.release();
            } catch (Exception ignored) {}
            slot.exoPlayer = null;
        }
        slot.playerView = null;
        slot.isPlayingEmbed = false;

        // 2. Exibe mensagem centralizada no grid
        if (slot.playerHost != null) {
            slot.playerHost.removeAllViews();

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(android.view.Gravity.CENTER);
            container.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));
            container.setBackgroundColor(Color.parseColor("#141414"));

            TextView iconView = new TextView(this);
            iconView.setText("⚠️");
            iconView.setTextSize(34);
            iconView.setGravity(android.view.Gravity.CENTER);
            container.addView(iconView);

            TextView msgView = new TextView(this);
            msgView.setText("Canal indisponível para Mosaico");
            msgView.setTextColor(Color.WHITE);
            msgView.setTextSize(16);
            msgView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            msgView.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(16, 12, 16, 6);
            msgView.setLayoutParams(lp);
            container.addView(msgView);

            TextView hintView = new TextView(this);
            hintView.setText("Pressione OK para trocar de canal");
            hintView.setTextColor(Color.parseColor("#888888"));
            hintView.setTextSize(12);
            hintView.setGravity(android.view.Gravity.CENTER);
            container.addView(hintView);

            slot.playerHost.addView(container);
        }

        // 3. Atualiza header do slot
        if (slot.titleView != null) {
            String chName = (slot.channel != null && slot.channel.name != null) ? slot.channel.name : "";
            int chNum = allChannels.indexOf(slot.channel) + 1;
            slot.titleView.setText(String.format(Locale.getDefault(), "TELA %d - %03d %s (Indisponível no Mosaico)", slotIdx + 1, chNum, chName));
        }
        if (slot.audioView != null) {
            slot.audioView.setText("⚠️ INDISPONÍVEL");
            slot.audioView.setTextColor(Color.parseColor("#FF5555"));
        }
        showMosaicSlotOverlay(slotIdx);
    }

    private String decodeBolodechocolateHtml(String html) {
        if (html == null || !html.contains("var Dws") || !html.contains("String.fromCharCode")) {
            return html;
        }
        try {
            java.util.regex.Matcher offsetMatcher = java.util.regex.Pattern.compile("-\\s*(\\d+)\\s*\\)\\s*;\\s*\\}\\s*\\)\\s*;").matcher(html);
            if (!offsetMatcher.find()) {
                return html;
            }
            long offset = Long.parseLong(offsetMatcher.group(1));

            java.util.regex.Matcher dwsMatcher = java.util.regex.Pattern.compile("(?s)var Dws\\s*=\\s*\\[(.*?)\\];").matcher(html);
            if (!dwsMatcher.find()) {
                return html;
            }
            String arrayContent = dwsMatcher.group(1);
            String[] tokens = arrayContent.split(",");
            StringBuilder decoded = new StringBuilder(tokens.length);
            for (String token : tokens) {
                String clean = token.replace("\"", "").replace("'", "").trim();
                if (!clean.isEmpty()) {
                    byte[] decodedBytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT);
                    String s = new String(decodedBytes, StandardCharsets.UTF_8);
                    String digits = s.replaceAll("\\D", "");
                    if (!digits.isEmpty()) {
                        long code = Long.parseLong(digits) - offset;
                        decoded.append((char) code);
                    }
                }
            }
            String res = decoded.toString();
            if (res.contains("<html") || res.contains("<body") || res.contains("<script")) {
                Log.i("EPlay", "Sucesso ao desofuscar HTML do bolodechocolate (" + res.length() + " chars)");
                return res;
            }
        } catch (Exception e) {
            Log.w("EPlay", "Erro ao desofuscar bolodechocolate: " + e.getMessage());
        }
        return html;
    }

    private WebResourceResponse handleEmbedInterception(WebView view, WebResourceRequest request, boolean isMosaic, int mosaicSlotIdx) {
        if (request == null || request.getUrl() == null) return null;
        String url = request.getUrl().toString();

        // 0. Bloqueia Bitmovin e bolodechocolate no modo Mosaico (incompatível com multi-view simultâneo)
        if (isMosaic && mosaicSlotIdx >= 0) {
            if (url.contains("bitmovin") || url.contains("bolodechocolate")) {
                Log.w("Mosaic", "Slot " + mosaicSlotIdx + " tentou carregar Bitmovin/bolodechocolate (" + url + "). Bloqueando no Mosaico.");
                mainHandler.post(() -> onBitmovinDetectedInMosaic(mosaicSlotIdx));
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        }

        // 1. Bloqueia anúncios, rastreadores e popunders conhecidos instantaneamente sem requisição de rede
        if (url.contains("aclib")
                || url.contains("histats")
                || url.contains("statcounter")
                || url.contains("popunder")
                || url.contains("doubleclick")
                || url.contains("googlesyndication")
                || url.contains("adnxs")
                || url.contains("adservice")) {
            return new WebResourceResponse("text/javascript", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }

        // 2. Cache em memória RAM de bibliotecas JS do player (JWPlayer, Bitmovin) para carregamento instantâneo
        boolean isPlayerAsset = url.contains("jwplayer.latest.js")
                || url.contains("jwplayer.js")
                || url.contains("jwplayer.cast.js")
                || url.contains("bitmovinplayer.js");

        if (isPlayerAsset) {
            byte[] cached = STATIC_WEB_CACHE.get(url);
            if (cached != null) {
                return new WebResourceResponse("application/javascript", "UTF-8", new ByteArrayInputStream(cached));
            }
            try {
                Request okReq = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                        .build();
                Response okRes = sharedOkHttpClient.newCall(okReq).execute();
                if (okRes.isSuccessful() && okRes.body() != null) {
                    byte[] data = okRes.body().bytes();
                    STATIC_WEB_CACHE.put(url, data);
                    return new WebResourceResponse("application/javascript", "UTF-8", new ByteArrayInputStream(data));
                }
            } catch (Exception ignored) {}
        }

        // 3. Intercepta player.js do localhost.tattoo para garantir autoplay e controle de áudio
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
                    boolean isFocused = (isMosaic && mosaicSlotIdx >= 0 && mosaicSlotIdx == currentMosaicFocusedIdx);
                    boolean initialMuted = isMosaic ? !isFocused : false;
                    String injection = "\n;(function(){\n" +
                            "  var css = '.jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-flag-touch .jw-display-icon-container, .jw-flag-touch .jw-display-icon-display, .jw-flag-touch .jw-icon-playback, .plyr__control--overlaid, .plyr__controls, .vjs-big-play-button, .vjs-control-bar { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; }';\n" +
                            "  var st = document.createElement('style');\n" +
                            "  st.textContent = css;\n" +
                            "  (document.head || document.documentElement).appendChild(st);\n" +
                            "  var isMosaic = " + isMosaic + ";\n" +
                            "  if (window.__andplay_muted === undefined) {\n" +
                            "    window.__andplay_muted = " + (isMosaic ? initialMuted : "false") + ";\n" +
                            "  }\n" +
                            "  function forceStart() {\n" +
                            "    var m = (window.__andplay_muted !== false);\n" +
                            "    try {\n" +
                            "      if (typeof jwplayer === 'function') {\n" +
                            "        var p = jwplayer();\n" +
                            "        if (p && typeof p.play === 'function') {\n" +
                            "          if (typeof p.setMute === 'function') p.setMute(m);\n" +
                            "          if (typeof p.setVolume === 'function') p.setVolume(m ? 0 : 100);\n" +
                            "          var s = typeof p.getState === 'function' ? p.getState() : '';\n" +
                            "          if (s === 'paused' || s === 'idle') { p.play(); }\n" +
                            (!isMosaic ? "          if (s === 'playing') { if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted(); }\n" : "") +
                            "        }\n" +
                            "      }\n" +
                            "    } catch(e) {}\n" +
                            "    try {\n" +
                            "      var v = document.querySelector('video');\n" +
                            "      if (v) {\n" +
                            "        if (v.muted !== m) v.muted = m;\n" +
                            "        if (m && v.volume > 0) v.volume = 0.0;\n" +
                            "        else if (!m && v.volume < 1.0) v.volume = 1.0;\n" +
                            "        if (v.paused) { v.play().catch(function(){}); }\n" +
                            (!isMosaic ? "        else if (v.currentTime > 0) { if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted(); }\n" : "") +
                            "      }\n" +
                            "    } catch(e) {}\n" +
                            "  }\n" +
                            "  window.addEventListener('message', function(e) {\n" +
                            "    if (e.data && e.data.type === 'ANDPLAY_AUDIO_CONTROL') {\n" +
                            "      window.__andplay_muted = !!e.data.muted;\n" +
                            "      forceStart();\n" +
                            "    }\n" +
                            "  });\n" +
                            "  if (isMosaic) {\n" +
                            "    document.addEventListener('pause', function(e) {\n" +
                            "      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {\n" +
                            "        setTimeout(function() { if (e.target.paused) e.target.play().catch(function(){}); }, 50);\n" +
                            "      }\n" +
                            "    }, true);\n" +
                            "  }\n" +
                            "  setInterval(forceStart, 400);\n" +
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

        // 4. Intercepta hotstar.css, player-v3.1.min.css e outros CSS de players com cache em RAM
        if (url.contains("hotstar.css") || url.contains("player-v3.1.min.css") || (url.contains(".css") && (url.contains("player") || url.contains("jwplayer") || url.contains("plyr")))) {
            byte[] cachedCss = STATIC_WEB_CACHE.get(url);
            if (cachedCss != null) {
                return new WebResourceResponse("text/css", "UTF-8", new ByteArrayInputStream(cachedCss));
            }
            try {
                Request okReq = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                        .build();
                Response okRes = sharedOkHttpClient.newCall(okReq).execute();
                if (okRes.isSuccessful() && okRes.body() != null) {
                    String originalCss = okRes.body().string();
                    String hideCss = "\n.jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-logo, .jw-title, .jw-flag-touch .jw-display-icon-container, .jw-flag-touch .jw-display-icon-display, .jw-flag-touch .jw-icon-playback, .plyr__control--overlaid, .plyr__controls, .vjs-big-play-button, .vjs-control-bar, button[data-plyr=\"play\"], video::-webkit-media-controls { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; }\n";
                    byte[] cssBytes = (originalCss + hideCss).getBytes(StandardCharsets.UTF_8);
                    STATIC_WEB_CACHE.put(url, cssBytes);
                    return new WebResourceResponse("text/css", "UTF-8", new ByteArrayInputStream(cssBytes));
                }
            } catch (Exception e) {
                Log.w("EPlay", "Erro ao interceptar CSS do player: " + e.getMessage());
            }
        }

        // 5. Intercepta páginas e frames do rdcanais.net, bolodechocolate, rdembed, esportesembed e localhost.tattoo limpando anúncios, controles e garantindo permissões de autoplay
        if (url.contains("rdcanais.net") || url.contains("rdembed") || url.contains("redecanais") || url.contains("bolodechocolate") || url.contains("esportesembed") || url.contains("localhost.tattoo")) {
            boolean isHtml = request.isForMainFrame()
                    || (request.getRequestHeaders() != null && String.valueOf(request.getRequestHeaders().get("Accept")).contains("text/html"))
                    || (!url.contains(".js") && !url.contains(".css") && !url.contains(".png") && !url.contains(".jpg") && !url.contains(".m3u8") && !url.contains(".ts") && !url.contains(".woff") && !url.contains(".svg"));
            if (isHtml) {
                try {
                    Request okReq = new Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            .build();
                    Response okRes = sharedOkHttpClient.newCall(okReq).execute();
                    if (okRes.isSuccessful() && okRes.body() != null) {
                        String html = okRes.body().string();
                        if (isMosaic && mosaicSlotIdx >= 0 && (url.contains("bolodechocolate") || html.contains("bolodechocolate") || html.contains("bitmovin"))) {
                            Log.w("Mosaic", "Slot " + mosaicSlotIdx + " HTML contém Bitmovin/bolodechocolate. Bloqueando no Mosaico.");
                            mainHandler.post(() -> onBitmovinDetectedInMosaic(mosaicSlotIdx));
                            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                        }
                        html = decodeBolodechocolateHtml(html);
                        String hideStyle = "<style>" +
                                ".jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-logo, .jw-title, " +
                                ".plyr__control--overlaid, .plyr__controls, .vjs-big-play-button, .vjs-control-bar, button[data-plyr=\"play\"], " +
                                "video::-webkit-media-controls, " +
                                ".bmpui-ui-seekbar, .bmpui-ui-volumeslider, .bmpui-ui-controlbar, .bmpui-controlbar, " +
                                ".bmpui-ui-playbacktogglebutton, .bmpui-ui-hugeplaybacktogglebutton, .bmpui-ui-watermark, " +
                                ".bmpui-ui-settings-panel, .bmpui-ui-selectbox, #status { " +
                                "  display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; " +
                                "} " +
                                "body, html, #wrapper, #player { " +
                                "  background: #000 !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; " +
                                "  width: 100% !important; height: 100% !important; " +
                                "}" +
                                "</style>";
                        boolean isFocused = (isMosaic && mosaicSlotIdx >= 0 && mosaicSlotIdx == currentMosaicFocusedIdx);
                        boolean initialMuted = isMosaic ? !isFocused : false;
                        String autoplayScript = "<script>\n" +
                                ";(function() {\n" +
                                "  var isMosaic = " + isMosaic + ";\n" +
                                "  if (isMosaic) {\n" +
                                "    function checkBitmovin() {\n" +
                                "      if (window.bitmovin) {\n" +
                                "        try {\n" +
                                "          if (window.AndroidPlayback && window.AndroidPlayback.onBitmovinDetected) {\n" +
                                "            window.AndroidPlayback.onBitmovinDetected();\n" +
                                "          }\n" +
                                "        } catch(e) {}\n" +
                                "      }\n" +
                                "    }\n" +
                                "    checkBitmovin();\n" +
                                "    setTimeout(checkBitmovin, 200);\n" +
                                "    setTimeout(checkBitmovin, 500);\n" +
                                "  }\n" +
                                "  if (window.__andplay_muted === undefined) {\n" +
                                "    window.__andplay_muted = " + (isMosaic ? initialMuted : "false") + ";\n" +
                                "  }\n" +
                                "  (function hookBitmovin() {\n" +
                                "    window.__andplay_bitmovin_instances = window.__andplay_bitmovin_instances || [];\n" +
                                "    if (window.bitmovin && window.bitmovin.player && window.bitmovin.player.Player) {\n" +
                                "      if (!window.bitmovin.player.Player.__andplay_hooked) {\n" +
                                "        var OriginalPlayer = window.bitmovin.player.Player;\n" +
                                "        window.bitmovin.player.Player = function(container, config) {\n" +
                                "          var inst = new OriginalPlayer(container, config);\n" +
                                "          window.__andplay_bitmovin_instances.push(inst);\n" +
                                "          var m = (window.__andplay_muted !== false);\n" +
                                "          if (m) {\n" +
                                "            try { if (typeof inst.mute === 'function') inst.mute(); } catch(e){}\n" +
                                "          }\n" +
                                "          if (isMosaic && typeof inst.on === 'function') {\n" +
                                "            inst.on('paused', function() {\n" +
                                "              setTimeout(function() {\n" +
                                "                if (inst.isPaused && inst.isPaused()) {\n" +
                                "                  inst.play();\n" +
                                "                }\n" +
                                "              }, 50);\n" +
                                "            });\n" +
                                "          }\n" +
                                "          return inst;\n" +
                                "        };\n" +
                                "        window.bitmovin.player.Player.prototype = OriginalPlayer.prototype;\n" +
                                "        window.bitmovin.player.Player.__andplay_hooked = true;\n" +
                                "      }\n" +
                                "    } else {\n" +
                                "      setTimeout(hookBitmovin, 50);\n" +
                                "    }\n" +
                                "  })();\n" +
                                "  function updateAudioAndPlay() {\n" +
                                "    var m = (window.__andplay_muted !== false);\n" +
                                "    try {\n" +
                                "      var media = document.querySelectorAll('video, audio');\n" +
                                "      for (var i = 0; i < media.length; i++) {\n" +
                                "        var v = media[i];\n" +
                                "        if (v.muted !== m) v.muted = m;\n" +
                                "        if (m && v.volume > 0) v.volume = 0.0;\n" +
                                "        else if (!m && v.volume < 1.0) v.volume = 1.0;\n" +
                                "        if (v.paused) { v.play().catch(function(){}); }\n" +
                                "      }\n" +
                                "    } catch(e) {}\n" +
                                "    try {\n" +
                                "      if (typeof jwplayer === 'function') {\n" +
                                "        var p = jwplayer();\n" +
                                "        if (p && typeof p.play === 'function') {\n" +
                                "          if (typeof p.setMute === 'function') p.setMute(m);\n" +
                                "          if (typeof p.setVolume === 'function') p.setVolume(m ? 0 : 100);\n" +
                                "          var s = typeof p.getState === 'function' ? p.getState() : '';\n" +
                                "          if (s === 'paused' || s === 'idle') { p.play(); }\n" +
                                "        }\n" +
                                "      }\n" +
                                "    } catch(e) {}\n" +
                                "    try {\n" +
                                "      if (window.__andplay_bitmovin_instances) {\n" +
                                "        for (var bi = 0; bi < window.__andplay_bitmovin_instances.length; bi++) {\n" +
                                "          var bp = window.__andplay_bitmovin_instances[bi];\n" +
                                "          if (bp) {\n" +
                                "            if (m) {\n" +
                                "              if (typeof bp.mute === 'function') bp.mute();\n" +
                                "            } else {\n" +
                                "              if (typeof bp.unmute === 'function') bp.unmute();\n" +
                                "              if (typeof bp.setVolume === 'function') bp.setVolume(100);\n" +
                                "              if (typeof bp.isPaused === 'function' && bp.isPaused()) bp.play();\n" +
                                "            }\n" +
                                "          }\n" +
                                "        }\n" +
                                "      }\n" +
                                "    } catch(e) {}\n" +
                                "    try {\n" +
                                "      var btns = document.querySelectorAll('.vjs-big-play-button, .plyr__control--overlaid, button[aria-label*=\"Play\" i], button[title*=\"Play\" i]');\n" +
                                "      for (var j = 0; j < btns.length; j++) {\n" +
                                "        btns[j].click();\n" +
                                "      }\n" +
                                "    } catch(e) {}\n" +
                                "  }\n" +
                                "  window.addEventListener('message', function(e) {\n" +
                                "    if (e.data && e.data.type === 'ANDPLAY_REQUEST_AUDIO') {\n" +
                                "      try {\n" +
                                "        if (e.source) {\n" +
                                "          e.source.postMessage({ type: 'ANDPLAY_AUDIO_CONTROL', muted: (window.__andplay_muted !== false) }, '*');\n" +
                                "        }\n" +
                                "      } catch(err) {}\n" +
                                "    }\n" +
                                "    if (e.data && e.data.type === 'ANDPLAY_AUDIO_CONTROL') {\n" +
                                "      window.__andplay_muted = !!e.data.muted;\n" +
                                "      updateAudioAndPlay();\n" +
                                "      try {\n" +
                                "        if (window.frames && window.frames.length > 0) {\n" +
                                "          for (var k = 0; k < window.frames.length; k++) {\n" +
                                "            window.frames[k].postMessage(e.data, '*');\n" +
                                "          }\n" +
                                "        }\n" +
                                "      } catch(err) {}\n" +
                                "    }\n" +
                                "  });\n" +
                                "  try {\n" +
                                "    if (window.top && window.top !== window) {\n" +
                                "      window.top.postMessage({ type: 'ANDPLAY_REQUEST_AUDIO' }, '*');\n" +
                                "    }\n" +
                                "  } catch(e) {}\n" +
                                "  if (isMosaic) {\n" +
                                "    document.addEventListener('pause', function(e) {\n" +
                                "      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {\n" +
                                "        setTimeout(function() { if (e.target.paused) e.target.play().catch(function(){}); }, 50);\n" +
                                "      }\n" +
                                "    }, true);\n" +
                                "  }\n" +
                                "  setInterval(updateAudioAndPlay, 400);\n" +
                                "  document.addEventListener('DOMContentLoaded', updateAudioAndPlay);\n" +
                                "  window.addEventListener('load', updateAudioAndPlay);\n" +
                                "})();\n" +
                                "</script>";
                        html = html.replaceAll("(?is)<script[^>]*aclib[^>]*>.*?</script>", "")
                                   .replaceAll("(?is)<script[^>]*histats[^>]*>.*?</script>", "")
                                   .replaceAll("(?i)<iframe\\b([^>]*)>", "<iframe$1 allow=\"autoplay *; encrypted-media *; fullscreen *; picture-in-picture *\">");
                        if (html.toLowerCase().contains("<head>")) {
                            html = html.replaceFirst("(?i)<head>", "<head>" + hideStyle + autoplayScript);
                        } else {
                            html = hideStyle + autoplayScript + html;
                        }
                        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(htmlBytes));
                    }
                } catch (Exception e) {
                    Log.w("EPlay", "Erro ao interceptar rdcanais HTML: " + e.getMessage());
                }
            }
        }

        return null;
    }

    private void playMosaicSlotFallback(int slotIdx) {
        if (slotIdx < 0 || slotIdx >= 4) return;
        MosaicSlotItem slot = mosaicSlots[slotIdx];
        if (slot == null || slot.channel == null || slot.fallbacks == null || slot.currentFallbackIdx >= slot.fallbacks.size()) return;

        Channel ch = slot.channel;
        Channel.StreamFallback fb = slot.fallbacks.get(slot.currentFallbackIdx);

        // Limpa visualização anterior do player no slot mantendo canal e fallbacks
        if (slot.exoPlayer != null) {
            slot.exoPlayer.stop();
            slot.exoPlayer.release();
            slot.exoPlayer = null;
        }
        if (slot.webView != null) {
            slot.webView.stopLoading();
            slot.webView.loadUrl("about:blank");
            slot.webView.clearHistory();
            slot.webView.destroy();
            slot.webView = null;
        }
        if (slot.playerHost != null) {
            slot.playerHost.removeAllViews();
        }
        slot.playerView = null;

        int chNum = allChannels.indexOf(ch) + 1;
        if (slot.titleView != null) {
            slot.titleView.setText(String.format(Locale.getDefault(), "TELA %d - %03d %s", slotIdx + 1, chNum, ch.name));
        }

        boolean isSlotFocused = (slot.slotView != null && slot.slotView.isFocused());

        if (fb.isEmbed) {
            if (fb.url != null && (fb.url.contains("bolodechocolate") || fb.url.contains("bitmovin"))) {
                onBitmovinDetectedInMosaic(slotIdx);
                return;
            }
            slot.isPlayingEmbed = true;
            WebView wv = new WebView(this);
            wv.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void onBitmovinDetected() {
                    mainHandler.post(() -> onBitmovinDetectedInMosaic(slotIdx));
                }
            }, "AndroidPlayback");
            wv.setFocusable(false);
            wv.setFocusableInTouchMode(false);
            wv.setClickable(false);
            wv.setOnTouchListener((v, event) -> true);
            wv.setOnKeyListener((v, keyCode, event) -> true);
            slot.webView = wv;

            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setAllowFileAccess(true);
            ws.setAllowContentAccess(true);
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setSupportMultipleWindows(false);
            ws.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
            }

            wv.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                    return false;
                }
            });

            wv.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (request != null && !request.isForMainFrame()) return false;
                    String url = request != null ? request.getUrl().toString() : "";
                    if (url.startsWith("file://")
                            || url.contains("rdcanais.net")
                            || url.contains("redecanaistv.af")
                            || url.contains("redecanais")
                            || url.contains("v2.rdembed.sbs")
                            || url.contains("streamverde.net")
                            || url.contains("cazetv.shop")
                            || url.contains("tvacabo.top")
                            || url.contains("tvacabo.free.nf")
                            || url.contains("bolodechocolate.fit")
                            || url.contains("esportesembed.net")
                            || url.contains("localhost.tattoo")
                            || url.contains("about:blank")) {
                        return false;
                    }
                    return true;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    WebResourceResponse res = handleEmbedInterception(view, request, true, slotIdx);
                    if (res != null) return res;
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (slot.isUnavailable) return;
                    if (request != null && request.isForMainFrame()) {
                        Log.w("Mosaic", "Slot " + slotIdx + " WebView error, tentando próximo fallback");
                        mainHandler.post(() -> tryNextMosaicFallback(slotIdx));
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (slot.isUnavailable) return;
                    boolean isFocused = (slotIdx == currentMosaicFocusedIdx);
                    setSlotAudioMuted(slot, !isFocused);
                    view.postDelayed(() -> {
                        if (isMosaicActive && slot.webView == view && !slot.isUnavailable) {
                            boolean f = (slotIdx == currentMosaicFocusedIdx);
                            setSlotAudioMuted(slot, !f);
                        }
                    }, 1200);
                }
            });

            slot.playerHost.addView(wv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));

            String autoplayUrl = fb.url + (fb.url.contains("?") ? "&" : "?") + "autoplay=1";
            wv.loadUrl(autoplayUrl);

        } else {
            slot.isPlayingEmbed = false;
            PlayerView pv = new PlayerView(this);
            pv.setFocusable(false);
            pv.setFocusableInTouchMode(false);
            pv.setUseController(false);
            slot.playerView = pv;

            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(new OkHttpDataSource.Factory(sharedOkHttpClient));
            ExoPlayer ep = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build();
            slot.exoPlayer = ep;
            pv.setPlayer(ep);

            slot.playerHost.addView(pv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));

            ep.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.w("Mosaic", "Slot " + slotIdx + " ExoPlayer error: " + error.getMessage() + ", tentando próximo fallback");
                    mainHandler.post(() -> tryNextMosaicFallback(slotIdx));
                }
            });

            ep.setVolume(isSlotFocused ? 1.0f : 0.0f);
            ep.setMediaItem(MediaItem.fromUri(fb.url));
            ep.prepare();
            ep.play();
        }
    }

    private void clearMosaicSlot(MosaicSlotItem slot) {
        if (slot == null) return;
        slot.isUnavailable = false;
        if (slot.hideOverlayRunnable != null) {
            mainHandler.removeCallbacks(slot.hideOverlayRunnable);
        }
        if (slot.exoPlayer != null) {
            slot.exoPlayer.stop();
            slot.exoPlayer.release();
            slot.exoPlayer = null;
        }
        if (slot.webView != null) {
            slot.webView.stopLoading();
            slot.webView.loadUrl("about:blank");
            slot.webView.clearHistory();
            slot.webView.destroy();
            slot.webView = null;
        }
        if (slot.playerHost != null) {
            slot.playerHost.removeAllViews();
        }
        slot.playerView = null;
        slot.channel = null;
        slot.fallbacks = null;
        slot.currentFallbackIdx = 0;
    }

    private void exitMosaicMode(boolean restorePrevious) {
        isMosaicActive = false;
        mosaicTargetSlotIdx = -1;

        for (int i = 0; i < 4; i++) {
            if (mosaicSlots[i] != null && mosaicSlots[i].hideOverlayRunnable != null) {
                mainHandler.removeCallbacks(mosaicSlots[i].hideOverlayRunnable);
            }
            clearMosaicSlot(mosaicSlots[i]);
        }

        if (mosaicLayout != null) {
            mosaicLayout.setVisibility(View.GONE);
        }

        if (restorePrevious) {
            setScreenMode(ScreenMode.FULLSCREEN);
            if (!allChannels.isEmpty() && mosaicInitialChannelIdx >= 0 && mosaicInitialChannelIdx < allChannels.size()) {
                tuneChannel(mosaicInitialChannelIdx, true);
            }
        }
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
                CACHE.put("static.s23-cloudfront-net.lat", Arrays.asList(
                        InetAddress.getByName("104.21.49.33"),
                        InetAddress.getByName("172.67.158.120")
                ));
                CACHE.put("api.reidoscanais.st", Arrays.asList(
                        InetAddress.getByName("104.21.4.193"),
                        InetAddress.getByName("172.67.154.45")
                ));
                CACHE.put("esportesembed.net", Arrays.asList(
                        InetAddress.getByName("172.67.162.24"),
                        InetAddress.getByName("104.21.15.95")
                ));
                CACHE.put("rdcanais.net", Arrays.asList(
                        InetAddress.getByName("104.21.82.94"),
                        InetAddress.getByName("172.67.199.224")
                ));
                CACHE.put("streamverde.net", Arrays.asList(
                        InetAddress.getByName("104.21.28.81"),
                        InetAddress.getByName("172.67.170.106")
                ));
            } catch (Exception ignored) {}
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            if (CACHE.containsKey(hostname)) {
                return CACHE.get(hostname);
            }

            boolean isBlockedDomain = hostname.contains("reidoscanais")
                    || hostname.contains("cazetv")
                    || hostname.contains("streamverde")
                    || hostname.contains("cloudfront-net")
                    || hostname.endsWith(".lat")
                    || hostname.contains("esportesembed")
                    || hostname.contains("rdcanais")
                    || hostname.contains("rdembed");

            // Para domínios frequentemente bloqueados por operadoras, consulta DoH 1.1.1.1 prioritariamente
            if (isBlockedDomain) {
                List<InetAddress> dohIps = queryDoh(hostname);
                if (dohIps != null && !dohIps.isEmpty()) {
                    CACHE.put(hostname, dohIps);
                    return dohIps;
                }
            }

            try {
                List<InetAddress> sys = Dns.SYSTEM.lookup(hostname);
                if (sys != null && !sys.isEmpty()) return sys;
            } catch (UnknownHostException ignored) {}

            List<InetAddress> dohIps = queryDoh(hostname);
            if (dohIps != null && !dohIps.isEmpty()) {
                CACHE.put(hostname, dohIps);
                return dohIps;
            }

            if (hostname.contains("cazetv.shop") || hostname.contains("streamverde")) {
                List<InetAddress> ips = CACHE.get("svd.cazetv.shop");
                if (ips != null) return ips;
            }
            if (hostname.contains("s23-cloudfront-net")) {
                List<InetAddress> ips = CACHE.get("static.s23-cloudfront-net.lat");
                if (ips != null) return ips;
            }
            if (hostname.contains("s22-cloudfront-net") || hostname.endsWith(".lat")) {
                List<InetAddress> ips = CACHE.get("cdn1.s22-cloudfront-net.lat");
                if (ips != null) return ips;
            }
            if (hostname.contains("reidoscanais")) {
                List<InetAddress> ips = CACHE.get("api.reidoscanais.st");
                if (ips != null) return ips;
            }

            throw new UnknownHostException("Não foi possível resolver host: " + hostname);
        }

        private List<InetAddress> queryDoh(String hostname) {
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
                            return ips;
                        }
                    }
                }
            } catch (Exception ignored) {}
            return null;
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

        Map<String, String> defaultHeaders = new HashMap<>();
        defaultHeaders.put("Referer", "https://streamverde.net/");
        defaultHeaders.put("Origin", "https://streamverde.net");

        OkHttpDataSource.Factory httpDataSourceFactory = new OkHttpDataSource.Factory(sharedOkHttpClient)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setDefaultRequestProperties(defaultHeaders);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpDataSourceFactory);

        exoPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    if (pendingVodSeekPositionMs > 0 && exoPlayer != null) {
                        long targetSeek = pendingVodSeekPositionMs;
                        pendingVodSeekPositionMs = 0;
                        exoPlayer.seekTo(targetSeek);
                    }
                    if (exoPlayer.getPlayWhenReady()) {
                        mainHandler.post(() -> onPlaybackStarted());
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    mainHandler.post(() -> onPlaybackEnded());
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

        unifiedEmbedWebView.setFocusable(false);
        unifiedEmbedWebView.setFocusableInTouchMode(false);

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
                        || url.contains("redecanaistv.af")
                        || url.contains("redecanais")
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
                WebResourceResponse res = handleEmbedInterception(view, request, false, -1);
                if (res != null) return res;
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
                mainHandler.post(() -> {
                    onPlaybackStarted();
                    if (currentMode == ScreenMode.FULLSCREEN) {
                        scheduleOsdHide(getOsdTimeoutMs());
                    }
                });
                // Injeta script seguro para áudio, remoção de botões sobrepostos e detecção de reprodução
                String antiAdAndPlaybackScript = "(function() {" +
                        "try {" +
                        "  if (!window.__eplay_h) {" +
                        "    window.__eplay_h = true;" +
                        "    window.open = function() { return { focus: function(){}, close: function(){}, closed: false, location: { href: '' } }; };" +
                        "  }" +
                        "} catch(e) {}" +
                        "try {" +
                        "  var css = '.jw-display-icon-container, .jw-display-icon-display, .jw-icon-playback, .jw-controlbar, .jw-overlays, .jw-logo, .jw-title, .jw-title-primary, .jw-title-secondary, .vjs-title-bar, .jw-media-controls, .jw-flag-touch .jw-display-icon-container, .jw-flag-touch .jw-display-icon-display, .jw-flag-touch .jw-icon-playback, .plyr__control--overlaid, .plyr__controls, .vjs-big-play-button, .vjs-control-bar, button[data-plyr=\"play\"], .vjs-text-track-display, .vjs-loading-spinner, .vjs-poster, video::-webkit-media-controls, video::-webkit-media-controls-enclosure, ::-webkit-scrollbar, header, footer, nav, .menu, #sidebar, .chat, .comments, .site-header, .site-footer { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; width: 0 !important; height: 0 !important; } body, html { background: #000 !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; }';" +
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
                        "      ifr.style.position = 'fixed';" +
                        "      ifr.style.top = '0';" +
                        "      ifr.style.left = '0';" +
                        "      ifr.style.width = '100vw';" +
                        "      ifr.style.height = '100vh';" +
                        "      ifr.style.zIndex = '2147483647';" +
                        "      ifr.style.border = 'none';" +
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
                        "        if (!m.__eplay_sent) {" +
                        "          m.__eplay_sent = true;" +
                        "          if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted();" +
                        "        }" +
                        "      });" +
                        "      m.addEventListener('timeupdate', function() {" +
                        "        if (m.currentTime > 0.3 && !m.__eplay_sent) {" +
                        "          m.__eplay_sent = true;" +
                        "          if (window.AndroidPlayback) window.AndroidPlayback.onVideoStarted();" +
                        "        }" +
                        "      });" +
                        "    }" +
                        "    if (!m.paused && m.currentTime > 0 && !m.__eplay_sent) {" +
                        "      m.__eplay_sent = true;" +
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
        targetHost.requestLayout();
        unifiedPlayerBox.requestLayout();
        if (unifiedExoPlayerView != null) {
            unifiedExoPlayerView.requestLayout();
        }
        if (unifiedEmbedWebView != null) {
            unifiedEmbedWebView.requestLayout();
            if (isPlayingEmbed) {
                unifiedEmbedWebView.setInitialScale(0);
                unifiedEmbedWebView.evaluateJavascript(
                        "(function() {" +
                        "  var meta = document.querySelector('meta[name=\"viewport\"]');" +
                        "  if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; document.head.appendChild(meta); }" +
                        "  meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
                        "  var vids = document.querySelectorAll('video, iframe');" +
                        "  for (var i = 0; i < vids.length; i++) {" +
                        "    vids[i].style.width = '100%';" +
                        "    vids[i].style.height = '100%';" +
                        "    vids[i].style.objectFit = 'contain';" +
                        "  }" +
                        "})();", null);
            }
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

    private String getMovieProgressKey(Movie movie) {
        if (movie == null) return null;
        if (movie.stream_id != null && !movie.stream_id.isEmpty()) {
            return "movie_" + movie.stream_id;
        }
        if (movie.num > 0) {
            return "movie_num_" + movie.num;
        }
        if (movie.name != null && !movie.name.isEmpty()) {
            return "movie_name_" + movie.name;
        }
        return null;
    }

    private String getCurrentVodKey() {
        if (activeVodMovie != null) {
            return getMovieProgressKey(activeVodMovie);
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
            // Salva apenas se assistiu pelo menos 5 segundos
            if (positionMs > 5000) {
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

    private static final String PREF_APP_STATE = "andplay_app_state";
    private static final String KEY_LAST_CHANNEL_ID = "last_channel_id";
    private static final String KEY_LAST_CHANNEL_INDEX = "last_channel_idx";
    private static final String KEY_RECENT_CHANNELS = "recent_channels_ids";

    private void addRecentChannel(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        try {
            SharedPreferences sp = getSharedPreferences(PREF_APP_STATE, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_RECENT_CHANNELS, "");
            List<String> list = new ArrayList<>();
            if (!raw.isEmpty()) {
                String[] parts = raw.split(",");
                for (String p : parts) {
                    p = p.trim();
                    if (!p.isEmpty() && !p.equals(channelId) && !list.contains(p)) {
                        list.add(p);
                    }
                }
            }
            list.add(0, channelId);
            while (list.size() > 5) {
                list.remove(list.size() - 1);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            sp.edit().putString(KEY_RECENT_CHANNELS, sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    private List<String> getRecentChannelIds() {
        List<String> list = new ArrayList<>();
        try {
            SharedPreferences sp = getSharedPreferences(PREF_APP_STATE, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_RECENT_CHANNELS, "");
            if (!raw.isEmpty()) {
                String[] parts = raw.split(",");
                for (String p : parts) {
                    p = p.trim();
                    if (!p.isEmpty() && !list.contains(p)) {
                        list.add(p);
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<Channel> getFeaturedChannelsList() {
        if (allChannels == null || allChannels.isEmpty()) return new ArrayList<>();
        List<Channel> featured = new ArrayList<>();
        java.util.Set<String> addedIds = new java.util.HashSet<>();

        List<String> recentIds = getRecentChannelIds();
        for (String id : recentIds) {
            for (Channel ch : allChannels) {
                if (id.equals(ch.id)) {
                    featured.add(ch);
                    addedIds.add(id);
                    break;
                }
            }
        }

        for (Channel ch : allChannels) {
            if (ch.id != null && addedIds.contains(ch.id)) {
                continue;
            }
            featured.add(ch);
        }

        return featured;
    }

    private int getChannelGroupRank(Channel ch) {
        if (ch == null) return 6;
        String k = ch.key != null ? ch.key.toLowerCase(Locale.ROOT) : "";
        String c = ch.cat != null ? ch.cat.toLowerCase(Locale.ROOT) : "";

        // 1. Abertos
        if ("open_tv".equals(k) || c.contains("aberto")) return 1;

        // 2. Esportes
        if ("sports".equals(k) || c.contains("esporte")) return 2;

        // 3. Variedades (Agora unificado com Filmes / Séries / Realitys)
        if ("variety".equals(k) || "reality".equals(k) || "movies".equals(k)
                || c.contains("variedade") || c.contains("not") || c.contains("doc")
                || c.contains("rie") || c.contains("serie")
                || c.contains("reality") || c.contains("filme")
                || c.contains("geral") || c.contains("ing") || c.contains("miami")) {
            return 3;
        }

        // 4. Infantil
        if ("kids".equals(k) || c.contains("infantil") || c.contains("desenho")) return 4;

        // 5. 24hrs
        if ("channels_24h".equals(k) || c.contains("24")) return 5;

        // 6. Outros
        return 6;
    }

    private int extractTrailingNumber(String s) {
        if (s == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group());
            } catch (Exception ignored) {}
        }
        return last;
    }

    private int getChannelSubRank(Channel ch, int groupRank) {
        if (ch == null) return 0;
        String id = ch.id != null ? ch.id.toLowerCase(Locale.ROOT) : "";
        String name = ch.name != null ? ch.name.toLowerCase(Locale.ROOT) : "";

        if (groupRank == 1) {
            // 1. Globo SP como primeiro canal absoluto (Canal 001)
            if ("globosp".equals(id) || name.startsWith("globo sp")) {
                return 1;
            }
            // 2. Demais canais GLOBO e derivados (ex.: TV Asa Branca, TV Bahia, TV Anhanguera, TV Morena, RBS TV, Globo Minas, etc.)
            if (id.startsWith("globo") || name.contains("globo")
                    || "globoal".equals(id) || "globoba".equals(id) || "globoam".equals(id)
                    || "globodf".equals(id) || "globogo".equals(id) || "globomg".equals(id)
                    || "globoms".equals(id) || "globors".equals(id)
                    || name.contains("asa branca") || name.contains("bahia")
                    || name.contains("anhanguera") || name.contains("morena")
                    || name.contains("rbs") || name.contains("amazônica") || name.contains("brasília")) {
                return 10;
            }
            // 3. Band SP
            if ("bandsp".equals(id) || name.startsWith("band sp") || "band".equals(id)) {
                return 20;
            }
            // 4. Record SP
            if ("recordsp".equals(id) || name.startsWith("record sp") || "record".equals(id)) {
                return 30;
            }
            // 5. SBT
            if ("sbt".equals(id) || name.equals("sbt")) {
                return 40;
            }
            // 6. Demais canais abertos (ordem alfabética)
            return 50;
        }

        if (groupRank == 2) {
            // 1. ESPN (ESPN, ESPN 2..6)
            if (id.startsWith("espn") || name.startsWith("espn")) {
                if ("espn".equals(id) || name.equals("espn")) return 101;
                int num = extractTrailingNumber(name.isEmpty() ? id : name);
                return (num > 0) ? (100 + num) : 199;
            }
            // 2. SporTV (SporTV, SporTV 2..4)
            if (id.startsWith("sportv") || name.startsWith("sportv")) {
                if ("sportv".equals(id) || name.equals("sportv")) return 201;
                int num = extractTrailingNumber(name.isEmpty() ? id : name);
                return (num > 0) ? (200 + num) : 299;
            }
            // 3. Premiere (Premiere Clubes primeiro, depois 2..8)
            if (id.startsWith("premiere") || name.startsWith("premiere")) {
                if (name.contains("clubes") || "premiere".equals(id)) return 301;
                int num = extractTrailingNumber(name.isEmpty() ? id : name);
                return (num > 0) ? (300 + num) : 399;
            }
            // 4. Demais canais esportivos
            return 400;
        }

        if (groupRank == 3) {
            // A Fazenda vai para o final do grupo Variedades
            if (id.startsWith("afazenda") || name.startsWith("a fazenda")) {
                if ("afazenda".equals(id) || name.equals("a fazenda")) return 1001;
                int num = extractTrailingNumber(name.isEmpty() ? id : name);
                return (num > 0) ? (1000 + num) : 1099;
            }
            return 0;
        }

        return 0;
    }

    private void sortChannelsByGroup(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return;
        Collections.sort(channels, (c1, c2) -> {
            int r1 = getChannelGroupRank(c1);
            int r2 = getChannelGroupRank(c2);
            if (r1 != r2) {
                return Integer.compare(r1, r2);
            }
            int sub1 = getChannelSubRank(c1, r1);
            int sub2 = getChannelSubRank(c2, r2);
            if (sub1 != sub2) {
                return Integer.compare(sub1, sub2);
            }
            String n1 = c1.name != null ? c1.name : "";
            String n2 = c2.name != null ? c2.name : "";
            return n1.compareToIgnoreCase(n2);
        });
    }

    private void loadInitialData() {
        showLoading("Carregando canais...");
        executor.execute(() -> {
            allChannels = ApiClient.loadLocalChannels(this);
            sortChannelsByGroup(allChannels);
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
                    int initialIdx = 0;
                    try {
                        SharedPreferences sp = getSharedPreferences(PREF_APP_STATE, Context.MODE_PRIVATE);
                        String lastId = sp.getString(KEY_LAST_CHANNEL_ID, "");
                        int lastIdx = sp.getInt(KEY_LAST_CHANNEL_INDEX, -1);
                        if (lastId != null && !lastId.isEmpty()) {
                            for (int i = 0; i < allChannels.size(); i++) {
                                if (lastId.equals(allChannels.get(i).id)) {
                                    initialIdx = i;
                                    break;
                                }
                            }
                        } else if (lastIdx >= 0 && lastIdx < allChannels.size()) {
                            initialIdx = lastIdx;
                        }
                    } catch (Exception ignored) {}
                    tuneChannel(initialIdx, false);
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
            } catch (Throwable t) {
                Log.e("EPlay", "Erro ao pré-carregar filmes", t);
            }
            try {
                seriesCategories = ApiClient.getSeriesCategories();
                cachedSeries = ApiClient.getSeries();
                mainHandler.post(this::setupSeriesRail);
            } catch (Throwable t) {
                Log.e("EPlay", "Erro ao pré-carregar séries", t);
            }
        });
    }

    private void setupCentralButtons() {
        // Clicar ou teclar Enter no miniplayer -> expande imediatamente para Tela Cheia sem recarregar o stream
        pipContainer.setOnClickListener(v -> setScreenMode(ScreenMode.FULLSCREEN));
        pipContainer.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showPipOverlay();
            } else {
                pipOverlayHandler.removeCallbacks(pipOverlayHideRunnable);
                if (pipOverlayBar != null) {
                    pipOverlayBar.setVisibility(View.GONE);
                }
            }
        });
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
        btnNavSports.setOnClickListener(v -> openSportsHub());
        btnNavEpg.setOnClickListener(v -> {
            openFullGuide();
        });

        // Botão "VER TUDO ▶" ao lado do título de Jogos
        View btnAllSports = findViewById(R.id.btnAllSports);
        if (btnAllSports != null) {
            btnAllSports.setOnClickListener(v -> openSportsHub());
            btnAllSports.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    openSportsHub();
                    return true;
                }
                return false;
            });
            btnAllSports.setOnFocusChangeListener((v, hasFocus) ->
                v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start());
        }

        if (btnHeaderOptions != null) {
            btnHeaderOptions.setOnClickListener(v -> showProviderOptionsDialog());
            btnHeaderOptions.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    showProviderOptionsDialog();
                    return true;
                }
                return false;
            });
            btnHeaderOptions.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start();
                if (hasFocus && centralScroll != null) {
                    centralScroll.smoothScrollTo(0, 0);
                }
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
            btnDrawerOptions.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start();
            });
        }

        if (btnDrawerMosaic != null) {
            btnDrawerMosaic.setOnClickListener(v -> showMosaicDialog());
            btnDrawerMosaic.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    showMosaicDialog();
                    return true;
                }
                return false;
            });
            btnDrawerMosaic.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start();
            });
        }
    }

    private void setupChannelsRail() {
        List<Channel> featuredChannels = getFeaturedChannelsList();
        channelsRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        channelsRail.setAdapter(new ChannelRailAdapter(this, featuredChannels, false, (ch, idx) -> {
            int originalIdx = allChannels.indexOf(ch);
            if (originalIdx < 0) originalIdx = idx;
            // Se for o mesmo canal já em execução, apenas redimensiona para tela cheia
            if (originalIdx == currentChannelIdx && (currentActiveStreamUrl != null && !currentActiveStreamUrl.isEmpty())) {
                setScreenMode(ScreenMode.FULLSCREEN);
                return;
            }
            // Canal diferente: destrói player anterior e sintoniza novo
            destroyCurrentStream();
            tuneChannel(originalIdx, true);
            setScreenMode(ScreenMode.FULLSCREEN);
        }));
    }

    // Periodic Sports Refresh — adaptativo: 1 min com jogos ao vivo, 5 min sem
    private static final long SPORTS_REFRESH_LIVE_MS   = 60 * 1000L;       // 1 minuto
    private static final long SPORTS_REFRESH_IDLE_MS   = 5 * 60 * 1000L;   // 5 minutos
    private final Handler sportsRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable sportsRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshSportsEvents();
            // Agenda próximo ciclo com intervalo adaptativo
            boolean hasLive = false;
            for (SportsEvent ev : allSports) { if (ev.isLive) { hasLive = true; break; } }
            long nextDelay = hasLive ? SPORTS_REFRESH_LIVE_MS : SPORTS_REFRESH_IDLE_MS;
            sportsRefreshHandler.postDelayed(this, nextDelay);
        }
    };

    private void startSportsRefreshTicker() {
        sportsRefreshHandler.removeCallbacks(sportsRefreshRunnable);
        // Começa com intervalo curto — ajusta após o primeiro refresh
        boolean hasLive = false;
        for (SportsEvent ev : allSports) { if (ev.isLive) { hasLive = true; break; } }
        long firstDelay = hasLive ? SPORTS_REFRESH_LIVE_MS : SPORTS_REFRESH_IDLE_MS;
        sportsRefreshHandler.postDelayed(sportsRefreshRunnable, firstDelay);
    }

    private void stopSportsRefreshTicker() {
        sportsRefreshHandler.removeCallbacks(sportsRefreshRunnable);
    }

    private void refreshSportsEvents() {
        executor.execute(() -> {
            try {
                List<SportsEvent> fresh = ApiClient.getLiveSports();
                if (fresh != null && !fresh.isEmpty()) {
                    mainHandler.post(() -> updateSportsList(fresh));
                }
            } catch (Exception ignored) {}
        });
    }

    private void updateSportsList(List<SportsEvent> fresh) {
        if (fresh == null || fresh.isEmpty()) return;
        if (allSports.isEmpty()) {
            allSports.addAll(fresh);
            if (sportsRail != null && sportsRail.getAdapter() != null) {
                sportsRail.getAdapter().notifyDataSetChanged();
            }
            return;
        }

        // Se estiver em reprodução de um evento esportivo em tela cheia, atualiza os dados do evento ativo
        if (isPlayingSportsEvent && activeSportsEvent != null) {
            for (SportsEvent fev : fresh) {
                if (Objects.equals(fev.id, activeSportsEvent.id)) {
                    activeSportsEvent.score = fev.score;
                    activeSportsEvent.clock = fev.clock;
                    activeSportsEvent.isLive = fev.isLive;
                    activeSportsEvent.isFinished = fev.isFinished;
                    break;
                }
            }
        }

        // Re-match de canal normal: se estiver em tela cheia assistindo um canal regular,
        // re-verifica se o canal tem evento ao vivo detectado
        if (!isPlayingSportsEvent && currentMode == ScreenMode.FULLSCREEN
                && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size()) {
            Channel curCh = allChannels.get(currentChannelIdx);
            SportsEvent detected = detectSportsEventForChannel(curCh);
            if (detected != null) {
                activeSportsEvent = detected;
            }
        }

        // Verifica se a lista tem a mesma quantidade e os mesmos IDs na mesma ordem
        boolean sameOrderAndSize = (allSports.size() == fresh.size());
        if (sameOrderAndSize) {
            for (int i = 0; i < fresh.size(); i++) {
                String oldId = allSports.get(i).id;
                String newId = fresh.get(i).id;
                if (!Objects.equals(oldId, newId)) {
                    sameOrderAndSize = false;
                    break;
                }
            }
        }

        if (sameOrderAndSize) {
            // Atualização granular item a item — NÃO remove views e preserva 100% o foco
            for (int i = 0; i < fresh.size(); i++) {
                SportsEvent oldEv = allSports.get(i);
                SportsEvent newEv = fresh.get(i);

                boolean changed = false;
                if (!Objects.equals(oldEv.score, newEv.score)) {
                    oldEv.score = newEv.score;
                    changed = true;
                }
                if (!Objects.equals(oldEv.clock, newEv.clock)) {
                    oldEv.clock = newEv.clock;
                    changed = true;
                }
                if (oldEv.isLive != newEv.isLive) {
                    oldEv.isLive = newEv.isLive;
                    changed = true;
                }
                if (oldEv.isFinished != newEv.isFinished) {
                    oldEv.isFinished = newEv.isFinished;
                    changed = true;
                }
                if (!Objects.equals(oldEv.matchTime, newEv.matchTime)) {
                    oldEv.matchTime = newEv.matchTime;
                    changed = true;
                }
                if (newEv.fallbacks != null && !newEv.fallbacks.isEmpty()) {
                    oldEv.fallbacks = newEv.fallbacks;
                }

                if (changed && sportsRail != null && sportsRail.getAdapter() != null) {
                    sportsRail.getAdapter().notifyItemChanged(i);
                }
            }
        } else {
            // Se a lista mudou de tamanho ou ordem: salva o item que tem o foco antes de recarregar
            int focusedIndex = -1;
            if (sportsRail != null) {
                View focused = sportsRail.getFocusedChild();
                if (focused != null) {
                    focusedIndex = sportsRail.getChildAdapterPosition(focused);
                }
            }
            String focusedId = (focusedIndex >= 0 && focusedIndex < allSports.size()) ? allSports.get(focusedIndex).id : null;

            allSports.clear();
            allSports.addAll(fresh);
            if (sportsRail != null && sportsRail.getAdapter() != null) {
                sportsRail.getAdapter().notifyDataSetChanged();
            }

            // Restaura o foco na posição do mesmo card caso o usuário estivesse na grade
            if (focusedId != null && sportsRail != null) {
                for (int i = 0; i < allSports.size(); i++) {
                    if (focusedId.equals(allSports.get(i).id)) {
                        final int targetPos = i;
                        sportsRail.post(() -> {
                            RecyclerView.ViewHolder vh = sportsRail.findViewHolderForAdapterPosition(targetPos);
                            if (vh != null) {
                                vh.itemView.requestFocus();
                            }
                        });
                        break;
                    }
                }
            }
        }
    }

    /** Abre o hub de jogos (SportsHubActivity) */
    private void openSportsHub() {
        android.content.Intent intent = new android.content.Intent(this, SportsHubActivity.class);
        try {
            java.util.ArrayList<SportsEvent> sportsList = new java.util.ArrayList<>(allSports);
            intent.putExtra("live_events", sportsList);
        } catch (Exception ignored) {}
        startActivityForResult(intent, REQ_SPORTS_HUB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPORTS_HUB && resultCode == RESULT_OK && data != null) {
            String eventId = data.getStringExtra("launch_event_id");
            if (eventId != null && !eventId.isEmpty()) {
                for (SportsEvent ev : allSports) {
                    if (eventId.equals(ev.id)) {
                        playSportsEvent(ev);
                        return;
                    }
                }
            }
        }
    }

    private void setupSportsRail() {
        sportsRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        sportsRail.setAdapter(new SportsRailAdapter(this, allSports, ev -> {
            if (ev != null && ev.isFinished) {
                activeSportsEvent = ev;
                showSportsOverlay();
            } else {
                playSportsEvent(ev);
            }
        }));
        startSportsRefreshTicker();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OVERLAY: TABELA + JOGOS DA RODADA (D-pad Direito em tela cheia esportiva)
    // ─────────────────────────────────────────────────────────────────────────

    private void showSportsOverlay() {
        if (sportsStandingsOverlay == null || activeSportsEvent == null) return;
        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) return;
        standingsOverlayVisible = true;
        sportsStandingsOverlay.setVisibility(View.VISIBLE);

        // Cabeçalho
        if (standingsLeagueName != null) standingsLeagueName.setText(activeSportsEvent.league);
        if (standingsMatchScore != null) {
            if (activeSportsEvent.isLive && activeSportsEvent.score != null) {
                standingsMatchScore.setText("● AO VIVO  " + activeSportsEvent.score +
                        (activeSportsEvent.clock != null ? "  " + activeSportsEvent.clock : ""));
            } else if (activeSportsEvent.isFinished && activeSportsEvent.score != null) {
                standingsMatchScore.setText("Encerrado  " + activeSportsEvent.score);
            } else {
                standingsMatchScore.setText(activeSportsEvent.matchTime != null ? activeSportsEvent.matchTime : "");
            }
        }

        // Mostra a aba padrão (tabela)
        standingsActiveTab = "standings";
        applyTabStyle();

        // Mostra loading e busca dados em background
        if (standingsProgress != null) standingsProgress.setVisibility(View.VISIBLE);
        if (standingsEmptyMsg != null) standingsEmptyMsg.setVisibility(View.GONE);
        if (standingsTablePanel != null) standingsTablePanel.setVisibility(View.GONE);
        if (roundMatchesPanel != null) roundMatchesPanel.setVisibility(View.GONE);

        final String comp = activeSportsEvent.league;
        final String home = activeSportsEvent.homeName;
        final String away = activeSportsEvent.awayName;

        executor.execute(() -> {
            List<ApiClient.StandingEntry> standings = ApiClient.getStandings(comp, home, away);
            List<ApiClient.RoundMatch> roundMatches = ApiClient.getRoundMatches(comp, home, away);
            mainHandler.post(() -> {
                if (!standingsOverlayVisible) return;
                if (standingsProgress != null) standingsProgress.setVisibility(View.GONE);

                boolean hasStandings = !standings.isEmpty();
                boolean hasRound = !roundMatches.isEmpty();

                if (!hasStandings && !hasRound) {
                    if (standingsEmptyMsg != null) standingsEmptyMsg.setVisibility(View.VISIBLE);
                    return;
                }

                // Popula standings
                if (standingsRecycler != null && hasStandings) {
                    standingsRecycler.setAdapter(new StandingsAdapter(standings));
                    if (standingsLeagueName != null && !standings.isEmpty()) {
                        String grp = standings.get(0).groupName;
                        if (grp != null && !grp.isEmpty() && !grp.matches("\\d{4}")) {
                            standingsLeagueName.setText(activeSportsEvent.league + " • " + grp);
                        }
                    }
                }

                // Popula rodada
                if (roundMatchesRecycler != null && hasRound) {
                    roundMatchesRecycler.setAdapter(new RoundMatchesAdapter(roundMatches));
                }

                // Escolhe qual aba mostrar (tabela tem prioridade, mas se não existe vai direto p/ rodada)
                if (hasStandings) {
                    standingsActiveTab = "standings";
                } else {
                    standingsActiveTab = "round";
                }
                applyTabStyle();
                renderActiveTab(hasStandings, hasRound);
            });
        });

        // Foca na aba de tabela
        if (tabStandings != null) tabStandings.requestFocus();
    }

    private void hideSportsOverlay() {
        if (sportsStandingsOverlay == null) return;
        standingsOverlayVisible = false;
        sportsStandingsOverlay.setVisibility(View.GONE);
        if (!isPlayingSportsEvent) {
            activeSportsEvent = null;
        }
    }

    private SportsEvent detectSportsEventForChannel(Channel ch) {
        if (ch == null) return null;
        // 1. Match direto por candidateChannels em allSports
        if (ch.id != null && !allSports.isEmpty()) {
            for (SportsEvent ev : allSports) {
                if (ev.isLive && ev.candidateChannels != null && ev.candidateChannels.contains(ch.id)) {
                    return ev;
                }
            }
        }
        // 2. Match por título do EPG atual vs eventos em allSports
        LiveSchedule epg = EpgEngine.getLiveSchedule(ch);
        if (epg != null && epg.nowTitle != null && !epg.nowTitle.isEmpty() && !allSports.isEmpty()) {
            String epgLow = epg.nowTitle.toLowerCase(Locale.ROOT);
            for (SportsEvent ev : allSports) {
                if (!ev.isLive) continue;
                if (ev.name != null && epgLow.contains(ev.name.toLowerCase(Locale.ROOT))) {
                    return ev;
                }
                if (ev.homeName != null && ev.awayName != null) {
                    String h = ev.homeName.toLowerCase(Locale.ROOT);
                    String a = ev.awayName.toLowerCase(Locale.ROOT);
                    if (epgLow.contains(h) && epgLow.contains(a)) {
                        return ev;
                    }
                }
            }
        }
        // 3. Fallback sintético: título do EPG no formato "Time A x Time B", "Time A X Time B", etc.
        if (epg != null && epg.nowTitle != null) {
            String title = epg.nowTitle;
            String sep = null;
            if (title.contains(" x ")) sep = " x ";
            else if (title.contains(" X ")) sep = " X ";
            else if (title.contains(" vs ")) sep = " vs ";
            else if (title.contains(" VS ")) sep = " VS ";
            else if (title.contains(" v ")) sep = " v ";

            if (sep != null) {
                SportsEvent synth = new SportsEvent();
                synth.name = title;
                synth.isLive = true;
                String[] parts = title.split(sep, 2);
                if (parts.length == 2) {
                    synth.homeName = parts[0].trim();
                    synth.awayName = parts[1].trim();
                }
                if (epg.synopsis != null && !epg.synopsis.isEmpty()) {
                    String firstLine = epg.synopsis.split("\n")[0].trim();
                    synth.league = firstLine.isEmpty() ? "Futebol" : firstLine;
                } else {
                    synth.league = "Futebol";
                }
                if (ch.id != null) {
                    synth.candidateChannels = new ArrayList<>();
                    synth.candidateChannels.add(ch.id);
                }
                return synth;
            }
        }
        return null;
    }

    private void switchStandingsTab(String tab) {
        standingsActiveTab = tab;
        applyTabStyle();
        boolean hasStandings = standingsRecycler != null && standingsRecycler.getAdapter() != null
                && standingsRecycler.getAdapter().getItemCount() > 0;
        boolean hasRound = roundMatchesRecycler != null && roundMatchesRecycler.getAdapter() != null
                && roundMatchesRecycler.getAdapter().getItemCount() > 0;
        renderActiveTab(hasStandings, hasRound);
    }

    private void applyTabStyle() {
        if (tabStandings == null || tabRound == null) return;
        boolean isStandings = "standings".equals(standingsActiveTab);
        tabStandings.setTextColor(isStandings ? 0xFFFFFFFF : 0x80FFFFFF);
        tabStandings.setBackgroundColor(isStandings ? 0x991565C0 : 0x00000000);
        tabRound.setTextColor(!isStandings ? 0xFFFFFFFF : 0x80FFFFFF);
        tabRound.setBackgroundColor(!isStandings ? 0x991565C0 : 0x00000000);
    }

    private void renderActiveTab(boolean hasStandings, boolean hasRound) {
        if (standingsTablePanel == null || roundMatchesPanel == null) return;
        if ("standings".equals(standingsActiveTab) && hasStandings) {
            standingsTablePanel.setVisibility(View.VISIBLE);
            roundMatchesPanel.setVisibility(View.GONE);
        } else if ("round".equals(standingsActiveTab) && hasRound) {
            standingsTablePanel.setVisibility(View.GONE);
            roundMatchesPanel.setVisibility(View.VISIBLE);
        } else {
            // Aba solicitada não tem dados: tenta a outra
            if (hasStandings) {
                standingsTablePanel.setVisibility(View.VISIBLE);
                roundMatchesPanel.setVisibility(View.GONE);
            } else if (hasRound) {
                standingsTablePanel.setVisibility(View.GONE);
                roundMatchesPanel.setVisibility(View.VISIBLE);
            }
        }
    }

    // ─── Adapter: Tabela de Classificação ────────────────────────────────────

    private class StandingsAdapter extends RecyclerView.Adapter<StandingsAdapter.VH> {
        private final List<ApiClient.StandingEntry> items;
        StandingsAdapter(List<ApiClient.StandingEntry> items) { this.items = items; }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(parent.getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp(36)));
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ApiClient.StandingEntry e = items.get(pos);
            // Destaque para os times da partida atual
            int bg = e.isHighlighted ? 0x33FFD700 : (pos % 2 == 0 ? 0x0AFFFFFF : 0x00000000);
            h.row.setBackgroundColor(bg);

            String sgStr = (e.goalDiff >= 0 ? "+" : "") + e.goalDiff;
            h.pos.setText(String.valueOf(e.position));
            h.pos.setTextColor(e.isHighlighted ? 0xFFFFD700 : 0xFF888888);
            h.team.setText(e.teamAbbr != null && !e.teamAbbr.isEmpty() ? e.teamAbbr : e.teamName);
            h.team.setTextColor(e.isHighlighted ? 0xFFFFD700 : 0xFFFFFFFF);
            h.pts.setText(String.valueOf(e.points));
            h.pts.setTextColor(0xFFFFD700);
            h.played.setText(String.valueOf(e.played));
            h.played.setTextColor(e.isHighlighted ? 0xFFFFD700 : 0xFF888888);
            h.wins.setText(String.valueOf(e.wins));
            h.wins.setTextColor(e.isHighlighted ? 0xFFFFD700 : 0xFFFFFFFF);
            h.sg.setText(sgStr);
            h.sg.setTextColor(e.isHighlighted ? 0xFFFFD700 : 0xFF888888);
            h.gf.setText(String.valueOf(e.goalsFor));
            h.gf.setTextColor(e.isHighlighted ? 0xFFFFD700 : 0xFF888888);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            android.widget.LinearLayout row;
            android.widget.TextView pos, team, pts, played, wins, sg, gf;
            VH(android.widget.LinearLayout v) {
                super(v);
                row = v;
                pos    = addCell(v, dp(24), android.view.Gravity.CENTER, 0xFF888888, 10, true);
                team   = addCell(v, 0,       android.view.Gravity.START,  0xFFFFFFFF, 11, false);
                ((android.widget.LinearLayout.LayoutParams) team.getLayoutParams()).weight = 1;
                ((android.widget.LinearLayout.LayoutParams) team.getLayoutParams()).setMarginStart(dp(4));
                pts    = addCell(v, dp(28), android.view.Gravity.CENTER, 0xFFFFD700, 10, true);
                played = addCell(v, dp(24), android.view.Gravity.CENTER, 0xFF888888, 10, false);
                wins   = addCell(v, dp(24), android.view.Gravity.CENTER, 0xFFFFFFFF, 10, false);
                sg     = addCell(v, dp(24), android.view.Gravity.CENTER, 0xFF888888, 10, false);
                gf     = addCell(v, dp(24), android.view.Gravity.CENTER, 0xFF888888, 10, false);
            }
        }

        private android.widget.TextView addCell(android.widget.LinearLayout parent, int widthPx,
                int gravity, int color, int spSize, boolean bold) {
            android.widget.TextView tv = new android.widget.TextView(parent.getContext());
            android.widget.LinearLayout.LayoutParams lp;
            if (widthPx == 0) {
                lp = new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            } else {
                lp = new android.widget.LinearLayout.LayoutParams(widthPx,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            }
            tv.setLayoutParams(lp);
            tv.setGravity(gravity);
            tv.setTextColor(color);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, spSize);
            if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
            parent.addView(tv);
            return tv;
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }

    // ─── Adapter: Jogos da Rodada ─────────────────────────────────────────────

    private class RoundMatchesAdapter extends RecyclerView.Adapter<RoundMatchesAdapter.VH> {
        private final List<ApiClient.RoundMatch> items;
        RoundMatchesAdapter(List<ApiClient.RoundMatch> items) { this.items = items; }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(parent.getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ApiClient.RoundMatch m = items.get(pos);
            boolean live = "in".equalsIgnoreCase(m.state);
            boolean fin  = "post".equalsIgnoreCase(m.state);

            int bg = m.isCurrent ? 0x33FFD700 : (pos % 2 == 0 ? 0x0AFFFFFF : 0x00000000);
            h.row.setBackgroundColor(bg);

            h.home.setText(m.homeTeam != null ? m.homeTeam : "");
            h.home.setTextColor(m.isCurrent ? 0xFFFFD700 : 0xFFFFFFFF);

            h.away.setText(m.awayTeam != null ? m.awayTeam : "");
            h.away.setTextColor(m.isCurrent ? 0xFFFFD700 : 0xFFFFFFFF);

            h.scoreTv.setText(m.score != null ? m.score : "vs");
            if (live) {
                h.scoreTv.setTextColor(0xFF4FC3F7);
                h.statusTv.setText(m.statusLabel != null ? m.statusLabel : "AO VIVO");
                h.statusTv.setTextColor(0xFF4FC3F7);
            } else if (fin) {
                h.scoreTv.setTextColor(m.isCurrent ? 0xFFFFD700 : 0xFFFFFFFF);
                h.statusTv.setText(m.statusLabel != null ? m.statusLabel : "Encerrado");
                h.statusTv.setTextColor(0xFF888888);
            } else {
                h.scoreTv.setTextColor(0xFF888888);
                h.statusTv.setText(m.statusLabel != null ? m.statusLabel : m.matchTime);
                h.statusTv.setTextColor(0xFF4FC3F7);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            android.widget.LinearLayout row;
            android.widget.TextView home;
            android.widget.LinearLayout centerBox;
            android.widget.TextView scoreTv;
            android.widget.TextView statusTv;
            android.widget.TextView away;

            VH(android.widget.LinearLayout v) {
                super(v);
                row = v;
                home = makeTv(v, 0, android.view.Gravity.END, 0xFFFFFFFF, 12, false, 1);

                centerBox = new android.widget.LinearLayout(v.getContext());
                centerBox.setOrientation(android.widget.LinearLayout.VERTICAL);
                centerBox.setGravity(android.view.Gravity.CENTER);
                android.widget.LinearLayout.LayoutParams cLp = new android.widget.LinearLayout.LayoutParams(
                        dp(100), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                cLp.setMargins(dp(6), 0, dp(6), 0);
                centerBox.setLayoutParams(cLp);
                v.addView(centerBox);

                scoreTv = new android.widget.TextView(v.getContext());
                scoreTv.setGravity(android.view.Gravity.CENTER);
                scoreTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                scoreTv.setTypeface(null, android.graphics.Typeface.BOLD);
                centerBox.addView(scoreTv);

                statusTv = new android.widget.TextView(v.getContext());
                statusTv.setGravity(android.view.Gravity.CENTER);
                statusTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
                statusTv.setMaxLines(1);
                statusTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                centerBox.addView(statusTv);

                away = makeTv(v, 0, android.view.Gravity.START, 0xFFFFFFFF, 12, false, 1);
            }
        }

        private android.widget.TextView makeTv(android.widget.LinearLayout parent, int widthPx,
                int gravity, int color, int sp, boolean bold, int weight) {
            android.widget.TextView tv = new android.widget.TextView(parent.getContext());
            android.widget.LinearLayout.LayoutParams lp = widthPx == 0
                    ? new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                    : new android.widget.LinearLayout.LayoutParams(widthPx, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            tv.setLayoutParams(lp);
            tv.setGravity(gravity);
            tv.setTextColor(color);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
            parent.addView(tv);
            return tv;
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }

    private static boolean isDemoMovie(Movie m) {
        if (m == null) return false;
        String n = m.name != null ? m.name.toLowerCase() : "";
        String t = m.title != null ? m.title.toLowerCase() : "";
        return n.contains("demo") || t.contains("demo");
    }

    private static final String PREF_RECENT_MOVIES = "andplay_recent_movies";

    private void saveRecentMovie(Movie movie) {
        if (movie == null || movie.stream_id == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_RECENT_MOVIES, Context.MODE_PRIVATE);
            String raw = prefs.getString("history", "");
            List<String> list = new ArrayList<>();
            if (!raw.isEmpty()) {
                for (String id : raw.split(",")) {
                    if (!id.trim().isEmpty() && !id.trim().equals(movie.stream_id)) {
                        list.add(id.trim());
                    }
                }
            }
            list.add(0, movie.stream_id);
            while (list.size() > 500) {
                list.remove(list.size() - 1);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            prefs.edit().putString("history", sb.toString()).apply();
        } catch (Exception e) {
            Log.w("EPlay", "Erro ao salvar filme recente: " + e.getMessage());
        }
    }

    private List<String> getRecentMovieIds() {
        List<String> list = new ArrayList<>();
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_RECENT_MOVIES, Context.MODE_PRIVATE);
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

    private void setupMoviesRail() {
        if (cachedMovies == null || cachedMovies.isEmpty() || moviesRail == null) return;
        List<Movie> orderedMovies = new ArrayList<>();
        List<String> recentIds = getRecentMovieIds();
        for (String id : recentIds) {
            for (Movie m : cachedMovies) {
                if (m.stream_id != null && m.stream_id.equals(id)) {
                    if (!orderedMovies.contains(m)) {
                        orderedMovies.add(m);
                    }
                    break;
                }
            }
            if (orderedMovies.size() >= 5) break;
        }

        for (Movie m : cachedMovies) {
            if (isDemoMovie(m)) continue;
            if (!orderedMovies.contains(m)) {
                orderedMovies.add(m);
            }
            if (orderedMovies.size() >= 30) break;
        }

        moviesRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        moviesRail.setAdapter(new MoviePosterAdapter(this, orderedMovies, new MoviePosterAdapter.OnMovieActionListener() {
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
            while (list.size() > 500) {
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
            if (orderedSeries.size() >= 5) break;
        }

        for (Series s : cachedSeries) {
            String nameLow = s.name != null ? s.name.toLowerCase() : "";
            String titleLow = s.title != null ? s.title.toLowerCase() : "";
            if (nameLow.contains("demo") || titleLow.contains("demo")) continue;
            if (!orderedSeries.contains(s)) {
                orderedSeries.add(s);
            }
            if (orderedSeries.size() >= 30) break;
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

    private void triggerViewTap(View view) {
        if (view == null) return;
        view.post(() -> {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) return;
            float x = w / 2.0f;
            float y = h / 2.0f;
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            if (isMosaicActive && currentMosaicFocusedIdx >= 0 && currentMosaicFocusedIdx < 4) {
                if (mosaicSlots[currentMosaicFocusedIdx] != null && mosaicSlots[currentMosaicFocusedIdx].slotView != null) {
                    mosaicSlots[currentMosaicFocusedIdx].slotView.requestFocus();
                }
            }
        });
    }

    private void triggerAutoplayTap() {
        if (unifiedEmbedWebView == null) return;
        // Segurança absoluta: nunca disparar touch events simulados no modo CENTRAL para não clicar acidentalmente nos canais do grid
        if (currentMode != ScreenMode.FULLSCREEN) return;
        triggerViewTap(unifiedEmbedWebView);
    }

    private void setupDrawer() {
        setupDrawerForLiveTv();
    }

    private void setupDrawerForLiveTv() {
        if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.VISIBLE);
        if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.VISIBLE);
        if (drawerHeaderTitle != null) {
            drawerHeaderTitle.setText("GUIA DE PROGRAMAÇÃO");
        }

        drawerCats.clear();
        drawerCats.add(new Category("ALL", "Todos"));
        drawerCats.add(new Category("open_tv", "Abertos"));
        drawerCats.add(new Category("sports", "Esportes"));
        drawerCats.add(new Category("variety", "Variedades"));
        drawerCats.add(new Category("kids", "Infantil"));
        drawerCats.add(new Category("channels_24h", "24 Horas"));
        drawerCats.add(new Category("other", "Outros"));

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
        if (currentCh != null) {
            int rank = getChannelGroupRank(currentCh);
            String targetCatId = "ALL";
            switch (rank) {
                case 1: targetCatId = "open_tv"; break;
                case 2: targetCatId = "sports"; break;
                case 3: targetCatId = "variety"; break;
                case 4: targetCatId = "kids"; break;
                case 5: targetCatId = "channels_24h"; break;
                case 6: targetCatId = "other"; break;
            }
            for (int i = 0; i < drawerCats.size(); i++) {
                if (targetCatId.equals(drawerCats.get(i).category_id)) {
                    selectedDrawerCatIdx = i;
                    break;
                }
            }
        }

        selectDrawerCategory(selectedDrawerCatIdx, currentCh);
    }

    private void setupDrawerForSports() {
        if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.GONE);
        if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.GONE);
        if (activeSportsEvent == null) return;

        if (drawerHeaderTitle != null) {
            drawerHeaderTitle.setText("⚽ " + activeSportsEvent.getDisplayName());
        }

        // Popula drawerCats com apenas uma categoria: a liga do evento
        drawerCats.clear();
        drawerCats.add(new Category("SPORTS", activeSportsEvent.getDisplayLeague()));
        drawerCatsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        drawerCatsAdapter = new CategoryPillAdapter(drawerCats, cat -> {});
        drawerCatsAdapter.setSelectedId("SPORTS");
        drawerCatsRecycler.setAdapter(drawerCatsAdapter);

        // Converte os fallbacks em pseudo-canais para reutilizar ChannelRailAdapter
        List<Channel> pseudoChannels = new ArrayList<>();
        for (Channel.StreamFallback fb : activeSportsEvent.fallbacks) {
            Channel pseudo = new Channel();
            pseudo.name = fb.name;
            pseudo.now = fb.isEmbed ? "Transmissão via embed" : "Transmissão via HLS";
            pseudoChannels.add(pseudo);
        }

        drawerChannelsRecycler.setLayoutManager(new LinearLayoutManager(this));
        ChannelRailAdapter sportsAdapter = new ChannelRailAdapter(this, pseudoChannels, true, (channel, index) -> {
            closeDrawer();
            startSportsPlayback(activeSportsEvent, index);
        });
        sportsAdapter.setCurrentPlayingIdx(currentFallbackIdx);
        drawerChannelsRecycler.setAdapter(sportsAdapter);

        drawerChannelsRecycler.scrollToPosition(currentFallbackIdx);
        drawerChannelsRecycler.postDelayed(() -> {
            RecyclerView.ViewHolder vh = drawerChannelsRecycler.findViewHolderForAdapterPosition(currentFallbackIdx);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else if (drawerChannelsRecycler.getChildCount() > 0) {
                View first = drawerChannelsRecycler.getChildAt(0);
                if (first != null) first.requestFocus();
            }
        }, 80);
    }

    private void setupDrawerForSeries() {
        if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.GONE);
        if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.GONE);
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
        if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.GONE);
        if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.GONE);
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
        // No modo esportivo, há apenas uma categoria (não deve navegar entre categorias)
        if (isPlayingSportsEvent) return;
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
            if ("ALL".equals(catId)) {
                filtered.add(ch);
            } else if ("open_tv".equals(catId) && getChannelGroupRank(ch) == 1) {
                filtered.add(ch);
            } else if ("sports".equals(catId) && getChannelGroupRank(ch) == 2) {
                filtered.add(ch);
            } else if ("variety".equals(catId) && getChannelGroupRank(ch) == 3) {
                filtered.add(ch);
            } else if ("kids".equals(catId) && getChannelGroupRank(ch) == 4) {
                filtered.add(ch);
            } else if ("channels_24h".equals(catId) && getChannelGroupRank(ch) == 5) {
                filtered.add(ch);
            } else if ("other".equals(catId) && getChannelGroupRank(ch) == 6) {
                filtered.add(ch);
            }
        }
        ChannelRailAdapter adapter = new ChannelRailAdapter(this, filtered, true, (ch, idx) -> {
            if (isMosaicActive) {
                closeDrawer();
                if (mosaicTargetSlotIdx >= 0 && mosaicTargetSlotIdx < 4) {
                    tuneMosaicSlot(mosaicTargetSlotIdx, ch);
                    if (mosaicSlots[mosaicTargetSlotIdx] != null && mosaicSlots[mosaicTargetSlotIdx].slotView != null) {
                        mosaicSlots[mosaicTargetSlotIdx].slotView.requestFocus();
                        onMosaicSlotFocused(mosaicTargetSlotIdx);
                    }
                }
                return;
            }
            int realIdx = allChannels.indexOf(ch);
            int targetIdx = realIdx >= 0 ? realIdx : idx;
            // Se for o mesmo canal que já está tocando, abre o guia completo de programação
            if (targetIdx == currentChannelIdx && (currentActiveStreamUrl != null && !currentActiveStreamUrl.isEmpty())) {
                closeDrawer();
                openFullGuide();
                return;
            }
            // Canal diferente: destrói conexões anteriores e sintoniza
            destroyCurrentStream();
            tuneChannel(targetIdx, true);
            closeDrawer();
            setScreenMode(ScreenMode.FULLSCREEN);
        });

        int playingIdx = -1;
        if (!allChannels.isEmpty() && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size()) {
            Channel curr = allChannels.get(currentChannelIdx);
            playingIdx = filtered.indexOf(curr);
            if (playingIdx < 0 && curr.id != null) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (curr.id.equals(filtered.get(i).id)) {
                        playingIdx = i;
                        break;
                    }
                }
            }
        }

        int focusPos = 0;
        if (targetChannel != null) {
            focusPos = filtered.indexOf(targetChannel);
            if (focusPos < 0) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (filtered.get(i).id != null && filtered.get(i).id.equals(targetChannel.id)) {
                        focusPos = i;
                        break;
                    }
                }
            }
            if (focusPos < 0) focusPos = 0;
        } else if (playingIdx >= 0) {
            focusPos = playingIdx;
        }

        drawerChannelsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        adapter.setCurrentPlayingIdx(playingIdx);
        drawerChannelsRecycler.setAdapter(adapter);

        final int focusIndex = focusPos;
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
        isPlayingSportsEvent = false;
        activeSportsEvent = null;
        try {
            SharedPreferences sp = getSharedPreferences(PREF_APP_STATE, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_LAST_CHANNEL_ID, ch.id != null ? ch.id : "")
                    .putInt(KEY_LAST_CHANNEL_INDEX, currentChannelIdx)
                    .apply();
            addRecentChannel(ch.id);
        } catch (Exception ignored) {}

        LiveSchedule epg = EpgEngine.getLiveSchedule(ch);

        // Detectar match esportivo via candidateChannels, título EPG ou padrão sintético
        activeSportsEvent = detectSportsEventForChannel(ch);

        // Atualiza PiP
        pipChannelName.setText(String.format("%03d - %s", currentChannelIdx + 1, ch.name));
        pipProgramTitle.setText("🔴 No Ar: " + (epg != null && !"SEM DADOS DE PROGRAMAÇÃO".equals(epg.nowTitle) ? epg.nowTitle : "SEM DADOS DE PROGRAMAÇÃO"));
        if (currentMode == ScreenMode.CENTRAL && pipContainer != null && pipContainer.isFocused()) {
            showPipOverlay();
        }

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

    private void stepSportsFallback(int step) {
        if (activeSportsEvent == null || activeSportsEvent.fallbacks == null || activeSportsEvent.fallbacks.isEmpty()) return;
        int size = activeSportsEvent.fallbacks.size();
        currentFallbackIdx = ((currentFallbackIdx + step) % size + size) % size;
        Channel.StreamFallback fb = activeSportsEvent.fallbacks.get(currentFallbackIdx);

        // Atualiza OSD sem trocar a transmissão ainda (preview por 3s)
        osdChNum.setText("JOGO");
        osdChName.setText(activeSportsEvent.getDisplayName());
        String leagueInfo = "⚽ " + activeSportsEvent.getDisplayLeague();
        if ((activeSportsEvent.isLive || activeSportsEvent.isFinished) && activeSportsEvent.score != null && !activeSportsEvent.score.isEmpty()) {
            leagueInfo += " • Placar: " + activeSportsEvent.score;
            if (activeSportsEvent.clock != null && !activeSportsEvent.clock.isEmpty()) {
                leagueInfo += " (" + activeSportsEvent.clock + ")";
            }
        } else if (activeSportsEvent.matchTime != null && !activeSportsEvent.matchTime.isEmpty()) {
            leagueInfo += " • Horário: " + activeSportsEvent.matchTime;
        }
        osdNowTitle.setText(leagueInfo);
        osdSynopsis.setText(activeSportsEvent.getDisplayName() + ((activeSportsEvent.isLive || activeSportsEvent.isFinished) && activeSportsEvent.score != null ? " [" + activeSportsEvent.score + "]" : "") + " - Transmissão via " + fb.name);
        osdRemaining.setText(activeSportsEvent.clock != null && !activeSportsEvent.clock.isEmpty() ? activeSportsEvent.clock : (activeSportsEvent.matchTime != null ? activeSportsEvent.matchTime : "Ao Vivo"));
        showOsdBanner(5000);

        zapHandler.removeCallbacks(zapConfirmRunnable);
        zapHandler.postDelayed(() -> {
            startSportsPlayback(activeSportsEvent, currentFallbackIdx);
        }, 3000);
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
                if (isPlayingEmbed && !isVideoPlaybackActive && currentMode == ScreenMode.FULLSCREEN) {
                    triggerAutoplayTap();
                }
            }, 1200);
            mainHandler.postDelayed(() -> {
                if (isPlayingEmbed) {
                    onPlaybackStarted();
                    if (currentMode == ScreenMode.FULLSCREEN) {
                        scheduleOsdHide(getOsdTimeoutMs());
                    }
                }
            }, 2200);
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
        activeMoviePlaylistQueue = null;
        activeMoviePlaylistIndex = -1;
        activeMoviePlaylistName = null;
        String key = getMovieProgressKey(movie);
        long savedPos = getVodProgress(key);

        if (savedPos > 5000) {
            String[] options = new String[] {
                    "▶️ CONTINUAR DE ONDE PAROU (" + formatDuration(savedPos) + ")",
                    "🔄 VOLTAR AO INÍCIO"
            };
            AlertDialog dialog = createThemedDialogBuilder()
                    .setTitle("🎬 " + movie.getDisplayTitle() + "\n(Parou em " + formatDuration(savedPos) + ")")
                    .setItems(options, (d, which) -> {
                        d.dismiss();
                        if (which == 0) {
                            startMoviePlayback(movie, savedPos);
                        } else {
                            clearVodProgress(key);
                            startMoviePlayback(movie, 0);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .create();
            styleDialogButtons(dialog);
            dialog.show();
        } else {
            startMoviePlayback(movie, 0);
        }
    }

    private void startMoviePlayback(Movie movie, long startPos) {
        saveRecentMovie(movie);
        destroyCurrentStream();
        activeVodMovie = movie;
        activeVodSeries = null;
        activeVodEpisode = null;
        isPlayingVod = true;
        setScreenMode(ScreenMode.FULLSCREEN);

        String streamUrl = movie.getStreamUrl(ApiClient.SERVER, ApiClient.USER, ApiClient.PASS);
        playStream(streamUrl, false, startPos);

        // Preenche OSD com dados do filme
        if (activeMoviePlaylistQueue != null && !activeMoviePlaylistQueue.isEmpty()) {
            String plLabel = (activeMoviePlaylistName != null ? activeMoviePlaylistName : "PLAYLIST") +
                    " (" + (activeMoviePlaylistIndex + 1) + "/" + activeMoviePlaylistQueue.size() + ")";
            topChNum.setText(plLabel);
            osdChNum.setText(plLabel);
        } else {
            topChNum.setText("FILME");
            osdChNum.setText("FILME");
        }
        topChName.setText(movie.getDisplayTitle());
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
        if (savedPos > 5000) {
            String[] options = new String[] {
                    "▶️ CONTINUAR DE ONDE PAROU (" + formatDuration(savedPos) + ")",
                    "🔄 VOLTAR AO INÍCIO"
            };
            AlertDialog dialog = createThemedDialogBuilder()
                    .setTitle("🍿 " + fullTitle + "\n(Parou em " + formatDuration(savedPos) + ")")
                    .setItems(options, (d, which) -> {
                        d.dismiss();
                        if (which == 0) {
                            startSeriesEpisodePlayback(series, ep, seasonNum, savedPos);
                        } else {
                            clearVodProgress(key);
                            startSeriesEpisodePlayback(series, ep, seasonNum, 0);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .create();
            styleDialogButtons(dialog);
            dialog.show();
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

    private Channel findChannelByCandidate(String cand) {
        if (cand == null || cand.trim().isEmpty()) return null;
        String cNorm = EpgEngine.normalizeForMatch(cand).replace(" ", "");
        for (Channel ch : allChannels) {
            if (ch.name != null) {
                String chNorm = EpgEngine.normalizeForMatch(ch.name).replace(" ", "");
                if (chNorm.equals(cNorm) || chNorm.contains(cNorm) || cNorm.contains(chNorm)) {
                    return ch;
                }
            }
            if (ch.id != null) {
                String idNorm = EpgEngine.normalizeForMatch(ch.id).replace("-", "");
                if (idNorm.equals(cNorm) || idNorm.contains(cNorm) || cNorm.contains(idNorm)) {
                    return ch;
                }
            }
        }
        return null;
    }

    public void playSportsEvent(SportsEvent ev) {
        if (ev == null) return;

        // 1. Verifica candidatos específicos da partida contra o EPG
        if (ev.candidateChannels != null && !ev.candidateChannels.isEmpty()) {
            for (String candName : ev.candidateChannels) {
                Channel candCh = findChannelByCandidate(candName);
                if (candCh != null && EpgEngine.channelEpgMatchesEvent(candCh, ev)) {
                    int chIdx = allChannels.indexOf(candCh);
                    if (chIdx >= 0) {
                        Toast.makeText(this, "⚽ " + candCh.name + " confirmado no Guia EPG", Toast.LENGTH_SHORT).show();
                        tuneChannel(chIdx, true);
                        setScreenMode(ScreenMode.FULLSCREEN);
                        return;
                    }
                }
            }
        }

        // 2. Busca na grade de canais esportivos e abertos pelo evento no EPG
        for (int i = 0; i < allChannels.size(); i++) {
            Channel ch = allChannels.get(i);
            String cat = ch.cat != null ? ch.cat.toLowerCase(Locale.ROOT) : "";
            if (cat.contains("esport") || cat.contains("sport") || cat.contains("abert")) {
                if (EpgEngine.channelEpgMatchesEvent(ch, ev)) {
                    Toast.makeText(this, "⚽ " + ch.name + " confirmado no Guia EPG", Toast.LENGTH_SHORT).show();
                    tuneChannel(i, true);
                    setScreenMode(ScreenMode.FULLSCREEN);
                    return;
                }
            }
        }

        // 3. Se não houver confirmação no EPG, mas o primeiro canal candidato existir na grade, sintoniza direto
        if (ev.candidateChannels != null && !ev.candidateChannels.isEmpty()) {
            Channel candCh = findChannelByCandidate(ev.candidateChannels.get(0));
            if (candCh != null) {
                int chIdx = allChannels.indexOf(candCh);
                if (chIdx >= 0) {
                    Toast.makeText(this, "📺 Sintonizando " + candCh.name + " para a partida", Toast.LENGTH_SHORT).show();
                    tuneChannel(chIdx, true);
                    setScreenMode(ScreenMode.FULLSCREEN);
                    return;
                }
            }
        }

        // 4. Se houver transmissões/fallbacks diretos salvos no evento
        List<Channel.StreamFallback> options = ev.fallbacks;
        if (options != null && !options.isEmpty()) {
            if (options.size() == 1) {
                startSportsPlayback(ev, 0);
            } else {
                String[] names = new String[options.size()];
                for (int i = 0; i < options.size(); i++) {
                    Channel.StreamFallback fb = options.get(i);
                    names[i] = "📺 " + fb.name;
                }

                AlertDialog dialog = createThemedDialogBuilder()
                        .setTitle("⚽ " + ev.getDisplayName() + "\nEscolha a transmissão:")
                        .setItems(names, (d, which) -> {
                            d.dismiss();
                            startSportsPlayback(ev, which);
                        })
                        .setNegativeButton("Cancelar", null)
                        .create();
                styleDialogButtons(dialog);
                dialog.show();
            }
            return;
        }

        Toast.makeText(this, "Nenhuma transmissão confirmada para esta partida no momento.", Toast.LENGTH_SHORT).show();
    }

    private void startSportsPlayback(SportsEvent ev, int fallbackIndex) {
        destroyCurrentStream();
        isPlayingVod = false;
        isPlayingSportsEvent = true;
        activeSportsEvent = ev;
        setScreenMode(ScreenMode.FULLSCREEN);

        currentChannelFallbacks = ev.fallbacks;
        currentFallbackIdx = Math.max(0, Math.min(fallbackIndex, ev.fallbacks.size() - 1));
        Channel.StreamFallback fb = ev.fallbacks.get(currentFallbackIdx);
        playStream(fb.url, fb.isEmbed);

        topChNum.setText(ev.isLive ? "AO VIVO" : (ev.isFinished ? "FINALIZADO" : "JOGO"));
        topChName.setText(ev.getDisplayName() + ((ev.isLive || ev.isFinished) && ev.score != null ? " (" + ev.score + ")" : ""));
        osdChNum.setText("JOGO");
        osdChName.setText(ev.getDisplayName());
        String leagueInfo = "⚽ " + ev.getDisplayLeague();
        if ((ev.isLive || ev.isFinished) && ev.score != null && !ev.score.isEmpty()) {
            leagueInfo += " • Placar: " + ev.score;
            if (ev.clock != null && !ev.clock.isEmpty()) {
                leagueInfo += " (" + ev.clock + ")";
            }
        } else if (ev.matchTime != null && !ev.matchTime.isEmpty()) {
            leagueInfo += " • Horário: " + ev.matchTime;
        }
        osdNowTitle.setText(leagueInfo);
        osdRemaining.setText(ev.clock != null && !ev.clock.isEmpty() ? ev.clock : (ev.isLive ? "Ao Vivo" : (ev.matchTime != null ? ev.matchTime : "Esportes")));
        osdSynopsis.setText(ev.getDisplayName() + ((ev.isLive || ev.isFinished) && ev.score != null ? " [" + ev.score + "]" : "") + " - Transmissão via " + fb.name);
        osdNextProgram.setText("Compactos e melhores momentos ao final da partida.");
        osdProgressBar.setProgress(100);

        showOsdBanner(getOsdTimeoutMs());
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

        // Hint esportivo: mostrar quando há jogo ao vivo neste canal
        if (osdSportsHint != null) {
            if (activeSportsEvent != null) {
                String hintText = "▶  " + activeSportsEvent.getDisplayName();
                if (activeSportsEvent.isLive && activeSportsEvent.score != null && !activeSportsEvent.score.isEmpty()) {
                    hintText += "  " + activeSportsEvent.score;
                }
                hintText += "  —  DPAD DIREITO: ver tabela";
                osdSportsHint.setText(hintText);
                osdSportsHint.setVisibility(View.VISIBLE);
            } else {
                osdSportsHint.setVisibility(View.GONE);
            }
        }
    }

    private int getOsdTimeoutMs() {
        return (isPlayingEmbed && !isPlayingVod) ? 15000 : 5000;
    }

    public void onPlaybackStarted() {
        boolean wasActive = isVideoPlaybackActive;
        isVideoPlaybackActive = true;
        enforceMaxVolume();
        if (isPlayingVod) {
            startVodProgressTicker();
            updateVodProgress();
        }
        if (!wasActive && currentMode == ScreenMode.FULLSCREEN && osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
            scheduleOsdHide(getOsdTimeoutMs());
        }
    }

    public void scheduleOsdHide(int durationMs) {
        osdHandler.removeCallbacks(osdHideRunnable);
        osdHandler.postDelayed(osdHideRunnable, durationMs);
    }

    public void showOsdBannerLoading() {
        if (isMosaicActive) return;
        osdHandler.removeCallbacks(osdHideRunnable);
        if (topChannelBadge != null) topChannelBadge.setVisibility(View.VISIBLE);
        if (osdBanner != null) osdBanner.setVisibility(View.VISIBLE);
        scheduleOsdHide(getOsdTimeoutMs());
    }

    public void showOsdBanner(int durationMs) {
        if (isMosaicActive) return;
        osdHandler.removeCallbacks(osdHideRunnable);
        if (topChannelBadge != null) topChannelBadge.setVisibility(View.VISIBLE);
        if (osdBanner != null) osdBanner.setVisibility(View.VISIBLE);

        int dur = durationMs > 0 ? durationMs : getOsdTimeoutMs();
        if (isPlayingEmbed && !isPlayingVod && dur < 15000) {
            dur = 15000;
        }

        if (isVideoPlaybackActive) {
            scheduleOsdHide(dur);
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
        if (fullGuideLayout != null && fullGuideLayout.getVisibility() == View.VISIBLE) {
            closeFullGuide();
        }
        if (standingsOverlayVisible) {
            hideSportsOverlay();
        }
        drawerHandler.removeCallbacks(drawerHideRunnable);
        if (epgDrawer != null) {
            epgDrawer.setVisibility(View.VISIBLE);
            hideOsdBanner();
            resetDrawerTimeout();

            if (isPlayingSportsEvent) {
                if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.GONE);
                if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.GONE);
            } else if (isPlayingVod) {
                if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.GONE);
                if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.GONE);
            } else {
                if (btnDrawerOptions != null) btnDrawerOptions.setVisibility(View.VISIBLE);
                if (btnDrawerMosaic != null) btnDrawerMosaic.setVisibility(View.VISIBLE);
            }

            epgDrawer.post(() -> {
                if (isPlayingSportsEvent) {
                    setupDrawerForSports();
                } else if (isPlayingVod) {
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
        if (isMosaicActive && currentMosaicFocusedIdx >= 0 && currentMosaicFocusedIdx < 4) {
            if (mosaicSlots[currentMosaicFocusedIdx] != null && mosaicSlots[currentMosaicFocusedIdx].slotView != null) {
                mosaicSlots[currentMosaicFocusedIdx].slotView.requestFocus();
            }
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
            hideSportsOverlay(); // Fecha overlay de tabela ao sair da tela cheia
            if (isPlayingVod) {
                stopVodProgressTicker();
                destroyCurrentStream();
                isPlayingVod = false;
                activeVodMovie = null;
                activeVodEpisode = null;
                activeVodSeries = null;
                activeMoviePlaylistQueue = null;
                activeMoviePlaylistIndex = -1;
                activeMoviePlaylistName = null;
            }
            if (isPlayingSportsEvent) {
                isPlayingSportsEvent = false;
                activeSportsEvent = null;
            }
        }

        if (mode != ScreenMode.CENTRAL) {
            pipOverlayHandler.removeCallbacks(pipOverlayHideRunnable);
            if (pipOverlayBar != null) {
                pipOverlayBar.setVisibility(View.GONE);
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
                showOsdBanner(getOsdTimeoutMs());
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
                    showPipOverlay();
                }, 100);
            }
            setupChannelsRail();
            setupMoviesRail();
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
            if (seriesEpisodesRecycler != null) {
                seriesEpisodesRecycler.postDelayed(() -> {
                    if (seriesEpisodesRecycler.getChildCount() > 0) {
                        View first = seriesEpisodesRecycler.getChildAt(0);
                        if (first != null) first.requestFocus();
                    } else if (seriesSeasonsRecycler != null) {
                        seriesSeasonsRecycler.requestFocus();
                    }
                }, 100);
            }
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
        isViewingSeries = false;
        if (btnVodPlaylist != null) btnVodPlaylist.setVisibility(View.VISIBLE);
        if (btnVodWatched != null) btnVodWatched.setVisibility(View.VISIBLE);
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
            }
        }

        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 7));
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
        isViewingSeries = true;
        if (btnVodPlaylist != null) btnVodPlaylist.setVisibility(View.GONE);
        if (btnVodWatched != null) btnVodWatched.setVisibility(View.VISIBLE);
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
            }
        }

        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 7));
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

    private static String quickClean(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            switch (c) {
                case 'á': case 'à': case 'ã': case 'â': case 'ä': c = 'a'; break;
                case 'é': case 'è': case 'ê': case 'ë': c = 'e'; break;
                case 'í': case 'ì': case 'î': case 'ï': c = 'i'; break;
                case 'ó': case 'ò': case 'õ': case 'ô': case 'ö': c = 'o'; break;
                case 'ú': case 'ù': case 'û': case 'ü': c = 'u'; break;
                case 'ç': c = 'c'; break;
                case 'ñ': c = 'n'; break;
            }
            if (Character.isLetterOrDigit(c) || c == ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void showVodSearchDialog() {
        AlertDialog.Builder builder = createThemedDialogBuilder();
        builder.setTitle(isViewingSeries ? "🔍 Buscar Séries" : "🔍 Buscar Filmes");

        final EditText input = new EditText(this);
        input.setHint(isViewingSeries ? "Digite o nome da série, gênero ou ator..." : "Digite o nome do filme, gênero ou ator...");

        // Estilização com alto contraste visível em qualquer tema (TV/Box)
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(android.graphics.Color.parseColor("#121826"));
        gd.setStroke((int) (2 * getResources().getDisplayMetrics().density), android.graphics.Color.parseColor("#FFC107"));
        gd.setCornerRadius(10 * getResources().getDisplayMetrics().density);
        input.setBackground(gd);
        input.setTextColor(android.graphics.Color.WHITE);
        input.setHintTextColor(android.graphics.Color.parseColor("#9AA0A6"));
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int padH = (int) (16 * getResources().getDisplayMetrics().density);
        int padV = (int) (12 * getResources().getDisplayMetrics().density);
        input.setPadding(padH, padV, padH, padV);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (20 * getResources().getDisplayMetrics().density);
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.topMargin = margin / 2;
        params.bottomMargin = margin / 2;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("🔍 Buscar", (dialog, which) -> {
            String query = input.getText().toString().trim();
            executeVodSearch(query);
        });

        builder.setNeutralButton("❌ Limpar Filtro", (dialog, which) -> {
            if (isViewingSeries) {
                filterSeriesByCat("ALL", cachedSeries);
            } else {
                filterMoviesByCat("ALL", cachedMovies);
            }
        });

        builder.setNegativeButton("Cancelar", null);

        final AlertDialog dialog = builder.create();
        activeSearchDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeSearchDialog == dialog) {
                activeSearchDialog = null;
            }
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                dialog.dismiss();
                String query = input.getText().toString().trim();
                executeVodSearch(query);
                return true;
            }
            return false;
        });

        styleDialogButtons(dialog);
        dialog.show();
        input.requestFocus();
    }

    private void executeVodSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            if (isViewingSeries) {
                filterSeriesByCat("ALL", cachedSeries);
            } else {
                filterMoviesByCat("ALL", cachedMovies);
            }
            return;
        }

        final String cleanQuery = quickClean(query.trim());
        if (cleanQuery.isEmpty()) return;

        showLoading("Pesquisando por \"" + query + "\"...");

        executor.execute(() -> {
            try {
                if (isViewingSeries) {
                    if (cachedSeries == null || cachedSeries.isEmpty()) {
                        mainHandler.post(this::hideLoading);
                        return;
                    }
                    List<Movie> converted = new ArrayList<>();
                    List<Series> rawFiltered = new ArrayList<>();
                    for (Series s : cachedSeries) {
                        String nameClean = quickClean(s.name);
                        String titleClean = quickClean(s.title);
                        String genreClean = quickClean(s.genre);
                        String castClean = quickClean(s.cast);

                        if (nameClean.contains(cleanQuery) || titleClean.contains(cleanQuery)
                                || genreClean.contains(cleanQuery) || castClean.contains(cleanQuery)) {
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
                        }
                    }

                    mainHandler.post(() -> {
                        hideLoading();
                        if (converted.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Nenhuma série encontrada para \"" + query + "\"", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        vodHeroTitle.setText("🔍 Séries: \"" + query + "\" (" + converted.size() + " encontradas)");
                        vodHeroPlot.setText("Resultados da pesquisa por \"" + query + "\". Selecione para assistir.");

                        vodGridRecycler.setLayoutManager(new GridLayoutManager(MainActivity.this, 7));
                        vodGridRecycler.setAdapter(new MoviePosterAdapter(MainActivity.this, converted, true, new MoviePosterAdapter.OnMovieActionListener() {
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
                        vodGridRecycler.requestFocus();
                    });

                } else {
                    if (cachedMovies == null || cachedMovies.isEmpty()) {
                        mainHandler.post(this::hideLoading);
                        return;
                    }
                    List<Movie> filtered = new ArrayList<>();
                    for (Movie m : cachedMovies) {
                        if (isDemoMovie(m)) continue;
                        String nameClean = quickClean(m.name);
                        String titleClean = quickClean(m.title);
                        String genreClean = quickClean(m.genre);
                        String castClean = quickClean(m.cast);

                        if (nameClean.contains(cleanQuery) || titleClean.contains(cleanQuery)
                                || genreClean.contains(cleanQuery) || castClean.contains(cleanQuery)) {
                            filtered.add(m);
                        }
                    }

                    mainHandler.post(() -> {
                        hideLoading();
                        if (filtered.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Nenhum filme encontrado para \"" + query + "\"", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        vodHeroTitle.setText("🔍 Filmes: \"" + query + "\" (" + filtered.size() + " encontrados)");
                        vodHeroPlot.setText("Resultados da pesquisa por \"" + query + "\". Selecione para assistir.");

                        vodGridRecycler.setLayoutManager(new GridLayoutManager(MainActivity.this, 7));
                        vodGridRecycler.setAdapter(new MoviePosterAdapter(MainActivity.this, filtered, true, new MoviePosterAdapter.OnMovieActionListener() {
                            @Override
                            public void onMovieClick(Movie movie) {
                                playMovie(movie);
                            }

                            @Override
                            public void onMovieFocus(Movie movie) {
                                updateVodHero(movie);
                            }
                        }));
                        vodGridRecycler.requestFocus();
                    });
                }
            } catch (Throwable t) {
                Log.e("EPlay", "Erro na busca VOD", t);
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(MainActivity.this, "Erro ao realizar busca.", Toast.LENGTH_SHORT).show();
                });
            }
        });
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
        seriesDetailTitle.setText("📺 " + series.getDisplayTitle());
        if (seriesDetailRating != null) {
            seriesDetailRating.setText(series.rating != null && !series.rating.isEmpty() ? "★ " + series.rating : "★ 8.0");
        }
        if (seriesDetailYear != null) {
            seriesDetailYear.setText(series.releaseDate != null ? series.releaseDate : "");
        }
        if (seriesDetailGenre != null) {
            seriesDetailGenre.setText(series.genre != null ? series.genre : "");
        }
        if (seriesDetailPlot != null) {
            seriesDetailPlot.setText(series.plot != null ? series.plot : "Temporadas e episódios disponíveis.");
        }

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

        currentSeriesSeasonKeys = seasonKeys;
        currentSeriesSeasonIdx = 0;

        for (String sNum : seasonKeys) {
            seasonPills.add(new Category(sNum, "Temporada " + sNum));
        }

        seriesSeasonsRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        seriesSeasonsRecycler.setAdapter(new CategoryPillAdapter(seasonPills, cat -> {
            int idx = seasonKeys.indexOf(cat.category_id);
            if (idx >= 0) currentSeriesSeasonIdx = idx;
            displaySeasonEpisodes(series, episodesMap.get(cat.category_id), cat.category_id);
        }));

        if (!seasonKeys.isEmpty()) {
            displaySeasonEpisodes(series, episodesMap.get(seasonKeys.get(0)), seasonKeys.get(0));
        }
    }

    private void switchSeriesSeason(int delta) {
        if (currentSeriesSeasonKeys == null || currentSeriesSeasonKeys.isEmpty() || activeVodSeries == null || currentSeriesEpisodesMap == null) return;
        currentSeriesSeasonIdx = (currentSeriesSeasonIdx + delta + currentSeriesSeasonKeys.size()) % currentSeriesSeasonKeys.size();
        String sNum = currentSeriesSeasonKeys.get(currentSeriesSeasonIdx);
        if (seriesSeasonsRecycler != null && seriesSeasonsRecycler.getAdapter() instanceof CategoryPillAdapter) {
            ((CategoryPillAdapter) seriesSeasonsRecycler.getAdapter()).setSelectedId(sNum);
            seriesSeasonsRecycler.smoothScrollToPosition(currentSeriesSeasonIdx);
        }
        displaySeasonEpisodes(activeVodSeries, currentSeriesEpisodesMap.get(sNum), sNum);
        focusFirstEpisode();
    }

    private void focusFirstEpisode() {
        if (seriesEpisodesRecycler == null) return;
        seriesEpisodesRecycler.scrollToPosition(0);
        seriesEpisodesRecycler.post(() -> {
            RecyclerView.ViewHolder vh = seriesEpisodesRecycler.findViewHolderForAdapterPosition(0);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else {
                seriesEpisodesRecycler.postDelayed(() -> {
                    RecyclerView.ViewHolder vh2 = seriesEpisodesRecycler.findViewHolderForAdapterPosition(0);
                    if (vh2 != null && vh2.itemView != null) {
                        vh2.itemView.requestFocus();
                    } else if (seriesEpisodesRecycler.getChildCount() > 0) {
                        seriesEpisodesRecycler.getChildAt(0).requestFocus();
                    }
                }, 60);
            }
        });
    }

    private void focusCurrentSeasonPill() {
        if (seriesSeasonsRecycler == null || currentSeriesSeasonKeys == null || currentSeriesSeasonKeys.isEmpty()) return;
        final int targetPos = Math.max(0, Math.min(currentSeriesSeasonIdx, currentSeriesSeasonKeys.size() - 1));
        seriesSeasonsRecycler.scrollToPosition(targetPos);
        seriesSeasonsRecycler.post(() -> {
            RecyclerView.ViewHolder vh = seriesSeasonsRecycler.findViewHolderForAdapterPosition(targetPos);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else {
                seriesSeasonsRecycler.postDelayed(() -> {
                    RecyclerView.ViewHolder vh2 = seriesSeasonsRecycler.findViewHolderForAdapterPosition(targetPos);
                    if (vh2 != null && vh2.itemView != null) {
                        vh2.itemView.requestFocus();
                    } else if (seriesSeasonsRecycler.getChildCount() > 0) {
                        seriesSeasonsRecycler.getChildAt(0).requestFocus();
                    }
                }, 60);
            }
        });
    }

    private void displaySeasonEpisodes(Series series, List<Episode> eps, String seasonNum) {
        if (eps == null) eps = new ArrayList<>();
        seriesEpisodesRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        seriesEpisodesRecycler.setAdapter(new EpisodeAdapter(this, eps, ep -> {
            playSeriesEpisode(series, ep, seasonNum);
        }, ep -> {
            if (ep != null && seriesDetailPlot != null) {
                String epPlot = ep.getPlot();
                if (epPlot != null && !epPlot.isEmpty()) {
                    seriesDetailPlot.setText("E" + ep.episode_num + " - " + epPlot);
                } else if (series.plot != null) {
                    seriesDetailPlot.setText(series.plot);
                }
            }
        }));
    }

    private void ensureMoviesLoaded(Runnable onReady) {
        if (cachedMovies != null && !cachedMovies.isEmpty()) {
            if (onReady != null) onReady.run();
            return;
        }
        showLoading("Carregando catálogo de filmes...");
        executor.execute(() -> {
            try {
                if (movieCategories == null || movieCategories.isEmpty()) {
                    movieCategories = ApiClient.getMovieCategories();
                }
                cachedMovies = ApiClient.getMovies();
                mainHandler.post(() -> {
                    hideLoading();
                    if (onReady != null) onReady.run();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, "Erro ao carregar filmes: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void ensureSeriesLoaded(Runnable onReady) {
        if (cachedSeries != null && !cachedSeries.isEmpty()) {
            if (onReady != null) onReady.run();
            return;
        }
        showLoading("Carregando catálogo de séries...");
        executor.execute(() -> {
            try {
                if (seriesCategories == null || seriesCategories.isEmpty()) {
                    seriesCategories = ApiClient.getSeriesCategories();
                }
                cachedSeries = ApiClient.getSeries();
                mainHandler.post(() -> {
                    hideLoading();
                    if (onReady != null) onReady.run();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    hideLoading();
                    Toast.makeText(this, "Erro ao carregar séries: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showVodWatchedContent() {
        if (isViewingSeries) {
            ensureSeriesLoaded(() -> {
                List<String> recentIds = getRecentSeriesIds();
                if (recentIds.isEmpty()) {
                    Toast.makeText(this, "Nenhuma série assistida recentemente.", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Series> watchedSeries = new ArrayList<>();
                for (String id : recentIds) {
                    for (Series s : cachedSeries) {
                        if (s.series_id != null && s.series_id.equals(id)) {
                            if (!watchedSeries.contains(s)) {
                                watchedSeries.add(s);
                            }
                            break;
                        }
                    }
                }
                if (watchedSeries.isEmpty()) {
                    Toast.makeText(this, "Nenhuma série encontrada no histórico.", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Movie> converted = new ArrayList<>();
                for (Series s : watchedSeries) {
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
                vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 7));
                vodGridRecycler.setAdapter(new MoviePosterAdapter(this, converted, true, new MoviePosterAdapter.OnMovieActionListener() {
                    @Override
                    public void onMovieClick(Movie m) {
                        for (Series s : watchedSeries) {
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
                vodGridRecycler.requestFocus();
            });
        } else {
            ensureMoviesLoaded(() -> {
                List<String> recentIds = getRecentMovieIds();
                if (recentIds.isEmpty()) {
                    Toast.makeText(this, "Nenhum filme assistido recentemente.", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Movie> watchedMovies = new ArrayList<>();
                for (String id : recentIds) {
                    for (Movie m : cachedMovies) {
                        if (m.stream_id != null && m.stream_id.equals(id)) {
                            if (!watchedMovies.contains(m)) {
                                watchedMovies.add(m);
                            }
                            break;
                        }
                    }
                }
                if (watchedMovies.isEmpty()) {
                    Toast.makeText(this, "Nenhum filme encontrado no histórico.", Toast.LENGTH_SHORT).show();
                    return;
                }
                vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 7));
                vodGridRecycler.setAdapter(new MoviePosterAdapter(this, watchedMovies, true, new MoviePosterAdapter.OnMovieActionListener() {
                    @Override
                    public void onMovieClick(Movie movie) {
                        playMovie(movie);
                    }

                    @Override
                    public void onMovieFocus(Movie movie) {
                        updateVodHero(movie);
                    }
                }));
                if (!watchedMovies.isEmpty()) {
                    updateVodHero(watchedMovies.get(0));
                }
                vodGridRecycler.requestFocus();
            });
        }
    }

    private void onPlaybackEnded() {
        if (isPlayingVod) {
            if (activeVodSeries != null && activeVodEpisode != null) {
                String key = activeVodEpisode.id != null ? "episode_" + activeVodEpisode.id :
                        "series_" + (activeVodSeries.series_id != null ? activeVodSeries.series_id : "") + "_s" + activeVodSeasonNum + "_e" + activeVodEpisode.episode_num;
                clearVodProgress(key);
                playNextSeriesEpisode();
            } else if (activeVodMovie != null) {
                clearVodProgress(getMovieProgressKey(activeVodMovie));
                if (activeMoviePlaylistQueue != null && !activeMoviePlaylistQueue.isEmpty()) {
                    if (activeMoviePlaylistIndex + 1 < activeMoviePlaylistQueue.size()) {
                        activeMoviePlaylistIndex++;
                        Movie nextMovie = activeMoviePlaylistQueue.get(activeMoviePlaylistIndex);
                        Toast.makeText(this, "▶️ Próximo filme: " + nextMovie.getDisplayTitle(), Toast.LENGTH_SHORT).show();
                        startMoviePlayback(nextMovie, 0);
                    } else {
                        Toast.makeText(this, "🏁 Fim da playlist '" + (activeMoviePlaylistName != null ? activeMoviePlaylistName : "") + "'!", Toast.LENGTH_SHORT).show();
                        activeMoviePlaylistQueue = null;
                        activeMoviePlaylistIndex = -1;
                        activeMoviePlaylistName = null;
                        setScreenMode(ScreenMode.CENTRAL);
                    }
                }
            }
        }
    }

    private void playNextSeriesEpisode() {
        if (activeVodSeries == null || activeVodEpisode == null) return;

        if (currentSeriesEpisodesMap == null || currentSeriesEpisodesMap.isEmpty()) {
            showLoading("Carregando próximo episódio...");
            executor.execute(() -> {
                try {
                    Map<String, List<Episode>> epMap = ApiClient.getSeriesEpisodes(activeVodSeries.series_id);
                    mainHandler.post(() -> {
                        hideLoading();
                        currentSeriesEpisodesMap = epMap;
                        proceedToNextSeriesEpisode();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        hideLoading();
                        Toast.makeText(this, "Erro ao carregar próximo episódio.", Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } else {
            proceedToNextSeriesEpisode();
        }
    }

    private void proceedToNextSeriesEpisode() {
        if (activeVodSeries == null || activeVodEpisode == null || currentSeriesEpisodesMap == null) return;

        List<Episode> currentSeasonEps = currentSeriesEpisodesMap.get(activeVodSeasonNum);
        int currentEpIdx = -1;
        if (currentSeasonEps != null) {
            for (int i = 0; i < currentSeasonEps.size(); i++) {
                Episode ep = currentSeasonEps.get(i);
                if (ep.id != null && ep.id.equals(activeVodEpisode.id)) {
                    currentEpIdx = i;
                    break;
                }
            }
            if (currentEpIdx < 0) {
                for (int i = 0; i < currentSeasonEps.size(); i++) {
                    Episode ep = currentSeasonEps.get(i);
                    if (ep.episode_num == activeVodEpisode.episode_num) {
                        currentEpIdx = i;
                        break;
                    }
                }
            }
        }

        // 1. Próximo episódio da mesma temporada
        if (currentSeasonEps != null && currentEpIdx >= 0 && currentEpIdx + 1 < currentSeasonEps.size()) {
            Episode nextEp = currentSeasonEps.get(currentEpIdx + 1);
            Toast.makeText(this, "▶️ Próximo episódio: " + nextEp.getDisplayTitle(), Toast.LENGTH_SHORT).show();
            startSeriesEpisodePlayback(activeVodSeries, nextEp, activeVodSeasonNum, 0);
            return;
        }

        // 2. Primeira episódio da próxima temporada
        List<String> seasonKeys = new ArrayList<>(currentSeriesEpisodesMap.keySet());
        seasonKeys.sort((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (Exception e) { return a.compareTo(b); }
        });

        int currentSeasonIdx = seasonKeys.indexOf(activeVodSeasonNum);
        if (currentSeasonIdx >= 0 && currentSeasonIdx + 1 < seasonKeys.size()) {
            String nextSeasonNum = seasonKeys.get(currentSeasonIdx + 1);
            List<Episode> nextSeasonEps = currentSeriesEpisodesMap.get(nextSeasonNum);
            if (nextSeasonEps != null && !nextSeasonEps.isEmpty()) {
                Episode nextEp = nextSeasonEps.get(0);
                Toast.makeText(this, "▶️ Nova Temporada (" + nextSeasonNum + "): " + nextEp.getDisplayTitle(), Toast.LENGTH_SHORT).show();
                startSeriesEpisodePlayback(activeVodSeries, nextEp, nextSeasonNum, 0);
                return;
            }
        }

        // 3. Fim da série
        Toast.makeText(this, "🏁 Parabéns! Você concluiu todos os episódios desta série!", Toast.LENGTH_LONG).show();
        setScreenMode(ScreenMode.CENTRAL);
    }

    private static final String PREF_MOVIE_PLAYLISTS = "andplay_movie_playlists";

    private List<MoviePlaylist> loadMoviePlaylists() {
        List<MoviePlaylist> list = new ArrayList<>();
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_MOVIE_PLAYLISTS, Context.MODE_PRIVATE);
            String raw = prefs.getString("playlists", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                MoviePlaylist pl = new MoviePlaylist();
                pl.id = obj.optString("id", java.util.UUID.randomUUID().toString());
                pl.name = obj.optString("name", "Playlist " + (i + 1));
                JSONArray mArr = obj.optJSONArray("movieIds");
                if (mArr != null) {
                    for (int j = 0; j < mArr.length(); j++) {
                        pl.movieIds.add(mArr.getString(j));
                    }
                }
                list.add(pl);
            }
        } catch (Exception e) {
            Log.e("EPlay", "Erro ao carregar playlists: " + e.getMessage());
        }
        return list;
    }

    private void saveMoviePlaylists(List<MoviePlaylist> list) {
        try {
            JSONArray arr = new JSONArray();
            if (list != null) {
                for (MoviePlaylist pl : list) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", pl.id);
                    obj.put("name", pl.name);
                    JSONArray mArr = new JSONArray();
                    for (String mid : pl.movieIds) {
                        mArr.put(mid);
                    }
                    obj.put("movieIds", mArr);
                    arr.put(obj);
                }
            }
            SharedPreferences prefs = getSharedPreferences(PREF_MOVIE_PLAYLISTS, Context.MODE_PRIVATE);
            prefs.edit().putString("playlists", arr.toString()).apply();
        } catch (Exception e) {
            Log.e("EPlay", "Erro ao salvar playlists: " + e.getMessage());
        }
    }

    private List<Movie> getPlaylistMovies(MoviePlaylist pl) {
        List<Movie> res = new ArrayList<>();
        if (pl == null || pl.movieIds == null || cachedMovies == null) return res;
        for (String id : pl.movieIds) {
            for (Movie m : cachedMovies) {
                if (m.stream_id != null && m.stream_id.equals(id)) {
                    res.add(m);
                    break;
                }
            }
        }
        return res;
    }

    private void showMoviePlaylistsDialog() {
        ensureMoviesLoaded(() -> {
            List<MoviePlaylist> playlists = loadMoviePlaylists();
            List<String> items = new ArrayList<>();
            items.add("➕ NOVA PLAYLIST");
            for (MoviePlaylist pl : playlists) {
                items.add("📋 " + pl.name + " (" + pl.movieIds.size() + " filmes)");
            }
            AlertDialog dialog = createThemedDialogBuilder()
                    .setTitle("🎬 Playlists de Filmes")
                    .setItems(items.toArray(new String[0]), (d, which) -> {
                        d.dismiss();
                        if (which == 0) {
                            showCreatePlaylistNameDialog();
                        } else {
                            MoviePlaylist selected = playlists.get(which - 1);
                            showPlaylistActionsDialog(selected);
                        }
                    })
                    .setNegativeButton("Fechar", null)
                    .create();
            styleDialogButtons(dialog);
            dialog.show();
        });
    }

    private void showCreatePlaylistNameDialog() {
        AlertDialog dialog = createThemedDialogBuilder()
                .setTitle("➕ Nova Playlist")
                .setMessage("Digite o nome da sua playlist de filmes:")
                .create();

        final EditText input = new EditText(this);
        input.setHint("Ex: Filmes de Ação, Fim de Semana...");
        input.setBackgroundResource(R.drawable.edit_text_dark_bg);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#7A899C"));
        input.setTextSize(14);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.topMargin = margin / 2;
        params.bottomMargin = margin / 2;
        input.setLayoutParams(params);
        container.addView(input);
        dialog.setView(container);

        dialog.setButton(DialogInterface.BUTTON_POSITIVE, "Continuar", (d, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                name = "Minha Playlist";
            }
            MoviePlaylist pl = new MoviePlaylist();
            pl.id = java.util.UUID.randomUUID().toString();
            pl.name = name;
            showPlaylistEditorDialog(pl, true);
        });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancelar", (d, which) -> d.dismiss());

        styleDialogButtons(dialog);
        dialog.show();
        input.requestFocus();
    }

    private static class PlaylistMovieSelectAdapter extends RecyclerView.Adapter<PlaylistMovieSelectAdapter.ViewHolder> {
        private final Context context;
        private final List<Movie> movies;
        private final MoviePlaylist playlist;
        private final View sidebarTarget;
        private final Runnable onSelectionChanged;

        public PlaylistMovieSelectAdapter(Context context, List<Movie> movies, MoviePlaylist playlist, View sidebarTarget, Runnable onSelectionChanged) {
            this.context = context;
            this.movies = movies;
            this.playlist = playlist;
            this.sidebarTarget = sidebarTarget;
            this.onSelectionChanged = onSelectionChanged;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(context).inflate(R.layout.item_playlist_movie_select, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Movie movie = movies.get(position);
            holder.tvNum.setText(String.valueOf(position + 1));
            holder.tvTitle.setText(movie.getDisplayTitle());
            String meta = "";
            if (movie.year != null && !movie.year.isEmpty()) meta += movie.year;
            if (movie.genre != null && !movie.genre.isEmpty()) {
                if (!meta.isEmpty()) meta += " • ";
                meta += movie.genre;
            }
            holder.tvMeta.setText(meta);

            boolean isAdded = playlist.movieIds.contains(movie.stream_id);
            updateBadge(holder, isAdded);

            holder.itemView.setOnClickListener(v -> {
                if (playlist.movieIds.contains(movie.stream_id)) {
                    playlist.movieIds.remove(movie.stream_id);
                    updateBadge(holder, false);
                } else {
                    playlist.movieIds.add(movie.stream_id);
                    updateBadge(holder, true);
                }
                if (onSelectionChanged != null) onSelectionChanged.run();
            });

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                holder.tvTitle.setTextColor(hasFocus ? Color.BLACK : Color.WHITE);
                holder.tvNum.setTextColor(hasFocus ? Color.BLACK : Color.parseColor("#8899AA"));
                holder.tvMeta.setTextColor(hasFocus ? Color.DKGRAY : Color.parseColor("#7A899C"));
            });

            // DPAD_RIGHT envia o foco diretamente para a barra lateral de ações!
            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (sidebarTarget != null) {
                        sidebarTarget.requestFocus();
                        return true;
                    }
                }
                return false;
            });
        }

        private void updateBadge(ViewHolder holder, boolean isAdded) {
            if (isAdded) {
                holder.tvBadge.setText("✓ NA FILA");
                holder.tvBadge.setTextColor(Color.parseColor("#FFC107"));
            } else {
                holder.tvBadge.setText("+ Adicionar");
                holder.tvBadge.setTextColor(Color.parseColor("#CCCCCC"));
            }
        }

        @Override
        public int getItemCount() {
            return movies.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvNum, tvTitle, tvMeta, tvBadge;
            ViewHolder(View itemView) {
                super(itemView);
                tvNum = itemView.findViewById(R.id.tvSelectMovieNumber);
                tvTitle = itemView.findViewById(R.id.tvSelectMovieTitle);
                tvMeta = itemView.findViewById(R.id.tvSelectMovieMeta);
                tvBadge = itemView.findViewById(R.id.tvSelectMovieBadge);
            }
        }
    }

    private void showPlaylistEditorDialog(MoviePlaylist playlist, boolean isNew) {
        if (cachedMovies == null || cachedMovies.isEmpty()) {
            Toast.makeText(this, "Nenhum filme carregado no catálogo.", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_playlist_editor, null);
        AlertDialog dialog = createThemedDialogBuilder()
                .setView(dialogView)
                .setCancelable(true)
                .create();

        TextView tvTitle = dialogView.findViewById(R.id.tvPlaylistEditorTitle);
        TextView tvCount = dialogView.findViewById(R.id.tvPlaylistEditorCount);
        TextView tvListHeader = dialogView.findViewById(R.id.tvPlaylistListHeader);
        EditText etSearch = dialogView.findViewById(R.id.etPlaylistSearch);
        TextView btnSearch = dialogView.findViewById(R.id.btnPlaylistSearchAction);
        RecyclerView rvMovies = dialogView.findViewById(R.id.rvPlaylistMovies);

        TextView btnSidebarNewSearch = dialogView.findViewById(R.id.btnSidebarNewSearch);
        TextView btnSidebarCatalog = dialogView.findViewById(R.id.btnSidebarCatalog);
        TextView btnSidebarViewQueue = dialogView.findViewById(R.id.btnSidebarViewQueue);
        TextView btnSidebarSave = dialogView.findViewById(R.id.btnSidebarSavePlaylist);
        TextView btnSidebarCancel = dialogView.findViewById(R.id.btnSidebarCancel);

        tvTitle.setText("📋 " + (isNew ? "Nova Playlist: " : "Editar: ") + playlist.name);

        Runnable updateCounters = () -> {
            int total = playlist.movieIds.size();
            tvCount.setText(total + " filmes na playlist");
            btnSidebarViewQueue.setText("👁️ Ver Fila (" + total + ")");
        };
        updateCounters.run();

        View.OnFocusChangeListener buttonFocus = (v, hasFocus) -> {
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(hasFocus ? Color.BLACK : Color.WHITE);
            }
        };
        btnSearch.setOnFocusChangeListener(buttonFocus);
        btnSidebarNewSearch.setOnFocusChangeListener(buttonFocus);
        btnSidebarCatalog.setOnFocusChangeListener(buttonFocus);
        btnSidebarViewQueue.setOnFocusChangeListener(buttonFocus);
        btnSidebarSave.setOnFocusChangeListener(buttonFocus);
        btnSidebarCancel.setOnFocusChangeListener(buttonFocus);

        final List<Movie> currentDisplayed = new ArrayList<>();
        rvMovies.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));

        Runnable refreshAdapter = () -> {
            PlaylistMovieSelectAdapter adapter = new PlaylistMovieSelectAdapter(
                    this,
                    currentDisplayed,
                    playlist,
                    btnSidebarSave,
                    updateCounters
            );
            rvMovies.setAdapter(adapter);
        };

        Runnable populateAll = () -> {
            currentDisplayed.clear();
            for (Movie m : cachedMovies) {
                if (!isDemoMovie(m)) currentDisplayed.add(m);
            }
            refreshAdapter.run();
        };
        populateAll.run();

        // Teclas no sidebar: DPAD_LEFT retorna para a lista de filmes
        View.OnKeyListener sidebarLeftListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (rvMovies.getChildCount() > 0) {
                    rvMovies.requestFocus();
                    return true;
                }
            }
            return false;
        };
        btnSidebarNewSearch.setOnKeyListener(sidebarLeftListener);
        btnSidebarCatalog.setOnKeyListener(sidebarLeftListener);
        btnSidebarViewQueue.setOnKeyListener(sidebarLeftListener);
        btnSidebarSave.setOnKeyListener(sidebarLeftListener);
        btnSidebarCancel.setOnKeyListener(sidebarLeftListener);

        Runnable doSearch = () -> {
            String q = etSearch.getText().toString().trim().toLowerCase();
            if (q.isEmpty()) {
                tvListHeader.setText("Todos os Filmes do Catálogo (" + cachedMovies.size() + "):");
                populateAll.run();
                return;
            }
            List<Movie> matched = new ArrayList<>();
            for (Movie m : cachedMovies) {
                if (isDemoMovie(m)) continue;
                String title = m.getDisplayTitle() != null ? m.getDisplayTitle().toLowerCase() : "";
                String genre = m.genre != null ? m.genre.toLowerCase() : "";
                if (title.contains(q) || genre.contains(q)) {
                    matched.add(m);
                }
            }
            currentDisplayed.clear();
            currentDisplayed.addAll(matched);
            refreshAdapter.run();
            tvListHeader.setText("Resultados para '" + q + "' (" + matched.size() + " encontrados):");
            if (matched.isEmpty()) {
                Toast.makeText(this, "Nenhum filme encontrado para '" + q + "'", Toast.LENGTH_SHORT).show();
            } else {
                rvMovies.requestFocus();
            }
        };

        btnSearch.setOnClickListener(v -> doSearch.run());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                doSearch.run();
                return true;
            }
            return false;
        });

        btnSidebarNewSearch.setOnClickListener(v -> {
            etSearch.requestFocus();
            etSearch.selectAll();
        });

        btnSidebarCatalog.setOnClickListener(v -> {
            etSearch.setText("");
            tvListHeader.setText("Todos os Filmes do Catálogo (" + cachedMovies.size() + "):");
            populateAll.run();
            rvMovies.requestFocus();
        });

        btnSidebarViewQueue.setOnClickListener(v -> {
            List<Movie> queueMovies = getPlaylistMovies(playlist);
            currentDisplayed.clear();
            currentDisplayed.addAll(queueMovies);
            refreshAdapter.run();
            tvListHeader.setText("Filmes na Fila da Playlist (" + queueMovies.size() + "):");
            if (queueMovies.isEmpty()) {
                Toast.makeText(this, "A playlist ainda não possui filmes adicionados.", Toast.LENGTH_SHORT).show();
            } else {
                rvMovies.requestFocus();
            }
        });

        btnSidebarSave.setOnClickListener(v -> {
            if (playlist.movieIds.isEmpty()) {
                Toast.makeText(this, "Selecione pelo menos 1 filme para salvar a playlist!", Toast.LENGTH_SHORT).show();
                return;
            }
            List<MoviePlaylist> all = loadMoviePlaylists();
            boolean found = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id != null && all.get(i).id.equals(playlist.id)) {
                    all.set(i, playlist);
                    found = true;
                    break;
                }
            }
            if (!found) {
                all.add(playlist);
            }
            saveMoviePlaylists(all);
            Toast.makeText(this, "Playlist '" + playlist.name + "' salva com sucesso! (" + playlist.movieIds.size() + " filmes)", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            showMoviePlaylistsDialog();
        });

        btnSidebarCancel.setOnClickListener(v -> {
            dialog.dismiss();
            if (!isNew) {
                showMoviePlaylistsDialog();
            }
        });

        dialog.show();
        etSearch.requestFocus();
    }

    private void showPlaylistActionsDialog(MoviePlaylist playlist) {
        String[] actions = new String[] {
                "▶️ INICIAR REPRODUÇÃO EM SEQUÊNCIA",
                "📺 EXIBIR FILMES NA GRADE",
                "✏️ ADICIONAR / EDITAR FILMES",
                "🏷️ RENOMEAR PLAYLIST",
                "🗑️ EXCLUIR PLAYLIST"
        };
        AlertDialog dialog = createThemedDialogBuilder()
                .setTitle("📋 " + playlist.name + " (" + playlist.movieIds.size() + " filmes)")
                .setItems(actions, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        startMoviePlaylist(playlist, 0);
                    } else if (which == 1) {
                        displayPlaylistInGrid(playlist);
                    } else if (which == 2) {
                        showPlaylistEditorDialog(playlist, false);
                    } else if (which == 3) {
                        showRenamePlaylistDialog(playlist);
                    } else if (which == 4) {
                        confirmDeletePlaylist(playlist);
                    }
                })
                .setNegativeButton("Voltar", (d, w) -> showMoviePlaylistsDialog())
                .create();
        styleDialogButtons(dialog);
        dialog.show();
    }

    private void showRenamePlaylistDialog(MoviePlaylist playlist) {
        AlertDialog dialog = createThemedDialogBuilder()
                .setTitle("🏷️ Renomear Playlist")
                .setMessage("Digite o novo nome:")
                .create();

        final EditText input = new EditText(this);
        input.setText(playlist.name);
        input.setBackgroundResource(R.drawable.edit_text_dark_bg);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#7A899C"));
        input.setTextSize(14);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.topMargin = margin / 2;
        params.bottomMargin = margin / 2;
        input.setLayoutParams(params);
        container.addView(input);
        dialog.setView(container);

        dialog.setButton(DialogInterface.BUTTON_POSITIVE, "Salvar", (d, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                playlist.name = newName;
                List<MoviePlaylist> all = loadMoviePlaylists();
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(playlist.id)) {
                        all.set(i, playlist);
                        break;
                    }
                }
                saveMoviePlaylists(all);
                Toast.makeText(this, "Playlist renomeada para: " + newName, Toast.LENGTH_SHORT).show();
            }
            showPlaylistActionsDialog(playlist);
        });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancelar", (d, which) -> showPlaylistActionsDialog(playlist));

        styleDialogButtons(dialog);
        dialog.show();
        input.requestFocus();
    }

    private void confirmDeletePlaylist(MoviePlaylist playlist) {
        AlertDialog dialog = createThemedDialogBuilder()
                .setTitle("🗑️ Excluir Playlist")
                .setMessage("Deseja realmente excluir a playlist '" + playlist.name + "'?")
                .setPositiveButton("Excluir", (d, which) -> {
                    List<MoviePlaylist> all = loadMoviePlaylists();
                    all.removeIf(pl -> pl.id != null && pl.id.equals(playlist.id));
                    saveMoviePlaylists(all);
                    Toast.makeText(this, "Playlist excluída!", Toast.LENGTH_SHORT).show();
                    showMoviePlaylistsDialog();
                })
                .setNegativeButton("Cancelar", (d, which) -> showPlaylistActionsDialog(playlist))
                .create();
        styleDialogButtons(dialog);
        dialog.show();
    }

    private void displayPlaylistInGrid(MoviePlaylist playlist) {
        List<Movie> pMovies = getPlaylistMovies(playlist);
        if (pMovies.isEmpty()) {
            Toast.makeText(this, "Playlist vazia ou filmes indisponíveis.", Toast.LENGTH_SHORT).show();
            return;
        }
        vodGridRecycler.setLayoutManager(new GridLayoutManager(this, 7));
        vodGridRecycler.setAdapter(new MoviePosterAdapter(this, pMovies, true, new MoviePosterAdapter.OnMovieActionListener() {
            @Override
            public void onMovieClick(Movie movie) {
                int idx = pMovies.indexOf(movie);
                startMoviePlaylist(playlist, Math.max(0, idx));
            }

            @Override
            public void onMovieFocus(Movie movie) {
                updateVodHero(movie);
            }
        }));
        updateVodHero(pMovies.get(0));
        vodGridRecycler.requestFocus();
    }

    public void startMoviePlaylist(MoviePlaylist playlist, int startIndex) {
        if (playlist == null || playlist.movieIds == null || playlist.movieIds.isEmpty()) return;
        List<Movie> movies = getPlaylistMovies(playlist);
        if (movies.isEmpty()) {
            Toast.makeText(this, "Nenhum filme disponível nesta playlist.", Toast.LENGTH_SHORT).show();
            return;
        }
        activeMoviePlaylistQueue = movies;
        activeMoviePlaylistIndex = Math.max(0, Math.min(startIndex, movies.size() - 1));
        activeMoviePlaylistName = playlist.name;
        startMoviePlayback(movies.get(activeMoviePlaylistIndex), 0);
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

        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && action == KeyEvent.ACTION_UP) {
            if (suppressNextEnterUp) {
                suppressNextEnterUp = false;
                return true;
            }
        }

        // 0. MODO MOSAICO: Prioridade absoluta para D-Pad, Enter e Back quando gaveta fechada
        if (isMosaicActive) {
            if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
                // Gaveta aberta sobre o mosaico: deixa seguir para o tratamento de gaveta abaixo
            } else {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                    return super.dispatchKeyEvent(event);
                }
                if (action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        moveMosaicFocus(-1, 0);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        moveMosaicFocus(1, 0);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        moveMosaicFocus(0, -1);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        moveMosaicFocus(0, 1);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        onMosaicSlotClicked(currentMosaicFocusedIdx);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        return true;
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        handleBack();
                        return true;
                    }
                }
                return true; // Consome qualquer outra tecla no Mosaico para NENHUMA tecla vazar para WebViews
            }
        }

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
            } else if (keyCode == KeyEvent.KEYCODE_GUIDE) {
                toggleFullGuide();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_INFO) {
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

            // CENÁRIO 0: GUIA DE PROGRAMAÇÃO COMPLETO EM TELA CHEIA
            if (fullGuideLayout != null && fullGuideLayout.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    guideSwitchChannel(-1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    guideSwitchChannel(1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    guideTuneSelectedChannel();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                    closeFullGuide();
                    return true;
                }
                // LEFT e RIGHT navegam normalmente pela timeline horizontal do guia
                // Nunca propaga para os outros cenários (evita abrir gaveta lateral)
                return super.dispatchKeyEvent(event);
            }

            // CENÁRIO 0.5: OVERLAY DE TABELA / RODADA ESPORTIVA (TELA CHEIA)
            // Fecha SOMENTE com BACK. Nenhum DPAD fecha o overlay.
            if (standingsOverlayVisible && sportsStandingsOverlay != null && sportsStandingsOverlay.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    hideSportsOverlay();
                    return true;
                }
                // UP/DOWN/LEFT/RIGHT → scrolla o conteúdo ativo
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    RecyclerView activeRecycler = "round".equals(standingsActiveTab) ? roundMatchesRecycler : standingsRecycler;
                    if (activeRecycler != null) {
                        int vert = 0, horiz = 0;
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) vert = 160;
                        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) vert = -160;
                        activeRecycler.smoothScrollBy(horiz, vert);
                    }
                    return true;
                }
                // OK/ENTER → alterna entre Tabela e Rodada
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if ("standings".equals(standingsActiveTab)) {
                        switchStandingsTab("round");
                        if (tabRound != null) tabRound.requestFocus();
                    } else {
                        switchStandingsTab("standings");
                        if (tabStandings != null) tabStandings.requestFocus();
                    }
                    return true;
                }
                // Consome todas as outras teclas — isolamento total, nada vaza para o player/drawer
                return true;
            }

            // CENÁRIO 1: GAVETA LATERAL ESTÁ ABERTA (qualquer modo de tela)
            if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
                resetDrawerTimeout();

                // 1. Se o foco estiver no botão de OPÇÕES ou no botão de MOSAICO:
                boolean isOptionsFocused = (btnDrawerOptions != null && btnDrawerOptions.hasFocus());
                boolean isMosaicFocused = (btnDrawerMosaic != null && btnDrawerMosaic.hasFocus());

                if (isOptionsFocused || isMosaicFocused) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (isOptionsFocused && btnDrawerMosaic != null) {
                            btnDrawerMosaic.requestFocus();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (isMosaicFocused && btnDrawerOptions != null) {
                            btnDrawerOptions.requestFocus();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (drawerCatsRecycler != null && drawerCatsRecycler.getChildCount() > 0) {
                            View child = drawerCatsRecycler.getLayoutManager() != null
                                    ? drawerCatsRecycler.getLayoutManager().findViewByPosition(selectedDrawerCatIdx)
                                    : null;
                            if (child != null) child.requestFocus();
                            else drawerCatsRecycler.getChildAt(0).requestFocus();
                            return true;
                        } else if (drawerChannelsRecycler != null && drawerChannelsRecycler.getChildCount() > 0) {
                            drawerChannelsRecycler.getChildAt(0).requestFocus();
                            return true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }

                // 2. Se o foco estiver no seletor de categorias da gaveta:
                if (drawerCatsRecycler != null && drawerCatsRecycler.hasFocus()) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (!isPlayingVod && btnDrawerOptions != null && btnDrawerOptions.getVisibility() == View.VISIBLE) {
                            btnDrawerOptions.requestFocus();
                            return true;
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (drawerChannelsRecycler != null && drawerChannelsRecycler.getChildCount() > 0) {
                            drawerChannelsRecycler.getChildAt(0).requestFocus();
                            return true;
                        }
                    }
                    return super.dispatchKeyEvent(event);
                }

                // 3. Se o foco estiver na lista/grade de canais da gaveta:
                if (drawerChannelsRecycler != null && drawerChannelsRecycler.hasFocus()) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        View focused = drawerChannelsRecycler.findFocus();
                        View itemView = focused != null ? drawerChannelsRecycler.findContainingItemView(focused) : null;
                        int pos = itemView != null ? drawerChannelsRecycler.getChildAdapterPosition(itemView) : RecyclerView.NO_POSITION;
                        RecyclerView.LayoutManager lm = drawerChannelsRecycler.getLayoutManager();
                        int spanCount = (lm instanceof GridLayoutManager) ? ((GridLayoutManager) lm).getSpanCount() : 1;
                        if (pos >= 0 && pos < spanCount) {
                            // Subindo além da primeira linha de canais, sobe para as categorias
                            if (drawerCatsRecycler != null && drawerCatsRecycler.getChildCount() > 0) {
                                View child = drawerCatsRecycler.getLayoutManager() != null
                                        ? drawerCatsRecycler.getLayoutManager().findViewByPosition(selectedDrawerCatIdx)
                                        : null;
                                if (child != null) child.requestFocus();
                                else drawerCatsRecycler.getChildAt(0).requestFocus();
                            } else if (!isPlayingVod && btnDrawerOptions != null && btnDrawerOptions.getVisibility() == View.VISIBLE) {
                                btnDrawerOptions.requestFocus();
                            }
                            return true;
                        }
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        RecyclerView.LayoutManager lm = drawerChannelsRecycler.getLayoutManager();
                        if (lm instanceof GridLayoutManager) {
                            GridLayoutManager glm = (GridLayoutManager) lm;
                            int spanCount = glm.getSpanCount();
                            View focused = drawerChannelsRecycler.findFocus();
                            View itemView = focused != null ? drawerChannelsRecycler.findContainingItemView(focused) : null;
                            int pos = itemView != null ? drawerChannelsRecycler.getChildAdapterPosition(itemView) : RecyclerView.NO_POSITION;
                            if (pos != RecyclerView.NO_POSITION) {
                                int col = pos % spanCount;
                                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                    if (col > 0) {
                                        return super.dispatchKeyEvent(event);
                                    } else {
                                        switchDrawerCategory(-1);
                                        return true;
                                    }
                                } else {
                                    int itemCount = drawerChannelsRecycler.getAdapter() != null ? drawerChannelsRecycler.getAdapter().getItemCount() : 0;
                                    if (col < spanCount - 1 && pos + 1 < itemCount) {
                                        return super.dispatchKeyEvent(event);
                                    } else {
                                        switchDrawerCategory(1);
                                        return true;
                                    }
                                }
                            }
                        }

                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            switchDrawerCategory(-1);
                            return true;
                        } else {
                            switchDrawerCategory(1);
                            return true;
                        }
                    }
                }

                return super.dispatchKeyEvent(event);
            } else if (currentMode == ScreenMode.SERIES_DETAIL) {
                // CENÁRIO 2: DETALHES DA SÉRIE
                // DPAD esquerda/direita só alterna temporada se o foco NÃO estiver no seletor de temporadas!
                // Se o foco estiver nas pills de temporadas, permite navegar e selecionar normalmente.
                boolean isFocusOnSeasons = (seriesSeasonsRecycler != null && seriesSeasonsRecycler.hasFocus());
                if (!isFocusOnSeasons) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        switchSeriesSeason(-1);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        switchSeriesSeason(1);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (seriesEpisodesRecycler != null && seriesEpisodesRecycler.hasFocus()) {
                            View focused = seriesEpisodesRecycler.findFocus();
                            View itemView = focused != null ? seriesEpisodesRecycler.findContainingItemView(focused) : null;
                            int pos = itemView != null ? seriesEpisodesRecycler.getChildAdapterPosition(itemView) : -1;
                            if (pos == 0) {
                                focusCurrentSeasonPill();
                                return true;
                            }
                        }
                    }
                }
            } else if (currentMode == ScreenMode.FULLSCREEN) {
                // CENÁRIO 3: GAVETA LATERAL ESTÁ FECHADA EM TELA CHEIA
                if (!isPlayingVod) {
                        // Entrada direta de canal por número (ex: 1 -> 001, 10 -> 010) com debounce de 2s
                        boolean isDigit = (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
                                || (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9);
                        if (isDigit) {
                            int digit = (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
                                    ? (keyCode - KeyEvent.KEYCODE_0)
                                    : (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
                            handleDirectChannelDigit(digit);
                            return true;
                        }
                        if (channelNumberBuffer.length() > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                            channelNumberHandler.removeCallbacks(channelNumberCommitRunnable);
                            commitChannelNumberInput();
                            return true;
                        }

                        // D-pad Esquerdo abre a gaveta lateral
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                                // Dentro do overlay de informações, DPAD_LEFT não aciona o menu gaveta
                                scheduleOsdHide(getOsdTimeoutMs());
                                return true;
                            }
                            if (pendingZapChannelIdx >= 0) {
                                cancelPendingZap();
                            }
                            openDrawer();
                            return true;
                        }

                        // D-pad Direito: abre overlay de tabela + rodada
                        // Funciona tanto ao entrar via playSportsEvent quanto ao navegar num
                        // canal normal que está transmitindo um evento ao vivo com match EPG
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (activeSportsEvent == null && currentChannelIdx >= 0 && currentChannelIdx < allChannels.size()) {
                                activeSportsEvent = detectSportsEventForChannel(allChannels.get(currentChannelIdx));
                            }
                            if (activeSportsEvent != null) {
                                if (epgDrawer == null || epgDrawer.getVisibility() != View.VISIBLE) {
                                    showSportsOverlay();
                                    return true;
                                }
                            }
                        }

                        // D-pad Cima (+) alterna canais em ordem crescente (+1) com confirmação após 3s
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            if (isPlayingSportsEvent) {
                                stepSportsFallback(1);
                            } else {
                                stepZapChannel(1);
                            }
                            return true;
                        }

                        // D-pad Baixo (-) alterna canais em ordem decrescente (-1) com confirmação após 3s
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (isPlayingSportsEvent) {
                                stepSportsFallback(-1);
                            } else {
                                stepZapChannel(-1);
                            }
                            return true;
                        }


                        // ENTER / OK confirma a troca imediata se estiver zapeando, abre a gaveta se o OSD já estiver visível, ou exibe o OSD se oculto
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (pendingZapChannelIdx >= 0) {
                                confirmPendingZapChannel();
                                return true;
                            } else if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                                suppressNextEnterUp = true;
                                openDrawer();
                                return true;
                            } else {
                                showOsdBanner(getOsdTimeoutMs());
                                return true;
                            }
                        }

                        // Prolonga o OSD se estiver visível e reprodução ativa
                        if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                            if (isVideoPlaybackActive) {
                                scheduleOsdHide(getOsdTimeoutMs());
                            }
                        }
                    } else {
                        // Em VOD (Filme ou Série):
                        // D-pad Esquerdo abre a gaveta lateral
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
                        // ENTER / OK: se overlay estiver visível, alterna play/pause; senão exibe o OSD
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (osdBanner != null && osdBanner.getVisibility() == View.VISIBLE) {
                                if (exoPlayer != null) {
                                    if (exoPlayer.isPlaying()) {
                                        exoPlayer.pause();
                                    } else {
                                        exoPlayer.play();
                                    }
                                    updateVodProgress();
                                    showOsdBanner(5000);
                                }
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
                } else if (currentMode == ScreenMode.CENTRAL) {
                    // CENÁRIO 4: TELA INICIAL (CENTRAL) - BARREIRAS LATERAIS
                    if (checkCentralHorizontalBarriers(keyCode)) {
                        return true;
                    }
                }
            }

        // Botão Voltar (Back) com pilha inteligente
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP) {
            if (handleBack()) return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean checkCentralHorizontalBarriers(int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false;
        }

        // 1. Miniplayer (pipContainer): barreira lateral esquerda
        if (pipContainer != null && pipContainer.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return true; // Esbarra na parede esquerda
            }
            return false;
        }

        // 2. Cards laterais superiores (btnNavSeries e btnNavEpg): barreira lateral direita
        if ((btnNavSeries != null && btnNavSeries.hasFocus()) || (btnNavEpg != null && btnNavEpg.hasFocus())) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true; // Esbarra na parede direita
            }
            return false;
        }

        // 3. Trilhos horizontais (Canais, Jogos/Eventos, Filmes, Séries): barreiras esquerda e direita
        RecyclerView[] rails = new RecyclerView[] { channelsRail, sportsRail, moviesRail, seriesRail };
        for (RecyclerView rail : rails) {
            if (rail != null && rail.hasFocus()) {
                View focused = rail.findFocus();
                View itemView = focused != null ? rail.findContainingItemView(focused) : null;
                int pos = itemView != null ? rail.getChildAdapterPosition(itemView) : -1;
                if (pos == RecyclerView.NO_POSITION) return false;

                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                    return true; // Barreira à esquerda: esbarra na parede
                }
                RecyclerView.Adapter<?> adapter = rail.getAdapter();
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && adapter != null && pos == adapter.getItemCount() - 1) {
                    return true; // Barreira à direita: esbarra na parede
                }
                return false;
            }
        }

        return false;
    }

    private boolean handleBack() {
        if (activeSearchDialog != null && activeSearchDialog.isShowing()) {
            activeSearchDialog.dismiss();
            activeSearchDialog = null;
            return true;
        }

        if (fullGuideLayout != null && fullGuideLayout.getVisibility() == View.VISIBLE) {
            closeFullGuide();
            return true;
        }

        if (standingsOverlayVisible) {
            hideSportsOverlay();
            return true;
        }

        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return true;
        }

        if (isMosaicActive) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastMosaicBackAt < 3000) {
                lastMosaicBackAt = 0;
                AlertDialog dialog = createThemedDialogBuilder()
                        .setTitle("Sair do Mosaico")
                        .setMessage("Deseja realmente sair do modo Mosaico e voltar à exibição padrão?")
                        .setPositiveButton("Sim, Sair", (d, which) -> {
                            d.dismiss();
                            exitMosaicMode(true);
                        })
                        .setNegativeButton("Continuar no Mosaico", (d, which) -> d.dismiss())
                        .create();
                styleDialogButtons(dialog);
                dialog.show();
            } else {
                lastMosaicBackAt = now;
                Toast.makeText(this, "Pressione Voltar novamente para sair do Mosaico", Toast.LENGTH_SHORT).show();
            }
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
            if (isPlayingSportsEvent) {
                zapHandler.removeCallbacks(zapConfirmRunnable);
                isPlayingSportsEvent = false;
                activeSportsEvent = null;
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

        // Raiz do app (Central): exige 2 toques rápidos para abrir confirmação de saída
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAt < 2500) {
            lastBackAt = 0;
            showExitConfirmDialog();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Pressione Voltar novamente para sair", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void showExitConfirmDialog() {
        AlertDialog dialog = createThemedDialogBuilder()
                .setTitle("🚪 Sair do EPlay")
                .setMessage("Deseja realmente fechar o aplicativo EPlay?")
                .setPositiveButton("Sim, Sair", (d, which) -> {
                    d.dismiss();
                    finish();
                })
                .setNegativeButton("Cancelar", (d, which) -> {
                    d.dismiss();
                })
                .create();
        styleDialogButtons(dialog);
        dialog.show();
    }

    public void openFullGuide() {
        if (epgDrawer != null && epgDrawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
        }
        hideOsdBanner();
        if (fullGuideLayout != null) {
            previousGuideMode = currentMode;
            if (currentMode == ScreenMode.CENTRAL) {
                setScreenMode(ScreenMode.FULLSCREEN);
            }
            fullGuideLayout.setVisibility(View.VISIBLE);
            guideSelectedChannelIdx = (currentChannelIdx >= 0 && currentChannelIdx < allChannels.size()) ? currentChannelIdx : 0;
            if (guideClock != null) {
                SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());
                guideClock.setText(tf.format(new Date()));
            }
            populateGuideForCurrentChannel();
        }
    }

    public void closeFullGuide() {
        if (fullGuideLayout != null) {
            fullGuideLayout.setVisibility(View.GONE);
            if (previousGuideMode == ScreenMode.CENTRAL) {
                previousGuideMode = null;
                setScreenMode(ScreenMode.CENTRAL);
            } else if (currentMode == ScreenMode.FULLSCREEN) {
                showOsdBanner(3000);
            }
        }
    }

    public void toggleFullGuide() {
        if (fullGuideLayout != null && fullGuideLayout.getVisibility() == View.VISIBLE) {
            closeFullGuide();
        } else {
            openFullGuide();
        }
    }

    private void guideSwitchChannel(int delta) {
        if (allChannels.isEmpty()) return;
        guideSelectedChannelIdx = (guideSelectedChannelIdx + delta + allChannels.size()) % allChannels.size();
        populateGuideForCurrentChannel();
    }

    private void guideTuneSelectedChannel() {
        int targetIdx = guideSelectedChannelIdx;
        previousGuideMode = null;
        closeFullGuide();
        if (targetIdx == currentChannelIdx && (currentActiveStreamUrl != null && !currentActiveStreamUrl.isEmpty())) {
            setScreenMode(ScreenMode.FULLSCREEN);
            return;
        }
        destroyCurrentStream();
        tuneChannel(targetIdx, true);
        setScreenMode(ScreenMode.FULLSCREEN);
    }

    private void populateGuideForCurrentChannel() {
        if (allChannels.isEmpty() || guideSelectedChannelIdx < 0 || guideSelectedChannelIdx >= allChannels.size()) return;
        Channel ch = allChannels.get(guideSelectedChannelIdx);

        if (guideChannelTitle != null) {
            guideChannelTitle.setText(String.format(Locale.getDefault(), "%03d • %s", guideSelectedChannelIdx + 1, ch.name));
        }
        if (guidePrevChannelHint != null) {
            int prev = (guideSelectedChannelIdx - 1 + allChannels.size()) % allChannels.size();
            guidePrevChannelHint.setText(String.format(Locale.getDefault(), "▲ %03d. %s", prev + 1, allChannels.get(prev).name));
        }
        if (guideNextChannelHint != null) {
            int next = (guideSelectedChannelIdx + 1) % allChannels.size();
            guideNextChannelHint.setText(String.format(Locale.getDefault(), "▼ %03d. %s", next + 1, allChannels.get(next).name));
        }

        guideCurrentTimeline = EpgEngine.getChannelTimeline(ch);
        int nowIdx = 0;
        for (int i = 0; i < guideCurrentTimeline.size(); i++) {
            if (guideCurrentTimeline.get(i).isCurrent) {
                nowIdx = i;
                break;
            }
        }

        if (!guideCurrentTimeline.isEmpty()) {
            updateGuideHero(guideCurrentTimeline.get(nowIdx), ch, guideSelectedChannelIdx);
        }

        guideTimelineAdapter = new TimelineAdapter(this, guideCurrentTimeline, true, new TimelineAdapter.OnTimelineActionListener() {
            @Override
            public void onProgramClick(TimelineProgram program) {
                guideTuneSelectedChannel();
            }

            @Override
            public void onProgramFocus(TimelineProgram program, int position) {
                updateGuideHero(program, ch, guideSelectedChannelIdx);
            }
        });
        guideTimelineRecycler.setAdapter(guideTimelineAdapter);

        final int focusSlot = nowIdx;
        guideTimelineRecycler.scrollToPosition(focusSlot);
        guideTimelineRecycler.postDelayed(() -> {
            RecyclerView.ViewHolder vh = guideTimelineRecycler.findViewHolderForAdapterPosition(focusSlot);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else if (guideTimelineRecycler.getChildCount() > 0) {
                View first = guideTimelineRecycler.getChildAt(0);
                if (first != null) first.requestFocus();
            }
        }, 80);

        // LINHA INFERIOR: Preview do próximo canal (somente visível, não selecionável)
        int nextChIdx = (guideSelectedChannelIdx + 1) % allChannels.size();
        Channel nextCh = allChannels.get(nextChIdx);
        if (guideNextChannelPreviewLabel != null) {
            guideNextChannelPreviewLabel.setText(String.format(Locale.getDefault(), "▼ A SEGUIR NO PRÓXIMO CANAL: %03d • %s", nextChIdx + 1, nextCh.name));
        }
        if (guideNextTimelineRecycler != null) {
            List<TimelineProgram> nextTimeline = EpgEngine.getChannelTimeline(nextCh);
            guideNextTimelineRecycler.setAdapter(new TimelineAdapter(this, nextTimeline, false, null));
            int nextNowIdx = 0;
            for (int i = 0; i < nextTimeline.size(); i++) {
                if (nextTimeline.get(i).isCurrent) {
                    nextNowIdx = i;
                    break;
                }
            }
            guideNextTimelineRecycler.scrollToPosition(nextNowIdx);
        }
    }

    private void updateGuideHero(TimelineProgram prog, Channel ch, int chIdx) {
        if (prog == null) return;
        if (guideHeroChannelBadge != null) {
            guideHeroChannelBadge.setText(String.format(Locale.getDefault(), "%03d • %s", chIdx + 1, ch != null ? ch.name : ""));
        }
        if (guideHeroStatusBadge != null) {
            if (prog.isCurrent) {
                guideHeroStatusBadge.setText("🔴 NO AR");
                guideHeroStatusBadge.setBackgroundResource(R.drawable.badge_gold);
                guideHeroStatusBadge.setTextColor(Color.BLACK);
            } else if (prog.isPast) {
                guideHeroStatusBadge.setText("⏪ EXIBIDO");
                guideHeroStatusBadge.setBackgroundColor(Color.parseColor("#333A48"));
                guideHeroStatusBadge.setTextColor(Color.parseColor("#99A3B0"));
            } else {
                guideHeroStatusBadge.setText("⏱️ EM BREVE");
                guideHeroStatusBadge.setBackgroundColor(Color.parseColor("#1B3358"));
                guideHeroStatusBadge.setTextColor(Color.parseColor("#7AB2F5"));
            }
        }
        if (guideHeroTime != null) {
            guideHeroTime.setText(prog.timeRange != null ? prog.timeRange : "--:--");
        }
        if (guideHeroRemaining != null) {
            if (prog.isCurrent) {
                guideHeroRemaining.setVisibility(View.VISIBLE);
                guideHeroRemaining.setText("Restam ~" + prog.remainingMin + " min");
            } else {
                guideHeroRemaining.setVisibility(View.GONE);
            }
        }
        if (guideHeroTitle != null) {
            guideHeroTitle.setText(prog.title != null ? prog.title : "Sem título");
        }
        if (guideHeroProgress != null) {
            if (prog.isCurrent) {
                guideHeroProgress.setVisibility(View.VISIBLE);
                guideHeroProgress.setProgress(prog.progress);
            } else {
                guideHeroProgress.setVisibility(View.GONE);
            }
        }
        if (guideHeroSynopsis != null) {
            guideHeroSynopsis.setText(prog.synopsis != null && !prog.synopsis.isEmpty() ? prog.synopsis : "Transmissão digital oficial ao vivo em alta definição.");
        }
    }

    private void handleDirectChannelDigit(int digit) {
        channelNumberHandler.removeCallbacks(channelNumberCommitRunnable);
        if (channelNumberBuffer.length() >= 4) {
            channelNumberBuffer.setLength(0);
        }
        channelNumberBuffer.append(digit);
        String currentInput = channelNumberBuffer.toString();

        if (topChannelBadge != null) {
            topChannelBadge.setVisibility(View.VISIBLE);
        }
        if (topChNum != null) {
            topChNum.setText("CH " + currentInput);
        }
        if (topChName != null) {
            topChName.setText("Sintonizando...");
        }
        if (osdChNum != null) {
            osdChNum.setText(currentInput);
        }
        showOsdBanner(3000);

        channelNumberHandler.postDelayed(channelNumberCommitRunnable, 2000);
    }

    private void commitChannelNumberInput() {
        if (channelNumberBuffer.length() == 0 || allChannels.isEmpty()) return;
        String inputStr = channelNumberBuffer.toString().trim();
        channelNumberBuffer.setLength(0);
        int targetNum = -1;
        try {
            targetNum = Integer.parseInt(inputStr);
        } catch (Exception ignored) {}

        if (targetNum <= 0) return;

        // 1. Prioridade: índice sequencial 1-based (Canal 1 = index 0, Canal 10 = index 9)
        int targetIdx = targetNum - 1;
        if (targetIdx >= 0 && targetIdx < allChannels.size()) {
            destroyCurrentStream();
            tuneChannel(targetIdx, true);
            return;
        }

        // 2. Busca por prefixo numérico no nome do canal (ex: "010 Globo", "12 SBT")
        for (int i = 0; i < allChannels.size(); i++) {
            Channel ch = allChannels.get(i);
            if (ch.name != null) {
                String digitsOnly = ch.name.replaceAll("^[^0-9]*([0-9]+).*", "$1");
                try {
                    if (!digitsOnly.isEmpty() && Integer.parseInt(digitsOnly) == targetNum) {
                        destroyCurrentStream();
                        tuneChannel(i, true);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        Toast.makeText(this, "Canal " + targetNum + " não encontrado", Toast.LENGTH_SHORT).show();
    }

    private void showProviderOptionsDialog() {
        List<String> currentPriority = ProviderManager.getPriorityList(this);

        String[] options = new String[] {
                "⭐ 1º StreamVerde | 2º RDCanais | 3º RDEmbed (Padrão)",
                "⚡ 1º RDCanais | 2º RDEmbed | 3º StreamVerde",
                "🚀 1º RDEmbed | 2º StreamVerde | 3º RDCanais",
                "🟢 1º StreamVerde | 2º RDEmbed | 3º RDCanais",
                "🛠️ Escolher Provedor Primário (1º Lugar)..."
        };

        int selectedIndex = 0;
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

        AlertDialog dialog1 = createThemedDialogBuilder()
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
                .create();
        styleDialogButtons(dialog1);
        dialog1.show();
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

        AlertDialog dialog2 = createThemedDialogBuilder()
                .setTitle("Escolha o 1º Provedor (Primário)")
                .setItems(providers, (dialog, which) -> {
                    String chosen1 = provIds[which];
                    showSecondaryProviderDialog(chosen1);
                })
                .setNegativeButton("Cancelar", null)
                .create();
        styleDialogButtons(dialog2);
        dialog2.show();
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

        AlertDialog dialog3 = createThemedDialogBuilder()
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
                .create();
        styleDialogButtons(dialog3);
        dialog3.show();
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
        startSportsRefreshTicker();
        if (isMosaicActive) {
            for (int i = 0; i < 4; i++) {
                if (mosaicSlots[i] != null && mosaicSlots[i].exoPlayer != null) {
                    mosaicSlots[i].exoPlayer.play();
                }
            }
        } else if (exoPlayer != null && !isPlayingEmbed) {
            exoPlayer.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isPlayingVod) {
            saveCurrentVodProgress();
        }
        if (isMosaicActive) {
            for (int i = 0; i < 4; i++) {
                if (mosaicSlots[i] != null && mosaicSlots[i].exoPlayer != null) {
                    mosaicSlots[i].exoPlayer.pause();
                }
            }
        } else if (exoPlayer != null) {
            exoPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSportsRefreshTicker();
        if (isMosaicActive) {
            exitMosaicMode(false);
        }
        destroyCurrentStream();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        executor.shutdown();
    }
}
