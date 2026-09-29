package com.example.passmanager.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

public class BackupEncryptionUtilTest {

    private static final String JSON = "{\"version\":2,\"items\":[{\"title\":\"GitHub\",\"password\":\"hunter2\"}]}";

    // Low iteration count keeps the tests fast; the format is identical.
    private static final int FAST_ITERATIONS = 1_000;

    @Test
    public void roundTrip() throws Exception {
        byte[] file = BackupEncryptionUtil.encryptBackup(JSON, "correct horse", FAST_ITERATIONS);
        assertEquals(JSON, BackupEncryptionUtil.decryptBackup(file, "correct horse"));
    }

    @Test
    public void writesV2HeaderWithIterations() throws Exception {
        byte[] file = BackupEncryptionUtil.encryptBackup(JSON, "pw", FAST_ITERATIONS);
        assertEquals("LDG2", new String(file, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(FAST_ITERATIONS, ByteBuffer.wrap(file, 4, 4).getInt());
    }

    @Test
    public void wrongPasswordFails() throws Exception {
        byte[] file = BackupEncryptionUtil.encryptBackup(JSON, "right", FAST_ITERATIONS);
        assertThrows(AEADBadTagException.class, () -> BackupEncryptionUtil.decryptBackup(file, "wrong"));
    }

    @Test
    public void tamperedCiphertextFails() throws Exception {
        byte[] file = BackupEncryptionUtil.encryptBackup(JSON, "pw", FAST_ITERATIONS);
        file[file.length - 1] ^= 0x01;
        assertThrows(AEADBadTagException.class, () -> BackupEncryptionUtil.decryptBackup(file, "pw"));
    }

    @Test
    public void tamperedIterationHeaderFails() throws Exception {
        byte[] file = BackupEncryptionUtil.encryptBackup(JSON, "pw", FAST_ITERATIONS);
        ByteBuffer.wrap(file).putInt(4, FAST_ITERATIONS + 1);
        assertThrows(AEADBadTagException.class, () -> BackupEncryptionUtil.decryptBackup(file, "pw"));
    }

    @Test
    public void absurdIterationHeaderIsRejected() throws Exception {
        byte[] file = BackupEncryptionUtil.encryptBackup(JSON, "pw", FAST_ITERATIONS);
        ByteBuffer.wrap(file).putInt(4, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> BackupEncryptionUtil.decryptBackup(file, "pw"));
    }

    @Test
    public void decryptsLegacyV1Files() throws Exception {
        String legacyJson = "[{\"title\":\"Old\"}]";
        byte[] file = legacyEncrypt(legacyJson, "pw");
        assertEquals(legacyJson, BackupEncryptionUtil.decryptBackup(file, "pw"));
    }

    // Byte-for-byte copy of the original v1 writer: [SALT 16] + [IV 12] + [CIPHERTEXT], 100k PBKDF2
    private static byte[] legacyEncrypt(String plain, String password) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] iv = new byte[12];
        random.nextBytes(iv);

        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, BackupEncryptionUtil.ITERATION_COUNT_V1, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        return ByteBuffer.allocate(salt.length + iv.length + cipherText.length)
                .put(salt).put(iv).put(cipherText).array();
    }
}
