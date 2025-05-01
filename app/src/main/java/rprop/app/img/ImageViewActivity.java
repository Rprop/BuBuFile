package rprop.app.img;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.documentfile.provider.DocumentFile;

import rp.file.R;
import rp.file.databinding.ActivityImageViewBinding;
import rprop.app.ui.FileCopyToHelper;
import rprop.app.ui.FileUriActions;
import rprop.app.video.utils.DisplayUtils;

public final class ImageViewActivity extends AppCompatActivity implements ImageViewerListener {
    private static final String TAG = "image-viewer";
    private ActivityImageViewBinding mBinding;
    @Nullable
    private Uri mUri;
    private FileCopyToHelper mCopyToHelper;

    public static void call(final Activity activity, final Uri imageUri) {
        final Intent intent = new Intent(activity, ImageViewActivity.class);
        intent.setData(imageUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent, null);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_image_view);
        this.mBinding.imageViewer.setOnImageViewerListener(this);
        this.mCopyToHelper = new FileCopyToHelper(this, () -> this.mUri);
        resolveIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        this.mBinding.imageViewer.setOnImageViewerListener(null);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        resolveIntent(intent);
    }

    private void resolveIntent(final Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final String type = intent.getType();
            if (type != null && type.startsWith("image/")) {
                final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    Log.d(TAG, type + ' ' + uri);
                    openImage(uri);
                }
            }
            return;
        }
        final Uri uri = intent.getData();
        if (uri != null) {
            Log.d(TAG, "view " + uri);
            openImage(uri);
        }
    }

    private void openImage(@NonNull final Uri uri) {
        this.mUri = uri;
        this.mBinding.imageViewer.setTitle(resolveDisplayName(uri));
        this.mBinding.imageViewer.setImageUri(uri);
    }

    @Nullable
    private String resolveDisplayName(@NonNull final Uri uri) {
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

    @Override
    public void onBack() {
        finish();
    }

    @Override
    public void onShareRequested() {
        final Uri uri = this.mUri;
        if (uri != null) {
            FileUriActions.share(this, uri);
        }
    }

    @Override
    public void onCopyToRequested() {
        this.mCopyToHelper.start();
    }

    @Override
    public void onImageLoadFailed() {
        Toast.makeText(this, R.string.image_read_failed, Toast.LENGTH_LONG).show();
    }
}
