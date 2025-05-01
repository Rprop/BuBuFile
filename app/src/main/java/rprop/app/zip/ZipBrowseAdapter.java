package rprop.app.zip;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rp.file.R;

final class ZipBrowseAdapter extends RecyclerView.Adapter<ZipBrowseAdapter.Holder> {
    interface Listener {
        void onItemClick(@NonNull ZipArchiveSession.BrowseItem item);
    }

    @NonNull
    private final List<ZipArchiveSession.BrowseItem> mItems = new ArrayList<>();
    @NonNull
    private final Listener mListener;

    ZipBrowseAdapter(@NonNull final Listener listener) {
        this.mListener = listener;
    }

    void submit(@NonNull final List<ZipArchiveSession.BrowseItem> items) {
        this.mItems.clear();
        this.mItems.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.zip_browse_list_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, final int position) {
        final ZipArchiveSession.BrowseItem item = this.mItems.get(position);
        holder.nameView.setText(item.directory ? item.displayName + '/' : item.displayName);
        if (item.directory) {
            holder.metaView.setText(R.string.zip_folder);
        } else if (item.encrypted) {
            final String size = formatSize(item.size);
            if (size.isEmpty()) {
                holder.metaView.setText(R.string.zip_encrypted);
            } else {
                holder.metaView.setText(holder.itemView.getContext()
                        .getString(R.string.zip_encrypted) + " · " + size);
            }
        } else {
            holder.metaView.setText(formatSize(item.size));
        }
        holder.itemView.setOnClickListener(v -> this.mListener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return this.mItems.size();
    }

    @NonNull
    private static String formatSize(final long size) {
        if (size < 0L) {
            return "";
        }
        if (size < 1024L) {
            return size + " B";
        }
        final double value;
        final String unit;
        if (size < 1024L * 1024L) {
            value = size / 1024.0;
            unit = "KB";
        } else if (size < 1024L * 1024L * 1024L) {
            value = size / (1024.0 * 1024.0);
            unit = "MB";
        } else {
            value = size / (1024.0 * 1024.0 * 1024.0);
            unit = "GB";
        }
        return String.format(Locale.US, "%.1f %s", value, unit);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        @NonNull
        final TextView nameView;
        @NonNull
        final TextView metaView;

        Holder(@NonNull final View itemView) {
            super(itemView);
            this.nameView = itemView.findViewById(R.id.item_name);
            this.metaView = itemView.findViewById(R.id.item_meta);
        }
    }
}
