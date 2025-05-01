package rprop.app.lan;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import rprop.app.ui.FileUriActions;
import rprop.app.io.PreadInputStream;
import rprop.app.zip.ZipArchiveSession;

public final class LanShareServer extends NanoHTTPD {
    public static final int PORT = 10021;

    private static final String TAG = "lan-share";
    private static final String PATH_SHARE = "/s/";
    private static final String PATH_UPLOAD = "/upload";
    private static final String PATH_UPLOAD_STATUS = "/upload/status";
    private static final String PATH_CLIPBOARD = "/clipboard";
    private static final String PATH_LOGO = "/logo.png";
    private static final String MIME_HTML_UTF8 = "text/html; charset=utf-8";
    private static final String MIME_JSON_UTF8 = "application/json; charset=utf-8";
    private static final String MIME_PNG = "image/png";
    private static final String INDEX_ASSET = "lan_share/index.html";
    private static final String INDEX_STYLE_ASSET = "lan_share/app.css";
    private static final String INDEX_SCRIPT_ASSET = "lan_share/app.js";
    private static final String LOGO_ASSET = "lan_share/logo.png";
    private static final int MAX_CLIPBOARD_BYTES = 1024 * 1024;
    private static final AtomicLong SHARE_SEQUENCE = new AtomicLong();

    @Nullable
    private static LanShareServer sInstance;

    @NonNull
    private final Context mAppContext;
    private final LinkedHashMap<String, ShareTarget> mTargets = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, UploadBatch> mUploadBatches = new ConcurrentHashMap<>();
    @Nullable
    private volatile String mIndexTemplate;
    @Nullable
    private volatile byte[] mLogoBytes;

    private LanShareServer(@NonNull final Context appContext) {
        super(PORT);
        this.mAppContext = appContext;
    }

    @Override
    protected boolean useGzipWhenAccepted(final Response r) {
        // Avoid gzip buffering large shared-file downloads into memory.
        return false;
    }

    public static synchronized void ensureStarted(@NonNull final Context context) {
        if (sInstance != null) {
            return;
        }
        final LanShareServer server = new LanShareServer(context.getApplicationContext());
        try {
            server.start();
            sInstance = server;
            Log.i(TAG, "LAN HTTP server started on port " + PORT);
        } catch (final IOException e) {
            Log.e(TAG, "unable to start LAN HTTP server", e);
        }
    }

    /**
     * Opens and holds a {@link ParcelFileDescriptor} for the share target. Previous shares stay
     * available until the process dies.
     *
     * @return resolved share file name and length used in the share URL
     */
    @NonNull
    public static synchronized ShareStart startSharing(@NonNull final Context context,
                                                       @NonNull final Uri uri,
                                                       @NonNull final String fileName,
                                                       final long lengthHint) throws IOException {
        ensureStarted(context);
        final LanShareServer server = sInstance;
        if (server == null) {
            throw new IOException("LAN HTTP server is not running");
        }

        final ParcelFileDescriptor pfd = context.getContentResolver()
                .openFileDescriptor(uri, "r");
        if (pfd == null) {
            throw new IOException("unable to open file descriptor");
        }
        long length = pfd.getStatSize();
        if (length < 0) {
            length = lengthHint;
        }
        if (length < 0) {
            closeQuietly(pfd);
            throw new IOException("unknown file length");
        }
        String mimeType = context.getContentResolver().getType(uri);
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = guessMimeType(fileName);
        }

        final String originalPath = readLinkPath(pfd);
        final boolean seekable = probeSeekable(pfd);
        String shareName = fileName;
        final String zipExtractName = ZipArchiveSession.displayNameFromCachePath(originalPath);
        if (zipExtractName != null && !zipExtractName.isEmpty()) {
            shareName = zipExtractName;
            if (mimeType == null || mimeType.isEmpty()
                    || "application/octet-stream".equals(mimeType)) {
                mimeType = guessMimeType(shareName);
            }
        }
        String detail = null;
        if (isApkShare(mimeType, shareName)) {
            final ApkInfo apkInfo = extractApkInfo(context, pfd, originalPath);
            if (apkInfo != null) {
                detail = apkInfo.formatDetail();
                if ("base.apk".equalsIgnoreCase(shareName)) {
                    shareName = apkInfo.buildShareFileName();
                }
            }
        } else {
            detail = extractShareDetail(context, uri, pfd, mimeType, shareName);
        }
        rewindShareFd(pfd);
        final long lastModifiedMillis = System.currentTimeMillis();
        final String etag = buildEtag(length, shareName, lastModifiedMillis,
                SHARE_SEQUENCE.incrementAndGet());
        final String key = shareKey(length, shareName);
        final ShareTarget previous = server.mTargets.put(
                key, new ShareTarget(shareName, length, mimeType, originalPath, detail, pfd,
                        seekable, lastModifiedMillis, etag));
        if (previous != null) {
            closeQuietly(previous.pfd);
        }
        return new ShareStart(shareName, length);
    }

    @Override
    public Response serve(final IHTTPSession session) {
        final String path = normalizePath(session.getUri());
        final HttpMethod method = session.getMethod();
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            if (method == HttpMethod.GET && ("/".equals(path) || "/file".equals(path))) {
                final Map<String, String> parms = session.getParms();
                final String flash = parms.get("flash");
                if (flash == null || flash.isEmpty()) {
                    return serveIndex(null, null);
                }
                return serveIndex(flash, "1".equals(parms.get("err")));
            }
            if (PATH_LOGO.equals(path)) {
                return serveLogo();
            }
            if (method == HttpMethod.GET && PATH_CLIPBOARD.equals(path)) {
                return serveClipboardGet();
            }
            if (method == HttpMethod.GET && PATH_UPLOAD_STATUS.equals(path)) {
                return serveUploadStatus(session);
            }
            if (path.startsWith(PATH_SHARE)) {
                return serveSharedFile(session, path);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }
        if (method == HttpMethod.DELETE && path.startsWith(PATH_SHARE)) {
            return serveRemoveShare(path);
        }
        if (method == HttpMethod.POST && PATH_UPLOAD.equals(path)) {
            return serveStreamUpload(session);
        }
        if (method == HttpMethod.POST && PATH_CLIPBOARD.equals(path)) {
            return serveClipboardSet(session);
        }
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT, "Method Not Allowed");
    }

    @NonNull
    private Response serveRemoveShare(@NonNull final String path) {
        final ShareTarget removed;
        synchronized (LanShareServer.class) {
            removed = removeTargetLocked(path);
        }
        if (removed == null) {
            return jsonResponse(Response.Status.NOT_FOUND, false, "share not found");
        }
        closeQuietly(removed.pfd);
        Log.i(TAG, "removed share: " + removed.fileName);
        return jsonResponse(Response.Status.OK, true, "removed");
    }

    @NonNull
    private Response serveUploadStatus(@NonNull final IHTTPSession session) {
        final Map<String, String> headers = session.getHeaders();
        final String batchId = headerValue(headers, "x-upload-batch");
        final String fileName = decodeHeaderFileName(headerValue(headers, "x-file-name"));
        final String sizeHeader = headerValue(headers, "x-file-size");
        if (batchId == null || batchId.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "missing upload batch");
        }
        long totalSize = -1L;
        if (sizeHeader != null) {
            try {
                totalSize = Long.parseLong(sizeHeader.trim());
            } catch (final NumberFormatException ignored) {
                totalSize = -1L;
            }
        }
        try {
            final long received = LanUploadHandler.getReceivedBytes(
                    this.mAppContext, batchId, fileName);
            final JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("received", received);
            if (totalSize >= 0) {
                json.put("size", totalSize);
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON_UTF8, json.toString());
        } catch (final Exception e) {
            Log.w(TAG, e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, false,
                    e.getMessage() == null ? "status failed" : e.getMessage());
        }
    }

    @NonNull
    private Response serveClipboardGet() {
        try {
            final String text = LanClipboardHelper.getText(this.mAppContext);
            final JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("text", text);
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON_UTF8, json.toString());
        } catch (final Exception e) {
            Log.w(TAG, e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, false,
                    e.getMessage() == null ? "clipboard read failed" : e.getMessage());
        }
    }

    @NonNull
    private Response serveClipboardSet(@NonNull final IHTTPSession session) {
        final Map<String, String> headers = session.getHeaders();
        final String lengthHeader = headerValue(headers, "content-length");
        if (lengthHeader == null) {
            return jsonResponse(Response.Status.LENGTH_REQUIRED, false,
                    "Content-Length required");
        }
        final long contentLength;
        try {
            contentLength = Long.parseLong(lengthHeader.trim());
        } catch (final NumberFormatException e) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "invalid Content-Length");
        }
        if (contentLength < 0 || contentLength > MAX_CLIPBOARD_BYTES) {
            return jsonResponse(Response.Status.BAD_REQUEST, false,
                    "clipboard text too large (max 1MB)");
        }
        try {
            final String text = readUtf8Body(session.getInputStream(), contentLength);
            LanClipboardHelper.setText(this.mAppContext, text);
            final JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("message", "clipboard updated");
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON_UTF8, json.toString());
        } catch (final Exception e) {
            Log.w(TAG, e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, false,
                    e.getMessage() == null ? "clipboard write failed" : e.getMessage());
        }
    }

    @NonNull
    private static String readUtf8Body(@NonNull final InputStream in, final long length)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(length, 8192));
        final byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            final int toRead = (int) Math.min(buffer.length, remaining);
            final int read = in.read(buffer, 0, toRead);
            if (read < 0) {
                throw new IOException("unexpected end of clipboard body");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    @NonNull
    private Response serveStreamUpload(@NonNull final IHTTPSession session) {
        final Map<String, String> headers = session.getHeaders();
        final String lengthHeader = headerValue(headers, "content-length");
        if (lengthHeader == null) {
            return jsonResponse(Response.Status.LENGTH_REQUIRED, false,
                    "Content-Length required");
        }
        final long contentLength;
        final long totalSize;
        final long chunkOffset;
        try {
            contentLength = Long.parseLong(lengthHeader.trim());
            final String sizeHeader = headerValue(headers, "x-file-size");
            final String offsetHeader = headerValue(headers, "x-chunk-offset");
            if (sizeHeader == null || offsetHeader == null) {
                return jsonResponse(Response.Status.BAD_REQUEST, false,
                        "missing X-File-Size or X-Chunk-Offset");
            }
            totalSize = Long.parseLong(sizeHeader.trim());
            chunkOffset = Long.parseLong(offsetHeader.trim());
        } catch (final NumberFormatException e) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "invalid upload size headers");
        }
        if (contentLength < 0 || totalSize < 0 || chunkOffset < 0) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "invalid upload size headers");
        }
        final String mode = headerValue(headers, "x-lan-mode");
        final String batchId = headerValue(headers, "x-upload-batch");
        final String countHeader = headerValue(headers, "x-upload-count");
        if (mode == null || mode.isEmpty() || batchId == null || batchId.isEmpty()
                || countHeader == null) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "missing upload headers");
        }
        final int expected;
        try {
            expected = Integer.parseInt(countHeader.trim());
        } catch (final NumberFormatException e) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "invalid upload count");
        }
        if (expected <= 0 || expected > 512) {
            return jsonResponse(Response.Status.BAD_REQUEST, false, "invalid upload count");
        }
        final String fileName = decodeHeaderFileName(headerValue(headers, "x-file-name"));
        try {
            final LanUploadHandler.ChunkWriteResult chunk = LanUploadHandler.writeChunk(
                    this.mAppContext, batchId, fileName, totalSize, chunkOffset,
                    session.getInputStream(), contentLength);
            if (chunk.offsetMismatch) {
                final JSONObject json = new JSONObject();
                json.put("ok", false);
                json.put("message", "offset mismatch");
                json.put("received", chunk.received);
                json.put("size", chunk.totalSize);
                return newFixedLengthResponse(Response.Status.CONFLICT, MIME_JSON_UTF8,
                        json.toString());
            }
            if (!chunk.fileComplete) {
                final JSONObject json = new JSONObject();
                json.put("ok", true);
                json.put("received", chunk.received);
                json.put("size", chunk.totalSize);
                json.put("fileComplete", false);
                json.put("batchComplete", false);
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON_UTF8, json.toString());
            }
            final LanUploadHandler.SavedFile saved = chunk.savedFile;
            if (saved == null) {
                return jsonResponse(Response.Status.INTERNAL_ERROR, false, "missing saved file");
            }
            UploadBatch batch = this.mUploadBatches.get(batchId);
            if (batch == null) {
                final UploadBatch created = new UploadBatch(mode, expected);
                final UploadBatch existing = this.mUploadBatches.putIfAbsent(batchId, created);
                batch = existing != null ? existing : created;
            }
            synchronized (batch) {
                if (!batch.mode.equals(mode) || batch.expected != expected) {
                    this.mUploadBatches.remove(batchId, batch);
                    return jsonResponse(Response.Status.BAD_REQUEST, false,
                            "upload batch mismatch");
                }
                // Replacing same fileName keeps multi-file resume idempotent when a
                // completed part was re-uploaded after the client reused a batch id.
                boolean replaced = false;
                for (int i = 0; i < batch.files.size(); i++) {
                    if (batch.files.get(i).fileName.equals(saved.fileName)) {
                        batch.files.set(i, saved);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    batch.files.add(saved);
                }
                final JSONObject json = new JSONObject();
                json.put("ok", true);
                json.put("received", chunk.received);
                json.put("size", chunk.totalSize);
                json.put("fileComplete", true);
                if (batch.files.size() < batch.expected) {
                    json.put("batchComplete", false);
                    json.put("message", "uploaded " + batch.files.size() + "/" + batch.expected);
                    return newFixedLengthResponse(Response.Status.OK, MIME_JSON_UTF8,
                            json.toString());
                }
                this.mUploadBatches.remove(batchId, batch);
                final String message = LanUploadHandler.handleSaved(
                        this.mAppContext, batch.mode, new ArrayList<>(batch.files));
                json.put("batchComplete", true);
                json.put("message", message);
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON_UTF8, json.toString());
            }
        } catch (final Exception e) {
            Log.w(TAG, e);
            final String message = e.getMessage() == null ? "upload failed" : e.getMessage();
            return jsonResponse(Response.Status.INTERNAL_ERROR, false, message);
        }
    }

    @NonNull
    private static Response jsonResponse(@NonNull final Response.IStatus status,
                                         final boolean ok,
                                         @Nullable final String message) {
        try {
            final JSONObject json = new JSONObject();
            json.put("ok", ok);
            json.put("message", message == null ? "" : message);
            return newFixedLengthResponse(status, MIME_JSON_UTF8, json.toString());
        } catch (final Exception e) {
            final String safe = message == null ? "" : message;
            final String body = "{\"ok\":" + ok + ",\"message\":\"" + jsonEscape(safe) + "\"}";
            return newFixedLengthResponse(status, MIME_JSON_UTF8, body);
        }
    }

    @NonNull
    private static String jsonEscape(@NonNull final String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    @Nullable
    private static String decodeHeaderFileName(@Nullable final String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (final Exception e) {
            return raw;
        }
    }

    @NonNull
    private Response serveSharedFile(@NonNull final IHTTPSession session,
                                     @NonNull final String path) {
        final ShareTarget target = findTarget(path);
        if (target == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }

        final boolean head = session.getMethod() == HttpMethod.HEAD;
        final Map<String, String> headers = session.getHeaders();
        final String rangeHeader = headerValue(headers, "range");
        ByteRange range = null;
        if (target.seekable && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            range = parseRange(rangeHeader, target.length);
            if (range == null) {
                final Response unsatisfiable = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
                unsatisfiable.addHeader("Content-Range", "bytes */" + target.length);
                addDownloadHeaders(unsatisfiable, target);
                return unsatisfiable;
            }
            final String ifRange = headerValue(headers, "if-range");
            if (ifRange != null && !ifRangeMatches(ifRange, target)) {
                // Entity changed: ignore Range and send full representation.
                range = null;
            }
        }

        final long start = range == null ? 0L : range.start;
        final long end = range == null
                ? Math.max(0L, target.length) - 1
                : range.end;
        final long contentLength = target.length == 0 ? 0L : (end - start + 1);
        try {
            final InputStream inputStream;
            if (head) {
                inputStream = new ByteArrayInputStream(new byte[0]);
            } else {
                inputStream = openStreamAt(target, start);
            }
            final Response.IStatus status = range == null
                    ? Response.Status.OK
                    : Response.Status.PARTIAL_CONTENT;
            final Response response = newFixedLengthResponse(
                    status, contentTypeForResponse(target.mimeType), inputStream, contentLength);
            if (range != null) {
                response.addHeader("Content-Range",
                        "bytes " + start + '-' + end + '/' + target.length);
            }
            addDownloadHeaders(response, target);
            return response;
        } catch (final IOException e) {
            Log.w(TAG, e);
            if (range != null) {
                // Seek failed at request time: browsers/downloaders see no usable range.
                final Response fallback;
                try {
                    final InputStream full = head
                            ? new ByteArrayInputStream(new byte[0])
                            : openStreamAt(target, 0L);
                    fallback = newFixedLengthResponse(
                            Response.Status.OK, contentTypeForResponse(target.mimeType),
                            full, target.length);
                } catch (final IOException openError) {
                    Log.w(TAG, openError);
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT, "Internal Error");
                }
                addDownloadHeaders(fallback, target, false);
                fallback.addHeader("X-Seek-Error", "1");
                return fallback;
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Internal Error");
        }
    }

    @NonNull
    private Response serveLogo() {
        try {
            final byte[] logo = loadLogoBytes();
            final Response response = newFixedLengthResponse(
                    Response.Status.OK, MIME_PNG, new ByteArrayInputStream(logo), logo.length);
            response.addHeader("Cache-Control", "public, max-age=86400");
            return response;
        } catch (final IOException e) {
            Log.e(TAG, "unable to serve logo", e);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }
    }

    @NonNull
    private byte[] loadLogoBytes() throws IOException {
        final byte[] cached = this.mLogoBytes;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.mLogoBytes != null) {
                return this.mLogoBytes;
            }
            try (final InputStream in = this.mAppContext.getAssets().open(LOGO_ASSET);
                 final ByteArrayOutputStream out = new ByteArrayOutputStream(
                         Math.max(4096, in.available()))) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                this.mLogoBytes = out.toByteArray();
                return this.mLogoBytes;
            }
        }
    }

    @NonNull
    private Response serveIndex(@Nullable final String flashMessage, @Nullable final Boolean error) {
        final List<ShareTarget> targets;
        synchronized (LanShareServer.class) {
            targets = new ArrayList<>(this.mTargets.values());
        }
        try {
            String page = loadIndexTemplate();
            final boolean hasFlash = flashMessage != null && !flashMessage.isEmpty();
            final boolean isError = error != null && error;
            page = page.replace("{{PORT}}", String.valueOf(PORT))
                    .replace("{{FLASH_CLASS}}", hasFlash ? (isError ? "err" : "ok") : "ok")
                    .replace("{{FLASH_ATTR}}", hasFlash ? "" : " style=\"display:none\"")
                    .replace("{{FLASH_BODY}}", hasFlash ? escapeHtml(flashMessage) : "")
                    .replace("{{SHARED_LIST}}", buildSharedListHtml(targets));
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML_UTF8, page);
        } catch (final IOException e) {
            Log.e(TAG, "unable to render index", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Index template missing");
        }
    }

    @NonNull
    private String loadIndexTemplate() throws IOException {
        final String cached = this.mIndexTemplate;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.mIndexTemplate != null) {
                return this.mIndexTemplate;
            }
            final String html = readAssetUtf8(INDEX_ASSET);
            final String style = readAssetUtf8(INDEX_STYLE_ASSET);
            final String script = readAssetUtf8(INDEX_SCRIPT_ASSET);
            this.mIndexTemplate = html
                    .replace("{{STYLE}}", style)
                    .replace("{{SCRIPT}}", script)
                    .replace("{{MODE_SHARE}}", LanUploadHandler.MODE_SHARE)
                    .replace("{{MODE_DOWNLOAD}}", LanUploadHandler.MODE_DOWNLOAD)
                    .replace("{{MODE_INSTALL}}", LanUploadHandler.MODE_INSTALL);
            return this.mIndexTemplate;
        }
    }

    @NonNull
    private String readAssetUtf8(@NonNull final String assetPath) throws IOException {
        try (final InputStream in = this.mAppContext.getAssets().open(assetPath);
             final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            final StringBuilder out = new StringBuilder(Math.max(1024, in.available()));
            final char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, read);
            }
            return out.toString();
        }
    }

    @NonNull
    private String buildSharedListHtml(@NonNull final List<ShareTarget> targets) {
        if (targets.isEmpty()) {
            return "<p class=\"empty\" data-i18n=\"shared_empty\">No shared files yet</p>";
        }
        final StringBuilder html = new StringBuilder(512);
        html.append("<ul class=\"file-list\" id=\"shared-list\">");
        for (final ShareTarget target : targets) {
            final String href = PATH_SHARE + target.length + '/' + Uri.encode(target.fileName);
            html.append("<li class=\"file-item\">")
                    .append("<div class=\"file-item-top\"><a href=\"")
                    .append(escapeHtml(href)).append("\">")
                    .append(escapeHtml(target.fileName))
                    .append("</a>")
                    .append("<button type=\"button\" class=\"btn btn-secondary btn-remove\" ")
                    .append("onclick=\"removeShare(this)\" data-i18n=\"share_remove\">Remove</button>")
                    .append("</div><div class=\"meta\">")
                    .append(formatSize(target.length));
            if (target.detail != null && !target.detail.isEmpty()) {
                html.append(" · ").append(escapeHtml(target.detail));
            }
            html.append("</div>");
            if (target.originalPath != null && !target.originalPath.isEmpty()) {
                html.append("<div class=\"meta path\">")
                        .append(escapeHtml(target.originalPath))
                        .append("</div>");
            }
            html.append("</li>");
        }
        html.append("</ul>");
        return html.toString();
    }

    @NonNull
    private static String formatSize(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    @NonNull
    private static String normalizePath(@Nullable final String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path;
    }

    @Nullable
    private ShareTarget findTarget(@NonNull final String path) {
        synchronized (LanShareServer.class) {
            return findTargetLocked(path);
        }
    }

    @Nullable
    private ShareTarget findTargetLocked(@NonNull final String path) {
        final String rest = path.substring(PATH_SHARE.length());
        final int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            return null;
        }
        final String lengthPart = rest.substring(0, slash);
        String namePart = rest.substring(slash + 1);
        final long length;
        try {
            length = Long.parseLong(lengthPart);
        } catch (final NumberFormatException e) {
            return null;
        }
        ShareTarget target = this.mTargets.get(shareKey(length, namePart));
        if (target != null) {
            return target;
        }
        try {
            namePart = URLDecoder.decode(namePart, "UTF-8");
        } catch (final Exception e) {
            return null;
        }
        return this.mTargets.get(shareKey(length, namePart));
    }

    @Nullable
    private ShareTarget removeTargetLocked(@NonNull final String path) {
        final ShareTarget target = findTargetLocked(path);
        if (target == null) {
            return null;
        }
        this.mTargets.remove(shareKey(target.length, target.fileName));
        return target;
    }

    @NonNull
    private static String shareKey(final long length, @NonNull final String fileName) {
        return length + '/' + fileName;
    }

    @NonNull
    private static InputStream openStreamAt(@NonNull final ShareTarget target, final long position)
            throws IOException {
        if (position < 0L || position > target.length) {
            throw new IOException("invalid read position");
        }
        if (target.seekable) {
            // pread does not move the shared file offset, so concurrent Range
            // downloads of different blocks stay isolated.
            return new PreadInputStream(target.pfd.getFileDescriptor(), position,
                    target.length - position);
        }
        if (position > 0L) {
            throw new IOException("seek not supported");
        }
        final ParcelFileDescriptor dup = ParcelFileDescriptor.dup(target.pfd.getFileDescriptor());
        return new ParcelFileDescriptor.AutoCloseInputStream(dup);
    }

    /**
     * Must run on the freshly opened share PFD before it is published for downloads.
     * Probes {@link Os#pread} because that is the concurrent read path.
     */
    private static boolean probeSeekable(@NonNull final ParcelFileDescriptor pfd) {
        try {
            Os.pread(pfd.getFileDescriptor(), new byte[1], 0, 1, 0L);
            return true;
        } catch (final Exception e) {
            Log.i(TAG, "share target is not pread-capable; Accept-Ranges disabled");
            return false;
        }
    }

    @NonNull
    private static String buildEtag(final long length, @NonNull final String fileName,
                                    final long lastModifiedMillis, final long sequence) {
        return '"' + Long.toHexString(length) + '-'
                + Integer.toHexString(fileName.hashCode()) + '-'
                + Long.toHexString(lastModifiedMillis) + '-'
                + Long.toHexString(sequence) + '"';
    }

    @NonNull
    private static String formatHttpDate(final long millis) {
        final SimpleDateFormat format = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(millis));
    }

    private static boolean ifRangeMatches(@NonNull final String ifRange,
                                          @NonNull final ShareTarget target) {
        final String value = ifRange.trim();
        if (value.isEmpty()) {
            return false;
        }
        // If-Range is either an entity-tag or an HTTP-date. Some stacks strip the
        // surrounding quotes from ETag/If-Range, so treat non-date tokens as etags.
        if (isHttpDateIfRange(value)) {
            try {
                final SimpleDateFormat format = new SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                final Date date = format.parse(value);
                if (date == null) {
                    return false;
                }
                // HTTP dates have 1-second resolution.
                return date.getTime() / 1000L == target.lastModifiedMillis / 1000L;
            } catch (final ParseException e) {
                return false;
            }
        }
        return normalizeEtag(value).equals(normalizeEtag(target.etag));
    }

    private static boolean isHttpDateIfRange(@NonNull final String value) {
        if (value.charAt(0) == '"' || value.regionMatches(true, 0, "W/", 0, 2)) {
            return false;
        }
        // IMF-fixdate always contains a comma; bare/stripped etags typically do not.
        return value.indexOf(',') >= 0;
    }

    @NonNull
    private static String normalizeEtag(@NonNull final String etag) {
        String value = etag.trim();
        if (value.regionMatches(true, 0, "W/", 0, 2)) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2
                && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    @Nullable
    private static ByteRange parseRange(@Nullable final String rangeHeader, final long total) {
        if (rangeHeader == null || total <= 0) {
            return null;
        }
        if (!rangeHeader.startsWith("bytes=") || rangeHeader.contains(",")) {
            return null;
        }
        final String spec = rangeHeader.substring("bytes=".length()).trim();
        final int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        final String startText = spec.substring(0, dash).trim();
        final String endText = spec.substring(dash + 1).trim();
        try {
            final long start;
            final long end;
            if (startText.isEmpty()) {
                if (endText.isEmpty()) {
                    return null;
                }
                final long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0L, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= total || end < start) {
                return null;
            }
            return new ByteRange(start, Math.min(end, total - 1));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String headerValue(@Nullable final Map<String, String> headers,
                                      @NonNull final String name) {
        if (headers == null) {
            return null;
        }
        final String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        return headers.get(name.toLowerCase(Locale.US));
    }

    private static void addDownloadHeaders(@NonNull final Response response,
                                           @NonNull final ShareTarget target) {
        addDownloadHeaders(response, target, target.seekable);
    }

    private static void addDownloadHeaders(@NonNull final Response response,
                                           @NonNull final ShareTarget target,
                                           final boolean acceptRanges) {
        response.addHeader("Accept-Ranges", acceptRanges ? "bytes" : "none");
        response.addHeader("ETag", target.etag);
        response.addHeader("Last-Modified", formatHttpDate(target.lastModifiedMillis));
        final String disposition = isBrowserPreviewable(target.mimeType) ? "inline" : "attachment";
        try {
            final String encoded = URLEncoder.encode(target.fileName, "UTF-8").replace("+", "%20");
            response.addHeader("Content-Disposition",
                    disposition + "; filename*=UTF-8''" + encoded);
        } catch (final UnsupportedEncodingException ignored) {
            response.addHeader("Content-Disposition", disposition);
        }
    }

    private static boolean isBrowserPreviewable(@NonNull final String mimeType) {
        final String mime = mimeType.toLowerCase(Locale.US);
        final int semi = mime.indexOf(';');
        final String type = semi >= 0 ? mime.substring(0, semi).trim() : mime.trim();
        return type.startsWith("image/")
                || type.startsWith("video/")
                || type.startsWith("audio/")
                || type.startsWith("text/")
                || "application/pdf".equals(type)
                || "application/json".equals(type)
                || "application/xml".equals(type)
                || type.endsWith("+xml");
    }

    @NonNull
    private static String contentTypeForResponse(@NonNull final String mimeType) {
        final String mime = mimeType.toLowerCase(Locale.US);
        if (mime.startsWith("text/") && !mime.contains("charset=")) {
            return mimeType + "; charset=utf-8";
        }
        return mimeType;
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

    @NonNull
    private static String escapeHtml(@NonNull final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @NonNull
    public static String buildShareUrl(@NonNull final String ip,
                                       @NonNull final String fileName,
                                       final long length) {
        return "http://" + ip + ':' + PORT + PATH_SHARE + length + '/'
                + Uri.encode(fileName);
    }

    @Nullable
    private static String readLinkPath(@NonNull final ParcelFileDescriptor pfd) {
        return FileUriActions.readLink(pfd);
    }

    private static boolean isApkShare(@NonNull final String mimeType,
                                      @NonNull final String fileName) {
        final String mime = mimeType.toLowerCase(Locale.US);
        return "application/vnd.android.package-archive".equals(mime)
                || fileName.toLowerCase(Locale.US).endsWith(".apk");
    }

    @Nullable
    private static String extractShareDetail(@NonNull final Context context,
                                             @NonNull final Uri uri,
                                             @NonNull final ParcelFileDescriptor pfd,
                                             @NonNull final String mimeType,
                                             @NonNull final String fileName) {
        final String mime = mimeType.toLowerCase(Locale.US);
        try {
            if (mime.startsWith("image/") || looksLikeImage(fileName)) {
                return extractImageDetail(pfd);
            }
            if (mime.startsWith("video/") || looksLikeVideo(fileName)) {
                return extractVideoDetail(context, uri, pfd);
            }
        } catch (final Exception e) {
            Log.w(TAG, "unable to extract share detail for " + fileName, e);
        }
        return null;
    }

    private static boolean looksLikeImage(@NonNull final String fileName) {
        final String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    private static boolean looksLikeVideo(@NonNull final String fileName) {
        final String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".3gp") || lower.endsWith(".mov") || lower.endsWith(".avi")
                || lower.endsWith(".m4v");
    }

    @Nullable
    private static String extractImageDetail(@NonNull final ParcelFileDescriptor pfd) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        return options.outWidth + " × " + options.outHeight;
    }

    @Nullable
    private static String extractVideoDetail(@NonNull final Context context,
                                             @NonNull final Uri uri,
                                             @NonNull final ParcelFileDescriptor pfd) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            try {
                retriever.setDataSource(context, uri);
            } catch (final Exception ignored) {
                retriever.setDataSource(pfd.getFileDescriptor());
            }
            final String width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            final String height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            final String durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            final StringBuilder detail = new StringBuilder();
            if (width != null && height != null
                    && !width.isEmpty() && !height.isEmpty()
                    && !"0".equals(width) && !"0".equals(height)) {
                detail.append(width).append(" × ").append(height);
            }
            if (durationMs != null && !durationMs.isEmpty()) {
                try {
                    final long millis = Long.parseLong(durationMs);
                    if (millis > 0L) {
                        if (detail.length() > 0) {
                            detail.append(" · ");
                        }
                        detail.append(formatDuration(millis));
                    }
                } catch (final NumberFormatException ignored) {
                }
            }
            return detail.length() > 0 ? detail.toString() : null;
        } finally {
            try {
                retriever.release();
            } catch (final Exception ignored) {
            }
        }
    }

    @NonNull
    private static String formatDuration(final long durationMs) {
        long totalSeconds = (durationMs + 500L) / 1000L;
        if (totalSeconds < 0L) {
            totalSeconds = 0L;
        }
        final long hours = totalSeconds / 3600L;
        final long minutes = (totalSeconds % 3600L) / 60L;
        final long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    @Nullable
    private static ApkInfo extractApkInfo(@NonNull final Context context,
                                          @NonNull final ParcelFileDescriptor pfd,
                                          @Nullable final String originalPath) {
        try {
            final String archivePath = resolveApkArchivePath(pfd, originalPath);
            if (archivePath == null) {
                return null;
            }
            final PackageManager pm = context.getPackageManager();
            final PackageInfo info = pm.getPackageArchiveInfo(archivePath, 0);
            if (info == null || info.packageName == null || info.packageName.isEmpty()) {
                return null;
            }
            final String versionName = info.versionName == null || info.versionName.isEmpty()
                    ? "?"
                    : info.versionName;
            return new ApkInfo(info.packageName, versionName, info.getLongVersionCode());
        } catch (final Exception e) {
            Log.w(TAG, "unable to extract apk info", e);
            return null;
        }
    }

    @Nullable
    private static String resolveApkArchivePath(@NonNull final ParcelFileDescriptor pfd,
                                                @Nullable final String originalPath) {
        if (isUsableArchivePath(originalPath)) {
            return originalPath;
        }
        final String procPath = "/proc/self/fd/" + pfd.getFd();
        if (isUsableArchivePath(procPath)) {
            return procPath;
        }
        return null;
    }

    private static boolean isUsableArchivePath(@Nullable final String path) {
        try {
            // noinspection DataFlowIssue
            return new File(path).canRead();
        } catch (final Exception e) {
            return false;
        }
    }

    private static void rewindShareFd(@NonNull final ParcelFileDescriptor pfd) {
        try {
            Os.lseek(pfd.getFileDescriptor(), 0L, OsConstants.SEEK_SET);
        } catch (final Exception ignored) {
            // Non-seekable providers cannot rewind; downloads use pread when possible.
        }
    }

    private static void closeQuietly(@Nullable final ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return;
        }
        try {
            pfd.close();
        } catch (final IOException ignored) {
        }
    }

    private static final class UploadBatch {
        @NonNull
        final String mode;
        final int expected;
        @NonNull
        final List<LanUploadHandler.SavedFile> files = new ArrayList<>();

        UploadBatch(@NonNull final String mode, final int expected) {
            this.mode = mode;
            this.expected = expected;
        }
    }

    private static final class ByteRange {
        final long start;
        final long end;

        ByteRange(final long start, final long end) {
            this.start = start;
            this.end = end;
        }
    }

    public static final class ShareStart {
        @NonNull
        public final String fileName;
        public final long length;

        ShareStart(@NonNull final String fileName, final long length) {
            this.fileName = fileName;
            this.length = length;
        }
    }

    private static final class ApkInfo {
        @NonNull
        final String packageName;
        @NonNull
        final String versionName;
        final long versionCode;

        ApkInfo(@NonNull final String packageName, @NonNull final String versionName,
                final long versionCode) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        @NonNull
        String formatDetail() {
            return this.packageName + " · " + this.versionName + " (" + this.versionCode + ")";
        }

        @NonNull
        String buildShareFileName() {
            return sanitizeApkNamePart(this.packageName)
                    + '_' + sanitizeApkNamePart(this.versionName)
                    + '_' + this.versionCode
                    + ".apk";
        }

        @NonNull
        private static String sanitizeApkNamePart(@NonNull final String part) {
            final StringBuilder builder = new StringBuilder(part.length());
            for (int i = 0; i < part.length(); i++) {
                final char c = part.charAt(i);
                if (c <= 0x1f || "\\/:*?\"<>|".indexOf(c) >= 0) {
                    builder.append('_');
                } else {
                    builder.append(c);
                }
            }
            return builder.length() > 0 ? builder.toString() : "unknown";
        }
    }

    private static final class ShareTarget {
        @NonNull
        final String fileName;
        final long length;
        @NonNull
        final String mimeType;
        @Nullable
        final String originalPath;
        @Nullable
        final String detail;
        @NonNull
        final ParcelFileDescriptor pfd;
        final boolean seekable;
        final long lastModifiedMillis;
        @NonNull
        final String etag;

        ShareTarget(@NonNull final String fileName, final long length,
                    @NonNull final String mimeType, @Nullable final String originalPath,
                    @Nullable final String detail,
                    @NonNull final ParcelFileDescriptor pfd, final boolean seekable,
                    final long lastModifiedMillis, @NonNull final String etag) {
            this.fileName = fileName;
            this.length = length;
            this.mimeType = mimeType;
            this.originalPath = originalPath;
            this.detail = detail;
            this.pfd = pfd;
            this.seekable = seekable;
            this.lastModifiedMillis = lastModifiedMillis;
            this.etag = etag;
        }
    }
}
