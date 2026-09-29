package com.example.passmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutofillMatcherTest {

    private static final String CHROME = "com.android.chrome";

    @Test
    public void browserUsesSiteNameFromDomain() {
        assertEquals("Github", AutofillMatcher.targetName(CHROME, "Chrome", "github.com"));
        assertEquals("Google", AutofillMatcher.targetName(CHROME, "Chrome", "accounts.google.com"));
    }

    @Test
    public void nonBrowserCannotSpoofDomain() {
        // A random app claiming to show paypal.com still only gets its own label
        assertEquals("Totally Legit Game",
                AutofillMatcher.targetName("com.evil.game", "Totally Legit Game", "paypal.com"));
    }

    @Test
    public void nonBrowserFallsBackToPackageWithoutLabel() {
        assertEquals("com.example.app", AutofillMatcher.targetName("com.example.app", null, null));
    }

    @Test
    public void browserWithoutDomainUsesAppLabel() {
        assertEquals("Chrome", AutofillMatcher.targetName(CHROME, "Chrome", null));
    }

    @Test
    public void exactMatchIgnoresCaseAndSpaces() {
        assertTrue(AutofillMatcher.matches("GitHub", "Github"));
        assertTrue(AutofillMatcher.matches("  paypal ", "PayPal"));
    }

    @Test
    public void lookAlikeSitesDoNotMatch() {
        String target = AutofillMatcher.targetName(CHROME, "Chrome", "paypal-login.evil.com");
        assertFalse(AutofillMatcher.matches("PayPal", target));
        assertFalse(AutofillMatcher.matches("PayPal", "PayPal Secure Login"));
    }

    @Test
    public void shortTitlesDoNotMatchEverything() {
        assertFalse(AutofillMatcher.matches("a", "Amazon"));
        assertFalse(AutofillMatcher.matches("", "Amazon"));
        assertFalse(AutofillMatcher.matches(null, "Amazon"));
    }
}
