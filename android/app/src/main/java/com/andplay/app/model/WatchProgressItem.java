package com.andplay.app.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class WatchProgressItem implements Serializable {
    @SerializedName("content_type")
    public String contentType; // "movie" ou "series"

    @SerializedName("content_id")
    public String contentId;

    @SerializedName("position")
    public double position; // segundos (suporta inteiro ou decimal)

    @SerializedName("duration")
    public double duration; // segundos

    @SerializedName("title")
    public String title;

    @SerializedName("poster")
    public String poster;

    @SerializedName("series_id")
    public String seriesId;

    @SerializedName("season_num")
    public int seasonNum;

    @SerializedName("episode_num")
    public int episodeNum;

    @SerializedName("updated_at")
    public String updatedAt;

    public WatchProgressItem() {}

    public int getProgressPercent() {
        if (duration <= 0) return 0;
        int pct = (int) Math.round((position / duration) * 100.0);
        return Math.max(0, Math.min(100, pct));
    }

    public boolean isCompleted() {
        return getProgressPercent() >= 95;
    }

    public long getUpdatedAtMs() {
        if (updatedAt == null || updatedAt.trim().isEmpty()) return System.currentTimeMillis();
        try {
            java.text.SimpleDateFormat isoFmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            isoFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String clean = updatedAt.split("\\.")[0];
            java.util.Date d = isoFmt.parse(clean);
            return d != null ? d.getTime() : System.currentTimeMillis();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }
}
