package com.andplay.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

public class EPlayApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(ScreenDensityAdapter.wrapContext(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ScreenDensityAdapter.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                ScreenDensityAdapter.applyDensity(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                ScreenDensityAdapter.applyDensity(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                ScreenDensityAdapter.applyDensity(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ScreenDensityAdapter.init(this);
    }
}
