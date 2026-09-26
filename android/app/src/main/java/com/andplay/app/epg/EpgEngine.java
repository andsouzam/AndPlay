package com.andplay.app.epg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import com.andplay.app.api.ApiClient;
import com.andplay.app.model.Channel;
import com.andplay.app.model.LiveSchedule;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EpgEngine {

    private static final String TAG = "EPlayEPG";
    private static final String[] EPG_URLS = {
            "https://iptv-epg.org/files/epg-br.xml",
            "https://raw.githubusercontent.com/limaalef/BrazilTVEPG/main/epg.xml"
    };
    private static final String PREF_NAME = "epg_prefs";
    private static final String KEY_LAST_SYNC = "last_sync_time";
    private static final String CACHE_FILE = "epg_cache_v2.json";

    public interface OnEpgUpdatedListener {
        void onEpgUpdated();
    }

    public static class ProgramInfo {
        public String title;
        public String desc;
        public long startMs;
        public long stopMs;

        public ProgramInfo() {}

        public ProgramInfo(String title, String desc, long startMs, long stopMs) {
            this.title = title;
            this.desc = desc;
            this.startMs = startMs;
            this.stopMs = stopMs;
        }
    }

    public static class TimelineProgram {
        public String title;
        public String synopsis;
        public long startMs;
        public long stopMs;
        public String timeRange;
        public boolean isCurrent;
        public boolean isPast;
        public boolean isFuture;
        public int progress;
        public int remainingMin;

        public TimelineProgram(String title, String synopsis, long startMs, long stopMs, String timeRange, boolean isCurrent, boolean isPast, boolean isFuture, int progress, int remainingMin) {
            this.title = title;
            this.synopsis = synopsis;
            this.startMs = startMs;
            this.stopMs = stopMs;
            this.timeRange = timeRange;
            this.isCurrent = isCurrent;
            this.isPast = isPast;
            this.isFuture = isFuture;
            this.progress = progress;
            this.remainingMin = remainingMin;
        }
    }

    private static final Map<String, List<ProgramInfo>> liveEpgMap = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> REGIONAL_ALIASES = new HashMap<>();

    static {
        // Globo Regionais (Mapeamento de Afiliadas das Capitais + Fallback para Rede Nacional)
        REGIONAL_ALIASES.put("globoba", Arrays.asList("tvbahia", "globobahia", "redebugbahia", "globoba", "globobrasil", "tvglobo", "globosp", "globorj", "globo"));
        REGIONAL_ALIASES.put("tvbahia", Arrays.asList("tvbahia", "globobahia", "globoba", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoal", Arrays.asList("tvasabranca", "asabranca", "tvgazetaalagoas", "tvgazetaal", "tvgazetamaceio", "tvgazeta", "globoalagoas", "globoal", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvasabranca", Arrays.asList("tvasabranca", "asabranca", "globoal", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoam", Arrays.asList("redeamazonica", "tvamazonas", "redeamazonicamanaus", "globoam", "globoamazonas", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoap", Arrays.asList("redeamazonicamacapa", "tvamapa", "redeamazonica", "globoap", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoac", Arrays.asList("redeamazonicariobranco", "tvacre", "redeamazonica", "globoac", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoce", Arrays.asList("tvverdesmares", "verdesmares", "globoceara", "globoce", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvverdesmares", Arrays.asList("tvverdesmares", "globoce", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globodf", Arrays.asList("globobrasilia", "tvglobobrasilia", "globodf", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoes", Arrays.asList("tvgazetaes", "tvgazetavitoria", "tvgazeta", "globoes", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globogo", Arrays.asList("globoanhanguera", "tvanhanguera", "tvanhangueragoiania", "globogoias", "globogo", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvanhanguera", Arrays.asList("globoanhanguera", "globogo", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoma", Arrays.asList("tvmirante", "tvmirantesaoluis", "globoma", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvmirante", Arrays.asList("tvmirante", "globoma", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globomt", Arrays.asList("tvcentroamerica", "tvcentroamericacuiaba", "globomt", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvcentroamerica", Arrays.asList("tvcentroamerica", "globomt", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoms", Arrays.asList("tvmorena", "tvmorenams", "tvmorenacampogrande", "globoms", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvmorena", Arrays.asList("tvmorenams", "tvmorena", "globoms", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globomg", Arrays.asList("globominas", "tvglobominas", "globomg", "globobrasil", "tvglobo", "globosp", "globorj", "globo"));
        REGIONAL_ALIASES.put("globopa", Arrays.asList("tvliberal", "tvliberalbelem", "globopa", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvliberal", Arrays.asList("tvliberal", "globopa", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globopb", Arrays.asList("tvcabobranco", "tvparaiba", "globopb", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvcabobranco", Arrays.asList("tvcabobranco", "globopb", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globopr", Arrays.asList("rpctv", "rpccuritiba", "rpc", "globopr", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("rpctv", Arrays.asList("rpccuritiba", "rpctv", "globopr", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globope", Arrays.asList("tvgloborecife", "globorecife", "tvglobonordeste", "globope", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globopi", Arrays.asList("tvclube", "tvclubeteresina", "globopi", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvclube", Arrays.asList("tvclube", "globopi", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globorj", Arrays.asList("globorj", "tvgloborj", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globorn", Arrays.asList("intertvcabugi", "intertv", "intertvnatal", "globorn", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("intertvcabugi", Arrays.asList("intertvcabugi", "globorn", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globoro", Arrays.asList("redeamazonicarondonia", "tvrondonia", "redeamazonica", "globoro", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globorr", Arrays.asList("redeamazonicaroraima", "tvroraima", "redeamazonica", "globorr", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globors", Arrays.asList("rbstvportoalegre", "rbstv", "rbstvrs", "globors", "globobrasil", "tvglobo", "globosp", "globorj", "globo"));
        REGIONAL_ALIASES.put("rbstv", Arrays.asList("rbstvportoalegre", "rbstv", "rbstvrs", "globors", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globosc", Arrays.asList("nsctv", "nsctvflorianopolis", "rbssc", "globosc", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("nsctv", Arrays.asList("nsctvflorianopolis", "nsctv", "globosc", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globose", Arrays.asList("tvsergipe", "tvsergipearacaju", "globose", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("tvsergipe", Arrays.asList("tvsergipe", "globose", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globosp", Arrays.asList("globosp", "tvglobosp", "globobrasil", "tvglobo", "globo"));
        REGIONAL_ALIASES.put("globoto", Arrays.asList("tvanhanguerapalmas", "tvanhanguera", "globoto", "globobrasil", "tvglobo", "globosp", "globo"));
        REGIONAL_ALIASES.put("globo", Arrays.asList("tvglobo", "globobrasil", "globosp", "globorj", "globo"));
        REGIONAL_ALIASES.put("globonews", Arrays.asList("globonews"));
        REGIONAL_ALIASES.put("globoplaynovelas", Arrays.asList("globoplaynovelas", "viva"));

        // Band Regionais (Mapeamento de Afiliadas + Fallback Nacional)
        REGIONAL_ALIASES.put("bandba", Arrays.asList("bandbahia", "tvbandbahia", "bandba", "bandbrasil", "bandsp", "bandrj", "band"));
        REGIONAL_ALIASES.put("bandbahia", Arrays.asList("tvbandbahia", "bandbahia", "bandba", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandmg", Arrays.asList("bandminas", "tvbandminas", "bandmg", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandrj", Arrays.asList("bandrio", "tvbandrio", "bandrj", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandsp", Arrays.asList("bandsp", "tvbandsp", "bandcampinas", "bandbrasil", "band"));
        REGIONAL_ALIASES.put("bandrs", Arrays.asList("bandrs", "tvbandrs", "bandportoalegre", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandpr", Arrays.asList("bandparana", "tvtaroba", "bandcuritiba", "bandpr", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandsc", Arrays.asList("tvbarrigaverde", "bandsc", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("banddf", Arrays.asList("bandbrasilia", "banddf", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandce", Arrays.asList("bandceara", "nordestv", "bandce", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandrn", Arrays.asList("bandnatal", "bandrn", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandpe", Arrays.asList("tvtribunape", "bandpernambuco", "bandpe", "tvtribunarecife", "tvtribuna", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandpa", Arrays.asList("bandpara", "bandbelem", "rbatv", "tvbandpara", "bandpa", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandpb", Arrays.asList("tvmanaira", "bandmanaira", "bandpb", "bandparaiba", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandam", Arrays.asList("bandamazonas", "bandam", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("bandma", Arrays.asList("bandmaranhao", "bandma", "bandbrasil", "bandsp", "band"));
        REGIONAL_ALIASES.put("band", Arrays.asList("bandsp", "bandbrasil", "band"));
        REGIONAL_ALIASES.put("bandnews", Arrays.asList("bandnews"));
        REGIONAL_ALIASES.put("bandsports", Arrays.asList("bandsports"));

        // Record Regionais (Mapeamento de Afiliadas + Fallback Nacional)
        REGIONAL_ALIASES.put("recordsp", Arrays.asList("recordtvsp", "recordsp", "recordtvbrasil", "recordbrasil", "record"));
        REGIONAL_ALIASES.put("recordpb", Arrays.asList("tvcorreio", "recordpb", "recordparaiba", "recordtvbrasil", "recordtvsp", "recordbrasil", "record"));
        REGIONAL_ALIASES.put("recordro", Arrays.asList("sictv", "recordro", "recordsictv", "recordtvbrasil", "recordtvsp", "recordbrasil", "record"));
        REGIONAL_ALIASES.put("recordrn", Arrays.asList("tvtropical", "recordrn", "recordtropical", "recordtvbrasil", "recordtvsp", "recordbrasil", "record"));
        REGIONAL_ALIASES.put("recordmt", Arrays.asList("tvvilareal", "recordmt", "recordtvbrasil", "recordtvsp", "recordbrasil", "record"));
        REGIONAL_ALIASES.put("recordnews", Arrays.asList("recordnews"));

        // SBT Regionais (Mapeamento de Afiliadas + Fallback Nacional)
        REGIONAL_ALIASES.put("sbt", Arrays.asList("sbtbrasil", "sbtsp", "sbt", "sbtrj"));
        REGIONAL_ALIASES.put("sbtpi", Arrays.asList("tvcidadeverde", "sbtpi", "sbtpiaui", "sbtbrasil", "sbtsp", "sbt"));

        // Outros canais abertos com variações comuns
        REGIONAL_ALIASES.put("culturabrasil", Arrays.asList("cultura", "tvcultura", "culturabrasil"));
        REGIONAL_ALIASES.put("cancaonova", Arrays.asList("cancaonova", "tvfcanconova"));
        REGIONAL_ALIASES.put("gazeta", Arrays.asList("tvgazeta", "gazeta"));
        REGIONAL_ALIASES.put("tvbrasil", Arrays.asList("tvbrasil", "ebc"));
        REGIONAL_ALIASES.put("redetv", Arrays.asList("redetv", "redetvsp", "redetvrj"));
        REGIONAL_ALIASES.put("redevida", Arrays.asList("redevida"));
        REGIONAL_ALIASES.put("redegospel", Arrays.asList("redegospel"));
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static OnEpgUpdatedListener updateListener;
    private static boolean isSyncing = false;
    private static final Gson gson = new Gson();

    public static void setUpdateListener(OnEpgUpdatedListener listener) {
        updateListener = listener;
    }

    public static void init(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();

        // 1. Carrega imediatamente o cache local do disco para ter EPG instantâneo
        loadLocalCache(appCtx);

        // 2. Verifica se precisa atualizar da internet (a cada 2 horas)
        SharedPreferences prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long lastSync = prefs.getLong(KEY_LAST_SYNC, 0);
        long now = System.currentTimeMillis();

        if (now - lastSync > 2 * 60 * 60 * 1000 || liveEpgMap.isEmpty()) {
            syncFromNetwork(appCtx);
        }
    }

    private static InputStream openStreamWithRedirects(String initialUrl) throws IOException {
        String currentUrl = initialUrl;
        for (int redirects = 0; redirects < 6; redirects++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0");
            conn.setRequestProperty("Accept", "text/xml,application/xml,*/*");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null) {
                    if (loc.startsWith("/")) {
                        currentUrl = url.getProtocol() + "://" + url.getHost() + loc;
                    } else {
                        currentUrl = loc;
                    }
                    continue;
                }
            }
            if (code == HttpURLConnection.HTTP_OK) {
                return conn.getInputStream();
            }
            conn.disconnect();
            throw new IOException("HTTP " + code + " ao acessar " + currentUrl);
        }
        throw new IOException("Muitos redirecionamentos em " + initialUrl);
    }

    public static void syncFromNetwork(Context context) {
        if (isSyncing) return;
        isSyncing = true;
        Context appCtx = context.getApplicationContext();

        executor.execute(() -> {
            try {
                Log.d(TAG, "Iniciando download e sincronização do EPG real de TV...");
                boolean downloaded = false;

                for (String epgUrl : EPG_URLS) {
                    InputStream is = null;
                    try {
                        Log.d(TAG, "Tentando baixar EPG da URL: " + epgUrl);
                        is = openStreamWithRedirects(epgUrl);
                        parseXmltv(is);
                        downloaded = true;
                        Log.i(TAG, "EPG sincronizado com sucesso a partir de " + epgUrl + "! Canais mapeados: " + liveEpgMap.size());
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "Falha ao baixar EPG de " + epgUrl + ": " + e.getMessage());
                    } finally {
                        if (is != null) {
                            try { is.close(); } catch (Exception ignored) {}
                        }
                    }
                }

                if (downloaded) {
                    // Salva cache local
                    saveLocalCache(appCtx);

                    SharedPreferences prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply();

                    mainHandler.post(() -> {
                        if (updateListener != null) {
                            updateListener.onEpgUpdated();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro durante sync do EPG: " + e.getMessage());
            } finally {
                isSyncing = false;
            }
        });
    }

    private static void parseXmltv(InputStream is) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, null);

            long now = System.currentTimeMillis();
            Map<String, List<ProgramInfo>> tempMap = new HashMap<>();

            int eventType = parser.getEventType();
            String currentChannel = null;
            String currentStart = null;
            String currentStop = null;
            String currentTitle = null;
            String currentDesc = null;
            String currentTag = null;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                    if ("programme".equals(currentTag)) {
                        currentChannel = parser.getAttributeValue(null, "channel");
                        currentStart = parser.getAttributeValue(null, "start");
                        currentStop = parser.getAttributeValue(null, "stop");
                        currentTitle = null;
                        currentDesc = null;
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (currentTag != null) {
                        String text = parser.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            if ("title".equals(currentTag) && currentTitle == null) {
                                currentTitle = ApiClient.sanitizeText(text.trim());
                            } else if ("desc".equals(currentTag) && currentDesc == null) {
                                currentDesc = ApiClient.sanitizeText(text.trim());
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("programme".equals(tag)) {
                        if (currentChannel != null && currentStart != null && currentStop != null && currentTitle != null) {
                            long startMs = parseXmltvDate(currentStart);
                            long stopMs = parseXmltvDate(currentStop);

                            // Mantém programação de 2h atrás até 36h no futuro
                            if (stopMs >= (now - 2 * 3600000L) && startMs <= (now + 36 * 3600000L)) {
                                String key = normalizeKey(currentChannel);
                                if (!key.isEmpty()) {
                                    List<ProgramInfo> list = tempMap.computeIfAbsent(key, k -> new ArrayList<>());
                                    list.add(new ProgramInfo(currentTitle, currentDesc, startMs, stopMs));
                                }
                            }
                        }
                        currentChannel = null;
                        currentStart = null;
                        currentStop = null;
                        currentTitle = null;
                        currentDesc = null;
                    }
                    currentTag = null;
                }
                eventType = parser.next();
            }

            // Ordena cada lista por horário de início e transfere para o mapa global
            for (Map.Entry<String, List<ProgramInfo>> entry : tempMap.entrySet()) {
                List<ProgramInfo> progs = entry.getValue();
                Collections.sort(progs, (a, b) -> Long.compare(a.startMs, b.startMs));
                liveEpgMap.put(entry.getKey(), progs);
            }

        } catch (Exception e) {
            Log.e(TAG, "Erro no XmlPullParser: " + e.getMessage());
        }
    }

    public static long parseXmltvDate(String str) {
        if (str == null || str.length() < 14) return 0;
        try {
            String trimmed = str.trim();
            if (trimmed.length() >= 20 && (trimmed.contains("+") || trimmed.contains("-"))) {
                int signIdx = Math.max(trimmed.lastIndexOf('+'), trimmed.lastIndexOf('-'));
                if (signIdx > 0) {
                    String datePart = trimmed.substring(0, signIdx).trim();
                    String tzPart = trimmed.substring(signIdx).replace(":", "");
                    trimmed = datePart + " " + tzPart;
                }
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
                Date d = sdf.parse(trimmed);
                if (d != null) return d.getTime();
            }
            if (trimmed.endsWith("Z") || trimmed.endsWith("z")) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(trimmed);
                if (d != null) return d.getTime();
            }
            String digits = trimmed.substring(0, 14);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT-3"));
            Date d = sdf.parse(digits);
            if (d != null) return d.getTime();
        } catch (Exception ignored) {}
        return 0;
    }

    public static String normalizeKey(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replaceAll("&", "and")
                .replaceAll("\\+", "plus")
                .replaceAll("_local", "")
                .replaceAll("\\.br$", "")
                .replaceAll("br$", "")
                .replaceAll("\\bhd\\b", "")
                .replaceAll("\\buhd\\b", "")
                .replaceAll("\\b4k\\b", "")
                .replaceAll("\\bfast\\b", "")
                .replaceAll("[^a-z0-9]", "");
        return s;
    }

    public static List<String> getSearchAliases(Channel ch) {
        List<String> list = new ArrayList<>();
        if (ch == null) return list;

        String idNorm = normalizeKey(ch.id);
        String nameNorm = normalizeKey(ch.name);

        // 1. Adiciona aliases mapeados explicitamente para o id ou para o nome
        if (!idNorm.isEmpty() && REGIONAL_ALIASES.containsKey(idNorm)) {
            for (String a : REGIONAL_ALIASES.get(idNorm)) {
                if (!list.contains(a)) list.add(a);
            }
        }
        if (!nameNorm.isEmpty() && !nameNorm.equals(idNorm) && REGIONAL_ALIASES.containsKey(nameNorm)) {
            for (String a : REGIONAL_ALIASES.get(nameNorm)) {
                if (!list.contains(a)) list.add(a);
            }
        }

        // 2. Adiciona o id e o nome normalizados como alternativas padrão
        if (!idNorm.isEmpty() && !list.contains(idNorm)) list.add(idNorm);
        if (!nameNorm.isEmpty() && !list.contains(nameNorm)) list.add(nameNorm);

        return list;
    }

    public static LiveSchedule getLiveSchedule(Channel ch) {
        if (ch == null) {
            return new LiveSchedule(
                    "SEM DADOS DE PROGRAMAÇÃO",
                    "Grade de programação indisponível para este canal no momento.",
                    "--:--",
                    "--:--",
                    0,
                    0,
                    "SEM DADOS DE PROGRAMAÇÃO",
                    "--:--"
            );
        }
        long now = System.currentTimeMillis();
        List<String> candidates = getSearchAliases(ch);
        List<ProgramInfo> progs = null;

        // 1. Busca exata por candidato na ordem de prioridade (afiliada local -> rede nacional)
        for (String cand : candidates) {
            if (cand.isEmpty()) continue;
            progs = liveEpgMap.get(cand);
            if (progs != null && !progs.isEmpty()) break;
        }

        // 2. Se não encontrou exato, busca parcial por candidato na ordem de prioridade
        if (progs == null || progs.isEmpty()) {
            for (String cand : candidates) {
                if (cand.length() < 3) continue;
                for (Map.Entry<String, List<ProgramInfo>> entry : liveEpgMap.entrySet()) {
                    String k = entry.getKey();
                    if (k.equals(cand) || k.startsWith(cand) || cand.startsWith(k)
                            || (k.length() >= 4 && cand.contains(k))
                            || (cand.length() >= 4 && k.contains(cand))) {
                        progs = entry.getValue();
                        break;
                    }
                }
                if (progs != null && !progs.isEmpty()) break;
            }
        }

        if (progs != null && !progs.isEmpty()) {
            ProgramInfo cur = null;
            ProgramInfo nxt = null;

            for (ProgramInfo p : progs) {
                if (p.startMs <= now && now < p.stopMs) {
                    cur = p;
                } else if (p.startMs >= now) {
                    if (nxt == null || p.startMs < nxt.startMs) {
                        nxt = p;
                    }
                }
            }

            SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());

            if (cur != null) {
                long dur = Math.max(60000, cur.stopMs - cur.startMs);
                long elapsed = Math.max(0, now - cur.startMs);
                int progress = (int) Math.min(99, Math.max(2, (elapsed * 100) / dur));
                int remainingMin = (int) Math.max(1, (cur.stopMs - now) / 60000);

                String startStr = tf.format(new Date(cur.startMs));
                String endStr = tf.format(new Date(cur.stopMs));
                String nextTitle = (nxt != null && nxt.title != null) ? nxt.title : "SEM DADOS DE PROGRAMAÇÃO";
                String nextStart = (nxt != null && nxt.startMs > 0) ? tf.format(new Date(nxt.startMs)) : "--:--";
                String synopsis = (cur.desc != null && !cur.desc.isEmpty()) ? cur.desc : "Transmissão digital oficial ao vivo em alta definição.";

                return new LiveSchedule(cur.title, synopsis, startStr, endStr, progress, remainingMin, nextTitle, nextStart);
            } else if (nxt != null && (nxt.startMs - now) <= 30 * 60000L) {
                // Intervalo curto antes do próximo programa
                String nextStart = tf.format(new Date(nxt.startMs));
                String nextEnd = tf.format(new Date(nxt.stopMs));
                int minUntil = (int) Math.max(1, (nxt.startMs - now) / 60000);
                return new LiveSchedule(
                        "A Seguir: " + nxt.title,
                        (nxt.desc != null && !nxt.desc.isEmpty()) ? nxt.desc : "Em instantes na programação.",
                        nextStart,
                        nextEnd,
                        0,
                        minUntil,
                        nxt.title,
                        nextStart
                );
            }
        }

        // Fonte 2: Dados embutidos no canal (now e next)
        if (ch.now != null && !ch.now.trim().isEmpty() && !"SEM DADOS DE PROGRAMAÇÃO".equalsIgnoreCase(ch.now.trim())) {
            String nextTitle = "Programação Contínua";
            String nextStart = "A Seguir";
            if (ch.next != null && !ch.next.isEmpty() && ch.next.get(0) != null) {
                if (ch.next.get(0).t != null && !ch.next.get(0).t.isEmpty()) nextTitle = ApiClient.sanitizeText(ch.next.get(0).t);
                if (ch.next.get(0).s != null && !ch.next.get(0).s.isEmpty()) nextStart = ch.next.get(0).s;
            }
            int prog = ch.prog > 0 ? ch.prog : 50;
            return new LiveSchedule(
                    ApiClient.sanitizeText(ch.now),
                    "Transmissão oficial ao vivo em tempo real.",
                    "Ao Vivo",
                    nextStart,
                    prog,
                    30,
                    nextTitle,
                    nextStart
            );
        }

        // Fonte 3: Geração dinâmica contextual de alta fidelidade
        return generateDynamicSchedule(ch);
    }

    public static class ProgramDetails {
        public String title;
        public String desc;
        public String nextTitle;

        public ProgramDetails(String title, String desc, String nextTitle) {
            this.title = title;
            this.desc = desc;
            this.nextTitle = nextTitle;
        }
    }

    public static ProgramDetails getShowDetailsAtHour(Channel ch, int hours) {
        hours = ((hours % 24) + 24) % 24;
        String chName = (ch != null && ch.name != null) ? ch.name : "Canal Ao Vivo";
        String norm = normalizeKey(chName);

        if (norm.contains("globo") || norm.contains("tvbahia") || norm.contains("rbstv")) {
            if (hours >= 4 && hours < 6) {
                return new ProgramDetails("Hora 1", "As primeiras notícias do dia com agilidade e dinamismo.", "Bom Dia Brasil");
            } else if (hours >= 6 && hours < 8) {
                return new ProgramDetails("Bom Dia Local", "Notícias locais da sua região, trânsito e previsão do tempo.", "Bom Dia Brasil");
            } else if (hours >= 8 && hours < 9) {
                return new ProgramDetails("Bom Dia Brasil", "Os acontecimentos mais importantes do país e do mundo.", "Encontro com Patrícia Poeta");
            } else if (hours >= 9 && hours < 10) {
                return new ProgramDetails("Encontro com Patrícia Poeta", "Música, entretenimento e entrevistas com convidados especiais.", "Mais Você");
            } else if (hours >= 10 && hours < 12) {
                return new ProgramDetails("Mais Você com Ana Maria Braga", "Receitas deliciosas, bate-papo, culinária e matérias especiais.", "Globo Esporte");
            } else if (hours >= 12 && hours < 13) {
                return new ProgramDetails("Jornal Local - 1ª Edição", "O balanço dos fatos do dia em sua região ao vivo.", "Globo Esporte");
            } else if (hours >= 13 && hours < 14) {
                return new ProgramDetails("Globo Esporte", "Tudo sobre o futebol e os principais atletas do país.", "Jornal Hoje");
            } else if (hours >= 14 && hours < 15) {
                return new ProgramDetails("Jornal Hoje", "Noticiário vespertino com tudo o que está acontecendo agora.", "Sessão da Tarde");
            } else if (hours >= 15 && hours < 17) {
                return new ProgramDetails("Sessão da Tarde", "Grandes filmes e sucessos para animar a sua tarde em alta definição.", "Vale a Pena Ver de Novo");
            } else if (hours >= 17 && hours < 18) {
                return new ProgramDetails("Vale a Pena Ver de Novo", "As novelas consagradas que marcaram época na TV Globo.", "Novela das Seis");
            } else if (hours >= 18 && hours < 19) {
                return new ProgramDetails("Novela das Seis", "A trama do início de noite repleta de romance e aventura.", "Jornal Local - 2ª Edição");
            } else if (hours >= 19 && hours < 20) {
                return new ProgramDetails("Jornal Local - 2ª Edição", "As principais notícias do final de tarde na sua cidade.", "Novela das Sete");
            } else if (hours >= 20 && hours < 21) {
                return new ProgramDetails("Jornal Nacional", "O principal telejornal do Brasil com cobertura factual completa.", "Novela das Nove");
            } else if (hours >= 21 && hours < 22) {
                return new ProgramDetails("Novela das Nove", "A novela do horário nobre em alta definição digital.", "Cinema Especial / Futebol");
            } else if (hours >= 22 && hours < 24) {
                return new ProgramDetails("Linha de Shows / Cinema Especial", "Filmes premiados, reality shows e produções de prestígio.", "Jornal da Globo");
            } else {
                return new ProgramDetails("Jornal da Globo / Conversa com Bial", "Análise aprofundada dos assuntos políticos e econômicos do dia.", "Hora 1");
            }
        } else if (norm.contains("band")) {
            if (hours >= 6 && hours < 9) {
                return new ProgramDetails("Bora Brasil", "O amanhecer com as notícias mais quentes do trânsito e do país.", "Jogo Aberto");
            } else if (hours >= 9 && hours < 11) {
                return new ProgramDetails("The Chef com Edu Guedes", "Dicas práticas de culinária e gastronomia na sua manhã.", "Jogo Aberto");
            } else if (hours >= 11 && hours < 13) {
                return new ProgramDetails("Jogo Aberto", "Renata Fan e Denílson debatem o futebol com irreverência e gols.", "Os Donos da Bola");
            } else if (hours >= 13 && hours < 14) {
                return new ProgramDetails("Os Donos da Bola", "Craque Neto e comentaristas debatem os lances polêmicos da rodada.", "Melhor da Tarde");
            } else if (hours >= 14 && hours < 16) {
                return new ProgramDetails("Melhor da Tarde", "Entretenimento, variedades, culinária e fofocas das celebridades.", "Brasil Urgente");
            } else if (hours >= 16 && hours < 19) {
                return new ProgramDetails("Brasil Urgente com Datena", "Plantão policial ao vivo e os principais flagrantes das capitais.", "Jornal da Band");
            } else if (hours >= 19 && hours < 21) {
                return new ProgramDetails("Jornal da Band", "Telejornal de credibilidade com os temas mais relevantes do dia.", "Melhor da Noite");
            } else if (hours >= 21 && hours < 23) {
                return new ProgramDetails("Melhor da Noite", "Programa ao vivo de variedades com cultura, humor e entretenimento.", "Jornal da Noite");
            } else {
                return new ProgramDetails("Jornal da Noite / Linha de Shows", "Noticiário de encerramento do dia e compactos esportivos.", "Bora Brasil");
            }
        } else {
            String period;
            String nextPeriod;
            if (hours >= 6 && hours < 12) {
                period = "Edição Matinal";
                nextPeriod = "Edição da Tarde";
            } else if (hours >= 12 && hours < 18) {
                period = "Edição da Tarde";
                nextPeriod = "Horário Nobre";
            } else if (hours >= 18 && hours < 24) {
                period = "Horário Nobre";
                nextPeriod = "Madrugada";
            } else {
                period = "Madrugada Especial";
                nextPeriod = "Edição Matinal";
            }
            return new ProgramDetails(chName + " - " + period, "Transmissão digital oficial em tempo real em alta definição.", chName + " - " + nextPeriod);
        }
    }

    private static LiveSchedule generateDynamicSchedule(Channel ch) {
        Calendar cal = Calendar.getInstance();
        int hours = cal.get(Calendar.HOUR_OF_DAY);
        int mins = cal.get(Calendar.MINUTE);
        ProgramDetails pd = getShowDetailsAtHour(ch, hours);

        int prog = Math.min(95, Math.max(10, (mins * 100) / 60));
        int rem = Math.max(5, 60 - mins);
        String startStr = String.format(Locale.getDefault(), "%02d:00", hours);
        String endStr = String.format(Locale.getDefault(), "%02d:00", (hours + 1) % 24);

        return new LiveSchedule(
                pd.title,
                pd.desc,
                startStr,
                endStr,
                prog,
                rem,
                pd.nextTitle,
                endStr
        );
    }

    public static List<TimelineProgram> getChannelTimeline(Channel ch) {
        List<TimelineProgram> timeline = new ArrayList<>();
        if (ch == null) return timeline;

        long now = System.currentTimeMillis();
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        List<String> candidates = getSearchAliases(ch);
        List<ProgramInfo> progs = null;

        for (String cand : candidates) {
            if (cand.isEmpty()) continue;
            progs = liveEpgMap.get(cand);
            if (progs != null && !progs.isEmpty()) break;
        }

        if (progs == null || progs.isEmpty()) {
            for (String cand : candidates) {
                if (cand.length() < 3) continue;
                for (Map.Entry<String, List<ProgramInfo>> entry : liveEpgMap.entrySet()) {
                    String k = entry.getKey();
                    if (k.equals(cand) || k.startsWith(cand) || cand.startsWith(k)
                            || (k.length() >= 4 && cand.contains(k))
                            || (cand.length() >= 4 && k.contains(cand))) {
                        progs = entry.getValue();
                        break;
                    }
                }
                if (progs != null && !progs.isEmpty()) break;
            }
        }

        if (progs != null && !progs.isEmpty()) {
            int currentIdx = -1;
            for (int i = 0; i < progs.size(); i++) {
                ProgramInfo p = progs.get(i);
                if (p.startMs <= now && now < p.stopMs) {
                    currentIdx = i;
                    break;
                }
            }
            if (currentIdx < 0) {
                for (int i = 0; i < progs.size(); i++) {
                    if (progs.get(i).startMs >= now) {
                        currentIdx = i;
                        break;
                    }
                }
            }
            if (currentIdx < 0) currentIdx = progs.size() - 1;

            int startIdx = Math.max(0, currentIdx - 3);
            int endIdx = Math.min(progs.size(), currentIdx + 9);

            for (int i = startIdx; i < endIdx; i++) {
                ProgramInfo p = progs.get(i);
                boolean isCur = (p.startMs <= now && now < p.stopMs);
                boolean isPast = (p.stopMs <= now);
                boolean isFut = (p.startMs > now);
                int dur = (int) Math.max(1, (p.stopMs - p.startMs) / 60000);
                int elapsed = (int) Math.max(0, (now - p.startMs) / 60000);
                int prog = isCur ? (int) Math.min(99, Math.max(2, (elapsed * 100) / dur)) : (isPast ? 100 : 0);
                int rem = isCur ? (int) Math.max(1, (p.stopMs - now) / 60000) : 0;
                String timeRange = tf.format(new Date(p.startMs)) + " - " + tf.format(new Date(p.stopMs));
                String syn = (p.desc != null && !p.desc.trim().isEmpty()) ? p.desc.trim() : "Transmissão digital oficial ao vivo em alta definição.";
                timeline.add(new TimelineProgram(p.title, syn, p.startMs, p.stopMs, timeRange, isCur, isPast, isFut, prog, rem));
            }
            return timeline;
        }

        // Se não houver XMLTV para este canal, gera 3 passados, 1 atual e 6 futuros contextualizados
        Calendar cal = Calendar.getInstance();
        int curHour = cal.get(Calendar.HOUR_OF_DAY);
        int curMin = cal.get(Calendar.MINUTE);

        // 3 passados (curHour - 3, -2, -1)
        for (int hDiff = -3; hDiff < 0; hDiff++) {
            int h = curHour + hDiff;
            ProgramDetails pd = getShowDetailsAtHour(ch, h);
            String timeRange = String.format(Locale.getDefault(), "%02d:00 - %02d:00", ((h % 24) + 24) % 24, (((h + 1) % 24) + 24) % 24);
            timeline.add(new TimelineProgram(pd.title, pd.desc, 0, 0, timeRange, false, true, false, 100, 0));
        }

        // Atual (curHour)
        ProgramDetails curPd = getShowDetailsAtHour(ch, curHour);
        int prog = Math.min(95, Math.max(10, (curMin * 100) / 60));
        int rem = Math.max(5, 60 - curMin);
        String curRange = String.format(Locale.getDefault(), "%02d:00 - %02d:00", ((curHour % 24) + 24) % 24, (((curHour + 1) % 24) + 24) % 24);
        timeline.add(new TimelineProgram(curPd.title, curPd.desc, 0, 0, curRange, true, false, false, prog, rem));

        // 6 futuros (curHour + 1 a + 6)
        for (int hDiff = 1; hDiff <= 6; hDiff++) {
            int h = curHour + hDiff;
            ProgramDetails pd = getShowDetailsAtHour(ch, h);
            String timeRange = String.format(Locale.getDefault(), "%02d:00 - %02d:00", ((h % 24) + 24) % 24, (((h + 1) % 24) + 24) % 24);
            timeline.add(new TimelineProgram(pd.title, pd.desc, 0, 0, timeRange, false, false, true, 0, 0));
        }

        return timeline;
    }

    private static void saveLocalCache(Context context) {
        try {
            File file = new File(context.getFilesDir(), CACHE_FILE);
            String json = gson.toJson(liveEpgMap);
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            osw.write(json);
            osw.close();
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "Erro ao salvar cache EPG local: " + e.getMessage());
        }
    }

    private static void loadLocalCache(Context context) {
        try {
            File file = new File(context.getFilesDir(), CACHE_FILE);
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
                Type type = new TypeToken<Map<String, List<ProgramInfo>>>() {}.getType();
                Map<String, List<ProgramInfo>> map = gson.fromJson(reader, type);
                reader.close();
                fis.close();
                if (map != null && !map.isEmpty()) {
                    liveEpgMap.putAll(map);
                    Log.i(TAG, "Cache local do EPG carregado: " + liveEpgMap.size() + " canais.");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao ler cache EPG local: " + e.getMessage());
        }
    }
}
