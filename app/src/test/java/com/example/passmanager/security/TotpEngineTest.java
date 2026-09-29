package com.example.passmanager.security;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TotpEngineTest {

    // RFC 6238 Appendix B, SHA-1 seed "12345678901234567890" in Base32.
    // Expected values are the last 6 digits of the RFC's 8-digit codes.
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    public void matchesRfc6238Vectors() {
        assertEquals("287082", TotpEngine.generateTOTP(RFC_SECRET, 59_000L));
        assertEquals("081804", TotpEngine.generateTOTP(RFC_SECRET, 1_111_111_109_000L));
        assertEquals("050471", TotpEngine.generateTOTP(RFC_SECRET, 1_111_111_111_000L));
        assertEquals("005924", TotpEngine.generateTOTP(RFC_SECRET, 1_234_567_890_000L));
        assertEquals("279037", TotpEngine.generateTOTP(RFC_SECRET, 2_000_000_000_000L));
        assertEquals("353130", TotpEngine.generateTOTP(RFC_SECRET, 20_000_000_000_000L));
    }

    @Test
    public void acceptsLowercaseAndSpaces() {
        String messy = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq";
        assertEquals("287082", TotpEngine.generateTOTP(messy, 59_000L));
    }

    @Test
    public void emptySecretShowsPlaceholder() {
        assertEquals("------", TotpEngine.generateTOTP(null, 59_000L));
        assertEquals("------", TotpEngine.generateTOTP("  ", 59_000L));
    }

    @Test
    public void validatesSecrets() {
        assertEquals(true, TotpEngine.isValidSecret(RFC_SECRET));
        assertEquals(true, TotpEngine.isValidSecret("jbsw y3dp ehpk 3pxp"));
        assertEquals(true, TotpEngine.isValidSecret("JBSWY3DPEHPK3PXP===="));
        assertEquals(false, TotpEngine.isValidSecret(null));
        assertEquals(false, TotpEngine.isValidSecret(""));
        assertEquals(false, TotpEngine.isValidSecret("ABC"));           // too short
        assertEquals(false, TotpEngine.isValidSecret("JBSWY3DPEHPK3PX1")); // '1' is not Base32
        assertEquals(false, TotpEngine.isValidSecret("otpauth://totp/x"));
    }

    @Test
    public void invalidSecretReportsError() {
        assertEquals("ERROR", TotpEngine.generateTOTP("NOT-BASE32!", 59_000L));
    }
}
