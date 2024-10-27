package rprop.app;

import android.app.Application;

import rprop.app.ui.ThemeHelper;

public final class BuBuFileApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeHelper.applyStoredTheme(this);
        ThemeHelper.refreshDynamicShortcuts(this);
    }
}
