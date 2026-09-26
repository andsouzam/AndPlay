package com.andplay.app.model;

import java.io.Serializable;
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

    public String getDisplayTitle() {
        if (name != null && !name.isEmpty()) return name;
        if (title != null && !title.isEmpty()) return title;
        return "Filme";
    }

    public String getPosterUrl() {
        if (stream_icon != null && !stream_icon.isEmpty()) return stream_icon;
        return "";
    }

    public String getStreamUrl(String server, String user, String pass) {
        String ext = (container_extension != null && !container_extension.isEmpty()) ? container_extension : "mp4";
        return server + "/movie/" + user + "/" + pass + "/" + stream_id + "." + ext;
    }
}
