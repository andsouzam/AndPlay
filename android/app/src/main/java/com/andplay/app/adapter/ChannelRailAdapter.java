package com.andplay.app.adapter;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.andplay.app.R;
import com.andplay.app.epg.EpgEngine;
import com.andplay.app.model.Channel;
import com.andplay.app.model.LiveSchedule;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;

public class ChannelRailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(Channel channel, int index);
    }

    private final Context context;
    private final List<Channel> channels;
    private final boolean isDrawerMode;
    private final OnChannelClickListener listener;
    private int currentPlayingIdx = -1;

    public ChannelRailAdapter(Context context, List<Channel> channels, boolean isDrawerMode, OnChannelClickListener listener) {
        this.context = context;
        this.channels = channels;
        this.isDrawerMode = isDrawerMode;
        this.listener = listener;
    }

    public void setCurrentPlayingIdx(int idx) {
        this.currentPlayingIdx = idx;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (isDrawerMode) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_drawer_channel, parent, false);
            return new DrawerViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_channel_card, parent, false);
            return new RailViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Channel ch = channels.get(position);
        int chNumber = position + 1;

        if (holder instanceof RailViewHolder) {
            RailViewHolder vh = (RailViewHolder) holder;
            vh.num.setText(String.format("CH %03d", chNumber));
            vh.name.setText(ch.name != null ? ch.name : "Canal");

            if (ch.logo != null && !ch.logo.isEmpty()) {
                Glide.with(context)
                        .load(ch.logo)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.card_focus_bg)
                        .into(vh.logo);
            } else {
                vh.logo.setImageResource(R.drawable.card_focus_bg);
            }

            vh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChannelClick(ch, position);
            });

            vh.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    v.performClick();
                    return true;
                }
                return false;
            });

            vh.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.05f : 1.0f).scaleY(hasFocus ? 1.05f : 1.0f).setDuration(120).start();
            });

        } else if (holder instanceof DrawerViewHolder) {
            DrawerViewHolder vh = (DrawerViewHolder) holder;
            vh.num.setText(String.format("%03d", chNumber));
            vh.name.setText(ch.name != null ? ch.name : "Canal");

            LiveSchedule epg = EpgEngine.getLiveSchedule(ch);
            vh.program.setText(epg != null && epg.nowTitle != null ? epg.nowTitle : "Ao Vivo");

            boolean isPlaying = (position == currentPlayingIdx);
            vh.liveBadge.setVisibility(isPlaying ? View.VISIBLE : View.GONE);

            if (ch.logo != null && !ch.logo.isEmpty()) {
                Glide.with(context)
                        .load(ch.logo)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(vh.logo);
            } else {
                vh.logo.setImageResource(R.drawable.card_focus_bg);
            }

            vh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChannelClick(ch, position);
            });

            vh.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    v.performClick();
                    return true;
                }
                return false;
            });

            vh.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setSelected(hasFocus);
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                v.setElevation(hasFocus ? 4f : 0f);
            });
        }
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class RailViewHolder extends RecyclerView.ViewHolder {
        TextView num;
        ImageView logo;
        TextView name;

        RailViewHolder(@NonNull View itemView) {
            super(itemView);
            num = itemView.findViewById(R.id.channelNum);
            logo = itemView.findViewById(R.id.channelLogo);
            name = itemView.findViewById(R.id.channelName);
        }
    }

    static class DrawerViewHolder extends RecyclerView.ViewHolder {
        TextView num;
        ImageView logo;
        TextView name;
        TextView program;
        TextView liveBadge;

        DrawerViewHolder(@NonNull View itemView) {
            super(itemView);
            num = itemView.findViewById(R.id.drawerChNum);
            logo = itemView.findViewById(R.id.drawerChLogo);
            name = itemView.findViewById(R.id.drawerChName);
            program = itemView.findViewById(R.id.drawerChProgram);
            liveBadge = itemView.findViewById(R.id.drawerLiveBadge);
        }
    }
}
