package com.andplay.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

/**
 * Activity base com suporte automático à adaptação de densidade de tela (DPI Auto-Adjust)
 * em resoluções 720p, 1080p, 4K, TV Boxes e projetores.
 */
public abstract class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ScreenDensityAdapter.wrapContext(newBase));
    }

    @Override
    public void applyOverrideConfiguration(Configuration overrideConfiguration) {
        if (overrideConfiguration != null) {
            int densityDpi = ScreenDensityAdapter.getTargetDensityDpi(this);
            if (densityDpi > 0) {
                overrideConfiguration.densityDpi = densityDpi;
            }
        }
        super.applyOverrideConfiguration(overrideConfiguration);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ScreenDensityAdapter.applyDensity(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public Resources getResources() {
        Resources res = super.getResources();
        ScreenDensityAdapter.applyDensityToResources(res, this);
        return res;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ScreenDensityAdapter.applyDensity(this);
    }
}
