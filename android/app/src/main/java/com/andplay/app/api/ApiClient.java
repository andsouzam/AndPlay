package com.andplay.app.api;

import android.content.Context;
import com.andplay.app.model.Category;
import com.andplay.app.model.Channel;
import com.andplay.app.model.Episode;
import com.andplay.app.model.Movie;
import com.andplay.app.model.Series;
import com.andplay.app.model.SportsEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiClient {

    public static final String SERVER = "https://2kbrfonte.space";
    public static final String USER = "LuizDavi%40";
    public static final String PASS = "fBkvnKe5Mq";

    private static final Gson gson = new Gson();

    public static List<Channel> loadLocalChannels(Context context) {
        try {
            InputStream is = context.getAssets().open("channels.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            Type type = new TypeToken<List<Channel>>() {}.getType();
            List<Channel> channels = gson.fromJson(reader, type);
            reader.close();
            if (channels != null) return channels;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    public static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "AndPlay Native Android TV 2.0");

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    public static List<Category> getMovieCategories() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_vod_categories";
        String json = httpGet(url);
        Type type = new TypeToken<List<Category>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public static List<Movie> getMovies() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_vod_streams";
        String json = httpGet(url);
        Type type = new TypeToken<List<Movie>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public static List<Category> getSeriesCategories() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series_categories";
        String json = httpGet(url);
        Type type = new TypeToken<List<Category>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public static List<Series> getSeries() throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series";
        String json = httpGet(url);
        Type type = new TypeToken<List<Series>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public static Map<String, List<Episode>> getSeriesEpisodes(String seriesId) throws Exception {
        String url = SERVER + "/player_api.php?username=" + USER + "&password=" + PASS + "&action=get_series_info&series_id=" + seriesId;
        String json = httpGet(url);
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        Map<String, List<Episode>> result = new HashMap<>();

        if (obj != null && obj.has("episodes")) {
            JsonObject epsObj = obj.getAsJsonObject("episodes");
            for (String seasonKey : epsObj.keySet()) {
                JsonElement elem = epsObj.get(seasonKey);
                if (elem != null && elem.isJsonArray()) {
                    Type listType = new TypeToken<List<Episode>>() {}.getType();
                    List<Episode> epList = gson.fromJson(elem, listType);
                    result.put(seasonKey, epList);
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
