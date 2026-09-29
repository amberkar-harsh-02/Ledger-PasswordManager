package com.example.passmanager.security;

import android.util.Pair;

/**
 * Encrypts 2FA (TOTP) secrets at rest with the same Keystore key as passwords.
 *
 * Stored form: "enc1:<base64 iv>:<base64 ciphertext>". Values without the prefix are
 * legacy plaintext secrets; {@link #reveal(String)} passes them through unchanged so
 * nothing breaks before the one-time upgrade has run.
 */
public class TotpSecretCodec {

    private static final String PREFIX = "enc1:";

    public static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static String seal(String plainSecret) throws Exception {
        if (plainSecret == null || plainSecret.trim().isEmpty() || isSealed(plainSecret)) return plainSecret;
        Pair<String, String> encrypted = EncryptionUtil.encryptPassword(plainSecret);
        return PREFIX + encrypted.second + ":" + encrypted.first;
    }

    /** Returns the plaintext secret, or null if it can't be decrypted. */
    public static String reveal(String stored) {
        if (!isSealed(stored)) return stored;
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) return null;
            return EncryptionUtil.decryptPassword(parts[1], parts[0]);
        } catch (Exception e) {
            return null;
        }
    }
}
