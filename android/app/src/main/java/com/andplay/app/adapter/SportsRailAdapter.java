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
import com.andplay.app.model.SportsEvent;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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

        // Exibe o placar ou o horário no centro entre os escudos
        if (ev.score != null && !ev.score.isEmpty()) {
            holder.time.setText(ev.score);
            if (ev.isLive) {
                holder.time.setTextColor(0xFF00E676); // Verde chamativo para jogo ao vivo
            } else if (ev.isFinished) {
                holder.time.setTextColor(0xFFFFFFFF); // Branco para placar final
            } else {
                holder.time.setTextColor(0xFFFFC107);
            }
        } else {
            String centerTime = ev.matchTime != null ? ev.matchTime : "VS";
            if (ev.isLive && "AO VIVO".equalsIgnoreCase(centerTime)) {
                centerTime = "VS";
            }
            holder.time.setText(centerTime);
            holder.time.setTextColor(0xFFFFC107);
        }

        // Determina badge dinâmico
        if (ev.isLive) {
            String badgeText = (ev.clock != null && !ev.clock.isEmpty()) ? "AO VIVO " + ev.clock : "AO VIVO";
            holder.badge.setText(badgeText);
            holder.badge.setBackgroundResource(R.drawable.badge_live);
            holder.badge.setTextColor(0xFF000000);
        } else if (ev.isFinished) {
            holder.badge.setText("FINALIZADO");
            holder.badge.setBackgroundResource(R.drawable.badge_gray);
            holder.badge.setTextColor(0xFFFFFFFF);
        } else {
            // Determina se é HOJE ou AMANHÃ comparando o dia do evento com o dia atual
            String dayLabel = "HOJE";
            if (ev.startTimestamp > 0) {
                try {
                    TimeZone tz = TimeZone.getTimeZone("America/Sao_Paulo");
                    SimpleDateFormat dayFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
                    dayFmt.setTimeZone(tz);
                    String eventDay = dayFmt.format(new Date(ev.startTimestamp * 1000L));
                    String todayDay = dayFmt.format(new Date());
                    String tomorrowDay = dayFmt.format(new Date(System.currentTimeMillis() + 86400000L));
                    if (eventDay.equals(tomorrowDay)) {
                        dayLabel = "AMANHÃ";
                    } else if (!eventDay.equals(todayDay)) {
                        // Além de amanhã: exibe a data resumida
                        SimpleDateFormat shortDate = new SimpleDateFormat("dd/MM", Locale.US);
                        shortDate.setTimeZone(tz);
                        dayLabel = shortDate.format(new Date(ev.startTimestamp * 1000L));
                    }
                } catch (Exception ignored) {}
            }
            if ("AMANHÃ".equals(dayLabel)) {
                holder.badge.setText("AMANHÃ");
                holder.badge.setBackgroundResource(R.drawable.badge_blue);
                holder.badge.setTextColor(0xFFFFFFFF);
            } else {
                holder.badge.setText(dayLabel);
                holder.badge.setBackgroundResource(R.drawable.badge_gold);
                holder.badge.setTextColor(0xFF000000);
            }
        }

        if (ev.homeLogo != null && !ev.homeLogo.isEmpty()) {
            Glide.with(context).load(ev.homeLogo).diskCacheStrategy(DiskCacheStrategy.ALL).into(holder.homeLogo);
        }
        if (ev.awayLogo != null && !ev.awayLogo.isEmpty()) {
            Glide.with(context).load(ev.awayLogo).diskCacheStrategy(DiskCacheStrategy.ALL).into(holder.awayLogo);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSportsClick(ev);
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                v.performClick();
                return true;
            }
            return false;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.05f : 1.0f).scaleY(hasFocus ? 1.05f : 1.0f).setDuration(120).start();
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
