package com.example.passmanager;

import android.app.assist.AssistStructure;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.os.CancellationSignal;

import com.example.passmanager.data.model.Credential;
import com.example.passmanager.repository.CredentialRepository;
import com.example.passmanager.security.EncryptionUtil;

import java.util.List;

public class VaultAutofillService extends AutofillService {

    private static final String TAG = "VaultAutofill";

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal, FillCallback callback) {
        Log.d(TAG, "onFillRequest: Scanning screen...");

        // Duress session open: offer nothing, not even usernames or "Search Ledger"
        if (com.example.passmanager.security.VaultSession.isDuress()) {
            callback.onSuccess(null);
            return;
        }

        List<FillContext> contexts = request.getFillContexts();
        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();

        ParsedStructure parsed = new ParsedStructure();
        int nodes = structure.getWindowNodeCount();
        for (int i = 0; i < nodes; i++) {
            scanForAutofillIds(structure.getWindowNodeAt(i).getRootViewNode(), parsed);
        }

        if (parsed.usernameId != null || parsed.passwordId != null) {

            // 1. Setup the Save Interceptor
            int flags = 0;
            AutofillId[] idsToWatch;

            if (parsed.passwordId == null && parsed.usernameId != null) {
                flags = SaveInfo.FLAG_DELAY_SAVE;
                idsToWatch = new AutofillId[]{parsed.usernameId};
            } else if (parsed.passwordId != null && parsed.usernameId != null) {
                idsToWatch = new AutofillId[]{parsed.usernameId, parsed.passwordId};
            } else {
                idsToWatch = new AutofillId[]{parsed.passwordId};
            }

            SaveInfo saveInfo = new SaveInfo.Builder(
                    SaveInfo.SAVE_DATA_TYPE_USERNAME | SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                    idsToWatch
            ).setFlags(flags).build();

            // 2. Determine the Target Title to search for
            android.content.ComponentName component = structure.getActivityComponent();
            String rawPackageName = (component != null) ? component.getPackageName() : "Unknown App";
            final String targetPackage = component != null ? component.getPackageName() : null;
            final String targetLabel = targetPackage != null ? getAppLabel(targetPackage) : null;
            final String finalSearchTitle = AutofillMatcher.targetName(rawPackageName, targetLabel, parsed.webDomain);

            // 3. Query the Database on a Background Thread
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                FillResponse.Builder responseBuilder = new FillResponse.Builder().setSaveInfo(saveInfo);

                try {
                    CredentialRepository repo = new CredentialRepository(getApplication());
                    List<Credential> allCreds = repo.getAllCredentialsSync();

                    boolean foundMatch = false;

                    for (Credential cred : allCreds) {
                        // Look for an exact match (substring matching let look-alike apps/sites pull credentials)
                        if (AutofillMatcher.matches(cred.getTitle(), finalSearchTitle)) {

                            foundMatch = true;

                            // Dropdown row: username on top, site/app name underneath
                            android.widget.RemoteViews presentation = AutofillPresentation.login(this, cred.getTitle(), cred.getUsername());

                            // Locked dataset: the fields are listed but empty. Tapping the row opens
                            // AutofillAuthActivity, which asks for fingerprint/PIN, decrypts the password
                            // and returns the filled dataset. Nothing is decrypted here.
                            android.service.autofill.Dataset.Builder datasetBuilder = new android.service.autofill.Dataset.Builder();
                            if (parsed.usernameId != null) datasetBuilder.setValue(parsed.usernameId, null, presentation);
                            if (parsed.passwordId != null) datasetBuilder.setValue(parsed.passwordId, null, presentation);

                            android.content.Intent unlockIntent = new android.content.Intent(getApplicationContext(), AutofillAuthActivity.class);
                            unlockIntent.putExtra(AutofillAuthActivity.EXTRA_CREDENTIAL_ID, cred.getId());
                            // So the prompt can say where the password is going
                            unlockIntent.putExtra(AutofillPresentation.EXTRA_TARGET_PACKAGE, targetPackage);
                            unlockIntent.putExtra(AutofillPresentation.EXTRA_TARGET_LABEL, targetLabel);
                            unlockIntent.putExtra(AutofillPresentation.EXTRA_TARGET_WEB_DOMAIN, parsed.webDomain);
                            if (parsed.usernameId != null) unlockIntent.putExtra("target_username_id", parsed.usernameId);
                            if (parsed.passwordId != null) unlockIntent.putExtra("target_password_id", parsed.passwordId);
                            android.app.PendingIntent unlockPending = android.app.PendingIntent.getActivity(
                                    getApplicationContext(),
                                    AutofillAuthActivity.REQUEST_CODE_BASE + cred.getId(), // one per login
                                    unlockIntent,
                                    android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_MUTABLE
                            );
                            datasetBuilder.setAuthentication(unlockPending.getIntentSender());

                            responseBuilder.addDataset(datasetBuilder.build());
                        }
                    }

                    if (foundMatch) {
                        Log.d(TAG, "Found matches! Injecting datasets.");
                    } else {
                        Log.d(TAG, "No matches found. Injecting 'Search Ledger' fallback.");

                        android.content.Intent authIntent = new android.content.Intent(getApplicationContext(), AutofillPickerActivity.class);
                        if (parsed.usernameId != null) authIntent.putExtra("target_username_id", parsed.usernameId);
                        if (parsed.passwordId != null) authIntent.putExtra("target_password_id", parsed.passwordId);
                        authIntent.putExtra(AutofillPresentation.EXTRA_TARGET_PACKAGE, targetPackage);
                        authIntent.putExtra(AutofillPresentation.EXTRA_TARGET_LABEL, targetLabel);
                        authIntent.putExtra(AutofillPresentation.EXTRA_TARGET_WEB_DOMAIN, parsed.webDomain);

                        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                                getApplicationContext(),
                                1001,
                                authIntent,
                                android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_MUTABLE
                        );
                        android.content.IntentSender intentSender = pendingIntent.getIntentSender();

                        // Build the Custom Ledger UI for the fallback button
                        android.widget.RemoteViews authPresentation = AutofillPresentation.searchLedger(this);

                        // THE FIX: Attach Intent directly to the FillResponse to wipe out the "Double Tap" bug
                        responseBuilder.setAuthentication(idsToWatch, intentSender, authPresentation);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error building autofill datasets", e);
                }

                // 4. Send the payload back to the Android OS
                callback.onSuccess(responseBuilder.build());
            });

        } else {
            callback.onSuccess(null);
        }
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        Log.d(TAG, "onSaveRequest: User clicked Save! Ripping data...");

        List<FillContext> contexts = request.getFillContexts();
        ParsedStructure finalData = new ParsedStructure();

        for (FillContext context : contexts) {
            AssistStructure structure = context.getStructure();
            for (int i = 0; i < structure.getWindowNodeCount(); i++) {
                scanForData(structure.getWindowNodeAt(i).getRootViewNode(), finalData);
            }
        }

        // --- THE VAULT DROP ---
        Log.d(TAG, "Rip Complete. Username: " + (finalData.usernameText != null ? "FOUND (Hidden)" : "NULL"));
        Log.d(TAG, "Rip Complete. Password: " + (finalData.passwordText != null ? "FOUND (Hidden)" : "NULL"));

        if (finalData.passwordText != null) {
            String rawPackageName = contexts.get(contexts.size() - 1).getStructure().getActivityComponent().getPackageName();
            // Same naming rule as the fill side, so saved credentials match on the next visit
            final String finalDbTitle = AutofillMatcher.targetName(rawPackageName, getAppLabel(rawPackageName), finalData.webDomain);
            String user = finalData.usernameText != null ? finalData.usernameText.toString() : "Unknown User";
            String pass = finalData.passwordText.toString();

            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    android.util.Pair<String, String> encryptedData = EncryptionUtil.encryptPassword(pass);
                    // Real strength score (was hard-coded to "fair" for every autofill save)
                    Credential newAccount = new Credential(finalDbTitle, user, encryptedData.first, encryptedData.second,
                            PasswordStrength.score(pass), null);

                    CredentialRepository repo = new CredentialRepository(getApplication());
                    repo.insert(newAccount);

                    // Toast, not snackbar: this appears over another app
                    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                    handler.post(() -> android.widget.Toast.makeText(getApplicationContext(),
                            getString(R.string.autofill_saved, finalDbTitle),
                            android.widget.Toast.LENGTH_LONG).show());

                } catch (Exception e) {
                    Log.e(TAG, "Failed to encrypt/save to Vault", e);
                }
            });
        } else {
            Log.e(TAG, "ABORTING SAVE: Password text was null! The scanner couldn't rip the text.");
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> android.widget.Toast.makeText(getApplicationContext(),
                    R.string.autofill_save_failed,
                    android.widget.Toast.LENGTH_LONG).show());
        }
        callback.onSuccess();
    }

    private String getAppLabel(String packageName) {
        try {
            android.content.pm.PackageManager pm = getApplicationContext().getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            Log.w(TAG, "Could not find app name.");
            return null;
        }
    }

    // --- THE SCREEN PARSER HELPERS ---
    private static class ParsedStructure {
        AutofillId usernameId;
        AutofillId passwordId;
        CharSequence usernameText;
        CharSequence passwordText;
        String webDomain;
    }

    // Pass 1: Finding the boxes on load
    private void scanForAutofillIds(AssistStructure.ViewNode node, ParsedStructure parsed) {
        if (node.getWebDomain() != null) {
            parsed.webDomain = node.getWebDomain();
        }

        if (node.getAutofillHints() != null) {
            for (String hint : node.getAutofillHints()) {
                String h = hint.toLowerCase(java.util.Locale.ROOT);
                if (h.contains("username") || h.contains("email")) {
                    parsed.usernameId = node.getAutofillId();
                } else if (h.contains("password")) {
                    parsed.passwordId = node.getAutofillId();
                }
            }
        }

        if (node.getAutofillId() != null) {
            String viewHint = node.getHint() != null ? node.getHint().toString().toLowerCase(java.util.Locale.ROOT) : "";
            String viewId = node.getIdEntry() != null ? node.getIdEntry().toLowerCase(java.util.Locale.ROOT) : "";

            if (parsed.usernameId == null && (viewHint.contains("email") || viewHint.contains("user") || viewId.contains("email") || viewId.contains("user"))) {
                parsed.usernameId = node.getAutofillId();
            }
            if (parsed.passwordId == null && (viewHint.contains("password") || viewHint.contains("pass") || viewId.contains("password") || viewId.contains("pass"))) {
                parsed.passwordId = node.getAutofillId();
            }
        }

        android.view.ViewStructure.HtmlInfo htmlInfo = node.getHtmlInfo();
        if (htmlInfo != null && "input".equalsIgnoreCase(htmlInfo.getTag())) {
            for (android.util.Pair<String, String> attr : htmlInfo.getAttributes()) {
                String attrName = attr.first != null ? attr.first.toLowerCase(java.util.Locale.ROOT) : "";
                String attrValue = attr.second != null ? attr.second.toLowerCase(java.util.Locale.ROOT) : "";

                if (parsed.usernameId == null && (attrValue.contains("email") || attrValue.contains("username") || attrValue.contains("login"))) {
                    parsed.usernameId = node.getAutofillId();
                }
                if (parsed.passwordId == null && (attrValue.contains("password") || attrValue.contains("pass") || (attrName.equals("type") && attrValue.equals("password")))) {
                    parsed.passwordId = node.getAutofillId();
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            scanForAutofillIds(node.getChildAt(i), parsed);
        }
    }

    // Pass 2: Extracting the typed text on save
    private void scanForData(android.app.assist.AssistStructure.ViewNode node, ParsedStructure parsed) {
        if (node.getWebDomain() != null) {
            parsed.webDomain = node.getWebDomain();
        }

        boolean hasActualText = node.getText() != null && node.getText().toString().trim().length() > 0;

        boolean isPasswordNode = false;
        if (node.getInputType() != 0) {
            int variation = node.getInputType() & android.text.InputType.TYPE_MASK_VARIATION;
            if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                isPasswordNode = true;
            }
        }

        if (hasActualText && isPasswordNode) {
            parsed.passwordText = node.getText();
        }

        if (node.getAutofillHints() != null && hasActualText) {
            for (String hint : node.getAutofillHints()) {
                String h = hint.toLowerCase(java.util.Locale.ROOT);
                if (h.contains("username") || h.contains("email")) parsed.usernameText = node.getText();
                else if (h.contains("password")) parsed.passwordText = node.getText();
            }
        }

        if (hasActualText) {
            String viewId = node.getIdEntry() != null ? node.getIdEntry().toLowerCase(java.util.Locale.ROOT) : "";

            if (parsed.usernameText == null && (viewId.contains("email") || viewId.contains("user") || viewId.contains("login"))) {
                parsed.usernameText = node.getText();
            }
            if (parsed.passwordText == null && (viewId.contains("password") || viewId.contains("pass"))) {
                parsed.passwordText = node.getText();
            }
        }

        android.view.ViewStructure.HtmlInfo htmlInfo = node.getHtmlInfo();
        if (htmlInfo != null && "input".equalsIgnoreCase(htmlInfo.getTag()) && hasActualText) {
            for (android.util.Pair<String, String> attr : htmlInfo.getAttributes()) {
                String attrName = attr.first != null ? attr.first.toLowerCase(java.util.Locale.ROOT) : "";
                String attrValue = attr.second != null ? attr.second.toLowerCase(java.util.Locale.ROOT) : "";

                if (parsed.usernameText == null && (attrValue.contains("email") || attrValue.contains("username") || attrValue.contains("login"))) {
                    parsed.usernameText = node.getText();
                }
                if (parsed.passwordText == null && (attrValue.contains("password") || attrValue.contains("pass") || (attrName.equals("type") && attrValue.equals("password")))) {
                    parsed.passwordText = node.getText();
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            scanForData(node.getChildAt(i), parsed);
        }
    }
}