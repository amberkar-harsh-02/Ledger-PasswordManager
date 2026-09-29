package com.example.passmanager.ui.main;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.passmanager.PasswordStrength;
import com.example.passmanager.R;
import com.google.android.material.color.MaterialColors;

/**
 * Live password strength meter: 4 segments filled up to the score, plus the words
 * ("Weak", "Fair", "Strong", "Very strong"). Used by the login form and the backup password dialog.
 * Expects the views from the strength meter block (strength_meter / strength_segments / strength_label).
 */
final class StrengthMeter {

    private StrengthMeter() {}

    static void bind(View meter, ViewGroup segments, TextView label, String password) {
        if (password == null || password.isEmpty()) {
            meter.setVisibility(View.GONE);
            return;
        }
        meter.setVisibility(View.VISIBLE);

        int score = PasswordStrength.score(password);
        int filled = score + 1; // weak = 1 segment ... very strong = 4
        int onColor = PasswordStrength.needsAttention(score)
                ? MaterialColors.getColor(segments, R.attr.colorAttention)
                : MaterialColors.getColor(segments, androidx.appcompat.R.attr.colorPrimary);
        int offColor = MaterialColors.getColor(segments, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        for (int i = 0; i < segments.getChildCount(); i++) {
            segments.getChildAt(i).setBackgroundTintList(ColorStateList.valueOf(i < filled ? onColor : offColor));
        }

        CredentialAdapter.bindStrengthLabel(label, score, label.getContext().getString(CredentialAdapter.strengthLabel(score)));
    }
}
