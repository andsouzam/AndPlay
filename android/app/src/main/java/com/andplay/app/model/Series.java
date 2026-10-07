package com.andplay.app.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Series implements Serializable {
    public int num;
    public String name;
    public String title;
    public String series_id;
    public String cover;
    public String plot;
    public String cast;
    public String director;
    public String genre;
    public String releaseDate;
    public String last_modified;
    public String rating;
    public String rating_5based;
    public String category_id;
    public List<Integer> category_ids = new ArrayList<>();

    // Versões agrupadas (Dublado, Legendado)
    public List<SeriesVersion> versions = new ArrayList<>();
    public SeriesVersion activeVersion;
    public String versionsSummary; // ex: "DUB • LEG"
    public String cleanTitle;

    public static class SeriesVersion implements Serializable {
        public String type;    // "dublado", "legendado"
        public String label;   // "Dublado", "Legendado"
        public String badge;   // "DUB", "LEG"
        public String icon;    // "🔊", "💬"
        public String desc;
        public String seriesId;
        public Series rawSeries;

        public SeriesVersion() {}

        public SeriesVersion(String type, String label, String badge, String icon, String desc, String seriesId, Series rawSeries) {
            this.type = type;
            this.label = label;
            this.badge = badge;
            this.icon = icon;
            this.desc = desc;
            this.seriesId = seriesId;
            this.rawSeries = rawSeries;
        }
    }

    public String getDisplayTitle() {
        if (cleanTitle != null && !cleanTitle.isEmpty()) return cleanTitle;
        if (name != null && !name.isEmpty()) return name;
        if (title != null && !title.isEmpty()) return title;
        return "Série";
    }

    public String getRawTitle() {
        if (name != null && !name.isEmpty()) return name;
        if (title != null && !title.isEmpty()) return title;
        return "Série";
    }

    public String getPosterUrl() {
        if (cover != null && !cover.isEmpty()) return cover;
        return "";
    }
}
