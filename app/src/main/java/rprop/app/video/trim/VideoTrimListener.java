package rprop.app.video.trim;

import java.io.File;

public interface VideoTrimListener {
    void onPhase(final String desc);

    void onFinished(final File outputFile);

    void onCanceled();
}
