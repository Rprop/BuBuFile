package rprop.app.txt;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import java.io.IOException;

import rp.file.R;
import rp.file.databinding.ActivityTextViewBinding;
import rprop.app.ui.FileUriActions;
import rprop.app.video.utils.DisplayUtils;

public final class TextViewActivity extends AppCompatActivity implements TextViewerListener {
    private static final String TAG = "text-viewer";
    private ActivityTextViewBinding mBinding;
    @Nullable
    private Uri mUri;
    @Nullable
    private TextChunkReader mReader;
    private volatile boolean mLoadingChunk;
    private int mLoadGeneration;

    public static void call(final Activity activity, final Uri textUri) {
        final Intent intent = new Intent(activity, TextViewActivity.class);
        intent.setData(textUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent, null);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayUtils.init(this);

        this.mBinding = DataBindingUtil.setContentView(this, R.layout.activity_text_view);
        this.mBinding.textViewer.setOnTextViewerListener(this);
        resolveIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        this.mLoadGeneration++;
        closeReader();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        resolveIntent(intent);
    }

    private void resolveIntent(final Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final String type = intent.getType();
            if (type != null && type.startsWith("text/")) {
                final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    Log.d(TAG, type + ' ' + uri);
                    openText(uri);
                }
            }
            return;
        }
        final Uri uri = intent.getData();
        if (uri != null) {
            Log.d(TAG, "view " + uri);
            openText(uri);
        }
    }

    private void openText(final Uri uri) {
        this.mUri = uri;
        closeReader();
        this.mBinding.textViewer.setLoadMoreEnabled(false);
        this.mLoadingChunk = true;
        final int generation = ++this.mLoadGeneration;
        final Thread thread = new Thread(() -> {
            try {
                final TextChunkReader reader = TextChunkReader.open(getContentResolver(), uri);
                final String firstChunk = reader.readNextChunk();
                runOnUiThread(() -> {
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        try {
                            reader.close();
                        } catch (final IOException ignored) {
                        }
                        return;
                    }
                    applyFirstChunk(reader, firstChunk);
                });
            } catch (final SecurityException | IOException e) {
                Log.w(TAG, e);
                runOnUiThread(() -> handleReadFailure(generation));
            }
        }, TAG);
        thread.setDaemon(true);
        thread.start();
    }

    private void handleReadFailure(final int generation) {
        if (generation != this.mLoadGeneration || isFinishing()) {
            return;
        }
        this.mLoadingChunk = false;
        Toast.makeText(this, R.string.text_read_failed, Toast.LENGTH_LONG).show();
    }

    private void applyFirstChunk(@Nullable final TextChunkReader reader, final String firstChunk) {
        this.mReader = reader;
        this.mBinding.textViewer.setText(firstChunk);
        this.mBinding.textViewer.setLoadMoreEnabled(reader != null && reader.hasMore());
        this.mBinding.textViewer.requestLoadMoreIfNeeded();
        this.mLoadingChunk = false;
    }

    @Override
    public void onLoadMore() {
        final TextChunkReader reader = this.mReader;
        if (reader == null || !reader.hasMore() || this.mLoadingChunk) {
            return;
        }
        this.mLoadingChunk = true;
        final int generation = this.mLoadGeneration;
        final Thread thread = new Thread(() -> {
            try {
                final String chunk = reader.readNextChunk();
                runOnUiThread(() -> {
                    if (generation != this.mLoadGeneration || isFinishing()) {
                        return;
                    }
                    applyNextChunk(chunk);
                });
            } catch (final SecurityException | IOException e) {
                Log.w(TAG, e);
                runOnUiThread(() -> handleReadFailure(generation));
            }
        }, TAG + "-more");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyNextChunk(final String chunk) {
        this.mBinding.textViewer.appendText(chunk);
        final TextChunkReader reader = this.mReader;
        this.mBinding.textViewer.setLoadMoreEnabled(reader != null && reader.hasMore());
        this.mBinding.textViewer.requestLoadMoreIfNeeded();
        this.mLoadingChunk = false;
    }

    private void closeReader() {
        if (this.mReader != null) {
            try {
                this.mReader.close();
            } catch (final IOException e) {
                Log.w(TAG, e);
            }
            this.mReader = null;
        }
    }

    @Override
    public void onBack() {
        finish();
    }

    @Override
    public void onShareRequested() {
        final Uri uri = this.mUri;
        if (uri != null) {
            FileUriActions.share(this, uri);
        }
    }

    @Override
    public boolean hasMoreText() {
        final TextChunkReader reader = this.mReader;
        return reader != null && reader.hasMore();
    }
}
