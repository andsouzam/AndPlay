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
import com.andplay.app.model.Movie;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.List;

public class MoviePosterAdapter extends RecyclerView.Adapter<MoviePosterAdapter.ViewHolder> {

    public interface OnMovieActionListener {
        void onMovieClick(Movie movie);
        void onMovieFocus(Movie movie);
    }

    private final Context context;
    private final List<Movie> movies;
    private final boolean isGrid;
    private final OnMovieActionListener listener;

    public MoviePosterAdapter(Context context, List<Movie> movies, boolean isGrid, OnMovieActionListener listener) {
        this.context = context;
        this.movies = movies;
        this.isGrid = isGrid;
        this.listener = listener;
    }

    public MoviePosterAdapter(Context context, List<Movie> movies, OnMovieActionListener listener) {
        this(context, movies, false, listener);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_movie_card, parent, false);
        if (isGrid) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                view.setLayoutParams(lp);
            }
        }
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Movie movie = movies.get(position);
        holder.title.setText(movie.getDisplayTitle());

        String rating = (movie.rating != null && !movie.rating.isEmpty()) ? "★ " + movie.rating : "★ 7.5";
        String year = (movie.year != null && !movie.year.isEmpty()) ? movie.year : "";
        holder.subtitle.setText((year.isEmpty() ? "" : year + "  ") + rating);

        String poster = movie.getPosterUrl();
        if (poster != null && !poster.isEmpty()) {
            Glide.with(context)
                    .load(poster)
                    .override(220, 300)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.card_focus_bg)
                    .into(holder.poster);
        } else {
            holder.poster.setImageResource(R.drawable.card_focus_bg);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMovieClick(movie);
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
            if (hasFocus && listener != null) {
                listener.onMovieFocus(movie);
            }
        });
    }

    @Override
    public int getItemCount() {
        return movies.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView poster;
        TextView title;
        TextView subtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.moviePoster);
            title = itemView.findViewById(R.id.movieTitle);
            subtitle = itemView.findViewById(R.id.movieSubtitle);
        }
    }
}
