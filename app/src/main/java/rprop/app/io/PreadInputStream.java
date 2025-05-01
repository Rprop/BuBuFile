package rprop.app.io;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

/**
 * Positioned reads against a shared {@link FileDescriptor}. Does not close the fd
 * and does not change its file offset, so concurrent readers are safe.
 */
public final class PreadInputStream extends InputStream {
    @NonNull
    private final FileDescriptor mFd;
    private long mPosition;
    private long mRemaining;
    private boolean mClosed;

    public PreadInputStream(@NonNull final FileDescriptor fd, final long position,
                            final long remaining) {
        this.mFd = fd;
        this.mPosition = position;
        this.mRemaining = remaining;
    }

    @NonNull
    public static PreadInputStream fromPfd(@NonNull final ParcelFileDescriptor pfd,
                                           final long position) throws IOException {
        final long size = pfd.getStatSize();
        if (size >= 0L) {
            if (position < 0L || position > size) {
                throw new IOException("invalid read position");
            }
            return new PreadInputStream(pfd.getFileDescriptor(), position, size - position);
        }
        if (position < 0L) {
            throw new IOException("invalid read position");
        }
        return new PreadInputStream(pfd.getFileDescriptor(), position, Long.MAX_VALUE);
    }

    @Override
    public int read() throws IOException {
        final byte[] one = new byte[1];
        final int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(@NonNull final byte[] buffer, final int offset, final int count)
            throws IOException {
        if (this.mClosed) {
            throw new IOException("stream closed");
        }
        if (offset < 0 || count < 0 || offset + count > buffer.length) {
            throw new IndexOutOfBoundsException();
        }
        if (count == 0) {
            return 0;
        }
        if (this.mRemaining <= 0L) {
            return -1;
        }
        final int toRead = (int) Math.min(count, this.mRemaining);
        final int read;
        try {
            read = Os.pread(this.mFd, buffer, offset, toRead, this.mPosition);
        } catch (final ErrnoException e) {
            throw new IOException(e);
        }
        if (read <= 0) {
            return -1;
        }
        this.mPosition += read;
        this.mRemaining -= read;
        return read;
    }

    @Override
    public void close() {
        this.mClosed = true;
    }
}
