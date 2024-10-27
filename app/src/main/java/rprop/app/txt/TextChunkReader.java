package rprop.app.txt;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;

public final class TextChunkReader implements AutoCloseable {
    public static final int CHUNK_SIZE = 32 * 1024;

    private final PushbackInputStream mInputStream;
    private final boolean mChunked;
    private boolean mExhausted;

    private TextChunkReader(@NonNull final InputStream inputStream, final boolean chunked) {
        this.mInputStream = new PushbackInputStream(inputStream, CHUNK_SIZE);
        this.mChunked = chunked;
        this.mExhausted = false;
    }

    public static TextChunkReader open(@NonNull final ContentResolver resolver, @NonNull final Uri uri)
            throws IOException {
        final InputStream inputStream = resolver.openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("Unable to open " + uri);
        }
        final long size = querySize(resolver, uri);
        final boolean chunked = size < 0 || size > CHUNK_SIZE;
        return new TextChunkReader(inputStream, chunked);
    }

    public boolean isChunked() {
        return this.mChunked;
    }

    public boolean hasMore() {
        return !this.mExhausted;
    }

    @NonNull
    public String readNextChunk() throws IOException {
        if (this.mExhausted) {
            return "";
        }
        if (!this.mChunked) {
            return readAllRemaining();
        }
        return readChunk();
    }

    @NonNull
    private String readAllRemaining() throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = this.mInputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        this.mExhausted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return outputStream.toString(StandardCharsets.UTF_8);
        } else {
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private String readChunk() throws IOException {
        final byte[] buffer = new byte[CHUNK_SIZE];
        final int read = readUpTo(buffer, CHUNK_SIZE);
        if (read <= 0) {
            this.mExhausted = true;
            return "";
        }

        int length = read;
        if (read == CHUNK_SIZE) {
            final int next = this.mInputStream.read();
            if (next == -1) {
                this.mExhausted = true;
            } else {
                this.mInputStream.unread(next);
                final int newlineIndex = lastNewlineIndex(buffer, read);
                if (newlineIndex >= 0) {
                    length = newlineIndex + 1;
                    this.mInputStream.unread(buffer, length, read - length);
                } else {
                    length = utf8SafeLength(buffer, read);
                    if (length < read) {
                        this.mInputStream.unread(buffer, length, read - length);
                    }
                }
            }
        } else {
            this.mExhausted = true;
            length = utf8SafeLength(buffer, read);
            if (length < read) {
                throw new IOException("Invalid UTF-8 sequence near end of file");
            }
        }
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }

    private int readUpTo(@NonNull final byte[] buffer, final int max) throws IOException {
        int total = 0;
        while (total < max) {
            final int read = this.mInputStream.read(buffer, total, max - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static int lastNewlineIndex(@NonNull final byte[] buffer, final int length) {
        for (int i = length - 1; i >= 0; i--) {
            if (buffer[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int utf8SafeLength(@NonNull final byte[] buffer, final int length) {
        int safeLength = length;
        while (safeLength > 0 && (buffer[safeLength - 1] & 0xC0) == 0x80) {
            safeLength--;
        }
        if (safeLength == 0) {
            return length;
        }
        final int leadingByte = buffer[safeLength - 1] & 0xFF;
        final int expectedLength;
        if ((leadingByte & 0x80) == 0) {
            expectedLength = 1;
        } else if ((leadingByte & 0xE0) == 0xC0) {
            expectedLength = 2;
        } else if ((leadingByte & 0xF0) == 0xE0) {
            expectedLength = 3;
        } else if ((leadingByte & 0xF8) == 0xF0) {
            expectedLength = 4;
        } else {
            return safeLength;
        }
        final int available = length - (safeLength - 1);
        if (available >= expectedLength) {
            return length;
        }
        return safeLength - 1;
    }

    private static long querySize(@NonNull final ContentResolver resolver, @NonNull final Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (final Exception ignored) {
        }
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                final long size = pfd.getStatSize();
                if (size >= 0) {
                    return size;
                }
            }
        } catch (final Exception ignored) {
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        this.mInputStream.close();
    }
}
