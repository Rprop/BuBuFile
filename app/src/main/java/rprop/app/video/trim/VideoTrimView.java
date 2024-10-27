package rprop.app.video.trim;

import static rprop.app.video.utils.VideoUtils.MAX_COUNT_RANGE;
import static rprop.app.video.utils.VideoUtils.MAX_SHOOT_DURATION;
import static rprop.app.video.utils.VideoUtils.RECYCLER_VIEW_PADDING;
import static rprop.app.video.utils.VideoUtils.THUMB_WIDTH;
import static rprop.app.video.utils.VideoUtils.VIDEO_FRAMES_WIDTH;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.atomic.AtomicBoolean;

import rp.file.BuildConfig;
import rp.file.R;
import rprop.app.video.utils.VideoUtils;
import rprop.app.video.widget.RangeSeekBarView;
import rprop.app.video.widget.SpacesItemDecoration;

public class VideoTrimView extends FrameLayout {
    private static final String TAG = "ffmpeg-ui";
    private final Context mContext;
    private final RelativeLayout mLinearVideo;
    private final android.widget.VideoView mVideoView;
    private MediaPlayer mMediaPlayer;
    private final MaterialButton mPlayView;
    private final RecyclerView mVideoThumbRecyclerView;
    private RangeSeekBarView mRangeSeekBarView;
    private final ImageView mRedProgressIcon;
    private float mAverageMsPx; // 每毫秒所占的px
    private float mAveragePxMs; // 每px所占用的ms毫秒
    private Uri mVideoURI;
    private VideoTrimListener mOnTrimVideoListener;
    private final VideoTrimAdapter mVideoThumbAdapter;
    private long mLeftProgressPos, mRightProgressPos;
    private long mRedProgressBarPos = 0;
    private long mScrollPos = 0;
    private int mThumbnailsCount;
    private final AtomicBoolean mTrimming = new AtomicBoolean(false);

    public VideoTrimView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoTrimView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        this.mContext = context;
        LayoutInflater.from(context).inflate(R.layout.video_trimmer_view, this, true);

        this.mRedProgressIcon = findViewById(R.id.positionIcon);

        this.mVideoThumbAdapter = new VideoTrimAdapter(context);
        this.mVideoThumbRecyclerView = findViewById(R.id.video_frames_recyclerView);
        this.mVideoThumbRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        this.mVideoThumbRecyclerView.setAdapter(mVideoThumbAdapter);
        this.mVideoThumbRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private int lastScrollX;

            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView, final int dx, final int dy) {
                final int scrollX = calcScrollXDistance();
                if (Math.abs(lastScrollX - scrollX) <= 0) {
                    // 达不到滑动的距离
                    return;
                }
                // 初始状态,why ? 因为默认的时候有35dp的空白！
                if (scrollX == -RECYCLER_VIEW_PADDING) {
                    mScrollPos = 0;
                    mLeftProgressPos = mRangeSeekBarView.getSelectedMinValue() + mScrollPos;
                    mRightProgressPos = mRangeSeekBarView.getSelectedMaxValue() + mScrollPos;
                    mRedProgressBarPos = mLeftProgressPos;
                } else {
                    mScrollPos = (long) (mAverageMsPx * (RECYCLER_VIEW_PADDING + scrollX) / THUMB_WIDTH);
                    mLeftProgressPos = mRangeSeekBarView.getSelectedMinValue() + mScrollPos;
                    mRightProgressPos = mRangeSeekBarView.getSelectedMaxValue() + mScrollPos;
                    mRedProgressBarPos = mLeftProgressPos;
                    mRedProgressIcon.setVisibility(GONE);
                    pausePlay();
                    seekTo(mLeftProgressPos);
                    mRangeSeekBarView.setStartEndTime(mLeftProgressPos, mRightProgressPos);
                    mRangeSeekBarView.invalidate();
                }
                lastScrollX = scrollX;
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onScrolled " + mScrollPos + ' ' + mLeftProgressPos + ' ' + mRightProgressPos + ' ' + mRedProgressBarPos);
                }
            }

            private int calcScrollXDistance() {
                final LinearLayoutManager layoutManager = (LinearLayoutManager) mVideoThumbRecyclerView.getLayoutManager();
                assert layoutManager != null;
                final int position = layoutManager.findFirstVisibleItemPosition();
                final View firstVisibleChildView = layoutManager.findViewByPosition(position);
                assert firstVisibleChildView != null;
                return position * firstVisibleChildView.getWidth() - firstVisibleChildView.getLeft();
            }
        });

        findViewById(R.id.finishBtn).setOnClickListener(view -> onSaveClicked());
        findViewById(R.id.cancelBtn).setOnClickListener(view -> onCancelClicked());

        this.mLinearVideo = findViewById(R.id.layout_surface_view);
        this.mVideoView = findViewById(R.id.video_loader);
        this.mVideoView.setOnPreparedListener(mp -> {
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            onVideoPrepared(mp);
        });
        this.mVideoView.setOnCompletionListener(this::onVideoCompleted);

        this.mPlayView = findViewById(R.id.icon_video_play);
        this.mPlayView.setOnClickListener(v -> playVideoOrPause());
    }

    public void setVideoURI(final Uri videoURI) {
        this.mVideoURI = videoURI;
        this.mVideoView.setVideoURI(videoURI);
        this.mVideoView.requestFocus();
    }

    // --------------------------------------------------------------------------------

    private void onVideoPrepared(final MediaPlayer mp) {
        final int videoWidth = mp.getVideoWidth();
        final int videoHeight = mp.getVideoHeight();
        final int screenWidth = this.mLinearVideo.getWidth();
        final int screenHeight = this.mLinearVideo.getHeight();
        final ViewGroup.LayoutParams lp = mVideoView.getLayoutParams();
        if (videoHeight > videoWidth) {
            lp.width = screenWidth;
            lp.height = screenHeight;
        } else {
            lp.width = screenWidth;
            lp.height = (int) (lp.width * (videoHeight / (float) videoWidth));
        }
        this.mVideoView.setLayoutParams(lp);
        this.mMediaPlayer = mp;
        mp.seekTo(this.mRedProgressBarPos, MediaPlayer.SEEK_CLOSEST);
        if (this.mRangeSeekBarView == null) {
            final int duration = this.mVideoView.getDuration();
            initRangeSeekBarView(duration);
            VideoUtils.generateThumbnailsInBackground(this.mContext.getApplicationContext(), this.mVideoURI,
                    this.mThumbnailsCount, 0, duration,
                    new VideoUtils.ThumbnailCallback() {
                        @Override
                        public void on(final Bitmap bitmap) {
                            ((Activity) getContext()).runOnUiThread(() -> {
                                if (next()) {
                                    VideoTrimView.this.mVideoThumbAdapter.addBitmaps(bitmap);
                                }
                            });
                            waitTrimTask();
                        }

                        @Override
                        public boolean next() {
                            final Activity activity = (Activity) getContext();
                            return !activity.isDestroyed() && !activity.isFinishing();
                        }
                    });
        }
    }

    private void onVideoCompleted(final MediaPlayer mp) {
        seekTo(this.mLeftProgressPos);
        setPlayPauseViewIcon(false);
    }

    private void seekTo(final long ms) {
        this.mMediaPlayer.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "seekTo=" + ms);
        }
    }

    // --------------------------------------------------------------------------------

    private void initRangeSeekBarView(final int duration) {
        this.mLeftProgressPos = 0;
        if (duration <= MAX_SHOOT_DURATION) {
            this.mRightProgressPos = duration;
            this.mThumbnailsCount = MAX_COUNT_RANGE;
        } else {
            this.mRightProgressPos = MAX_SHOOT_DURATION;
            this.mThumbnailsCount = (int) (duration * 1.0f / (MAX_SHOOT_DURATION * 1.0f) * MAX_COUNT_RANGE);
        }
        this.mVideoThumbRecyclerView.addItemDecoration(new SpacesItemDecoration(RECYCLER_VIEW_PADDING, this.mThumbnailsCount));
        final RangeSeekBarView rangeSeekBarView = new RangeSeekBarView(this.mContext, this.mLeftProgressPos, this.mRightProgressPos);
        this.mRangeSeekBarView = rangeSeekBarView;
        rangeSeekBarView.setSelectedMinValue(this.mLeftProgressPos);
        rangeSeekBarView.setSelectedMaxValue(this.mRightProgressPos);
        rangeSeekBarView.setStartEndTime(this.mLeftProgressPos, this.mRightProgressPos);
        rangeSeekBarView.setMinShootTime(VideoUtils.MIN_SHOOT_DURATION);
        rangeSeekBarView.setNotifyWhileDragging(true);
        rangeSeekBarView.setOnRangeSeekBarChangeListener((bar, minValue, maxValue, action, isMin, pressedThumb) -> {
            this.mLeftProgressPos = minValue + mScrollPos;
            this.mRightProgressPos = maxValue + mScrollPos;
            this.mRedProgressBarPos = mLeftProgressPos;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onRanged " + this.mScrollPos + ' ' + this.mLeftProgressPos + ' ' + this.mRightProgressPos + ' ' + this.mRedProgressBarPos);
            }
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    seekTo(pressedThumb == RangeSeekBarView.Thumb.MIN ? mLeftProgressPos : mRightProgressPos);
                    break;
                case MotionEvent.ACTION_UP:
                    seekTo(mLeftProgressPos);
                    break;
                default:
                    break;
            }
            this.mRangeSeekBarView.setStartEndTime(mLeftProgressPos, mRightProgressPos);
        });
        ((LinearLayout) findViewById(R.id.seekBarLayout)).addView(rangeSeekBarView);
        if (this.mThumbnailsCount - MAX_COUNT_RANGE > 0) {
            this.mAverageMsPx = (duration - MAX_SHOOT_DURATION) / (float) (this.mThumbnailsCount - MAX_COUNT_RANGE);
        } else {
            this.mAverageMsPx = 0f;
        }
        this.mAveragePxMs = (VIDEO_FRAMES_WIDTH * 1.0f / (this.mRightProgressPos - this.mLeftProgressPos));
    }

    // --------------------------------------------------------------------------------

    private void onSaveClicked() {
        pausePlay();

        final Context context = getContext();
        final EditText startEditText = new EditText(context);
        startEditText.setText(VideoUtils.convertSecondsToTime(this.mLeftProgressPos / 1000));
        startEditText.setGravity(Gravity.CENTER);
        startEditText.setInputType(InputType.TYPE_CLASS_DATETIME);
        final EditText endEditText = new EditText(context);
        endEditText.setText(VideoUtils.convertSecondsToTime(this.mRightProgressPos / 1000));
        endEditText.setGravity(Gravity.CENTER);
        endEditText.setInputType(InputType.TYPE_CLASS_DATETIME);
        final MaterialCheckBox checkBox = new MaterialCheckBox(context);
        checkBox.setText("使用精确模式 (耗时更长)");
        checkBox.setChecked(false);
        checkBox.setGravity(Gravity.CENTER);
        final LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(48, 24, 48, 8);
        linearLayout.addView(startEditText);
        linearLayout.addView(endEditText);
        linearLayout.addView(checkBox);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.adj_time)
                .setView(linearLayout)
                .setCancelable(true)
                .setPositiveButton("确定", (dialog, which) -> {
                    final String start = startEditText.getText().toString().replace(" ", "").trim();
                    final String end = endEditText.getText().toString().replace(" ", "").trim();
                    final boolean precise = checkBox.isChecked();
                    VideoUtils.trim(context.getApplicationContext(),
                            this.mVideoURI,
                            start,
                            end,
                            precise,
                            this.mOnTrimVideoListener);
                })
                .show();

    }

    private void onCancelClicked() {
        this.mOnTrimVideoListener.onCanceled();
    }

    private void playVideoOrPause() {
        if (this.mVideoView.isPlaying()) {
            pausePlay();
        } else {
            this.mVideoView.start();
            playRedProgressAnimation();
            setPlayPauseViewIcon(true);
        }
    }

    // --------------------------------------------------------------------------------

    private void waitTrimTask() {
        if (this.mTrimming.get()) {
            synchronized (this.mTrimming) {
                if (this.mTrimming.get()) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "paused");
                    }
                    try {
                        this.mTrimming.wait();
                    } catch (final InterruptedException ignored) {
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "continued");
            }
        }
    }

    public void notifyTrimTaskStart() {
        this.mTrimming.set(true);
    }

    public void notifyTrimTaskDone() {
        this.mTrimming.set(false);
        synchronized (this.mTrimming) {
            this.mTrimming.notifyAll();
        }
    }

    // --------------------------------------------------------------------------------

    public void setOnTrimVideoListener(final VideoTrimListener onTrimVideoListener) {
        this.mOnTrimVideoListener = onTrimVideoListener;
    }

    private void setPlayPauseViewIcon(final boolean isPlaying) {
        this.mPlayView.setIconResource(isPlaying ? R.drawable.ic_video_pause_black : R.drawable.ic_video_play_black);
    }

    // --------------------------------------------------------------------------------

    private final Handler mAnimationHandler = new Handler();
    private final Runnable mAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            final long currentPosition = VideoTrimView.this.mVideoView.getCurrentPosition();
            VideoTrimView.this.mRangeSeekBarView.setCurrentPosition(currentPosition);
            if (currentPosition >= (VideoTrimView.this.mRightProgressPos)) {
                seekTo(VideoTrimView.this.mLeftProgressPos);
                onPause();
            } else {
                final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) VideoTrimView.this.mRedProgressIcon.getLayoutParams();
                params.setMarginStart((int) (RECYCLER_VIEW_PADDING + (currentPosition - mScrollPos) * VideoTrimView.this.mAveragePxMs));
                VideoTrimView.this.mRedProgressIcon.setLayoutParams(params);
                VideoTrimView.this.mAnimationHandler.postDelayed(this, 50);
            }
        }
    };

    private void playRedProgressAnimation() {
        pauseRedProgressAnimation();
        if (this.mRedProgressIcon.getVisibility() != View.VISIBLE) {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) this.mRedProgressIcon.getLayoutParams();
            params.setMarginStart(RECYCLER_VIEW_PADDING);
            this.mRedProgressIcon.setLayoutParams(params);
            this.mRedProgressIcon.setVisibility(View.VISIBLE);
        }
        this.mAnimationHandler.post(this.mAnimationRunnable);
    }

    private void pauseRedProgressAnimation() {
        this.mAnimationHandler.removeCallbacks(this.mAnimationRunnable);
    }

    // --------------------------------------------------------------------------------

    private void pausePlay() {
        this.mVideoView.pause();
        this.mRedProgressBarPos = this.mVideoView.getCurrentPosition();
        pauseRedProgressAnimation();
        setPlayPauseViewIcon(false);
    }

    // --------------------------------------------------------------------------------

    public void onPause() {
        pausePlay();
    }

    public void onDestroy() {
        FFmpegKit.cancel();
    }
}
