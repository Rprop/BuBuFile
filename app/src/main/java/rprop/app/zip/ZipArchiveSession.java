package rprop.app.zip;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import rprop.app.io.PreadInputStream;

public final class ZipArchiveSession implements Closeable {
    static final String CACHE_DIR = "zip_extract";
    private static final String TAG = "zip-session";
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 180;

    @Nullable
    private final ParcelFileDescriptor mPfd;
    @Nullable
    private final PreadZipFile mPreadZip;
    @Nullable
    private final ZipFile mZipFile;
    @Nullable
    private final Context mAppContext;
    @Nullable
    private final Uri mUri;
    @NonNull
    private final List<EntryMeta> mEntries;
    @Nullable
    private char[] mPassword;

    private ZipArchiveSession(@Nullable final ParcelFileDescriptor pfd,
                              @Nullable final PreadZipFile preadZip,
                              @Nullable final ZipFile zipFile,
                              @Nullable final Context appContext,
                              @Nullable final Uri uri,
                              @NonNull final List<EntryMeta> entries) {
        this.mPfd = pfd;
        this.mPreadZip = preadZip;
        this.mZipFile = zipFile;
        this.mAppContext = appContext;
        this.mUri = uri;
        this.mEntries = entries;
    }

    @NonNull
    static ZipArchiveSession open(@NonNull final Context context, @NonNull final Uri uri)
            throws IOException {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        } catch (final Exception e) {
            Log.w(TAG, "openFileDescriptor failed", e);
        }

        if (pfd != null) {
            try {
                final PreadZipFile preadZip = PreadZipFile.open(pfd);
                return openPreadZip(pfd, preadZip);
            } catch (final IOException e) {
                Log.w(TAG, "PreadZipFile failed, trying ZipFile(/proc/fd)", e);
            }

            try {
                final ZipFile zipFile = new ZipFile("/proc/self/fd/" + pfd.getFd());
                return openZipFile(zipFile, pfd);
            } catch (final IOException e) {
                Log.w(TAG, "ZipFile(/proc/fd) failed, fallback to ZipInputStream", e);
                try {
                    return openZipStreamFromPfd(pfd);
                } catch (final IOException streamFailed) {
                    Log.w(TAG, "ZipInputStream(pfd) failed, fallback to uri stream",
                            streamFailed);
                    try {
                        pfd.close();
                    } catch (final IOException ignored) {
                    }
                    pfd = null;
                }
            }
        }

        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            final String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                try {
                    return openZipFile(new ZipFile(path), null);
                } catch (final IOException e) {
                    Log.w(TAG, "ZipFile(path) failed, fallback to uri stream: " + path, e);
                }
            }
        }

        return openZipStreamFromUri(context, uri);
    }

    @NonNull
    private static ZipArchiveSession openPreadZip(@NonNull final ParcelFileDescriptor pfd,
                                                  @NonNull final PreadZipFile preadZip) {
        final List<EntryMeta> entries = new ArrayList<>();
        for (final PreadZipFile.Entry entry : preadZip.getEntries()) {
            final EntryMeta meta = toEntryMeta(entry);
            if (meta != null) {
                entries.add(meta);
            }
        }
        return new ZipArchiveSession(pfd, preadZip, null, null, null, entries);
    }

    @NonNull
    private static ZipArchiveSession openZipFile(@NonNull final ZipFile zipFile,
                                                 @Nullable final ParcelFileDescriptor pfd)
            throws IOException {
        try {
            final List<EntryMeta> entries = readEntriesFromZipFile(zipFile);
            return new ZipArchiveSession(pfd, null, zipFile, null, null, entries);
        } catch (final RuntimeException e) {
            try {
                zipFile.close();
            } catch (final IOException ignored) {
            }
            // Keep pfd open for caller fallback.
            throw new IOException("unable to read zip central directory", e);
        }
    }

    @NonNull
    private static ZipArchiveSession openZipStreamFromPfd(
            @NonNull final ParcelFileDescriptor pfd) throws IOException {
        final List<EntryMeta> entries;
        try (final InputStream inputStream = PreadInputStream.fromPfd(pfd, 0L);
             final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            entries = readEntriesFromZipStream(zipInputStream);
        }
        return new ZipArchiveSession(pfd, null, null, null, null, entries);
    }

    @NonNull
    private static ZipArchiveSession openZipStreamFromUri(@NonNull final Context context,
                                                          @NonNull final Uri uri)
            throws IOException {
        final List<EntryMeta> entries;
        try (final InputStream inputStream = openUriInputStream(context, uri);
             final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            entries = readEntriesFromZipStream(zipInputStream);
        }
        return new ZipArchiveSession(
                null, null, null, context.getApplicationContext(), uri, entries);
    }

    @NonNull
    private static List<EntryMeta> readEntriesFromZipFile(@NonNull final ZipFile zipFile) {
        final List<EntryMeta> entries = new ArrayList<>();
        final Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
        while (enumeration.hasMoreElements()) {
            final EntryMeta meta = toEntryMeta(enumeration.nextElement());
            if (meta != null) {
                entries.add(meta);
            }
        }
        return entries;
    }

    @NonNull
    private static List<EntryMeta> readEntriesFromZipStream(
            @NonNull final ZipInputStream zipInputStream) throws IOException {
        final List<EntryMeta> entries = new ArrayList<>();
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                // Drain entry bytes so size/crc from data descriptor are available.
                drainEntry(zipInputStream);
            }
            final EntryMeta meta = toEntryMeta(entry);
            zipInputStream.closeEntry();
            if (meta != null) {
                entries.add(meta);
            }
        }
        return entries;
    }

    @Nullable
    private static EntryMeta toEntryMeta(@NonNull final ZipEntry entry) {
        final String rawName = entry.getName();
        final String name = normalizeEntryName(rawName);
        if (name == null) {
            return null;
        }
        return new EntryMeta(
                name,
                rawName,
                entry.isDirectory() || name.endsWith("/"),
                false,
                entry.getSize(),
                entry.getCrc());
    }

    @Nullable
    private static EntryMeta toEntryMeta(@NonNull final PreadZipFile.Entry entry) {
        final String name = normalizeEntryName(entry.rawName);
        if (name == null) {
            return null;
        }
        return new EntryMeta(
                name,
                entry.rawName,
                entry.directory || name.endsWith("/"),
                entry.encrypted,
                entry.size,
                entry.crc);
    }

    void setPassword(@Nullable final char[] password) {
        clearPassword();
        if (password != null && password.length > 0) {
            this.mPassword = password.clone();
        }
    }

    boolean hasPassword() {
        return this.mPassword != null && this.mPassword.length > 0;
    }

    private void clearPassword() {
        if (this.mPassword != null) {
            java.util.Arrays.fill(this.mPassword, '\0');
            this.mPassword = null;
        }
    }

    @NonNull
    List<BrowseItem> listChildren(@NonNull final String currentDir) {
        final String prefix = normalizeDir(currentDir);
        final LinkedHashMap<String, BrowseItem> directories = new LinkedHashMap<>();
        final List<BrowseItem> files = new ArrayList<>();

        for (final EntryMeta entry : this.mEntries) {
            if (!entry.name.startsWith(prefix)) {
                continue;
            }
            final String relative = entry.name.substring(prefix.length());
            if (relative.isEmpty()) {
                continue;
            }
            final int slash = relative.indexOf('/');
            if (slash >= 0) {
                final String dirName = relative.substring(0, slash);
                if (!dirName.isEmpty() && !directories.containsKey(dirName)) {
                    directories.put(dirName, BrowseItem.directory(dirName, prefix + dirName + '/'));
                }
                continue;
            }
            if (entry.directory) {
                continue;
            }
            files.add(BrowseItem.file(
                    relative, entry.name, entry.rawName, entry.encrypted, entry.size, entry.crc));
        }

        final List<BrowseItem> items = new ArrayList<>(directories.size() + files.size());
        items.addAll(directories.values());
        Collections.sort(items, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        Collections.sort(files, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        items.addAll(files);
        return items;
    }

    @NonNull
    File extractToCache(@NonNull final Context context, @NonNull final BrowseItem item)
            throws IOException {
        if (item.directory) {
            throw new IOException("cannot extract directory");
        }
        final File cacheFile = cacheFileFor(context, item.entryName, item.size, item.crc);
        if (item.size >= 0L && cacheFile.isFile() && cacheFile.length() == item.size) {
            return cacheFile;
        }
        if (item.size < 0L && cacheFile.isFile() && cacheFile.length() > 0L) {
            return cacheFile;
        }

        final File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("unable to create cache dir");
        }

        final File partFile = new File(cacheFile.getPath() + ".part");
        if (partFile.exists() && !partFile.delete()) {
            throw new IOException("unable to clear partial extract");
        }

        try {
            if (this.mPreadZip != null) {
                extractWithPreadZip(item, partFile);
            } else if (item.encrypted) {
                throw new IOException("encrypted zip requires PreadZipFile backend");
            } else if (this.mZipFile != null) {
                extractWithZipFile(item, partFile);
            } else {
                extractWithZipStream(context, item, partFile);
            }
        } catch (final IOException e) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            throw e;
        }

        if (cacheFile.exists() && !cacheFile.delete()) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            throw new IOException("unable to replace cached extract");
        }
        if (!partFile.renameTo(cacheFile)) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            throw new IOException("unable to commit extracted file");
        }
        return cacheFile;
    }

    private void extractWithPreadZip(@NonNull final BrowseItem item,
                                     @NonNull final File partFile) throws IOException {
        final PreadZipFile preadZip = this.mPreadZip;
        if (preadZip == null) {
            throw new IOException("pread zip backend unavailable");
        }
        PreadZipFile.Entry entry = preadZip.findByRawName(item.rawEntryName);
        if (entry == null) {
            for (final PreadZipFile.Entry candidate : preadZip.getEntries()) {
                final String normalized = normalizeEntryName(candidate.rawName);
                if (item.entryName.equals(normalized)) {
                    entry = candidate;
                    break;
                }
            }
        }
        if (entry == null || entry.directory) {
            throw new IOException("zip entry missing: " + item.rawEntryName);
        }
        try (final FileOutputStream out = new FileOutputStream(partFile)) {
            preadZip.extract(entry, out, this.mPassword);
        }
    }

    private void extractWithZipFile(@NonNull final BrowseItem item,
                                    @NonNull final File partFile) throws IOException {
        final ZipFile zipFile = this.mZipFile;
        if (zipFile == null) {
            throw new IOException("zip file backend unavailable");
        }
        final ZipEntry entry = zipFile.getEntry(item.rawEntryName);
        if (entry == null) {
            throw new IOException("zip entry missing: " + item.rawEntryName);
        }
        try (final InputStream in = zipFile.getInputStream(entry);
             final FileOutputStream out = new FileOutputStream(partFile)) {
            if (in == null) {
                throw new IOException("unable to open zip entry stream");
            }
            copyStream(in, out);
            out.getFD().sync();
        }
    }

    private void extractWithZipStream(@NonNull final Context context,
                                      @NonNull final BrowseItem item,
                                      @NonNull final File partFile) throws IOException {
        try (final InputStream inputStream = openStreamInput(context);
             final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || !entryMatches(entry, item)) {
                    zipInputStream.closeEntry();
                    continue;
                }
                try (final FileOutputStream out = new FileOutputStream(partFile)) {
                    copyStream(zipInputStream, out);
                    out.getFD().sync();
                }
                zipInputStream.closeEntry();
                return;
            }
        }
        throw new IOException("zip entry missing: " + item.rawEntryName);
    }

    @NonNull
    private InputStream openStreamInput(@NonNull final Context context) throws IOException {
        final ParcelFileDescriptor pfd = this.mPfd;
        if (pfd != null) {
            return PreadInputStream.fromPfd(pfd, 0L);
        }
        final Uri uri = this.mUri;
        final Context appContext = this.mAppContext != null
                ? this.mAppContext
                : context.getApplicationContext();
        if (uri == null) {
            throw new IOException("zip stream backend unavailable");
        }
        return openUriInputStream(appContext, uri);
    }

    private static boolean entryMatches(@NonNull final ZipEntry entry,
                                        @NonNull final BrowseItem item) {
        final String rawName = entry.getName();
        if (item.rawEntryName.equals(rawName)) {
            return true;
        }
        final String normalized = normalizeEntryName(rawName);
        return normalized != null && item.entryName.equals(normalized);
    }

    @Override
    public void close() {
        clearPassword();
        final ZipFile zipFile = this.mZipFile;
        if (zipFile != null) {
            try {
                zipFile.close();
            } catch (final IOException ignored) {
            }
        }
        final ParcelFileDescriptor pfd = this.mPfd;
        if (pfd != null) {
            try {
                pfd.close();
            } catch (final IOException ignored) {
            }
        }
    }

    @NonNull
    static String parentDir(@NonNull final String currentDir) {
        final String normalized = normalizeDir(currentDir);
        if (normalized.isEmpty()) {
            return "";
        }
        final String withoutSlash = normalized.substring(0, normalized.length() - 1);
        final int slash = withoutSlash.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        return withoutSlash.substring(0, slash + 1);
    }

    /**
     * If {@code path} points at our zip extract cache ({@code .../zip_extract/crc_size_name}),
     * returns the original entry file name; otherwise {@code null}.
     */
    @Nullable
    public static String displayNameFromCachePath(@Nullable final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        final String normalized = path.replace('\\', '/');
        final String marker = '/' + CACHE_DIR + '/';
        final int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String fileName = normalized.substring(markerIndex + marker.length());
        final int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        if (fileName.isEmpty()) {
            return null;
        }
        final int first = fileName.indexOf('_');
        if (first <= 0) {
            return null;
        }
        final int second = fileName.indexOf('_', first + 1);
        if (second <= first + 1 || second >= fileName.length() - 1) {
            return null;
        }
        final String crcPart = fileName.substring(0, first);
        final String sizePart = fileName.substring(first + 1, second);
        if (!isHexToken(crcPart) || !isDecimalToken(sizePart)) {
            return null;
        }
        return fileName.substring(second + 1);
    }

    private static boolean isHexToken(@NonNull final String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            final boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimalToken(@NonNull final String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    @NonNull
    static String guessMimeType(@NonNull final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return "application/octet-stream";
        }
        final String extension = fileName.substring(dot + 1).toLowerCase(Locale.US);
        final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    @NonNull
    private static InputStream openUriInputStream(@NonNull final Context context,
                                                  @NonNull final Uri uri) throws IOException {
        final InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("unable to open zip stream");
        }
        return inputStream;
    }

    private static void drainEntry(@NonNull final ZipInputStream zipInputStream)
            throws IOException {
        final byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        while (zipInputStream.read(buffer) >= 0) {
            // Discard entry payload; needed so ZipEntry size/crc become available.
        }
    }

    private static void copyStream(@NonNull final InputStream in,
                                   @NonNull final FileOutputStream out) throws IOException {
        final byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    @NonNull
    private static File cacheFileFor(@NonNull final Context context,
                                     @NonNull final String entryName,
                                     final long size,
                                     final long crc) {
        final File dir = new File(context.getCacheDir(), CACHE_DIR);
        final String safeName = sanitizeFileName(entryName);
        return new File(dir, Long.toHexString(crc) + "_" + size + "_" + safeName);
    }

    @NonNull
    private static String sanitizeFileName(@NonNull final String entryName) {
        String baseName = entryName.replace('\\', '/');
        final int slash = baseName.lastIndexOf('/');
        if (slash >= 0) {
            baseName = baseName.substring(slash + 1);
        }
        final StringBuilder builder = new StringBuilder(baseName.length());
        for (int i = 0; i < baseName.length(); i++) {
            final char c = baseName.charAt(i);
            if (c <= 0x1f
                    || c == '"' || c == '*' || c == '/' || c == ':'
                    || c == '<' || c == '>' || c == '?' || c == '\\' || c == '|') {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        String safe = builder.toString();
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            safe = "file.bin";
        }
        if (safe.length() > MAX_FILE_NAME_LENGTH) {
            final int dot = safe.lastIndexOf('.');
            final String extension = dot > 0 && dot > safe.length() - 16
                    ? safe.substring(dot)
                    : "";
            final int keep = Math.max(1, MAX_FILE_NAME_LENGTH - extension.length());
            safe = safe.substring(0, keep) + extension;
        }
        return safe;
    }

    @Nullable
    private static String normalizeEntryName(@Nullable final String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return null;
        }
        String name = rawName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
            return null;
        }
        return name;
    }

    @NonNull
    private static String normalizeDir(@NonNull final String currentDir) {
        if (currentDir.isEmpty()) {
            return "";
        }
        String dir = currentDir.replace('\\', '/');
        if (!dir.endsWith("/")) {
            dir = dir + '/';
        }
        if (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        return dir;
    }

    static final class BrowseItem {
        @NonNull
        final String displayName;
        @NonNull
        final String entryName;
        @NonNull
        final String rawEntryName;
        final boolean directory;
        final boolean encrypted;
        final long size;
        final long crc;

        private BrowseItem(@NonNull final String displayName,
                           @NonNull final String entryName,
                           @NonNull final String rawEntryName,
                           final boolean directory,
                           final boolean encrypted,
                           final long size,
                           final long crc) {
            this.displayName = displayName;
            this.entryName = entryName;
            this.rawEntryName = rawEntryName;
            this.directory = directory;
            this.encrypted = encrypted;
            this.size = size;
            this.crc = crc;
        }

        @NonNull
        static BrowseItem directory(@NonNull final String displayName,
                                    @NonNull final String entryName) {
            return new BrowseItem(displayName, entryName, entryName, true, false, -1L, -1L);
        }

        @NonNull
        static BrowseItem file(@NonNull final String displayName,
                               @NonNull final String entryName,
                               @NonNull final String rawEntryName,
                               final boolean encrypted,
                               final long size,
                               final long crc) {
            return new BrowseItem(displayName, entryName, rawEntryName, false, encrypted,
                    size, crc);
        }
    }

    private static final class EntryMeta {
        @NonNull
        final String name;
        @NonNull
        final String rawName;
        final boolean directory;
        final boolean encrypted;
        final long size;
        final long crc;

        EntryMeta(@NonNull final String name,
                  @NonNull final String rawName,
                  final boolean directory,
                  final boolean encrypted,
                  final long size,
                  final long crc) {
            this.name = name;
            this.rawName = rawName;
            this.directory = directory;
            this.encrypted = encrypted;
            this.size = size;
            this.crc = crc;
        }
    }
}
