package rprop.app.video.trim;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

public final class VideoView extends android.widget.VideoView implements GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener {
    private static final float MAX_SCALE = 10.f;
    private static final float MIN_SCALE = 1.f;
    private final GestureDetector mGestureDetector;
    private float mPrevTwoPointerDistance;

    public VideoView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        this.mGestureDetector = new GestureDetector(context, this);
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        super.onTouchEvent(event);
        if (event.getPointerCount() == 1) {
            return this.mGestureDetector.onTouchEvent(event);
        } else if (event.getPointerCount() == 2) {
            return onScaleEvent(event);
        }
        return false;
    }

    private boolean onScaleEvent(final MotionEvent event) {
        if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN) {
            this.mPrevTwoPointerDistance = spacing(event); // 两点按下时的距离
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                this.mPrevTwoPointerDistance = spacing(event); // 两点按下时的距离
                return true;
            case MotionEvent.ACTION_MOVE:
                // 只有2个手指的时候才有放大缩小的操作
                final float currentDist = spacing(event);
                final float scale = currentDist / mPrevTwoPointerDistance;
                setScale(scale);
                return true;
        }
        return false;
    }

    private float spacing(final MotionEvent event) {
        if (event.getPointerCount() == 2) {
            final float x = event.getX(0) - event.getX(1);
            final float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        } else {
            return 0;
        }
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int width = getDefaultSize(0, widthMeasureSpec);
        final int height = getDefaultSize(0, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    /**
     * 触摸使用的移动事件
     */
    private void setSelfPivot(final float lessX, final float lessY) {
        float setPivotX = getPivotX() + lessX;
        float setPivotY = getPivotY() + lessY;
        if (setPivotX < 0 && setPivotY < 0) {
            setPivotX = 0;
            setPivotY = 0;
        } else if (setPivotX > 0 && setPivotY < 0) {
            setPivotY = 0;
            if (setPivotX > getWidth()) {
                setPivotX = getWidth();
            }
        } else if (setPivotX < 0 && setPivotY > 0) {
            setPivotX = 0;
            if (setPivotY > getHeight()) {
                setPivotY = getHeight();
            }
        } else {
            if (setPivotX > getWidth()) {
                setPivotX = getWidth();
            }
            if (setPivotY > getHeight()) {
                setPivotY = getHeight();
            }
        }
        setPivot(setPivotX, setPivotY);
    }

    /**
     * 平移画面，当画面的宽或高大于屏幕宽高时，调用此方法进行平移
     */
    public void setPivot(final float x, final float y) {
        setPivotX(x);
        setPivotY(y);
    }

    /**
     * 设置放大缩小
     */
    public void setScale(final float scale) {
        float currentScaleX = getScaleX() * scale;
        float currentScaleY = getScaleY() * scale;
        if (currentScaleX > MAX_SCALE || currentScaleY > MAX_SCALE) {
            currentScaleX = MAX_SCALE;
            currentScaleY = MAX_SCALE;
        } else if (currentScaleX < MIN_SCALE || currentScaleY < MIN_SCALE) {
            currentScaleX = MIN_SCALE;
            currentScaleY = MIN_SCALE;
        }
        setScaleX(currentScaleX);
        setScaleY(currentScaleY);
    }

    @Override
    public boolean onDown(@NonNull final MotionEvent e) {
        return true;
    }

    @Override
    public void onShowPress(@NonNull MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(@NonNull final MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(final MotionEvent e1, @NonNull final MotionEvent e2,
                            final float distanceX, final float distanceY) {
        if (e1 != null) {
            setSelfPivot(e1.getX() - e2.getX(), e1.getY() - e2.getY());
        }
        return true;
    }

    @Override
    public void onLongPress(@NonNull final MotionEvent e) {

    }

    @Override
    public boolean onFling(final MotionEvent e1, @NonNull final MotionEvent e2,
                           final float velocityX, final float velocityY) {
        return false;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull final MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDoubleTap(@NonNull final MotionEvent e) {
        final float scaleX = getScaleX();
        if (scaleX > 1) {
            startScaleAnimation(scaleX, 1);
        } else {
            startScaleAnimation(scaleX, 2);
        }
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(@NonNull final MotionEvent e) {
        return false;
    }

    private void startScaleAnimation(final float start, final float end) {
        final ValueAnimator anim = ValueAnimator.ofFloat(start, end);
        anim.setDuration(200);
        anim.addUpdateListener(animation -> {
            final float currentValue = (float) animation.getAnimatedValue();
            setScaleX(currentValue);
            setScaleY(currentValue);
        });
        anim.start();
    }
}


