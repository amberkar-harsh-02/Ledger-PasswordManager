package com.example.passmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class SecurityUtilTest {

    private static final String ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+<>?";

    @Test
    public void generates16AllowedCharacters() {
        for (int i = 0; i < 200; i++) {
            String pw = SecurityUtil.generateSecurePassword();
            assertEquals(16, pw.length());
            for (char c : pw.toCharArray()) {
                assertTrue("Unexpected char " + c, ALLOWED.indexOf(c) >= 0);
            }
        }
    }

    @Test
    public void passwordsAreNotRepeated() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(SecurityUtil.generateSecurePassword()));
        }
    }
}
