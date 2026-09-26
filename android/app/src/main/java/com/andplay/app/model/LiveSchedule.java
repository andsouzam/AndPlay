package com.andplay.app.model;

import java.io.Serializable;

public class LiveSchedule implements Serializable {
    public String nowTitle;
    public String synopsis;
    public String start;
    public String end;
    public String timeRange;
    public int progress;
    public int remainingMinutes;
    public String nextTitle;
    public String nextStart;

    public LiveSchedule() {}

    public LiveSchedule(String nowTitle, String synopsis, String start, String end, int progress, int remainingMinutes, String nextTitle, String nextStart) {
        this.nowTitle = nowTitle;
        this.synopsis = synopsis;
        this.start = start;
        this.end = end;
        this.timeRange = start + " • " + end;
        this.progress = progress;
        this.remainingMinutes = remainingMinutes;
        this.nextTitle = nextTitle;
        this.nextStart = nextStart;
    }
}
