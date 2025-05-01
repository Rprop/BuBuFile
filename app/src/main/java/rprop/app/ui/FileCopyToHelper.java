package rprop.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import rp.file.R;

public final class FileCopyToHelper {
    public interface UriProvider {
        @Nullable
        Uri getUri();
    }

    private final AppCompatActivity mActivity;
    private final UriProvider mUriProvider;
    @Nullable
    private final Runnable mOnComplete;
    private final ActivityResultLauncher<Intent> mLauncher;

    public FileCopyToHelper(final AppCompatActivity activity, final UriProvider uriProvider) {
        this(activity, uriProvider, null);
    }

    public FileCopyToHelper(final AppCompatActivity activity, final UriProvider uriProvider,
                            @Nullable final Runnable onComplete) {
        this.mActivity = activity;
        this.mUriProvider = uriProvider;
        this.mOnComplete = onComplete;
        this.mLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    final Uri uri = this.mUriProvider.getUri();
                    if (result.getResultCode() != AppCompatActivity.RESULT_OK
                            || result.getData() == null
                            || uri == null) {
                        complete();
                        return;
                    }
                    final Uri treeUri = result.getData().getData();
                    if (treeUri == null) {
                        Toast.makeText(this.mActivity, R.string.copy_failed, Toast.LENGTH_LONG).show();
                        complete();
                        return;
                    }
                    final boolean copied = FileUriActions.moveToTree(this.mActivity, uri, treeUri);
                    Toast.makeText(this.mActivity,
                            copied ? R.string.copy_done : R.string.copy_failed,
                            Toast.LENGTH_LONG).show();
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
