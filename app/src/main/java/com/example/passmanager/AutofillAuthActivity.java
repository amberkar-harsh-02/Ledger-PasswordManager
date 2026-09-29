package com.example.passmanager;

import android.content.Intent;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.appcompat.app.AppCompatActivity;

import com.example.passmanager.data.model.Credential;
import com.example.passmanager.repository.CredentialRepository;
import com.example.passmanager.security.EncryptionUtil;
import com.example.passmanager.ui.main.Messages;
import com.example.passmanager.ui.main.VaultUnlock;

import java.util.concurrent.Executors;

/**
 * Opened by the system when the user taps a Ledger suggestion in another app's login form.
 * Invisible apart from the fingerprint/PIN prompt. On unlock it decrypts that one login and
 * hands the filled dataset back to Android. Cancelled, failed, or a duress PIN: nothing is filled.
 */
public class AutofillAuthActivity extends AppCompatActivity {

    public static final String EXTRA_CREDENTIAL_ID = "credential_id";
    /** PendingIntent request codes for locked datasets are this + the credential ID. */
    public static final int REQUEST_CODE_BASE = 20_000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Screenshot blocking comes from LedgerApp (ScreenPrivacy), like every Ledger screen
        setResult(RESULT_CANCELED);

        // Recreated while the prompt was up (e.g. rotation): the callback is gone, so just give up.
        // The user taps the suggestion again.
        if (savedInstanceState != null) {
            finish();
            return;
        }

        int credentialId = getIntent().getIntExtra(EXTRA_CREDENTIAL_ID, -1);
        AutofillId usernameId = getIntent().getParcelableExtra("target_username_id");
        AutofillId passwordId = getIntent().getParcelableExtra("target_password_id");

        // Say which login is being filled and where it's going, e.g.
        // "Fill GitHub login?" / "Into Totally Legit Game (com.evil.game)": an app can borrow
        // GitHub's name, but not its package, so a look-alike stands out before anything is filled.
        String target = AutofillPresentation.describeTarget(this,
                getIntent().getStringExtra(AutofillPresentation.EXTRA_TARGET_PACKAGE),
                getIntent().getStringExtra(AutofillPresentation.EXTRA_TARGET_LABEL),
                getIntent().getStringExtra(AutofillPresentation.EXTRA_TARGET_WEB_DOMAIN));

        // Only the login's name is read before unlocking; nothing is decrypted yet
        Executors.newSingleThreadExecutor().execute(() -> {
            Credential cred = new CredentialRepository(getApplication()).getCredentialByIdSync(credentialId);
            String title = cred != null && cred.getTitle() != null && !cred.getTitle().isEmpty()
                    ? getString(R.string.autofill_prompt_title, cred.getTitle())
                    : getString(R.string.autofill_prompt_title_generic);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                VaultUnlock.authenticate(this, title, target, true, new VaultUnlock.Callback() {
                    @Override
                    public void onUnlocked(boolean duress) {
                        if (duress) {
                            // Looks like an ordinary cancel to whoever is watching; the vault stays sealed
                            finish();
                            return;
                        }
                        fill(credentialId, usernameId, passwordId);
                    }

                    @Override
                    public void onCancelled() {
                        finish();
                    }
                });
            });
        });
    }

    private void fill(int credentialId, AutofillId usernameId, AutofillId passwordId) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Credential cred = new CredentialRepository(getApplication()).getCredentialByIdSync(credentialId);
                if (cred == null) throw new IllegalStateException("Login no longer exists");

                String password = EncryptionUtil.decryptPassword(cred.getEncryptedPassword(), cred.getEncryptionIv());
                String username = cred.getUsername() != null ? cred.getUsername() : "";

                RemoteViews presentation = AutofillPresentation.login(this, cred.getTitle(), username);
                Dataset.Builder dataset = new Dataset.Builder();
                if (usernameId != null) dataset.setValue(usernameId, AutofillValue.forText(username), presentation);
                if (passwordId != null) dataset.setValue(passwordId, AutofillValue.forText(password), presentation);

                Intent reply = new Intent();
                reply.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset.build());
                runOnUiThread(() -> {
                    setResult(RESULT_OK, reply);
                    finish();
                });
            } catch (Exception e) {
                android.util.Log.e("AutofillAuth", "Could not fill the login", e);
                runOnUiThread(() -> {
                    Messages.showLong(this, R.string.picker_fill_failed);
                    finish();
                });
            }
        });
    }
}
