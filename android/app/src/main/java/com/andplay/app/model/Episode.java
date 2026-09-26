package com.andplay.app.model;

import java.io.Serializable;

public class Episode implements Serializable {
    public String id;
    public int episode_num;
    public String title;
    public String container_extension;
    public EpisodeInfo info;

    public static class EpisodeInfo implements Serializable {
        public String movie_image;
        public String plot;
        public String duration_secs;
        public String duration;
        public String rating;
    }

    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) return title;
        return "Episódio " + episode_num;
    }

    public String getThumbUrl() {
        if (info != null && info.movie_image != null && !info.movie_image.isEmpty()) {
            return info.movie_image;
        }
        return "";
    }

    public String getDurationText() {
        if (info != null) {
            if (info.duration_secs != null) {
                try {
                    int sec = Integer.parseInt(info.duration_secs);
                    if (sec > 0) return (sec / 60) + " min";
                } catch (Exception ignored) {}
            }
            if (info.duration != null && !info.duration.isEmpty()) {
                return info.duration;
            }
        }
        return "";
    }

    public String getStreamUrl(String server, String user, String pass) {
        String ext = (container_extension != null && !container_extension.isEmpty()) ? container_extension : "mp4";
        return server + "/series/" + user + "/" + pass + "/" + id + "." + ext;
    }
}
