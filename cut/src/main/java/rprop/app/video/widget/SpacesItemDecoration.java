package rprop.app.video.widget;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public final class SpacesItemDecoration extends RecyclerView.ItemDecoration {

    private final int space;
    private final int thumbnailsCount;

    public SpacesItemDecoration(final int space, final int thumbnailsCount) {
        this.space = space;
        this.thumbnailsCount = thumbnailsCount;
    }

    @Override
    public void getItemOffsets(@NonNull final Rect outRect, @NonNull final View view,
                               final RecyclerView parent, @NonNull final RecyclerView.State state) {
        final int position = parent.getChildAdapterPosition(view);
        if (position == 0) {
            outRect.left = space;
            outRect.right = 0;
        } else if (thumbnailsCount > 10 && position == thumbnailsCount - 1) {
            outRect.left = 0;
            outRect.right = space;
        } else {
            outRect.left = 0;
            outRect.right = 0;
        }
    }
}
