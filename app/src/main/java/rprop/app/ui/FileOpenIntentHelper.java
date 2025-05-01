package rprop.app.ui;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class FileOpenIntentHelper {
    @Nullable
    public static Uri extractUri(@NonNull final Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return intent.getData();
    }
}
