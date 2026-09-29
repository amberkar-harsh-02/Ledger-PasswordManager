package com.example.passmanager.ui.main;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.passmanager.R;
import com.google.android.material.snackbar.Snackbar;

/**
 * One way to tell the user something happened.
 * Inside the app: a Material snackbar, lifted above the FAB or navigation bar so it never covers them.
 * With no visible screen (activity gone): falls back to a toast.
 * A message can also be left "pending" for the vault screen, e.g. "GitHub saved" after the
 * Add login screen closes.
 */
public final class Messages {

    private static CharSequence pending;

    private Messages() {}

    public static void show(@Nullable Context context, CharSequence text) {
        show(context, text, false);
    }

    public static void show(@Nullable Context context, int textRes) {
        if (context != null) show(context, context.getString(textRes), false);
    }

    public static void showLong(@Nullable Context context, CharSequence text) {
        show(context, text, true);
    }

    public static void showLong(@Nullable Context context, int textRes) {
        if (context != null) show(context, context.getString(textRes), true);
    }

    private static void show(@Nullable Context context, CharSequence text, boolean isLong) {
        if (context == null) return;
        Activity activity = context instanceof Activity ? (Activity) context : null;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Toast.makeText(context.getApplicationContext(), text, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }

        View root = activity.findViewById(R.id.main_root);
        if (root == null) root = activity.findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, text, isLong ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT);
        View anchor = anchorFor(activity);
        if (anchor != null) snackbar.setAnchorView(anchor);
        snackbar.show();
    }

    /** Snackbar with an action (e.g. Undo). Same placement rules as {@link #show}. */
    public static void showWithAction(Activity activity, CharSequence text, int actionRes, View.OnClickListener action) {
        View root = activity.findViewById(R.id.main_root);
        if (root == null) root = activity.findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAction(actionRes, action);
        View anchor = anchorFor(activity);
        if (anchor != null) snackbar.setAnchorView(anchor);
        snackbar.show();
    }

    // Sit above whichever floating control is showing: a FAB, the form's save button, or the nav bar
    @Nullable
    private static View anchorFor(Activity activity) {
        int[] candidates = {R.id.fab_add_password, R.id.fab_add_auth, R.id.button_save_credential, R.id.bottom_navigation};
        for (int id : candidates) {
            View view = activity.findViewById(id);
            if (view != null && view.isShown()) return view;
        }
        return null;
    }

    /** Leave a message for the vault screen to show when it's next visible. */
    public static void setPending(CharSequence text) {
        pending = text;
    }

    @Nullable
    public static CharSequence takePending() {
        CharSequence text = pending;
        pending = null;
        return text;
    }
}
