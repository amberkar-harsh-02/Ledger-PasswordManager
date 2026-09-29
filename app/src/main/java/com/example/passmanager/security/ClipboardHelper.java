package com.example.passmanager.security;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

public class ClipboardHelper {

    // Same value as ClipDescription.EXTRA_IS_SENSITIVE (API 33); spelled out so it works on API 31-32 too.
    private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final long CLEAR_DELAY_MS = 45_000;

    /**
     * Copies a secret to the clipboard, hides it from the system clipboard preview,
     * and wipes it after 45 seconds unless something else has been copied since.
     */
    public static void copySensitive(Context context, String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getApplicationContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;

        ClipData clip = ClipData.newPlainText(label, text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ClipData current = null;
            try {
                current = clipboard.getPrimaryClip();
            } catch (SecurityException ignored) {
                // Background apps can't read the clipboard.
            }
            // Unreadable (we're in the background): clear anyway so the secret never lingers.
            // Readable: clear only if our secret is still the current clip.
            if (current == null
                    || (current.getItemCount() > 0 && text.contentEquals(current.getItemAt(0).getText()))) {
                clipboard.clearPrimaryClip();
            }
        }, CLEAR_DELAY_MS);
    }
}
