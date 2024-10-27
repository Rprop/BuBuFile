package rprop.app.video.play;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import rp.file.R;
import rprop.app.video.trim.VideoView;
import rprop.app.video.utils.VideoUtils;

public class VideoPlayView extends FrameLayout {
    private final RelativeLayout mVideoContainer;
    private final VideoView mVideoView;
    private MediaPlayer mMediaPlayer;
    private final MaterialButton mPlayView;
    private final Slider mSeekBar;
    private final TextView mCurrentTimeView;
    private final TextView mTotalTimeView;
    private Uri mVideoURI;
    private VideoPlayListener mListener;
    private int mDuration;
    private boolean mUserSeeking;

    public VideoPlayView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoPlayView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        LayoutInflater.from(context).inflate(R.layout.video_player_view, this, true);

        this.mVideoContainer = findViewById(R.id.layout_surface_view);
        this.mVideoView = findViewById(R.id.video_loader);
        this.mSeekBar = findViewById(R.id.seek_bar);
        this.mCurrentTimeView = findViewById(R.id.current_time);
        this.mTotalTimeView = findViewById(R.id.total_time);

        this.mVideoView.setOnPreparedListener(mp -> {
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            onVideoPrepared(mp);
        });
        this.mVideoView.setOnCompletionListener(this::onVideoCompleted);

        this.mSeekBar.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                updateCurrentTime((int) value);
            }
        });
        this.mSeekBar.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(final Slider slider) {
                mUserSeeking = true;
                pausePlay();
            }

            @Override
            public void onStopTrackingTouch(final Slider slider) {
                seekTo((int) slider.getValue());
                mUserSeeking = false;
            }
        });

        this.mPlayView = findViewById(R.id.icon_video_play);
        this.mPlayView.setOnClickListener(v -> playVideoOrPause());

        findViewById(R.id.backBtn).setOnClickListener(v -> {
            if (this.mListener != null) {
                this.mListener.onBack();
            }
        });
        findViewById(R.id.trimBtn).setOnClickListener(v -> {
            if (this.mListener != null && this.mVideoURI != null) {
                this.mListener.onTrim(this.mVideoURI);
            }
        });
    }

    public void setVideoURI(final Uri videoURI) {
        this.mVideoURI = videoURI;
        this.mDuration = 0;
        this.mSeekBar.setValueTo(1f);
        this.mSeekBar.setValue(0f);
        updateCurrentTime(0);
        this.mTotalTimeView.setText(VideoUtils.convertSecondsToTime(0));
        this.mVideoView.setVideoURI(videoURI);
        this.mVideoView.requestFocus();
    }

    public Uri getVideoURI() {
        return this.mVideoURI;
    }

    public void setOnPlayListener(final VideoPlayListener listener) {
        this.mListener = listener;
    }

    public void onPause() {
        pausePlay();
    }

    private void onVideoPrepared(final MediaPlayer mp) {
        final int videoWidth = mp.getVideoWidth();
        final int videoHeight = mp.getVideoHeight();
        final int screenWidth = this.mVideoContainer.getWidth();
        final int screenHeight = this.mVideoContainer.getHeight();
        final ViewGroup.LayoutParams lp = this.mVideoView.getLayoutParams();
        if (videoHeight > videoWidth) {
            lp.width = screenWidth;
            lp.height = screenHeight;
        } else {
            lp.width = screenWidth;
            lp.height = (int) (lp.width * (videoHeight / (float) videoWidth));
        }
        this.mVideoView.setLayoutParams(lp);
        this.mMediaPlayer = mp;

        this.mDuration = this.mVideoView.getDuration();
        this.mSeekBar.setValueTo(Math.max(this.mDuration, 1));
        this.mTotalTimeView.setText(VideoUtils.convertSecondsToTime(this.mDuration / 1000));
        updateProgress(0);
        mp.seekTo(0, MediaPlayer.SEEK_CLOSEST);
    }

    private void onVideoCompleted(final MediaPlayer mp) {
        pausePlay();
        updateProgress(0);
        seekTo(0);
    }

    private void playVideoOrPause() {
        if (this.mVideoView.isPlaying()) {
            pausePlay();
        } else {
            this.mVideoView.start();
            startProgressUpdate();
            setPlayPauseViewIcon(true);
        }
    }

    private void pausePlay() {
        this.mVideoView.pause();
        stopProgressUpdate();
        setPlayPauseViewIcon(false);
    }

    private void seekTo(final long ms) {
        if (this.mMediaPlayer != null) {
            this.mMediaPlayer.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            updateProgress((int) ms);
        }
    }

    private void updateProgress(final int positionMs) {
        if (!this.mUserSeeking) {
            this.mSeekBar.setValue(positionMs);
        }
        updateCurrentTime(positionMs);
    }

    private void updateCurrentTime(final int positionMs) {
        this.mCurrentTimeView.setText(VideoUtils.convertSecondsToTime(positionMs / 1000));
    }

    private void setPlayPauseViewIcon(final boolean isPlaying) {
        this.mPlayView.setIconResource(isPlaying ? R.drawable.ic_video_pause_black : R.drawable.ic_video_play_black);
    }

    private final Handler mProgressHandler = new Handler();
    private final Runnable mProgressRunnable = new Runnable() {
        @Override
        public void run() {
            final int position = VideoPlayView.this.mVideoView.getCurrentPosition();
            if (position >= VideoPlayView.this.mDuration) {
                onVideoCompleted(null);
            } else {
                updateProgress(position);
                VideoPlayView.this.mProgressHandler.postDelayed(this, 200);
            }
        }
    };

    private void startProgressUpdate() {
        stopProgressUpdate();
        this.mProgressHandler.post(this.mProgressRunnable);
    }

    private void stopProgressUpdate() {
        this.mProgressHandler.removeCallbacks(this.mProgressRunnable);
    }
}
