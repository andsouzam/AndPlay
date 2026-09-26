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
        if (!PROVIDER_RDCANAIS.equals(primaryProvider)) list.add(PROVIDER_RDCANAIS);
        if (!PROVIDER_RDEMBED.equals(primaryProvider)) list.add(PROVIDER_RDEMBED);
        if (!PROVIDER_STREAMVERDE.equals(primaryProvider)) list.add(PROVIDER_STREAMVERDE);
        setPriorityList(context, list);
    }
}
