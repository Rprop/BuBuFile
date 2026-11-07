package rprop.app.ui;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

public final class FileOpenIntentHelper {
    private FileOpenIntentHelper() {
    }

    @NonNull
    public static ArrayList<Uri> extractUris(@NonNull final Intent intent) {
        final ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            final ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (final Uri uri : streams) {
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
            }
            return uris;
        }
        final Uri uri = extractUri(intent);
        if (uri != null) {
            uris.add(uri);
        }
        return uris;
    }

    @Nullable
    public static Uri extractUri(@NonNull final Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return intent.getData();
    }
}
