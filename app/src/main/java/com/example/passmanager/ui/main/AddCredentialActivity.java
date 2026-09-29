package com.example.passmanager.ui.main;

import android.os.Bundle;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;

import com.example.passmanager.PasswordStrength;
import com.example.passmanager.R;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.security.EncryptionUtil;
import com.example.passmanager.ui.viewmodel.VaultViewModel;

public class AddCredentialActivity extends ProtectedActivity {

    private VaultViewModel vaultViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Not unlocked in this process (e.g. restored after Android killed the app): lock screen instead
        if (redirectedToLockScreen()) return;

        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_add_credential);

        vaultViewModel = new ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this.getApplication())).get(VaultViewModel.class);

        LoginFormHelper form = new LoginFormHelper(this, R.string.add_title, R.string.add_save);

        findViewById(R.id.button_save_credential).setOnClickListener(v -> {
            if (!form.validate()) return;

            String password = form.passwordText();
            try {
                // 1. CALCULATE HEALTH SCORE (Plaintext)
                int healthScore = PasswordStrength.score(password);

                // 2. ENCRYPT THE PASSWORD
                android.util.Pair<String, String> encryptedData = EncryptionUtil.encryptPassword(password);

                // 3. SAVE TO DATABASE (no 2FA secret yet)
                Credential credential = new Credential(
                        form.titleText(), form.usernameText(), encryptedData.first, encryptedData.second, healthScore, null
                );

                vaultViewModel.insert(credential);
                Messages.setPending(getString(R.string.add_saved, form.titleText())); // shown on the vault screen
                finish();

            } catch (Exception e) {
                android.util.Log.e("AddCredential", "Encryption failed", e);
                Messages.showLong(this, R.string.error_encrypt);
            }
        });
    }
}
