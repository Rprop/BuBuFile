package rprop.app.video.concat;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.arthenica.ffmpegkit.FFmpegKit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import rp.file.R;
import rp.file.databinding.ActivityVideoConcatBinding;
import rprop.app.ui.FileUriActions;
import rprop.app.ui.MaterialProgressHelper;
import rprop.app.video.utils.DisplayUtils;
import rprop.app.video.utils.VideoUtils;

public final class VideoConcatActivity extends AppCompatActivity implements VideoConcatListener {
    private static final String TAG = "ffmpeg-ui";
    private static final String VIDEO_URIS_KEY = "uris";
    private static final int REQUEST_CODE_ADD_VIDEO = 1;
    private ActivityVideoConcatBinding mBinding;
    private AlertDialog mProgressDialog;
    private final List<Uri> mVideoUris = new ArrayList<>();
    private VideoConcatAdapter mAdapter;
    private boolean mConcatRunning;
    private boolean mConcatCancelled;

    public static void call(@NonNull final Activity activity, @NonNull final ArrayList<Uri> videoUris) {
        final Intent intent = new Intent(activity, VideoConcatActivity.class);
        intent.putParcelableArrayListExtra(VIDEO_URIS_KEY, videoUris);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_video_concat);
        this.mBinding.toolbar.setNavigationOnClickListener(v -> onBackPressed());
        this.mBinding.btnAddVideo.setOnClickListener(v -> openVideoPicker());
        this.mBinding.btnConcat.setOnClickListener(v -> startConcat());

        this.mAdapter = new VideoConcatAdapter(this, this.mVideoUris, position -> {
            this.mVideoUris.remove(position);
            this.mAdapter.notifyItemRemoved(position);
            this.mAdapter.refreshOrderNumbers();
        });
        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new VideoConcatItemTouchCallback(this.mAdapter));
        this.mAdapter.setItemTouchHelper(itemTouchHelper);
        itemTouchHelper.attachToRecyclerView(this.mBinding.videoList);
        this.mBinding.videoList.setLayoutManager(new LinearLayoutManager(this));
        this.mBinding.videoList.setAdapter(this.mAdapter);

        final ArrayList<Uri> initialUris = getIntent().getParcelableArrayListExtra(VIDEO_URIS_KEY);
        if (initialUris != null) {
            this.mVideoUris.addAll(initialUris);
            this.mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onBackPressed() {
        if (this.mConcatRunning) {
            this.mConcatCancelled = true;
            FFmpegKit.cancel();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (this.mConcatRunning) {
            FFmpegKit.cancel();
        }
        super.onDestroy();
    }

    private void openVideoPicker() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        // noinspection deprecation
        startActivityForResult(intent, REQUEST_CODE_ADD_VIDEO);
    }

    private void startConcat() {
        if (this.mVideoUris.size() < 2) {
            Toast.makeText(this, R.string.concat_need_more, Toast.LENGTH_SHORT).show();
            return;
        }
        this.mConcatRunning = true;
        this.mConcatCancelled = false;
        this.mAdapter.setDragEnabled(false);
        this.mBinding.btnAddVideo.setEnabled(false);
        this.mBinding.btnConcat.setEnabled(false);
        this.mBinding.fastConcat.setEnabled(false);
        VideoUtils.concat(this, this.mVideoUris, this.mBinding.fastConcat.isChecked(), this);
    }

    private void addVideoUri(@NonNull final Uri uri) {
        if (this.mVideoUris.contains(uri)) {
            return;
        }
        this.mVideoUris.add(uri);
        this.mAdapter.notifyItemInserted(this.mVideoUris.size() - 1);
    }

    private void takePersistablePermission(@NonNull final Uri uri, @Nullable final Intent data) {
        if (data == null) {
            return;
        }
        final int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (final SecurityException ignored) {
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_ADD_VIDEO || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        if (data.getData() != null) {
            final Uri uri = data.getData();
            takePersistablePermission(uri, data);
            addVideoUri(uri);
            return;
        }
        if (data.getClipData() != null) {
            final int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                final Uri uri = data.getClipData().getItemAt(i).getUri();
                takePersistablePermission(uri, data);
                addVideoUri(uri);
            }
        }
    }

    @Override
    public void onPhase(final String desc) {
        runOnUiThread(() -> {
            if (this.mProgressDialog == null) {
                this.mProgressDialog = MaterialProgressHelper.show(this, desc);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                MaterialProgressHelper.updateMessage(this.mProgressDialog, desc);
            }
        });
    }

    @Override
    public void onFinished(final File outputFile) {
        if (isFinishing()) {
            return;
        }
        if (outputFile == null) {
            finishConcatUi(getString(R.string.concatenated_failed), !this.mConcatCancelled);
            return;
        }
        final Uri videoCollection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, outputFile.getName());
        contentValues.put(MediaStore.Video.Media.IS_PENDING, 1);
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, VideoUtils.VIDEO_OUTPUT_DIR);
        final ContentResolver resolver = getContentResolver();
        final Uri uri = resolver.insert(videoCollection, contentValues);
        if (uri == null) {
            finishConcatUi(getString(R.string.concatenated_failed), true);
            // noinspection ResultOfMethodCallIgnored
            outputFile.delete();
            return;
        }
        final boolean saved = FileUriActions.transfer(resolver, outputFile, uri);
        if (saved) {
            contentValues.clear();
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, contentValues, null, null);
            finishConcatUi(getString(R.string.concatenated_done), true);
        } else {
            try {
                resolver.delete(uri, null, null);
            } catch (final Exception e) {
                Log.w(TAG, e);
            }
            finishConcatUi(getString(R.string.concatenated_failed), true);
        }
        // noinspection ResultOfMethodCallIgnored
        outputFile.delete();
    }

    private void finishConcatUi(final String message, final boolean showToast) {
        this.mConcatRunning = false;
        this.mConcatCancelled = false;
        this.mAdapter.setDragEnabled(true);
        this.mBinding.btnAddVideo.setEnabled(true);
        this.mBinding.btnConcat.setEnabled(true);
        this.mBinding.fastConcat.setEnabled(true);
        final AlertDialog progressDialog = this.mProgressDialog;
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        this.mProgressDialog = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (showToast && message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }
}
