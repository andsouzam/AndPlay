package com.andplay.app.model;

import java.io.Serializable;

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

    public String getDisplayTitle() {
        if (name != null && !name.isEmpty()) return name;
        if (title != null && !title.isEmpty()) return title;
        return "Série";
    }

    public String getPosterUrl() {
        if (cover != null && !cover.isEmpty()) return cover;
        return "";
    }
}
