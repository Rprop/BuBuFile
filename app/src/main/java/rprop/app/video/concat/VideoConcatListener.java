package rprop.app.video.concat;

import java.io.File;

public interface VideoConcatListener {
    void onPhase(final String desc);

    void onFinished(final File outputFile);
}
