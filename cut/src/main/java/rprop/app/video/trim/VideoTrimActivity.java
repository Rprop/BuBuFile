package rprop.app.video.trim;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import rprop.app.video.R;
import rprop.app.video.databinding.ActivityVideoTrimBinding;
import rprop.app.video.utils.DisplayUtils;

public final class VideoTrimActivity extends AppCompatActivity implements VideoTrimListener {
    private static final String TAG = "ffmpeg-ui";
    private static final String VIDEO_PATH_KEY = "path";
    private ActivityVideoTrimBinding mBinding;
    private ProgressDialog mProgressDialog;

    public static void call(final Activity activity, final Uri videoPath) {
        final Intent intent = new Intent(activity, VideoTrimActivity.class);
        intent.putExtra(VIDEO_PATH_KEY, videoPath);
        activity.startActivityForResult(intent, 0);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_video_trim);
        this.mBinding.trimmerView.setOnTrimVideoListener(this);

        final Intent intent = getIntent();
        final Uri uri = intent.getParcelableExtra(VIDEO_PATH_KEY);
        if (uri != null) {
            this.mBinding.trimmerView.setVideoURI(uri);
        } else {
            checkIntent(intent);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        this.mBinding.trimmerView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.mBinding.trimmerView.onDestroy();
    }

    // --------------------------------------------------------------------------------

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        checkIntent(intent);
    }

    private void checkIntent(final Intent intent) {
        final String action = intent.getAction();
        final String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("video/")) {
            final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                Log.d(TAG, type + ' ' + uri);
                this.mBinding.trimmerView.setVideoURI(uri);
            }
        }
    }

    // --------------------------------------------------------------------------------

    @Override
    public void onPhase(final String desc) {
        this.mBinding.trimmerView.notifyTrimTaskStart();
        runOnUiThread(() -> {
            if (this.mProgressDialog == null) {
                this.mProgressDialog = ProgressDialog.show(this, "", desc);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            this.mProgressDialog.setMessage(desc);
        });
    }

    @Override
    public void onFinished(final File outputFile) {
        final String result;
        if (outputFile == null) {
            result = getString(R.string.trimmed_failed);
        } else {
            final Uri videoCollection;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }
            final ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, outputFile.getName());
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 1);
            contentValues.put(MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separatorChar + "VideoCut");
            final ContentResolver resolver = getContentResolver();
            final Uri uri = resolver.insert(videoCollection, contentValues);
            if (uri == null) {
                result = getString(R.string.trimmed_failed);
            } else {
                result = getString(R.string.trimmed_done);
                try {
                    final ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "w");
                    if (pfd != null) {
                        try (final FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor())) {
                            try (final FileInputStream fileInputStream = new FileInputStream(outputFile)) {
                                fileInputStream.getChannel().transferTo(0, Long.MAX_VALUE, fileOutputStream.getChannel());
                            }
                        } finally {
                            pfd.close();
                        }
                    }
                } catch (final Exception e) {
                    Log.w(TAG, e);
                }
                contentValues.clear();
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0);
                resolver.update(uri, contentValues, null, null);
            }
        }
        this.mBinding.trimmerView.notifyTrimTaskDone();
        runOnUiThread(() -> {
            final ProgressDialog progressDialog = this.mProgressDialog;
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            this.mProgressDialog = null;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onCanceled() {
        runOnUiThread(() -> {
            this.mBinding.trimmerView.onDestroy();
            finish();
        });
    }
}
