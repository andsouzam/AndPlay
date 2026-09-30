package com.andplay.app.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class WatchProgressItem implements Serializable {
    @SerializedName("content_type")
    public String contentType; // "movie" ou "series"

    @SerializedName("content_id")
    public String contentId;

    @SerializedName("position")
    public long position; // segundos

    @SerializedName("duration")
    public long duration; // segundos

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
    public long updatedAt;

    public WatchProgressItem() {}

    public int getProgressPercent() {
        if (duration <= 0) return 0;
        int pct = (int) Math.round(((double) position / (double) duration) * 100.0);
        return Math.max(0, Math.min(100, pct));
    }

    public boolean isCompleted() {
        return getProgressPercent() >= 95;
    }
}
