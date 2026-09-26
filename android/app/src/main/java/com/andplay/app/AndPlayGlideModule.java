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
        // Limita o cache de disco em 100 MB para evitar lotar o armazenamento interno de TVs e Projetores
        int diskCacheSizeBytes = 100 * 1024 * 1024; // 100 MB
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, "image_cache", diskCacheSizeBytes));

        // Limita o consumo de memória RAM para cache de imagens (máx 30 MB)
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(1.5f)
                .build();
        builder.setMemoryCache(new LruResourceCache(Math.min(calculator.getMemoryCacheSize() / 2, 30 * 1024 * 1024)));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
