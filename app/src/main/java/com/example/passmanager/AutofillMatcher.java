package com.example.passmanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which vault credentials autofill may offer for a given screen.
 *
 * Matching is exact (after normalizing) instead of substring, so "paypal-login.evil.com"
 * or a one-letter title can no longer pull unrelated credentials.
 * The web domain is only trusted when it comes from a known browser; any other app
 * can report an arbitrary domain, so for those we fall back to the app's label.
 */
public class AutofillMatcher {

    private static final Set<String> KNOWN_BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.chromium.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser"
    ));

    public static boolean isKnownBrowser(String packageName) {
        return packageName != null && KNOWN_BROWSERS.contains(packageName);
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The name a credential title must equal to be offered.
     * Browsers: the site name from the domain (same formatting used when saving).
     * Everything else: the app label, or the package name if there is no label.
     */
    public static String targetName(String packageName, String appLabel, String webDomain) {
        if (isKnownBrowser(packageName) && webDomain != null && !webDomain.trim().isEmpty()) {
            return DomainFormatter.formatWebsiteName(webDomain);
        }
        if (appLabel != null && !appLabel.trim().isEmpty()) return appLabel;
        return packageName;
    }

    public static boolean matches(String credentialTitle, String targetName) {
        String title = normalize(credentialTitle);
        return !title.isEmpty() && title.equals(normalize(targetName));
    }
}
