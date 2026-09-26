package com.andplay.app;

import android.content.Context;
import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.module.AppGlideModule;

@GlideModule
public final class AndPlayGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Limita o cache de disco em 300 MB para cache de capas
        int diskCacheSizeBytes = 300 * 1024 * 1024; // 300 MB
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, "image_cache", diskCacheSizeBytes));

        // Limita o consumo de memória RAM para cache de imagens (30 MB seguro para evitar OOM no projetor)
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(1.5f)
                .build();
        builder.setMemoryCache(new LruResourceCache(Math.min(calculator.getMemoryCacheSize(), 30 * 1024 * 1024)));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
