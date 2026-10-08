package com.andplay.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Adaptador dinâmico de densidade de tela (DPI Auto-Adjust) para Android TV e telas HD/FHD/4K.
 *
 * Garante que a interface projetada com referência de 540dp de altura (1080p @ 320 DPI, tela 960x540 dp)
 * seja renderizada com exata proporcionalidade em qualquer resolução (720p, 768p, 1080p, 1440p, 4K),
 * eliminando deformações, botões gigantes em 720p ou cortes na tela.
 */
public final class ScreenDensityAdapter {

    private static final String TAG = "ScreenDensityAdapter";

    // Resolução de design de referência (Landscape 16:9)
    // 1080p (1920x1080) @ 320dpi (density 2.0) = 960dp x 540dp
    public static final float DESIGN_HEIGHT_DP = 540.0f;
    public static final float DESIGN_WIDTH_DP  = 960.0f;

    private ScreenDensityAdapter() {}

    /**
     * Inicializa o adaptador no Application da aplicação.
     */
    public static void init(Application app) {
        if (app == null) return;
        applyDensityToApplication(app);
    }

    /**
     * Envelopa o contexto base da Activity com a configuração adaptada.
     */
    public static Context wrapContext(Context context) {
        if (context == null) return null;
        try {
            DisplayMetrics realDm = getRealDisplayMetrics(context);
            int widthPixels = realDm.widthPixels;
            int heightPixels = realDm.heightPixels;
            if (widthPixels <= 0 || heightPixels <= 0) return context;

            int screenHeight = Math.min(widthPixels, heightPixels);
            int screenWidth = Math.max(widthPixels, heightPixels);

            float targetDensity = (float) screenHeight / DESIGN_HEIGHT_DP;
            int targetDensityDpi = Math.round(160.0f * targetDensity);

            Resources res = context.getResources();
            Configuration config = res != null ? res.getConfiguration() : null;
            float fontScale = (config != null && config.fontScale > 0) ? config.fontScale : 1.0f;
            float targetScaledDensity = targetDensity * fontScale;

            Configuration overrideConfig = new Configuration(config != null ? config : new Configuration());
            overrideConfig.densityDpi = targetDensityDpi;
            overrideConfig.screenWidthDp = Math.round((float) screenWidth / targetDensity);
            overrideConfig.screenHeightDp = Math.round((float) screenHeight / targetDensity);
            overrideConfig.smallestScreenWidthDp = Math.min(overrideConfig.screenWidthDp, overrideConfig.screenHeightDp);

            Context newContext = context.createConfigurationContext(overrideConfig);
            applyToDisplayMetrics(newContext.getResources().getDisplayMetrics(), targetDensity, targetScaledDensity, targetDensityDpi);
            return newContext;
        } catch (Throwable t) {
            Log.w(TAG, "Falha ao envelopar contexto: " + t.getMessage());
            return context;
        }
    }

    /**
     * Aplica a densidade calculada na Activity e no Application associado.
     */
    public static void applyDensity(Activity activity) {
        if (activity == null) return;
        try {
            DisplayMetrics realDm = getRealDisplayMetrics(activity);
            int widthPixels = realDm.widthPixels;
            int heightPixels = realDm.heightPixels;
            if (widthPixels <= 0 || heightPixels <= 0) return;

            int screenHeight = Math.min(widthPixels, heightPixels);
            int screenWidth = Math.max(widthPixels, heightPixels);

            float targetDensity = (float) screenHeight / DESIGN_HEIGHT_DP;
            int targetDensityDpi = Math.round(160.0f * targetDensity);

            Resources actRes = activity.getResources();
            Configuration actConfig = actRes != null ? actRes.getConfiguration() : null;
            float fontScale = (actConfig != null && actConfig.fontScale > 0) ? actConfig.fontScale : 1.0f;
            float targetScaledDensity = targetDensity * fontScale;

            // 1. Atualizar métricas da Activity
            if (actRes != null) {
                applyToDisplayMetrics(actRes.getDisplayMetrics(), targetDensity, targetScaledDensity, targetDensityDpi);
                if (actConfig != null) {
                    actConfig.densityDpi = targetDensityDpi;
                    actConfig.screenWidthDp = Math.round((float) screenWidth / targetDensity);
                    actConfig.screenHeightDp = Math.round((float) screenHeight / targetDensity);
                    actConfig.smallestScreenWidthDp = Math.min(actConfig.screenWidthDp, actConfig.screenHeightDp);
                }
            }

            // 2. Atualizar métricas do Application
            Application app = activity.getApplication();
            if (app != null) {
                applyDensityToApplication(app, targetDensity, targetScaledDensity, targetDensityDpi, screenWidth, screenHeight);
            }

            // 3. Atualizar métricas do Sistema (para dialogs e inflaters globais)
            try {
                applyToDisplayMetrics(Resources.getSystem().getDisplayMetrics(), targetDensity, targetScaledDensity, targetDensityDpi);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.w(TAG, "Falha ao aplicar densidade customizada: " + t.getMessage());
        }
    }

    /**
     * Aplica a densidade calculada no Application.
     */
    public static void applyDensityToApplication(Application app) {
        if (app == null) return;
        try {
            DisplayMetrics realDm = getRealDisplayMetrics(app);
            int widthPixels = realDm.widthPixels;
            int heightPixels = realDm.heightPixels;
            if (widthPixels <= 0 || heightPixels <= 0) return;

            int screenHeight = Math.min(widthPixels, heightPixels);
            int screenWidth = Math.max(widthPixels, heightPixels);

            float targetDensity = (float) screenHeight / DESIGN_HEIGHT_DP;
            int targetDensityDpi = Math.round(160.0f * targetDensity);

            Resources appRes = app.getResources();
            Configuration appConfig = appRes != null ? appRes.getConfiguration() : null;
            float fontScale = (appConfig != null && appConfig.fontScale > 0) ? appConfig.fontScale : 1.0f;
            float targetScaledDensity = targetDensity * fontScale;

            applyDensityToApplication(app, targetDensity, targetScaledDensity, targetDensityDpi, screenWidth, screenHeight);
        } catch (Throwable ignored) {}
    }

    private static void applyDensityToApplication(Application app, float targetDensity, float targetScaledDensity, int targetDensityDpi, int screenWidth, int screenHeight) {
        Resources appRes = app.getResources();
        if (appRes != null) {
            applyToDisplayMetrics(appRes.getDisplayMetrics(), targetDensity, targetScaledDensity, targetDensityDpi);
            Configuration appConfig = appRes.getConfiguration();
            if (appConfig != null) {
                appConfig.densityDpi = targetDensityDpi;
                appConfig.screenWidthDp = Math.round((float) screenWidth / targetDensity);
                appConfig.screenHeightDp = Math.round((float) screenHeight / targetDensity);
                appConfig.smallestScreenWidthDp = Math.min(appConfig.screenWidthDp, appConfig.screenHeightDp);
            }
        }
    }

    /**
     * Garante a consistência do DisplayMetrics em chamadas a getResources().
     */
    public static void applyDensityToResources(Resources res, Context context) {
        if (res == null || context == null) return;
        try {
            DisplayMetrics realDm = getRealDisplayMetrics(context);
            int screenHeight = Math.min(realDm.widthPixels, realDm.heightPixels);
            if (screenHeight <= 0) return;

            float targetDensity = (float) screenHeight / DESIGN_HEIGHT_DP;
            int targetDensityDpi = Math.round(160.0f * targetDensity);

            DisplayMetrics dm = res.getDisplayMetrics();
            if (dm != null && dm.densityDpi == targetDensityDpi && Math.abs(dm.density - targetDensity) < 0.001f) {
                return;
            }

            float fontScale = 1.0f;
            Configuration config = res.getConfiguration();
            if (config != null && config.fontScale > 0) {
                fontScale = config.fontScale;
            }

            applyToDisplayMetrics(dm, targetDensity, targetDensity * fontScale, targetDensityDpi);
        } catch (Throwable ignored) {}
    }

    /**
     * Retorna o DPI de destino para o método applyOverrideConfiguration() da Activity.
     */
    public static int getTargetDensityDpi(Context context) {
        if (context == null) return 0;
        try {
            DisplayMetrics realDm = getRealDisplayMetrics(context);
            int screenHeight = Math.min(realDm.widthPixels, realDm.heightPixels);
            if (screenHeight <= 0) return 0;
            float targetDensity = (float) screenHeight / DESIGN_HEIGHT_DP;
            return Math.round(160.0f * targetDensity);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Obtém as dimensões físicas reais da tela do dispositivo sem dedução de barras de sistema.
     */
    public static DisplayMetrics getRealDisplayMetrics(Context context) {
        DisplayMetrics realDm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        display.getRealMetrics(realDm);
                    } else {
                        display.getMetrics(realDm);
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (realDm.widthPixels == 0 || realDm.heightPixels == 0) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            realDm.widthPixels = dm.widthPixels;
            realDm.heightPixels = dm.heightPixels;
            realDm.density = dm.density;
            realDm.scaledDensity = dm.scaledDensity;
            realDm.densityDpi = dm.densityDpi;
        }
        return realDm;
    }

    private static void applyToDisplayMetrics(DisplayMetrics dm, float density, float scaledDensity, int densityDpi) {
        if (dm == null) return;
        dm.density = density;
        dm.scaledDensity = scaledDensity;
        dm.densityDpi = densityDpi;
    }
}
