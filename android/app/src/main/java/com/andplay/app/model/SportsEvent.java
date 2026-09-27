package com.andplay.app.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SportsEvent implements Serializable {
    public String id;
    public String name;
    public String league;
    public String matchTime;
    public boolean isLive;
    public boolean isFinished;
    public long startTimestamp; // Unix timestamp em segundos (0 se desconhecido)
    public String homeLogo;
    public String awayLogo;
    public String homeName;
    public String awayName;
    public String homeScore;
    public String awayScore;
    public String score; // e.g. "2 x 2"
    public String clock; // e.g. "75'", "HT", "FT"
    public String embedUrl;
    public String hlsUrl;
    public List<String> candidateChannels = new ArrayList<>();
    public List<Channel.StreamFallback> fallbacks = new ArrayList<>();

    public String getDisplayName() {
        return (name != null) ? name : "Evento Esportivo";
    }

    public String getDisplayLeague() {
        return (league != null && !league.isEmpty()) ? league : "Futebol";
    }
}
