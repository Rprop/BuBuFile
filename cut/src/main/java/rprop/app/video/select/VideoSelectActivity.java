package rprop.app.video.select;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;

import rprop.app.video.BuildConfig;
import rprop.app.video.R;
import rprop.app.video.databinding.ActivityVideoSelectBinding;
import rprop.app.video.trim.VideoTrimActivity;
import rprop.app.video.utils.DisplayUtils;

public final class VideoSelectActivity extends AppCompatActivity {
    private static final String TAG = "ffmpeg-ui";
    private static final int REQUEST_CODE_ACTION_OPEN_DOCUMENT = 1;
    private static final int REQUEST_RECOVERABLE_SECURITY_EXCEPTION = 2;
    private ActivityVideoSelectBinding mBinding;
    private VideoSelectAdapter mVideoSelectAdapter;
    private Uri mDeletingUri;

    @SuppressLint("CheckResult")
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_video_select);
        this.mBinding.mBtnBack.setOnClickListener(v -> finish());
        this.mBinding.mSelectVideo.setOnClickListener(v -> {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            // noinspection deprecation
            startActivityForResult(intent, REQUEST_CODE_ACTION_OPEN_DOCUMENT);
        });

        final String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
        requestPermissions(permissions, 0);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0) {
            LoaderManager.getInstance(this).initLoader(1, null, new LoaderManager.LoaderCallbacks<Cursor>() {
                private static final String SELECTION = MediaStore.Files.FileColumns.MEDIA_TYPE + '=' + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                private static final String ORDER_BY = MediaStore.Files.FileColumns.DATE_ADDED + " DESC";
                private static final String[] MEDIA_PROJECTION = {
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.DATE_ADDED,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.PARENT
                };

                @NonNull
                @Override
                public Loader<Cursor> onCreateLoader(final int id, @Nullable final Bundle args) {
                    return new CursorLoader(
                            VideoSelectActivity.this,
                            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                            MEDIA_PROJECTION,
                            SELECTION,
                            null,
                            ORDER_BY
                    );
                }

                @Override
                public void onLoadFinished(@NonNull final Loader<Cursor> loader, final Cursor cursor) {
                    if (cursor != null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "onLoadFinished " + cursor.getCount());
                        }
                        if (mVideoSelectAdapter == null) {
                            mVideoSelectAdapter = new VideoSelectAdapter(VideoSelectActivity.this, cursor);
                        } else {
                            mVideoSelectAdapter.swapCursor(cursor);
                        }
                        if (mBinding.videoGridview.getAdapter() == null) {
                            mBinding.videoGridview.setAdapter(mVideoSelectAdapter);
                        }
                        mVideoSelectAdapter.notifyDataSetChanged();
                    }
                }

                @Override
                public void onLoaderReset(@NonNull final Loader<Cursor> loader) {
                }
            });
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    @Nullable final Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == REQUEST_CODE_ACTION_OPEN_DOCUMENT && resultCode == Activity.RESULT_OK && resultData != null) {
            final Uri uri = resultData.getData();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "open " + uri);
            }
            VideoTrimActivity.call(this, uri);
        }
        if (requestCode == REQUEST_RECOVERABLE_SECURITY_EXCEPTION && this.mDeletingUri != null) {
            createDeleteRequest(this.mDeletingUri, false);
            this.mDeletingUri = null;
        }
    }

    public void createDeleteRequest(final Uri uri, final boolean allowRetry) {
        try {
            try {
                final ContentResolver resolver = getContentResolver();
                resolver.delete(uri, null, null);
            } catch (final RecoverableSecurityException re) {
                if (allowRetry) {
                    this.mDeletingUri = uri;
                    final IntentSender intentSender = re.getUserAction().getActionIntent().getIntentSender();
                    // noinspection deprecation
                    startIntentSenderForResult(
                            intentSender,
                            REQUEST_RECOVERABLE_SECURITY_EXCEPTION,
                            null,
                            0,
                            0,
                            0,
                            null
                    );
                }
            }
        } catch (final Exception ignored) {
        }
    }
}
