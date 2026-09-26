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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            Type type = new TypeToken<List<Channel>>() {}.getType();
            List<Channel> channels = gson.fromJson(reader, type);
            reader.close();
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
        List<SportsEvent> list = new ArrayList<>();
        // Fallback garantido de partidas de alta relevância (Brasileirão, Champions League, UFC)
        SportsEvent ev1 = new SportsEvent();
        ev1.id = "sport_flamengo_palmeiras";
        ev1.name = "Flamengo x Palmeiras";
        ev1.league = "Brasileirão Série A";
        ev1.matchTime = "16:00";
        ev1.isLive = true;
        ev1.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9770.png";
        ev1.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/10283.png";
        ev1.fallbacks.add(new Channel.StreamFallback("Premiere HD", "https://rdcanais.net/premiere", true));
        ev1.fallbacks.add(new Channel.StreamFallback("SporTV HD", "https://rdcanais.net/sportv", true));
        list.add(ev1);

        SportsEvent ev2 = new SportsEvent();
        ev2.id = "sport_corinthians_saopaulo";
        ev2.name = "Corinthians x São Paulo";
        ev2.league = "Brasileirão Série A (Majestoso)";
        ev2.matchTime = "18:30";
        ev2.isLive = true;
        ev2.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9808.png";
        ev2.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/10277.png";
        ev2.fallbacks.add(new Channel.StreamFallback("Premiere HD", "https://rdcanais.net/premiere", true));
        ev2.fallbacks.add(new Channel.StreamFallback("Globo SP", "https://rdcanais.net/globosp", true));
        list.add(ev2);

        SportsEvent ev3 = new SportsEvent();
        ev3.id = "sport_realmadrid_barcelona";
        ev3.name = "Real Madrid x Barcelona";
        ev3.league = "La Liga (El Clásico)";
        ev3.matchTime = "16:00";
        ev3.isLive = false;
        ev3.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/8633.png";
        ev3.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/8634.png";
        ev3.fallbacks.add(new Channel.StreamFallback("ESPN HD", "https://rdcanais.net/espn", true));
        ev3.fallbacks.add(new Channel.StreamFallback("Star+ HD", "https://rdcanais.net/espn4", true));
        list.add(ev3);

        SportsEvent ev4 = new SportsEvent();
        ev4.id = "sport_ufc_main_event";
        ev4.name = "UFC Fight Night: Card Principal";
        ev4.league = "MMA / Artes Marciais";
        ev4.matchTime = "21:00";
        ev4.isLive = false;
        ev4.homeLogo = "https://reidosembeds.online/img/combate.png";
        ev4.awayLogo = "https://reidosembeds.online/img/combate.png";
        ev4.fallbacks.add(new Channel.StreamFallback("Combate HD", "https://rdcanais.net/combate", true));
        list.add(ev4);

        return list;
    }
}
