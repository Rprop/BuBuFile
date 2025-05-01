package rprop.app.zip;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Traditional PKWARE ZipCrypto stream cipher. */
final class ZipCrypto {
    private final int[] mKeys = new int[3];
    private static final int[] CRC_TABLE = makeCrcTable();

    private ZipCrypto() {
    }

    @NonNull
    static ZipCrypto forPassword(@NonNull final char[] password) {
        final ZipCrypto crypto = new ZipCrypto();
        crypto.mKeys[0] = 0x12345678;
        crypto.mKeys[1] = 0x23456789;
        crypto.mKeys[2] = 0x34567890;
        final byte[] passBytes = new String(password).getBytes(StandardCharsets.UTF_8);
        for (final byte b : passBytes) {
            crypto.updateKeys(b);
        }
        return crypto;
    }

    void decryptHeader(@NonNull final byte[] header12, final int checkByte) throws IOException {
        if (header12.length < 12) {
            throw new IOException("zip crypto header truncated");
        }
        for (int i = 0; i < 12; i++) {
            header12[i] = decryptByte(header12[i]);
        }
        if ((header12[11] & 0xff) != (checkByte & 0xff)) {
            throw new ZipWrongPasswordException();
        }
    }

    byte decryptByte(final byte cipher) {
        final byte plain = (byte) (cipher ^ decryptByteValue());
        updateKeys(plain);
        return plain;
    }

    void decryptInPlace(@NonNull final byte[] buffer, final int offset, final int length) {
        final int end = offset + length;
        for (int i = offset; i < end; i++) {
            buffer[i] = decryptByte(buffer[i]);
        }
    }

    private int decryptByteValue() {
        final int temp = this.mKeys[2] | 2;
        return (temp * (temp ^ 1)) >>> 8;
    }

    private void updateKeys(final byte b) {
        this.mKeys[0] = crc32(this.mKeys[0], b);
        this.mKeys[1] = (this.mKeys[1] + (this.mKeys[0] & 0xff)) * 134775813 + 1;
        this.mKeys[2] = crc32(this.mKeys[2], (byte) (this.mKeys[1] >>> 24));
    }

    private static int crc32(final int crc, final byte b) {
        return (crc >>> 8) ^ CRC_TABLE[(crc ^ b) & 0xff];
    }

    @NonNull
    private static int[] makeCrcTable() {
        final int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                if ((c & 1) != 0) {
                    c = (c >>> 1) ^ 0xedb88320;
                } else {
                    c = c >>> 1;
                }
            }
            table[i] = c;
        }
        return table;
    }
}
