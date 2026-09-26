package com.andplay.app.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Channel implements Serializable {
    public String id;
    public String name;
    public String cat;
    public String key;
    public String logo;
    public String embed;
    public boolean guide;
    public String now;
    public int prog;
    public List<NextProgram> next = new ArrayList<>();

    public static class NextProgram implements Serializable {
        public String t;
        public String s;
    }

    public static class StreamFallback implements Serializable {
        public String name;
        public String url;
        public boolean isEmbed;

        public StreamFallback(String name, String url, boolean isEmbed) {
            this.name = name;
            this.url = url;
            this.isEmbed = isEmbed;
        }
    }

    public List<StreamFallback> getFallbacks(android.content.Context context) {
        List<String> priority = com.andplay.app.provider.ProviderManager.getPriorityList(context);
        return getFallbacks(priority);
    }

    public List<StreamFallback> getFallbacks(List<String> priorityOrder) {
        List<StreamFallback> list = new ArrayList<>();
        String cleanSlug = (id != null) ? id.replaceFirst("^canal/", "").replaceFirst("\\.html$", "") : "";
        String rdSlug = cleanSlug.replaceFirst("^telecine-", "telecine").replaceFirst("^hbo-", "hbo");

        // Native stream overrides (YouTube, direct HLS)
        if ("recordnews".equals(id)) {
            list.add(new StreamFallback("HLS Nativo 1080p", "https://jmp2.uk/plu-6102e04e9ab1db0007a980a1.m3u8", false));
        } else if ("bobesponja".equals(id)) {
            list.add(new StreamFallback("HLS Nativo 1080p", "https://jmp2.uk/plu-62545c0b002f4b0007688b61.m3u8", false));
        } else if ("avatar".equals(id)) {
            list.add(new StreamFallback("HLS Nativo 1080p", "https://jmp2.uk/plu-6759eeb1bd523200083b4f29.m3u8", false));
        }

        if (priorityOrder == null || priorityOrder.isEmpty()) {
            priorityOrder = java.util.Arrays.asList(
                    com.andplay.app.provider.ProviderManager.PROVIDER_STREAMVERDE,
                    com.andplay.app.provider.ProviderManager.PROVIDER_RDCANAIS,
                    com.andplay.app.provider.ProviderManager.PROVIDER_RDEMBED
            );
        }

        int serverNum = 1;
        for (String prov : priorityOrder) {
            if (com.andplay.app.provider.ProviderManager.PROVIDER_RDCANAIS.equals(prov)) {
                list.add(new StreamFallback("RDCanais (HD " + serverNum + ")", "https://rdcanais.net/" + rdSlug, true));
                serverNum++;
            } else if (com.andplay.app.provider.ProviderManager.PROVIDER_RDEMBED.equals(prov)) {
                if (embed != null && !embed.isEmpty()) {
                    list.add(new StreamFallback("RDEmbed (HD " + serverNum + ")", embed, true));
                } else {
                    list.add(new StreamFallback("RDEmbed (HD " + serverNum + ")", "https://v2.rdembed.sbs/" + rdSlug, true));
                }
                serverNum++;
            } else if (com.andplay.app.provider.ProviderManager.PROVIDER_STREAMVERDE.equals(prov)) {
                String svSlug = getStreamVerdeSlug(cleanSlug);
                list.add(new StreamFallback("StreamVerde (HD " + serverNum + ")", "https://streamverde.net/canais/" + svSlug + "/embed/", true));
                serverNum++;
            }
        }
        return list;
    }

    public static String getStreamVerdeSlug(String cleanSlug) {
        if (cleanSlug == null || cleanSlug.isEmpty()) return "";
        String s = cleanSlug.toLowerCase();
        if ("warner".equals(s) || "warnerchannel".equals(s)) return "warnerchannel";
        if ("recordsp".equals(s)) return "record";
        if ("bandsp".equals(s)) return "band";
        if ("sbt".equals(s)) return "sbt-central";
        if ("premiere".equals(s)) return "premiere-1";

        if (s.contains("-")) return s;

        if (s.startsWith("globo") && s.length() == 7) {
            return "globo-" + s.substring(5);
        }

        if (s.matches(".*[a-z]+[0-9]+$")) {
            return s.replaceAll("([a-z]+)([0-9]+)", "$1-$2");
        }

        return s;
    }

    public List<StreamFallback> getFallbacks() {
        return getFallbacks((List<String>) null);
    }
}
