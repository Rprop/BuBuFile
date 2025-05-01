package rprop.app.img;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import rp.file.R;

public final class ImageViewerView extends RelativeLayout {
    private final MaterialToolbar mToolbar;
    private final SubsamplingScaleImageView mImageView;
    private final CircularProgressIndicator mProgress;
    private final LinearLayout mTileLoadingHint;
    @Nullable
    private ImageViewerListener mListener;
    private boolean mInitialLoading;
    private boolean mTileLoadingHintVisible;

    public ImageViewerView(final Context context) {
        this(context, null);
    }

    public ImageViewerView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImageViewerView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.image_viewer_view, this, true);
        this.mToolbar = findViewById(R.id.toolbar);
        this.mImageView = findViewById(R.id.image_view);
        this.mProgress = findViewById(R.id.progress);
        this.mTileLoadingHint = findViewById(R.id.tile_loading_hint);

        final Drawable overflowIcon = AppCompatResources.getDrawable(context, R.drawable.ic_more_vert);
        if (overflowIcon != null) {
            final Drawable tintedIcon = DrawableCompat.wrap(overflowIcon.mutate());
            DrawableCompat.setTint(tintedIcon, 0xFFFFFFFF);
            this.mToolbar.setOverflowIcon(tintedIcon);
        }
        this.mToolbar.setNavigationOnClickListener(v -> {
            if (this.mListener != null) {
                this.mListener.onBack();
            }
        });
        this.mToolbar.setOnMenuItemClickListener(item -> {
            final int itemId = item.getItemId();
            if (itemId == R.id.action_share) {
                if (this.mListener != null) {
                    this.mListener.onShareRequested();
                }
                return true;
            }
            if (itemId == R.id.action_copy_to) {
                if (this.mListener != null) {
                    this.mListener.onCopyToRequested();
                }
                return true;
            }
            return false;
        });

        this.mImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        this.mImageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE);
        this.mImageView.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER);
        this.mImageView.setMaxScale(12f);
        this.mImageView.setOnVisibleTileLoadingListener(this::onVisibleTileLoadingChanged);
        this.mImageView.setOnImageEventListener(new SubsamplingScaleImageView.DefaultOnImageEventListener() {
            @Override
            public void onReady() {
                setInitialLoading(false);
            }

            @Override
            public void onImageLoaded() {
                setInitialLoading(false);
            }

            @Override
            public void onImageLoadError(final Exception e) {
                setInitialLoading(false);
                setTileLoadingHintVisible(false);
                if (ImageViewerView.this.mListener != null) {
                    ImageViewerView.this.mListener.onImageLoadFailed();
                }
            }
        });
    }

    public void setOnImageViewerListener(@Nullable final ImageViewerListener listener) {
        this.mListener = listener;
    }

    public void setTitle(@Nullable final CharSequence title) {
        this.mToolbar.setTitle(title);
    }

    public void setImageUri(@NonNull final Uri uri) {
        setInitialLoading(true);
        setTileLoadingHintVisible(false);
        this.mImageView.recycle();
        this.mImageView.setImage(ImageSource.uri(uri));
    }

    private void onVisibleTileLoadingChanged(final boolean loading) {
        if (this.mInitialLoading) {
            return;
        }
        setTileLoadingHintVisible(loading);
    }

    private void setInitialLoading(final boolean loading) {
        this.mInitialLoading = loading;
        this.mProgress.setVisibility(loading ? VISIBLE : GONE);
        if (loading) {
            setTileLoadingHintVisible(false);
        }
    }

    private void setTileLoadingHintVisible(final boolean visible) {
        if (this.mTileLoadingHintVisible == visible) {
            return;
        }
        this.mTileLoadingHintVisible = visible;
        this.mTileLoadingHint.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
