package com.andplay.app.api;

import android.content.Context;
import com.andplay.app.model.Category;
import com.andplay.app.model.Channel;
import com.andplay.app.model.Episode;
import com.andplay.app.model.Movie;
import com.andplay.app.model.Series;
import com.andplay.app.model.SportsEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ApiClient {

    public static final String SERVER = "https://2kbrfonte.space";
    public static final String USER = "LuizDavi%40";
    public static final String PASS = "fBkvnKe5Mq";

    private static final Gson gson = new Gson();

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .dns(new com.andplay.app.MainActivity.StreamDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        JsonElement elem = obj.get(key);
        if (elem.isJsonPrimitive()) {
            return elem.getAsString();
        }
        return def;
    }

    private static int optInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) {
                return elem.getAsInt();
            }
        } catch (Exception ignored) {}
        return def;
    }

    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("\uFFFD", "")
                .replace("Caz?", "Cazé")
                .replace("S?rie", "Série")
                .replace("Not?cia", "Notícia")
                .replace("Document?rio", "Documentário")
                .replace("Ingl?s", "Inglês")
                .replace("Retr?", "Retrô")
                .replace("Cl?ssico", "Clássico")
                .replace("C?mara", "Câmara")
                .replace("Irm?o", "Irmão")
                .replace("?culos", "Óculos")
                .replace("M?gico", "Mágico")
                .replace("Hor?rio", "Horário")
                .replace("Obrigat?rio", "Obrigatório")
                .replace("Programa??o", "Programação")
                .replace("Programa?o", "Programação")
                .replace("Edi??o", "Edição")
                .replace("Edi?o", "Edição")
                .replace("Ambr?sio", "Ambrósio")
                .replace("Ant?nio", "Antônio")
                .replace("T? na ?rea", "Tá na Área")
                .replace("d? jogo", "dá jogo")
                .replace("Toma L? Da C?", "Toma Lá Dá Cá");
    }

    public static List<Channel> loadLocalChannels(Context context) {
        try {
            InputStream is = context.getAssets().open("channels.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            JsonElement root = JsonParser.parseReader(reader);
            reader.close();

            JsonArray arr = null;
            if (root != null) {
                if (root.isJsonArray()) {
                    arr = root.getAsJsonArray();
                } else if (root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    if (obj.has("value") && obj.get("value").isJsonArray()) {
                        arr = obj.getAsJsonArray("value");
                    } else if (obj.has("channels") && obj.get("channels").isJsonArray()) {
                        arr = obj.getAsJsonArray("channels");
                    } else if (obj.has("data") && obj.get("data").isJsonArray()) {
                        arr = obj.getAsJsonArray("data");
                    }
                }
            }

            if (arr != null) {
                Type type = new TypeToken<List<Channel>>() {}.getType();
                List<Channel> channels = gson.fromJson(arr, type);
                if (channels != null) {
                List<Channel> valid = new ArrayList<>();
                for (Channel ch : channels) {
                    if (ch == null || ch.id == null) continue;
                    String idLow = ch.id.toLowerCase();
                    String nameLow = (ch.name != null) ? ch.name.toLowerCase() : "";
                    if (idLow.contains("appletv") || nameLow.contains("apple tv")) {
                        continue;
                    }
                    if ("amazonsat".equals(idLow) || "24h-spacetoday".equals(idLow) || "24h-os-jetsons".equals(idLow)
                            || "bandba".equals(idLow) || "bandmg".equals(idLow) || "bandpa".equals(idLow) || "bandpb".equals(idLow) || "bandpe".equals(idLow)
                            || "recordmt".equals(idLow) || "recordpb".equals(idLow) || "recordrn".equals(idLow) || "recordro".equals(idLow)
                            || "sbtpi".equals(idLow) || nameLow.contains("spacetoday") || nameLow.contains("jetsons")) {
                        continue;
                    }
                    if ("premiere".equals(ch.id) || "premiere 1".equalsIgnoreCase(ch.name)) {
                        ch.name = "Premiere Clubes";
                    }
                    if ("globoal".equals(idLow)) {
                        ch.name = "TV Asa Branca";
                    }
                    if ("globoba".equals(idLow)) {
                        ch.name = "TV Bahia";
                    }
                    if ("globoam".equals(idLow)) {
                        ch.name = "Globo Amazônica";
                    }
                    if ("globodf".equals(idLow)) {
                        ch.name = "Globo Brasília";
                    }
                    if ("globogo".equals(idLow)) {
                        ch.name = "TV Anhanguera";
                    }
                    if ("globomg".equals(idLow)) {
                        ch.name = "Globo Minas";
                    }
                    if ("globoms".equals(idLow)) {
                        ch.name = "TV Morena";
                    }
                    if ("globors".equals(idLow)) {
                        ch.name = "RBS TV";
                    }
                    ch.name = sanitizeText(ch.name);
                    ch.cat = sanitizeText(ch.cat);
                    ch.now = sanitizeText(ch.now);
                    if (ch.next != null) {
                        for (Channel.NextProgram np : ch.next) {
                            if (np != null) {
                                np.t = sanitizeText(np.t);
                            }
                        }
                    }
                    valid.add(ch);
                }
                return valid;
            }
        }
    } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    public static String httpGet(String urlStr) throws Exception {
        Request request = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", "AndPlay Native Android TV 2.0")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            return body.string();
        }
    }

    private static String nextStringValue(JsonReader reader) {
        try {
            JsonToken token = reader.peek();
            if (token == JsonToken.NULL) {
                reader.nextNull();
                return "";
            } else if (token == JsonToken.STRING || token == JsonToken.NUMBER || token == JsonToken.BOOLEAN) {
                return reader.nextString();
            } else {
                reader.skipValue();
                return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static int nextIntValue(JsonReader reader) {
        try {
            JsonToken token = reader.peek();
            if (token == JsonToken.NULL) {
                reader.nextNull();
                return 0;
            } else if (token == JsonToken.NUMBER) {
                return reader.nextInt();
            } else if (token == JsonToken.STRING) {
                String str = reader.nextString();
                try {
                    return Integer.parseInt(str);
                } catch (Exception ignored) {
                    return 0;
                }
            } else {
                reader.skipValue();
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static Category readCategory(JsonReader reader) throws Exception {
        reader.beginObject();
        String catId = "";
        String catName = "";
        while (reader.hasNext()) {
            String key = reader.nextName();
            if ("category_id".equals(key)) {
                catId = nextStringValue(reader);
            } else if ("category_name".equals(key)) {
                catName = nextStringValue(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return new Category(catId, catName);
    }

    private static Movie readMovie(JsonReader reader) throws Exception {
        reader.beginObject();
        Movie m = new Movie();
        m.stream_type = "movie";
        m.container_extension = "mp4";
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (key) {
                case "num":
                    m.num = nextIntValue(reader);
                    break;
                case "name":
                    m.name = nextStringValue(reader);
                    break;
                case "title":
                    m.title = nextStringValue(reader);
                    break;
                case "year":
                    m.year = nextStringValue(reader);
                    break;
                case "stream_type":
                    m.stream_type = nextStringValue(reader);
                    break;
                case "stream_id":
                    m.stream_id = nextStringValue(reader);
                    break;
                case "stream_icon":
                    m.stream_icon = nextStringValue(reader);
                    break;
                case "rating":
                    m.rating = nextStringValue(reader);
                    break;
                case "rating_5based":
                    m.rating_5based = nextStringValue(reader);
                    break;
                case "added":
                    m.added = nextStringValue(reader);
                    break;
                case "category_id":
                    m.category_id = nextStringValue(reader);
                    break;
                case "container_extension":
                    m.container_extension = nextStringValue(reader);
                    break;
                case "custom_sid":
                    m.custom_sid = nextStringValue(reader);
                    break;
                case "direct_source":
                    m.direct_source = nextStringValue(reader);
                    break;
                case "plot":
                    m.plot = nextStringValue(reader);
                    break;
                case "cast":
                    m.cast = nextStringValue(reader);
                    break;
                case "director":
                    m.director = nextStringValue(reader);
                    break;
                case "genre":
                    m.genre = nextStringValue(reader);
                    break;
                case "release_date":
                    m.release_date = nextStringValue(reader);
                    break;
                case "duration":
                    m.duration = nextStringValue(reader);
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return m;
    }

    private static Series readSeries(JsonReader reader) throws Exception {
        reader.beginObject();
        Series s = new Series();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (key) {
                case "num":
                    s.num = nextIntValue(reader);
                    break;
                case "name":
                    s.name = nextStringValue(reader);
                    break;
                case "title":
                    s.title = nextStringValue(reader);
                    break;
                case "series_id":
                    s.series_id = nextStringValue(reader);
                    break;
                case "cover":
                    s.cover = nextStringValue(reader);
                    break;
                case "plot":
                    s.plot = nextStringValue(reader);
                    break;
                case "cast":
                    s.cast = nextStringValue(reader);
                    break;
                case "director":
                    s.director = nextStringValue(reader);
                    break;
                case "genre":
                    s.genre = nextStringValue(reader);
                    break;
                case "releaseDate":
                case "release_date":
                    s.releaseDate = nextStringValue(reader);
                    break;
                case "last_modified":
                    s.last_modified = nextStringValue(reader);
                    break;
                case "rating":
                    s.rating = nextStringValue(reader);
                    break;
                case "rating_5based":
                    s.rating_5based = nextStringValue(reader);
                    break;
                case "category_id":
                    s.category_id = nextStringValue(reader);
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        return s;
    }

    public static List<Category> getMovieCategories() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_vod_categories";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AndPlay Native Android TV 2.0")
                .build();

        List<Category> list = new ArrayList<>();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return list;

            try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)))) {
                reader.setLenient(true);
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            Category c = readCategory(reader);
                            if (c != null && c.category_name != null && !c.category_name.toLowerCase().contains("demo")) {
                                list.add(c);
                            }
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endArray();
                }
            }
        }
        return list;
    }

    public static List<Movie> getMovies() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_vod_streams";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AndPlay Native Android TV 2.0")
                .build();

        List<Movie> clean = new ArrayList<>();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return clean;

            try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)))) {
                reader.setLenient(true);
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            Movie m = readMovie(reader);
                            if (m != null) {
                                String nameLow = m.name != null ? m.name.toLowerCase() : "";
                                String titleLow = m.title != null ? m.title.toLowerCase() : "";
                                if (!nameLow.contains("demo") && !titleLow.contains("demo")) {
                                    clean.add(m);
                                }
                            }
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endArray();
                }
            }
        }
        return clean;
    }

    public static List<Category> getSeriesCategories() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series_categories";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AndPlay Native Android TV 2.0")
                .build();

        List<Category> list = new ArrayList<>();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return list;

            try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)))) {
                reader.setLenient(true);
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            Category c = readCategory(reader);
                            if (c != null && c.category_name != null && !c.category_name.toLowerCase().contains("demo")) {
                                list.add(c);
                            }
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endArray();
                }
            }
        }
        return list;
    }

    public static List<Series> getSeries() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AndPlay Native Android TV 2.0")
                .build();

        List<Series> list = new ArrayList<>();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return list;

            try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)))) {
                reader.setLenient(true);
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            Series s = readSeries(reader);
                            if (s != null) {
                                String nameLow = s.name != null ? s.name.toLowerCase() : "";
                                String titleLow = s.title != null ? s.title.toLowerCase() : "";
                                if (!nameLow.contains("demo") && !titleLow.contains("demo")) {
                                    list.add(s);
                                }
                            }
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endArray();
                }
            }
        }
        return list;
    }

    public static Map<String, List<Episode>> getSeriesEpisodes(String seriesId) throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series_info&series_id=" + seriesId;
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "AndPlay Native Android TV 2.0")
                .build();

        Map<String, List<Episode>> result = new HashMap<>();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return result;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                JsonElement root = JsonParser.parseReader(br);
                if (root != null && root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    if (obj.has("episodes")) {
                        JsonElement epElem = obj.get("episodes");
                        if (epElem != null && epElem.isJsonObject()) {
                            JsonObject epsObj = epElem.getAsJsonObject();
                            for (String seasonKey : epsObj.keySet()) {
                                JsonElement elem = epsObj.get(seasonKey);
                                if (elem != null && elem.isJsonArray()) {
                                    Type listType = new TypeToken<List<Episode>>() {}.getType();
                                    List<Episode> epList = gson.fromJson(elem, listType);
                                    result.put(seasonKey, epList != null ? epList : new ArrayList<>());
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    public static List<SportsEvent> getLiveSports() {
        List<SportsEvent> events = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url("https://api.reidoscanais.st/sports")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonStr = response.body().string();
                    JsonElement rootElem = JsonParser.parseString(jsonStr);
                    JsonArray list = null;
                    if (rootElem.isJsonObject()) {
                        JsonObject obj = rootElem.getAsJsonObject();
                        if (obj.has("data") && obj.get("data").isJsonArray()) {
                            list = obj.getAsJsonArray("data");
                        }
                    } else if (rootElem.isJsonArray()) {
                        list = rootElem.getAsJsonArray();
                    }

                    if (list != null) {
                        for (int i = 0; i < list.size(); i++) {
                            JsonElement el = list.get(i);
                            if (!el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject();

                            String id = optString(item, "id", "ev_" + i);
                            String title = optString(item, "title", "");
                            String competition = optString(item, "competition", optString(item, "category", "Futebol Ao Vivo"));
                            String status = optString(item, "status", "upcoming");
                            boolean isLive = "live".equalsIgnoreCase(status);
                            boolean isFinished = "finished".equalsIgnoreCase(status)
                                    || "ended".equalsIgnoreCase(status)
                                    || "completed".equalsIgnoreCase(status)
                                    || "ft".equalsIgnoreCase(status)
                                    || "post".equalsIgnoreCase(status);

                            JsonObject teams = item.has("teams") && item.get("teams").isJsonObject() ? item.getAsJsonObject("teams") : null;
                            String homeName = "";
                            String homeLogo = "";
                            String awayName = "";
                            String awayLogo = "";
                            String homeScore = "";
                            String awayScore = "";

                            if (teams != null) {
                                if (teams.has("home") && teams.get("home").isJsonObject()) {
                                    JsonObject h = teams.getAsJsonObject("home");
                                    homeName = optString(h, "name", "");
                                    homeLogo = optString(h, "logo", "");
                                    if (h.has("score") && !h.get("score").isJsonNull()) {
                                        homeScore = h.get("score").getAsString();
                                    }
                                }
                                if (teams.has("away") && teams.get("away").isJsonObject()) {
                                    JsonObject a = teams.getAsJsonObject("away");
                                    awayName = optString(a, "name", "");
                                    awayLogo = optString(a, "logo", "");
                                    if (a.has("score") && !a.get("score").isJsonNull()) {
                                        awayScore = a.get("score").getAsString();
                                    }
                                }
                            }

                            if (homeName.isEmpty() && awayName.isEmpty()) {
                                if (title.contains(" x ") || title.contains(" X ") || title.contains(" vs ") || title.contains(" VS ")) {
                                    String[] parts = title.split(" (?i)(x|vs) ");
                                    if (parts.length >= 2) {
                                        homeName = parts[0].trim();
                                        awayName = parts[1].trim();
                                    }
                                }
                            }

                            if (title.isEmpty()) {
                                title = (!homeName.isEmpty() && !awayName.isEmpty()) ? homeName + " x " + awayName : "Evento Esportivo";
                            }

                            // Captura o timestamp de início para calcular HOJE/AMANHÃ
                            long startTs = 0L;
                            if (item.has("start_timestamp") && !item.get("start_timestamp").isJsonNull()) {
                                try { startTs = item.get("start_timestamp").getAsLong(); } catch (Exception ignored) {}
                            }

                            // Calcula label de horário e dia
                            String matchTime = isLive ? "AO VIVO" : (isFinished ? "FINALIZADO" : "EM BREVE");
                            if (startTs > 0) {
                                try {
                                    TimeZone tz = TimeZone.getTimeZone("America/Sao_Paulo");
                                    SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
                                    timeFmt.setTimeZone(tz);

                                    // Compara data do evento com hoje/amanhã em São Paulo
                                    SimpleDateFormat dayFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
                                    dayFmt.setTimeZone(tz);
                                    String eventDay = dayFmt.format(new Date(startTs * 1000L));
                                    String todayDay = dayFmt.format(new Date());
                                    long nowMs = System.currentTimeMillis();
                                    Date tomorrowDate = new Date(nowMs + 86400000L);
                                    String tomorrowDay = dayFmt.format(tomorrowDate);

                                    if (isLive) {
                                        matchTime = "AO VIVO";
                                    } else if (isFinished) {
                                        matchTime = timeFmt.format(new Date(startTs * 1000L));
                                    } else if (eventDay.equals(todayDay)) {
                                        matchTime = timeFmt.format(new Date(startTs * 1000L));
                                    } else if (eventDay.equals(tomorrowDay)) {
                                        matchTime = timeFmt.format(new Date(startTs * 1000L));
                                    } else {
                                        matchTime = timeFmt.format(new Date(startTs * 1000L));
                                    }
                                } catch (Exception ignored) {}
                            } else if (item.has("start_time") && !item.get("start_time").isJsonNull()) {
                                String st = item.get("start_time").getAsString();
                                if (st.length() >= 16 && !isLive && !isFinished) {
                                    matchTime = st.substring(11, 16);
                                }
                            }

                            // Determina label de dia para o badge (HOJE / AMANHÃ / AO VIVO / FINALIZADO)
                            // Será calculado no adapter com base em startTimestamp e isFinished

                            SportsEvent ev = new SportsEvent();
                            ev.id = id;
                            ev.name = title;
                            ev.league = competition;
                            ev.isLive = isLive;
                            ev.isFinished = isFinished;
                            ev.startTimestamp = startTs;
                            ev.matchTime = matchTime;
                            ev.homeLogo = homeLogo;
                            ev.awayLogo = awayLogo;
                            ev.homeName = homeName;
                            ev.awayName = awayName;

                            // 1. Mapeia canais StreamVerde e lista de canais candidatos para as transmissões oficiais da partida
                            List<String> detectedSvSlugs = new ArrayList<>();
                            JsonArray embeds = item.has("embeds") && item.get("embeds").isJsonArray() ? item.getAsJsonArray("embeds") : null;
                            if (embeds != null) {
                                for (int j = 0; j < embeds.size(); j++) {
                                    JsonElement embEl = embeds.get(j);
                                    if (!embEl.isJsonObject()) continue;
                                    JsonObject emb = embEl.getAsJsonObject();
                                    String provider = optString(emb, "provider", "");
                                    if (!provider.isEmpty() && !ev.candidateChannels.contains(provider)) {
                                        ev.candidateChannels.add(provider);
                                    }

                                    String provLow = provider.toLowerCase(Locale.ROOT);
                                    if (provLow.contains("sportv 2") || provLow.contains("sportv2")) {
                                        if (!detectedSvSlugs.contains("sportv2")) detectedSvSlugs.add("sportv2");
                                    } else if (provLow.contains("sportv 3") || provLow.contains("sportv3")) {
                                        if (!detectedSvSlugs.contains("sportv3")) detectedSvSlugs.add("sportv3");
                                    } else if (provLow.contains("sportv 4") || provLow.contains("sportv4")) {
                                        if (!detectedSvSlugs.contains("sportv4")) detectedSvSlugs.add("sportv4");
                                    } else if (provLow.contains("sportv")) {
                                        if (!detectedSvSlugs.contains("sportv")) detectedSvSlugs.add("sportv");
                                    } else if (provLow.contains("premiere 2")) {
                                        if (!detectedSvSlugs.contains("premiere2")) detectedSvSlugs.add("premiere2");
                                    } else if (provLow.contains("premiere 3")) {
                                        if (!detectedSvSlugs.contains("premiere3")) detectedSvSlugs.add("premiere3");
                                    } else if (provLow.contains("premiere 4")) {
                                        if (!detectedSvSlugs.contains("premiere4")) detectedSvSlugs.add("premiere4");
                                    } else if (provLow.contains("premiere 5")) {
                                        if (!detectedSvSlugs.contains("premiere5")) detectedSvSlugs.add("premiere5");
                                    } else if (provLow.contains("premiere 6")) {
                                        if (!detectedSvSlugs.contains("premiere6")) detectedSvSlugs.add("premiere6");
                                    } else if (provLow.contains("premiere")) {
                                        if (!detectedSvSlugs.contains("premiereclubes")) detectedSvSlugs.add("premiereclubes");
                                    } else if (provLow.contains("espn 2") || provLow.contains("espn2")) {
                                        if (!detectedSvSlugs.contains("espn2")) detectedSvSlugs.add("espn2");
                                    } else if (provLow.contains("espn 3") || provLow.contains("espn3")) {
                                        if (!detectedSvSlugs.contains("espn3")) detectedSvSlugs.add("espn3");
                                    } else if (provLow.contains("espn 4") || provLow.contains("espn4")) {
                                        if (!detectedSvSlugs.contains("espn4")) detectedSvSlugs.add("espn4");
                                    } else if (provLow.contains("espn 5") || provLow.contains("espn5")) {
                                        if (!detectedSvSlugs.contains("espn5")) detectedSvSlugs.add("espn5");
                                    } else if (provLow.contains("espn 6") || provLow.contains("espn6")) {
                                        if (!detectedSvSlugs.contains("espn6")) detectedSvSlugs.add("espn6");
                                    } else if (provLow.contains("espn")) {
                                        if (!detectedSvSlugs.contains("espn")) detectedSvSlugs.add("espn");
                                    } else if (provLow.contains("cazé") || provLow.contains("caze")) {
                                        if (!detectedSvSlugs.contains("cazetv")) detectedSvSlugs.add("cazetv");
                                    } else if (provLow.contains("tnt")) {
                                        if (!detectedSvSlugs.contains("tnt")) detectedSvSlugs.add("tnt");
                                    } else if (provLow.contains("combate")) {
                                        if (!detectedSvSlugs.contains("combate")) detectedSvSlugs.add("combate");
                                    } else if (provLow.contains("globo")) {
                                        if (!detectedSvSlugs.contains("globosp")) detectedSvSlugs.add("globosp");
                                    } else if (provLow.contains("band")) {
                                        if (!detectedSvSlugs.contains("bandsp")) detectedSvSlugs.add("bandsp");
                                    } else if (provLow.contains("sbt")) {
                                        if (!detectedSvSlugs.contains("sbt")) detectedSvSlugs.add("sbt");
                                    }
                                }
                            }

                            // 1. Adiciona transmissões diretas em HLS do provedor StreamVerde (0 delay, nativo)
                            for (String svSlug : detectedSvSlugs) {
                                ev.fallbacks.add(new Channel.StreamFallback(
                                        "StreamVerde (" + svSlug.toUpperCase(Locale.ROOT) + ")",
                                        "https://svd.cazetv.shop/streamverde/" + svSlug + ".m3u8",
                                        false
                                ));
                            }

                            // 2. Adiciona links oficiais de embeds informados pelo evento
                            if (embeds != null) {
                                for (int j = 0; j < embeds.size(); j++) {
                                    JsonElement embEl = embeds.get(j);
                                    if (!embEl.isJsonObject()) continue;
                                    JsonObject emb = embEl.getAsJsonObject();
                                    String provider = optString(emb, "provider", "Opção " + (j + 1));
                                    String embedUrl = optString(emb, "embed_url", "");
                                    if (!embedUrl.isEmpty()) {
                                        ev.fallbacks.add(new Channel.StreamFallback(
                                                provider + " (Embed)",
                                                embedUrl,
                                                true
                                        ));
                                    }
                                }
                            }

                            events.add(ev);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Enriquece partidas com placares em tempo real via ESPN (ao vivo e finalizados)
        enrichSportsWithScores(events);

        // Ordena: AO VIVO → Próximos (por horário de início crescente) → Finalizados
        Collections.sort(events, (a, b) -> {
            int rankA = a.isLive ? 0 : (a.isFinished ? 2 : 1);
            int rankB = b.isLive ? 0 : (b.isFinished ? 2 : 1);
            if (rankA != rankB) return rankA - rankB;
            // Mesmo grupo: ordena por horário de início crescente
            return Long.compare(a.startTimestamp, b.startTimestamp);
        });
        return events;
    }

    private static class EspnMatch {
        String homeName;
        String awayName;
        String homeScore;
        String awayScore;
        String clock;
        String state;
    }

    private static final Map<String, String> TEAM_ALIASES = new HashMap<>();
    static {
        TEAM_ALIASES.put("eslovenia", "slovenia");
        TEAM_ALIASES.put("escocia", "scotland");
        TEAM_ALIASES.put("islandia", "iceland");
        TEAM_ALIASES.put("estonia", "estonia");
        TEAM_ALIASES.put("bulgaria", "bulgaria");
        TEAM_ALIASES.put("luxemburgo", "luxembourg");
        TEAM_ALIASES.put("san marino", "san marino");
        TEAM_ALIASES.put("finlandia", "finland");
        TEAM_ALIASES.put("ilhas faroe", "faroe islands");
        TEAM_ALIASES.put("cazaquistao", "kazakhstan");
        TEAM_ALIASES.put("eslovaquia", "slovakia");
        TEAM_ALIASES.put("moldavia", "moldova");
        TEAM_ALIASES.put("macedonia do norte", "north macedonia");
        TEAM_ALIASES.put("suica", "switzerland");
        TEAM_ALIASES.put("inglaterra", "england");
        TEAM_ALIASES.put("espanha", "spain");
        TEAM_ALIASES.put("republica tcheca", "czechia");
        TEAM_ALIASES.put("croacia", "croatia");
        TEAM_ALIASES.put("albania", "albania");
        TEAM_ALIASES.put("belarus", "belarus");
        TEAM_ALIASES.put("eua", "united states");
        TEAM_ALIASES.put("estados unidos", "united states");
        TEAM_ALIASES.put("peru", "peru");
        TEAM_ALIASES.put("lituania", "lithuania");
        TEAM_ALIASES.put("azerbaijao", "azerbaijan");
        TEAM_ALIASES.put("servia", "serbia");
        TEAM_ALIASES.put("holanda", "netherlands");
        TEAM_ALIASES.put("italia", "italy");
        TEAM_ALIASES.put("alemanha", "germany");
        TEAM_ALIASES.put("franca", "france");
        TEAM_ALIASES.put("portugal", "portugal");
        TEAM_ALIASES.put("belgica", "belgium");
        TEAM_ALIASES.put("austria", "austria");
        TEAM_ALIASES.put("dinamarca", "denmark");
        TEAM_ALIASES.put("suecia", "sweden");
        TEAM_ALIASES.put("noruega", "norway");
        TEAM_ALIASES.put("polonia", "poland");
        TEAM_ALIASES.put("ucrania", "ukraine");
        TEAM_ALIASES.put("turquia", "turkey");
        TEAM_ALIASES.put("grecia", "greece");
        TEAM_ALIASES.put("russia", "russia");
        TEAM_ALIASES.put("uruguai", "uruguay");
        TEAM_ALIASES.put("paraguai", "paraguay");
        TEAM_ALIASES.put("colombia", "colombia");
        TEAM_ALIASES.put("argentina", "argentina");
        TEAM_ALIASES.put("chile", "chile");

        TEAM_ALIASES.put("sj earthquakes", "san jose earthquakes");
        TEAM_ALIASES.put("dc united", "dc united");
        TEAM_ALIASES.put("d.c. united", "dc united");
        TEAM_ALIASES.put("sport recife", "sport");
        TEAM_ALIASES.put("sporting kc", "sporting kansas city");
        TEAM_ALIASES.put("los angeles fc", "lafc");
        TEAM_ALIASES.put("new york rb", "red bull new york");
        TEAM_ALIASES.put("st. louis city", "st. louis city sc");
        TEAM_ALIASES.put("operario-pr", "operario");
        TEAM_ALIASES.put("operario pr", "operario");
        TEAM_ALIASES.put("atletico goianiense", "atletico goianiense");
        TEAM_ALIASES.put("atletico-go", "atletico goianiense");
        TEAM_ALIASES.put("athletico-pr", "athletico");
        TEAM_ALIASES.put("athletico pr", "athletico");
    }

    private static String cleanAndNormalizeTeam(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (TEAM_ALIASES.containsKey(s)) {
            return TEAM_ALIASES.get(s);
        }
        s = s.replaceAll("\\b(fc|sc|cf|ec|ac)\\b", "").replaceAll("\\s+", " ").trim();
        return s;
    }

    private static boolean matchTeamName(String evTeam, String espnTeam) {
        if (evTeam == null || espnTeam == null) return false;
        String a = cleanAndNormalizeTeam(evTeam);
        String b = cleanAndNormalizeTeam(espnTeam);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.length() >= 4 && b.contains(a)) return true;
        if (b.length() >= 4 && a.contains(b)) return true;
        return false;
    }

    private static void parseEspnEventsArray(JsonArray eventsArr, List<EspnMatch> out) {
        for (int j = 0; j < eventsArr.size(); j++) {
            JsonElement el = eventsArr.get(j);
            if (!el.isJsonObject()) continue;
            JsonObject eObj = el.getAsJsonObject();
            if (!eObj.has("competitions") || !eObj.get("competitions").isJsonArray()) continue;
            JsonArray comps = eObj.getAsJsonArray("competitions");
            if (comps.size() == 0) continue;
            JsonObject comp = comps.get(0).getAsJsonObject();

            String state = "";
            String clock = "";
            if (comp.has("status") && comp.get("status").isJsonObject()) {
                JsonObject st = comp.getAsJsonObject("status");
                if (st.has("type") && st.get("type").isJsonObject()) {
                    JsonObject t = st.getAsJsonObject("type");
                    state = optString(t, "state", "");
                    clock = optString(t, "shortDetail", "");
                }
            }

            if (!comp.has("competitors") || !comp.get("competitors").isJsonArray()) continue;
            JsonArray compsArr = comp.getAsJsonArray("competitors");
            EspnMatch m = new EspnMatch();
            m.state = state;
            m.clock = clock;

            for (int k = 0; k < compsArr.size(); k++) {
                JsonObject c = compsArr.get(k).getAsJsonObject();
                String ha = optString(c, "homeAway", "");
                String sc = optString(c, "score", "");
                String name = "";
                if (c.has("team") && c.get("team").isJsonObject()) {
                    JsonObject tm = c.getAsJsonObject("team");
                    name = optString(tm, "displayName", optString(tm, "name", ""));
                }
                if ("home".equalsIgnoreCase(ha)) {
                    m.homeName = name;
                    m.homeScore = sc;
                } else {
                    m.awayName = name;
                    m.awayScore = sc;
                }
            }
            if (m.homeName != null && m.awayName != null) {
                out.add(m);
            }
        }
    }

    private static void fetchEspnScorepanel(String date, List<EspnMatch> out) {
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/scorepanel?dates=" + date + "&lang=pt&region=br";
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    if (root.has("scores") && root.get("scores").isJsonArray()) {
                        JsonArray scoresArr = root.getAsJsonArray("scores");
                        for (int i = 0; i < scoresArr.size(); i++) {
                            JsonObject sObj = scoresArr.get(i).getAsJsonObject();
                            if (sObj.has("events") && sObj.get("events").isJsonArray()) {
                                parseEspnEventsArray(sObj.getAsJsonArray("events"), out);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void fetchEspnScoreboard(String league, String date, List<EspnMatch> out) {
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + league + "/scoreboard?dates=" + date + "&lang=pt&region=br";
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    if (root.has("events") && root.get("events").isJsonArray()) {
                        parseEspnEventsArray(root.getAsJsonArray("events"), out);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void enrichSportsWithScores(List<SportsEvent> events) {
        if (events == null || events.isEmpty()) return;
        try {
            TimeZone tz = TimeZone.getTimeZone("America/Sao_Paulo");
            SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd", Locale.US);
            df.setTimeZone(tz);
            String todayStr = df.format(new Date());
            String yestStr = df.format(new Date(System.currentTimeMillis() - 86400000L));

            Set<String> dates = new HashSet<>();
            dates.add(todayStr);
            dates.add(yestStr);
            for (SportsEvent ev : events) {
                if (ev.startTimestamp > 0) {
                    dates.add(df.format(new Date(ev.startTimestamp * 1000L)));
                }
            }

            List<EspnMatch> espnMatches = new ArrayList<>();
            for (String d : dates) {
                fetchEspnScorepanel(d, espnMatches);
                fetchEspnScoreboard("bra.2", d, espnMatches);
                fetchEspnScoreboard("bra.1", d, espnMatches);
            }

            for (SportsEvent ev : events) {
                if (ev.homeName == null || ev.awayName == null) continue;
                for (EspnMatch em : espnMatches) {
                    boolean directMatch = matchTeamName(ev.homeName, em.homeName) && matchTeamName(ev.awayName, em.awayName);
                    boolean revMatch = matchTeamName(ev.homeName, em.awayName) && matchTeamName(ev.awayName, em.homeName);

                    if (directMatch || revMatch) {
                        String hScore = directMatch ? em.homeScore : em.awayScore;
                        String aScore = directMatch ? em.awayScore : em.homeScore;
                        if (hScore != null && aScore != null && !hScore.isEmpty() && !aScore.isEmpty()) {
                            ev.homeScore = hScore;
                            ev.awayScore = aScore;
                            ev.score = hScore + " x " + aScore;
                            ev.clock = em.clock;

                            if ("post".equalsIgnoreCase(em.state) || "FT".equalsIgnoreCase(em.clock)) {
                                ev.isFinished = true;
                                ev.isLive = false;
                            } else if ("in".equalsIgnoreCase(em.state)) {
                                ev.isLive = true;
                                ev.isFinished = false;
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static List<SportsEvent> getDefaultSportsFallbacks() {
        return getLiveSports();
    }
}
