package rprop.app.video.play;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import rp.file.R;
import rp.file.databinding.ActivityVideoPlayBinding;
import rprop.app.video.trim.VideoTrimActivity;
import rprop.app.video.utils.DisplayUtils;

public final class VideoPlayActivity extends AppCompatActivity implements VideoPlayListener {
    private static final String TAG = "ffmpeg-ui";
    private ActivityVideoPlayBinding mBinding;

    public static void call(final Activity activity, final Uri videoPath) {
        final Intent intent = new Intent(activity, VideoPlayActivity.class);
        intent.setData(videoPath);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent, null);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_video_play);
        this.mBinding.playerView.setOnPlayListener(this);
        resolveIntent(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.mBinding.playerView.onPause();
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
            if (type != null && type.startsWith("video/")) {
                final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    Log.d(TAG, type + ' ' + uri);
                    this.mBinding.playerView.setVideoURI(uri);
                }
            }
            return;
        }
        final Uri uri = intent.getData();
        if (uri != null) {
            Log.d(TAG, "view " + uri);
            this.mBinding.playerView.setVideoURI(uri);
        }
    }

    @Override
    public void onBack() {
        finish();
    }

    @Override
    public void onTrim(final Uri videoUri) {
        VideoTrimActivity.call(this, videoUri);
    }
}
