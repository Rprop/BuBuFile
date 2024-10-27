package rprop.app.video.concat;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.List;

import rp.file.R;
import rprop.app.video.play.VideoPlayActivity;
import rprop.app.video.utils.DisplayUtils;

public final class VideoConcatAdapter extends RecyclerView.Adapter<VideoConcatAdapter.ViewHolder> {
    private static final Object ORDER_PAYLOAD = new Object();

    public interface OnItemActionListener {
        void onRemove(final int position);
    }

    private final Context mContext;
    private final List<Uri> mVideoUris;
    private final OnItemActionListener mListener;
    private final int mCoverSize = DisplayUtils.dpToPx(56);
    private ItemTouchHelper mItemTouchHelper;
    private boolean mDragEnabled = true;

    VideoConcatAdapter(@NonNull final Context context, @NonNull final List<Uri> videoUris,
                       @NonNull final OnItemActionListener listener) {
        this.mContext = context;
        this.mVideoUris = videoUris;
        this.mListener = listener;
    }

    void setItemTouchHelper(@NonNull final ItemTouchHelper itemTouchHelper) {
        this.mItemTouchHelper = itemTouchHelper;
    }

    void setDragEnabled(final boolean enabled) {
        this.mDragEnabled = enabled;
    }

    boolean isDragEnabled() {
        return this.mDragEnabled;
    }

    void onItemMove(final int fromPosition, final int toPosition) {
        if (fromPosition == toPosition
                || fromPosition < 0 || toPosition < 0
                || fromPosition >= this.mVideoUris.size()
                || toPosition >= this.mVideoUris.size()) {
            return;
        }
        final Uri uri = this.mVideoUris.remove(fromPosition);
        this.mVideoUris.add(toPosition, uri);
        notifyItemMoved(fromPosition, toPosition);
    }

    void refreshOrderNumbers() {
        if (getItemCount() == 0) {
            return;
        }
        notifyItemRangeChanged(0, getItemCount(), ORDER_PAYLOAD);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(this.mContext).inflate(R.layout.video_concat_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position,
                                 @NonNull final List<Object> payloads) {
        if (!payloads.isEmpty()) {
            holder.mOrderView.setText(String.valueOf(position + 1));
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final Uri uri = this.mVideoUris.get(position);
        holder.mOrderView.setText(String.valueOf(position + 1));

        String name = uri.getLastPathSegment();
        try {
            final DocumentFile documentFile = DocumentFile.fromSingleUri(this.mContext, uri);
            if (documentFile != null && documentFile.getName() != null) {
                name = documentFile.getName();
            }
        } catch (final Exception ignored) {
        }
        holder.mNameView.setText(name != null ? name : uri.toString());

        Glide.with(this.mContext)
                .load(uri)
                .centerCrop()
                .override(this.mCoverSize, this.mCoverSize)
                .into(holder.mCoverView);

        holder.mCoverView.setOnClickListener(v -> VideoPlayActivity.call((Activity) this.mContext, uri));

        holder.mRemoveView.setOnClickListener(v -> {
            final int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                this.mListener.onRemove(adapterPosition);
            }
        });
        holder.mNameView.setOnTouchListener((v, event) -> {
            if (this.mDragEnabled
                    && this.mItemTouchHelper != null
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                this.mItemTouchHelper.startDrag(holder);
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return this.mVideoUris.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView mOrderView;
        private final ImageView mCoverView;
        private final TextView mNameView;
        private final MaterialButton mRemoveView;

        ViewHolder(@NonNull final View itemView) {
            super(itemView);
            this.mOrderView = itemView.findViewById(R.id.order_index);
            this.mCoverView = itemView.findViewById(R.id.cover_image);
            this.mNameView = itemView.findViewById(R.id.video_name);
            this.mRemoveView = itemView.findViewById(R.id.btn_remove);
        }
    }
}
