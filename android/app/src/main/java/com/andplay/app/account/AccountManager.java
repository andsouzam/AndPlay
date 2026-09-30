package com.andplay.app.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.andplay.app.model.FavoriteItem;
import com.andplay.app.model.WatchProgressItem;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AccountManager {

    private static final String TAG = "EPlayAccount";
    private static final String SUPABASE_URL = "https://zfawwhqogtynuygniskz.supabase.co";
    private static final String SUPABASE_ANON_KEY = "sb_publishable_naUBrBzRCU_SQbSiNtpQQQ_7Q8h1VoJ";

    public static final String VIEW_MODE_TV = "tv";
    public static final String VIEW_MODE_CINEMA = "cinema";

    private static final String PREF_NAME = "andplay_account_prefs";
    private static final String KEY_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_REFRESH_TOKEN = "auth_refresh_token";
    private static final String KEY_USER_ID = "auth_user_id";
    private static final String KEY_EMAIL = "auth_email";
    private static final String KEY_DISPLAY_NAME = "auth_display_name";
    private static final String KEY_AVATAR = "auth_avatar";
    private static final String KEY_VIEW_MODE = "pref_view_mode";
    private static final String KEY_FAVORITES_JSON = "cached_favorites_json";
    private static final String KEY_LAST_SYNC = "last_sync_timestamp";

    private static AccountManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private final List<FavoriteItem> memoryFavorites = new ArrayList<>();
    private final List<WatchProgressItem> memoryProgress = new ArrayList<>();

    public interface AuthCallback {
        void onSuccess(String email, String displayName);
        void onError(String message);
    }

    public interface SimpleCallback {
        void onResult(boolean success, String message);
    }

    public interface SyncCallback {
        void onComplete(boolean success, int favoritesCount, int progressCount);
    }

    public static synchronized AccountManager getInstance(Context context) {
        if (instance == null) {
            instance = new AccountManager(context.getApplicationContext());
        }
        return instance;
    }

    private AccountManager(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        loadMemoryCache();
    }

    private void loadMemoryCache() {
        try {
            String favJson = prefs.getString(KEY_FAVORITES_JSON, "[]");
            Type listType = new TypeToken<List<FavoriteItem>>(){}.getType();
            List<FavoriteItem> list = gson.fromJson(favJson, listType);
            if (list != null) {
                memoryFavorites.clear();
                memoryFavorites.addAll(list);
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao carregar favoritos da memória: " + e.getMessage());
        }
    }

    private void saveFavoritesToPrefs() {
        try {
            String json = gson.toJson(memoryFavorites);
            prefs.edit().putString(KEY_FAVORITES_JSON, json).apply();
        } catch (Exception ignored) {}
    }

    public boolean isSignedIn() {
        String token = prefs.getString(KEY_ACCESS_TOKEN, "");
        return token != null && !token.trim().isEmpty();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, "");
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, "");
    }

    public String getEmail() {
        return prefs.getString(KEY_EMAIL, "");
    }

    public String getDisplayName() {
        String name = prefs.getString(KEY_DISPLAY_NAME, "");
        if (name != null && !name.trim().isEmpty()) return name;
        String email = getEmail();
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return "Usuário EPlay";
    }

    public String getAvatarEmoji() {
        String av = prefs.getString(KEY_AVATAR, "");
        return (av != null && !av.trim().isEmpty()) ? av : "👤";
    }

    public String getViewMode() {
        return prefs.getString(KEY_VIEW_MODE, VIEW_MODE_TV);
    }

    public void setViewMode(String mode) {
        prefs.edit().putString(KEY_VIEW_MODE, mode).apply();
    }

    public long getLastSyncTimestamp() {
        return prefs.getLong(KEY_LAST_SYNC, 0);
    }

    public void signIn(String email, String password, AuthCallback callback) {
        if (email == null || email.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            if (callback != null) callback.onError("Informe e-mail e senha.");
            return;
        }

        executor.execute(() -> {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("email", email.trim());
                json.addProperty("password", password);

                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(SUPABASE_URL + "/auth/v1/token?grant_type=password")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    String respBody = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        String errMsg = "Falha no login (" + resp.code() + ")";
                        try {
                            JsonObject errObj = JsonParser.parseString(respBody).getAsJsonObject();
                            if (errObj.has("error_description")) {
                                errMsg = errObj.get("error_description").getAsString();
                            } else if (errObj.has("msg")) {
                                errMsg = errObj.get("msg").getAsString();
                            }
                        } catch (Exception ignored) {}
                        final String fErr = errMsg;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onError(fErr);
                        });
                        return;
                    }

                    JsonObject root = JsonParser.parseString(respBody).getAsJsonObject();
                    String accessToken = root.has("access_token") ? root.get("access_token").getAsString() : "";
                    String refreshToken = root.has("refresh_token") ? root.get("refresh_token").getAsString() : "";

                    String userId = "";
                    String userEmail = email.trim();
                    String displayName = "";
                    String avatar = "👤";

                    if (root.has("user") && root.get("user").isJsonObject()) {
                        JsonObject user = root.getAsJsonObject("user");
                        userId = user.has("id") ? user.get("id").getAsString() : "";
                        if (user.has("email")) userEmail = user.get("email").getAsString();
                        if (user.has("user_metadata") && user.get("user_metadata").isJsonObject()) {
                            JsonObject meta = user.getAsJsonObject("user_metadata");
                            if (meta.has("full_name")) displayName = meta.get("full_name").getAsString();
                            else if (meta.has("name")) displayName = meta.get("name").getAsString();
                            if (meta.has("avatar_url")) avatar = meta.get("avatar_url").getAsString();
                        }
                    }

                    if (displayName.isEmpty() && userEmail.contains("@")) {
                        displayName = userEmail.substring(0, userEmail.indexOf('@'));
                    }

                    prefs.edit()
                            .putString(KEY_ACCESS_TOKEN, accessToken)
                            .putString(KEY_REFRESH_TOKEN, refreshToken)
                            .putString(KEY_USER_ID, userId)
                            .putString(KEY_EMAIL, userEmail)
                            .putString(KEY_DISPLAY_NAME, displayName)
                            .putString(KEY_AVATAR, avatar)
                            .apply();

                    final String finalEmail = userEmail;
                    final String finalName = displayName;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onSuccess(finalEmail, finalName);
                    });

                    // Inicia sincronização completa dos dados após login
                    syncAll(null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro de rede no login", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Erro de conexão: " + e.getMessage());
                });
            }
        });
    }

    public void signUp(String email, String password, AuthCallback callback) {
        if (email == null || email.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            if (callback != null) callback.onError("Preencha todos os campos.");
            return;
        }

        executor.execute(() -> {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("email", email.trim());
                json.addProperty("password", password);

                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(SUPABASE_URL + "/auth/v1/signup")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    String respBody = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        String errMsg = "Erro no cadastro (" + resp.code() + ")";
                        try {
                            JsonObject errObj = JsonParser.parseString(respBody).getAsJsonObject();
                            if (errObj.has("msg")) errMsg = errObj.get("msg").getAsString();
                            else if (errObj.has("message")) errMsg = errObj.get("message").getAsString();
                        } catch (Exception ignored) {}
                        final String fErr = errMsg;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onError(fErr);
                        });
                        return;
                    }

                    // Tenta login direto após criação
                    signIn(email, password, callback);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro no signup", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Erro de conexão: " + e.getMessage());
                });
            }
        });
    }

    public void recoverPassword(String email, SimpleCallback callback) {
        if (email == null || email.trim().isEmpty()) {
            if (callback != null) callback.onResult(false, "Informe seu e-mail.");
            return;
        }

        executor.execute(() -> {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("email", email.trim());

                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(SUPABASE_URL + "/auth/v1/recover")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    boolean ok = resp.isSuccessful();
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onResult(ok, ok ? "Instruções enviadas para seu e-mail." : "Não foi possível recuperar agora.");
                        }
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onResult(false, "Erro de rede.");
                });
            }
        });
    }

    public void signOut() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .remove(KEY_DISPLAY_NAME)
                .remove(KEY_AVATAR)
                .apply();
    }

    public synchronized List<FavoriteItem> getFavorites() {
        return new ArrayList<>(memoryFavorites);
    }

    public synchronized boolean isFavorite(String type, String id) {
        if (id == null || id.isEmpty()) return false;
        String normType = "series".equalsIgnoreCase(type) ? "series" : "movie";
        for (FavoriteItem fav : memoryFavorites) {
            if (normType.equalsIgnoreCase(fav.contentType) && id.equals(fav.contentId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void toggleFavorite(String type, String id, String title, String poster, SimpleCallback callback) {
        if (id == null || id.isEmpty()) return;
        final String normType = "series".equalsIgnoreCase(type) ? "series" : "movie";
        boolean wasFavorite = isFavorite(normType, id);
        final boolean newFavoriteState = !wasFavorite;

        if (newFavoriteState) {
            FavoriteItem item = new FavoriteItem(normType, id, title != null ? title : "", poster != null ? poster : "");
            memoryFavorites.add(0, item);
        } else {
            Iterator<FavoriteItem> it = memoryFavorites.iterator();
            while (it.hasNext()) {
                FavoriteItem fav = it.next();
                if (normType.equalsIgnoreCase(fav.contentType) && id.equals(fav.contentId)) {
                    it.remove();
                    break;
                }
            }
        }
        saveFavoritesToPrefs();

        if (callback != null) {
            callback.onResult(newFavoriteState, newFavoriteState ? "Adicionado aos favoritos" : "Removido dos favoritos");
        }

        if (isSignedIn()) {
            final String token = getAccessToken();
            executor.execute(() -> {
                try {
                    if (newFavoriteState) {
                        JsonArray arr = new JsonArray();
                        JsonObject obj = new JsonObject();
                        obj.addProperty("content_type", normType);
                        obj.addProperty("content_id", id);
                        obj.addProperty("title", title != null ? title : "");
                        obj.addProperty("poster", poster != null ? poster : "");
                        arr.add(obj);

                        RequestBody body = RequestBody.create(
                                arr.toString(),
                                MediaType.parse("application/json; charset=utf-8")
                        );

                        Request req = new Request.Builder()
                                .url(SUPABASE_URL + "/rest/v1/user_favorites")
                                .header("apikey", SUPABASE_ANON_KEY)
                                .header("Authorization", "Bearer " + token)
                                .header("Prefer", "resolution=merge-duplicates")
                                .post(body)
                                .build();
                        try (Response resp = httpClient.newCall(req).execute()) {
                            Log.d(TAG, "Supabase favorite add: " + resp.code());
                        }
                    } else {
                        Request req = new Request.Builder()
                                .url(SUPABASE_URL + "/rest/v1/user_favorites?content_type=eq." + normType + "&content_id=eq." + id)
                                .header("apikey", SUPABASE_ANON_KEY)
                                .header("Authorization", "Bearer " + token)
                                .delete()
                                .build();
                        try (Response resp = httpClient.newCall(req).execute()) {
                            Log.d(TAG, "Supabase favorite remove: " + resp.code());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Erro sync favorito remoto: " + e.getMessage());
                }
            });
        }
    }

    public void recordVodProgress(String type, String id, String title, String poster, long positionMs, long durationMs, String seriesId, int season, int episode) {
        if (id == null || id.isEmpty()) return;
        final String normType = "series".equalsIgnoreCase(type) ? "series" : "movie";
        final long posSec = Math.max(0, positionMs / 1000);
        final long durSec = Math.max(0, durationMs / 1000);

        if (!isSignedIn()) return;
        final String token = getAccessToken();

        executor.execute(() -> {
            try {
                // 1. Envia progresso para watch_progress
                JsonArray progArr = new JsonArray();
                JsonObject progObj = new JsonObject();
                progObj.addProperty("content_type", normType);
                progObj.addProperty("content_id", id);
                progObj.addProperty("position", posSec);
                progObj.addProperty("duration", durSec);
                progObj.addProperty("title", title != null ? title : "");
                progObj.addProperty("poster", poster != null ? poster : "");
                if (seriesId != null && !seriesId.isEmpty()) {
                    progObj.addProperty("series_id", seriesId);
                    progObj.addProperty("season_num", season);
                    progObj.addProperty("episode_num", episode);
                }
                progObj.addProperty("updated_at", System.currentTimeMillis());
                progArr.add(progObj);

                RequestBody progBody = RequestBody.create(
                        progArr.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request progReq = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/watch_progress")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer " + token)
                        .header("Prefer", "resolution=merge-duplicates")
                        .post(progBody)
                        .build();

                try (Response resp = httpClient.newCall(progReq).execute()) {
                    Log.d(TAG, "Supabase record progress: " + resp.code());
                }

                // 2. Registra em watch_history
                JsonArray histArr = new JsonArray();
                JsonObject histObj = new JsonObject();
                histObj.addProperty("content_type", normType);
                histObj.addProperty("content_id", normType.equals("series") && seriesId != null ? seriesId : id);
                histObj.addProperty("sort_order", 0);
                histObj.addProperty("updated_at", System.currentTimeMillis());
                histArr.add(histObj);

                RequestBody histBody = RequestBody.create(
                        histArr.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request histReq = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/watch_history")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer " + token)
                        .header("Prefer", "resolution=merge-duplicates")
                        .post(histBody)
                        .build();

                try (Response resp = httpClient.newCall(histReq).execute()) {
                    Log.d(TAG, "Supabase record history: " + resp.code());
                }

            } catch (Exception e) {
                Log.w(TAG, "Erro ao gravar progresso VOD remoto: " + e.getMessage());
            }
        });
    }

    public synchronized List<WatchProgressItem> getMemoryProgress() {
        return new ArrayList<>(memoryProgress);
    }

    public void syncAll(SyncCallback callback) {
        if (!isSignedIn()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onComplete(false, memoryFavorites.size(), memoryProgress.size()));
            }
            return;
        }

        final String token = getAccessToken();

        executor.execute(() -> {
            boolean success = false;
            int favCount = 0;
            int progCount = 0;

            try {
                // 1. Sincroniza Favoritos
                Request favReq = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/user_favorites?select=content_type,content_id,title,poster,created_at,updated_at&order=updated_at.desc")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer " + token)
                        .get()
                        .build();

                try (Response resp = httpClient.newCall(favReq).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String bodyStr = resp.body().string();
                        Type listType = new TypeToken<List<FavoriteItem>>(){}.getType();
                        List<FavoriteItem> remote = gson.fromJson(bodyStr, listType);
                        if (remote != null) {
                            synchronized (AccountManager.this) {
                                memoryFavorites.clear();
                                memoryFavorites.addAll(remote);
                                saveFavoritesToPrefs();
                                favCount = memoryFavorites.size();
                            }
                        }
                    }
                }

                // 2. Sincroniza Progresso / Continuar Assistindo
                Request progReq = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/watch_progress?select=content_type,content_id,position,duration,title,poster,series_id,season_num,episode_num,updated_at&order=updated_at.desc")
                        .header("apikey", SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer " + token)
                        .get()
                        .build();

                try (Response resp = httpClient.newCall(progReq).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String bodyStr = resp.body().string();
                        Type listType = new TypeToken<List<WatchProgressItem>>(){}.getType();
                        List<WatchProgressItem> remoteProg = gson.fromJson(bodyStr, listType);
                        if (remoteProg != null) {
                            synchronized (AccountManager.this) {
                                memoryProgress.clear();
                                memoryProgress.addAll(remoteProg);
                                progCount = memoryProgress.size();
                            }

                            // Sincroniza também no SharedPreferences local de VOD do app para compatibilidade imediata
                            SharedPreferences vodPrefs = appContext.getSharedPreferences("andplay_vod_progress", Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = vodPrefs.edit();
                            for (WatchProgressItem item : remoteProg) {
                                if (item.contentId != null && item.position > 0) {
                                    String key = item.contentType + "_" + item.contentId;
                                    editor.putLong(key, item.position * 1000L);
                                    if (item.duration > 0) {
                                        editor.putLong(key + "_dur", item.duration * 1000L);
                                    }
                                }
                            }
                            editor.apply();
                        }
                    }
                }

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply();
                success = true;
            } catch (Exception e) {
                Log.w(TAG, "Erro durante sincronização: " + e.getMessage());
            }

            final boolean fSuccess = success;
            final int fFav = favCount;
            final int fProg = progCount;
            mainHandler.post(() -> {
                if (callback != null) callback.onComplete(fSuccess, fFav, fProg);
            });
        });
    }
}
