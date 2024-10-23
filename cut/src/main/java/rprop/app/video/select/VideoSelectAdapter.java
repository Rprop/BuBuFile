package rprop.app.video.select;

import android.app.Activity;
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

import com.bumptech.glide.Glide;

import rprop.app.video.R;
import rprop.app.video.trim.VideoTrimActivity;
import rprop.app.video.utils.DisplayUtils;
import rprop.app.video.utils.VideoUtils;

public final class VideoSelectAdapter extends CursorAdapter {
    private final MediaMetadataRetriever mMetadataRetriever = new MediaMetadataRetriever();
    private final int mVideoCoverSize = DisplayUtils.getDeviceWidth() / 3;

    VideoSelectAdapter(final Context context, final Cursor c) {
        super(context, c, false);
    }

    @Override
    public View newView(final Context context, final Cursor cursor, final ViewGroup parent) {
        final LayoutInflater inflater = LayoutInflater.from(context);
        return inflater.inflate(R.layout.video_select_gridview_item, null);
    }

    @Override
    public void bindView(final View itemView, final Context context, final Cursor cursor) {
        final Uri uri = getVideoUri(cursor);
        final String duration;
        try {
            this.mMetadataRetriever.setDataSource(context, uri);
            duration = this.mMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null) return;
        } catch (final Throwable ignored) {
            return;
        }

        final View videoItemView = itemView.findViewById(R.id.video_view);
        final TextView durationTv = videoItemView.findViewById(R.id.video_duration);
        durationTv.setText(VideoUtils.convertSecondsToTime(Integer.parseInt(duration) / 1000));

        final ImageView videoCover = videoItemView.findViewById(R.id.cover_image);
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoCover.getLayoutParams();
        params.width = this.mVideoCoverSize;
        params.height = this.mVideoCoverSize;
        videoCover.setLayoutParams(params);

        Glide.with(context)
                .load(uri)
                .centerCrop()
                .override(this.mVideoCoverSize, this.mVideoCoverSize)
                .into(videoCover);

        videoItemView.setOnClickListener(v ->
                VideoTrimActivity.call((Activity) v.getContext(), uri)
        );
        videoItemView.setOnLongClickListener(v -> {
            v.showContextMenu();
            return true;
        });
        videoItemView.setOnCreateContextMenuListener((menu, v, menuInfo) -> {
                    menu.add("分享").setOnMenuItemClickListener(item -> {
                        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                        shareIntent.setType("video/*");
                        v.getContext().startActivity(Intent.createChooser(shareIntent, null), null);
                        return true;
                    });
                    menu.add("删除").setOnMenuItemClickListener(item -> {
                        ((VideoSelectActivity) v.getContext()).createDeleteRequest(uri, true);
                        return true;
                    });
                }
        );
    }

    private Uri getVideoUri(final Cursor cursor) {
        // content://media/external/video/media/
        final String id = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
        return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
    }
}
