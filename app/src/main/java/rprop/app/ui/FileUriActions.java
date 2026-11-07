package rprop.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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
                    return readLink(pfd);
                }
            }
        } catch (final Exception e) {
            Log.w(TAG, e);
        }
        return null;
    }

    public static String readLink(@NonNull final ParcelFileDescriptor pfd) {
        try {
            final int fd = pfd.getFd();
            final String inputFile = "/proc/self/fd/" + fd;
            return Os.readlink(inputFile);
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

    public static void share(final Activity activity, @NonNull final List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        if (uris.size() == 1) {
            share(activity, uris.get(0));
            return;
        }
        final ArrayList<Uri> streams = new ArrayList<>(uris);
        final Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.setType(commonMimeType(activity, streams));
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setClipData(clipData(streams));
        final Intent chooser = Intent.createChooser(shareIntent, null);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(chooser);
    }

    @NonNull
    private static String commonMimeType(@NonNull final Activity activity,
                                         @NonNull final List<Uri> uris) {
        final ContentResolver resolver = activity.getContentResolver();
        String shared = null;
        for (final Uri uri : uris) {
            final String type = resolver.getType(uri);
            if (type == null || type.isEmpty()) {
                return "*/*";
            }
            if (shared == null) {
                shared = type;
                continue;
            }
            if (shared.equals(type)) {
                continue;
            }
            final int slash = shared.indexOf('/');
            final String major = slash > 0 ? shared.substring(0, slash) : shared;
            if (type.startsWith(major + "/")) {
                shared = major + "/*";
                continue;
            }
            return "*/*";
        }
        return shared == null ? "*/*" : shared;
    }

    @NonNull
    private static ClipData clipData(@NonNull final List<Uri> uris) {
        final ClipData clipData = ClipData.newRawUri("files", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clipData.addItem(new ClipData.Item(uris.get(i)));
        }
        return clipData;
    }
}
