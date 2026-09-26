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
    public String homeLogo;
    public String awayLogo;
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
