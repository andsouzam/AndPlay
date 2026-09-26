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

    public static List<Category> getMovieCategories() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_vod_categories";
        String json = httpGet(url);
        JsonElement root = JsonParser.parseString(json);
        List<Category> list = new ArrayList<>();
        if (root != null && root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String catId = optString(o, "category_id", "");
                String catName = optString(o, "category_name", "");
                if (catName != null && catName.toLowerCase().contains("demo")) {
                    continue;
                }
                list.add(new Category(catId, catName));
            }
        }
        return list;
    }

    public static List<Movie> getMovies() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_vod_streams";
        String json = httpGet(url);
        JsonElement root = JsonParser.parseString(json);
        List<Movie> clean = new ArrayList<>();
        if (root != null && root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String name = optString(o, "name", "");
                String title = optString(o, "title", "");
                String nameLow = name.toLowerCase();
                String titleLow = title.toLowerCase();
                if (nameLow.contains("demo") || titleLow.contains("demo")) {
                    continue;
                }

                Movie m = new Movie();
                m.num = optInt(o, "num", 0);
                m.name = name;
                m.title = title;
                m.year = optString(o, "year", "");
                m.stream_type = optString(o, "stream_type", "movie");
                m.stream_id = optString(o, "stream_id", "");
                m.stream_icon = optString(o, "stream_icon", "");
                m.rating = optString(o, "rating", "");
                m.rating_5based = optString(o, "rating_5based", "");
                m.added = optString(o, "added", "");
                m.category_id = optString(o, "category_id", "");
                m.container_extension = optString(o, "container_extension", "mp4");
                m.custom_sid = optString(o, "custom_sid", "");
                m.direct_source = optString(o, "direct_source", "");
                m.plot = optString(o, "plot", "");
                m.cast = optString(o, "cast", "");
                m.director = optString(o, "director", "");
                m.genre = optString(o, "genre", "");
                m.release_date = optString(o, "release_date", "");
                m.duration = optString(o, "duration", "");

                clean.add(m);
            }
        }
        return clean;
    }

    public static List<Category> getSeriesCategories() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series_categories";
        String json = httpGet(url);
        JsonElement root = JsonParser.parseString(json);
        List<Category> list = new ArrayList<>();
        if (root != null && root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String catId = optString(o, "category_id", "");
                String catName = optString(o, "category_name", "");
                if (catName != null && catName.toLowerCase().contains("demo")) {
                    continue;
                }
                list.add(new Category(catId, catName));
            }
        }
        return list;
    }

    public static List<Series> getSeries() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series";
        String json = httpGet(url);
        JsonElement root = JsonParser.parseString(json);
        List<Series> list = new ArrayList<>();
        if (root != null && root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Series s = new Series();
                s.num = optInt(o, "num", 0);
                s.name = optString(o, "name", "");
                s.title = optString(o, "title", "");
                s.series_id = optString(o, "series_id", "");
                s.cover = optString(o, "cover", "");
                s.plot = optString(o, "plot", "");
                s.cast = optString(o, "cast", "");
                s.director = optString(o, "director", "");
                s.genre = optString(o, "genre", "");
                s.releaseDate = optString(o, "releaseDate", optString(o, "release_date", ""));
                s.last_modified = optString(o, "last_modified", "");
                s.rating = optString(o, "rating", "");
                s.rating_5based = optString(o, "rating_5based", "");
                s.category_id = optString(o, "category_id", "");

                // Ignora série de demonstração "DEMO"
                String nameLow = s.name != null ? s.name.toLowerCase() : "";
                String titleLow = s.title != null ? s.title.toLowerCase() : "";
                if (nameLow.contains("demo") || titleLow.contains("demo")) {
                    continue;
                }

                list.add(s);
            }
        }
        return list;
    }

    public static Map<String, List<Episode>> getSeriesEpisodes(String seriesId) throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series_info&series_id=" + seriesId;
        String json = httpGet(url);
        JsonElement root = JsonParser.parseString(json);
        Map<String, List<Episode>> result = new HashMap<>();

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
        ev1.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9773.png";
        ev1.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9772.png";
        ev1.fallbacks.add(new Channel.StreamFallback("Premiere HD", "https://rdcanais.net/premiere", true));
        ev1.fallbacks.add(new Channel.StreamFallback("SporTV HD", "https://rdcanais.net/sportv", true));
        list.add(ev1);

        SportsEvent ev2 = new SportsEvent();
        ev2.id = "sport_corinthians_saopaulo";
        ev2.name = "Corinthians x São Paulo";
        ev2.league = "Brasileirão Série A (Majestoso)";
        ev2.matchTime = "18:30";
        ev2.isLive = true;
        ev2.homeLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9769.png";
        ev2.awayLogo = "https://images.fotmob.com/image_resources/logo/teamlogo/9777.png";
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
