package com.andplay.app.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Movie implements Serializable {
    public int num;
    public String name;
    public String title;
    public String year;
    public String stream_type;
    public String stream_id;
    public String stream_icon;
    public String rating;
    public String rating_5based;
    public String added;
    public String category_id;
    public String container_extension;
    public String custom_sid;
    public String direct_source;
    public String plot;
    public String cast;
    public String director;
    public String genre;
    public String release_date;
    public String duration;
    public int progressPercent = 0;
    public boolean isSeries = false;
    public boolean isFavorite = false;

    // Versões agrupadas (Dublado, Legendado, 4K Dublado, 4K Legendado)
    public List<MovieVersion> versions = new ArrayList<>();
    public MovieVersion activeVersion;
    public String versionsSummary; // ex: "4K • DUB • LEG"
    public String cleanTitle;

    public static class MovieVersion implements Serializable {
        public String type;   // "dublado", "legendado", "4k_dub", "4k_leg"
        public String label;  // "Dublado", "Legendado", "4K Ultra HD", "4K Legendado"
        public String badge;  // "DUB", "LEG", "4K", "4K LEG"
        public String icon;   // "🔊", "💬", "✨", "✨"
        public String desc;
        public String streamId;
        public String ext;
        public String quality;
        public Movie rawMovie;

        public MovieVersion() {}

        public MovieVersion(String type, String label, String badge, String icon, String desc, String streamId, String ext, String quality, Movie rawMovie) {
            this.type = type;
            this.label = label;
            this.badge = badge;
            this.icon = icon;
            this.desc = desc;
            this.streamId = streamId;
            this.ext = ext;
            this.quality = quality;
            this.rawMovie = rawMovie;
        }
    }

    public String getDisplayTitle() {
        if (cleanTitle != null && !cleanTitle.isEmpty()) return cleanTitle;
        if (name != null && !name.isEmpty()) return name;
        if (title != null && !title.isEmpty()) return title;
        return "Filme";
    }

    public String getRawTitle() {
        if (name != null && !name.isEmpty()) return name;
        if (title != null && !title.isEmpty()) return title;
        return "Filme";
    }

    public String getPosterUrl() {
        if (stream_icon != null && !stream_icon.isEmpty()) return stream_icon;
        return "";
    }

    public String getStreamUrl(String server, String user, String pass) {
        String activeId = (activeVersion != null && activeVersion.streamId != null) ? activeVersion.streamId : stream_id;
        String ext = (activeVersion != null && activeVersion.ext != null) ? activeVersion.ext :
                ((container_extension != null && !container_extension.isEmpty()) ? container_extension : "mp4");
        return server + "/movie/" + user + "/" + pass + "/" + activeId + "." + ext;
    }
}
