package rprop.app.ui;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.webkit.MimeTypeMap;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import rp.file.R;
import rprop.app.img.ImageViewActivity;
import rprop.app.txt.TextViewActivity;
import rprop.app.video.play.VideoPlayActivity;

public final class FileChooserActivity extends AppCompatActivity {
    private static final int OPTION_TEXT = 0;
    private static final int OPTION_IMAGE = 1;
    private static final int OPTION_VIDEO = 2;
    private static final int OPTION_SHARE = 3;
    private static final int OPTION_COPY = 4;

    private static final int[] OPEN_OPTIONS = {
            OPTION_TEXT, OPTION_IMAGE, OPTION_VIDEO
    };

    @Nullable
    private Uri mUri;
    private FileCopyToHelper mCopyToHelper;
    @NonNull
    private int[] mOptionIds = new int[0];

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.mCopyToHelper = new FileCopyToHelper(this, () -> this.mUri, this::finish);
        showOpenModeDialog(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showOpenModeDialog(intent);
    }

    private void showOpenModeDialog(final Intent intent) {
        final Uri uri = FileOpenIntentHelper.extractUri(intent);
        if (uri == null) {
            finish();
            return;
        }
        this.mUri = uri;
        final int suggested = resolveSuggestedOption(intent, uri);
        final CharSequence[] items = buildOptionItems(suggested);
        final TextView titleView = (TextView) getLayoutInflater()
                .inflate(R.layout.dialog_file_path_title, null);
        titleView.setText(FileUriActions.resolvePathLabel(this, uri));
        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(titleView)
                .setItems(items, (dialog, which) -> handleOption(which))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    @NonNull
    private CharSequence[] buildOptionItems(final int suggested) {
        final int[] ids = new int[OPEN_OPTIONS.length + 2];
        int index = 0;
        if (isOpenOption(suggested)) {
            ids[index++] = suggested;
        }
        for (final int option : OPEN_OPTIONS) {
            if (option != suggested) {
                ids[index++] = option;
            }
        }
        ids[index++] = OPTION_SHARE;
        ids[index] = OPTION_COPY;
        this.mOptionIds = ids;

        final CharSequence[] items = new CharSequence[ids.length];
        for (int i = 0; i < ids.length; i++) {
            final int option = ids[i];
            final CharSequence label = getString(labelRes(option));
            items[i] = option == suggested ? bold(label) : label;
        }
        return items;
    }

    private static boolean isOpenOption(final int option) {
        return option == OPTION_TEXT || option == OPTION_IMAGE || option == OPTION_VIDEO;
    }

    private static int labelRes(final int option) {
        switch (option) {
            case OPTION_IMAGE:
                return R.string.open_as_image;
            case OPTION_VIDEO:
                return R.string.open_as_video;
            case OPTION_SHARE:
                return R.string.share_to;
            case OPTION_COPY:
                return R.string.copy_to;
            case OPTION_TEXT:
            default:
                return R.string.open_as_text;
        }
    }

    @NonNull
    private static CharSequence bold(@NonNull final CharSequence text) {
        final SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new StyleSpan(Typeface.BOLD), 0, spannable.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    private int resolveSuggestedOption(@NonNull final Intent intent, @NonNull final Uri uri) {
        final String mimeType = resolveMimeType(intent, uri);
        if (isTextType(mimeType)) {
            return OPTION_TEXT;
        }
        if (isImageType(mimeType)) {
            return OPTION_IMAGE;
        }
        if (isVideoType(mimeType)) {
            return OPTION_VIDEO;
        }
        return -1;
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
        if (isImageExtension(extension)) {
            return "image/*";
        }
        if (isVideoExtension(extension)) {
            return "video/*";
        }
        return null;
    }

    private static boolean isTextType(@Nullable final String mimeType) {
        return mimeType != null && mimeType.startsWith("text/");
    }

    private static boolean isImageType(@Nullable final String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
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

    private static boolean isImageExtension(@NonNull final String extension) {
        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "webp":
            case "bmp":
            case "heic":
            case "heif":
            case "avif":
            case "tif":
            case "tiff":
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

    private void handleOption(final int which) {
        if (which < 0 || which >= this.mOptionIds.length) {
            finish();
            return;
        }
        final Uri uri = this.mUri;
        if (uri == null) {
            finish();
            return;
        }
        switch (this.mOptionIds[which]) {
            case OPTION_TEXT:
                TextViewActivity.call(this, uri);
                break;
            case OPTION_IMAGE:
                ImageViewActivity.call(this, uri);
                break;
            case OPTION_VIDEO:
                VideoPlayActivity.call(this, uri);
                break;
            case OPTION_SHARE:
                FileUriActions.share(this, uri);
                return;
            case OPTION_COPY:
                this.mCopyToHelper.start();
                return;
            default:
                break;
        }
        finish();
    }
}
