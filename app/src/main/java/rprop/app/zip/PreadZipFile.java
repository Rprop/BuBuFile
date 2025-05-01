package rprop.app.zip;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Read-only ZIP backed by {@link Os#pread}. Lists entries from the central directory
 * without decompressing payloads; extracts a single entry on demand.
 * Supports ZIP64, ZipCrypto and WinZip AES.
 */
final class PreadZipFile {
    private static final int SIG_EOCD = 0x06054b50;
    private static final int SIG_ZIP64_EOCD = 0x06064b50;
    private static final int SIG_ZIP64_LOCATOR = 0x07064b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_LOCAL = 0x04034b50;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;
    private static final int METHOD_AES = 99;
    private static final int GP_ENCRYPTED = 1;
    private static final int GP_DATA_DESCRIPTOR = 1 << 3;
    private static final int GP_UTF8 = 1 << 11;
    private static final int EOCD_MIN_SIZE = 22;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_EOCD_MIN_SIZE = 56;
    private static final int MAX_COMMENT = 0xffff;
    private static final int MAX_CENTRAL_DIRECTORY = 512 * 1024 * 1024;
    private static final int LOCAL_HEADER_FIXED = 30;
    private static final int CENTRAL_HEADER_FIXED = 46;
    private static final long ZIP64_MAGIC32 = 0xffffffffL;
    private static final int ZIP64_MAGIC16 = 0xffff;
    private static final int EXTRA_ZIP64 = 0x0001;
    private static final int EXTRA_AES = 0x9901;
    private static final int ZIP_CRYPTO_HEADER = 12;

    @NonNull
    private final FileDescriptor mFd;
    private final long mFileSize;
    @NonNull
    private final List<Entry> mEntries;

    private PreadZipFile(@NonNull final FileDescriptor fd, final long fileSize,
                         @NonNull final List<Entry> entries) {
        this.mFd = fd;
        this.mFileSize = fileSize;
        this.mEntries = entries;
    }

    @NonNull
    static PreadZipFile open(@NonNull final ParcelFileDescriptor pfd) throws IOException {
        final long fileSize = pfd.getStatSize();
        if (fileSize < EOCD_MIN_SIZE) {
            throw new IOException("zip too small");
        }
        final FileDescriptor fd = pfd.getFileDescriptor();
        final Eocd eocd = findEocd(fd, fileSize);
        if (eocd.entryCount == 0L) {
            return new PreadZipFile(fd, fileSize, Collections.emptyList());
        }
        if (eocd.directorySize < 0L
                || eocd.directorySize > MAX_CENTRAL_DIRECTORY
                || eocd.directoryOffset < 0L
                || eocd.directoryOffset + eocd.directorySize > fileSize) {
            throw new IOException("invalid central directory bounds");
        }
        if (eocd.directorySize > Integer.MAX_VALUE) {
            throw new IOException("central directory too large");
        }
        final byte[] directory = new byte[(int) eocd.directorySize];
        readFully(fd, eocd.directoryOffset, directory, 0, directory.length);
        final List<Entry> entries = parseCentralDirectory(directory);
        return new PreadZipFile(fd, fileSize, entries);
    }

    @NonNull
    List<Entry> getEntries() {
        return this.mEntries;
    }

    @Nullable
    Entry findByRawName(@NonNull final String rawName) {
        for (final Entry entry : this.mEntries) {
            if (entry.rawName.equals(rawName)) {
                return entry;
            }
        }
        return null;
    }

    void extract(@NonNull final Entry entry, @NonNull final OutputStream out,
                 @Nullable final char[] password) throws IOException {
        if (entry.directory) {
            throw new IOException("cannot extract directory");
        }
        if (entry.encrypted && (password == null || password.length == 0)) {
            throw new ZipWrongPasswordException();
        }
        if (entry.localHeaderOffset < 0L
                || entry.localHeaderOffset + LOCAL_HEADER_FIXED > this.mFileSize) {
            throw new IOException("invalid local header offset");
        }

        final byte[] localFixed = new byte[LOCAL_HEADER_FIXED];
        readFully(this.mFd, entry.localHeaderOffset, localFixed, 0, localFixed.length);
        if (readInt(localFixed, 0) != SIG_LOCAL) {
            throw new IOException("bad local header signature");
        }
        final int localFlags = readUnsignedShort(localFixed, 6);
        final int localMethod = readUnsignedShort(localFixed, 8);
        final int localDosTime = readUnsignedShort(localFixed, 10);
        final int nameLen = readUnsignedShort(localFixed, 26);
        final int extraLen = readUnsignedShort(localFixed, 28);
        long dataOffset = entry.localHeaderOffset + LOCAL_HEADER_FIXED + nameLen + extraLen;
        if (dataOffset < 0L || dataOffset > this.mFileSize) {
            throw new IOException("invalid local data offset");
        }

        int method = localMethod;
        int aesKeyBits = entry.aesKeyBits;
        int compressionMethod = entry.compressionMethod;
        if (extraLen > 0) {
            final byte[] extra = new byte[extraLen];
            readFully(this.mFd, entry.localHeaderOffset + LOCAL_HEADER_FIXED + nameLen,
                    extra, 0, extraLen);
            final AesExtra aesExtra = parseAesExtra(extra);
            if (aesExtra != null) {
                aesKeyBits = aesExtra.keyBits;
                compressionMethod = aesExtra.compressionMethod;
                method = METHOD_AES;
            }
        }
        if (method != METHOD_AES) {
            compressionMethod = method;
        }

        long compressedSize = entry.compressedSize;
        if (compressedSize < 0L || dataOffset + compressedSize > this.mFileSize) {
            throw new IOException("invalid compressed size");
        }

        final boolean encrypted = (localFlags & GP_ENCRYPTED) != 0 || entry.encrypted;
        byte[] payload = readPayload(dataOffset, compressedSize);

        if (method == METHOD_AES) {
            if (!encrypted) {
                throw new IOException("AES entry missing encryption flag");
            }
            if (password == null) {
                throw new ZipWrongPasswordException();
            }
            payload = decryptAes(payload, aesKeyBits, password);
            writeDecompressed(payload, compressionMethod, entry.size, entry.crc, out);
            return;
        }

        if (encrypted) {
            if (password == null) {
                throw new ZipWrongPasswordException();
            }
            final int checkByte = ((localFlags & GP_DATA_DESCRIPTOR) != 0)
                    ? ((localDosTime >>> 8) & 0xff)
                    : (int) ((entry.crc >>> 24) & 0xff);
            payload = decryptZipCrypto(payload, password, checkByte);
        }

        writeDecompressed(payload, compressionMethod, entry.size, entry.crc, out);
    }

    void extract(@NonNull final Entry entry, @NonNull final FileOutputStream out,
                 @Nullable final char[] password) throws IOException {
        extract(entry, (OutputStream) out, password);
        out.getFD().sync();
    }

    @NonNull
    private byte[] readPayload(final long dataOffset, final long compressedSize)
            throws IOException {
        if (compressedSize > Integer.MAX_VALUE) {
            throw new IOException("compressed entry too large to buffer");
        }
        final byte[] payload = new byte[(int) compressedSize];
        if (compressedSize > 0L) {
            readFully(this.mFd, dataOffset, payload, 0, payload.length);
        }
        return payload;
    }

    @NonNull
    private static byte[] decryptZipCrypto(@NonNull final byte[] payload,
                                           @NonNull final char[] password,
                                           final int checkByte) throws IOException {
        if (payload.length < ZIP_CRYPTO_HEADER) {
            throw new IOException("zip crypto payload truncated");
        }
        final ZipCrypto crypto = ZipCrypto.forPassword(password);
        final byte[] header = new byte[ZIP_CRYPTO_HEADER];
        System.arraycopy(payload, 0, header, 0, ZIP_CRYPTO_HEADER);
        crypto.decryptHeader(header, checkByte);
        final byte[] plain = new byte[payload.length - ZIP_CRYPTO_HEADER];
        System.arraycopy(payload, ZIP_CRYPTO_HEADER, plain, 0, plain.length);
        crypto.decryptInPlace(plain, 0, plain.length);
        return plain;
    }

    @NonNull
    private static byte[] decryptAes(@NonNull final byte[] payload, final int keyBits,
                                     @NonNull final char[] password) throws IOException {
        final int saltLen = ZipAesDecryptor.saltLengthForBits(keyBits);
        if (payload.length < saltLen + 2 + ZipAesDecryptor.AUTH_CODE_LENGTH) {
            throw new IOException("AES payload truncated");
        }
        final byte[] salt = new byte[saltLen];
        System.arraycopy(payload, 0, salt, 0, saltLen);
        final byte[] verifier = new byte[2];
        System.arraycopy(payload, saltLen, verifier, 0, 2);
        final ZipAesDecryptor.Keys keys = ZipAesDecryptor.deriveKeys(keyBits, password, salt);
        ZipAesDecryptor.verifyPassword(keys, verifier);
        final byte[] cipherAndAuth = new byte[payload.length - saltLen - 2];
        System.arraycopy(payload, saltLen + 2, cipherAndAuth, 0, cipherAndAuth.length);
        return ZipAesDecryptor.decryptAndAuthenticate(keys, cipherAndAuth);
    }

    private static void writeDecompressed(@NonNull final byte[] compressed,
                                          final int method,
                                          final long expectedSize,
                                          final long expectedCrc,
                                          @NonNull final OutputStream out) throws IOException {
        switch (method) {
            case METHOD_STORED:
                writeStored(compressed, expectedSize, expectedCrc, out);
                break;
            case METHOD_DEFLATED:
                inflateBytes(compressed, expectedSize, expectedCrc, out);
                break;
            default:
                throw new IOException("unsupported zip method: " + method);
        }
    }

    private static void writeStored(@NonNull final byte[] data, final long expectedSize,
                                    final long expectedCrc, @NonNull final OutputStream out)
            throws IOException {
        if (expectedSize >= 0L && data.length != expectedSize) {
            throw new IOException("stored size mismatch");
        }
        out.write(data);
        final CRC32 crc32 = new CRC32();
        crc32.update(data);
        verifyCrcAndSize(crc32.getValue(), expectedCrc, data.length, expectedSize);
    }

    private static void inflateBytes(@NonNull final byte[] compressed, final long expectedSize,
                                     final long expectedCrc, @NonNull final OutputStream out)
            throws IOException {
        final Inflater inflater = new Inflater(true);
        final CRC32 crc32 = new CRC32();
        long uncompressed = 0L;
        final byte[] outBuf = new byte[64 * 1024];
        try {
            inflater.setInput(compressed);
            while (!inflater.finished()) {
                final int produced;
                try {
                    produced = inflater.inflate(outBuf);
                } catch (final DataFormatException e) {
                    throw new IOException("zip inflate failed", e);
                }
                if (produced > 0) {
                    out.write(outBuf, 0, produced);
                    crc32.update(outBuf, 0, produced);
                    uncompressed += produced;
                    if (expectedSize >= 0L && uncompressed > expectedSize) {
                        throw new IOException("uncompressed size overflow");
                    }
                } else if (inflater.needsInput()) {
                    break;
                }
            }
            if (!inflater.finished() && expectedSize >= 0L && uncompressed != expectedSize) {
                throw new IOException("zip inflate incomplete");
            }
        } finally {
            inflater.end();
        }
        verifyCrcAndSize(crc32.getValue(), expectedCrc, uncompressed, expectedSize);
    }

    private static void verifyCrcAndSize(final long actualCrc, final long expectedCrc,
                                         final long actualSize, final long expectedSize)
            throws IOException {
        if (expectedSize >= 0L && actualSize != expectedSize) {
            throw new IOException("size mismatch");
        }
        // WinZip AE-2 stores CRC as 0 in headers.
        if (expectedCrc > 0L
                && (actualCrc & 0xffffffffL) != (expectedCrc & 0xffffffffL)) {
            throw new IOException("crc mismatch");
        }
    }

    @NonNull
    private static Eocd findEocd(@NonNull final FileDescriptor fd, final long fileSize)
            throws IOException {
        final int maxProbe = (int) Math.min(fileSize, (long) EOCD_MIN_SIZE + MAX_COMMENT);
        final byte[] tail = new byte[maxProbe];
        final long tailOffset = fileSize - maxProbe;
        readFully(fd, tailOffset, tail, 0, maxProbe);
        for (int i = maxProbe - EOCD_MIN_SIZE; i >= 0; i--) {
            if (readInt(tail, i) != SIG_EOCD) {
                continue;
            }
            final int commentLen = readUnsignedShort(tail, i + 20);
            if (i + EOCD_MIN_SIZE + commentLen != maxProbe) {
                continue;
            }
            final int diskNumber = readUnsignedShort(tail, i + 4);
            final int startDisk = readUnsignedShort(tail, i + 6);
            long entriesOnDisk = readUnsignedShort(tail, i + 8);
            long totalEntries = readUnsignedShort(tail, i + 10);
            long directorySize = readUnsignedInt(tail, i + 12);
            long directoryOffset = readUnsignedInt(tail, i + 16);
            final long eocdOffset = tailOffset + i;
            final boolean needZip64 = diskNumber == ZIP64_MAGIC16
                    || startDisk == ZIP64_MAGIC16
                    || entriesOnDisk == ZIP64_MAGIC16
                    || totalEntries == ZIP64_MAGIC16
                    || directorySize == ZIP64_MAGIC32
                    || directoryOffset == ZIP64_MAGIC32;
            if (needZip64) {
                final Eocd zip64 = readZip64Eocd(fd, eocdOffset, fileSize);
                entriesOnDisk = zip64.entryCount;
                totalEntries = zip64.entryCount;
                directorySize = zip64.directorySize;
                directoryOffset = zip64.directoryOffset;
            } else if (diskNumber != 0 || startDisk != 0 || entriesOnDisk != totalEntries) {
                throw new IOException("multi-disk zip not supported");
            }
            return new Eocd(totalEntries, directorySize, directoryOffset);
        }
        throw new IOException("zip EOCD not found");
    }

    @NonNull
    private static Eocd readZip64Eocd(@NonNull final FileDescriptor fd, final long eocdOffset,
                                      final long fileSize) throws IOException {
        if (eocdOffset < ZIP64_LOCATOR_SIZE) {
            throw new IOException("zip64 locator missing");
        }
        final byte[] locator = new byte[ZIP64_LOCATOR_SIZE];
        readFully(fd, eocdOffset - ZIP64_LOCATOR_SIZE, locator, 0, locator.length);
        if (readInt(locator, 0) != SIG_ZIP64_LOCATOR) {
            throw new IOException("zip64 locator signature mismatch");
        }
        final long zip64Offset = readLong(locator, 8);
        if (zip64Offset < 0L || zip64Offset + ZIP64_EOCD_MIN_SIZE > fileSize) {
            throw new IOException("invalid zip64 eocd offset");
        }
        final byte[] header = new byte[ZIP64_EOCD_MIN_SIZE];
        readFully(fd, zip64Offset, header, 0, header.length);
        if (readInt(header, 0) != SIG_ZIP64_EOCD) {
            throw new IOException("zip64 eocd signature mismatch");
        }
        final long diskNumber = readUnsignedInt(header, 16);
        final long startDisk = readUnsignedInt(header, 20);
        final long entriesOnDisk = readLong(header, 24);
        final long totalEntries = readLong(header, 32);
        final long directorySize = readLong(header, 40);
        final long directoryOffset = readLong(header, 48);
        if (diskNumber != 0L || startDisk != 0L || entriesOnDisk != totalEntries) {
            throw new IOException("multi-disk zip64 not supported");
        }
        if (totalEntries < 0L || directorySize < 0L || directoryOffset < 0L) {
            throw new IOException("invalid zip64 eocd fields");
        }
        return new Eocd(totalEntries, directorySize, directoryOffset);
    }

    @NonNull
    private static List<Entry> parseCentralDirectory(@NonNull final byte[] directory)
            throws IOException {
        final List<Entry> entries = new ArrayList<>();
        int offset = 0;
        while (offset + CENTRAL_HEADER_FIXED <= directory.length) {
            if (readInt(directory, offset) != SIG_CENTRAL) {
                throw new IOException("bad central directory signature");
            }
            final int flags = readUnsignedShort(directory, offset + 8);
            int method = readUnsignedShort(directory, offset + 10);
            final int dosTime = readUnsignedShort(directory, offset + 12);
            final long crc = readUnsignedInt(directory, offset + 16);
            long compressedSize = readUnsignedInt(directory, offset + 20);
            long size = readUnsignedInt(directory, offset + 24);
            final int nameLen = readUnsignedShort(directory, offset + 28);
            final int extraLen = readUnsignedShort(directory, offset + 30);
            final int commentLen = readUnsignedShort(directory, offset + 32);
            int diskStart = readUnsignedShort(directory, offset + 34);
            long localHeaderOffset = readUnsignedInt(directory, offset + 42);
            final int nameOffset = offset + CENTRAL_HEADER_FIXED;
            final int extraOffset = nameOffset + nameLen;
            final int next = extraOffset + extraLen + commentLen;
            if (nameLen < 0 || next > directory.length) {
                throw new IOException("central directory entry truncated");
            }

            final boolean needZip64 = size == ZIP64_MAGIC32
                    || compressedSize == ZIP64_MAGIC32
                    || localHeaderOffset == ZIP64_MAGIC32
                    || diskStart == ZIP64_MAGIC16;
            int aesKeyBits = 0;
            int compressionMethod = method;
            if (extraLen > 0) {
                final byte[] extra = new byte[extraLen];
                System.arraycopy(directory, extraOffset, extra, 0, extraLen);
                if (needZip64) {
                    final Zip64Sizes zip64 = parseZip64Extra(extra, size == ZIP64_MAGIC32,
                            compressedSize == ZIP64_MAGIC32,
                            localHeaderOffset == ZIP64_MAGIC32,
                            diskStart == ZIP64_MAGIC16);
                    if (size == ZIP64_MAGIC32) {
                        size = zip64.size;
                    }
                    if (compressedSize == ZIP64_MAGIC32) {
                        compressedSize = zip64.compressedSize;
                    }
                    if (localHeaderOffset == ZIP64_MAGIC32) {
                        localHeaderOffset = zip64.localHeaderOffset;
                    }
                    if (diskStart == ZIP64_MAGIC16) {
                        diskStart = zip64.diskStart;
                    }
                }
                final AesExtra aesExtra = parseAesExtra(extra);
                if (aesExtra != null) {
                    aesKeyBits = aesExtra.keyBits;
                    compressionMethod = aesExtra.compressionMethod;
                    method = METHOD_AES;
                }
            } else if (needZip64) {
                throw new IOException("zip64 extra field missing");
            }
            if (diskStart != 0) {
                throw new IOException("multi-disk zip entry not supported");
            }

            final byte[] nameBytes = new byte[nameLen];
            System.arraycopy(directory, nameOffset, nameBytes, 0, nameLen);
            final String rawName = new String(nameBytes, charsetFor(flags));
            final boolean directoryEntry = rawName.endsWith("/") || rawName.endsWith("\\");
            final boolean encrypted = (flags & GP_ENCRYPTED) != 0 || method == METHOD_AES;
            entries.add(new Entry(
                    rawName,
                    directoryEntry,
                    flags,
                    method,
                    compressionMethod,
                    aesKeyBits,
                    encrypted,
                    dosTime,
                    crc,
                    size,
                    compressedSize,
                    localHeaderOffset));
            offset = next;
        }
        if (offset != directory.length) {
            throw new IOException("central directory trailing garbage");
        }
        return entries;
    }

    @NonNull
    private static Zip64Sizes parseZip64Extra(@NonNull final byte[] extra,
                                              final boolean needSize,
                                              final boolean needCompressed,
                                              final boolean needOffset,
                                              final boolean needDisk) throws IOException {
        int pos = 0;
        while (pos + 4 <= extra.length) {
            final int headerId = readUnsignedShort(extra, pos);
            final int dataSize = readUnsignedShort(extra, pos + 2);
            pos += 4;
            if (pos + dataSize > extra.length) {
                throw new IOException("zip64 extra truncated");
            }
            if (headerId == EXTRA_ZIP64) {
                int cursor = pos;
                long size = -1L;
                long compressed = -1L;
                long offset = -1L;
                int disk = -1;
                if (needSize) {
                    if (cursor + 8 > pos + dataSize) {
                        throw new IOException("zip64 size missing");
                    }
                    size = readLong(extra, cursor);
                    cursor += 8;
                }
                if (needCompressed) {
                    if (cursor + 8 > pos + dataSize) {
                        throw new IOException("zip64 compressed size missing");
                    }
                    compressed = readLong(extra, cursor);
                    cursor += 8;
                }
                if (needOffset) {
                    if (cursor + 8 > pos + dataSize) {
                        throw new IOException("zip64 offset missing");
                    }
                    offset = readLong(extra, cursor);
                    cursor += 8;
                }
                if (needDisk) {
                    if (cursor + 4 > pos + dataSize) {
                        throw new IOException("zip64 disk missing");
                    }
                    disk = (int) readUnsignedInt(extra, cursor);
                }
                return new Zip64Sizes(size, compressed, offset, disk);
            }
            pos += dataSize;
        }
        throw new IOException("zip64 extra field not found");
    }

    @Nullable
    private static AesExtra parseAesExtra(@NonNull final byte[] extra) throws IOException {
        int pos = 0;
        while (pos + 4 <= extra.length) {
            final int headerId = readUnsignedShort(extra, pos);
            final int dataSize = readUnsignedShort(extra, pos + 2);
            pos += 4;
            if (pos + dataSize > extra.length) {
                throw new IOException("AES extra truncated");
            }
            if (headerId == EXTRA_AES) {
                if (dataSize < 7) {
                    throw new IOException("AES extra too small");
                }
                final int strength = extra[pos + 4] & 0xff;
                final int compressionMethod = readUnsignedShort(extra, pos + 5);
                final int keyBits;
                switch (strength) {
                    case 1:
                        keyBits = 128;
                        break;
                    case 2:
                        keyBits = 192;
                        break;
                    case 3:
                        keyBits = 256;
                        break;
                    default:
                        throw new IOException("unsupported AES strength: " + strength);
                }
                return new AesExtra(keyBits, compressionMethod);
            }
            pos += dataSize;
        }
        return null;
    }

    @NonNull
    private static Charset charsetFor(final int flags) {
        if ((flags & GP_UTF8) != 0) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName("Cp437");
        } catch (final Exception ignored) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static void readFully(@NonNull final FileDescriptor fd, final long fileOffset,
                                  @NonNull final byte[] buffer, final int arrayOffset,
                                  final int length) throws IOException {
        int done = 0;
        while (done < length) {
            final int read;
            try {
                read = Os.pread(fd, buffer, arrayOffset + done, length - done,
                        fileOffset + done);
            } catch (final ErrnoException e) {
                throw new IOException(e);
            }
            if (read <= 0) {
                throw new IOException("unexpected EOF in zip");
            }
            done += read;
        }
    }

    private static int readInt(@NonNull final byte[] buffer, final int offset) {
        return (buffer[offset] & 0xff)
                | ((buffer[offset + 1] & 0xff) << 8)
                | ((buffer[offset + 2] & 0xff) << 16)
                | ((buffer[offset + 3] & 0xff) << 24);
    }

    private static int readUnsignedShort(@NonNull final byte[] buffer, final int offset) {
        return (buffer[offset] & 0xff) | ((buffer[offset + 1] & 0xff) << 8);
    }

    private static long readUnsignedInt(@NonNull final byte[] buffer, final int offset) {
        return readInt(buffer, offset) & 0xffffffffL;
    }

    private static long readLong(@NonNull final byte[] buffer, final int offset) {
        return (readUnsignedInt(buffer, offset))
                | (readUnsignedInt(buffer, offset + 4) << 32);
    }

    static final class Entry {
        @NonNull
        final String rawName;
        final boolean directory;
        final int flags;
        final int method;
        final int compressionMethod;
        final int aesKeyBits;
        final boolean encrypted;
        final int dosTime;
        final long crc;
        final long size;
        final long compressedSize;
        final long localHeaderOffset;

        Entry(@NonNull final String rawName, final boolean directory, final int flags,
              final int method, final int compressionMethod, final int aesKeyBits,
              final boolean encrypted, final int dosTime, final long crc, final long size,
              final long compressedSize, final long localHeaderOffset) {
            this.rawName = rawName;
            this.directory = directory;
            this.flags = flags;
            this.method = method;
            this.compressionMethod = compressionMethod;
            this.aesKeyBits = aesKeyBits;
            this.encrypted = encrypted;
            this.dosTime = dosTime;
            this.crc = crc;
            this.size = size;
            this.compressedSize = compressedSize;
            this.localHeaderOffset = localHeaderOffset;
        }
    }

    private static final class Eocd {
        final long entryCount;
        final long directorySize;
        final long directoryOffset;

        Eocd(final long entryCount, final long directorySize, final long directoryOffset) {
            this.entryCount = entryCount;
            this.directorySize = directorySize;
            this.directoryOffset = directoryOffset;
        }
    }

    private static final class Zip64Sizes {
        final long size;
        final long compressedSize;
        final long localHeaderOffset;
        final int diskStart;

        Zip64Sizes(final long size, final long compressedSize, final long localHeaderOffset,
                   final int diskStart) {
            this.size = size;
            this.compressedSize = compressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.diskStart = diskStart;
        }
    }

    private static final class AesExtra {
        final int keyBits;
        final int compressionMethod;

        AesExtra(final int keyBits, final int compressionMethod) {
            this.keyBits = keyBits;
            this.compressionMethod = compressionMethod;
        }
    }
}
