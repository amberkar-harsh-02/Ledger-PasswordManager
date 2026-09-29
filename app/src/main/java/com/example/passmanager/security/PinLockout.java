package com.example.passmanager.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Brute-force lockout for the 4-digit master PIN, shared by every screen that checks it.
 * Free attempts first, then 30s, doubling each further failure, capped at 1 hour.
 *
 * Time is measured with the time since boot ({@link SystemClock#elapsedRealtime()}), which the
 * user can't change, instead of the wall clock, which anyone holding the phone can move forward in
 * Settings to skip a lockout. That clock restarts at every boot, so the lockout also records the
 * boot count: after a reboot the remaining wait starts over in full (rebooting never shortens it).
 */
public final class PinLockout {

    private static final int MAX_FREE_ATTEMPTS = 5;
    private static final long BASE_LOCKOUT_MS = 30_000;
    private static final long MAX_LOCKOUT_MS = 60L * 60 * 1000;

    private static final String KEY_FAILED_COUNT = "FAILED_PIN_COUNT";
    private static final String KEY_UNTIL_ELAPSED = "PIN_LOCKOUT_UNTIL_ELAPSED";
    private static final String KEY_BOOT = "PIN_LOCKOUT_BOOT";
    private static final String KEY_DURATION = "PIN_LOCKOUT_DURATION";
    /** Older builds stored a wall-clock deadline here; read once, then replaced. */
    private static final String LEGACY_KEY_UNTIL_WALL = "PIN_LOCKOUT_UNTIL";

    /** Time sources; swapped out in unit tests. */
    public interface Clock {
        /** Milliseconds since boot, including sleep. Can't be changed by the user. */
        long elapsedRealtime();

        /** Increases by one on every boot. */
        int bootCount();

        /** Wall-clock time; only used to carry over lockouts written by older builds. */
        long currentTimeMillis();
    }

    private static volatile Clock clock = new Clock() {
        @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
        @Override public int bootCount() { return -1; } // unknown until install() is called
        @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
    };

    private PinLockout() {}

    /** Called once from LedgerApp so the boot count can be read. */
    public static void install(Context context) {
        Context app = context.getApplicationContext();
        clock = new Clock() {
            @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
            @Override public int bootCount() {
                return Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            }
            @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
        };
    }

    /** Lockout that applies after the given number of consecutive failures (0 if none). */
    public static long lockoutForFailures(int failures) {
        if (failures < MAX_FREE_ATTEMPTS) return 0;
        int doublings = Math.min(failures - MAX_FREE_ATTEMPTS, 7); // 30s * 2^7 = 64 min, capped below
        return Math.min(BASE_LOCKOUT_MS << doublings, MAX_LOCKOUT_MS);
    }

    /** Milliseconds left on the current lockout (0 or less when PIN entry is allowed). */
    public static long remainingMs(SharedPreferences prefs) {
        return remainingMs(prefs, clock);
    }

    public static long remainingMs(SharedPreferences prefs, Clock clock) {
        migrateLegacy(prefs, clock);

        long duration = prefs.getLong(KEY_DURATION, 0);
        if (duration <= 0) return 0;

        long now = clock.elapsedRealtime();
        int boot = clock.bootCount();
        long until = prefs.getLong(KEY_UNTIL_ELAPSED, 0);
        int lockedInBoot = prefs.getInt(KEY_BOOT, Integer.MIN_VALUE);

        // Rebooted since the lockout started (or the boot count can't be read and the boot-time
        // clock went backwards): the old deadline means nothing now, so start the wait again
        boolean rebooted = (boot != -1 && boot != lockedInBoot) || now < until - duration;
        if (rebooted) {
            until = now + duration;
            prefs.edit().putLong(KEY_UNTIL_ELAPSED, until).putInt(KEY_BOOT, boot).apply();
        }

        long remaining = Math.min(until - now, duration); // never longer than the lockout itself
        if (remaining <= 0) {
            prefs.edit().remove(KEY_DURATION).remove(KEY_UNTIL_ELAPSED).remove(KEY_BOOT).apply();
            return 0;
        }
        return remaining;
    }

    /** Records a wrong PIN. Returns the lockout now in force (0 if none). */
    public static long registerFailure(SharedPreferences prefs) {
        return registerFailure(prefs, clock);
    }

    public static long registerFailure(SharedPreferences prefs, Clock clock) {
        migrateLegacy(prefs, clock);
        int failures = prefs.getInt(KEY_FAILED_COUNT, 0) + 1;
        long lockoutMs = lockoutForFailures(failures);
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_FAILED_COUNT, failures);
        if (lockoutMs > 0) {
            editor.putLong(KEY_DURATION, lockoutMs)
                    .putLong(KEY_UNTIL_ELAPSED, clock.elapsedRealtime() + lockoutMs)
                    .putInt(KEY_BOOT, clock.bootCount());
        }
        editor.apply();
        return lockoutMs;
    }

    public static void clear(SharedPreferences prefs) {
        prefs.edit()
                .remove(KEY_FAILED_COUNT)
                .remove(KEY_DURATION)
                .remove(KEY_UNTIL_ELAPSED)
                .remove(KEY_BOOT)
                .remove(LEGACY_KEY_UNTIL_WALL)
                .apply();
    }

    // A wall-clock deadline from an older build becomes a boot-clock lockout for the time that was left
    private static void migrateLegacy(SharedPreferences prefs, Clock clock) {
        if (!prefs.contains(LEGACY_KEY_UNTIL_WALL)) return;
        long left = prefs.getLong(LEGACY_KEY_UNTIL_WALL, 0) - clock.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit().remove(LEGACY_KEY_UNTIL_WALL);
        if (left > 0) {
            long duration = Math.min(left, MAX_LOCKOUT_MS);
            editor.putLong(KEY_DURATION, duration)
                    .putLong(KEY_UNTIL_ELAPSED, clock.elapsedRealtime() + duration)
                    .putInt(KEY_BOOT, clock.bootCount());
        }
        editor.apply();
    }
}
