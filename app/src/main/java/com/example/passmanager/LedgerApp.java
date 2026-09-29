package com.example.passmanager;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.passmanager.security.ScreenPrivacy;
import com.example.passmanager.security.VaultSession;

/**
 * Tells VaultSession when the app as a whole goes to the background and comes back.
 * Counting started screens means moving between Ledger's own screens (Vault → Edit → scanner)
 * never counts as leaving; only home, recents, another app or the screen turning off does.
 * Also applies the "Hide screen contents" setting to every screen (ScreenPrivacy).
 */
public class LedgerApp extends Application {

    private int startedActivities = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        // PIN lockout counts time since boot (not the changeable wall clock) and needs the boot count
        com.example.passmanager.security.PinLockout.install(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (startedActivities++ == 0) {
                    long timeout = getSharedPreferences("VaultSecurityPrefs", MODE_PRIVATE).getLong("AUTO_LOCK_TIMEOUT", 0);
                    VaultSession.onAppForeground(System.currentTimeMillis(), timeout);
                }
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                // A configuration change (e.g. rotation) stops and restarts the same screen: not leaving
                if (--startedActivities == 0 && !activity.isChangingConfigurations()) {
                    VaultSession.onAppBackground(System.currentTimeMillis());
                }
            }

            // "Hide screen contents" for every screen: set on creation (before the first frame and
            // any recents snapshot), and again on resume so a screen further back picks up a change
            // made in Security while it was covered.
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                ScreenPrivacy.apply(activity);
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                ScreenPrivacy.apply(activity);
            }

            @Override public void onActivityPaused(@NonNull Activity activity) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
            @Override public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }
}
