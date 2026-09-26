package com.andplay.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.andplay.app.R;
import com.andplay.app.model.SportsEvent;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;

public class SportsRailAdapter extends RecyclerView.Adapter<SportsRailAdapter.ViewHolder> {

    public interface OnSportsClickListener {
        void onSportsClick(SportsEvent event);
    }

    private final Context context;
    private final List<SportsEvent> events;
    private final OnSportsClickListener listener;

    public SportsRailAdapter(Context context, List<SportsEvent> events, OnSportsClickListener listener) {
        this.context = context;
        this.events = events;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_match_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SportsEvent ev = events.get(position);
        holder.league.setText(ev.getDisplayLeague());
        holder.title.setText(ev.getDisplayName());
        holder.time.setText(ev.matchTime != null ? ev.matchTime : "VS");

        holder.badge.setText(ev.isLive ? "AO VIVO" : "HOJE");
        holder.badge.setBackgroundResource(ev.isLive ? R.drawable.badge_live : R.drawable.badge_gold);

        if (ev.homeLogo != null && !ev.homeLogo.isEmpty()) {
            Glide.with(context).load(ev.homeLogo).diskCacheStrategy(DiskCacheStrategy.ALL).into(holder.homeLogo);
        }
        if (ev.awayLogo != null && !ev.awayLogo.isEmpty()) {
            Glide.with(context).load(ev.awayLogo).diskCacheStrategy(DiskCacheStrategy.ALL).into(holder.awayLogo);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSportsClick(ev);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(150).start();
        });
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView league;
        TextView badge;
        ImageView homeLogo;
        ImageView awayLogo;
        TextView time;
        TextView title;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            league = itemView.findViewById(R.id.matchLeague);
            badge = itemView.findViewById(R.id.matchBadge);
            homeLogo = itemView.findViewById(R.id.matchHomeLogo);
            awayLogo = itemView.findViewById(R.id.matchAwayLogo);
            time = itemView.findViewById(R.id.matchTime);
            title = itemView.findViewById(R.id.matchTitle);
        }
    }
}
