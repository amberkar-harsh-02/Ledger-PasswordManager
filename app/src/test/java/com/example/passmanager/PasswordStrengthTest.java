package com.example.passmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordStrengthTest {

    @Test
    public void emptyIsWeak() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.score(null));
        assertEquals(PasswordStrength.WEAK, PasswordStrength.score(""));
    }

    @Test
    public void scoresMatchOriginalRules() {
        assertEquals(PasswordStrength.WEAK, PasswordStrength.score("password"));      // 8 + lower = 2
        assertEquals(PasswordStrength.FAIR, PasswordStrength.score("Password1"));     // 8 + lower + upper + digit = 4
        assertEquals(PasswordStrength.STRONG, PasswordStrength.score("Password1234!")); // 8 + 12 + 4 classes = 6
        assertEquals(PasswordStrength.VERY_STRONG, PasswordStrength.score("Password1234!abcd")); // all 7
    }

    @Test
    public void attentionThreshold() {
        assertTrue(PasswordStrength.needsAttention(PasswordStrength.WEAK));
        assertTrue(PasswordStrength.needsAttention(PasswordStrength.FAIR));
        assertFalse(PasswordStrength.needsAttention(PasswordStrength.STRONG));
        assertFalse(PasswordStrength.needsAttention(PasswordStrength.VERY_STRONG));
    }
}
