package rprop.app.lan;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class LanClipboardHelper {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final long TIMEOUT_MS = 5000L;

    @NonNull
    static String getText(@NonNull final Context context) throws Exception {
        final AtomicReference<String> result = new AtomicReference<>("");
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN_HANDLER.post(() -> {
            try {
                final ClipboardManager clipboard = clipboard(context);
                if (clipboard == null) {
                    throw new IllegalStateException("clipboard unavailable");
                }
                final ClipData clip = clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() <= 0) {
                    result.set("");
                    return;
                }
                final CharSequence text = clip.getItemAt(0).coerceToText(context);
                result.set(text == null ? "" : text.toString());
            } catch (final Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("clipboard read timeout");
        }
        final Exception failure = error.get();
        if (failure != null) {
            throw failure;
        }
        return result.get();
    }

    static void setText(@NonNull final Context context, @NonNull final String text)
            throws Exception {
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN_HANDLER.post(() -> {
            try {
                final ClipboardManager clipboard = clipboard(context);
                if (clipboard == null) {
                    throw new IllegalStateException("clipboard unavailable");
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("BuBuFile", text));
            } catch (final Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("clipboard write timeout");
        }
        final Exception failure = error.get();
        if (failure != null) {
            throw failure;
        }
    }

    @Nullable
    private static ClipboardManager clipboard(@NonNull final Context context) {
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }
}
