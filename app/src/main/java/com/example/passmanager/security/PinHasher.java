package com.example.passmanager.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashes and verifies the master/duress PIN.
 *
 * Current format: "v2$<iterations>$<base64 salt>$<base64 hash>" (PBKDF2-HMAC-SHA256, random salt).
 * Legacy format:  base64(SHA-256("LedgerVaultSalt2026" + pin)). Still verified so existing
 * users can unlock; callers should re-hash with {@link #hash(String)} when {@link #needsUpgrade(String)}.
 *
 * Uses java.util.Base64 (not android.util.Base64) so it runs in plain JVM unit tests.
 * Both produce identical output for NO_WRAP standard Base64.
 */
public class PinHasher {

    private static final String PREFIX = "v2";
    private static final int ITERATIONS = 150_000;
    private static final int SALT_LENGTH = 16;
    private static final int HASH_BITS = 256;
    private static final String LEGACY_SALT = "LedgerVaultSalt2026";

    public static String hash(String pin) {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public static boolean verify(String pin, String stored) {
        if (pin == null || stored == null || stored.isEmpty()) return false;

        if (stored.startsWith(PREFIX + "$")) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                return MessageDigest.isEqual(expected, pbkdf2(pin, salt, iterations));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        byte[] expected;
        try {
            expected = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, legacyHash(pin));
    }

    /**
     * A duress-PIN slot that no PIN can open: the PBKDF2 hash of 32 random characters.
     * Stored whenever the user hasn't set a duress PIN, so the settings file looks the same
     * with or without one (someone inspecting it can't tell a duress PIN exists).
     */
    public static String decoyHash() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        return hash(Base64.getEncoder().encodeToString(random));
    }

    public static boolean needsUpgrade(String stored) {
        return stored != null && !stored.isEmpty() && !stored.startsWith(PREFIX + "$");
    }

    private static byte[] pbkdf2(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static byte[] legacyHash(String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LEGACY_SALT.getBytes(StandardCharsets.UTF_8));
            return digest.digest(pin.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
