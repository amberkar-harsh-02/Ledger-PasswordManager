package com.example.passmanager.security;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computers (Ledger extension installs) the user chose to remember, keyed by the install's
 * public-key fingerprint from {@link BridgeTrust}. Only public information is stored here:
 * a fingerprint, the name the user gave it, and when it was last used.
 */
public final class KnownComputers {

    private static final String PREFS = "LedgerKnownComputers";

    public static final class Computer {
        public final String fingerprint;
        public final String name;
        public final long lastUsed;

        Computer(String fingerprint, String name, long lastUsed) {
            this.fingerprint = fingerprint;
            this.name = name;
            this.lastUsed = lastUsed;
        }
    }

    private final SharedPreferences prefs;

    public KnownComputers(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public Computer get(@Nullable String fingerprint) {
        if (fingerprint == null) return null;
        String raw = prefs.getString(fingerprint, null);
        if (raw == null) return null;
        try {
            JSONObject json = new JSONObject(raw);
            return new Computer(fingerprint, json.getString("name"), json.optLong("lastUsed", 0));
        } catch (Exception e) {
            return null;
        }
    }

    public void remember(String fingerprint, String name) {
        save(fingerprint, name, System.currentTimeMillis());
    }

    public void markUsed(String fingerprint) {
        Computer computer = get(fingerprint);
        if (computer != null) save(fingerprint, computer.name, System.currentTimeMillis());
    }

    public void rename(String fingerprint, String name) {
        Computer computer = get(fingerprint);
        if (computer != null) save(fingerprint, name, computer.lastUsed);
    }

    public void forget(String fingerprint) {
        prefs.edit().remove(fingerprint).apply();
    }

    /** Most recently used first. */
    public List<Computer> list() {
        List<Computer> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Computer computer = get(entry.getKey());
            if (computer != null) result.add(computer);
        }
        result.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return result;
    }

    private void save(String fingerprint, String name, long lastUsed) {
        try {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("lastUsed", lastUsed);
            prefs.edit().putString(fingerprint, json.toString()).apply();
        } catch (Exception ignored) {
            // JSONObject.put only throws for non-finite numbers
        }
    }
}
