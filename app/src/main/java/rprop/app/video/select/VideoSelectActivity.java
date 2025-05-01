package rprop.app.video.select;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.color.MaterialColors;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import rp.file.BuildConfig;
import rp.file.R;
import rp.file.databinding.ActivityVideoSelectBinding;
import rprop.app.ui.FileCopyToHelper;
import rprop.app.video.concat.VideoConcatActivity;
import rprop.app.video.play.VideoPlayActivity;
import rprop.app.video.utils.DisplayUtils;
import rprop.app.video.utils.VideoUtils;

public final class VideoSelectActivity extends AppCompatActivity {
    private static final String TAG = "ffmpeg-ui";
    private static final int LOADER_APP_VIDEO = 1;
    private static final int LOADER_OTHER_VIDEO = 2;
    private static final int REQUEST_CODE_ACTION_OPEN_DOCUMENT = 1;
    private static final int REQUEST_RECOVERABLE_SECURITY_EXCEPTION = 2;
    private static final int REQUEST_BATCH_DELETE = 3;
    private static final String VIDEO_TYPE_SELECTION =
            MediaStore.Files.FileColumns.MEDIA_TYPE + '=' + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
    private static final String ORDER_BY = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
    private static final String[] MEDIA_PROJECTION = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.RELATIVE_PATH
    };
    private ActivityVideoSelectBinding mBinding;
    private VideoSelectListAdapter mListAdapter;
    private Cursor mAppVideoCursor;
    private Cursor mOtherVideoCursor;
    private Uri mDeletingUri;
    private final ArrayList<Uri> mPendingDeleteUris = new ArrayList<>();
    private boolean mLoadersInitialized;
    private int mRefreshLoadersPending;
    private boolean mSelectionMode;
    private final LinkedHashSet<Uri> mSelectedUris = new LinkedHashSet<>();
    @Nullable
    private Uri mCopyToUri;
    private FileCopyToHelper mCopyToHelper;

    @SuppressLint("CheckResult")
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_video_select);
        this.mBinding.mBtnBack.setOnClickListener(v -> {
            if (this.mSelectionMode) {
                exitSelectionMode();
            } else {
                finish();
            }
        });
        this.mBinding.btnMerge.setOnClickListener(v -> startConcatSelectedVideos());
        this.mBinding.btnDelete.setOnClickListener(v -> deleteSelectedVideos());
        this.mBinding.mSelectVideo.setOnClickListener(v -> {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            // noinspection deprecation
            startActivityForResult(intent, REQUEST_CODE_ACTION_OPEN_DOCUMENT);
        });

        this.mListAdapter = new VideoSelectListAdapter(this);
        this.mBinding.videoList.setLayoutManager(new LinearLayoutManager(this));
        this.mBinding.videoList.setAdapter(this.mListAdapter);
        this.mBinding.swipeRefresh.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0));
        this.mBinding.swipeRefresh.setColorSchemeColors(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, 0));
        this.mBinding.swipeRefresh.setOnRefreshListener(this::reloadVideoList);
        this.mCopyToHelper = new FileCopyToHelper(this, () -> this.mCopyToUri);

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
    public void onBackPressed() {
        if (this.mSelectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0) {
            final LoaderManager loaderManager = LoaderManager.getInstance(this);
            loaderManager.initLoader(LOADER_APP_VIDEO, null, createVideoLoaderCallbacks(true));
            loaderManager.initLoader(LOADER_OTHER_VIDEO, null, createVideoLoaderCallbacks(false));
            this.mLoadersInitialized = true;
        }
    }

    public boolean isSelectionMode() {
        return this.mSelectionMode;
    }

    public boolean isVideoSelected(@NonNull final Uri uri) {
        return this.mSelectedUris.contains(uri);
    }

    public int getVideoSelectionOrder(@NonNull final Uri uri) {
        int order = 1;
        for (final Uri selectedUri : this.mSelectedUris) {
            if (selectedUri.equals(uri)) {
                return order;
            }
            order++;
        }
        return 0;
    }

    public void enterSelectionMode(@NonNull final Uri uri) {
        this.mSelectionMode = true;
        this.mSelectedUris.clear();
        this.mSelectedUris.add(uri);
        updateSelectionUi();
    }

    public void exitSelectionMode() {
        this.mSelectionMode = false;
        this.mSelectedUris.clear();
        updateSelectionUi();
    }

    public void toggleVideoSelection(@NonNull final Uri uri) {
        if (!this.mSelectionMode) {
            return;
        }
        if (this.mSelectedUris.contains(uri)) {
            this.mSelectedUris.remove(uri);
            if (this.mSelectedUris.isEmpty()) {
                exitSelectionMode();
                return;
            }
        } else {
            this.mSelectedUris.add(uri);
        }
        updateSelectionUi();
    }

    public void startCopyTo(@NonNull final Uri uri) {
        this.mCopyToUri = uri;
        this.mCopyToHelper.start();
    }

    private void updateSelectionUi() {
        this.mBinding.btnMerge.setVisibility(this.mSelectionMode ? View.VISIBLE : View.INVISIBLE);
        this.mBinding.btnDelete.setVisibility(this.mSelectionMode ? View.VISIBLE : View.INVISIBLE);
        this.mBinding.btnMerge.setEnabled(this.mSelectedUris.size() >= 2);
        this.mBinding.btnDelete.setEnabled(!this.mSelectedUris.isEmpty());
        this.mBinding.mSelectVideo.setVisibility(this.mSelectionMode ? View.GONE : View.VISIBLE);
        this.mBinding.swipeRefresh.setEnabled(!this.mSelectionMode);
        this.mListAdapter.notifyDataSetChanged();
    }

    private void deleteSelectedVideos() {
        if (this.mSelectedUris.isEmpty()) {
            Toast.makeText(this, R.string.delete_need_select, Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>(this.mSelectedUris);
        exitSelectionMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestBatchDelete(uris);
        } else {
            this.mPendingDeleteUris.clear();
            this.mPendingDeleteUris.addAll(uris);
            processNextDelete();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void requestBatchDelete(@NonNull final ArrayList<Uri> uris) {
        try {
            final PendingIntent pendingIntent = MediaStore.createDeleteRequest(getContentResolver(), uris);
            // noinspection deprecation
            startIntentSenderForResult(
                    pendingIntent.getIntentSender(),
                    REQUEST_BATCH_DELETE,
                    null,
                    0,
                    0,
                    0,
                    null
            );
        } catch (final Exception e) {
            Log.w(TAG, e);
            this.mPendingDeleteUris.clear();
            this.mPendingDeleteUris.addAll(uris);
            processNextDelete();
        }
    }

    private void processNextDelete() {
        if (this.mPendingDeleteUris.isEmpty()) {
            reloadVideoList();
            return;
        }
        final Uri uri = this.mPendingDeleteUris.get(0);
        try {
            getContentResolver().delete(uri, null, null);
            this.mPendingDeleteUris.remove(0);
            processNextDelete();
        } catch (final RecoverableSecurityException re) {
            this.mDeletingUri = uri;
            try {
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
            } catch (final Exception e) {
                Log.w(TAG, e);
                this.mPendingDeleteUris.remove(0);
                processNextDelete();
            }
        } catch (final Exception e) {
            Log.w(TAG, e);
            this.mPendingDeleteUris.remove(0);
            processNextDelete();
        }
    }

    private void startConcatSelectedVideos() {
        if (this.mSelectedUris.size() < 2) {
            Toast.makeText(this, R.string.concat_need_more, Toast.LENGTH_SHORT).show();
            return;
        }
        VideoConcatActivity.call(this, new ArrayList<>(this.mSelectedUris));
    }

    private void reloadVideoList() {
        if (!this.mLoadersInitialized) {
            this.mBinding.swipeRefresh.setRefreshing(false);
            return;
        }
        this.mRefreshLoadersPending = 2;
        final LoaderManager loaderManager = LoaderManager.getInstance(this);
        loaderManager.restartLoader(LOADER_APP_VIDEO, null, createVideoLoaderCallbacks(true));
        loaderManager.restartLoader(LOADER_OTHER_VIDEO, null, createVideoLoaderCallbacks(false));
    }

    private void onRefreshLoaderFinished() {
        if (!this.mBinding.swipeRefresh.isRefreshing()) {
            return;
        }
        this.mRefreshLoadersPending--;
        if (this.mRefreshLoadersPending <= 0) {
            this.mBinding.swipeRefresh.setRefreshing(false);
        }
    }

    private LoaderManager.LoaderCallbacks<Cursor> createVideoLoaderCallbacks(final boolean appVideo) {
        return new LoaderManager.LoaderCallbacks<Cursor>() {
            @NonNull
            @Override
            public Loader<Cursor> onCreateLoader(final int id, @Nullable final Bundle args) {
                final String selection;
                final String[] selectionArgs;
                if (appVideo) {
                    selection = VIDEO_TYPE_SELECTION + " AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                    selectionArgs = new String[]{VideoUtils.VIDEO_OUTPUT_RELATIVE_PATH + '%'};
                } else {
                    selection = VIDEO_TYPE_SELECTION + " AND ("
                            + MediaStore.MediaColumns.RELATIVE_PATH + " NOT LIKE ? OR "
                            + MediaStore.MediaColumns.RELATIVE_PATH + " IS NULL)";
                    selectionArgs = new String[]{VideoUtils.VIDEO_OUTPUT_RELATIVE_PATH + '%'};
                }
                return new CursorLoader(
                        VideoSelectActivity.this,
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        MEDIA_PROJECTION,
                        selection,
                        selectionArgs,
                        ORDER_BY
                );
            }

            @Override
            public void onLoadFinished(@NonNull final Loader<Cursor> loader, final Cursor cursor) {
                if (appVideo) {
                    mAppVideoCursor = cursor;
                } else {
                    mOtherVideoCursor = cursor;
                }
                refreshVideoList();
                onRefreshLoaderFinished();
            }

            @Override
            public void onLoaderReset(@NonNull final Loader<Cursor> loader) {
                if (appVideo) {
                    mAppVideoCursor = null;
                } else {
                    mOtherVideoCursor = null;
                }
                refreshVideoList();
            }
        };
    }

    private void refreshVideoList() {
        this.mListAdapter.setItems(buildVideoListItems(this.mAppVideoCursor, this.mOtherVideoCursor));
    }

    private List<VideoSelectListAdapter.ListItem> buildVideoListItems(@Nullable final Cursor appCursor,
                                                                      @Nullable final Cursor otherCursor) {
        final List<VideoSelectListAdapter.ListItem> items = new ArrayList<>();
        appendVideos(appCursor, items, R.string.video_section_app, false);
        appendVideos(otherCursor, items, R.string.video_section_other, true);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "refreshVideoList app="
                    + (appCursor != null ? appCursor.getCount() : 0)
                    + " other=" + (otherCursor != null ? otherCursor.getCount() : 0)
                    + " items=" + items.size());
        }
        return items;
    }

    private void appendVideos(@Nullable final Cursor cursor,
                              @NonNull final List<VideoSelectListAdapter.ListItem> items,
                              final int sectionTitleResId, final boolean groupByMonth) {
        if (cursor == null || cursor.getCount() == 0) {
            return;
        }
        items.add(new VideoSelectListAdapter.SectionHeader(sectionTitleResId));
        final int idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
        final int dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
        final int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION);
        String currentMonth = null;
        final List<VideoSelectListAdapter.VideoEntry> rowEntries = new ArrayList<>();
        for (int i = 0; i < cursor.getCount(); i++) {
            if (!cursor.moveToPosition(i)) {
                continue;
            }
            if (groupByMonth) {
                final String monthLabel = formatMonthLabel(cursor.getLong(dateModifiedIndex));
                if (!monthLabel.equals(currentMonth)) {
                    flushVideoRow(rowEntries, items);
                    items.add(new VideoSelectListAdapter.MonthHeader(monthLabel));
                    currentMonth = monthLabel;
                }
            }
            final Uri uri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getString(idIndex));
            rowEntries.add(new VideoSelectListAdapter.VideoEntry(uri, cursor.getLong(durationIndex)));
            if (rowEntries.size() == 3) {
                flushVideoRow(rowEntries, items);
            }
        }
        flushVideoRow(rowEntries, items);
    }

    private static void flushVideoRow(@NonNull final List<VideoSelectListAdapter.VideoEntry> rowEntries,
                                      @NonNull final List<VideoSelectListAdapter.ListItem> items) {
        if (rowEntries.isEmpty()) {
            return;
        }
        items.add(new VideoSelectListAdapter.VideoRow(new ArrayList<>(rowEntries)));
        rowEntries.clear();
    }

    private static String formatMonthLabel(final long dateModifiedSeconds) {
        final DateTimeFormatter formatter;
        if (Locale.getDefault().getLanguage().startsWith("zh")) {
            formatter = DateTimeFormatter.ofPattern("yyyy年MM月", Locale.getDefault());
        } else {
            formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault());
        }
        return Instant.ofEpochSecond(dateModifiedSeconds)
                .atZone(ZoneId.systemDefault())
                .format(formatter);
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
            VideoPlayActivity.call(this, uri);
        }
        if (requestCode == REQUEST_BATCH_DELETE) {
            reloadVideoList();
            return;
        }
        if (requestCode == REQUEST_RECOVERABLE_SECURITY_EXCEPTION && this.mDeletingUri != null) {
            if (resultCode == Activity.RESULT_OK) {
                try {
                    getContentResolver().delete(this.mDeletingUri, null, null);
                } catch (final Exception e) {
                    Log.w(TAG, e);
                }
            }
            if (!this.mPendingDeleteUris.isEmpty()
                    && this.mDeletingUri.equals(this.mPendingDeleteUris.get(0))) {
                this.mPendingDeleteUris.remove(0);
            }
            this.mDeletingUri = null;
            processNextDelete();
        }
    }
}
