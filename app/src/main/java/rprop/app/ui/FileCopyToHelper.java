package rprop.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Collections;
import java.util.List;

import rp.file.R;

public final class FileCopyToHelper {
    public interface UriProvider {
        @Nullable
        Uri getUri();
    }

    public interface UriListProvider {
        @NonNull
        List<Uri> getUris();
    }

    private final AppCompatActivity mActivity;
    private final UriListProvider mUriListProvider;
    @Nullable
    private final Runnable mOnComplete;
    private final ActivityResultLauncher<Intent> mLauncher;

    public FileCopyToHelper(final AppCompatActivity activity, final UriProvider uriProvider) {
        this(activity, uriProvider, null);
    }

    public FileCopyToHelper(final AppCompatActivity activity, final UriProvider uriProvider,
                            @Nullable final Runnable onComplete) {
        this(activity, () -> {
            final Uri uri = uriProvider.getUri();
            if (uri == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(uri);
        }, onComplete);
    }

    public FileCopyToHelper(final AppCompatActivity activity, final UriListProvider uriListProvider,
                            @Nullable final Runnable onComplete) {
        this.mActivity = activity;
        this.mUriListProvider = uriListProvider;
        this.mOnComplete = onComplete;
        this.mLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    final List<Uri> uris = this.mUriListProvider.getUris();
                    if (result.getResultCode() != AppCompatActivity.RESULT_OK
                            || result.getData() == null
                            || uris.isEmpty()) {
                        complete();
                        return;
                    }
                    final Uri treeUri = result.getData().getData();
                    if (treeUri == null) {
                        Toast.makeText(this.mActivity, R.string.copy_failed, Toast.LENGTH_LONG).show();
                        complete();
                        return;
                    }
                    int copied = 0;
                    for (final Uri uri : uris) {
                        if (FileUriActions.moveToTree(this.mActivity, uri, treeUri)) {
                            copied++;
                        }
                    }
                    final int message;
                    if (copied == uris.size()) {
                        message = R.string.copy_done;
                    } else if (copied > 0) {
                        message = R.string.copy_failed_partial;
                    } else {
                        message = R.string.copy_failed;
                    }
                    Toast.makeText(this.mActivity, message, Toast.LENGTH_LONG).show();
                    complete();
                });
    }

    public void start() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        this.mLauncher.launch(intent);
    }

    private void complete() {
        if (this.mOnComplete != null) {
            this.mOnComplete.run();
        }
    }
}
