package com.andplay.app.epg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

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

    private static final Map<String, List<ProgramInfo>> liveEpgMap = new ConcurrentHashMap<>();
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
            parser.setInput(is, "UTF-8");

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
                                currentTitle = text.trim();
                            } else if ("desc".equals(currentTag) && currentDesc == null) {
                                currentDesc = text.trim();
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
        String idKey = normalizeKey(ch.id);
        String nameKey = normalizeKey(ch.name);

        List<ProgramInfo> progs = liveEpgMap.get(idKey);
        if (progs == null || progs.isEmpty()) progs = liveEpgMap.get(nameKey);

        if (progs == null || progs.isEmpty()) {
            for (Map.Entry<String, List<ProgramInfo>> entry : liveEpgMap.entrySet()) {
                String k = entry.getKey();
                if ((!idKey.isEmpty() && (k.contains(idKey) || idKey.contains(k)))
                        || (!nameKey.isEmpty() && (k.contains(nameKey) || nameKey.contains(k)))) {
                    progs = entry.getValue();
                    break;
                }
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

        // Sem dados reais no XMLTV: NUNCA usar valores falsos!
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
