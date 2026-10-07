package com.andplay.app.provider;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Resolver de alta performance para canais do RDCanais / RDEmbed.
 * Extrai transmissões HLS (.m3u8) e MPEG-DASH (.mpd com ClearKey DRM) diretamente das páginas
 * dos canais para reprodução nativa via ExoPlayer, eliminando WebViews, iframes e anúncios.
 */
public class RdCanaisResolver {

    private static final String TAG = "RdCanaisResolver";
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutos

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Map<String, CachedStream> CACHE = new ConcurrentHashMap<>();

    public static class ResolvedStream {
        public enum Type { HLS, DASH }

        public Type type = Type.HLS;
        public String streamUrl;
        public Map<String, String> headers = new HashMap<>();

        // ClearKey DRM (para streams MPEG-DASH)
        public String clearKeyId;
        public String clearKey;

        public boolean isDrmProtected() {
            return clearKeyId != null && !clearKeyId.isEmpty() && clearKey != null && !clearKey.isEmpty();
        }
    }

    private static class CachedStream {
        final ResolvedStream stream;
        final long expiresAt;

        CachedStream(ResolvedStream stream, long ttlMs) {
            this.stream = stream;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    public interface Callback {
        void onSuccess(@NonNull ResolvedStream stream);
        void onError(@NonNull Exception error);
    }

    public static boolean isRdCanaisUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("rdcanais.net")
                || u.contains("rdcanais.org")
                || u.contains("reidoscanais")
                || u.contains("reidosembeds.online")
                || u.contains("bolodechocolate")
                || u.contains("player.cdn-img.st")
                || u.contains("rdembed")
                || u.contains("redecanais")
                || u.contains("pescaplay.store")
                || u.contains("localhost.tattoo")
                || u.contains("repositoratacadao")
                || u.contains("comeumamao")
                || u.contains(".monster")
                || u.contains(".cyou")
                || u.contains(".goldorayanhoje")
                || u.contains(".seraquevaiter")
                || u.contains(".pamonha")
                || u.contains(".sbs")
                || u.contains("ourlawyermadeuschangethenameofthissongsowewouldntgetsued");
    }

    /**
     * Resolve um canal de forma assíncrona, entregando o resultado no Callback.
     */
    public static void resolve(@NonNull String channelUrl, @NonNull OkHttpClient client, @NonNull Callback callback) {
        // 1. Verifica cache em memória RAM para carregamento instantâneo (0ms)
        CachedStream cached = CACHE.get(channelUrl);
        if (cached != null && cached.isValid()) {
            Log.d(TAG, "Stream recuperado do cache instantâneo para: " + channelUrl);
            callback.onSuccess(cached.stream);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                ResolvedStream resolved = resolveSync(channelUrl, client);
                if (resolved != null && resolved.streamUrl != null && !resolved.streamUrl.isEmpty()) {
                    CACHE.put(channelUrl, new CachedStream(resolved, CACHE_TTL_MS));
                    callback.onSuccess(resolved);
                } else {
                    callback.onError(new IOException("Não foi possível extrair stream nativo para: " + channelUrl));
                }
            } catch (Exception e) {
                Log.w(TAG, "Erro ao resolver stream: " + e.getMessage());
                callback.onError(e);
            }
        });
    }

    /**
     * Resolução síncrona passo a passo (executada em thread de background).
     */
    public static ResolvedStream resolveSync(@NonNull String initialUrl, @NonNull OkHttpClient client) throws Exception {
        String url = initialUrl.trim();
        if (url.startsWith("//")) url = "https:" + url;

        // Se já for uma URL direta de mídia
        if (url.contains(".m3u8") || url.contains("__index.txt")) {
            ResolvedStream s = new ResolvedStream();
            s.type = ResolvedStream.Type.HLS;
            s.streamUrl = url;
            return s;
        }
        if (url.contains(".mpd")) {
            ResolvedStream s = new ResolvedStream();
            s.type = ResolvedStream.Type.DASH;
            s.streamUrl = url;
            return s;
        }

        Log.i(TAG, "Iniciando resolução para: " + url);

        String targetUrl = url;
        String parentUrl = null;
        if (targetUrl.contains("rdembed")) {
            parentUrl = "https://reidosembeds.online/";
        } else if (targetUrl.contains("rdcanais.org")) {
            parentUrl = "https://rdcanais.org/";
        }
        String accumulatedCookies = null;
        int maxHops = 5;

        for (int hop = 0; hop < maxHops; hop++) {
            Log.d(TAG, "Hop #" + (hop + 1) + " buscando: " + targetUrl);
            FetchResult fetchRes = fetchHtml(targetUrl, parentUrl, accumulatedCookies, client);
            if (fetchRes == null || fetchRes.html == null || fetchRes.html.isEmpty()) {
                break;
            }
            if (fetchRes.cookies != null && !fetchRes.cookies.isEmpty()) {
                accumulatedCookies = fetchRes.cookies;
            }
            String html = decodeBolodechocolateHtml(fetchRes.html);

            // Caso 1: RDEmbed com troca autenticada de token 'ref' via POST
            ResolvedStream rdembedStream = parseRdEmbedRefStream(html, targetUrl, accumulatedCookies, client);
            if (rdembedStream != null) {
                Log.i(TAG, "Identificado stream RDEmbed nativo via ref token: " + rdembedStream.streamUrl);
                return rdembedStream;
            }

            // Caso 2: MPEG-DASH com ClearKey DRM (ex: Premiere Clubes, SporTV)
            ResolvedStream dashStream = parseDashClearKeyStream(html, targetUrl);
            if (dashStream != null) {
                Log.i(TAG, "Identificado stream MPEG-DASH com ClearKey DRM: " + dashStream.streamUrl);
                return dashStream;
            }

            // Caso 3: HLS direto via window.STREAM_URLS (ex: ESPN, Combate, AMC bolodechocolate)
            ResolvedStream hlsStreamUrls = parseStreamUrls(html, targetUrl);
            if (hlsStreamUrls != null) {
                Log.i(TAG, "Identificado stream HLS direto via STREAM_URLS: " + hlsStreamUrls.streamUrl);
                return hlsStreamUrls;
            }

            // Caso 4: HLS direto via variáveis JS (jwplayer, streamUrl, file:, source:)
            ResolvedStream hlsVarStream = parseInlineHlsStream(html, targetUrl, client);
            if (hlsVarStream != null) {
                Log.i(TAG, "Identificado stream HLS direto via variável: " + hlsVarStream.streamUrl);
                return hlsVarStream;
            }

            // Caso 5: Globo Play Session API (localhost.tattoo)
            if (targetUrl.contains("localhost.tattoo") || html.contains("playback.video.globo.com")) {
                ResolvedStream globoStream = parseGloboSessionStream(html, targetUrl, client);
                if (globoStream != null) {
                    Log.i(TAG, "Identificado stream Globo via Session API: " + globoStream.streamUrl);
                    return globoStream;
                }
            }

            // Caso 6: Se encontrou iframe, avança para o próximo hop
            String iframeSrc = extractIframeSrc(html);
            if (iframeSrc != null && !iframeSrc.isEmpty()) {
                iframeSrc = iframeSrc.replace("&amp;", "&");
                if (iframeSrc.startsWith("//")) iframeSrc = "https:" + iframeSrc;
                else if (iframeSrc.startsWith("/")) iframeSrc = extractOrigin(targetUrl) + iframeSrc;
                parentUrl = targetUrl;
                targetUrl = iframeSrc;
            } else {
                break;
            }
        }

        throw new IOException("Nenhum padrão de stream ativo encontrado para: " + url);
    }

    private static class FetchResult {
        final String html;
        final String cookies;
        FetchResult(String html, String cookies) {
            this.html = html;
            this.cookies = cookies;
        }
    }

    private static FetchResult fetchHtml(String url, String referer, String cookies, OkHttpClient client) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
        if (referer != null && !referer.isEmpty()) {
            rb.header("Referer", referer);
        }
        if (cookies != null && !cookies.isEmpty()) {
            rb.header("Cookie", cookies);
        }
        try (Response response = client.newCall(rb.build()).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                StringBuilder cookieBuilder = new StringBuilder();
                if (cookies != null && !cookies.isEmpty()) {
                    cookieBuilder.append(cookies);
                }
                java.util.List<String> setCookies = response.headers("Set-Cookie");
                for (String sc : setCookies) {
                    if (sc != null && !sc.isEmpty()) {
                        String part = sc.split(";")[0].trim();
                        if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                        cookieBuilder.append(part);
                    }
                }
                return new FetchResult(body, cookieBuilder.toString());
            }
        }
        return null;
    }

    private static String extractIframeSrc(String html) {
        Pattern pattern = Pattern.compile("(?i)<iframe\\s+[^>]*src=[\"']([^\"']+)[\"']");
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Desofuscação matemática das camadas Base64 do bolodechocolate / esportesembed.
     */
    public static String decodeBolodechocolateHtml(String html) {
        if (html == null || html.contains("window.STREAM_URLS") || html.contains("\"ref\":")
                || (!html.contains("String.fromCharCode") && !html.contains("atob"))) {
            return html;
        }
        try {
            Pattern offsetPattern = Pattern.compile("-\\s*(\\d+)\\s*\\)");
            Matcher offsetMatcher = offsetPattern.matcher(html);
            if (!offsetMatcher.find()) return html;
            long offset = Long.parseLong(offsetMatcher.group(1));

            Pattern arrayPattern = Pattern.compile("(?s)var\\s+\\w+\\s*=\\s*\\[(.*?)\\];");
            Matcher arrayMatcher = arrayPattern.matcher(html);
            if (!arrayMatcher.find()) return html;

            String arrayContent = arrayMatcher.group(1);
            String[] tokens = arrayContent.split(",");
            StringBuilder decoded = new StringBuilder(tokens.length);
            for (String token : tokens) {
                String clean = token.replace("\"", "").replace("'", "").trim();
                if (!clean.isEmpty()) {
                    byte[] decodedBytes = Base64.decode(clean, Base64.DEFAULT);
                    String s = new String(decodedBytes, StandardCharsets.UTF_8);
                    String digits = s.replaceAll("\\D", "");
                    if (!digits.isEmpty()) {
                        long code = Long.parseLong(digits) - offset;
                        decoded.append((char) code);
                    }
                }
            }
            String res = decoded.toString();
            if (res.contains("<html") || res.contains("<body") || res.contains("<script") || res.contains("window.")) {
                return res;
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao desofuscar HTML: " + e.getMessage());
        }
        return html;
    }

    /**
     * Extrai streams MPEG-DASH (.mpd) com parâmetros ClearKey DRM.
     */
    private static ResolvedStream parseDashClearKeyStream(String html, String targetUrl) {
        Pattern urlPattern = Pattern.compile("window\\.url\\s*=\\s*[\"']([^\"']+\\.mpd[^\"']*)[\"']");
        Matcher mUrl = urlPattern.matcher(html);
        if (!mUrl.find()) {
            // Tenta outro padrão de declaração
            Pattern urlPatternAlt = Pattern.compile("[\"']([^\"']+\\.mpd[^\"']*)[\"']");
            mUrl = urlPatternAlt.matcher(html);
            if (!mUrl.find()) return null;
        }

        String mpdUrl = mUrl.group(1).replace("\\/", "/");
        String ckId = null;
        String ckKey = null;

        Matcher mId = Pattern.compile("window\\.ck_id\\s*=\\s*[\"']([a-fA-F0-9]+)[\"']").matcher(html);
        if (mId.find()) ckId = mId.group(1);

        Matcher mKey = Pattern.compile("window\\.ck_key\\s*=\\s*[\"']([a-fA-F0-9]+)[\"']").matcher(html);
        if (mKey.find()) ckKey = mKey.group(1);

        ResolvedStream stream = new ResolvedStream();
        stream.type = ResolvedStream.Type.DASH;
        stream.streamUrl = mpdUrl;
        stream.clearKeyId = ckId;
        stream.clearKey = ckKey;
        stream.headers.put("Referer", targetUrl);
        stream.headers.put("Origin", extractOrigin(targetUrl));
        stream.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        return stream;
    }

    /**
     * Extrai streams HLS de players baseados no Clappr P2P (window.STREAM_URLS).
     */
    private static ResolvedStream parseStreamUrls(String html, String targetUrl) {
        Pattern p = Pattern.compile("window\\.STREAM_URLS\\s*=\\s*\\[\\s*[\"']([^\"']+)[\"']");
        Matcher m = p.matcher(html);
        if (!m.find()) return null;

        String rawUrl = m.group(1).replace("\\/", "/");
        if (!rawUrl.contains(".m3u8")) return null;

        ResolvedStream stream = new ResolvedStream();
        stream.type = ResolvedStream.Type.HLS;
        stream.streamUrl = rawUrl;
        stream.headers.put("Referer", targetUrl);
        stream.headers.put("Origin", extractOrigin(targetUrl));
        stream.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        return stream;
    }

    /**
     * Extrai e executa a troca autenticada do token 'ref' do provedor RDEmbed via POST.
     */
    private static ResolvedStream parseRdEmbedRefStream(String html, String targetUrl, String cookies, OkHttpClient client) {
        if (html == null) return null;
        Pattern p = Pattern.compile("\"ref\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(html);
        if (!m.find()) return null;

        String refPath = m.group(1).replace("\\/", "/").trim();
        if (refPath.isEmpty()) return null;

        String origin = extractOrigin(targetUrl);
        String refUrl = refPath.startsWith("http") ? refPath : (origin + (refPath.startsWith("/") ? "" : "/") + refPath);

        Log.i(TAG, "Detectado RDEmbed ref token. Efetuando POST em: " + refUrl);

        try {
            RequestBody emptyBody = RequestBody.create(new byte[0], MediaType.parse("application/x-www-form-urlencoded"));
            Request.Builder reqB = new Request.Builder()
                    .url(refUrl)
                    .post(emptyBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .header("Referer", targetUrl)
                    .header("Origin", origin);
            if (cookies != null && !cookies.isEmpty()) {
                reqB.header("Cookie", cookies);
            }

            try (Response res = client.newCall(reqB.build()).execute()) {
                if (res.isSuccessful() && res.body() != null) {
                    String bodyStr = res.body().string();
                    JSONObject json = new JSONObject(bodyStr);
                    String src = json.optString("src", "").replace("\\/", "/");
                    if (!src.isEmpty()) {
                        if (src.startsWith("//")) src = "https:" + src;
                        ResolvedStream s = new ResolvedStream();
                        s.type = ResolvedStream.Type.HLS;
                        s.streamUrl = src;
                        s.headers.put("Referer", targetUrl);
                        s.headers.put("Origin", origin);
                        s.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                        return s;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao resolver RDEmbed ref: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extrai streams HLS declarados inline (jwplayer, const streamUrl, etc.).
     */
    private static ResolvedStream parseInlineHlsStream(String html, String targetUrl, OkHttpClient client) {
        Pattern p = Pattern.compile("(?:streamUrl\\s*=\\s*|file:\\s*|source:\\s*)[\"']([^\"']+\\.m3u8[^\"']*)[\"']");
        Matcher m = p.matcher(html);
        if (!m.find()) return null;

        String rawUrl = m.group(1).replace("\\/", "/");

        // Verificação para satlabscloud.com.br (pescaplay/rdcanais): se retornar 404, não entregar URL inválida
        if (rawUrl.contains("satlabscloud.com.br")) {
            if (!isStreamUrlAlive(rawUrl, targetUrl, client)) {
                Log.w(TAG, "Stream satlabscloud retornou 404 (offline na origem): " + rawUrl);
                return null;
            }
        }

        ResolvedStream stream = new ResolvedStream();
        stream.type = ResolvedStream.Type.HLS;
        stream.streamUrl = rawUrl;
        stream.headers.put("Referer", targetUrl);
        stream.headers.put("Origin", extractOrigin(targetUrl));
        stream.headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        return stream;
    }

    private static boolean isStreamUrlAlive(String streamUrl, String referer, OkHttpClient client) {
        try {
            Request req = new Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", referer)
                    .header("Origin", extractOrigin(referer))
                    .header("Range", "bytes=0-10")
                    .build();
            try (Response res = client.newCall(req).execute()) {
                return res.isSuccessful() || res.code() == 206;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve manifest assinado via Globo Video Session API (localhost.tattoo).
     */
    private static ResolvedStream parseGloboSessionStream(String html, String targetUrl, OkHttpClient client) {
        try {
            Matcher mCfg = Pattern.compile("(?s)window\\.__PLAYER_CFG\\s*=\\s*(\\{.*?\\});").matcher(html);
            if (!mCfg.find()) return null;

            JSONObject cfg = new JSONObject(mCfg.group(1));
            String endpoint = cfg.optString("endpoint", "").replace("\\/", "/");
            String token = cfg.optString("token", "");
            String videoId = cfg.optString("id", "");
            String lat = cfg.optString("lat", "-23.5505199");
            String lon = cfg.optString("long", "-46.6333094");

            if (endpoint.isEmpty() || token.isEmpty()) return null;

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("video_id", videoId);
            bodyJson.put("token", token);
            bodyJson.put("lat", lat);
            bodyJson.put("long", lon);

            RequestBody rb = RequestBody.create(bodyJson.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request req = new Request.Builder()
                    .url(endpoint)
                    .post(rb)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", targetUrl)
                    .build();

            try (Response res = client.newCall(req).execute()) {
                if (res.isSuccessful() && res.body() != null) {
                    String resStr = res.body().string();
                    JSONObject resObj = new JSONObject(resStr);
                    String hlsUrl = "";
                    if (resObj.has("sources")) {
                        JSONArray srcs = resObj.getJSONArray("sources");
                        for (int i = 0; i < srcs.length(); i++) {
                            JSONObject sObj = srcs.getJSONObject(i);
                            String u = sObj.optString("url", "");
                            if (u.contains(".m3u8")) {
                                hlsUrl = u;
                                break;
                            }
                        }
                    }
                    if (hlsUrl.isEmpty()) {
                        hlsUrl = resObj.optString("url", "");
                    }

                    if (!hlsUrl.isEmpty()) {
                        ResolvedStream s = new ResolvedStream();
                        s.type = ResolvedStream.Type.HLS;
                        s.streamUrl = hlsUrl;
                        s.headers.put("Referer", "https://globoplay.globo.com/");
                        return s;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao resolver Globo session: " + e.getMessage());
        }
        return null;
    }

    private static String extractOrigin(String url) {
        try {
            Uri uri = Uri.parse(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "https://rdcanais.net";
        }
    }

    /**
     * Constrói o MediaSource do ExoPlayer (HLS ou DASH com ClearKey DRM).
     */
    public static MediaSource buildMediaSource(@NonNull Context context, @NonNull ResolvedStream stream, @NonNull OkHttpClient client) {
        OkHttpDataSource.Factory srcFactory = new OkHttpDataSource.Factory(client)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        if (stream.headers != null && !stream.headers.isEmpty()) {
            srcFactory.setDefaultRequestProperties(stream.headers);
        }

        MediaItem.Builder miBuilder = new MediaItem.Builder().setUri(stream.streamUrl);
        if (stream.type == ResolvedStream.Type.HLS) {
            miBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (stream.type == ResolvedStream.Type.DASH) {
            miBuilder.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        MediaItem mediaItem = miBuilder.build();

        if (stream.type == ResolvedStream.Type.DASH) {
            DashMediaSource.Factory dashFactory = new DashMediaSource.Factory(srcFactory);

            if (stream.isDrmProtected()) {
                byte[] keyResponse = buildClearKeyJsonResponse(stream.clearKeyId, stream.clearKey);
                LocalMediaDrmCallback drmCallback = new LocalMediaDrmCallback(keyResponse);
                DrmSessionManager drmSessionManager = new DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(drmCallback);

                dashFactory.setDrmSessionManagerProvider(unusedMediaItem -> drmSessionManager);
                Log.i(TAG, "Configurado ClearKey DRM para DASH com KeyId=" + stream.clearKeyId);
            }

            return dashFactory.createMediaSource(mediaItem);
        } else {
            // HLS: allowChunklessPreparation = false garante que o ExoPlayer baixe o 1º fragmento
            // (.woff MPEG-TS do RDEmbed) e monte os renderizadores de vídeo e áudio nativos corretamente
            return new HlsMediaSource.Factory(srcFactory)
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(mediaItem);
        }
    }

    /**
     * Converte as chaves hexadecimais em payload JSON compatível com a especificação W3C ClearKey CDM.
     */
    public static byte[] buildClearKeyJsonResponse(String hexKeyId, String hexKey) {
        String cleanId = (hexKeyId != null) ? hexKeyId.replaceAll("[^a-fA-F0-9]", "") : "";
        String cleanKey = (hexKey != null) ? hexKey.replaceAll("[^a-fA-F0-9]", "") : "";

        String kidB64 = base64UrlEncodeNoPadding(hexToBytes(cleanId));
        String kB64 = base64UrlEncodeNoPadding(hexToBytes(cleanKey));

        String json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"" + kB64 + "\",\"kid\":\"" + kidB64 + "\"}],\"type\":\"temporary\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static String base64UrlEncodeNoPadding(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
