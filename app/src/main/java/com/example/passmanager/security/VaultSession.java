package com.example.passmanager.security;

/**
 * In-memory state of the current vault session, shared by every screen and the autofill service.
 *
 * - Unlocked: set by the lock screen. Lives only as long as the process, so a process that Android
 *   restores after killing it starts out locked.
 * - Duress: whether that unlock used the duress PIN. Never written to disk.
 * - Auto-lock: when the whole app went to the background, and whether the user must confirm it's
 *   them before a protected screen shows again (see LedgerApp and ProtectedActivity).
 */
public final class VaultSession {

    private static volatile boolean unlocked = false;
    private static volatile boolean duress = false;

    private static volatile boolean inBackground = false;
    private static volatile long backgroundedAt = 0;
    private static volatile boolean lockPending = false;
    private static volatile long expectedReturnUntil = 0;

    /** How long an intentional trip to another app (file picker, share sheet, settings) stays exempt. */
    private static final long EXPECTED_RETURN_WINDOW_MS = 5 * 60 * 1000;

    private VaultSession() {}

    public static void onUnlocked(boolean isDuress) {
        unlocked = true;
        duress = isDuress;
        lockPending = false;
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    /** True while the decoy vault from a duress PIN is open: autofill must offer nothing. */
    public static boolean isDuress() {
        return duress;
    }

    // --- Auto-lock ---

    /** The last visible Ledger screen went away (home, recents, another app, screen off). */
    public static void onAppBackground(long now) {
        inBackground = true;
        backgroundedAt = now;
    }

    /**
     * A Ledger screen became visible again.
     * @param timeoutMs the Auto-lock setting: 0 = immediately, -1 = never
     */
    public static void onAppForeground(long now, long timeoutMs) {
        boolean wasAway = inBackground;
        inBackground = false;
        if (!unlocked || !wasAway) return; // fresh start, or never unlocked: nothing to re-check
        long awayFrom = backgroundedAt;

        // The user left on purpose to pick a file, share, or change a system setting
        if (now <= expectedReturnUntil) {
            expectedReturnUntil = 0;
            return;
        }
        if (timeoutMs != -1 && now - awayFrom >= timeoutMs) {
            lockPending = true;
        }
    }

    /** Call just before sending the user to another app that will bring them straight back. */
    public static void expectReturn() {
        expectedReturnUntil = System.currentTimeMillis() + EXPECTED_RETURN_WINDOW_MS;
    }

    public static boolean isLockPending() {
        return lockPending;
    }

    /** The user confirmed it's them on a protected screen. */
    public static void onReauthenticated() {
        lockPending = false;
    }
}
