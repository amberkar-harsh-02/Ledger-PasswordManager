package com.example.passmanager.ui.main;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.passmanager.R;
import com.example.passmanager.data.model.AuditLog;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.security.BackupEncryptionUtil;
import com.example.passmanager.security.EncryptionUtil;
import com.example.passmanager.security.PinHasher;
import com.example.passmanager.security.ScreenPrivacy;
import com.example.passmanager.security.TotpSecretCodec;
import com.example.passmanager.ui.viewmodel.VaultViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SecurityFragment extends Fragment {

    private SharedPreferences sharedPreferences;
    private TextView textAutoLockStatus;
    private List<AuditLog> currentLogs = new ArrayList<>();
    private List<Credential> currentVault = new ArrayList<>();

    // Elevated to class level so our Import function can use it
    private VaultViewModel vaultViewModel;

    private AutofillManager autofillManager;
    private MaterialSwitch autofillSwitch;

    private final long[] timeoutValues = {0, 60000, 300000, -1};

    // New backups need a longer password; imports keep the old minimum so older files still open
    private static final int MIN_EXPORT_PASSWORD_LENGTH = 10;
    private static final int MIN_IMPORT_PASSWORD_LENGTH = 4;

    // --- SAF FILE PICKER LAUNCHERS ---

    // 1. Export (Save File) Launcher
    private final ActivityResultLauncher<Intent> exportFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == requireActivity().RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    promptForBackupPassword(uri, true);
                }
            });

    // 2. Import (Open File) Launcher
    private final ActivityResultLauncher<Intent> importFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == requireActivity().RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    promptForBackupPassword(uri, false);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_security, container, false);

        sharedPreferences = requireActivity().getSharedPreferences("VaultSecurityPrefs", Context.MODE_PRIVATE);
        textAutoLockStatus = view.findViewById(R.id.text_auto_lock_status);

        // --- CONNECT TO DATABASE FOR THE AUDIT ---
        vaultViewModel = new ViewModelProvider(requireActivity()).get(VaultViewModel.class);
        vaultViewModel.getAllCredentials().observe(getViewLifecycleOwner(), credentials -> {
            if (credentials != null) {
                currentVault = credentials;
            }
        });

        com.example.passmanager.data.local.VaultDatabase.getDatabase(requireContext())
                .auditLogDao().getAllLogs().observe(getViewLifecycleOwner(), logs -> {
                    if (logs != null) {
                        currentLogs = logs;
                    }
                });

        // --- 1. HIDE SCREEN CONTENTS (ScreenPrivacy) ---
        MaterialSwitch switchStealthMode = view.findViewById(R.id.switch_stealth_mode);
        switchStealthMode.setChecked(ScreenPrivacy.isOn(requireContext()));
        switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                applyHideScreen(true);
                return;
            }
            // Turning protection off: say what that exposes and let them back out
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.warn_hide_screen_title)
                    .setMessage(R.string.warn_hide_screen_body)
                    .setPositiveButton(R.string.warn_hide_screen_confirm, (d, w) -> applyHideScreen(false))
                    .setNegativeButton(R.string.action_cancel, (d, w) -> switchStealthMode.setChecked(true))
                    .setOnCancelListener(d -> switchStealthMode.setChecked(true))
                    .show();
        });
        // The whole row toggles the switch
        view.findViewById(R.id.row_stealth_mode).setOnClickListener(v -> switchStealthMode.toggle());

        // --- 2. AUTO-LOCK TIMEOUT ---
        updateAutoLockText(sharedPreferences.getLong("AUTO_LOCK_TIMEOUT", 0));
        view.findViewById(R.id.btn_auto_lock).setOnClickListener(v -> showTimeoutDialog());

        // --- 3. ACTIVITY ---
        view.findViewById(R.id.btn_reuse_scanner).setOnClickListener(v -> runReuseAudit());
        view.findViewById(R.id.btn_audit_log).setOnClickListener(v -> showHistorySheet());
        view.findViewById(R.id.btn_computers).setOnClickListener(v -> showComputersSheet());
        view.findViewById(R.id.btn_setup_duress).setOnClickListener(v -> showDuressPinDialog());

        // --- 4. BACKUP & RESTORE ---
        view.findViewById(R.id.btn_export_vault).setOnClickListener(v -> triggerExportFlow());
        view.findViewById(R.id.btn_import_vault).setOnClickListener(v -> triggerImportFlow());

        // --- 5. AUTOFILL ---
        autofillSwitch = view.findViewById(R.id.switch_autofill);
        autofillManager = requireContext().getSystemService(AutofillManager.class);
        autofillSwitch.setOnClickListener(v -> onAutofillToggled());
        // The row forwards to the switch so both behave the same
        view.findViewById(R.id.row_autofill).setOnClickListener(v -> onAutofillToggled());

        return view;
    }

    // "Hide screen contents" blocks screenshots and blanks the recents preview on every Ledger
    // screen (ScreenPrivacy). Other screens pick up the change when they next resume.
    private void applyHideScreen(boolean hide) {
        ScreenPrivacy.setOn(requireContext(), hide);
        ScreenPrivacy.apply(requireActivity());
    }

    private void onAutofillToggled() {
        if (isLedgerAutofillActive()) {
            if (autofillManager != null) {
                autofillManager.disableAutofillServices();
                autofillSwitch.setChecked(false);
                Messages.show(getContext(), R.string.security_autofill_off);
            }
        } else {
            autofillSwitch.setChecked(false); // Off until the user confirms in system settings
            Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            com.example.passmanager.security.VaultSession.expectReturn(); // system settings, back in a moment
            startActivity(intent);
        }
    }

    // Read from both the manager and the raw setting to be sure
    private boolean isLedgerAutofillActive() {
        boolean active = autofillManager != null && autofillManager.hasEnabledAutofillServices();
        String defaultService = Settings.Secure.getString(requireContext().getContentResolver(), "autofill_service");
        return active || (defaultService != null && defaultService.contains(requireContext().getPackageName()));
    }

    // --- SAF BACKUP & RESTORE LOGIC ---

    private void triggerExportFlow() {
        if (currentVault.isEmpty()) {
            Messages.show(getContext(), R.string.backup_empty_vault);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "vault_backup.ledger");
        com.example.passmanager.security.VaultSession.expectReturn(); // file picker, back in a moment
        exportFileLauncher.launch(intent);
    }

    private void triggerImportFlow() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Accept all files, but we expect .ledger
        com.example.passmanager.security.VaultSession.expectReturn(); // file picker, back in a moment
        importFileLauncher.launch(intent);
    }

    // Dialog with one secret field. onSubmit returns an error message to keep the dialog open, or null to close it.
    private interface SecretSubmit {
        @Nullable String onSubmit(String value);
    }

    private void showSecretDialog(int titleRes, CharSequence body, int hintRes, int inputType, int maxLength,
                                  boolean showStrength, int confirmRes, SecretSubmit onSubmit) {
        View form = getLayoutInflater().inflate(R.layout.dialog_secret_input, null);
        TextInputLayout layout = form.findViewById(R.id.layout_secret);
        EditText input = form.findViewById(R.id.input_secret);
        layout.setHint(hintRes);
        input.setInputType(inputType);
        if (maxLength > 0) input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});

        // Clear the error as they type; for a new backup password, show how strong it is
        View meter = form.findViewById(R.id.strength_meter);
        ViewGroup segments = form.findViewById(R.id.strength_segments);
        TextView meterLabel = form.findViewById(R.id.strength_label);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
                if (showStrength) StrengthMeter.bind(meter, segments, meterLabel, s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setMessage(body)
                .setView(form)
                .setPositiveButton(confirmRes, null) // wired below so errors keep the dialog open
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        ScreenPrivacy.apply(dialog);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String error = onSubmit.onSubmit(input.getText() != null ? input.getText().toString() : "");
            if (error == null) {
                dialog.dismiss();
            } else {
                layout.setError(error);
            }
        }));
        dialog.show();
    }

    private void promptForBackupPassword(Uri uri, boolean isExporting) {
        int minLength = isExporting ? MIN_EXPORT_PASSWORD_LENGTH : MIN_IMPORT_PASSWORD_LENGTH;
        showSecretDialog(
                isExporting ? R.string.backup_export_title : R.string.backup_import_title,
                getString(isExporting ? R.string.backup_export_body : R.string.backup_import_body),
                R.string.backup_password_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                0,
                isExporting, // strength meter only when choosing a new backup password
                isExporting ? R.string.backup_export_confirm : R.string.backup_import_confirm,
                password -> {
                    if (password.length() < minLength) {
                        return getString(R.string.backup_password_too_short, minLength);
                    }
                    if (isExporting) {
                        executeExport(uri, password);
                    } else {
                        executeImport(uri, password);
                    }
                    return null;
                });
    }

    // Backup payload format written inside the encrypted file.
    // v2 holds decrypted values so the backup can be restored on another device
    // (the Keystore key that protects the local vault never leaves this phone).
    private static final int BACKUP_PAYLOAD_VERSION = 2;

    private void executeExport(Uri fileUri, String password) {
        Context appContext = requireContext().getApplicationContext();
        android.app.Activity host = requireActivity(); // result shows as a snackbar here (toast if it has closed)
        List<Credential> snapshot = new ArrayList<>(currentVault);

        // PBKDF2 (600k rounds) takes a moment, so keep it off the UI thread
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                // 1. Serialize Room Data to JSON, decrypting with the device key
                JSONArray items = new JSONArray();
                for (Credential c : snapshot) {
                    JSONObject obj = new JSONObject();
                    obj.put("title", c.getTitle());
                    obj.put("username", c.getUsername());
                    obj.put("password", EncryptionUtil.decryptPassword(c.getEncryptedPassword(), c.getEncryptionIv()));
                    obj.put("healthScore", c.getHealthScore());
                    String totp = TotpSecretCodec.reveal(c.getTotpSecret());
                    obj.put("totpSecret", totp != null ? totp : "");
                    items.put(obj);
                }
                JSONObject payload = new JSONObject();
                payload.put("version", BACKUP_PAYLOAD_VERSION);
                payload.put("items", items);

                // 2. Encrypt the JSON String with the backup password
                byte[] encryptedBytes = BackupEncryptionUtil.encryptBackup(payload.toString(), password);

                // 3. Write to the SAF File
                try (OutputStream os = appContext.getContentResolver().openOutputStream(fileUri)) {
                    if (os == null) throw new java.io.IOException("Could not open file");
                    os.write(encryptedBytes);
                }
                message = appContext.getString(R.string.backup_saved);

            } catch (Exception e) {
                android.util.Log.e("VaultBackup", "Export failed", e);
                message = appContext.getString(R.string.backup_save_failed);
            }
            String finalMessage = message;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Messages.showLong(host, finalMessage));
        });
    }

    private void executeImport(Uri fileUri, String password) {
        Context appContext = requireContext().getApplicationContext();
        android.app.Activity host = requireActivity(); // result shows as a snackbar here (toast if it has closed)

        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                // 1. Read the bytes from the SAF File
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream is = appContext.getContentResolver().openInputStream(fileUri)) {
                    if (is == null) throw new java.io.IOException("Could not open file");
                    int nRead;
                    byte[] data = new byte[16384];
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                }

                // 2. Decrypt back to JSON (reads both the current and the legacy file format)
                String jsonString = BackupEncryptionUtil.decryptBackup(buffer.toByteArray(), password);

                // 3. Parse JSON and Insert into DB (The Merge Option)
                List<Credential> imported = jsonString.trim().startsWith("{")
                        ? parseV2Backup(new JSONObject(jsonString))
                        : parseLegacyBackup(new JSONArray(jsonString));

                // Insert alongside existing data!
                for (Credential c : imported) {
                    vaultViewModel.insert(c);
                }
                message = appContext.getResources().getQuantityString(R.plurals.backup_restored, imported.size(), imported.size());

            } catch (javax.crypto.AEADBadTagException e) {
                message = appContext.getString(R.string.backup_wrong_password);
            } catch (Exception e) {
                android.util.Log.e("VaultBackup", "Import failed", e);
                message = appContext.getString(R.string.backup_unreadable);
            }
            String finalMessage = message;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Messages.showLong(host, finalMessage));
        });
    }

    // v2: plaintext values inside the encrypted file; re-encrypt with this device's key
    private List<Credential> parseV2Backup(JSONObject payload) throws Exception {
        JSONArray items = payload.getJSONArray("items");
        List<Credential> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject obj = items.getJSONObject(i);
            android.util.Pair<String, String> encrypted = EncryptionUtil.encryptPassword(obj.getString("password"));

            String totp = obj.optString("totpSecret", "");
            totp = totp.isEmpty() ? null : TotpSecretCodec.seal(totp); // Clean up empty strings to null for Room

            result.add(new Credential(
                    obj.getString("title"),
                    obj.optString("username", ""),
                    encrypted.first,
                    encrypted.second,
                    obj.optInt("healthScore", 0),
                    totp
            ));
        }
        return result;
    }

    // Legacy: passwords are ciphertext from the Keystore of the phone that made the backup,
    // so they only decrypt on that same phone. Kept so old backups still import there.
    private List<Credential> parseLegacyBackup(JSONArray jsonArray) throws Exception {
        List<Credential> result = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject obj = jsonArray.getJSONObject(i);

            String totp = obj.optString("totpSecret", "");
            totp = totp.isEmpty() ? null : TotpSecretCodec.seal(totp); // Clean up empty strings to null for Room

            result.add(new Credential(
                    obj.getString("title"),
                    obj.getString("username"),
                    obj.getString("encryptedPassword"),
                    obj.getString("encryptionIv"),
                    obj.getInt("healthScore"),
                    totp
            ));
        }
        return result;
    }

    // Every time the user looks at this tab, sync the switch with the actual system truth
    @Override
    public void onResume() {
        super.onResume();
        syncAutofillSwitchState();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            syncAutofillSwitchState();
        }
    }

    private void syncAutofillSwitchState() {
        if (autofillSwitch != null) {
            autofillSwitch.setChecked(isLedgerAutofillActive());
        }
    }

    // --- AUTO-LOCK ---

    private void showTimeoutDialog() {
        long currentTimeout = sharedPreferences.getLong("AUTO_LOCK_TIMEOUT", 0);
        int checkedItem = 0;
        for (int i = 0; i < timeoutValues.length; i++) {
            if (timeoutValues[i] == currentTimeout) {
                checkedItem = i;
                break;
            }
        }

        String[] options = {
                getString(R.string.security_auto_lock_immediately),
                getString(R.string.security_auto_lock_1m),
                getString(R.string.security_auto_lock_5m),
                getString(R.string.security_auto_lock_never)
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.security_auto_lock)
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    long selectedTimeout = timeoutValues[which];
                    dialog.dismiss();
                    if (selectedTimeout == -1 && currentTimeout != -1) {
                        // "Never" leaves the vault open in the background: confirm first
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.warn_never_lock_title)
                                .setMessage(R.string.warn_never_lock_body)
                                .setPositiveButton(R.string.warn_never_lock_confirm, (d, w) -> saveAutoLock(selectedTimeout))
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    } else {
                        saveAutoLock(selectedTimeout);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void saveAutoLock(long timeoutInMillis) {
        sharedPreferences.edit().putLong("AUTO_LOCK_TIMEOUT", timeoutInMillis).apply();
        updateAutoLockText(timeoutInMillis);
    }

    private void updateAutoLockText(long timeoutInMillis) {
        if (timeoutInMillis == 60000) textAutoLockStatus.setText(R.string.security_auto_lock_value_1m);
        else if (timeoutInMillis == 300000) textAutoLockStatus.setText(R.string.security_auto_lock_value_5m);
        else if (timeoutInMillis == -1) textAutoLockStatus.setText(R.string.security_auto_lock_value_never);
        else textAutoLockStatus.setText(R.string.security_auto_lock_value_immediately);
    }

    // --- PASSWORD REUSE CHECK ---

    private void runReuseAudit() {
        if (currentVault.size() < 2) {
            Messages.show(getContext(), R.string.reuse_not_enough);
            return;
        }

        HashMap<String, List<String>> passwordMap = new HashMap<>();

        try {
            for (Credential cred : currentVault) {
                String decrypted = EncryptionUtil.decryptPassword(cred.getEncryptedPassword(), cred.getEncryptionIv());
                passwordMap.computeIfAbsent(decrypted, k -> new ArrayList<>()).add(cred.getTitle());
            }

            StringBuilder report = new StringBuilder();
            for (Map.Entry<String, List<String>> entry : passwordMap.entrySet()) {
                if (entry.getValue().size() > 1) {
                    report.append("\n\n").append(getString(R.string.reuse_group, String.join(", ", entry.getValue())));
                }
            }

            if (report.length() == 0) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.reuse_none_title)
                        .setMessage(R.string.reuse_none_body)
                        .setPositiveButton(R.string.action_done, null)
                        .show();
            } else {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.reuse_found_title)
                        .setMessage(getString(R.string.reuse_found_body) + report)
                        .setPositiveButton(R.string.action_done, null)
                        .show();
            }

        } catch (Exception e) {
            android.util.Log.e("VaultAudit", "Audit failed", e);
            Messages.show(getContext(), R.string.reuse_failed);
        }
    }

    // --- REMEMBERED COMPUTERS ---

    private void showComputersSheet() {
        com.example.passmanager.security.KnownComputers known = new com.example.passmanager.security.KnownComputers(requireContext());
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_computers, null);
        sheet.setContentView(content);
        LinearLayout list = content.findViewById(R.id.computers_list);
        View empty = content.findViewById(R.id.computers_empty);

        Runnable refresh = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                List<com.example.passmanager.security.KnownComputers.Computer> computers = known.list();
                empty.setVisibility(computers.isEmpty() ? View.VISIBLE : View.GONE);
                java.text.DateFormat format = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
                android.util.TypedValue ripple = new android.util.TypedValue();
                requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);

                for (com.example.passmanager.security.KnownComputers.Computer computer : computers) {
                    View row = getLayoutInflater().inflate(R.layout.item_history, list, false);
                    ImageView icon = row.findViewById(R.id.history_icon);
                    icon.setImageResource(R.drawable.ic_computer);
                    icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                            MaterialColors.getColor(icon, com.google.android.material.R.attr.colorOnSurfaceVariant)));
                    String lastUsed = getString(R.string.computers_last_used, format.format(new java.util.Date(computer.lastUsed)));
                    ((TextView) row.findViewById(R.id.history_event)).setText(computer.name);
                    ((TextView) row.findViewById(R.id.history_time)).setText(lastUsed);
                    row.setBackgroundResource(ripple.resourceId);
                    row.setClickable(true);
                    row.setContentDescription(computer.name + ", " + lastUsed);
                    Runnable self = this;
                    row.setOnClickListener(v -> showComputerActions(known, computer, self));
                    list.addView(row);
                }
            }
        };
        refresh.run();
        sheet.show();
    }

    private void showComputerActions(com.example.passmanager.security.KnownComputers known,
                                     com.example.passmanager.security.KnownComputers.Computer computer,
                                     Runnable refresh) {
        String[] actions = {getString(R.string.computers_rename), getString(R.string.computers_forget)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(computer.name)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        renameComputer(known, computer, refresh);
                    } else {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.computers_forget_title, computer.name))
                                .setMessage(R.string.computers_forget_body)
                                .setPositiveButton(R.string.computers_forget, (d, w) -> {
                                    known.forget(computer.fingerprint);
                                    refresh.run();
                                    Messages.show(requireActivity(), getString(R.string.computers_forgotten, computer.name));
                                })
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void renameComputer(com.example.passmanager.security.KnownComputers known,
                                com.example.passmanager.security.KnownComputers.Computer computer,
                                Runnable refresh) {
        View form = getLayoutInflater().inflate(R.layout.dialog_secret_input, null);
        TextInputLayout layout = form.findViewById(R.id.layout_secret);
        EditText input = form.findViewById(R.id.input_secret);
        layout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        layout.setHint(R.string.confirm_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setTypeface(android.graphics.Typeface.DEFAULT);
        input.setText(computer.name);
        input.setSelection(input.length());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.computers_rename)
                .setView(form)
                .setPositiveButton(R.string.edit_save, (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!name.isEmpty()) {
                        known.rename(computer.fingerprint, name);
                        refresh.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // --- UNLOCK HISTORY ---

    private void showHistorySheet() {
        if (currentLogs.isEmpty()) {
            Messages.show(getContext(), R.string.history_empty);
            return;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_history, null);
        sheet.setContentView(content);
        LinearLayout list = content.findViewById(R.id.history_list);

        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        int success = MaterialColors.getColor(list, androidx.appcompat.R.attr.colorPrimary);
        int failure = MaterialColors.getColor(list, androidx.appcompat.R.attr.colorError);

        int limit = Math.min(currentLogs.size(), 20);
        for (int i = 0; i < limit; i++) {
            AuditLog log = currentLogs.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_history, list, false);

            String event = getString(historyLabel(log.getEventType(), log.isSuccessful()));
            String time = format.format(new java.util.Date(log.getTimestamp()));

            ImageView icon = row.findViewById(R.id.history_icon);
            icon.setImageResource(log.isSuccessful() ? R.drawable.ic_check_circle : R.drawable.ic_error);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(log.isSuccessful() ? success : failure));
            ((TextView) row.findViewById(R.id.history_event)).setText(event);
            ((TextView) row.findViewById(R.id.history_time)).setText(time);
            row.setContentDescription(event + ", " + time);
            list.addView(row);
        }

        sheet.show();
    }

    private static int historyLabel(String eventType, boolean successful) {
        if (eventType == null) eventType = "";
        switch (eventType) {
            case "PIN_MASTER":
            case "PIN_DURESS": // older builds logged duress unlocks separately; show them like any PIN unlock
                return R.string.history_pin_ok;
            case "PIN":
                return successful ? R.string.history_pin_ok : R.string.history_pin_failed;
            case "BIOMETRIC":
                return successful ? R.string.history_fingerprint_ok : R.string.history_fingerprint_failed;
            default:
                return successful ? R.string.history_other_ok : R.string.history_other_failed;
        }
    }

    // --- DURESS PIN ---

    private void showDuressPinDialog() {
        android.app.Activity host = requireActivity();
        showSecretDialog(
                R.string.duress_title,
                getString(R.string.duress_body),
                R.string.duress_hint,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                4,
                false,
                R.string.duress_save,
                pin -> {
                    if (pin.length() != 4) return getString(R.string.duress_needs_4);

                    String masterHash = sharedPreferences.getString("MASTER_PIN_HASH", "");
                    // PBKDF2 is slow on purpose, so run it off the UI thread
                    java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                        boolean sameAsMaster = PinHasher.verify(pin, masterHash);
                        String hashed = sameAsMaster ? null : PinHasher.hash(pin);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (sameAsMaster) {
                                Messages.showLong(host, R.string.duress_same_as_main);
                                return;
                            }
                            sharedPreferences.edit().putString("DURESS_PIN_HASH", hashed).apply();
                            Messages.show(host, R.string.duress_saved);
                        });
                    });
                    return null;
                });
    }
}
