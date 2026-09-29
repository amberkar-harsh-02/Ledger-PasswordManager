package com.example.passmanager.security;

import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TotpEngine {

    /**
     * Generates a 6-digit TOTP code for the current 30-second window.
     * @param secretKey The Base32 secret provided by the website.
     * @return A 6-digit string (e.g., "045291").
     */
    public static String generateTOTP(String secretKey) {
        return generateTOTP(secretKey, System.currentTimeMillis());
    }

    /** Same as {@link #generateTOTP(String)} for a given moment in time (used by tests). */
    public static String generateTOTP(String secretKey, long timeMillis) {
        if (secretKey == null || secretKey.trim().isEmpty()) return "------";

        try {
            // 1. Clean the key (remove spaces, force uppercase for standard Base32)
            String normalizedBase32Key = secretKey.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
            byte[] bytes = decodeBase32(normalizedBase32Key);

            // 2. Get the current Unix time divided into 30-second windows
            long timeIndex = timeMillis / 1000 / 30;

            // 3. Convert that time index into an 8-byte array
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putLong(timeIndex);
            byte[] timeBytes = buffer.array();

            // 4. Hash the time against the secret key using HMAC-SHA1
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(bytes, "HmacSHA1"));
            byte[] hash = mac.doFinal(timeBytes);

            // 5. Dynamic Truncation (Extract the exact 6 digits from the 20-byte hash)
            int offset = hash[hash.length - 1] & 0xf;
            int binary =
                    ((hash[offset] & 0x7f) << 24) |
                            ((hash[offset + 1] & 0xff) << 16) |
                            ((hash[offset + 2] & 0xff) << 8) |
                            (hash[offset + 3] & 0xff);

            int otp = binary % 1000000;

            // 6. Format as exactly 6 digits, padding with leading zeros if necessary
            return String.format(java.util.Locale.ROOT, "%06d", otp);

        } catch (Exception e) {
            // Not logged: the decoder message can hold part of the secret
            return "ERROR";
        }
    }

    /**
     * True if the text looks like a usable Base32 2FA secret: letters A-Z and digits 2-7
     * (spaces, lowercase and trailing '=' padding allowed), at least 8 characters.
     */
    public static boolean isValidSecret(String secretKey) {
        if (secretKey == null) return false;
        String normalized = secretKey.replace(" ", "").toUpperCase(java.util.Locale.ROOT).replaceAll("=+$", "");
        return normalized.length() >= 8 && normalized.matches("[A-Z2-7]+");
    }

    /**
     * Calculates exactly how many seconds are left before the code changes.
     * We will use this to animate the progress circle in the UI!
     */
    public static int getRemainingSeconds() {
        long currentSeconds = System.currentTimeMillis() / 1000;
        return (int) (30 - (currentSeconds % 30));
    }

    /**
     * Custom lightweight Base32 Decoder.
     * Standard 2FA secrets use a specific 32-character alphabet (A-Z, 2-7).
     */
    private static byte[] decodeBase32(String base32) {
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;
        byte[] result = new byte[(base32.length() * 5) / 8];

        for (char c : base32.toCharArray()) {
            if (c == '=') break; // Ignore padding characters
            int val = base32Chars.indexOf(c);
            if (val < 0) throw new IllegalArgumentException("Invalid Base32 character found: " + c);

            buffer = (buffer << 5) | val;
            bitsLeft += 5;

            if (bitsLeft >= 8) {
                result[count++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return result;
    }
}