package rprop.app.lan;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumMap;
import java.util.Map;

import rp.file.R;

public final class LanShareHelper {
    private static final String TAG = "lan-share";
    private static final int QR_SIZE_PX = 512;

    private LanShareHelper() {
    }

    public static void share(@NonNull final Activity activity, @NonNull final Uri uri) {
        final String fileName = sanitizeFileName(queryDisplayName(activity, uri));
        final long length = queryLength(activity, uri);
        if (fileName == null || fileName.isEmpty() || length < 0) {
            Toast.makeText(activity, R.string.lan_share_failed, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }
        final String ip = resolveLanIpv4();
        if (ip == null) {
            Toast.makeText(activity, R.string.lan_share_no_network, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }
        final LanShareServer.ShareStart shareStart;
        try {
            shareStart = LanShareServer.startSharing(activity, uri, fileName, length);
        } catch (final IOException e) {
            Log.w(TAG, e);
            Toast.makeText(activity, R.string.lan_share_failed, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }
        final String shareUrl = LanShareServer.buildShareUrl(
                ip, shareStart.fileName, shareStart.length);
        final Bitmap qrBitmap;
        try {
            qrBitmap = createQrBitmap(shareUrl, QR_SIZE_PX);
        } catch (final WriterException e) {
            Log.w(TAG, e);
            Toast.makeText(activity, R.string.lan_share_failed, Toast.LENGTH_LONG).show();
            activity.finish();
            return;
        }
        final View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_lan_share_qr, null);
        final ImageView qrView = content.findViewById(R.id.lan_share_qr);
        qrView.setImageBitmap(qrBitmap);
        final TextView titleView = (TextView) LayoutInflater.from(activity)
                .inflate(R.layout.dialog_file_path_title, null);
        titleView.setText(shareUrl);
        new MaterialAlertDialogBuilder(activity)
                .setCustomTitle(titleView)
                .setView(content)
                .setOnDismissListener(dialog -> {
                    if (!activity.isFinishing()) {
                        activity.finish();
                    }
                })
                .show();
    }

    @Nullable
    private static String sanitizeFileName(@Nullable final String fileName) {
        if (fileName == null) {
            return null;
        }
        final String trimmed = fileName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replace('/', '_').replace('\\', '_');
    }

    @Nullable
    private static String queryDisplayName(@NonNull final Activity activity,
                                           @NonNull final Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    final String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        try {
            final DocumentFile documentFile = DocumentFile.fromSingleUri(activity, uri);
            if (documentFile != null) {
                final String name = documentFile.getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (final Exception ignored) {
        }
        final String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null && !lastSegment.isEmpty()) {
            return lastSegment;
        }
        return null;
    }

    private static long queryLength(@NonNull final Activity activity, @NonNull final Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    final long size = cursor.getLong(index);
                    if (size >= 0) {
                        return size;
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        try {
            final DocumentFile documentFile = DocumentFile.fromSingleUri(activity, uri);
            if (documentFile != null) {
                final long size = documentFile.length();
                if (size >= 0) {
                    return size;
                }
            }
        } catch (final Exception ignored) {
        }
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            final String path = uri.getPath();
            if (path != null) {
                final long size = new java.io.File(path).length();
                if (size >= 0) {
                    return size;
                }
            }
        }
        return -1L;
    }

    @Nullable
    private static String resolveLanIpv4() {
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            String fallback = null;
            for (final NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (final InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    final String host = address.getHostAddress();
                    if (host == null || host.startsWith("169.254.")) {
                        continue;
                    }
                    if (isPrivateIpv4(host)) {
                        return host;
                    }
                    if (fallback == null) {
                        fallback = host;
                    }
                }
            }
            return fallback;
        } catch (final Exception e) {
            Log.w(TAG, e);
            return null;
        }
    }

    private static boolean isPrivateIpv4(@NonNull final String host) {
        if (host.startsWith("10.") || host.startsWith("192.168.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            final String[] parts = host.split("\\.");
            if (parts.length > 1) {
                try {
                    final int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (final NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    @NonNull
    private static Bitmap createQrBitmap(@NonNull final String content, final int size)
            throws WriterException {
        final Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        final BitMatrix matrix = new QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        final int width = matrix.getWidth();
        final int height = matrix.getHeight();
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
