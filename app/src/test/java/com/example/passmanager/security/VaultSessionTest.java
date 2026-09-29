package com.example.passmanager.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class VaultSessionTest {

    private static final long MINUTE = 60_000;

    @Before
    public void freshUnlockedSession() {
        VaultSession.onUnlocked(false); // unlocked, nothing pending
    }

    @Test
    public void immediateLocksOnAnyReturn() {
        VaultSession.onAppBackground(1_000);
        VaultSession.onAppForeground(1_001, 0);
        assertTrue(VaultSession.isLockPending());
    }

    @Test
    public void timeoutOnlyAfterTheChosenTime() {
        VaultSession.onAppBackground(0);
        VaultSession.onAppForeground(MINUTE - 1, MINUTE);
        assertFalse(VaultSession.isLockPending());

        VaultSession.onAppBackground(0);
        VaultSession.onAppForeground(MINUTE, MINUTE);
        assertTrue(VaultSession.isLockPending());
    }

    @Test
    public void neverMeansNever() {
        VaultSession.onAppBackground(0);
        VaultSession.onAppForeground(24 * 60 * MINUTE, -1);
        assertFalse(VaultSession.isLockPending());
    }

    @Test
    public void reauthenticatingClearsTheLock() {
        VaultSession.onAppBackground(0);
        VaultSession.onAppForeground(MINUTE, 0);
        VaultSession.onReauthenticated();
        assertFalse(VaultSession.isLockPending());
    }

    @Test
    public void aLockStaysPendingUntilReauthenticated() {
        // Coming back twice without unlocking must not clear the lock
        VaultSession.onAppBackground(0);
        VaultSession.onAppForeground(MINUTE, 0);
        VaultSession.onAppBackground(MINUTE + 1);
        VaultSession.onAppForeground(MINUTE + 2, 5 * MINUTE);
        assertTrue(VaultSession.isLockPending());
    }

    @Test
    public void startingTheAppIsNotReturning() {
        // First screen of a fresh process: nothing was backgrounded
        VaultSession.onAppForeground(MINUTE, 0);
        assertFalse(VaultSession.isLockPending());
    }

    @Test
    public void intentionalTripOutIsExemptOnce() {
        long now = System.currentTimeMillis();
        VaultSession.expectReturn();                 // e.g. opening the file picker
        VaultSession.onAppBackground(now);
        VaultSession.onAppForeground(now + MINUTE, 0);
        assertFalse(VaultSession.isLockPending());

        VaultSession.onAppBackground(now + MINUTE);  // the next ordinary trip out locks again
        VaultSession.onAppForeground(now + 2 * MINUTE, 0);
        assertTrue(VaultSession.isLockPending());
    }

    @Test
    public void unlockingWithTheLockScreenClearsAnyPendingLock() {
        VaultSession.onAppBackground(0);
        VaultSession.onAppForeground(MINUTE, 0);
        VaultSession.onUnlocked(false);
        assertFalse(VaultSession.isLockPending());
        assertTrue(VaultSession.isUnlocked());
    }
}
