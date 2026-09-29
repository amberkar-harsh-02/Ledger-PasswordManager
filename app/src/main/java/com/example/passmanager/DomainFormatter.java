package com.example.passmanager;

public class DomainFormatter {

    public static String formatWebsiteName(String rawDomain) {
        if (rawDomain == null || rawDomain.isEmpty()) return "Unknown Site";

        // Locale.ROOT: domains are ASCII; a Turkish phone would otherwise turn "I" into dotless "ı"
        String clean = rawDomain.toLowerCase(java.util.Locale.ROOT).trim();

        // 1. Strip the web protocols and paths
        clean = clean.replace("https://", "").replace("http://", "");
        if (clean.contains("/")) {
            clean = clean.substring(0, clean.indexOf("/"));
        }

        // 2. Break the URL into pieces
        String[] parts = clean.split("\\.");
        String mainWord = clean;

        // 3. Find the actual company name
        if (parts.length >= 2) {
            int tldIndex = parts.length - 1;
            // Catch double TLDs like ".co.uk" or ".com.au"
            if (parts.length > 2 && parts[tldIndex].length() <= 2 &&
                    (parts[tldIndex - 1].equals("co") || parts[tldIndex - 1].equals("com") || parts[tldIndex - 1].equals("org"))) {
                mainWord = parts[parts.length - 3];
            } else {
                // Standard domains like "madhat.io" or "accounts.google.com"
                mainWord = parts[parts.length - 2];
            }
        } else if (parts.length == 1) {
            mainWord = parts[0];
        }

        // 4. Capitalize the first letter for the Vault UI
        if (mainWord.length() > 0) {
            return mainWord.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + mainWord.substring(1);
        }

        return rawDomain;
    }
}