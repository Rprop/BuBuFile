package rprop.app.video.play;

import android.net.Uri;

public interface VideoPlayListener {
    void onBack();

    void onTrim(Uri videoUri);
}
