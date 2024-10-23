package rprop.app.video.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.Arrays;

import rprop.app.video.BuildConfig;
import rprop.app.video.R;
import rprop.app.video.trim.VideoTrimListener;

public final class VideoUtils {
    public static final String TAG = "ffmpeg-utils";
    public static final long MIN_SHOOT_DURATION = 3000L;// 最小剪辑时间3s
    public static final int VIDEO_MAX_TIME = 10; // 10秒
    public static final long MAX_SHOOT_DURATION = VIDEO_MAX_TIME * 1000L; //视频最多剪切多长时间10秒

    public static final int MAX_COUNT_RANGE = 10; // seekBar的区域内一共有多少张图片
    private static final int SCREEN_WIDTH_FULL = DisplayUtils.getDeviceWidth();
    public static final int RECYCLER_VIEW_PADDING = DisplayUtils.dpToPx(35);
    public static final int VIDEO_FRAMES_WIDTH = SCREEN_WIDTH_FULL - RECYCLER_VIEW_PADDING * 2;
    public static final int THUMB_WIDTH = (SCREEN_WIDTH_FULL - RECYCLER_VIEW_PADDING * 2) / VIDEO_MAX_TIME;
    private static final int THUMB_HEIGHT = DisplayUtils.dpToPx(50);

    public static void trim(final Context context, final Uri videoUri, final String start, final String end,
                            final boolean precise, final VideoTrimListener callback) {
        callback.onPhase(context.getString(R.string.trimming));

        if (BuildConfig.DEBUG && BuildConfig.VERSION_CODE == 0) {
            try {
                final ContentResolver contentResolver = context.getContentResolver();
                final ParcelFileDescriptor pfd = contentResolver.openFileDescriptor(videoUri, "r");
                if (pfd == null) {
                    callback.onFinished(null);
                    return;
                }
                try (pfd) {
                    final int fd = pfd.getFd();
                    final String inputFile = "/proc/self/fd/" + fd;
                    try {
                        Log.d(TAG, inputFile + ' ' + Os.readlink(inputFile));
                    } catch (final Exception e) {
                        Log.w(TAG, e);
                    }
                    try {
                        Os.close(Os.open(inputFile, OsConstants.O_RDONLY, OsConstants.O_CLOEXEC));
                    } catch (final Exception e) {
                        Log.w(TAG, e);
                    }
                }
            } catch (final Exception e) {
                Log.w(TAG, e);
                callback.onFinished(null);
                return;
            }
        }

        final String name;
        try {
            final DocumentFile documentFile = DocumentFile.fromSingleUri(context, videoUri);
            name = documentFile.getName();
            if (name == null) {
                callback.onFinished(null);
                return;
            }
        } catch (final Exception e) {
            Log.w(TAG, e);
            callback.onFinished(null);
            return;
        }
        final int postfix = name.lastIndexOf('.');
        final String outputName = (postfix > 0 ? name.substring(0, postfix) : name) + '.' +
                start.replace(':', '-') + '_' +
                end.replace(':', '-') +
                ".mp4";
        final File outputFile = new File(context.getCacheDir(), outputName);
        final String inputFile = FFmpegKitConfig.getSafParameterForRead(context, videoUri);
        final String[] arguments;
        if (precise) {
            arguments = new String[]{
                    "-ss", start,
                    "-to", end,
                    "-i", inputFile,
                    "-c:v", "libx264",
                    "-c:a", "aac",
                    "-strict", "experimental",
                    "-b:a", "98k",
                    outputFile.getPath(),
                    "-y"
            };
        } else {
            arguments = new String[]{
                    "-ss", start,
                    "-to", end,
                    "-i", inputFile,
                    "-codec", "copy",
                    outputFile.getPath(),
                    "-y"
            };
        }
        Log.d(TAG, Arrays.toString(arguments));
        FFmpegKit.executeWithArgumentsAsync(arguments, session -> {
            final int returnCode = session.getReturnCode().getValue();
            Log.d(TAG, "ffmpeg ret " + returnCode);

            callback.onFinished(returnCode == ReturnCode.SUCCESS ? outputFile : null);
        });
    }

    public interface ThumbnailCallback {
        void on(final Bitmap bitmap);

        boolean next();
    }

    public static void generateThumbnailsInBackground(final Context context, final Uri videoUri,
                                                      final int thumbnailsCount,
                                                      final long startPosition,
                                                      final long endPosition,
                                                      final ThumbnailCallback callback) {
        final Thread thread = new Thread(() -> {
            try (final MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever()) {
                mediaMetadataRetriever.setDataSource(context, videoUri);
                final int interval = (int) ((endPosition - startPosition) / (thumbnailsCount - 1));
                for (long i = 0; i < thumbnailsCount && callback.next(); ++i) {
                    final long frameTime = startPosition + interval * i;
                    final Bitmap bitmap = mediaMetadataRetriever.getScaledFrameAtTime(
                            frameTime * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMB_WIDTH,
                            THUMB_HEIGHT);
                    if (bitmap != null) {
                        callback.on(bitmap);
                    }
                }
            } catch (final Throwable ignored) {
            }
        }, TAG);
        thread.setDaemon(true);
        thread.start();
    }

    public static String convertSecondsToTime(final long seconds) {
        if (seconds <= 0) {
            return "00:00:00";
        }
        long minute = seconds / 60;
        if (minute < 60) {
            final long second = seconds % 60;
            return "00:" + formatUnit(minute) + ':' + formatUnit(second);
        } else {
            final long hour = minute / 60;
            if (hour > 99) {
                return "99:59:59";
            }
            minute = minute % 60;
            final long second = seconds - hour * 3600 - minute * 60;
            return formatUnit(hour) + ':' + formatUnit(minute) + ':' + formatUnit(second);
        }
    }

    private static String formatUnit(final long i) {
        if (i >= 0 && i < 10) {
            return "0" + i;
        } else {
            return "" + i;
        }
    }
}
