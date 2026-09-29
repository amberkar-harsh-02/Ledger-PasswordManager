package com.example.passmanager.ui.main;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.passmanager.R;

import java.util.concurrent.Executor;

import com.example.passmanager.data.local.VaultDatabase;
import com.example.passmanager.data.model.AuditLog;
import com.example.passmanager.security.PinHasher;
import com.example.passmanager.security.PinLockout;
import java.util.concurrent.Executors;

public class LockScreenActivity extends AppCompatActivity {

    private StringBuilder currentPin = new StringBuilder();
    private ImageView[] pinDots;
    private View pinDotsRow;
    private TextView textTitle, textSubtitle;
    private SharedPreferences prefs;

    private String currentState = "UNLOCK";
    private String setupFirstPin = "";

    // The 72-Hour Rule (in milliseconds)
    private static final long SEVENTY_TWO_HOURS = 72L * 60 * 60 * 1000;

    // True while a PIN is being hashed/checked in the background; keypad input is ignored meanwhile
    private boolean isVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_lock_screen);

        prefs = getSharedPreferences("VaultSecurityPrefs", MODE_PRIVATE);

        textTitle = findViewById(R.id.text_lock_title);
        textSubtitle = findViewById(R.id.text_lock_subtitle);

        pinDots = new ImageView[]{
                findViewById(R.id.dot_1), findViewById(R.id.dot_2),
                findViewById(R.id.dot_3), findViewById(R.id.dot_4)
        };
        pinDotsRow = findViewById(R.id.pin_dots);

        setupKeypad();
        updatePinDots();

        String savedPinHash = prefs.getString("MASTER_PIN_HASH", null);
        if (savedPinHash == null) {
            // First time setup. No fingerprint here: there is no PIN yet to fall back on,
            // and a fingerprint must never open a vault that has no PIN.
            currentState = "CREATE_PIN";
            textTitle.setText(R.string.lock_title_create);
            showInstruction(R.string.lock_subtitle_create);
            findViewById(R.id.btn_fingerprint).setVisibility(View.INVISIBLE);
        } else {
            // Unlock flow: Evaluate the 72-hour threat model
            long lastPinTime = prefs.getLong("LAST_SUCCESSFUL_PIN", 0);
            boolean isPinRequired = (System.currentTimeMillis() - lastPinTime) > SEVENTY_TWO_HOURS;

            textTitle.setText(R.string.lock_title_unlock);
            if (isPinRequired) {
                showInstruction(R.string.lock_subtitle_pin_required);
                // Hide the fingerprint button to enforce the rule
                findViewById(R.id.btn_fingerprint).setVisibility(View.INVISIBLE);
            } else {
                showInstruction(R.string.lock_subtitle_unlock);
            }
        }
    }

    // --- MESSAGES (inline under the title instead of toasts) ---

    private void showInstruction(int messageRes) {
        textSubtitle.setText(messageRes);
        textSubtitle.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                textSubtitle, com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    private void showError(CharSequence message) {
        textSubtitle.setText(message);
        textSubtitle.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                textSubtitle, androidx.appcompat.R.attr.colorError));
        pinDotsRow.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT);
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            pinDotsRow.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake));
        }
    }

    private void showLockout(long lockedForMs) {
        showError(getString(R.string.lock_locked_out, (int) ((lockedForMs + 999) / 1000)));
    }

    private void setupKeypad() {
        int[] numberButtons = {R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4, R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9};

        for (int id : numberButtons) {
            findViewById(id).setOnClickListener(v -> {
                if (!isVerifying && currentPin.length() < 4) {
                    currentPin.append(((Button) v).getText().toString());
                    updatePinDots();
                    if (currentPin.length() == 4) processPinEntry();
                }
            });
        }

        findViewById(R.id.btn_backspace).setOnClickListener(v -> {
            if (!isVerifying && currentPin.length() > 0) {
                currentPin.deleteCharAt(currentPin.length() - 1);
                updatePinDots();
            }
        });

        findViewById(R.id.btn_fingerprint).setOnClickListener(v -> showBiometricPrompt());
    }

    private void updatePinDots() {
        for (int i = 0; i < 4; i++) {
            pinDots[i].setImageResource(i < currentPin.length() ? R.drawable.pin_dot_filled : R.drawable.pin_dot_empty);
        }
        // TalkBack reads the progress, never the digits themselves
        pinDotsRow.setContentDescription(getString(R.string.lock_digits_entered, currentPin.length()));
    }

    private void processPinEntry() {
        String enteredPin = currentPin.toString();

        if (currentState.equals("CREATE_PIN")) {
            setupFirstPin = enteredPin;
            currentState = "CONFIRM_PIN";
            textTitle.setText(R.string.lock_title_confirm);
            showInstruction(R.string.lock_subtitle_confirm);
            resetPad();

        } else if (currentState.equals("CONFIRM_PIN")) {
            if (enteredPin.equals(setupFirstPin)) {
                // HASH THE PIN BEFORE SAVING! (PBKDF2 is slow on purpose, so run it off the UI thread)
                isVerifying = true;
                Executors.newSingleThreadExecutor().execute(() -> {
                    String hashedPin = PinHasher.hash(enteredPin);
                    // Fill the duress slot with a decoy, so it looks the same whether or not a duress PIN is set later
                    String decoy = PinHasher.decoyHash();
                    runOnUiThread(() -> {
                        isVerifying = false;
                        prefs.edit().putString("MASTER_PIN_HASH", hashedPin).putString("DURESS_PIN_HASH", decoy).apply();
                        unlockVault(true, false); // True because they successfully used the PIN
                    });
                });
            } else {
                currentState = "CREATE_PIN";
                textTitle.setText(R.string.lock_title_create);
                resetPad();
                showError(getString(R.string.lock_mismatch));
            }

        } else if (currentState.equals("UNLOCK")) {
            long lockedForMs = PinLockout.remainingMs(prefs);
            if (lockedForMs > 0) {
                resetPad();
                showLockout(lockedForMs);
                return;
            }

            String savedPinHash = prefs.getString("MASTER_PIN_HASH", "");
            String savedDuressHash = prefs.getString("DURESS_PIN_HASH", "");

            isVerifying = true;
            Executors.newSingleThreadExecutor().execute(() -> {
                boolean isMaster = PinHasher.verify(enteredPin, savedPinHash);
                boolean isDuress = !isMaster && PinHasher.verify(enteredPin, savedDuressHash);

                // Silently move legacy SHA-256 hashes to PBKDF2 now that we know the PIN
                if (isMaster && PinHasher.needsUpgrade(savedPinHash)) {
                    prefs.edit().putString("MASTER_PIN_HASH", PinHasher.hash(enteredPin)).apply();
                } else if (isDuress && PinHasher.needsUpgrade(savedDuressHash)) {
                    prefs.edit().putString("DURESS_PIN_HASH", PinHasher.hash(enteredPin)).apply();
                }
                // Vaults created before decoys existed: add one, so an empty slot doesn't give away "no duress PIN"
                if (isMaster && savedDuressHash.isEmpty()) {
                    prefs.edit().putString("DURESS_PIN_HASH", PinHasher.decoyHash()).apply();
                }

                runOnUiThread(() -> {
                    isVerifying = false;
                    if (isMaster) {
                        // REAL MASTER PIN
                        PinLockout.clear(prefs);
                        logAccessAttempt("PIN_MASTER", true);
                        unlockVault(true, false); // <-- Pass false for isDuress

                    } else if (isDuress) {
                        // DURESS PIN ENTERED! Deploy the illusion.
                        // Logged exactly like the master PIN: the unlock history must not reveal a duress unlock
                        PinLockout.clear(prefs);
                        logAccessAttempt("PIN_MASTER", true);
                        unlockVault(true, true); // <-- Pass true for isDuress

                    } else {
                        logAccessAttempt("PIN", false);
                        long lockoutMs = PinLockout.registerFailure(prefs);
                        resetPad();
                        if (lockoutMs > 0) {
                            showLockout(lockoutMs);
                        } else {
                            showError(getString(R.string.lock_wrong_pin));
                        }
                    }
                });
            });
        }
    }

    private void showBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(LockScreenActivity.this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                logAccessAttempt("BIOMETRIC", true);
                unlockVault(false, false); // <-- Biometrics always open the real vault
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                // E.g. no fingerprint enrolled: say why instead of doing nothing. "Use PIN" stays quiet.
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED
                        && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        && errorCode != BiometricPrompt.ERROR_CANCELED) {
                    showError(errString);
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // The system prompt already shows "not recognized"; just keep the audit trail
                logAccessAttempt("BIOMETRIC", false);
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_title))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setNegativeButtonText(getString(R.string.biometric_use_pin))
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void unlockVault(boolean resetPinTimer, boolean isDuress) {
        if (resetPinTimer) {
            prefs.edit().putLong("LAST_SUCCESSFUL_PIN", System.currentTimeMillis()).apply();
        }

        // Opens the session for every protected screen; autofill stays silent in a duress session
        com.example.passmanager.security.VaultSession.onUnlocked(isDuress);
        Intent intent = new Intent(LockScreenActivity.this, MainActivity.class);
        intent.putExtra("IS_DURESS_MODE", isDuress); // <-- THE VOLATILE MEMORY FIX
        startActivity(intent);
        finish();
    }

    private void resetPad() {
        currentPin.setLength(0);
        updatePinDots();
    }
    private void logAccessAttempt(String eventType, boolean isSuccessful) {
        // Run the database insertion on a background thread so the UI doesn't freeze
        Executors.newSingleThreadExecutor().execute(() -> {
            VaultDatabase db = VaultDatabase.getDatabase(getApplicationContext());
            db.auditLogDao().insertLog(new AuditLog(System.currentTimeMillis(), eventType, isSuccessful));
        });
    }
}