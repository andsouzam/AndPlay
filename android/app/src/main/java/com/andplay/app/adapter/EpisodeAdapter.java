package com.andplay.app.adapter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.andplay.app.R;
import com.andplay.app.model.Episode;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;
import java.util.Locale;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    public interface OnEpisodeClickListener {
        void onEpisodeClick(Episode episode);
    }

    public interface OnEpisodeFocusListener {
        void onEpisodeFocus(Episode episode);
    }

    private final Context context;
    private final List<Episode> episodes;
    private final OnEpisodeClickListener listener;
    private final OnEpisodeFocusListener focusListener;

    public EpisodeAdapter(Context context, List<Episode> episodes, OnEpisodeClickListener listener, OnEpisodeFocusListener focusListener) {
        this.context = context;
        this.episodes = episodes;
        this.listener = listener;
        this.focusListener = focusListener;
    }

    public EpisodeAdapter(Context context, List<Episode> episodes, OnEpisodeClickListener listener) {
        this(context, episodes, listener, null);
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
        if (holder.num != null) {
            holder.num.setText(String.format(Locale.getDefault(), "E%02d", ep.episode_num));
        }
        if (holder.title != null) {
            holder.title.setText(ep.getDisplayTitle());
        }

        StringBuilder details = new StringBuilder();
        String dur = ep.getDurationText();
        if (!dur.isEmpty()) details.append(dur);
        String rating = ep.getRating();
        if (!rating.isEmpty()) {
            if (details.length() > 0) details.append(" • ");
            details.append("★ ").append(rating);
        }
        String date = ep.getReleaseDate();
        if (!date.isEmpty()) {
            if (details.length() > 0) details.append(" • ");
            details.append(date);
        }
        if (holder.duration != null) {
            holder.duration.setText(details.length() > 0 ? details.toString() : "Duração padrão");
        }

        if (holder.plot != null) {
            holder.plot.setText(ep.getPlot());
        }

        String thumb = ep.getThumbUrl();
        if (holder.thumb != null) {
            if (thumb != null && !thumb.isEmpty()) {
                Glide.with(context)
                        .load(thumb)
                        .override(320, 180)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.card_focus_bg)
                        .into(holder.thumb);
            } else {
                holder.thumb.setImageResource(R.drawable.card_focus_bg);
            }
        }

        // Progresso e Estados (Seção 30 e 31: NOVO, RETOMAR, ASSISTIDO)
        long saved = 0;
        long totalDur = 0;
        try {
            if (ep.id != null) {
                SharedPreferences prefs = context.getSharedPreferences("vod_playback_progress_prefs", Context.MODE_PRIVATE);
                saved = prefs.getLong("episode_" + ep.id, 0);
                totalDur = prefs.getLong("episode_" + ep.id + "_dur", 0);
            }
        } catch (Exception ignored) {}

        boolean isCompleted = totalDur > 0 && (saved >= totalDur - 30000 || saved >= (long) (totalDur * 0.95));
        boolean isResuming = !isCompleted && saved > 10000;

        if (holder.progressBar != null) {
            if (isCompleted) {
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setProgress(100);
            } else if (isResuming && totalDur > 0) {
                holder.progressBar.setVisibility(View.VISIBLE);
                int pct = (int) Math.round(((double) saved / (double) totalDur) * 100.0);
                holder.progressBar.setProgress(Math.max(5, Math.min(95, pct)));
            } else {
                holder.progressBar.setVisibility(View.GONE);
            }
        }

        if (holder.watchBtn != null) {
            if (isCompleted) {
                holder.watchBtn.setText("✓ ASSISTIDO");
                holder.watchBtn.setBackgroundResource(R.drawable.badge_pill_dark);
                holder.watchBtn.setTextColor(Color.parseColor("#4ADE80")); // Verde claro
            } else if (isResuming) {
                holder.watchBtn.setText("↻ RETOMAR");
                holder.watchBtn.setBackgroundResource(R.drawable.badge_gold);
                holder.watchBtn.setTextColor(Color.parseColor("#000000"));
            } else {
                holder.watchBtn.setText("▶ ASSISTIR");
                holder.watchBtn.setBackgroundResource(R.drawable.badge_gold);
                holder.watchBtn.setTextColor(Color.parseColor("#000000"));
            }
        }

        if (holder.statusText != null) {
            if (isCompleted) {
                holder.statusText.setVisibility(View.VISIBLE);
                holder.statusText.setText("Concluído");
            } else if (isResuming && totalDur > 0) {
                holder.statusText.setVisibility(View.VISIBLE);
                long remSecTotal = Math.max(0, (totalDur - saved) / 1000);
                long remMin = remSecTotal / 60;
                long remSec = remSecTotal % 60;
                holder.statusText.setText(String.format(Locale.getDefault(), "Restam %d:%02d", remMin, remSec));
            } else {
                holder.statusText.setVisibility(View.GONE);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEpisodeClick(ep);
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                if (listener != null) listener.onEpisodeClick(ep);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    if (context instanceof Activity) {
                        View seasonsRecycler = ((Activity) context).findViewById(R.id.seriesSeasonsRecycler);
                        if (seasonsRecycler != null && seasonsRecycler.isShown()) {
                            seasonsRecycler.requestFocus();
                            return true;
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    if (context instanceof Activity) {
                        View recsRecycler = ((Activity) context).findViewById(R.id.seriesDetailRecommendationsRecycler);
                        if (recsRecycler != null && recsRecycler.isShown()) {
                            recsRecycler.requestFocus();
                            return true;
                        }
                    }
                }
            }
            return false;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            v.animate().scaleX(hasFocus ? 1.02f : 1.0f).scaleY(hasFocus ? 1.02f : 1.0f).setDuration(100).start();
            v.setElevation(hasFocus ? 6f : 0f);
            if (hasFocus && focusListener != null) {
                focusListener.onEpisodeFocus(ep);
            }
        });
    }

    @Override
    public int getItemCount() {
        return episodes != null ? episodes.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView num;
        ImageView thumb;
        ProgressBar progressBar;
        TextView title;
        TextView duration;
        TextView plot;
        TextView watchBtn;
        TextView statusText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            num = itemView.findViewById(R.id.epNum);
            thumb = itemView.findViewById(R.id.epThumb);
            progressBar = itemView.findViewById(R.id.epProgressBar);
            title = itemView.findViewById(R.id.epTitle);
            duration = itemView.findViewById(R.id.epDuration);
            plot = itemView.findViewById(R.id.epPlot);
            watchBtn = itemView.findViewById(R.id.epWatchBtn);
            statusText = itemView.findViewById(R.id.epStatusText);
        }
    }
}
