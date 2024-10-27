package rprop.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import java.util.Arrays;
import java.util.List;

import rp.file.R;

public final class ThemeHelper {
    public static final String ACTION_SET_THEME = "rp.file.action.SET_THEME";
    public static final String EXTRA_MODE = "theme_mode";
    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREFS_NAME = "theme_prefs";
    private static final String KEY_MODE = "mode";
    private static final String KEY_TEXT_FIND_QUERY = "text_find_query";

    private ThemeHelper() {
    }

    public static void applyStoredTheme(@NonNull final Context context) {
        applyNightMode(getMode(context));
    }

    public static int getMode(@NonNull final Context context) {
        return prefs(context).getInt(KEY_MODE, MODE_FOLLOW_SYSTEM);
    }

    public static void setMode(@NonNull final Context context, final int mode) {
        final int normalizedMode = normalizeMode(mode);
        prefs(context).edit().putInt(KEY_MODE, normalizedMode).apply();
        applyNightMode(normalizedMode);
        refreshDynamicShortcuts(context.getApplicationContext());
    }

    @NonNull
    public static String getTextFindQuery(@NonNull final Context context) {
        return prefs(context).getString(KEY_TEXT_FIND_QUERY, "");
    }

    public static void setTextFindQuery(@NonNull final Context context, @NonNull final String query) {
        prefs(context).edit().putString(KEY_TEXT_FIND_QUERY, query).apply();
    }

    public static void refreshDynamicShortcuts(@NonNull final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return;
        }
        final ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager == null) {
            return;
        }
        final List<ShortcutInfo> shortcuts = Arrays.asList(
                buildShortcut(context, "theme_light", R.string.theme_shortcut_light,
                        R.drawable.ic_theme_light, MODE_LIGHT),
                buildShortcut(context, "theme_dark", R.string.theme_shortcut_dark,
                        R.drawable.ic_theme_dark, MODE_DARK),
                buildShortcut(context, "theme_system", R.string.theme_shortcut_system,
                        R.drawable.ic_theme_auto, MODE_FOLLOW_SYSTEM));
        shortcutManager.setDynamicShortcuts(shortcuts);
    }

    @NonNull
    private static ShortcutInfo buildShortcut(@NonNull final Context context, @NonNull final String id,
                                              final int labelResId, final int iconResId, final int mode) {
        final Intent intent = new Intent(context, ThemeShortcutActivity.class)
                .setAction(ACTION_SET_THEME)
                .putExtra(EXTRA_MODE, mode)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(context.getString(labelResId))
                .setLongLabel(context.getString(labelResId))
                .setIcon(Icon.createWithResource(context, iconResId))
                .setIntent(intent)
                .build();
    }

    private static void applyNightMode(final int mode) {
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    private static int normalizeMode(final int mode) {
        if (mode == MODE_LIGHT || mode == MODE_DARK) {
            return mode;
        }
        return MODE_FOLLOW_SYSTEM;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull final Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
