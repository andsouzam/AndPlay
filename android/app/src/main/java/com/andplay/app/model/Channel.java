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

        // Native stream overrides (direct official CDN HLS streams from tvacabo.top)
        if ("recordnews".equals(id)) {
            list.add(new StreamFallback("Record News (HLS Nativo)", "https://rnw-rn-samsungtvplus.otteravision.com/rnw/rn/rnw_rn.m3u8", false));
        } else if ("sbt".equals(id)) {
            list.add(new StreamFallback("MaisSBT (HLS Direto)", "https://aovivo.maissbt.com/indexMobile.m3u8", false));
        } else if ("sbtnews".equals(id)) {
            list.add(new StreamFallback("SBT News (HLS Direto)", "https://sbtnews.maissbt.com/index.m3u8", false));
        } else if ("globoba".equals(id) || "tvbahia".equals(id)) {
            list.add(new StreamFallback("TV Bahia HD (HLS Direto)", "http://hls1.sua.tv/live/globotvbahiafhdbr2/s.m3u8", false));
        } else if ("tvbrasil".equals(id)) {
            list.add(new StreamFallback("TV Brasil (HLS Direto)", "https://tvbrasil-stream.ebc.com.br/index.m3u8", false));
        } else if ("cultura".equals(id) || "tvcultura".equals(id)) {
            list.add(new StreamFallback("TV Cultura (HLS Direto)", "https://player-tvcultura.stream.uol.com.br/live/tvcultura.m3u8", false));
        } else if ("nicktoons".equals(id)) {
            list.add(new StreamFallback("Nicktoons (HLS Direto)", "https://stmv2.srvif.com/nicktoons/nicktoons/playlist.m3u8", false));
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
                String rdCleanSlug = getRdCanaisSlug(cleanSlug, id);
                list.add(new StreamFallback("RDCanais (HD " + serverNum + ")", "https://rdcanais.net/" + rdCleanSlug, true));
                serverNum++;
            } else if (com.andplay.app.provider.ProviderManager.PROVIDER_RDEMBED.equals(prov)) {
                if (embed != null && !embed.isEmpty()) {
                    list.add(new StreamFallback("RDEmbed (HD " + serverNum + ")", embed, true));
                } else {
                    list.add(new StreamFallback("RDEmbed (HD " + serverNum + ")", "https://v2.rdembed.sbs/" + rdSlug, true));
                }
                serverNum++;
            } else if (com.andplay.app.provider.ProviderManager.PROVIDER_STREAMVERDE.equals(prov)) {
                String svSlug = getStreamVerdeSlug(cleanSlug, id);
                list.add(new StreamFallback("StreamVerde (HLS Direto)", "https://svd.cazetv.shop/streamverde/" + svSlug + ".m3u8", false));
                serverNum++;
            }
        }

        return list;
    }

    public static String getRdCanaisSlug(String cleanSlug, String id) {
        String s = (cleanSlug != null && !cleanSlug.isEmpty()) ? cleanSlug : (id != null ? id : "");
        s = s.replaceFirst("^canal/", "").replaceFirst("\\.html$", "").trim().toLowerCase();

        if ("premiere".equals(s)) return "premiereclubes";
        if ("universal".equals(s)) return "universaltv";
        if ("warnerchannel".equals(s)) return "warner";

        return s.replace("-", "");
    }

    public static String getStreamVerdeSlug(String cleanSlug, String id) {
        if (cleanSlug == null) cleanSlug = "";
        String s = cleanSlug.replace("-", "").toLowerCase();
        if ("warner".equals(s) || "warner".equalsIgnoreCase(id) || "warnerchannel".equals(s)) {
            return "warnerchannel";
        }
        if ("premiere".equals(s) || "premiereclubes".equals(s) || "premiere1".equals(s)) {
            return "premiere";
        }
        if ("recordsp".equals(s)) {
            return "recordsp";
        }
        if ("bandsp".equals(s)) {
            return "bandsp";
        }
        return s;
    }

    public List<StreamFallback> getFallbacks() {
        return getFallbacks((List<String>) null);
    }
}
