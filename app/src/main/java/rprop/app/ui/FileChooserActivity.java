package rprop.app.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import rp.file.R;
import rprop.app.txt.TextViewActivity;
import rprop.app.video.play.VideoPlayActivity;

public final class FileChooserActivity extends AppCompatActivity {
    private static final String TAG = "file-chooser";

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        routeIntent(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        routeIntent(intent);
    }

    private void routeIntent(@Nullable final Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        final Uri uri = FileOpenIntentHelper.extractUri(intent);
        if (uri == null) {
            showUnsupportedAndFinish();
            return;
        }
        final String mimeType = resolveMimeType(intent, uri);
        Log.d(TAG, mimeType + ' ' + uri);
        if (isTextType(mimeType)) {
            forwardToText(uri);
            return;
        }
        if (isVideoType(mimeType)) {
            forwardToVideo(uri);
            return;
        }
        showUnsupportedAndFinish();
    }

    @Nullable
    private String resolveMimeType(@NonNull final Intent intent, @NonNull final Uri uri) {
        String mimeType = intent.getType();
        if (isConcreteMimeType(mimeType)) {
            return mimeType;
        }
        mimeType = getContentResolver().getType(uri);
        if (isConcreteMimeType(mimeType)) {
            return mimeType;
        }
        return guessMimeTypeFromName(queryDisplayName(uri));
    }

    private static boolean isConcreteMimeType(@Nullable final String mimeType) {
        return mimeType != null
                && !"*/*".equals(mimeType)
                && !"application/octet-stream".equals(mimeType);
    }

    @Nullable
    private String queryDisplayName(@NonNull final Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getString(index);
                }
            }
        } catch (final Exception ignored) {
        }
        try {
            final DocumentFile documentFile = DocumentFile.fromSingleUri(this, uri);
            if (documentFile != null) {
                return documentFile.getName();
            }
        } catch (final Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    @Nullable
    private static String guessMimeTypeFromName(@Nullable final String name) {
        if (name == null) {
            return null;
        }
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) {
            return null;
        }
        final String extension = name.substring(dot + 1).toLowerCase();
        final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (mimeType != null) {
            return mimeType;
        }
        if (isTextExtension(extension)) {
            return "text/plain";
        }
        if (isVideoExtension(extension)) {
            return "video/*";
        }
        return null;
    }

    private static boolean isTextType(@Nullable final String mimeType) {
        return mimeType != null && mimeType.startsWith("text/");
    }

    private static boolean isVideoType(@Nullable final String mimeType) {
        return mimeType != null && mimeType.startsWith("video/");
    }

    private static boolean isTextExtension(@NonNull final String extension) {
        switch (extension) {
            case "txt":
            case "log":
            case "md":
            case "json":
            case "xml":
            case "csv":
            case "html":
            case "htm":
            case "java":
            case "kt":
            case "properties":
            case "ini":
            case "conf":
            case "cfg":
            case "yaml":
            case "yml":
                return true;
            default:
                return false;
        }
    }

    private static boolean isVideoExtension(@NonNull final String extension) {
        switch (extension) {
            case "mp4":
            case "mkv":
            case "webm":
            case "3gp":
            case "avi":
            case "mov":
            case "m4v":
            case "ts":
            case "flv":
                return true;
            default:
                return false;
        }
    }

    private void forwardToText(@NonNull final Uri uri) {
        TextViewActivity.call(this, uri);
        finish();
    }

    private void forwardToVideo(@NonNull final Uri uri) {
        VideoPlayActivity.call(this, uri);
        finish();
    }

    private void showUnsupportedAndFinish() {
        Toast.makeText(this, R.string.file_open_unsupported, Toast.LENGTH_LONG).show();
        finish();
    }
}
