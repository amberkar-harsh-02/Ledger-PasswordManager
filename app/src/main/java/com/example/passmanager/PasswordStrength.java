package com.example.passmanager;

/**
 * Password health score shared by Add, Edit and the strength meter.
 * Same rules the two activities used before (length 8/12/16 plus lower, upper, digit, symbol),
 * mapped to 0 = weak, 1 = fair, 2 = strong, 3 = very strong. Scores 0 and 1 "need attention".
 */
public final class PasswordStrength {

    public static final int WEAK = 0;
    public static final int FAIR = 1;
    public static final int STRONG = 2;
    public static final int VERY_STRONG = 3;

    private PasswordStrength() {}

    public static int score(String password) {
        if (password == null || password.isEmpty()) return WEAK;

        int points = 0;
        if (password.length() >= 8) points++;
        if (password.length() >= 12) points++;
        if (password.length() >= 16) points++;
        if (password.matches(".*[a-z].*")) points++;
        if (password.matches(".*[A-Z].*")) points++;
        if (password.matches(".*\\d.*")) points++;
        if (password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) points++;

        if (points <= 3) return WEAK;
        if (points <= 5) return FAIR;
        if (points == 6) return STRONG;
        return VERY_STRONG;
    }

    public static boolean needsAttention(int score) {
        return score <= FAIR;
    }
}
