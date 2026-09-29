package com.example.passmanager.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.junit.Test;

public class PinHasherTest {

    @Test
    public void hashThenVerify() {
        String stored = PinHasher.hash("1234");
        assertTrue(stored.startsWith("v2$"));
        assertTrue(PinHasher.verify("1234", stored));
        assertFalse(PinHasher.verify("1235", stored));
    }

    @Test
    public void saltMakesEveryHashUnique() {
        assertNotEquals(PinHasher.hash("0000"), PinHasher.hash("0000"));
    }

    @Test
    public void verifiesLegacySha256Hashes() throws Exception {
        String legacy = legacyHash("4821");
        assertTrue(PinHasher.verify("4821", legacy));
        assertFalse(PinHasher.verify("4822", legacy));
        assertTrue(PinHasher.needsUpgrade(legacy));
    }

    @Test
    public void currentHashesDoNotNeedUpgrade() {
        assertFalse(PinHasher.needsUpgrade(PinHasher.hash("9999")));
        assertFalse(PinHasher.needsUpgrade(""));
        assertFalse(PinHasher.needsUpgrade(null));
    }

    @Test
    public void decoyDuressSlotLooksRealButNoPinOpensIt() {
        String decoy = PinHasher.decoyHash();
        // Same shape as a real duress PIN hash...
        assertTrue(decoy.startsWith("v2$"));
        assertFalse(PinHasher.needsUpgrade(decoy));
        assertEquals(PinHasher.hash("1234").split("\\$").length, decoy.split("\\$").length);
        // ...but no 4-digit PIN matches (sampled; each check is a full PBKDF2 run)
        for (String pin : new String[]{"0000", "1234", "5555", "9999", "2580"}) {
            assertFalse(PinHasher.verify(pin, decoy));
        }
        assertNotEquals(decoy, PinHasher.decoyHash());
    }

    @Test
    public void missingOrCorruptHashNeverVerifies() {
        assertFalse(PinHasher.verify("1234", null));
        assertFalse(PinHasher.verify("1234", ""));
        assertFalse(PinHasher.verify("1234", "v2$oops"));
        assertFalse(PinHasher.verify("1234", "v2$abc$!!$!!"));
        assertFalse(PinHasher.verify("1234", "not base64 at all!"));
    }

    // The exact scheme LockScreenActivity used before PinHasher existed
    private static String legacyHash(String pin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("LedgerVaultSalt2026".getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest.digest(pin.getBytes(StandardCharsets.UTF_8)));
    }
}
