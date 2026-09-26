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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final String[] ESPN_SCOREBOARDS = new String[] {
            "https://site.api.espn.com/apis/site/v2/sports/soccer/bra.1/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/conmebol.libertadores/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard"
    };

    private static String formatIsoMatchTime(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "Hoje";
        try {
            String cleaned = isoDate.replace("Z", "+0000");
            SimpleDateFormat inFmt = new SimpleDateFormat(
                    isoDate.contains(".") ? "yyyy-MM-dd'T'HH:mm:ss.SSSZ" : (cleaned.length() > 22 ? "yyyy-MM-dd'T'HH:mm:ssZ" : "yyyy-MM-dd'T'HH:mmZ"),
                    Locale.US
            );
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = inFmt.parse(cleaned);
            if (date != null) {
                SimpleDateFormat outFmt = new SimpleDateFormat("HH:mm", Locale.US);
                outFmt.setTimeZone(TimeZone.getTimeZone("America/Sao_Paulo"));
                return outFmt.format(date);
            }
        } catch (Exception ignored) {}
        return "Hoje";
    }

    private static String formatLeagueName(String rawLeague) {
        if (rawLeague == null) return "Futebol";
        String low = rawLeague.toLowerCase(Locale.ROOT);
        if (low.contains("brazilian") || low.contains("serie a") || low.contains("brasileir")) return "Brasileirão Série A";
        if (low.contains("libertadores")) return "Libertadores";
        if (low.contains("champions")) return "Champions League";
        if (low.contains("premier")) return "Premier League";
        if (low.contains("laliga") || low.contains("spanish")) return "La Liga";
        if (low.contains("copa do brasil")) return "Copa do Brasil";
        if (low.contains("sul-americana") || low.contains("sudamericana")) return "Sul-Americana";
        return rawLeague;
    }

    private static List<SportsEvent> fetchDynamicLiveSports() {
        List<SportsEvent> list = new ArrayList<>();
        for (String url : ESPN_SCOREBOARDS) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Android TV)")
                        .build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) continue;
                    String json = response.body().string();
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    if (!root.has("events")) continue;

                    String leagueName = "Futebol";
                    if (root.has("leagues")) {
                        JsonArray lArr = root.getAsJsonArray("leagues");
                        if (lArr.size() > 0 && lArr.get(0).isJsonObject()) {
                            leagueName = formatLeagueName(optString(lArr.get(0).getAsJsonObject(), "name", "Futebol"));
                        }
                    }

                    JsonArray events = root.getAsJsonArray("events");
                    for (int i = 0; i < events.size(); i++) {
                        JsonElement evElem = events.get(i);
                        if (!evElem.isJsonObject()) continue;
                        JsonObject evObj = evElem.getAsJsonObject();

                        String evId = optString(evObj, "id", "");
                        String evDate = optString(evObj, "date", "");
                        if (!evObj.has("competitions")) continue;
                        JsonArray comps = evObj.getAsJsonArray("competitions");
                        if (comps.size() == 0 || !comps.get(0).isJsonObject()) continue;
                        JsonObject comp = comps.get(0).getAsJsonObject();

                        boolean isLive = false;
                        String statusClock = "";
                        String state = "pre";
                        if (comp.has("status") && comp.get("status").isJsonObject()) {
                            JsonObject status = comp.getAsJsonObject("status");
                            statusClock = optString(status, "displayClock", "");
                            if (status.has("type") && status.get("type").isJsonObject()) {
                                state = optString(status.getAsJsonObject("type"), "state", "pre");
                                isLive = "in".equalsIgnoreCase(state);
                            }
                        }

                        if (!comp.has("competitors")) continue;
                        JsonArray competitors = comp.getAsJsonArray("competitors");
                        String homeName = "", awayName = "", homeLogo = "", awayLogo = "";
                        String homeScore = "", awayScore = "";

                        for (int c = 0; c < competitors.size(); c++) {
                            JsonObject competitor = competitors.get(c).getAsJsonObject();
                            String homeAway = optString(competitor, "homeAway", "");
                            String score = optString(competitor, "score", "");
                            JsonObject team = competitor.has("team") && competitor.get("team").isJsonObject() ? competitor.getAsJsonObject("team") : null;
                            String tName = team != null ? optString(team, "displayName", "") : "";
                            String tLogo = team != null ? optString(team, "logo", "") : "";

                            if ("home".equalsIgnoreCase(homeAway)) {
                                homeName = tName;
                                homeLogo = tLogo;
                                homeScore = score;
                            } else {
                                awayName = tName;
                                awayLogo = tLogo;
                                awayScore = score;
                            }
                        }

                        if (homeName.isEmpty() || awayName.isEmpty()) continue;

                        SportsEvent ev = new SportsEvent();
                        ev.id = "espn_" + evId;
                        ev.league = leagueName;
                        ev.isLive = isLive;
                        ev.homeLogo = homeLogo;
                        ev.awayLogo = awayLogo;

                        if (isLive) {
                            ev.matchTime = !statusClock.isEmpty() ? statusClock : "AO VIVO";
                            if (!homeScore.isEmpty() && !awayScore.isEmpty()) {
                                ev.name = homeName + " " + homeScore + " x " + awayScore + " " + awayName;
                            } else {
                                ev.name = homeName + " x " + awayName;
                            }
                        } else if ("post".equalsIgnoreCase(state)) {
                            ev.matchTime = (!homeScore.isEmpty() && !awayScore.isEmpty())
                                    ? "Fim (" + homeScore + "x" + awayScore + ")" : "Finalizado";
                            ev.name = homeName + " x " + awayName;
                        } else {
                            ev.matchTime = formatIsoMatchTime(evDate);
                            ev.name = homeName + " x " + awayName;
                        }

                        // Stream Fallbacks (StreamVerde primário, RDCanais secundário)
                        boolean isSouthAmerica = leagueName.contains("Brasileirão") || leagueName.contains("Libertadores") || leagueName.contains("Brasil");
                        if (isSouthAmerica) {
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (Premiere)", "https://streamverde.net/canais/premiere-1/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (SporTV)", "https://streamverde.net/canais/sportv/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (Globo SP)", "https://streamverde.net/canais/globo-sp/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (Cazé TV)", "https://streamverde.net/canais/cazetv-1/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (Premiere HD)", "https://rdcanais.net/premiere", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (SporTV HD)", "https://rdcanais.net/sportv", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (Globo SP)", "https://rdcanais.net/globosp", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (Cazé TV)", "https://rdcanais.net/cazetv", true));
                        } else {
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (ESPN)", "https://streamverde.net/canais/espn/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (ESPN 4)", "https://streamverde.net/canais/espn-4/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (SporTV 2)", "https://streamverde.net/canais/sportv-2/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("StreamVerde (TNT)", "https://streamverde.net/canais/tnt/embed/", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (ESPN HD)", "https://rdcanais.net/espn", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (ESPN 4 HD)", "https://rdcanais.net/espn4", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (SporTV 2 HD)", "https://rdcanais.net/sportv2", true));
                            ev.fallbacks.add(new Channel.StreamFallback("RDCanais (TNT HD)", "https://rdcanais.net/tnt", true));
                        }

                        list.add(ev);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Ordena: Ao Vivo primeiro, depois Em Breve (pre), depois Finalizados (post)
        Collections.sort(list, (e1, e2) -> {
            if (e1.isLive != e2.isLive) return e1.isLive ? -1 : 1;
            boolean e1Post = e1.matchTime != null && e1.matchTime.startsWith("Fim");
            boolean e2Post = e2.matchTime != null && e2.matchTime.startsWith("Fim");
            if (e1Post != e2Post) return e1Post ? 1 : -1;
            return 0;
        });

        // Adiciona evento de MMA / UFC garantido
        SportsEvent evUfc = new SportsEvent();
        evUfc.id = "sport_ufc_main_event";
        evUfc.name = "UFC Fight Night: Card Principal";
        evUfc.league = "MMA / Artes Marciais";
        evUfc.matchTime = "21:00";
        evUfc.isLive = false;
        evUfc.homeLogo = "https://reidosembeds.online/img/combate.png";
        evUfc.awayLogo = "https://reidosembeds.online/img/combate.png";
        evUfc.fallbacks.add(new Channel.StreamFallback("StreamVerde (Combate)", "https://streamverde.net/canais/combate/embed/", true));
        evUfc.fallbacks.add(new Channel.StreamFallback("RDCanais (Combate HD)", "https://rdcanais.net/combate", true));
        list.add(evUfc);

        return list;
    }

    public static List<SportsEvent> getDefaultSportsFallbacks() {
        List<SportsEvent> list = new ArrayList<>();

        // Partida 1: Grêmio x Palmeiras
        SportsEvent ev1 = new SportsEvent();
        ev1.id = "match_br_1";
        ev1.name = "Grêmio x Palmeiras";
        ev1.league = "Brasileirão Série A";
        ev1.matchTime = "AO VIVO";
        ev1.isLive = true;
        ev1.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9768.png";
        ev1.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/10283.png";
        ev1.fallbacks.add(new Channel.StreamFallback("StreamVerde (Premiere)", "https://streamverde.net/canais/premiere-1/embed/", true));
        ev1.fallbacks.add(new Channel.StreamFallback("StreamVerde (SporTV)", "https://streamverde.net/canais/sportv/embed/", true));
        ev1.fallbacks.add(new Channel.StreamFallback("RDCanais (Premiere HD)", "https://rdcanais.net/premiere", true));
        ev1.fallbacks.add(new Channel.StreamFallback("RDCanais (SporTV HD)", "https://rdcanais.net/sportv", true));
        list.add(ev1);

        // Partida 2: Palmeiras x Flamengo
        SportsEvent ev2 = new SportsEvent();
        ev2.id = "match_br_2";
        ev2.name = "Palmeiras x Flamengo";
        ev2.league = "Brasileirão Série A";
        ev2.matchTime = "AO VIVO";
        ev2.isLive = true;
        ev2.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/10283.png";
        ev2.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9770.png";
        ev2.fallbacks.add(new Channel.StreamFallback("StreamVerde (Premiere)", "https://streamverde.net/canais/premiere-1/embed/", true));
        ev2.fallbacks.add(new Channel.StreamFallback("StreamVerde (Globo SP)", "https://streamverde.net/canais/globo-sp/embed/", true));
        ev2.fallbacks.add(new Channel.StreamFallback("RDCanais (Premiere HD)", "https://rdcanais.net/premiere", true));
        ev2.fallbacks.add(new Channel.StreamFallback("RDCanais (Globo SP)", "https://rdcanais.net/globosp", true));
        list.add(ev2);

        // Partida 3: Corinthians x São Paulo (Majestoso)
        SportsEvent ev3 = new SportsEvent();
        ev3.id = "match_br_3";
        ev3.name = "Corinthians x São Paulo";
        ev3.league = "Brasileirão Série A (Majestoso)";
        ev3.matchTime = "18:30";
        ev3.isLive = true;
        ev3.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9808.png";
        ev3.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/10277.png";
        ev3.fallbacks.add(new Channel.StreamFallback("StreamVerde (Premiere 2)", "https://streamverde.net/canais/premiere-2/embed/", true));
        ev3.fallbacks.add(new Channel.StreamFallback("StreamVerde (SporTV 2)", "https://streamverde.net/canais/sportv-2/embed/", true));
        ev3.fallbacks.add(new Channel.StreamFallback("RDCanais (Premiere 2)", "https://rdcanais.net/premiere2", true));
        ev3.fallbacks.add(new Channel.StreamFallback("RDCanais (SporTV 2)", "https://rdcanais.net/sportv2", true));
        list.add(ev3);

        // Partida 4: Manchester City x Sunderland
        SportsEvent ev4 = new SportsEvent();
        ev4.id = "match_br_4";
        ev4.name = "Manchester City x Sunderland";
        ev4.league = "Premier League";
        ev4.matchTime = "10:00";
        ev4.isLive = false;
        ev4.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/8456.png";
        ev4.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/8472.png";
        ev4.fallbacks.add(new Channel.StreamFallback("StreamVerde (ESPN)", "https://streamverde.net/canais/espn/embed/", true));
        ev4.fallbacks.add(new Channel.StreamFallback("StreamVerde (TNT)", "https://streamverde.net/canais/tnt/embed/", true));
        ev4.fallbacks.add(new Channel.StreamFallback("RDCanais (ESPN HD)", "https://rdcanais.net/espn", true));
        ev4.fallbacks.add(new Channel.StreamFallback("RDCanais (TNT Sports)", "https://rdcanais.net/tnt", true));
        list.add(ev4);

        // Partida 5: Real Madrid x Barcelona (El Clásico)
        SportsEvent ev5 = new SportsEvent();
        ev5.id = "match_br_5";
        ev5.name = "Real Madrid x Barcelona";
        ev5.league = "La Liga / Champions League";
        ev5.matchTime = "16:00";
        ev5.isLive = false;
        ev5.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/8633.png";
        ev5.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/8634.png";
        ev5.fallbacks.add(new Channel.StreamFallback("StreamVerde (ESPN)", "https://streamverde.net/canais/espn/embed/", true));
        ev5.fallbacks.add(new Channel.StreamFallback("StreamVerde (ESPN 4)", "https://streamverde.net/canais/espn-4/embed/", true));
        ev5.fallbacks.add(new Channel.StreamFallback("RDCanais (ESPN HD)", "https://rdcanais.net/espn", true));
        ev5.fallbacks.add(new Channel.StreamFallback("RDCanais (ESPN 4 HD)", "https://rdcanais.net/espn4", true));
        list.add(ev5);

        // Partida 6: UFC Fight Night
        SportsEvent ev6 = new SportsEvent();
        ev6.id = "sport_ufc_main_event";
        ev6.name = "UFC Fight Night: Card Principal";
        ev6.league = "MMA / Artes Marciais";
        ev6.matchTime = "21:00";
        ev6.isLive = false;
        ev6.homeLogo = "https://reidosembeds.online/img/combate.png";
        ev6.awayLogo = "https://reidosembeds.online/img/combate.png";
        ev6.fallbacks.add(new Channel.StreamFallback("StreamVerde (Combate)", "https://streamverde.net/canais/combate/embed/", true));
        ev6.fallbacks.add(new Channel.StreamFallback("RDCanais (Combate HD)", "https://rdcanais.net/combate", true));
        list.add(ev6);

        return list;
    }

    public static List<SportsEvent> getLiveSports() {
        try {
            List<SportsEvent> dynamicList = fetchDynamicLiveSports();
            if (dynamicList != null && dynamicList.size() > 1) {
                return dynamicList;
            }
        } catch (Exception ignored) {}
        return getDefaultSportsFallbacks();
    }
}
