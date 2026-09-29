package com.example.passmanager.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class BackupEncryptionUtil {

    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 256;

    // v2 file layout: [MAGIC "LDG2"] + [ITERATIONS int32] + [SALT] + [IV] + [CIPHERTEXT]
    // The header (magic + iterations) is bound to the ciphertext as GCM associated data.
    private static final byte[] MAGIC_V2 = {'L', 'D', 'G', '2'};
    static final int ITERATION_COUNT_V2 = 600_000; // OWASP 2023 guidance for PBKDF2-HMAC-SHA256
    private static final int MAX_ITERATIONS = 10_000_000; // Guards against a crafted file stalling the app

    // v1 (legacy) file layout: [SALT] + [IV] + [CIPHERTEXT], fixed 100k iterations
    static final int ITERATION_COUNT_V1 = 100_000;

    /**
     * Encrypts the raw JSON data of the Vault using a user-provided password.
     * Always writes the v2 format.
     */
    public static byte[] encryptBackup(String plainJson, String password) throws Exception {
        return encryptBackup(plainJson, password, ITERATION_COUNT_V2);
    }

    static byte[] encryptBackup(String plainJson, String password, int iterations) throws Exception {
        SecureRandom random = new SecureRandom();

        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        byte[] header = ByteBuffer.allocate(MAGIC_V2.length + 4).put(MAGIC_V2).putInt(iterations).array();

        SecretKey secretKey = deriveKey(password, salt, iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
        cipher.updateAAD(header);
        byte[] cipherText = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));

        return ByteBuffer.allocate(header.length + salt.length + iv.length + cipherText.length)
                .put(header).put(salt).put(iv).put(cipherText)
                .array();
    }

    /**
     * Decrypts a `.ledger` backup file back into the raw JSON string.
     * Reads both the v2 format and legacy v1 files.
     */
    public static String decryptBackup(byte[] encryptedData, String password) throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedData);

        int iterations = ITERATION_COUNT_V1;
        byte[] header = null;
        if (hasV2Magic(encryptedData)) {
            byteBuffer.position(MAGIC_V2.length);
            iterations = byteBuffer.getInt();
            if (iterations <= 0 || iterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("Invalid backup header");
            }
            header = new byte[MAGIC_V2.length + 4];
            System.arraycopy(encryptedData, 0, header, 0, header.length);
        }

        byte[] salt = new byte[SALT_LENGTH];
        byteBuffer.get(salt);
        byte[] iv = new byte[IV_LENGTH];
        byteBuffer.get(iv);
        byte[] cipherText = new byte[byteBuffer.remaining()];
        byteBuffer.get(cipherText);

        SecretKey secretKey = deriveKey(password, salt, iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
        if (header != null) cipher.updateAAD(header);

        byte[] plainTextBytes = cipher.doFinal(cipherText);
        return new String(plainTextBytes, StandardCharsets.UTF_8);
    }

    private static boolean hasV2Magic(byte[] data) {
        if (data.length < MAGIC_V2.length + 4 + SALT_LENGTH + IV_LENGTH) return false;
        for (int i = 0; i < MAGIC_V2.length; i++) {
            if (data[i] != MAGIC_V2[i]) return false;
        }
        return true;
    }

    /**
     * The PBKDF2 Key Derivation Function.
     */
    private static SecretKey deriveKey(String password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}
