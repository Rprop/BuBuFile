package rprop.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import rprop.app.video.select.VideoSelectActivity;

public final class ThemeShortcutActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        final int mode = getIntent().getIntExtra(ThemeHelper.EXTRA_MODE, ThemeHelper.MODE_FOLLOW_SYSTEM);
        if (mode != ThemeHelper.getMode(this)) {
            ThemeHelper.setMode(this, mode);
        }
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, VideoSelectActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
