package com.example.passmanager.security;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

/**
 * The one place that decides whether Ledger's windows block screenshots, screen recording
 * and the recent apps preview (FLAG_SECURE). Driven by Security → "Hide screen contents"
 * (stored as STEALTH_MODE); on unless the user has turned it off.
 *
 * Every activity gets it from LedgerApp's lifecycle callbacks. Dialogs and bottom sheets are
 * separate windows, so code that shows one with secrets on it calls {@link #apply(Dialog)}.
 */
public final class ScreenPrivacy {

    private static final String PREFS = "VaultSecurityPrefs";
    private static final String KEY = "STEALTH_MODE";

    private ScreenPrivacy() {}

    public static boolean isOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setOn(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    public static void apply(Activity activity) {
        apply(activity.getWindow(), isOn(activity));
    }

    public static void apply(Dialog dialog) {
        apply(dialog.getWindow(), isOn(dialog.getContext()));
    }

    private static void apply(Window window, boolean on) {
        if (window == null) return;
        if (on) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
