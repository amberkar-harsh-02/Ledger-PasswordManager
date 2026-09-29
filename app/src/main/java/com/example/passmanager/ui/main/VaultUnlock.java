package com.example.passmanager.ui.main;

import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.passmanager.R;
import com.example.passmanager.data.local.VaultDatabase;
import com.example.passmanager.data.model.AuditLog;
import com.example.passmanager.security.PinHasher;
import com.example.passmanager.security.PinLockout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.Executors;

/**
 * "Confirm it's you" before showing or filling a secret. Used by the vault (reveal, edit, delete)
 * and by autofill (before a password is filled into another app).
 *
 * Fingerprint or screen lock through the system prompt when the device has one; otherwise the
 * vault's own PIN, with the same hash and brute-force lockout as the lock screen.
 */
public final class VaultUnlock {

    public interface Callback {
        /** Unlocked. {@code duress} is true when the duress PIN was entered (only if accepted). */
        void onUnlocked(boolean duress);

        /** The user backed out, or unlocking isn't possible right now (e.g. PIN lockout). */
        default void onCancelled() {}
    }

    private VaultUnlock() {}

    /**
     * @param acceptDuress when true the duress PIN "works" and is reported via {@code onUnlocked(true)};
     *                     when false it counts as a wrong PIN.
     */
    public static void authenticate(AppCompatActivity activity, boolean acceptDuress, Callback callback) {
        authenticate(activity, activity.getString(R.string.biometric_title), null, acceptDuress, callback);
    }

    /**
     * Same, with a prompt that says what is being unlocked, e.g. "Fill GitHub login?" /
     * "Into Chrome · github.com". Autofill uses this so the user sees where the password goes.
     */
    public static void authenticate(AppCompatActivity activity, CharSequence title, CharSequence subtitle,
                                    boolean acceptDuress, Callback callback) {
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

        // No fingerprint and no screen lock on this device (common on emulators): the system
        // prompt cannot show, so ask for the vault's own PIN instead
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            promptForPin(activity, title, subtitle, acceptDuress, callback);
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        callback.onUnlocked(false); // Biometrics / screen lock always mean the real owner
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // Stay quiet when the user backed out; report everything else (e.g. lockout)
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED
                                && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                && errorCode != BiometricPrompt.ERROR_CANCELED) {
                            Messages.show(activity, errString);
                        }
                        callback.onCancelled();
                    }
                });
        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(authenticators);
        if (subtitle != null) info.setSubtitle(subtitle);
        prompt.authenticate(info.build());
    }

    // Fallback for devices without biometrics or a screen lock. Same PIN hash, lockout and
    // audit trail as the lock screen.
    private static void promptForPin(AppCompatActivity activity, CharSequence title, CharSequence subtitle,
                                     boolean acceptDuress, Callback callback) {
        SharedPreferences prefs = activity.getSharedPreferences("VaultSecurityPrefs", AppCompatActivity.MODE_PRIVATE);

        long lockedForMs = PinLockout.remainingMs(prefs);
        if (lockedForMs > 0) {
            Messages.show(activity, activity.getString(R.string.lock_locked_out, (int) ((lockedForMs + 999) / 1000)));
            callback.onCancelled();
            return;
        }

        View form = activity.getLayoutInflater().inflate(R.layout.dialog_secret_input, null);
        ((TextInputLayout) form.findViewById(R.id.layout_secret)).setHint(R.string.pin_dialog_hint);
        EditText input = form.findViewById(R.id.input_secret);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});

        // Set when "Unlock" was tapped, so dismissing the dialog afterwards isn't reported as a cancel
        final boolean[] submitted = {false};

        CharSequence message = subtitle != null
                ? subtitle + "\n\n" + activity.getString(R.string.pin_dialog_body)
                : activity.getString(R.string.pin_dialog_body);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setView(form)
                .setPositiveButton(R.string.lock_cover_unlock, (dialog, which) -> {
                    submitted[0] = true;
                    String pin = input.getText() != null ? input.getText().toString() : "";
                    String masterHash = prefs.getString("MASTER_PIN_HASH", "");
                    String duressHash = prefs.getString("DURESS_PIN_HASH", "");

                    // PBKDF2 is slow on purpose, so run it off the UI thread
                    Executors.newSingleThreadExecutor().execute(() -> {
                        boolean isMaster = PinHasher.verify(pin, masterHash);
                        boolean isDuress = !isMaster && acceptDuress && PinHasher.verify(pin, duressHash);
                        boolean accepted = isMaster || isDuress;

                        VaultDatabase.getDatabase(activity.getApplicationContext()).auditLogDao().insertLog(
                                new AuditLog(System.currentTimeMillis(),
                                        // A duress unlock is logged like the master PIN (the history must not reveal it)
                                        accepted ? "PIN_MASTER" : "PIN", accepted));

                        activity.runOnUiThread(() -> {
                            if (accepted) {
                                PinLockout.clear(prefs);
                                callback.onUnlocked(isDuress);
                            } else {
                                long lockoutMs = PinLockout.registerFailure(prefs);
                                Messages.show(activity, lockoutMs > 0
                                        ? activity.getString(R.string.lock_locked_out, (int) (lockoutMs / 1000))
                                        : activity.getString(R.string.lock_wrong_pin));
                                callback.onCancelled();
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener(dialog -> {
                    if (!submitted[0]) callback.onCancelled();
                })
                .show();
    }
}
