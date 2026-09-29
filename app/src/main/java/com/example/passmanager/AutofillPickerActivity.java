package com.example.passmanager;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passmanager.data.model.Credential;
import com.example.passmanager.repository.CredentialRepository;
import com.example.passmanager.security.EncryptionUtil;
import com.example.passmanager.ui.main.CredentialAdapter;
import com.example.passmanager.ui.main.Messages;
import com.example.passmanager.ui.main.VaultUnlock;
import com.google.android.material.divider.MaterialDividerItemDecoration;

import java.util.List;

/**
 * "Search Ledger" picker, opened from the autofill dropdown when nothing matched the screen.
 * Lists every login in the same rows as the Vault tab; picking one fills it.
 */
public class AutofillPickerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Screenshot blocking comes from LedgerApp (ScreenPrivacy), like every Ledger screen
        setContentView(R.layout.activity_autofill_picker);

        // 1. Catch the target coordinates
        android.view.autofill.AutofillId usernameId = getIntent().getParcelableExtra("target_username_id");
        android.view.autofill.AutofillId passwordId = getIntent().getParcelableExtra("target_password_id");

        RecyclerView list = findViewById(R.id.picker_list);
        View empty = findViewById(R.id.picker_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        MaterialDividerItemDecoration divider = new MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL);
        divider.setDividerInsetStart(getResources().getDimensionPixelSize(R.dimen.list_divider_inset));
        divider.setLastItemDecorated(false);
        list.addItemDecoration(divider);

        CredentialAdapter adapter = new CredentialAdapter();
        adapter.setShowStrength(false);
        list.setAdapter(adapter);

        // Where the password would go, shown in the prompt (see AutofillAuthActivity)
        String target = AutofillPresentation.describeTarget(this,
                getIntent().getStringExtra(AutofillPresentation.EXTRA_TARGET_PACKAGE),
                getIntent().getStringExtra(AutofillPresentation.EXTRA_TARGET_LABEL),
                getIntent().getStringExtra(AutofillPresentation.EXTRA_TARGET_WEB_DOMAIN));

        ((android.widget.TextView) findViewById(R.id.picker_body)).setText(getString(R.string.picker_body_target, target));

        // 2. Picking a login asks for fingerprint/PIN, then fills both fields at once.
        //    A duress PIN closes the picker without filling anything.
        adapter.setOnItemClickListener(selectedCred -> VaultUnlock.authenticate(this,
                selectedCred.getTitle() != null && !selectedCred.getTitle().isEmpty()
                        ? getString(R.string.autofill_prompt_title, selectedCred.getTitle())
                        : getString(R.string.autofill_prompt_title_generic),
                target, true, duress -> {
            if (duress) {
                finish();
                return;
            }
            try {
                String decryptedPassword = EncryptionUtil.decryptPassword(selectedCred.getEncryptedPassword(), selectedCred.getEncryptionIv());
                String username = selectedCred.getUsername() != null ? selectedCred.getUsername() : "";

                android.widget.RemoteViews presentation = AutofillPresentation.login(this, selectedCred.getTitle(), username);
                android.service.autofill.Dataset.Builder datasetBuilder = new android.service.autofill.Dataset.Builder();
                if (usernameId != null) datasetBuilder.setValue(usernameId, android.view.autofill.AutofillValue.forText(username), presentation);
                if (passwordId != null) datasetBuilder.setValue(passwordId, android.view.autofill.AutofillValue.forText(decryptedPassword), presentation);

                // Wrap the Dataset in a FillResponse so it fills both fields in one go (no double tap)
                android.service.autofill.FillResponse masterResponse = new android.service.autofill.FillResponse.Builder()
                        .addDataset(datasetBuilder.build())
                        .build();

                android.content.Intent replyIntent = new android.content.Intent();
                replyIntent.putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, masterResponse);
                setResult(RESULT_OK, replyIntent);
                finish();

            } catch (Exception e) {
                android.util.Log.e("AutofillPicker", "Could not build the dataset", e);
                Messages.show(this, R.string.picker_fill_failed);
            }
        }));

        // 3. Load the vault off the UI thread
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<Credential> allCreds = new CredentialRepository(getApplication()).getAllCredentialsSync();
                allCreds.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                        a.getTitle() != null ? a.getTitle() : "", b.getTitle() != null ? b.getTitle() : ""));
                runOnUiThread(() -> {
                    adapter.setCredentials(allCreds);
                    boolean isEmpty = allCreds.isEmpty();
                    list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                    empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                android.util.Log.e("AutofillPicker", "Could not load the vault", e);
            }
        });
    }
}
