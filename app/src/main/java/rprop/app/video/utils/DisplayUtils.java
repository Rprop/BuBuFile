package rprop.app.video.utils;

import android.content.Context;
import android.util.DisplayMetrics;

public final class DisplayUtils {
    private static DisplayMetrics sDisplayMetrics;

    public static void init(final Context context) {
        sDisplayMetrics = context.getResources().getDisplayMetrics();
    }

    public static int dpToPx(int dp) {
        return sDisplayMetrics != null ? (int) (dp * sDisplayMetrics.density + 0.5f) : 0;
    }

    public static int getDeviceWidth() {
        return sDisplayMetrics != null ? sDisplayMetrics.widthPixels : 0;
    }
}
