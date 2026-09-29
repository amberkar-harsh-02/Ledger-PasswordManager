package com.example.passmanager.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PinLockoutTest {

    private static final long SECOND = 1000;
    private static final long HOUR = 60 * 60 * SECOND;

    @Test
    public void firstFourFailuresAreFree() {
        for (int failures = 0; failures < 5; failures++) {
            assertEquals(0, PinLockout.lockoutForFailures(failures));
        }
    }

    @Test
    public void lockoutDoublesFromThirtySeconds() {
        assertEquals(30_000, PinLockout.lockoutForFailures(5));
        assertEquals(60_000, PinLockout.lockoutForFailures(6));
        assertEquals(120_000, PinLockout.lockoutForFailures(7));
    }

    @Test
    public void lockoutIsCappedAtOneHour() {
        assertEquals(HOUR, PinLockout.lockoutForFailures(12));
        assertEquals(HOUR, PinLockout.lockoutForFailures(1000));
    }

    // --- Clock handling (#4: the lockout must not be skippable by changing the phone's clock) ---

    @Test
    public void fifthWrongPinLocksForThirtySeconds() {
        FakeClock clock = new FakeClock();
        FakePrefs prefs = new FakePrefs();
        failTimes(prefs, clock, 5);
        assertEquals(30 * SECOND, PinLockout.remainingMs(prefs, clock));

        clock.elapsed += 10 * SECOND;
        assertEquals(20 * SECOND, PinLockout.remainingMs(prefs, clock));

        clock.elapsed += 20 * SECOND;
        assertEquals(0, PinLockout.remainingMs(prefs, clock));
    }

    @Test
    public void movingTheWallClockForwardDoesNothing() {
        FakeClock clock = new FakeClock();
        FakePrefs prefs = new FakePrefs();
        failTimes(prefs, clock, 5);

        clock.wall += 24 * HOUR; // Settings → Date & time → a day ahead
        assertEquals(30 * SECOND, PinLockout.remainingMs(prefs, clock));
    }

    @Test
    public void rebootingRestartsTheWaitInsteadOfEndingIt() {
        FakeClock clock = new FakeClock();
        FakePrefs prefs = new FakePrefs();
        failTimes(prefs, clock, 6); // 60 s lockout
        clock.elapsed += 50 * SECOND;

        // Reboot: time since boot restarts near zero, boot count goes up
        clock.boot++;
        clock.elapsed = 5 * SECOND;
        assertEquals(60 * SECOND, PinLockout.remainingMs(prefs, clock));

        clock.elapsed += 30 * SECOND;
        assertEquals(30 * SECOND, PinLockout.remainingMs(prefs, clock));
    }

    @Test
    public void rebootIsDetectedEvenWithoutABootCount() {
        FakeClock clock = new FakeClock();
        clock.boot = -1; // boot count unreadable on this device
        FakePrefs prefs = new FakePrefs();
        clock.elapsed = 10 * 60 * SECOND;
        failTimes(prefs, clock, 5);

        clock.elapsed = 2 * SECOND; // boot-time clock went backwards: a reboot
        assertEquals(30 * SECOND, PinLockout.remainingMs(prefs, clock));
    }

    @Test
    public void correctPinClearsEverything() {
        FakeClock clock = new FakeClock();
        FakePrefs prefs = new FakePrefs();
        failTimes(prefs, clock, 7);
        PinLockout.clear(prefs);
        assertEquals(0, PinLockout.remainingMs(prefs, clock));
        assertEquals(0, PinLockout.registerFailure(prefs, clock)); // counting starts again from one
    }

    @Test
    public void lockoutFromAnOlderBuildCarriesOver() {
        FakeClock clock = new FakeClock();
        FakePrefs prefs = new FakePrefs();
        prefs.values.put("PIN_LOCKOUT_UNTIL", clock.wall + 45 * SECOND); // old wall-clock deadline
        assertEquals(45 * SECOND, PinLockout.remainingMs(prefs, clock));
        assertTrue(!prefs.values.containsKey("PIN_LOCKOUT_UNTIL"));

        clock.wall += HOUR; // and from now on the wall clock no longer matters
        assertEquals(45 * SECOND, PinLockout.remainingMs(prefs, clock));
    }

    // --- helpers ---

    private static void failTimes(FakePrefs prefs, FakeClock clock, int times) {
        for (int i = 0; i < times; i++) PinLockout.registerFailure(prefs, clock);
    }

    private static final class FakeClock implements PinLockout.Clock {
        long elapsed = 3_600_000;
        long wall = 1_790_640_000_000L;
        int boot = 7;

        @Override public long elapsedRealtime() { return elapsed; }
        @Override public int bootCount() { return boot; }
        @Override public long currentTimeMillis() { return wall; }
    }

    /** Minimal in-memory SharedPreferences (apply() writes immediately). */
    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String def) { Object v = values.get(key); return v != null ? (String) v : def; }
        @Override public Set<String> getStringSet(String key, Set<String> def) { return def; }
        @Override public int getInt(String key, int def) { Object v = values.get(key); return v != null ? (Integer) v : def; }
        @Override public long getLong(String key, long def) { Object v = values.get(key); return v != null ? (Long) v : def; }
        @Override public float getFloat(String key, float def) { Object v = values.get(key); return v != null ? (Float) v : def; }
        @Override public boolean getBoolean(String key, boolean def) { Object v = values.get(key); return v != null ? (Boolean) v : def; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> puts = new HashMap<>();
                final Set<String> removes = new HashSet<>();
                boolean clear = false;

                @Override public Editor putString(String k, String v) { puts.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { puts.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { puts.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { puts.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { puts.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { puts.put(k, v); return this; }
                @Override public Editor remove(String k) { removes.add(k); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }

                @Override
                public void apply() {
                    if (clear) values.clear();
                    for (String k : removes) values.remove(k);
                    values.putAll(puts);
                }
            };
        }
    }
}
