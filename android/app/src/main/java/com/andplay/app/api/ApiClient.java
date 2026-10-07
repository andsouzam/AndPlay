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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    public static final OkHttpClient httpClient = new OkHttpClient.Builder()
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

                            // Sanitização de anomalias da API (ex: times brasileiros da Série B rotulados como UEFA Nations League)
                            String compLow = competition.toLowerCase(Locale.ROOT);
                            if (isBrazilianClub(homeName) || isBrazilianClub(awayName)) {
                                if (compLow.contains("nations") || compLow.contains("uefa") || compLow.contains("premier")
                                        || compLow.contains("la liga") || compLow.contains("bundesliga") || compLow.contains("italiano")) {
                                    if (isBrazilianSerieBClub(homeName) || isBrazilianSerieBClub(awayName)) {
                                        competition = "Brasileirão Série B";
                                    } else {
                                        competition = "Brasileirão Série A";
                                    }
                                }
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
                                        if (isRealTvChannel(provider)) {
                                            ev.candidateChannels.add(0, provider); // Canais de TV reais sempre na frente!
                                        } else {
                                            ev.candidateChannels.add(provider);
                                        }
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
        String homeShortName;
        String awayName;
        String awayShortName;
        String homeScore;
        String awayScore;
        String clock;
        String state;
    }

    private static final Map<String, String> TEAM_ALIASES = new HashMap<>();
    static {
        // Seleções / UEFA Nations League
        TEAM_ALIASES.put("eslovenia", "slovenia");
        TEAM_ALIASES.put("slovenia", "slovenia");
        TEAM_ALIASES.put("escocia", "scotland");
        TEAM_ALIASES.put("scotland", "scotland");
        TEAM_ALIASES.put("islandia", "iceland");
        TEAM_ALIASES.put("iceland", "iceland");
        TEAM_ALIASES.put("estonia", "estonia");
        TEAM_ALIASES.put("bulgaria", "bulgaria");
        TEAM_ALIASES.put("luxemburgo", "luxembourg");
        TEAM_ALIASES.put("luxembourg", "luxembourg");
        TEAM_ALIASES.put("san marino", "san marino");
        TEAM_ALIASES.put("finlandia", "finland");
        TEAM_ALIASES.put("finland", "finland");
        TEAM_ALIASES.put("ilhas faroe", "faroe islands");
        TEAM_ALIASES.put("faroe islands", "faroe islands");
        TEAM_ALIASES.put("cazaquistao", "kazakhstan");
        TEAM_ALIASES.put("kazakhstan", "kazakhstan");
        TEAM_ALIASES.put("eslovaquia", "slovakia");
        TEAM_ALIASES.put("slovakia", "slovakia");
        TEAM_ALIASES.put("moldavia", "moldova");
        TEAM_ALIASES.put("moldova", "moldova");
        TEAM_ALIASES.put("macedonia do norte", "north macedonia");
        TEAM_ALIASES.put("north macedonia", "north macedonia");
        TEAM_ALIASES.put("suica", "switzerland");
        TEAM_ALIASES.put("switzerland", "switzerland");
        TEAM_ALIASES.put("inglaterra", "england");
        TEAM_ALIASES.put("england", "england");
        TEAM_ALIASES.put("espanha", "spain");
        TEAM_ALIASES.put("spain", "spain");
        TEAM_ALIASES.put("republica tcheca", "czechia");
        TEAM_ALIASES.put("tchequia", "czechia");
        TEAM_ALIASES.put("czechia", "czechia");
        TEAM_ALIASES.put("croacia", "croatia");
        TEAM_ALIASES.put("croatia", "croatia");
        TEAM_ALIASES.put("albania", "albania");
        TEAM_ALIASES.put("belarus", "belarus");
        TEAM_ALIASES.put("bielorrussia", "belarus");
        TEAM_ALIASES.put("eua", "united states");
        TEAM_ALIASES.put("estados unidos", "united states");
        TEAM_ALIASES.put("united states", "united states");
        TEAM_ALIASES.put("peru", "peru");
        TEAM_ALIASES.put("lituania", "lithuania");
        TEAM_ALIASES.put("lithuania", "lithuania");
        TEAM_ALIASES.put("azerbaijao", "azerbaijan");
        TEAM_ALIASES.put("azerbaijan", "azerbaijan");
        TEAM_ALIASES.put("servia", "serbia");
        TEAM_ALIASES.put("serbia", "serbia");
        TEAM_ALIASES.put("holanda", "netherlands");
        TEAM_ALIASES.put("paises baixos", "netherlands");
        TEAM_ALIASES.put("netherlands", "netherlands");
        TEAM_ALIASES.put("italia", "italy");
        TEAM_ALIASES.put("italy", "italy");
        TEAM_ALIASES.put("alemanha", "germany");
        TEAM_ALIASES.put("germany", "germany");
        TEAM_ALIASES.put("franca", "france");
        TEAM_ALIASES.put("france", "france");
        TEAM_ALIASES.put("portugal", "portugal");
        TEAM_ALIASES.put("belgica", "belgium");
        TEAM_ALIASES.put("belgium", "belgium");
        TEAM_ALIASES.put("austria", "austria");
        TEAM_ALIASES.put("dinamarca", "denmark");
        TEAM_ALIASES.put("denmark", "denmark");
        TEAM_ALIASES.put("suecia", "sweden");
        TEAM_ALIASES.put("sweden", "sweden");
        TEAM_ALIASES.put("noruega", "norway");
        TEAM_ALIASES.put("norway", "norway");
        TEAM_ALIASES.put("polonia", "poland");
        TEAM_ALIASES.put("poland", "poland");
        TEAM_ALIASES.put("ucrania", "ukraine");
        TEAM_ALIASES.put("ukraine", "ukraine");
        TEAM_ALIASES.put("turquia", "turkey");
        TEAM_ALIASES.put("turkey", "turkey");
        TEAM_ALIASES.put("grecia", "greece");
        TEAM_ALIASES.put("greece", "greece");
        TEAM_ALIASES.put("uruguai", "uruguay");
        TEAM_ALIASES.put("paraguai", "paraguay");
        TEAM_ALIASES.put("colombia", "colombia");
        TEAM_ALIASES.put("argentina", "argentina");
        TEAM_ALIASES.put("chile", "chile");

        // MLS e Clubes Internacionais
        TEAM_ALIASES.put("sj earthquakes", "san jose earthquakes");
        TEAM_ALIASES.put("san jose earthquakes", "san jose earthquakes");
        TEAM_ALIASES.put("dc united", "dc united");
        TEAM_ALIASES.put("d c united", "dc united");
        TEAM_ALIASES.put("sporting kc", "sporting kansas city");
        TEAM_ALIASES.put("sporting kansas city", "sporting kansas city");
        TEAM_ALIASES.put("los angeles fc", "lafc");
        TEAM_ALIASES.put("lafc", "lafc");
        TEAM_ALIASES.put("los angeles football club", "lafc");
        TEAM_ALIASES.put("new york rb", "new york red bulls");
        TEAM_ALIASES.put("red bull new york", "new york red bulls");
        TEAM_ALIASES.put("ny red bulls", "new york red bulls");
        TEAM_ALIASES.put("new york red bulls", "new york red bulls");
        TEAM_ALIASES.put("st louis city", "st louis city");
        TEAM_ALIASES.put("st louis city sc", "st louis city");
        TEAM_ALIASES.put("cf montreal", "montreal");
        TEAM_ALIASES.put("montreal impact", "montreal");

        // Clubes Brasileiros
        TEAM_ALIASES.put("sport recife", "sport");
        TEAM_ALIASES.put("sport club do recife", "sport");
        TEAM_ALIASES.put("operario pr", "operario");
        TEAM_ALIASES.put("operario ferroviario", "operario");
        TEAM_ALIASES.put("operario", "operario");
        TEAM_ALIASES.put("atletico goianiense", "atletico goianiense");
        TEAM_ALIASES.put("atletico go", "atletico goianiense");
        TEAM_ALIASES.put("athletico pr", "athletico");
        TEAM_ALIASES.put("athletico paranaense", "athletico");
        TEAM_ALIASES.put("athletico", "athletico");
        TEAM_ALIASES.put("america mg", "america mineiro");
        TEAM_ALIASES.put("america mineiro", "america mineiro");
        TEAM_ALIASES.put("ceara sc", "ceara");
        TEAM_ALIASES.put("ceara", "ceara");
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
        if (TEAM_ALIASES.containsKey(s)) {
            return TEAM_ALIASES.get(s);
        }
        return s;
    }

    public static boolean isBrazilianClub(String teamName) {
        if (teamName == null || teamName.isEmpty()) return false;
        String t = Normalizer.normalize(teamName.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.matches(".*\\b(crb|cuiaba|santos|sport|coritiba|vila nova|paysandu|chapecoense|operario|novorizontino|mirassol|america mg|america mineiro|avai|ceara|goias|ponte preta|botafogo sp|brusque|amazonas|guarani|ituano|flamengo|palmeiras|corinthians|sao paulo|vasco|fluminense|botafogo|gremio|internacional|cruzeiro|atletico mg|bahia|fortaleza|athletico pr|vitoria|juventude|criciuma|atletico go|nautico|csa|figueirense|tombense|confianca|abc|caxias|ferroviaria|volta redonda|ypiranga|londrina|remo|sampaio correa|aparecidense|ferroviario)\\b.*");
    }

    public static boolean isBrazilianSerieBClub(String teamName) {
        if (teamName == null || teamName.isEmpty()) return false;
        String t = Normalizer.normalize(teamName.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.matches(".*\\b(crb|cuiaba|santos|sport|coritiba|vila nova|paysandu|chapecoense|operario|novorizontino|mirassol|america mg|america mineiro|avai|ceara|goias|ponte preta|botafogo sp|brusque|amazonas|guarani|ituano)\\b.*");
    }

    public static boolean matchTeamName(String evTeam, String espnTeam) {
        if (evTeam == null || espnTeam == null) return false;
        String a = cleanAndNormalizeTeam(evTeam);
        String b = cleanAndNormalizeTeam(espnTeam);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.length() >= 4 && b.contains(a)) return true;
        if (b.length() >= 4 && a.contains(b)) return true;
        return false;
    }

    public static String getEspnLeagueForCompetition(String comp, String homeTeam, String awayTeam) {
        String fromComp = getEspnLeagueForCompetition(comp);
        if (fromComp != null) return fromComp;

        // Se a competição for genérica (ex: "Futebol", null, "Premiere Clubes"),
        // infere a liga através dos clubes participantes:
        if (homeTeam != null || awayTeam != null) {
            if (isBrazilianSerieBClub(homeTeam) || isBrazilianSerieBClub(awayTeam)) {
                return "bra.2";
            }
            if (isBrazilianClub(homeTeam) || isBrazilianClub(awayTeam)) {
                return "bra.1";
            }
        }
        return null;
    }

    public static String getEspnLeagueForCompetition(String comp) {
        if (comp == null) return null;
        String c = Normalizer.normalize(comp.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        if (c.contains("major league") || c.contains("mls")) return "usa.1";
        if (c.contains("serie b")) return "bra.2";
        if (c.contains("serie a") || c.contains("brasileirao")) return "bra.1";
        if (c.contains("nations") || c.contains("nacoes")) return "uefa.nations";
        if (c.contains("premier") || c.contains("ingles")) return "eng.1";
        if (c.contains("la liga") || c.contains("laliga") || c.contains("espanhol")) return "esp.1";
        if (c.contains("italiano") || c.contains("serie a italiana")) return "ita.1";
        if (c.contains("bundesliga") || c.contains("alemao")) return "ger.1";
        if (c.contains("ligue 1") || c.contains("frances")) return "fra.1";
        if (c.contains("champions") || c.contains("campeoes")) return "uefa.champions";
        if (c.contains("libertadores")) return "conmebol.libertadores";
        if (c.contains("sul americana") || c.contains("sudamericana")) return "conmebol.sudamericana";
        if (c.contains("copa do brasil")) return "bra.copa_do_brazil";
        if (c.contains("saudita") || c.contains("saudi")) return "sau.1";
        if (c.contains("amistoso") || c.contains("friendly")) return "fifa.friendly";
        if (c.contains("portuguesa") || c.contains("primeira liga") || c.contains("por.1")) return "por.1";
        if (c.contains("argentino") || c.contains("argentina") || c.contains("arg.1")) return "arg.1";
        if (c.contains("serie c") || c.contains("bra.3")) return "bra.3";
        return null;
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
                String shortName = "";
                if (c.has("team") && c.get("team").isJsonObject()) {
                    JsonObject tm = c.getAsJsonObject("team");
                    name = optString(tm, "displayName", optString(tm, "name", ""));
                    shortName = optString(tm, "shortDisplayName", "");
                }
                if ("home".equalsIgnoreCase(ha)) {
                    m.homeName = name;
                    m.homeShortName = shortName;
                    m.homeScore = sc;
                } else {
                    m.awayName = name;
                    m.awayShortName = shortName;
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
                    .header("Accept", "*/*")
                    .header("User-Agent", "curl/8.21.0")
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
                    .header("Accept", "*/*")
                    .header("User-Agent", "curl/8.21.0")
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

    private static class EspnCachedScore {
        final String score;
        final String homeScore;
        final String awayScore;
        final String clock;

        EspnCachedScore(String score, String homeScore, String awayScore, String clock) {
            this.score = score;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.clock = clock;
        }
    }

    // Cache LRU em memória volátil com limite expandido para 500 partidas (~50KB a 60KB em RAM, bem abaixo de 1000KB)
    private static final Map<String, EspnCachedScore> FINISHED_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, EspnCachedScore>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, EspnCachedScore> eldest) {
                    return size() > 500;
                }
            });

    private static EspnCachedScore getCachedFinishedScore(String home, String away) {
        if (home == null || away == null) return null;
        String a = cleanAndNormalizeTeam(home);
        String b = cleanAndNormalizeTeam(away);
        if (a.isEmpty() || b.isEmpty()) return null;
        String k1 = a + "_" + b;
        if (FINISHED_CACHE.containsKey(k1)) return FINISHED_CACHE.get(k1);
        String k2 = b + "_" + a;
        return FINISHED_CACHE.get(k2);
    }

    private static void saveFinishedScoreToCache(String home, String away, String score, String homeScore, String awayScore, String clock) {
        if (home == null || away == null || score == null) return;
        String a = cleanAndNormalizeTeam(home);
        String b = cleanAndNormalizeTeam(away);
        if (a.isEmpty() || b.isEmpty()) return;
        FINISHED_CACHE.put(a + "_" + b, new EspnCachedScore(score, homeScore, awayScore, clock));
    }

    private static void enrichSportsWithScores(List<SportsEvent> events) {
        if (events == null || events.isEmpty()) return;
        try {
            TimeZone tz = TimeZone.getTimeZone("America/Sao_Paulo");
            SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd", Locale.US);
            df.setTimeZone(tz);
            String todayStr = df.format(new Date());
            long nowSec = System.currentTimeMillis() / 1000L;

            // 1. Aplica placares de jogos finalizados já cacheados (sem fazer chamadas de rede)
            for (SportsEvent ev : events) {
                if (ev.homeName == null || ev.awayName == null) continue;
                EspnCachedScore cs = getCachedFinishedScore(ev.homeName, ev.awayName);
                if (cs != null) {
                    ev.score = cs.score;
                    ev.homeScore = cs.homeScore;
                    ev.awayScore = cs.awayScore;
                    ev.clock = cs.clock;
                    ev.isFinished = true;
                    ev.isLive = false;
                }
            }

            // 2. Filtra APENAS eventos que realmente precisam de consulta à ESPN:
            //    - Jogos marcados como AO VIVO (placar muda em tempo real)
            //    - Jogos finalizados que ainda NÃO têm placar no cache
            //    - Jogos com início recente/próximo (começou há menos de 4h ou começa em menos de 15min)
            Set<String> datesToQuery = new HashSet<>();
            Set<String> leaguesToQuery = new HashSet<>();
            List<SportsEvent> pendingEvents = new ArrayList<>();

            for (SportsEvent ev : events) {
                if (ev.homeName == null || ev.awayName == null) continue;

                // Se já está finalizado com placar preenchido, não necessita de nova requisição
                if (ev.isFinished && ev.score != null && !ev.score.isEmpty()) {
                    continue;
                }

                boolean isRecentlyStarted = (ev.startTimestamp > 0)
                        && (nowSec >= (ev.startTimestamp - 15 * 60L))
                        && (nowSec <= (ev.startTimestamp + 4 * 3600L));

                boolean needsQuery = ev.isLive
                        || ev.isFinished // Finalizado pendente de placar
                        || isRecentlyStarted; // Na janela ativa de partida

                if (needsQuery) {
                    pendingEvents.add(ev);
                    if (ev.startTimestamp > 0) {
                        datesToQuery.add(df.format(new Date(ev.startTimestamp * 1000L)));
                    } else {
                        datesToQuery.add(todayStr);
                    }
                    String lg = getEspnLeagueForCompetition(ev.league);
                    if (lg != null) leaguesToQuery.add(lg);
                }
            }

            // Se nenhum evento necessita de atualização ou busca, encerra sem fazer requisições HTTP
            if (pendingEvents.isEmpty() || datesToQuery.isEmpty()) {
                return;
            }

            // 3. Executa requisições ESPN em paralelo APENAS para as datas e ligas dos jogos pendentes
            List<EspnMatch> espnMatches = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, Math.max(1, leaguesToQuery.size() + 1)));
            List<Future<?>> futures = new ArrayList<>();

            for (String d : datesToQuery) {
                final String fDate = d;
                futures.add(pool.submit(() -> fetchEspnScorepanel(fDate, espnMatches)));
                for (String lg : leaguesToQuery) {
                    final String fLg = lg;
                    futures.add(pool.submit(() -> fetchEspnScoreboard(fLg, fDate, espnMatches)));
                }
            }

            for (Future<?> f : futures) {
                try {
                    f.get(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
            pool.shutdown();

            // 4. Faz a correlação de placares apenas para os eventos pendentes
            for (SportsEvent ev : pendingEvents) {
                for (EspnMatch em : espnMatches) {
                    boolean directMatch = (matchTeamName(ev.homeName, em.homeName) || matchTeamName(ev.homeName, em.homeShortName))
                            && (matchTeamName(ev.awayName, em.awayName) || matchTeamName(ev.awayName, em.awayShortName));
                    boolean revMatch = (matchTeamName(ev.homeName, em.awayName) || matchTeamName(ev.homeName, em.awayShortName))
                            && (matchTeamName(ev.awayName, em.homeName) || matchTeamName(ev.awayName, em.homeShortName));

                    if (directMatch || revMatch) {
                        // Se o jogo ainda não começou ("pre"), não define placar
                        if ("pre".equalsIgnoreCase(em.state)) {
                            ev.isLive = false;
                            ev.isFinished = false;
                            ev.score = null;
                            ev.clock = null;
                            break;
                        }

                        String hScore = directMatch ? em.homeScore : em.awayScore;
                        String aScore = directMatch ? em.awayScore : em.homeScore;
                        boolean isPost = "post".equalsIgnoreCase(em.state) || "FT".equalsIgnoreCase(em.clock) || "F".equalsIgnoreCase(em.clock) || ev.isFinished;
                        boolean isIn = "in".equalsIgnoreCase(em.state) || ev.isLive;

                        if (isPost && !isIn) {
                            ev.isFinished = true;
                            ev.isLive = false;
                            if (hScore != null && aScore != null && !hScore.isEmpty() && !aScore.isEmpty()) {
                                ev.homeScore = hScore;
                                ev.awayScore = aScore;
                                ev.score = hScore + " x " + aScore;
                                ev.clock = em.clock;
                                saveFinishedScoreToCache(ev.homeName, ev.awayName, ev.score, hScore, aScore, em.clock);
                            }
                        } else if (isIn) {
                            ev.isLive = true;
                            ev.isFinished = false;
                            if (hScore != null && aScore != null && !hScore.isEmpty() && !aScore.isEmpty()) {
                                ev.homeScore = hScore;
                                ev.awayScore = aScore;
                                ev.score = hScore + " x " + aScore;
                                ev.clock = em.clock;
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

    // ─────────────────────────────────────────────────────────────────────────
    // MODELOS PARA OVERLAY DE TABELA / RODADA
    // ─────────────────────────────────────────────────────────────────────────

    public static class StandingEntry {
        public int position;
        public String teamName;
        public String teamAbbr;
        public int played;
        public int wins;
        public int draws;
        public int losses;
        public int goalsFor;
        public int goalsAgainst;
        public int goalDiff;
        public int points;
        public boolean isHighlighted; // time da partida atual
        public String groupName;      // ex: "Conferência Leste", "Grupo A"
    }

    public static final String[][] HUB_LEAGUES = {
        // {slug ESPN, nome display, emoji}
        {"bra.1", "Brasileirão A", "🇧🇷"},
        {"bra.2", "Brasileirão B", "🇧🇷"},
        {"bra.3", "Série C", "🇧🇷"},
        {"conmebol.libertadores", "Libertadores", "🏆"},
        {"conmebol.sudamericana", "Sulamericana", "🏆"},
        {"bra.copa_do_brazil", "Copa do Brasil", "🏆"},
        {"arg.1", "Arg. Primera", "🇦🇷"},
        {"eng.1", "Premier League", "🏴󠁧󠁢󠁥󠁮󠁧󠁿"},
        {"esp.1", "La Liga", "🇪🇸"},
        {"ger.1", "Bundesliga", "🇩🇪"},
        {"ita.1", "Serie A", "🇮🇹"},
        {"fra.1", "Ligue 1", "🇫🇷"},
        {"por.1", "Primeira Liga", "🇵🇹"}
    };

    public static class ScheduleDay {
        public String dateLabel;   // "Hoje", "Sex 26/09"
        public String dateCode;    // "20260926" (para ordenação)
        public long dateMs;        // epoch ms do início do dia
        public boolean isToday;
        public List<RoundMatch> matches;
        public ScheduleDay() { matches = new ArrayList<>(); }
    }

    public static class RoundMatch {
        public String homeTeam;
        public String awayTeam;
        public String score;        // ex: "2 x 1" ou "vs"
        public String matchTime;    // ex: "20:00"
        public String statusLabel;  // ex: "Encerrado", "● AO VIVO • 45'", "Hoje 20:00", "Sáb 26/09"
        public String state;        // "pre" | "in" | "post"
        public boolean isCurrent;   // é a partida que está sendo assistida
        public long startMs;        // timestamp em milissegundos para ordenação
        public String channelId;    // id do SportsEvent com EPG match
        public String channelName;  // nome do canal/competição
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TABELA DE CLASSIFICAÇÃO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Busca a tabela de classificação da liga correspondente à competição do evento.
     * Suporta ligas com tabela única (Brasileirão, Premier League) e ligas com grupos/conferências (MLS, etc.).
     */
    /** Versão que aceita slug ESPN diretamente — usada pelo SportsHub */
    public static List<StandingEntry> getStandingsBySlug(String leagueSlug) {
        if (leagueSlug == null || leagueSlug.isEmpty()) return new ArrayList<>();
        // Cups sem tabela
        if (leagueSlug.startsWith("conmebol.") || leagueSlug.equals("bra.copa_do_brazil")
                || leagueSlug.equals("uefa.champions") || leagueSlug.equals("fifa.friendly")
                || leagueSlug.equals("uefa.nations")) return new ArrayList<>();
        // Mapeia slug → string reconhecida por getEspnLeagueForCompetition
        String compName;
        switch (leagueSlug) {
            case "bra.1":  compName = "brasileirao serie a"; break;
            case "bra.2":  compName = "serie b"; break;
            case "bra.3":  compName = "serie c"; break;
            case "arg.1":  compName = "campeonato argentino"; break;
            case "eng.1":  compName = "premier league"; break;
            case "esp.1":  compName = "la liga"; break;
            case "ger.1":  compName = "bundesliga"; break;
            case "ita.1":  compName = "serie a italiana"; break;
            case "fra.1":  compName = "ligue 1"; break;
            case "por.1":  compName = "portuguesa primeira liga"; break;
            default:       compName = leagueSlug;
        }
        return getStandings(compName, null, null);
    }

    public static List<StandingEntry> getStandings(String competition, String homeTeam, String awayTeam) {
        List<StandingEntry> result = new ArrayList<>();
        String league = getEspnLeagueForCompetition(competition, homeTeam, awayTeam);
        if (league == null) return result;

        // Algumas competições de copa não têm tabela contínua (Champions, Libertadores, Copa do Brasil, etc.)
        if (league.startsWith("conmebol.") || league.equals("bra.copa_do_brazil")
                || league.equals("uefa.champions") || league.equals("fifa.friendly")
                || league.equals("uefa.nations")) {
            return result; // sem tabela contínua
        }

        String url = "https://site.api.espn.com/apis/v2/sports/soccer/" + league
                + "/standings?lang=pt&region=br";
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "*/*")
                    .header("User-Agent", "curl/8.21.0")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return result;
                String json = response.body().string();
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();

                JsonArray entries = null;
                String groupName = "";

                // A ESPN v2 organiza a classificação dentro de 'children' (mesmo em ligas de grupo único como Brasileirão)
                if (root.has("children") && root.get("children").isJsonArray()) {
                    JsonArray children = root.getAsJsonArray("children");
                    int chosenIdx = 0;

                    // Se houver mais de uma conferência/grupo (ex: MLS Conferência Leste e Oeste),
                    // procura o grupo ao qual pertence o time da partida em exibição
                    if (children.size() > 1 && (homeTeam != null || awayTeam != null)) {
                        boolean found = false;
                        for (int c = 0; c < children.size(); c++) {
                            JsonObject ch = children.get(c).getAsJsonObject();
                            if (ch.has("standings") && ch.get("standings").isJsonObject()) {
                                JsonObject st = ch.getAsJsonObject("standings");
                                if (st.has("entries") && st.get("entries").isJsonArray()) {
                                    JsonArray chEntries = st.getAsJsonArray("entries");
                                    for (int e = 0; e < chEntries.size(); e++) {
                                        JsonObject entryObj = chEntries.get(e).getAsJsonObject();
                                        if (entryObj.has("team") && entryObj.get("team").isJsonObject()) {
                                            JsonObject tm = entryObj.getAsJsonObject("team");
                                            String tmName = optString(tm, "displayName", optString(tm, "name", ""));
                                            if (matchTeamName(tmName, homeTeam) || matchTeamName(tmName, awayTeam)) {
                                                chosenIdx = c;
                                                found = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            if (found) break;
                        }
                    }

                    if (chosenIdx < children.size()) {
                        JsonObject chosenChild = children.get(chosenIdx).getAsJsonObject();
                        groupName = optString(chosenChild, "name", "");
                        if (chosenChild.has("standings") && chosenChild.get("standings").isJsonObject()) {
                            JsonObject st = chosenChild.getAsJsonObject("standings");
                            if (st.has("entries") && st.get("entries").isJsonArray()) {
                                entries = st.getAsJsonArray("entries");
                            }
                        }
                    }
                } else if (root.has("standings") && root.get("standings").isJsonObject()) {
                    JsonObject st = root.getAsJsonObject("standings");
                    if (st.has("entries") && st.get("entries").isJsonArray()) {
                        entries = st.getAsJsonArray("entries");
                    }
                }

                // Para ligas que dividem temporadas em fases/estágios (ex: bra.3 - Série C Primeira Fase, Segunda Fase),
                // o endpoint padrão sem parâmetros não retorna 'children' na raiz. Buscamos a última temporada e estágio com tabela:
                if ((entries == null || entries.size() == 0) && root.has("seasons") && root.get("seasons").isJsonArray()) {
                    JsonArray seasonsArr = root.getAsJsonArray("seasons");
                    for (int si = 0; si < seasonsArr.size(); si++) {
                        JsonObject seasonObj = seasonsArr.get(si).getAsJsonObject();
                        int sYear = 0;
                        if (seasonObj.has("year")) sYear = seasonObj.get("year").getAsInt();
                        if (seasonObj.has("types") && seasonObj.get("types").isJsonArray()) {
                            JsonArray typesArr = seasonObj.getAsJsonArray("types");
                            for (int ti = 0; ti < typesArr.size(); ti++) {
                                JsonObject tObj = typesArr.get(ti).getAsJsonObject();
                                boolean hasSt = tObj.has("hasStandings") && tObj.get("hasStandings").getAsBoolean();
                                String tId = optString(tObj, "id", "1");
                                if (hasSt) {
                                    String subUrl = "https://site.api.espn.com/apis/v2/sports/soccer/" + league
                                            + "/standings?season=" + sYear + "&stage=" + tId + "&lang=pt&region=br";
                                    Request subReq = new Request.Builder().url(subUrl)
                                            .header("Accept", "*/*").header("User-Agent", "curl/8.21.0").build();
                                    try (Response subResp = httpClient.newCall(subReq).execute()) {
                                        if (subResp.isSuccessful() && subResp.body() != null) {
                                            JsonObject subRoot = JsonParser.parseString(subResp.body().string()).getAsJsonObject();
                                            if (subRoot.has("children") && subRoot.get("children").isJsonArray()) {
                                                JsonArray ch = subRoot.getAsJsonArray("children");
                                                if (ch.size() > 0) {
                                                    JsonObject child0 = ch.get(0).getAsJsonObject();
                                                    groupName = optString(child0, "name", "");
                                                    if (child0.has("standings") && child0.get("standings").isJsonObject()) {
                                                        JsonObject st = child0.getAsJsonObject("standings");
                                                        if (st.has("entries") && st.get("entries").isJsonArray()) {
                                                            JsonArray subEntries = st.getAsJsonArray("entries");
                                                            if (subEntries.size() > 0) {
                                                                entries = subEntries;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                        if (entries != null && entries.size() > 0) break;
                    }
                }

                if (entries == null || entries.size() == 0) return result;

                for (int i = 0; i < entries.size(); i++) {
                    JsonObject entry = entries.get(i).getAsJsonObject();
                    StandingEntry se = new StandingEntry();
                    se.position = i + 1;
                    se.groupName = groupName;

                    if (entry.has("team") && entry.get("team").isJsonObject()) {
                        JsonObject team = entry.getAsJsonObject("team");
                        se.teamName = optString(team, "displayName", optString(team, "name", ""));
                        se.teamAbbr = optString(team, "shortDisplayName", optString(team, "abbreviation", ""));
                        se.isHighlighted = matchTeamName(se.teamName, homeTeam) || matchTeamName(se.teamName, awayTeam);
                    }

                    // Estatísticas
                    if (entry.has("stats") && entry.get("stats").isJsonArray()) {
                        JsonArray stats = entry.getAsJsonArray("stats");
                        for (int j = 0; j < stats.size(); j++) {
                            JsonObject stat = stats.get(j).getAsJsonObject();
                            String name = optString(stat, "name", "").toLowerCase(Locale.ROOT);
                            int val = 0;
                            try {
                                if (stat.has("value") && !stat.get("value").isJsonNull()) {
                                    val = (int) Math.round(stat.get("value").getAsDouble());
                                }
                            } catch (Exception ignored) {}

                            switch (name) {
                                case "rank":
                                    if (val > 0) se.position = val;
                                    break;
                                case "gamesplayed":
                                case "gp":
                                    se.played = val;
                                    break;
                                case "wins":
                                case "w":
                                    se.wins = val;
                                    break;
                                case "ties":
                                case "d":
                                    se.draws = val;
                                    break;
                                case "losses":
                                case "l":
                                    se.losses = val;
                                    break;
                                case "pointsfor":
                                case "gf":
                                    se.goalsFor = val;
                                    break;
                                case "pointsagainst":
                                case "ga":
                                    se.goalsAgainst = val;
                                    break;
                                case "pointdifferential":
                                case "gd":
                                    se.goalDiff = val;
                                    break;
                                case "points":
                                case "pts":
                                    se.points = val;
                                    break;
                            }
                        }
                        if (se.goalDiff == 0 && (se.goalsFor > 0 || se.goalsAgainst > 0)) {
                            se.goalDiff = se.goalsFor - se.goalsAgainst;
                        }
                    }
                    result.add(se);
                }

                // Ordena por posição / pontos / vitórias / saldo / gols pró
                Collections.sort(result, (a, b) -> {
                    if (a.position > 0 && b.position > 0 && a.position != b.position) {
                        return Integer.compare(a.position, b.position);
                    }
                    if (a.points != b.points) {
                        return Integer.compare(b.points, a.points);
                    }
                    if (a.wins != b.wins) {
                        return Integer.compare(b.wins, a.wins);
                    }
                    if (a.goalDiff != b.goalDiff) {
                        return Integer.compare(b.goalDiff, a.goalDiff);
                    }
                    return Integer.compare(b.goalsFor, a.goalsFor);
                });

                // Normaliza numeração de posição 1..N
                for (int idx = 0; idx < result.size(); idx++) {
                    result.get(idx).position = idx + 1;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JOGOS DA RODADA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Busca os jogos da rodada COMPLETA da liga correspondente ao evento.
     * Usa o calendar da ESPN para identificar todos os dias que compõem a rodada
     * (ex: Sex+Sáb+Dom+Seg para rodada de fim de semana; Ter+Qua+Qui para midweek)
     * e agrega os eventos de cada dia num único retorno ordenado por horário.
     */
    public static List<RoundMatch> getRoundMatches(String competition, String homeTeam, String awayTeam) {
        List<RoundMatch> result = new ArrayList<>();
        String league = getEspnLeagueForCompetition(competition, homeTeam, awayTeam);
        if (league == null) return result;

        TimeZone tzBrasilia = TimeZone.getTimeZone("America/Sao_Paulo");
        SimpleDateFormat dayCodeFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dayCodeFmt.setTimeZone(tzBrasilia);

        // 1. Buscar scoreboard sem dates= para obter o calendário e o dia-padrão da liga
        String baseUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + league
                + "/scoreboard?lang=pt&region=br";
        try {
            Request initReq = new Request.Builder()
                    .url(baseUrl)
                    .header("Accept", "*/*")
                    .header("User-Agent", "curl/8.21.0")
                    .build();

            JsonObject initRoot;
            try (Response initResp = httpClient.newCall(initReq).execute()) {
                if (!initResp.isSuccessful() || initResp.body() == null) return result;
                initRoot = JsonParser.parseString(initResp.body().string()).getAsJsonObject();
            }

            // 2. Extrair as datas do calendário (calendarType=day → lista de strings ISO)
            //    Se o calendário for de objetos de fase (Champions, Libertadores), hasDateCalendar=false
            Set<String> calendarDateCodes = new HashSet<>();
            boolean hasDateCalendar = false;
            if (initRoot.has("leagues") && initRoot.get("leagues").isJsonArray()) {
                JsonArray leaguesArr = initRoot.getAsJsonArray("leagues");
                if (leaguesArr.size() > 0) {
                    JsonObject leagueObj = leaguesArr.get(0).getAsJsonObject();
                    if (leagueObj.has("calendar") && leagueObj.get("calendar").isJsonArray()) {
                        JsonArray cal = leagueObj.getAsJsonArray("calendar");
                        if (cal.size() > 0 && cal.get(0).isJsonPrimitive()) {
                            hasDateCalendar = true;
                            SimpleDateFormat isoDateFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
                            isoDateFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                            for (int i = 0; i < cal.size(); i++) {
                                try {
                                    Date calDate = isoDateFmt.parse(cal.get(i).getAsString());
                                    if (calDate != null) calendarDateCodes.add(dayCodeFmt.format(calDate));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // 3. Determinar a data-âncora: dia padrão retornado pela ESPN para esta liga
            Date anchorDate = new Date();
            if (initRoot.has("day") && initRoot.get("day").isJsonObject()) {
                String dayStr = optString(initRoot.getAsJsonObject("day"), "date", "");
                if (!dayStr.isEmpty()) {
                    try {
                        SimpleDateFormat ymdFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                        ymdFmt.setTimeZone(tzBrasilia);
                        Date parsed = ymdFmt.parse(dayStr);
                        if (parsed != null) anchorDate = parsed;
                    } catch (Exception ignored) {}
                }
            }

            // 4. Calcular janela da rodada com base no dia da semana da âncora:
            //    Fim de semana: Sex, Sáb, Dom, Seg  |  Semana: Ter, Qua, Qui
            Calendar anchorCal = Calendar.getInstance(tzBrasilia);
            anchorCal.setTime(anchorDate);
            int dow = anchorCal.get(Calendar.DAY_OF_WEEK); // Sun=1,Mon=2,...,Sat=7

            List<String> windowCodes = new ArrayList<>();
            boolean isWeekend = (dow == Calendar.FRIDAY || dow == Calendar.SATURDAY
                    || dow == Calendar.SUNDAY || dow == Calendar.MONDAY);

            if (isWeekend) {
                int offsetFromFri;
                if (dow == Calendar.FRIDAY) offsetFromFri = 0;
                else if (dow == Calendar.SATURDAY) offsetFromFri = 1;
                else if (dow == Calendar.SUNDAY) offsetFromFri = 2;
                else offsetFromFri = 3; // Monday
                Calendar friCal = Calendar.getInstance(tzBrasilia);
                friCal.setTime(anchorDate);
                friCal.add(Calendar.DAY_OF_YEAR, -offsetFromFri);
                for (int d = 0; d < 4; d++) {
                    windowCodes.add(dayCodeFmt.format(friCal.getTime()));
                    friCal.add(Calendar.DAY_OF_YEAR, 1);
                }
            } else {
                int offsetFromTue;
                if (dow == Calendar.TUESDAY) offsetFromTue = 0;
                else if (dow == Calendar.WEDNESDAY) offsetFromTue = 1;
                else offsetFromTue = 2; // Thursday
                Calendar tueCal = Calendar.getInstance(tzBrasilia);
                tueCal.setTime(anchorDate);
                tueCal.add(Calendar.DAY_OF_YEAR, -offsetFromTue);
                for (int d = 0; d < 3; d++) {
                    windowCodes.add(dayCodeFmt.format(tueCal.getTime()));
                    tueCal.add(Calendar.DAY_OF_YEAR, 1);
                }
            }

            // 5. Filtrar a janela pelo calendário (se disponível como datas)
            String anchorCode = dayCodeFmt.format(anchorDate);
            List<String> datesToFetch = new ArrayList<>();
            for (String code : windowCodes) {
                if (!hasDateCalendar || calendarDateCodes.contains(code)) {
                    datesToFetch.add(code);
                }
            }
            // Garante que o dia âncora sempre seja incluído
            if (!datesToFetch.contains(anchorCode)) {
                datesToFetch.add(0, anchorCode);
            }

            // 6. Coletar eventos de todos os dias da rodada, deduplicando por event ID
            LinkedHashMap<String, JsonObject> eventsById = new LinkedHashMap<>();

            // Aproveita os eventos já carregados para o dia âncora
            if (initRoot.has("events") && initRoot.get("events").isJsonArray()) {
                JsonArray anchorEvs = initRoot.getAsJsonArray("events");
                for (int i = 0; i < anchorEvs.size(); i++) {
                    JsonElement el = anchorEvs.get(i);
                    if (el.isJsonObject()) {
                        JsonObject ev = el.getAsJsonObject();
                        String evId = optString(ev, "id", "");
                        if (!evId.isEmpty()) eventsById.put(evId, ev);
                    }
                }
            }

            // Busca paralela para os demais dias da rodada
            List<String> extraDates = new ArrayList<>();
            for (String code : datesToFetch) {
                if (!code.equals(anchorCode)) extraDates.add(code);
            }
            if (!extraDates.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(extraDates.size(), 4));
                List<Future<JsonArray>> futures = new ArrayList<>();
                for (final String dateCode : extraDates) {
                    final String fetchUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
                            + league + "/scoreboard?dates=" + dateCode + "&lang=pt&region=br";
                    futures.add(pool.submit(() -> {
                        try {
                            Request req = new Request.Builder()
                                    .url(fetchUrl)
                                    .header("Accept", "*/*")
                                    .header("User-Agent", "curl/8.21.0")
                                    .build();
                            try (Response resp = httpClient.newCall(req).execute()) {
                                if (!resp.isSuccessful() || resp.body() == null) return new JsonArray();
                                JsonObject r = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                                return r.has("events") && r.get("events").isJsonArray()
                                        ? r.getAsJsonArray("events") : new JsonArray();
                            }
                        } catch (Exception e) {
                            return new JsonArray();
                        }
                    }));
                }
                for (Future<JsonArray> f : futures) {
                    try {
                        JsonArray evs = f.get(8, TimeUnit.SECONDS);
                        for (int i = 0; i < evs.size(); i++) {
                            JsonElement el = evs.get(i);
                            if (el.isJsonObject()) {
                                JsonObject ev = el.getAsJsonObject();
                                String evId = optString(ev, "id", "");
                                if (!evId.isEmpty()) eventsById.put(evId, ev);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                pool.shutdown();
            }

            // 7. Formatar os eventos coletados
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
            timeFmt.setTimeZone(tzBrasilia);
            SimpleDateFormat dayNameFmt = new SimpleDateFormat("EEE dd/MM", new Locale("pt", "BR"));
            dayNameFmt.setTimeZone(tzBrasilia);
            SimpleDateFormat isofmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            isofmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String todayCode = dayCodeFmt.format(new Date());
            String tomorrowCode = dayCodeFmt.format(new Date(System.currentTimeMillis() + 86400000L));

            for (JsonObject ev : eventsById.values()) {
                if (!ev.has("competitions") || !ev.get("competitions").isJsonArray()) continue;
                JsonArray comps = ev.getAsJsonArray("competitions");
                if (comps.size() == 0) continue;
                JsonObject comp = comps.get(0).getAsJsonObject();

                RoundMatch rm = new RoundMatch();

                // Estado e clock
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
                rm.state = state;

                // Horário e dia de início
                String startDate = optString(ev, "date", "");
                String timeStr = "--:--";
                String dayLabel = "";
                long startMs = 0;
                try {
                    if (!startDate.isEmpty()) {
                        Date d = isofmt.parse(startDate);
                        if (d != null) {
                            startMs = d.getTime();
                            timeStr = timeFmt.format(d);
                            String dCode = dayCodeFmt.format(d);
                            if (dCode.equals(todayCode)) {
                                dayLabel = "Hoje";
                            } else if (dCode.equals(tomorrowCode)) {
                                dayLabel = "Amanhã";
                            } else {
                                dayLabel = dayNameFmt.format(d);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                rm.matchTime = timeStr;
                rm.startMs = startMs;

                // Times e placar
                if (comp.has("competitors") && comp.get("competitors").isJsonArray()) {
                    JsonArray competitors = comp.getAsJsonArray("competitors");
                    String hName = "", aName = "", hScore = "", aScore = "";
                    for (int k = 0; k < competitors.size(); k++) {
                        JsonObject c = competitors.get(k).getAsJsonObject();
                        String ha = optString(c, "homeAway", "");
                        String sc = optString(c, "score", "");
                        String name = "";
                        if (c.has("team") && c.get("team").isJsonObject()) {
                            JsonObject tm = c.getAsJsonObject("team");
                            name = optString(tm, "shortDisplayName", optString(tm, "displayName", ""));
                        }
                        if ("home".equalsIgnoreCase(ha)) { hName = name; hScore = sc; }
                        else { aName = name; aScore = sc; }
                    }
                    rm.homeTeam = hName;
                    rm.awayTeam = aName;

                    if ("in".equalsIgnoreCase(state)) {
                        rm.score = (hScore.isEmpty() ? "0" : hScore) + " x " + (aScore.isEmpty() ? "0" : aScore);
                        rm.statusLabel = "AO VIVO" + (!clock.isEmpty() ? " • " + clock : "");
                    } else if ("post".equalsIgnoreCase(state)) {
                        rm.score = (hScore.isEmpty() ? "0" : hScore) + " x " + (aScore.isEmpty() ? "0" : aScore);
                        rm.statusLabel = "Encerrado" + (!dayLabel.isEmpty() ? " • " + dayLabel : "");
                    } else {
                        rm.score = "vs";
                        rm.statusLabel = (!dayLabel.isEmpty() ? dayLabel + " " : "") + timeStr;
                    }

                    // Marca se é a partida sendo assistida
                    rm.isCurrent = (matchTeamName(hName, homeTeam) && matchTeamName(aName, awayTeam))
                            || (matchTeamName(hName, awayTeam) && matchTeamName(aName, homeTeam));
                }

                if (rm.homeTeam != null && !rm.homeTeam.isEmpty()) {
                    result.add(rm);
                }
            }

            // 8. Ordenar por horário de início
            Collections.sort(result, (a, b) -> Long.compare(a.startMs, b.startMs));

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Busca o calendário completo de uma liga: últimas 6 semanas + próximas 4 semanas.
     * Usa o calendar ESPN para filtrar datas relevantes e faz requests paralelos.
     * @param leagueSlug slug ESPN (ex: "bra.1")
     * @param liveEvents lista de SportsEvent ativos para match EPG (pode ser null)
     */
    public static List<ScheduleDay> getFullLeagueSchedule(String leagueSlug, List<com.andplay.app.model.SportsEvent> liveEvents) {
        List<ScheduleDay> result = new ArrayList<>();
        if (leagueSlug == null || leagueSlug.isEmpty()) return result;

        TimeZone tzBrasilia = TimeZone.getTimeZone("America/Sao_Paulo");
        SimpleDateFormat dayCodeFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dayCodeFmt.setTimeZone(tzBrasilia);
        SimpleDateFormat isoDateFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        isoDateFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        try {
            // 1. Buscar o calendar da liga (sem dates= para pegar metadados)
            String baseUrl = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + leagueSlug
                    + "/scoreboard?lang=pt&region=br";
            Request initReq = new Request.Builder().url(baseUrl)
                    .header("Accept", "*/*").header("User-Agent", "curl/8.21.0").build();
            JsonObject initRoot;
            try (Response initResp = httpClient.newCall(initReq).execute()) {
                if (!initResp.isSuccessful() || initResp.body() == null) return result;
                initRoot = JsonParser.parseString(initResp.body().string()).getAsJsonObject();
            }

            // 2. Filtrar datas do calendar: -42 dias até +28 dias a partir de hoje
            long now = System.currentTimeMillis();
            long winStart = now - 42L * 86400000L;
            long winEnd   = now + 28L * 86400000L;

            Set<String> calendarDateCodes = new HashSet<>();
            boolean hasDateCalendar = false;
            if (initRoot.has("leagues") && initRoot.get("leagues").isJsonArray()) {
                JsonArray leaguesArr = initRoot.getAsJsonArray("leagues");
                if (leaguesArr.size() > 0) {
                    JsonObject leagueObj = leaguesArr.get(0).getAsJsonObject();
                    if (leagueObj.has("calendar") && leagueObj.get("calendar").isJsonArray()) {
                        JsonArray cal = leagueObj.getAsJsonArray("calendar");
                        if (cal.size() > 0 && cal.get(0).isJsonPrimitive()) {
                            hasDateCalendar = true;
                            List<String> allCalDates = new ArrayList<>();
                            for (int i = 0; i < cal.size(); i++) {
                                try {
                                    Date calDate = isoDateFmt.parse(cal.get(i).getAsString());
                                    if (calDate != null) {
                                        String dCode = dayCodeFmt.format(calDate);
                                        allCalDates.add(dCode);
                                        if (calDate.getTime() >= winStart && calDate.getTime() <= winEnd) {
                                            calendarDateCodes.add(dCode);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                            if (calendarDateCodes.isEmpty() && !allCalDates.isEmpty()) {
                                int startIdx = Math.max(0, allCalDates.size() - 15);
                                for (int i = startIdx; i < allCalDates.size(); i++) {
                                    calendarDateCodes.add(allCalDates.get(i));
                                }
                            }
                        }
                    }
                }
            }

            // 3. Se não há calendar de datas (ex: Copa, UCL), usar últimas 3 semanas + próximas 2 semanas
            //    gerando datas candidatas e verificando quais têm eventos
            List<String> datesToFetch = new ArrayList<>();
            if (hasDateCalendar) {
                datesToFetch.addAll(calendarDateCodes);
            } else {
                // Gerar todas as datas da janela
                Calendar c = Calendar.getInstance(tzBrasilia);
                c.setTimeInMillis(winStart);
                while (c.getTimeInMillis() <= winEnd) {
                    datesToFetch.add(dayCodeFmt.format(c.getTime()));
                    c.add(Calendar.DAY_OF_YEAR, 1);
                }
            }

            // Ordenar cronologicamente
            Collections.sort(datesToFetch);

            // 4. Busca paralela (até 8 threads) para todos os dias
            if (datesToFetch.isEmpty()) return result;

            ExecutorService pool = Executors.newFixedThreadPool(Math.min(datesToFetch.size(), 8));
            List<Future<JsonArray>> futures = new ArrayList<>();
            for (final String dateCode : datesToFetch) {
                final String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
                        + leagueSlug + "/scoreboard?dates=" + dateCode + "&lang=pt&region=br";
                futures.add(pool.submit(() -> {
                    try {
                        Request req = new Request.Builder().url(url)
                                .header("Accept", "*/*").header("User-Agent", "curl/8.21.0").build();
                        try (Response resp = httpClient.newCall(req).execute()) {
                            if (!resp.isSuccessful() || resp.body() == null) return new JsonArray();
                            JsonObject r = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                            return r.has("events") && r.get("events").isJsonArray()
                                    ? r.getAsJsonArray("events") : new JsonArray();
                        }
                    } catch (Exception e) { return new JsonArray(); }
                }));
            }

            // 5. Coletar e agrupar por data
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
            timeFmt.setTimeZone(tzBrasilia);
            SimpleDateFormat dayNameFmt = new SimpleDateFormat("EEE dd/MM", new Locale("pt", "BR"));
            dayNameFmt.setTimeZone(tzBrasilia);
            String todayCode = dayCodeFmt.format(new Date());
            String tomorrowCode = dayCodeFmt.format(new Date(System.currentTimeMillis() + 86400000L));

            // Pré-processar live events para match EPG
            Map<String, com.andplay.app.model.SportsEvent> liveByKey = new LinkedHashMap<>();
            if (liveEvents != null) {
                for (com.andplay.app.model.SportsEvent ev : liveEvents) {
                    if (ev.homeName != null && ev.awayName != null) {
                        String key = normalize(ev.homeName) + "_" + normalize(ev.awayName);
                        liveByKey.put(key, ev);
                    }
                }
            }

            LinkedHashMap<String, ScheduleDay> dayMap = new LinkedHashMap<>();
            for (int fi = 0; fi < futures.size(); fi++) {
                String dateCode = datesToFetch.get(fi);
                try {
                    JsonArray evs = futures.get(fi).get(10, TimeUnit.SECONDS);
                    for (int i = 0; i < evs.size(); i++) {
                        JsonElement el = evs.get(i);
                        if (!el.isJsonObject()) continue;
                        JsonObject ev = el.getAsJsonObject();
                        String evId = optString(ev, "id", "");
                        if (evId.isEmpty()) continue;
                        if (!ev.has("competitions") || !ev.get("competitions").isJsonArray()) continue;
                        JsonArray comps = ev.getAsJsonArray("competitions");
                        if (comps.size() == 0) continue;
                        JsonObject comp = comps.get(0).getAsJsonObject();

                        RoundMatch rm = new RoundMatch();

                        String state = "", clock = "";
                        if (comp.has("status") && comp.get("status").isJsonObject()) {
                            JsonObject st = comp.getAsJsonObject("status");
                            if (st.has("type") && st.get("type").isJsonObject()) {
                                JsonObject t = st.getAsJsonObject("type");
                                state = optString(t, "state", "");
                                clock = optString(t, "shortDetail", "");
                            }
                        }
                        rm.state = state;

                        String startDateStr = optString(ev, "date", "");
                        String timeStr = "--:--", dayLabel = "";
                        long startMs = 0;
                        try {
                            if (!startDateStr.isEmpty()) {
                                Date d = isoDateFmt.parse(startDateStr);
                                if (d != null) {
                                    startMs = d.getTime();
                                    timeStr = timeFmt.format(d);
                                    String dCode = dayCodeFmt.format(d);
                                    if (dCode.equals(todayCode)) dayLabel = "Hoje";
                                    else if (dCode.equals(tomorrowCode)) dayLabel = "Amanhã";
                                    else dayLabel = dayNameFmt.format(d);
                                    dateCode = dCode; // normalizar para o dia real do evento
                                }
                            }
                        } catch (Exception ignored) {}
                        rm.matchTime = timeStr;
                        rm.startMs = startMs;

                        if (comp.has("competitors") && comp.get("competitors").isJsonArray()) {
                            JsonArray competitors = comp.getAsJsonArray("competitors");
                            String hName = "", aName = "", hScore = "", aScore = "";
                            for (int k = 0; k < competitors.size(); k++) {
                                JsonObject c = competitors.get(k).getAsJsonObject();
                                String ha = optString(c, "homeAway", "");
                                String sc = optString(c, "score", "");
                                String name = "";
                                if (c.has("team") && c.get("team").isJsonObject()) {
                                    JsonObject tm = c.getAsJsonObject("team");
                                    name = optString(tm, "shortDisplayName", optString(tm, "displayName", ""));
                                }
                                if ("home".equalsIgnoreCase(ha)) { hName = name; hScore = sc; }
                                else { aName = name; aScore = sc; }
                            }
                            rm.homeTeam = hName;
                            rm.awayTeam = aName;

                            if ("in".equalsIgnoreCase(state)) {
                                rm.score = (hScore.isEmpty() ? "0" : hScore) + " x " + (aScore.isEmpty() ? "0" : aScore);
                                rm.statusLabel = "● AO VIVO" + (!clock.isEmpty() ? " " + clock : "");
                            } else if ("post".equalsIgnoreCase(state)) {
                                rm.score = (hScore.isEmpty() ? "0" : hScore) + " x " + (aScore.isEmpty() ? "0" : aScore);
                                rm.statusLabel = "Encerrado";
                            } else {
                                rm.score = "vs";
                                rm.statusLabel = timeStr;
                            }

                            // Match EPG
                            String key1 = normalize(hName) + "_" + normalize(aName);
                            String key2 = normalize(aName) + "_" + normalize(hName);
                            com.andplay.app.model.SportsEvent matched = liveByKey.get(key1);
                            if (matched == null) matched = liveByKey.get(key2);
                            if (matched != null) {
                                rm.channelId = matched.id;
                                rm.channelName = formatCandidateChannelsLabel(matched.candidateChannels);
                            }
                            rm.isCurrent = false;
                        }

                        if (rm.homeTeam != null && !rm.homeTeam.isEmpty()) {
                            ScheduleDay day = dayMap.get(dateCode);
                            if (day == null) {
                                day = new ScheduleDay();
                                day.dateCode = dateCode;
                                day.dateMs = startMs;
                                day.isToday = dateCode.equals(todayCode);
                                day.dateLabel = dayLabel.isEmpty() ? dateCode : dayLabel;
                                dayMap.put(dateCode, day);
                            }
                            day.matches.add(rm);
                        }
                    }
                } catch (Exception ignored) {}
            }
            pool.shutdown();

            // 6. Ordenar partidas de cada dia por horário e retornar dias ordenados
            List<ScheduleDay> days = new ArrayList<>(dayMap.values());
            Collections.sort(days, (a, b) -> a.dateCode.compareTo(b.dateCode));
            for (ScheduleDay day : days) {
                Collections.sort(day.matches, (a, b) -> Long.compare(a.startMs, b.startMs));
            }
            result.addAll(days);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static boolean isRealTvChannel(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.contains("opcao") || n.contains("opção")
                || n.contains("embed") || n.contains("stream") || n.contains("server")
                || n.contains("web") || n.contains("player")) {
            return false;
        }
        return n.contains("sportv") || n.contains("premiere") || n.contains("espn")
                || n.contains("xsport") || n.contains("globo") || n.contains("band") || n.contains("sbt")
                || n.contains("record") || n.contains("cazé") || n.contains("caze")
                || n.contains("tnt") || n.contains("combate") || n.contains("max")
                || n.contains("prime") || n.contains("paramount") || n.contains("dazn")
                || n.contains("goat") || n.contains("nosso futebol") || n.contains("bandsports");
    }

    public static String formatCandidateChannelsLabel(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return "AO VIVO";

        List<String> realTv = new ArrayList<>();
        List<String> webOptions = new ArrayList<>();

        for (String cand : candidates) {
            if (cand == null || cand.trim().isEmpty()) continue;
            String clean = cand.trim();
            if (isRealTvChannel(clean)) {
                if (!realTv.contains(clean)) realTv.add(clean);
            } else {
                if (!webOptions.contains(clean)) webOptions.add(clean);
            }
        }

        if (!realTv.isEmpty()) {
            if (realTv.size() == 1) {
                return realTv.get(0);
            } else if (realTv.size() == 2) {
                return realTv.get(0) + " • " + realTv.get(1);
            } else {
                return realTv.get(0) + " • " + realTv.get(1) + " (+" + (realTv.size() - 2) + ")";
            }
        }

        if (!webOptions.isEmpty()) {
            return webOptions.get(0);
        }

        return "AO VIVO";
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("[^a-z0-9]", "");
    }

    public static class OnlineSubtitle {
        public String id;
        public String url;
        public String lang;
        public String format;
        public String release;

        @Override
        public String toString() {
            return lang + (release != null && !release.isEmpty() ? " (" + release + ")" : "");
        }
    }

    /**
     * Busca legendas online em português (PT-BR e PT-PT) via Cinemeta + OpenSubtitles v3 (Stremio).
     */
    public static List<OnlineSubtitle> searchOnlineSubtitles(String rawTitle, String mediaType, int season, int episode) {
        List<OnlineSubtitle> results = new ArrayList<>();
        if (rawTitle == null || rawTitle.trim().isEmpty()) return results;

        try {
            // Limpa título de tags e metadados
            String cleanQuery = rawTitle
                    .replaceAll("\\[.*?\\]", "")
                    .replaceAll("\\(.*?\\)", "")
                    .replaceAll("(?i)\\b(4k|dublado|legendado|completo|temporada|episodio|hd|fhd)\\b", "")
                    .trim();

            if (cleanQuery.isEmpty()) cleanQuery = rawTitle.trim();

            String type = "series".equalsIgnoreCase(mediaType) ? "series" : "movie";

            // 1. Busca Cinemeta para descobrir o IMDB ID
            String searchUrl = "https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8") + ".json";
            Request cinemetaReq = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            String imdbId = null;
            try (Response resp = httpClient.newCall(cinemetaReq).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonObject json = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                    if (json.has("metas") && json.get("metas").isJsonArray()) {
                        JsonArray metas = json.getAsJsonArray("metas");
                        if (metas.size() > 0) {
                            JsonObject first = metas.get(0).getAsJsonObject();
                            if (first.has("imdb_id") && !first.get("imdb_id").isJsonNull()) {
                                imdbId = first.get("imdb_id").getAsString();
                            } else if (first.has("id") && !first.get("id").isJsonNull()) {
                                imdbId = first.get("id").getAsString();
                            }
                        }
                    }
                }
            }

            if (imdbId == null || imdbId.isEmpty()) return results;

            // 2. Busca legendas no OpenSubtitles v3
            String subQuery = type.equals("series") && season > 0 && episode > 0
                    ? imdbId + ":" + season + ":" + episode
                    : imdbId;

            String subUrl = "https://opensubtitles-v3.strem.io/subtitles/" + type + "/" + subQuery + ".json";
            Request subReq = new Request.Builder()
                    .url(subUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response resp = httpClient.newCall(subReq).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonObject json = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                    if (json.has("subtitles") && json.get("subtitles").isJsonArray()) {
                        JsonArray subs = json.getAsJsonArray("subtitles");
                        for (int i = 0; i < subs.size(); i++) {
                            JsonObject s = subs.get(i).getAsJsonObject();
                            String lang = s.has("lang") ? optString(s, "lang", "").toLowerCase(Locale.ROOT) : "";
                            // Filtrar legendas em português
                            if (lang.equals("pob") || lang.equals("por") || lang.startsWith("po") || lang.equals("pt")) {
                                OnlineSubtitle sub = new OnlineSubtitle();
                                sub.id = s.has("id") ? optString(s, "id", String.valueOf(i)) : String.valueOf(i);
                                sub.url = optString(s, "url", "");
                                sub.format = optString(s, "format", "srt");
                                sub.lang = lang.equals("pob") ? "Português (Brasil)" : "Português";
                                sub.release = optString(s, "release", "");
                                if (!sub.url.isEmpty()) {
                                    results.add(sub);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }
}

