package rprop.app.video.concat;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

final class VideoConcatItemTouchCallback extends ItemTouchHelper.Callback {
    private final VideoConcatAdapter mAdapter;

    VideoConcatItemTouchCallback(@NonNull final VideoConcatAdapter adapter) {
        this.mAdapter = adapter;
    }

    @Override
    public int getMovementFlags(@NonNull final RecyclerView recyclerView,
                                @NonNull final RecyclerView.ViewHolder viewHolder) {
        if (!this.mAdapter.isDragEnabled()) {
            return makeMovementFlags(0, 0);
        }
        final int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
        return makeMovementFlags(dragFlags, 0);
    }

    @Override
    public boolean onMove(@NonNull final RecyclerView recyclerView,
                          @NonNull final RecyclerView.ViewHolder viewHolder,
                          @NonNull final RecyclerView.ViewHolder target) {
        final int fromPosition = viewHolder.getBindingAdapterPosition();
        final int toPosition = target.getBindingAdapterPosition();
        if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
            return false;
        }
        this.mAdapter.onItemMove(fromPosition, toPosition);
        return true;
    }

    @Override
    public void clearView(@NonNull final RecyclerView recyclerView,
                          @NonNull final RecyclerView.ViewHolder viewHolder) {
        super.clearView(recyclerView, viewHolder);
        this.mAdapter.refreshOrderNumbers();
    }

    @Override
    public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder, final int direction) {
    }

    @Override
    public boolean isLongPressDragEnabled() {
        return false;
    }
}
