package com.andplay.app.model;

import java.io.Serializable;

public class Episode implements Serializable {
    public String id;
    public int episode_num;
    public String title;
    public String container_extension;
    public EpisodeInfo info;

    public static class EpisodeInfo implements Serializable {
        public String name;
        public String movie_image;
        public String plot;
        public String duration_secs;
        public String duration;
        public String rating;
        public String releasedate;
        public String release_date;
    }

    public String getDisplayTitle() {
        if (info != null && info.name != null && !info.name.trim().isEmpty()) {
            String in = info.name.trim();
            if (!in.equalsIgnoreCase("Episode " + episode_num) && !in.equalsIgnoreCase("Episódio " + episode_num)) {
                return in;
            }
        }
        if (title != null && !title.trim().isEmpty()) {
            String t = title.trim();
            if (!t.equalsIgnoreCase("Episode " + episode_num) && !t.equalsIgnoreCase("Episódio " + episode_num)) {
                return t;
            }
        }
        if (info != null && info.name != null && !info.name.trim().isEmpty()) {
            return info.name.trim();
        }
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        return "Episódio " + episode_num;
    }

    public String getPlot() {
        if (info != null && info.plot != null && !info.plot.trim().isEmpty()) {
            return info.plot.trim();
        }
        return "Sinopse do episódio não informada.";
    }

    public String getRating() {
        if (info != null && info.rating != null && !info.rating.trim().isEmpty()) {
            return info.rating.trim();
        }
        return "";
    }

    public String getReleaseDate() {
        if (info != null) {
            if (info.releasedate != null && !info.releasedate.trim().isEmpty()) return info.releasedate.trim();
            if (info.release_date != null && !info.release_date.trim().isEmpty()) return info.release_date.trim();
        }
        return "";
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
