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
import com.andplay.app.model.Episode;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    public interface OnEpisodeClickListener {
        void onEpisodeClick(Episode episode);
    }

    private final Context context;
    private final List<Episode> episodes;
    private final OnEpisodeClickListener listener;

    public EpisodeAdapter(Context context, List<Episode> episodes, OnEpisodeClickListener listener) {
        this.context = context;
        this.episodes = episodes;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_episode_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode ep = episodes.get(position);
        holder.num.setText(String.format("E%02d", ep.episode_num));
        holder.title.setText(ep.getDisplayTitle());
        holder.duration.setText(ep.getDurationText());

        String thumb = ep.getThumbUrl();
        if (thumb != null && !thumb.isEmpty()) {
            Glide.with(context)
                    .load(thumb)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.thumb);
        } else {
            holder.thumb.setImageResource(R.drawable.card_focus_bg);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEpisodeClick(ep);
        });

        if (holder.watchBtn != null) {
            long saved = 0;
            try {
                if (ep.id != null) {
                    saved = context.getSharedPreferences("vod_playback_progress_prefs", Context.MODE_PRIVATE)
                            .getLong("episode_" + ep.id, 0);
                }
            } catch (Exception ignored) {}
            if (saved > 10000) {
                holder.watchBtn.setText("▶ Retomar");
            } else {
                holder.watchBtn.setText("▶ Assistir");
            }
        }

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            v.setScaleX(1.0f);
            v.setScaleY(1.0f);
            v.setElevation(hasFocus ? 4f : 0f);
        });
    }

    @Override
    public int getItemCount() {
        return episodes.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView num;
        ImageView thumb;
        TextView title;
        TextView duration;
        TextView watchBtn;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            num = itemView.findViewById(R.id.epNum);
            thumb = itemView.findViewById(R.id.epThumb);
            title = itemView.findViewById(R.id.epTitle);
            duration = itemView.findViewById(R.id.epDuration);
            watchBtn = itemView.findViewById(R.id.epWatchBtn);
        }
    }
}
