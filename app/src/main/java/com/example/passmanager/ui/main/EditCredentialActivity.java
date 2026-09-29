package com.example.passmanager.ui.main;

import android.os.Bundle;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;

import com.example.passmanager.PasswordStrength;
import com.example.passmanager.R;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.security.EncryptionUtil;
import com.example.passmanager.ui.viewmodel.VaultViewModel;

public class EditCredentialActivity extends ProtectedActivity {

    private VaultViewModel vaultViewModel;
    private int credentialId;

    // Hold onto the (encrypted) 2FA secret so saving the form doesn't erase it
    private String existingTotpSecret = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Not unlocked in this process (e.g. restored after Android killed the app): lock screen instead
        if (redirectedToLockScreen()) return;

        // Same form as Add login
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_add_credential);

        vaultViewModel = new ViewModelProvider(this).get(VaultViewModel.class);
        LoginFormHelper form = new LoginFormHelper(this, R.string.edit_title, R.string.edit_save);

        // 1. CATCH THE INTENT DATA
        android.content.Intent intent = getIntent();
        if (intent != null && intent.hasExtra("CREDENTIAL_ID")) {
            credentialId = intent.getIntExtra("CREDENTIAL_ID", -1);
            form.title.setText(intent.getStringExtra("CREDENTIAL_TITLE"));
            form.username.setText(intent.getStringExtra("CREDENTIAL_USERNAME"));

            // Catch the 2FA secret (if it exists) so we can preserve it
            if (intent.hasExtra("CREDENTIAL_TOTP_SECRET")) {
                existingTotpSecret = intent.getStringExtra("CREDENTIAL_TOTP_SECRET");
            }

            // Decrypt the old password so the user can see what they are replacing
            try {
                String encryptedPw = intent.getStringExtra("CREDENTIAL_ENCRYPTED_PW");
                String iv = intent.getStringExtra("CREDENTIAL_IV");
                form.password.setText(EncryptionUtil.decryptPassword(encryptedPw, iv));
            } catch (Exception e) {
                Messages.showLong(this, R.string.edit_decrypt_failed);
            }
        }

        // 2. UPDATE LOGIC
        findViewById(R.id.button_save_credential).setOnClickListener(v -> {
            if (!form.validate()) return;

            String updatedPassword = form.passwordText();
            try {
                int newHealthScore = PasswordStrength.score(updatedPassword);
                android.util.Pair<String, String> encryptedData = EncryptionUtil.encryptPassword(updatedPassword);

                // Same ID and the existing 2FA secret, so Room updates this login in place
                Credential updatedCredential = new Credential(
                        form.titleText(),
                        form.usernameText(),
                        encryptedData.first,
                        encryptedData.second,
                        newHealthScore,
                        existingTotpSecret
                );
                updatedCredential.setId(credentialId);

                vaultViewModel.update(updatedCredential);
                Messages.setPending(getString(R.string.edit_saved)); // shown on the vault screen
                finish();

            } catch (Exception e) {
                android.util.Log.e("EditCredential", "Encryption failed", e);
                Messages.showLong(this, R.string.error_encrypt);
            }
        });
    }
}
