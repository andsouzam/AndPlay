package com.andplay.app.model;

import java.io.Serializable;

public class Category implements Serializable {
    public String category_id;
    public String category_name;
    public int parent_id;

    public Category() {}

    public Category(String id, String name) {
        this.category_id = id;
        this.category_name = name;
    }

    public String getCleanName() {
        if (category_name == null) return "Categoria";
        return category_name.replace("⚡", "").trim();
    }
}
