package com.andplay.app.adapter;

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

        holder.itemView.setOnClickListener(v -> {
            selectedId = cat.category_id;
            notifyDataSetChanged();
            if (listener != null) listener.onCategoryClick(cat);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(150).start();
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
