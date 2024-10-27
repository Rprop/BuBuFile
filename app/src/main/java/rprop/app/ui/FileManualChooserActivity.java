package rprop.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import rp.file.R;
import rprop.app.txt.TextViewActivity;
import rprop.app.video.play.VideoPlayActivity;

public final class FileManualChooserActivity extends AppCompatActivity {
    private static final int OPTION_TEXT = 0;
    private static final int OPTION_VIDEO = 1;
    private static final int OPTION_SHARE = 2;
    private static final int OPTION_MOVE = 3;

    @Nullable
    private Uri mUri;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOpenModeDialog(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        showOpenModeDialog(intent);
    }

    private void showOpenModeDialog(final Intent intent) {
        final Uri uri = FileOpenIntentHelper.extractUri(intent);
        if (uri == null) {
            finish();
            return;
        }
        this.mUri = uri;
        final TextView titleView = (TextView) getLayoutInflater()
                .inflate(R.layout.dialog_file_path_title, null);
        titleView.setText(FileUriActions.resolvePathLabel(this, uri));
        new MaterialAlertDialogBuilder(this)
                .setCustomTitle(titleView)
                .setItems(new CharSequence[]{
                        getString(R.string.open_as_text),
                        getString(R.string.open_as_video),
                        getString(R.string.share_to),
                        getString(R.string.copy_to)
                }, (dialog, which) -> handleOption(which))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void handleOption(final int which) {
        final Uri uri = this.mUri;
        if (uri == null) {
            finish();
            return;
        }
        switch (which) {
            case OPTION_TEXT:
                TextViewActivity.call(this, uri);
                break;
            case OPTION_VIDEO:
                VideoPlayActivity.call(this, uri);
                break;
            case OPTION_SHARE:
                FileUriActions.share(this, uri);
                return;
            case OPTION_MOVE:
                startMoveTo();
                return;
            default:
                break;
        }
        finish();
    }

    private final ActivityResultLauncher<Intent> mLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> {
                final Uri uri = this.mUri;
                if (result.getResultCode() != RESULT_OK || result.getData() == null || uri == null) {
                    finish();
                    return;
                }
                final Uri treeUri = result.getData().getData();
                if (treeUri == null) {
                    Toast.makeText(this, R.string.copy_failed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                final boolean moved = FileUriActions.moveToTree(this, uri, treeUri);
                Toast.makeText(this, moved ? R.string.copy_done : R.string.copy_failed, Toast.LENGTH_LONG).show();
                finish();
            });

    private void startMoveTo() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        this.mLauncher.launch(intent);
    }
}
