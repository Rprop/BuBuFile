package rprop.app.video.select;

import android.app.Activity;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import rp.file.R;
import rprop.app.video.utils.DisplayUtils;

public final class VideoSelectListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SECTION_HEADER = 0;
    private static final int TYPE_MONTH_HEADER = 1;
    private static final int TYPE_VIDEO_ROW = 2;
    private static final int COLUMNS = 3;

    private final Activity mActivity;
    private final android.media.MediaMetadataRetriever mMetadataRetriever =
            new android.media.MediaMetadataRetriever();
    private final int mVideoCoverSize = DisplayUtils.getDeviceWidth() / COLUMNS;
    private final List<ListItem> mItems = new ArrayList<>();

    public VideoSelectListAdapter(final Activity activity) {
        this.mActivity = activity;
    }

    public void setItems(final List<ListItem> items) {
        this.mItems.clear();
        if (items != null) {
            this.mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(final int position) {
        final ListItem item = this.mItems.get(position);
        if (item instanceof SectionHeader) {
            return TYPE_SECTION_HEADER;
        }
        if (item instanceof MonthHeader) {
            return TYPE_MONTH_HEADER;
        }
        return TYPE_VIDEO_ROW;
    }

    @Override
    public int getItemCount() {
        return this.mItems.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_SECTION_HEADER:
                return new SectionHeaderViewHolder(
                        inflater.inflate(R.layout.video_select_section_header, parent, false));
            case TYPE_MONTH_HEADER:
                return new MonthHeaderViewHolder(
                        inflater.inflate(R.layout.video_select_month_header, parent, false));
            default:
                return new VideoRowViewHolder(
                        inflater.inflate(R.layout.video_select_video_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        final ListItem item = this.mItems.get(position);
        if (holder instanceof SectionHeaderViewHolder) {
            ((SectionHeaderViewHolder) holder).bind(((SectionHeader) item).titleResId);
        } else if (holder instanceof MonthHeaderViewHolder) {
            ((MonthHeaderViewHolder) holder).bind(((MonthHeader) item).label);
        } else if (holder instanceof VideoRowViewHolder) {
            ((VideoRowViewHolder) holder).bind(((VideoRow) item).entries);
        }
    }

    public abstract static class ListItem {
    }

    public static final class SectionHeader extends ListItem {
        @StringRes
        public final int titleResId;

        public SectionHeader(@StringRes final int titleResId) {
            this.titleResId = titleResId;
        }
    }

    public static final class MonthHeader extends ListItem {
        public final String label;

        public MonthHeader(final String label) {
            this.label = label;
        }
    }

    public static final class VideoEntry {
        public final Uri uri;
        public final long durationMs;

        public VideoEntry(final Uri uri, final long durationMs) {
            this.uri = uri;
            this.durationMs = durationMs;
        }
    }

    public static final class VideoRow extends ListItem {
        public final List<VideoEntry> entries;

        public VideoRow(final List<VideoEntry> entries) {
            this.entries = entries;
        }
    }

    private static final class SectionHeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView mTitleView;

        SectionHeaderViewHolder(final View itemView) {
            super(itemView);
            this.mTitleView = (TextView) itemView;
        }

        void bind(@StringRes final int titleResId) {
            this.mTitleView.setText(titleResId);
        }
    }

    private static final class MonthHeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView mTitleView;

        MonthHeaderViewHolder(final View itemView) {
            super(itemView);
            this.mTitleView = (TextView) itemView;
        }

        void bind(final String label) {
            this.mTitleView.setText(label);
        }
    }

    private final class VideoRowViewHolder extends RecyclerView.ViewHolder {
        private final View[] mSlots = new View[COLUMNS];

        VideoRowViewHolder(final View itemView) {
            super(itemView);
            this.mSlots[0] = itemView.findViewById(R.id.slot0);
            this.mSlots[1] = itemView.findViewById(R.id.slot1);
            this.mSlots[2] = itemView.findViewById(R.id.slot2);
        }

        void bind(final List<VideoEntry> entries) {
            for (int i = 0; i < COLUMNS; i++) {
                final View slot = this.mSlots[i];
                if (i < entries.size()) {
                    final VideoEntry entry = entries.get(i);
                    slot.setVisibility(View.VISIBLE);
                    VideoSelectAdapter.bindVideoItem(slot, (VideoSelectActivity) VideoSelectListAdapter.this.mActivity,
                            entry.uri, entry.durationMs,
                            VideoSelectListAdapter.this.mMetadataRetriever,
                            VideoSelectListAdapter.this.mVideoCoverSize);
                } else {
                    slot.setVisibility(View.INVISIBLE);
                    slot.setOnClickListener(null);
                    slot.setOnLongClickListener(null);
                    slot.setOnCreateContextMenuListener(null);
                }
            }
        }
    }
}
