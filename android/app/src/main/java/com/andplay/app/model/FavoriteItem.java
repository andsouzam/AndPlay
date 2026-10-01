package com.andplay.app.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class FavoriteItem implements Serializable {
    @SerializedName("content_type")
    public String contentType; // "movie" ou "series"

    @SerializedName("content_id")
    public String contentId;

    @SerializedName("title")
    public String title;

    @SerializedName("poster")
    public String poster;

    @SerializedName("created_at")
    public String createdAt;

    @SerializedName("updated_at")
    public String updatedAt;

    public FavoriteItem() {}

    public FavoriteItem(String contentType, String contentId, String title, String poster) {
        this.contentType = contentType;
        this.contentId = contentId;
        this.title = title;
        this.poster = poster;
        this.updatedAt = String.valueOf(System.currentTimeMillis());
    }
}
