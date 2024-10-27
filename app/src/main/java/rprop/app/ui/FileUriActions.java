package rprop.app.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FileUriActions {
    private static final String TAG = "file-uri-actions";

    public static boolean transfer(final ContentResolver resolver, final File inputFile, final Uri outputUri) {
        try {
            final ParcelFileDescriptor pfd = resolver.openFileDescriptor(outputUri, "w");
            if (pfd != null) {
                try (final FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor())) {
                    try (final FileInputStream fileInputStream = new FileInputStream(inputFile)) {
                        fileInputStream.getChannel().transferTo(0, Long.MAX_VALUE, fileOutputStream.getChannel());
                    }
                } finally {
                    pfd.close();
                }
                return true;
            }
        } catch (final Exception e) {
            Log.w(TAG, e);
        }
        return false;
    }

    public static String readLink(final ContentResolver resolver, final Uri uri) {
        try {
            final ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
            if (pfd != null) {
                try (pfd) {
                    final int fd = pfd.getFd();
                    final String inputFile = "/proc/self/fd/" + fd;
                    try {
                        return Os.readlink(inputFile);
                    } catch (final Exception e) {
                        Log.w(TAG, e);
                    }
                }
            }
        } catch (final Exception e) {
            Log.w(TAG, e);
        }
        return null;
    }

    public static String resolvePathLabel(final Context context, final Uri uri) {
        final String link = readLink(context.getContentResolver(), uri);
        if (link != null) {
            return link;
        }
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            final String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            final String documentId = DocumentsContract.getDocumentId(uri);
            if (documentId != null) {
                if (documentId.startsWith("raw:")) {
                    return documentId.substring(4);
                }
                if (documentId.startsWith("primary:")) {
                    return "/storage/emulated/0/" + documentId.substring("primary:".length());
                }
            }
        }
        final String displayName = queryDisplayName(context, uri);
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        final String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null && !lastSegment.isEmpty()) {
            return lastSegment;
        }
        return uri.toString();
    }

    public static boolean moveToTree(final Context context, final Uri source,
                                     final Uri treeUri) {
        try {
            final ContentResolver resolver = context.getContentResolver();
            final String displayName = queryDisplayName(context, source);
            if (displayName == null) {
                return false;
            }

            String mimeType = resolver.getType(source);
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            final DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
            if (tree == null || !tree.canWrite()) {
                return false;
            }

            final DocumentFile existing = tree.findFile(displayName);
            if (existing != null) {
                existing.delete();
            }
            final DocumentFile destination = tree.createFile(mimeType, displayName);
            if (destination == null) {
                return false;
            }

            try (final InputStream inputStream = resolver.openInputStream(source);
                 final OutputStream outputStream = resolver.openOutputStream(destination.getUri())) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, read);
                }
            } catch (final IOException e) {
                Log.w(TAG, e);
                destination.delete();
                return false;
            }
            return true;
        } catch (final SecurityException e) {
            Log.w(TAG, e);
            return false;
        }
    }

    private static String queryDisplayName(final Context context, final Uri uri) {
        final DocumentFile documentFile = DocumentFile.fromSingleUri(context, uri);
        if (documentFile != null) {
            return documentFile.getName();
        }
        return null;
    }

    public static void share(final Activity activity, final Uri uri) {
        final ContentResolver resolver = activity.getContentResolver();
        String type = resolver.getType(uri);
        if (type == null) {
            type = "*/*";
        }
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(type);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(shareIntent, null), null);
    }
}
