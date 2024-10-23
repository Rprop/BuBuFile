package rprop.app.video.trim;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import rprop.app.video.R;
import rprop.app.video.utils.VideoUtils;

public final class VideoTrimAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final ArrayList<Bitmap> mBitmaps = new ArrayList<>();
    private final LayoutInflater mInflater;

    public VideoTrimAdapter(final Context context) {
        this.mInflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return new TrimmerViewHolder(this.mInflater.inflate(R.layout.video_thumb_item_layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        ((TrimmerViewHolder) holder).mThumbImageView.setImageBitmap(this.mBitmaps.get(position));
    }

    @Override
    public int getItemCount() {
        return this.mBitmaps.size();
    }

    public void addBitmaps(final Bitmap bitmap) {
        this.mBitmaps.add(bitmap);
        notifyItemInserted(this.mBitmaps.size() - 1);
    }

    private static final class TrimmerViewHolder extends RecyclerView.ViewHolder {
        final ImageView mThumbImageView;

        TrimmerViewHolder(final View itemView) {
            super(itemView);
            this.mThumbImageView = itemView.findViewById(R.id.thumb);
            final LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) this.mThumbImageView.getLayoutParams();
            layoutParams.width = VideoUtils.VIDEO_FRAMES_WIDTH / VideoUtils.MAX_COUNT_RANGE;
            this.mThumbImageView.setLayoutParams(layoutParams);
        }
    }
}
