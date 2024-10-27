package rprop.app.video.select;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;

import rp.file.R;
import rprop.app.video.play.VideoPlayActivity;
import rprop.app.video.utils.DisplayUtils;
import rprop.app.video.utils.VideoUtils;

public final class VideoSelectAdapter extends CursorAdapter {
    private final MediaMetadataRetriever mMetadataRetriever = new MediaMetadataRetriever();
    private final int mVideoCoverSize = DisplayUtils.getDeviceWidth() / 3;

    VideoSelectAdapter(final Context context, final Cursor c) {
        super(context, c, false);
    }

    static void bindVideoItem(final View videoItemView, @NonNull final VideoSelectActivity activity,
                              final Uri uri, final long durationMs,
                              final MediaMetadataRetriever metadataRetriever, final int coverSize) {
        final Context context = activity;
        final TextView durationTv = videoItemView.findViewById(R.id.video_duration);
        final long durationSeconds = resolveDurationSeconds(context, uri, durationMs, metadataRetriever);
        if (durationSeconds >= 0) {
            durationTv.setText(VideoUtils.convertSecondsToTime(durationSeconds));
        }

        final ImageView videoCover = videoItemView.findViewById(R.id.cover_image);
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoCover.getLayoutParams();
        params.width = coverSize;
        params.height = coverSize;
        videoCover.setLayoutParams(params);

        Glide.with(context)
                .load(uri)
                .centerCrop()
                .override(coverSize, coverSize)
                .into(videoCover);

        final View selectToggle = videoItemView.findViewById(R.id.select_toggle);
        selectToggle.setOnClickListener(v -> {
            if (activity.isSelectionMode()) {
                activity.toggleVideoSelection(uri);
            } else {
                activity.enterSelectionMode(uri);
            }
        });

        updateSelectionUi(videoItemView, activity, uri);

        if (activity.isSelectionMode()) {
            videoItemView.setOnClickListener(v -> activity.toggleVideoSelection(uri));
            videoItemView.setOnLongClickListener(v -> {
                activity.toggleVideoSelection(uri);
                return true;
            });
            videoItemView.setOnCreateContextMenuListener(null);
            return;
        }

        videoItemView.setOnClickListener(v -> VideoPlayActivity.call(activity, uri));
        videoItemView.setOnLongClickListener(v -> {
            v.showContextMenu();
            return true;
        });
        videoItemView.setOnCreateContextMenuListener((menu, v, menuInfo) ->
                menu.add(context.getString(R.string.share_to)).setOnMenuItemClickListener(item -> {
                    final Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.setType("video/*");
                    shareIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(shareIntent, null), null);
                    return true;
                }));
    }

    private static void updateSelectionUi(final View videoItemView, final VideoSelectActivity activity,
                                          final Uri uri) {
        final View overlay = videoItemView.findViewById(R.id.selection_overlay);
        final View selectCircle = videoItemView.findViewById(R.id.select_circle);
        final TextView orderView = videoItemView.findViewById(R.id.selection_order);
        final boolean selected = activity.isVideoSelected(uri);
        if (selected) {
            overlay.setVisibility(View.VISIBLE);
            selectCircle.setVisibility(View.GONE);
            orderView.setVisibility(View.VISIBLE);
            orderView.setText(String.valueOf(activity.getVideoSelectionOrder(uri)));
        } else {
            overlay.setVisibility(View.GONE);
            selectCircle.setVisibility(View.VISIBLE);
            orderView.setVisibility(View.GONE);
        }
    }

    private static long resolveDurationSeconds(final Context context, final Uri uri, final long durationMs,
                                               final MediaMetadataRetriever metadataRetriever) {
        if (durationMs > 0) {
            return (durationMs + 500) / 1000;
        }
        if (metadataRetriever == null) {
            return -1;
        }
        try {
            metadataRetriever.setDataSource(context, uri);
            final String duration = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null) {
                return -1;
            }
            return (Long.parseLong(duration) + 500) / 1000;
        } catch (final Throwable ignored) {
            return -1;
        }
    }

    @Override
    public View newView(final Context context, final Cursor cursor, final ViewGroup parent) {
        final LayoutInflater inflater = LayoutInflater.from(context);
        return inflater.inflate(R.layout.video_select_gridview_item, null);
    }

    @Override
    public void bindView(final View itemView, final Context context, final Cursor cursor) {
        final Uri uri = getVideoUri(cursor);
        final long durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION));
        final View videoItemView = itemView.findViewById(R.id.video_view);
        bindVideoItem(videoItemView, (VideoSelectActivity) context, uri, durationMs,
                this.mMetadataRetriever, this.mVideoCoverSize);
    }

    private Uri getVideoUri(final Cursor cursor) {
        final String id = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
        return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
    }
}
