package rprop.app.ui;

import android.app.Activity;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import rp.file.R;

public final class MaterialProgressHelper {
    private MaterialProgressHelper() {
    }

    @NonNull
    public static AlertDialog show(@NonNull final Activity activity, @Nullable final CharSequence message) {
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(R.layout.dialog_material_progress)
                .setCancelable(false)
                .create();
        dialog.show();
        updateMessage(dialog, message);
        return dialog;
    }

    public static void updateMessage(@Nullable final AlertDialog dialog,
                                     @Nullable final CharSequence message) {
        if (dialog == null) {
            return;
        }
        final TextView messageView = dialog.findViewById(R.id.message);
        if (messageView != null) {
            messageView.setText(message);
        }
    }
}
