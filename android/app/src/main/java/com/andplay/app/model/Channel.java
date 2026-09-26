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

    public List<StreamFallback> getFallbacks() {
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

        // Primary: RDCanais
        list.add(new StreamFallback("HD 1", "https://rdcanais.net/" + rdSlug, true));
        // Fallback: Embed alternativo oficial
        if (embed != null && !embed.isEmpty()) {
            list.add(new StreamFallback("HD 2", embed, true));
        } else {
            list.add(new StreamFallback("HD 2", "https://v2.rdembed.sbs/" + rdSlug, true));
        }
        // Fallback: RedeCanais / StreamVerde
        list.add(new StreamFallback("Servidor 3", "https://streamverde.net/canais/" + cleanSlug + "/embed", true));
        return list;
    }
}
