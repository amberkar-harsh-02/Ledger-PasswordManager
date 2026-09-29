package com.example.passmanager.ui.main;

import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.passmanager.PasswordStrength;
import com.example.passmanager.R;
import com.example.passmanager.SecurityUtil;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Shared behaviour for the Add login and Edit login screens (activity_add_credential.xml):
 * close button, "Generate strong password", live strength meter and inline field errors.
 */
class LoginFormHelper {

    final EditText title;
    final EditText username;
    final EditText password;

    private final AppCompatActivity activity;
    private final TextInputLayout titleLayout;
    private final TextInputLayout passwordLayout;
    private final View strengthMeter;
    private final ViewGroup strengthSegments;
    private final TextView strengthLabel;

    LoginFormHelper(AppCompatActivity activity, int toolbarTitleRes, int saveLabelRes) {
        this.activity = activity;

        MaterialToolbar toolbar = activity.findViewById(R.id.toolbar_credential);
        toolbar.setTitle(toolbarTitleRes);
        toolbar.setNavigationOnClickListener(v -> activity.getOnBackPressedDispatcher().onBackPressed());

        title = activity.findViewById(R.id.edit_text_title);
        username = activity.findViewById(R.id.edit_text_username);
        password = activity.findViewById(R.id.edit_text_password);
        titleLayout = activity.findViewById(R.id.layout_title);
        passwordLayout = activity.findViewById(R.id.layout_password);
        strengthMeter = activity.findViewById(R.id.strength_meter);
        strengthSegments = activity.findViewById(R.id.strength_segments);
        strengthLabel = activity.findViewById(R.id.strength_label);

        ((TextView) activity.findViewById(R.id.button_save_credential)).setText(saveLabelRes);

        activity.findViewById(R.id.button_generate_password).setOnClickListener(v -> {
            password.setText(SecurityUtil.generateSecurePassword());
            password.setSelection(password.length());
        });

        title.addTextChangedListener(new SimpleWatcher(() -> titleLayout.setError(null)));
        password.addTextChangedListener(new SimpleWatcher(() -> {
            passwordLayout.setError(null);
            updateStrengthMeter();
        }));
    }

    String titleText() {
        return title.getText() != null ? title.getText().toString().trim() : "";
    }

    String usernameText() {
        return username.getText() != null ? username.getText().toString().trim() : "";
    }

    String passwordText() {
        return password.getText() != null ? password.getText().toString().trim() : "";
    }

    /** Shows inline errors on empty required fields. Returns true when the form can be saved. */
    boolean validate() {
        boolean ok = true;
        if (passwordText().isEmpty()) {
            passwordLayout.setError(activity.getString(R.string.field_password_required));
            password.requestFocus();
            ok = false;
        }
        if (titleText().isEmpty()) {
            titleLayout.setError(activity.getString(R.string.field_site_required));
            title.requestFocus();
            ok = false;
        }
        return ok;
    }

    private void updateStrengthMeter() {
        StrengthMeter.bind(strengthMeter, strengthSegments, strengthLabel, passwordText());
    }

    private static class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { onChange.run(); }
        @Override public void afterTextChanged(Editable s) {}
    }
}
