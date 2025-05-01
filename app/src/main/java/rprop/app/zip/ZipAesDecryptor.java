package rprop.app.zip;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * WinZip AES decryption (AE-1 / AE-2): PBKDF2-HMAC-SHA1 + AES-CTR + HMAC-SHA1.
 */
final class ZipAesDecryptor {
    private static final int PBKDF2_ITERATIONS = 1000;
    static final int AUTH_CODE_LENGTH = 10;

    private ZipAesDecryptor() {
    }

    static int saltLengthForBits(final int keyBits) throws IOException {
        switch (keyBits) {
            case 128:
                return 8;
            case 192:
                return 12;
            case 256:
                return 16;
            default:
                throw new IOException("unsupported AES key size: " + keyBits);
        }
    }

    static int keyLengthForBits(final int keyBits) throws IOException {
        switch (keyBits) {
            case 128:
                return 16;
            case 192:
                return 24;
            case 256:
                return 32;
            default:
                throw new IOException("unsupported AES key size: " + keyBits);
        }
    }

    @NonNull
    static Keys deriveKeys(final int keyBits, @NonNull final char[] password,
                           @NonNull final byte[] salt) throws IOException {
        final int keyLen = keyLengthForBits(keyBits);
        final int macLen = keyLen;
        final int totalLen = keyLen + macLen + 2;
        try {
            final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            final PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, totalLen * 8);
            final byte[] derived = factory.generateSecret(spec).getEncoded();
            final byte[] aesKey = Arrays.copyOfRange(derived, 0, keyLen);
            final byte[] macKey = Arrays.copyOfRange(derived, keyLen, keyLen + macLen);
            final byte[] verify = Arrays.copyOfRange(derived, keyLen + macLen, keyLen + macLen + 2);
            return new Keys(aesKey, macKey, verify);
        } catch (final GeneralSecurityException e) {
            throw new IOException("AES key derivation failed", e);
        }
    }

    static void verifyPassword(@NonNull final Keys keys, @NonNull final byte[] passwordVerifier)
            throws ZipWrongPasswordException {
        if (passwordVerifier.length < 2
                || keys.verify[0] != passwordVerifier[0]
                || keys.verify[1] != passwordVerifier[1]) {
            throw new ZipWrongPasswordException();
        }
    }

    @NonNull
    static byte[] decryptAndAuthenticate(@NonNull final Keys keys,
                                          @NonNull final byte[] cipherAndAuth)
            throws IOException {
        if (cipherAndAuth.length < AUTH_CODE_LENGTH) {
            throw new IOException("AES payload truncated");
        }
        final int cipherLen = cipherAndAuth.length - AUTH_CODE_LENGTH;
        final byte[] cipherText = Arrays.copyOfRange(cipherAndAuth, 0, cipherLen);
        final byte[] authCode = Arrays.copyOfRange(cipherAndAuth, cipherLen, cipherAndAuth.length);
        try {
            final Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(keys.macKey, "HmacSHA1"));
            final byte[] digest = mac.doFinal(cipherText);
            for (int i = 0; i < AUTH_CODE_LENGTH; i++) {
                if (digest[i] != authCode[i]) {
                    throw new ZipWrongPasswordException();
                }
            }
            return aesCtrDecrypt(keys.aesKey, cipherText);
        } catch (final ZipWrongPasswordException e) {
            throw e;
        } catch (final GeneralSecurityException e) {
            throw new IOException("AES decrypt failed", e);
        }
    }

    @NonNull
    private static byte[] aesCtrDecrypt(@NonNull final byte[] key,
                                        @NonNull final byte[] cipherText)
            throws GeneralSecurityException {
        // WinZip AES uses little-endian 128-bit counter starting at 1.
        final byte[] nonce = new byte[16];
        nonce[0] = 1;
        final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        final byte[] plain = new byte[cipherText.length];
        final byte[] keyStream = new byte[16];
        int offset = 0;
        while (offset < cipherText.length) {
            System.arraycopy(nonce, 0, keyStream, 0, 16);
            final byte[] block = cipher.doFinal(keyStream);
            final int n = Math.min(16, cipherText.length - offset);
            for (int i = 0; i < n; i++) {
                plain[offset + i] = (byte) (cipherText[offset + i] ^ block[i]);
            }
            offset += n;
            incrementLittleEndian(nonce);
        }
        return plain;
    }

    private static void incrementLittleEndian(@NonNull final byte[] counter) {
        for (int i = 0; i < counter.length; i++) {
            counter[i]++;
            if (counter[i] != 0) {
                break;
            }
        }
    }

    static final class Keys {
        @NonNull
        final byte[] aesKey;
        @NonNull
        final byte[] macKey;
        @NonNull
        final byte[] verify;

        Keys(@NonNull final byte[] aesKey, @NonNull final byte[] macKey,
             @NonNull final byte[] verify) {
            this.aesKey = aesKey;
            this.macKey = macKey;
            this.verify = verify;
        }
    }
}
