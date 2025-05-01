package rprop.app.lan;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LanUploadHandler {
    private static final String CACHE_DIR = "lan_upload";
    private static final String PARTS_DIR = "parts";
    private static final String AUTHORITY = "rp.file.fileprovider";
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    static final String MODE_SHARE = "share";
    static final String MODE_DOWNLOAD = "uploadMedia";
    static final String MODE_INSTALL = "install";

    static long getReceivedBytes(@NonNull final Context context,
                                 @NonNull final String batchId,
                                 @Nullable final String rawFileName) throws IOException {
        final File part = partialFile(context, batchId, rawFileName);
        return part.exists() ? part.length() : 0L;
    }

    /**
     * Appends a chunk at {@code offset}. Only accepts {@code offset == currentLength}
     * (append-only resume). When the partial file reaches {@code totalSize}, finalizes it.
     */
    @NonNull
    static ChunkWriteResult writeChunk(@NonNull final Context context,
                                       @NonNull final String batchId,
                                       @Nullable final String rawFileName,
                                       final long totalSize,
                                       final long offset,
                                       @NonNull final InputStream body,
                                       final long chunkLength) throws IOException {
        if (totalSize < 0 || offset < 0 || chunkLength < 0) {
            throw new IOException("invalid chunk parameters");
        }
        if (offset > totalSize || chunkLength > totalSize - offset) {
            throw new IOException("chunk exceeds file size");
        }
        String fileName = sanitizeFileName(rawFileName);
        if (fileName == null) {
            fileName = "upload.bin";
        }
        final File part = partialFile(context, batchId, fileName);
        final File parent = part.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("unable to create upload parts dir");
        }
        final long current = part.exists() ? part.length() : 0L;
        if (offset != current) {
            return ChunkWriteResult.offsetMismatch(current, totalSize);
        }
        if (chunkLength > 0) {
            try (final RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                raf.seek(offset);
                copyExact(body, raf, chunkLength);
            } catch (final IOException e) {
                // Keep partial progress for resume; do not delete.
                throw e;
            }
        }
        final long received = part.length();
        if (received < totalSize) {
            return ChunkWriteResult.progress(received, totalSize);
        }
        if (received > totalSize) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("upload larger than declared size");
        }
        final File finalDir = new File(cacheRoot(context), Long.toString(totalSize));
        if (!finalDir.exists() && !finalDir.mkdirs()) {
            throw new IOException("unable to create upload cache");
        }
        final File dest = new File(finalDir, fileName);
        if (dest.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
        if (!part.renameTo(dest)) {
            copyFile(part, dest);
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        return ChunkWriteResult.completed(received, totalSize,
                new SavedFile(dest, fileName, guessMimeType(fileName)));
    }

    @NonNull
    static String handleSaved(@NonNull final Context context,
                              @NonNull String mode,
                              @NonNull final List<SavedFile> uploads) throws IOException {
        if ("download".equals(mode)) {
            mode = MODE_DOWNLOAD;
        }
        if (uploads.isEmpty()) {
            throw new IOException("no file selected");
        }
        switch (mode) {
            case MODE_SHARE:
                return shareToApps(context, uploads);
            case MODE_DOWNLOAD:
                return saveToDownload(context, uploads);
            case MODE_INSTALL:
                return installApk(context, uploads);
            default:
                throw new IOException("unknown upload mode");
        }
    }

    @NonNull
    private static String shareToApps(@NonNull final Context context,
                                      @NonNull final List<SavedFile> uploads) {
        final ArrayList<Uri> uris = new ArrayList<>(uploads.size());
        for (final SavedFile upload : uploads) {
            uris.add(FileProvider.getUriForFile(context, AUTHORITY, upload.file));
        }
        final Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType(uploads.get(0).mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("*/*");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(buildClipData(uris));
        final Intent chooser = Intent.createChooser(intent, null);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityOnMain(context, chooser);
        return "Shared " + uploads.size() + " file(s) to apps";
    }

    @NonNull
    private static String saveToDownload(@NonNull final Context context,
                                         @NonNull final List<SavedFile> uploads)
            throws IOException {
        final ContentResolver resolver = context.getContentResolver();
        int saved = 0;
        for (final SavedFile upload : uploads) {
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, upload.fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, upload.mimeType);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Downloads.IS_PENDING, 1);
            }
            final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("unable to create download entry: " + upload.fileName);
            }
            try (final InputStream in = new FileInputStream(upload.file);
                 final OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    resolver.delete(uri, null, null);
                    throw new IOException("unable to write download: " + upload.fileName);
                }
                copyStream(in, out);
            } catch (final IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
            saved++;
        }
        return "Saved " + saved + " file(s) to Download";
    }

    @NonNull
    private static String installApk(@NonNull final Context context,
                                     @NonNull final List<SavedFile> uploads) throws IOException {
        SavedFile apk = null;
        for (final SavedFile upload : uploads) {
            if (upload.fileName.toLowerCase(Locale.US).endsWith(".apk")) {
                apk = upload;
                break;
            }
        }
        if (apk == null) {
            throw new IOException("please upload an .apk file");
        }
        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            final Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityOnMain(context, settings);
            throw new IOException("allow install unknown apps, then upload again");
        }
        final Uri uri = FileProvider.getUriForFile(context, AUTHORITY, apk.file);
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(context.getContentResolver(), apk.fileName, uri));
        startActivityOnMain(context, intent);
        return "Installing " + apk.fileName;
    }

    private static void startActivityOnMain(@NonNull final Context context,
                                            @NonNull final Intent intent) {
        MAIN_HANDLER.post(() -> context.startActivity(intent));
    }

    @NonNull
    private static ClipData buildClipData(@NonNull final List<Uri> uris) {
        final ClipData clipData = ClipData.newRawUri("upload", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clipData.addItem(new ClipData.Item(uris.get(i)));
        }
        return clipData;
    }

    @NonNull
    private static File cacheRoot(@NonNull final Context context) {
        return new File(context.getCacheDir(), CACHE_DIR);
    }

    @NonNull
    private static File partialFile(@NonNull final Context context,
                                    @NonNull final String batchId,
                                    @Nullable final String rawFileName) throws IOException {
        String fileName = sanitizeFileName(rawFileName);
        if (fileName == null) {
            fileName = "upload.bin";
        }
        final String safeBatch = sanitizeBatchId(batchId);
        final File dir = new File(cacheRoot(context), PARTS_DIR + '/' + safeBatch);
        return new File(dir, fileName + ".part");
    }

    @NonNull
    private static String sanitizeBatchId(@NonNull final String batchId) throws IOException {
        final StringBuilder builder = new StringBuilder(batchId.length());
        for (int i = 0; i < batchId.length(); i++) {
            final char c = batchId.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_') {
                builder.append(c);
            }
        }
        if (builder.length() == 0) {
            throw new IOException("invalid upload batch");
        }
        return builder.toString();
    }

    private static void copyExact(@NonNull final InputStream in, @NonNull final RandomAccessFile out,
                                  final long length) throws IOException {
        final byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            final int toRead = (int) Math.min(buffer.length, remaining);
            final int read = in.read(buffer, 0, toRead);
            if (read < 0) {
                throw new IOException("unexpected end of upload stream");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void copyFile(@NonNull final File source, @NonNull final File dest)
            throws IOException {
        try (final FileInputStream in = new FileInputStream(source);
             final FileOutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(@NonNull final FileInputStream in,
                                   @NonNull final FileOutputStream out) throws IOException {
        final FileChannel inChannel = in.getChannel();
        final FileChannel outChannel = out.getChannel();
        long position = 0L;
        final long size = inChannel.size();
        while (position < size) {
            final long transferred = inChannel.transferTo(position, size - position, outChannel);
            if (transferred <= 0L) {
                throw new IOException("channel transfer stalled");
            }
            position += transferred;
        }
    }

    private static void copyStream(@NonNull final InputStream in, @NonNull final OutputStream out)
            throws IOException {
        final byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    @Nullable
    private static String sanitizeFileName(@Nullable final String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim().replace('\\', '/');
        final int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace('/', '_').replace('\0', '_').trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name;
    }

    @NonNull
    private static String guessMimeType(@NonNull final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            final String extension = fileName.substring(dot + 1).toLowerCase(Locale.US);
            final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    static final class ChunkWriteResult {
        final long received;
        final long totalSize;
        final boolean offsetMismatch;
        final boolean fileComplete;
        @Nullable
        final SavedFile savedFile;

        private ChunkWriteResult(final long received, final long totalSize,
                                 final boolean offsetMismatch, final boolean fileComplete,
                                 @Nullable final SavedFile savedFile) {
            this.received = received;
            this.totalSize = totalSize;
            this.offsetMismatch = offsetMismatch;
            this.fileComplete = fileComplete;
            this.savedFile = savedFile;
        }

        @NonNull
        static ChunkWriteResult progress(final long received, final long totalSize) {
            return new ChunkWriteResult(received, totalSize, false, false, null);
        }

        @NonNull
        static ChunkWriteResult offsetMismatch(final long received, final long totalSize) {
            return new ChunkWriteResult(received, totalSize, true, false, null);
        }

        @NonNull
        static ChunkWriteResult completed(final long received, final long totalSize,
                                          @NonNull final SavedFile savedFile) {
            return new ChunkWriteResult(received, totalSize, false, true, savedFile);
        }
    }

    static final class SavedFile {
        @NonNull
        final File file;
        @NonNull
        final String fileName;
        @NonNull
        final String mimeType;

        SavedFile(@NonNull final File file, @NonNull final String fileName,
                  @NonNull final String mimeType) {
            this.file = file;
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }
}
