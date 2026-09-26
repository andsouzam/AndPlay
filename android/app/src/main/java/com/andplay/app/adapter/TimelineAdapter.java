package com.andplay.app.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.andplay.app.R;
import com.andplay.app.epg.EpgEngine.TimelineProgram;

import java.util.List;

public class TimelineAdapter extends RecyclerView.Adapter<TimelineAdapter.ViewHolder> {

    public interface OnTimelineActionListener {
        void onProgramClick(TimelineProgram program);
        void onProgramFocus(TimelineProgram program, int position);
    }

    private final Context context;
    private final List<TimelineProgram> programs;
    private final boolean isSelectable;
    private final OnTimelineActionListener listener;

    public TimelineAdapter(Context context, List<TimelineProgram> programs, boolean isSelectable, OnTimelineActionListener listener) {
        this.context = context;
        this.programs = programs;
        this.isSelectable = isSelectable;
        this.listener = listener;
    }

    public TimelineAdapter(Context context, List<TimelineProgram> programs, OnTimelineActionListener listener) {
        this(context, programs, true, listener);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_timeline_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TimelineProgram prog = programs.get(position);
        holder.title.setText(prog.title != null ? prog.title : "Sem título");
        holder.time.setText(prog.timeRange != null ? prog.timeRange : "--:--");

        if (prog.isCurrent) {
            holder.badge.setText("🔴 NO AR");
            holder.badge.setBackgroundResource(R.drawable.badge_gold);
            holder.badge.setTextColor(Color.BLACK);
            holder.progressBar.setVisibility(View.VISIBLE);
            holder.progressBar.setProgress(Math.max(5, prog.progress));
            holder.remaining.setVisibility(View.VISIBLE);
            holder.remaining.setText("Restam ~" + prog.remainingMin + " min");
        } else if (prog.isPast) {
            holder.badge.setText("⏪ EXIBIDO");
            holder.badge.setBackgroundColor(Color.parseColor("#333A48"));
            holder.badge.setTextColor(Color.parseColor("#99A3B0"));
            holder.progressBar.setVisibility(View.GONE);
            holder.remaining.setVisibility(View.GONE);
        } else {
            holder.badge.setText("⏱️ EM BREVE");
            holder.badge.setBackgroundColor(Color.parseColor("#1B3358"));
            holder.badge.setTextColor(Color.parseColor("#7AB2F5"));
            holder.progressBar.setVisibility(View.GONE);
            holder.remaining.setVisibility(View.GONE);
        }

        if (isSelectable) {
            holder.itemView.setFocusable(true);
            holder.itemView.setClickable(true);
            holder.itemView.setFocusableInTouchMode(true);
            holder.itemView.setForeground(ContextCompat.getDrawable(context, R.drawable.card_focus_fg));

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onProgramClick(prog);
            });

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setSelected(hasFocus);
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                v.setElevation(hasFocus ? 6f : 0f);
                if (hasFocus && listener != null) {
                    listener.onProgramFocus(prog, holder.getAdapterPosition());
                }
            });
        } else {
            holder.itemView.setFocusable(false);
            holder.itemView.setClickable(false);
            holder.itemView.setFocusableInTouchMode(false);
            holder.itemView.setForeground(null);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnFocusChangeListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return programs != null ? programs.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView badge;
        TextView remaining;
        TextView time;
        TextView title;
        ProgressBar progressBar;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            badge = itemView.findViewById(R.id.timelineBadge);
            remaining = itemView.findViewById(R.id.timelineRemaining);
            time = itemView.findViewById(R.id.timelineTime);
            title = itemView.findViewById(R.id.timelineTitle);
            progressBar = itemView.findViewById(R.id.timelineProgressBar);
        }
    }
}
