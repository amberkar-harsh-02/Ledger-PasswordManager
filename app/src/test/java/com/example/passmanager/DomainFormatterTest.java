package com.example.passmanager;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DomainFormatterTest {

    @Test
    public void extractsMainWord() {
        assertEquals("Github", DomainFormatter.formatWebsiteName("github.com"));
        assertEquals("Google", DomainFormatter.formatWebsiteName("accounts.google.com"));
        assertEquals("Madhat", DomainFormatter.formatWebsiteName("madhat.io"));
    }

    @Test
    public void stripsProtocolAndPath() {
        assertEquals("Github", DomainFormatter.formatWebsiteName("https://github.com/login"));
    }

    @Test
    public void handlesDoubleTlds() {
        assertEquals("Bbc", DomainFormatter.formatWebsiteName("www.bbc.co.uk"));
        assertEquals("Abc", DomainFormatter.formatWebsiteName("abc.com.au"));
    }

    @Test
    public void sameResultOnATurkishPhone() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertEquals("Github", DomainFormatter.formatWebsiteName("GITHUB.COM"));
            assertEquals("Instagram", DomainFormatter.formatWebsiteName("instagram.com"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    public void emptyInput() {
        assertEquals("Unknown Site", DomainFormatter.formatWebsiteName(null));
        assertEquals("Unknown Site", DomainFormatter.formatWebsiteName(""));
    }
}
