package rprop.app.zip;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

import rp.file.R;
import rp.file.databinding.ActivityZipBrowseBinding;
import rprop.app.ui.FileUriActions;
import rprop.app.ui.MaterialProgressHelper;
import rprop.app.video.utils.DisplayUtils;

public final class ZipBrowseActivity extends AppCompatActivity {
    private static final String TAG = "zip-browse";
    private static final String AUTHORITY = "rp.file.fileprovider";

    private ActivityZipBrowseBinding mBinding;
    private ZipBrowseAdapter mAdapter;
    @Nullable
    private Uri mUri;
    @Nullable
    private ZipArchiveSession mSession;
    @NonNull
    private String mCurrentDir = "";
    private int mLoadGeneration;
    private boolean mExtracting;

    public static void call(@NonNull final Activity activity, @NonNull final Uri zipUri) {
        final Intent intent = new Intent(activity, ZipBrowseActivity.class);
        intent.setData(zipUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);
        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_zip_browse);
        this.mBinding.toolbar.setNavigationOnClickListener(v -> onBackPressed());
        this.mAdapter = new ZipBrowseAdapter(this::onBrowseItemClick);
        this.mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        this.mBinding.recycler.setAdapter(this.mAdapter);
        openFromIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        this.mLoadGeneration++;
        closeSession();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (!this.mCurrentDir.isEmpty()) {
            this.mCurrentDir = ZipArchiveSession.parentDir(this.mCurrentDir);
            refreshList();
            return;
        }
        super.onBackPressed();
    }

    private void openFromIntent(@NonNull final Intent intent) {
        final Uri uri = intent.getData();
        if (uri == null) {
            finish();
            return;
        }
        this.mUri = uri;
        this.mCurrentDir = "";
        closeSession();
        final int generation = ++this.mLoadGeneration;
        final AlertDialog progress = MaterialProgressHelper.show(this, getString(R.string.zip_opening));
        final Thread thread = new Thread(() -> {
            ZipArchiveSession session = null;
            try {
                session = ZipArchiveSession.open(this, uri);
                final ZipArchiveSession opened = session;
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        opened.close();
                        return;
                    }
                    this.mSession = opened;
                    refreshList();
                });
            } catch (final Exception e) {
                Log.w(TAG, e);
                if (session != null) {
                    session.close();
                }
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        return;
                    }
                    Toast.makeText(this, R.string.zip_open_failed, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }, "zip-open");
        thread.start();
    }

    private void refreshList() {
        final ZipArchiveSession session = this.mSession;
        if (session == null) {
            return;
        }
        final Uri uri = this.mUri;
        final String title = this.mCurrentDir.isEmpty()
                ? (uri != null ? FileUriActions.resolvePathLabel(this, uri) : getString(R.string.open_as_zip))
                : this.mCurrentDir;
        this.mBinding.toolbar.setTitle(title);
        this.mAdapter.submit(session.listChildren(this.mCurrentDir));
    }

    private void onBrowseItemClick(@NonNull final ZipArchiveSession.BrowseItem item) {
        if (item.directory) {
            this.mCurrentDir = item.entryName;
            refreshList();
            return;
        }
        extractAndOpen(item);
    }

    private void extractAndOpen(@NonNull final ZipArchiveSession.BrowseItem item) {
        final ZipArchiveSession session = this.mSession;
        if (item.encrypted && (session == null || !session.hasPassword())) {
            promptPasswordAndExtract(item);
            return;
        }
        runExtract(item, item.encrypted);
    }

    private void promptPasswordAndExtract(@NonNull final ZipArchiveSession.BrowseItem item) {
        if (this.mExtracting) {
            return;
        }
        final View content = LayoutInflater.from(this).inflate(R.layout.dialog_zip_password, null);
        final TextInputEditText passwordInput = content.findViewById(R.id.zip_password);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.zip_password_title)
                .setView(content)
                .setPositiveButton(R.string.zip_password_ok, (dialog, which) -> {
                    final CharSequence text = passwordInput.getText();
                    if (TextUtils.isEmpty(text)) {
                        Toast.makeText(this, R.string.zip_password_wrong, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final ZipArchiveSession session = this.mSession;
                    if (session != null) {
                        session.setPassword(text.toString().toCharArray());
                    }
                    runExtract(item, true);
                })
                .setNegativeButton(R.string.zip_password_cancel, null)
                .show();
    }

    private void runExtract(@NonNull final ZipArchiveSession.BrowseItem item,
                            final boolean fromPasswordPrompt) {
        final ZipArchiveSession session = this.mSession;
        if (session == null || this.mExtracting) {
            return;
        }
        this.mExtracting = true;
        final AlertDialog progress = MaterialProgressHelper.show(this,
                getString(R.string.zip_extracting));
        final int generation = this.mLoadGeneration;
        final Thread thread = new Thread(() -> {
            try {
                final File file = session.extractToCache(this, item);
                runOnUiThread(() -> {
                    progress.dismiss();
                    this.mExtracting = false;
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        return;
                    }
                    openExtractedFile(file, item.displayName);
                });
            } catch (final ZipWrongPasswordException e) {
                Log.w(TAG, e);
                session.setPassword(null);
                runOnUiThread(() -> {
                    progress.dismiss();
                    this.mExtracting = false;
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        return;
                    }
                    Toast.makeText(this, R.string.zip_password_wrong, Toast.LENGTH_SHORT).show();
                    if (fromPasswordPrompt || item.encrypted) {
                        promptPasswordAndExtract(item);
                    }
                });
            } catch (final Exception e) {
                Log.w(TAG, e);
                runOnUiThread(() -> {
                    progress.dismiss();
                    this.mExtracting = false;
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        return;
                    }
                    Toast.makeText(this, R.string.zip_extract_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }, "zip-extract");
        thread.start();
    }

    private void openExtractedFile(@NonNull final File file, @NonNull final String displayName) {
        final Uri uri = FileProvider.getUriForFile(this, AUTHORITY, file);
        final String mimeType = ZipArchiveSession.guessMimeType(displayName);
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), displayName, uri));
        try {
            startActivity(Intent.createChooser(intent, displayName));
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.zip_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeSession() {
        final ZipArchiveSession session = this.mSession;
        this.mSession = null;
        if (session != null) {
            session.close();
        }
    }
}
