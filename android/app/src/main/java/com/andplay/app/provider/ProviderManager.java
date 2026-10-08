package com.andplay.app.provider;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProviderManager {

    public static final String PREF_NAME = "provider_prefs";
    public static final String KEY_PRIORITY = "provider_priority";

    public static final String PROVIDER_RDCANAIS = "rdcanais";
    public static final String PROVIDER_RDEMBED = "rdembed";
    public static final String PROVIDER_STREAMVERDE = "streamverde";

    public static final String KEY_CHANNEL_PREFIX = "channel_prov_";

    // Canais conhecidos com melhor funcionamento via RDEmbed (v2.rdembed.sbs)
    private static final java.util.Set<String> PREFERRED_RDEMBED = new java.util.HashSet<>(Arrays.asList(
            "hbo", "hbo2", "hboplus", "hbofamily", "hbosignature", "hbomundi", "hboxtreme", "hbopop",
            "amc", "cinemax", "megapix", "telecinecult", "telecinefun", "paramountnetwork",
            "warnerchannel", "warner", "sonymovies", "sonychannel", "starchannel", "fx",
            "usanetwork", "axn", "tntnovelas", "adultswim", "syfy", "eentertainment",
            "ae", "aie", "lifetime", "tooncast", "dreamworks"
    ));

    // Canais conhecidos com melhor funcionamento via RDCanais (rdcanais.org / rdcanais.net)
    private static final java.util.Set<String> PREFERRED_RDCANAIS = new java.util.HashSet<>(Arrays.asList(
            "premiere", "premiere2", "premiere3", "premiere4", "premiere5", "premiere6", "premiere7", "premiere8", "premiereclubes",
            "sportv", "sportv2", "sportv3", "espn", "espn2", "espn3", "espn4", "espn5", "espn6",
            "cazetv", "cazetv2", "cazetv3", "combate", "bandsports", "tnt", "space",
            "globo", "globosp", "globorj", "globodf", "globomg", "globonordeste", "globointerior",
            "band", "bandsp", "record", "recordtv", "redetv", "globonews", "cnnbrasil", "bandnews", "jpnews",
            "multishow", "viva", "gnt", "universal", "universaltv", "canaloff", "bis", "modoviagem",
            "discovery", "discoveryworld", "discoverytheater", "discoveryturbo", "discoveryscience",
            "discoveryhomehealth", "discoverykids", "history", "history2", "natgeo", "animalplanet",
            "investigacaodiscovery", "cartoon", "gloob", "gloobinho", "disney"
    ));

    public static String normalizeChannelKey(String channelId) {
        if (channelId == null) return "";
        return channelId.replaceFirst("^canal/", "").replaceFirst("\\.html$", "")
                .replace("-", "").trim().toLowerCase();
    }

    /**
     * Retorna o provedor recomendado no setup inicial baseado nos testes práticos.
     */
    public static String getPreAppliedProviderForChannel(String channelId) {
        String key = normalizeChannelKey(channelId);
        if (key.isEmpty()) return null;
        if (key.contains("hbo") || PREFERRED_RDEMBED.contains(key)) {
            return PROVIDER_RDEMBED;
        }
        if (PREFERRED_RDCANAIS.contains(key) || key.contains("sportv") || key.contains("premiere") || key.contains("espn") || key.contains("globo")) {
            return PROVIDER_RDCANAIS;
        }
        return null;
    }

    /**
     * Retorna a escolha personalizada salva pelo usuário para o canal especificado, ou null.
     */
    public static String getChannelProviderOverride(Context context, String channelId) {
        if (context == null || channelId == null) return null;
        String key = normalizeChannelKey(channelId);
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CHANNEL_PREFIX + key, null);
    }

    /**
     * Salva a preferência de provedor para um canal específico.
     * Se providerId for nulo ou "auto", remove o override para voltar ao setup inicial.
     */
    public static void setChannelProviderOverride(Context context, String channelId, String providerId) {
        if (context == null || channelId == null) return;
        String key = normalizeChannelKey(channelId);
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (providerId == null || "auto".equalsIgnoreCase(providerId)) {
            prefs.edit().remove(KEY_CHANNEL_PREFIX + key).apply();
        } else {
            prefs.edit().putString(KEY_CHANNEL_PREFIX + key, providerId).apply();
        }
    }

    /**
     * Retorna o provedor efetivo para o canal:
     * 1º: Escolha explícita do usuário para o canal;
     * 2º: Setup pré-aplicado de testes;
     * 3º: Provedor primário global do sistema.
     */
    public static String getEffectiveChannelProvider(Context context, String channelId) {
        String override = getChannelProviderOverride(context, channelId);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        String preApplied = getPreAppliedProviderForChannel(channelId);
        if (preApplied != null && !preApplied.isEmpty()) {
            return preApplied;
        }
        List<String> global = getPriorityList(context);
        return !global.isEmpty() ? global.get(0) : PROVIDER_RDCANAIS;
    }

    /**
     * Retorna a lista de prioridade reordenada para um canal específico.
     */
    public static List<String> getPriorityListForChannel(Context context, String channelId) {
        String primary = getEffectiveChannelProvider(context, channelId);
        List<String> list = new ArrayList<>();
        list.add(primary);
        List<String> global = getPriorityList(context);
        for (String p : global) {
            if (!list.contains(p)) list.add(p);
        }
        if (!list.contains(PROVIDER_RDCANAIS)) list.add(PROVIDER_RDCANAIS);
        if (!list.contains(PROVIDER_RDEMBED)) list.add(PROVIDER_RDEMBED);
        if (!list.contains(PROVIDER_STREAMVERDE)) list.add(PROVIDER_STREAMVERDE);
        return list;
    }

    public static String getProviderDisplayName(String id) {
        if (PROVIDER_STREAMVERDE.equals(id)) {
            return "StreamVerde (streamverde.net)";
        } else if (PROVIDER_RDEMBED.equals(id)) {
            return "RDEmbed (v2.rdembed.sbs)";
        } else {
            return "RDCanais (rdcanais.net)";
        }
    }

    public static List<String> getPriorityList(Context context) {
        if (context == null) {
            return new ArrayList<>(Arrays.asList(PROVIDER_RDCANAIS, PROVIDER_RDEMBED, PROVIDER_STREAMVERDE));
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_PRIORITY, "rdcanais,rdembed,streamverde");

        String[] parts = saved.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String clean = p.trim();
            if (!clean.isEmpty() && !list.contains(clean)) {
                list.add(clean);
            }
        }
        if (!list.contains(PROVIDER_RDCANAIS)) list.add(PROVIDER_RDCANAIS);
        if (!list.contains(PROVIDER_RDEMBED)) list.add(PROVIDER_RDEMBED);
        if (!list.contains(PROVIDER_STREAMVERDE)) list.add(PROVIDER_STREAMVERDE);
        return list;
    }

    public static void setPriorityList(Context context, List<String> list) {
        if (context == null || list == null || list.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PRIORITY, sb.toString()).apply();
    }

    public static void setPrimaryProvider(Context context, String primaryProvider) {
        List<String> list = new ArrayList<>();
        list.add(primaryProvider);
        if (!PROVIDER_STREAMVERDE.equals(primaryProvider)) list.add(PROVIDER_STREAMVERDE);
        if (!PROVIDER_RDCANAIS.equals(primaryProvider)) list.add(PROVIDER_RDCANAIS);
        if (!PROVIDER_RDEMBED.equals(primaryProvider)) list.add(PROVIDER_RDEMBED);
        setPriorityList(context, list);
    }
}
