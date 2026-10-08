package com.andplay.app.adapter;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.andplay.app.R;
import com.andplay.app.model.Category;

import java.util.List;

public class CategoryPillAdapter extends RecyclerView.Adapter<CategoryPillAdapter.ViewHolder> {

    public interface OnCategoryClickListener {
        void onCategoryClick(Category category);
    }

    private final List<Category> categories;
    private final OnCategoryClickListener listener;
    private String selectedId = "ALL";

    public CategoryPillAdapter(List<Category> categories, OnCategoryClickListener listener) {
        this.categories = categories;
        this.listener = listener;
    }

    public void setSelectedId(String id) {
        this.selectedId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category_pill, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Category cat = categories.get(position);
        holder.pillText.setText(cat.getCleanName());
        boolean isSelected = cat.category_id != null && cat.category_id.equals(selectedId);
        holder.pillText.setSelected(isSelected);
        if (isSelected) {
            holder.pillText.setTextColor(0xFF000000);
        } else {
            holder.pillText.setTextColor(0xFFFFFFFF);
        }

        holder.itemView.setOnClickListener(v -> {
            selectedId = cat.category_id;
            notifyDataSetChanged();
            if (listener != null) listener.onCategoryClick(cat);
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                v.performClick();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    android.content.Context ctx = v.getContext();
                    if (ctx instanceof android.app.Activity) {
                        android.app.Activity act = (android.app.Activity) ctx;
                        View scroll = act.findViewById(R.id.vodHighlightsScroll);
                        if (scroll instanceof androidx.core.widget.NestedScrollView) {
                            ((androidx.core.widget.NestedScrollView) scroll).smoothScrollTo(0, 0);
                        } else if (scroll instanceof android.widget.ScrollView) {
                            ((android.widget.ScrollView) scroll).smoothScrollTo(0, 0);
                        }
                        View tabMovies = act.findViewById(R.id.btnVodCinemaTabMovies);
                        View tabSeries = act.findViewById(R.id.btnVodCinemaTabSeries);
                        if (tabSeries != null && tabSeries.isSelected()) {
                            tabSeries.requestFocus();
                            return true;
                        } else if (tabMovies != null) {
                            tabMovies.requestFocus();
                            return true;
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    View modeHigh = ((android.app.Activity) v.getContext()).findViewById(R.id.btnVodModeHighlights);
                    if (modeHigh != null && modeHigh.isShown()) {
                        modeHigh.requestFocus();
                        return true;
                    }
                }
            }
            return false;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.05f : 1.0f).scaleY(hasFocus ? 1.05f : 1.0f).setDuration(100).start();
            v.setElevation(hasFocus ? 8f : 0f);
            if (hasFocus) {
                holder.pillText.setTextColor(0xFF000000);
            } else if (!isSelected) {
                holder.pillText.setTextColor(0xFFFFFFFF);
            }
        });
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView pillText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            pillText = itemView.findViewById(R.id.pillText);
        }
    }
}
