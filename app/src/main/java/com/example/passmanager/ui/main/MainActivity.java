package com.example.passmanager.ui.main;

import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passmanager.BuildConfig;
import com.example.passmanager.PortraitCaptureActivity;
import com.example.passmanager.R;
import com.example.passmanager.SecurityUtil;
import com.example.passmanager.security.EncryptionUtil;
import com.example.passmanager.ui.viewmodel.VaultViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends ProtectedActivity {
    private List<com.example.passmanager.data.model.Credential> allCredentials = new ArrayList<>();
    private VaultViewModel vaultViewModel;
    private CredentialAdapter adapter;
    private EditText searchBar;
    private View searchContainer;

    private String pendingInjectionPayload = null;
    private String pendingUsernamePayload = null;
    private String pendingTitlePayload = null;
    // Login name sent (encrypted) to the extension so it can check it against the open tab
    private String pendingSiteName = null;

    // Tracks the currently active tab to prevent re-click animations
    private int currentTabId = R.id.nav_home;

    // The login detail sheet (shows a decrypted password); closed whenever Ledger leaves the screen
    private android.app.Dialog credentialSheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- SECURITY: Never show the vault without passing the lock screen (ProtectedActivity) ---
        if (redirectedToLockScreen()) return;

        // Screenshot blocking ("Hide screen contents") is applied to every screen by LedgerApp

        // Draw behind the status and navigation bars; system bar icons follow light/dark theme
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        // --- CHECK THREAT LEVEL (VOLATILE MEMORY) ---
        boolean isDuressMode = getIntent().getBooleanExtra("IS_DURESS_MODE", false);

        // --- 3. LINK ALL UI ELEMENTS TO IDs ---
        RecyclerView recyclerView = findViewById(R.id.recyclerView_credentials);
        FloatingActionButton fabAdd = findViewById(R.id.fab_add_password);
        searchBar = findViewById(R.id.edit_text_search);
        searchContainer = findViewById(R.id.search_container);
        FrameLayout fragmentContainer = findViewById(R.id.fragment_container);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

        if (isDuressMode) {
            bottomNav.getMenu().removeItem(R.id.nav_security);
        }

        // --- 4. SETUP RECYCLERVIEW & ADAPTER ---
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        adapter = new CredentialAdapter();
        recyclerView.setAdapter(adapter);

        // Flat list: inset dividers that start under the text, not under the monogram
        com.google.android.material.divider.MaterialDividerItemDecoration divider =
                new com.google.android.material.divider.MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL);
        divider.setDividerInsetStart(getResources().getDimensionPixelSize(R.dimen.list_divider_inset));
        divider.setLastItemDecorated(false);
        recyclerView.addItemDecoration(divider);

        // --- 5. DATABASE OBSERVER ---
        vaultViewModel = new ViewModelProvider(this).get(VaultViewModel.class);
        if (!isDuressMode && savedInstanceState == null) {
            vaultViewModel.upgradeLegacyTotpSecrets(); // Encrypt any 2FA secrets saved before encryption at rest
        }
        vaultViewModel.getAllCredentials().observe(this, credentials -> {
            if (isDuressMode) {
                allCredentials = new java.util.ArrayList<>();
                adapter.setCredentials(new java.util.ArrayList<>());
            } else {
                allCredentials = credentials;
                adapter.setCredentials(credentials);
            }
        });

        // --- 6. SEARCH BAR LOGIC ---
        searchBar.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                filterVault(s.toString());
            }
        });

        // --- 7. NAV BAR LOGIC ---
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();

            if (itemId == currentTabId) return true;
            currentTabId = itemId;

            boolean wasFragmentContainerHidden = fragmentContainer.getVisibility() == View.GONE;

            if (itemId == R.id.nav_home) {
                recyclerView.setVisibility(View.GONE);
                fabAdd.setVisibility(View.GONE);
                searchContainer.setVisibility(View.GONE);
                fragmentContainer.setVisibility(View.VISIBLE);
                if (wasFragmentContainerHidden) playEnterAnimation(fragmentContainer);
                tabTransaction().replace(R.id.fragment_container, new HomeFragment()).commit();
                return true;
            } else if (itemId == R.id.nav_vault) {
                fragmentContainer.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                searchContainer.setVisibility(View.VISIBLE);
                playEnterAnimation(recyclerView);
                fabAdd.setVisibility(isDuressMode ? View.GONE : View.VISIBLE);
                return true;
            } else if (itemId == R.id.nav_authenticator) {
                recyclerView.setVisibility(View.GONE);
                fabAdd.setVisibility(View.GONE);
                searchContainer.setVisibility(View.GONE);
                fragmentContainer.setVisibility(View.VISIBLE);
                if (wasFragmentContainerHidden) playEnterAnimation(fragmentContainer);
                tabTransaction().replace(R.id.fragment_container, new AuthenticatorFragment()).commit();
                return true;
            } else if (itemId == R.id.nav_security) {
                recyclerView.setVisibility(View.GONE);
                fabAdd.setVisibility(View.GONE);
                searchContainer.setVisibility(View.GONE);
                fragmentContainer.setVisibility(View.VISIBLE);
                if (wasFragmentContainerHidden) playEnterAnimation(fragmentContainer);
                tabTransaction().replace(R.id.fragment_container, new SecurityFragment()).commit();
                return true;
            } else if (itemId == R.id.nav_about) {
                recyclerView.setVisibility(View.GONE);
                fabAdd.setVisibility(View.GONE);
                searchContainer.setVisibility(View.GONE);
                fragmentContainer.setVisibility(View.VISIBLE);
                if (wasFragmentContainerHidden) playEnterAnimation(fragmentContainer);
                tabTransaction().replace(R.id.fragment_container, new AboutFragment()).commit();
                return true;
            }
            return false;
        });

        // --- 8. SET DEFAULT STATE (HOME SCREEN) ---
        recyclerView.setVisibility(View.GONE);
        fabAdd.setVisibility(View.GONE);
        searchContainer.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);
        getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, new HomeFragment()).commit();

        // --- 9. ITEM ACTIONS (CLICK & SWIPE) ---
        adapter.setOnItemClickListener(credential -> {
            authenticateUser(() -> {
                try {
                    String decryptedPassword = EncryptionUtil.decryptPassword(credential.getEncryptedPassword(), credential.getEncryptionIv());
                    showCredentialSheet(credential, decryptedPassword);
                } catch (Exception e) {
                    Messages.showLong(MainActivity.this, R.string.error_decrypt);
                }
            });
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                com.example.passmanager.data.model.Credential targetCredential = adapter.getCredentialAt(position);
                adapter.notifyItemChanged(position);

                if (direction == ItemTouchHelper.RIGHT) {
                    authenticateUser(() -> confirmDelete(targetCredential));
                } else if (direction == ItemTouchHelper.LEFT) {
                    authenticateUser(() -> openEditor(targetCredential));
                }
            }

            // Show what the swipe will do: delete (right) on the error container, edit (left) on secondary container
            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0) {
                    drawSwipeBackground(c, viewHolder.itemView, dX);
                }
                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        }).attachToRecyclerView(recyclerView);

        // Same edit/delete as the swipes, reachable from TalkBack's actions menu
        adapter.setAccessibilityActions(
                credential -> authenticateUser(() -> openEditor(credential)),
                credential -> authenticateUser(() -> confirmDelete(credential)));

        fabAdd.setOnClickListener(v -> showAddBottomSheet());
    }

    private void filterVault(String text) {
        if (adapter == null) return;
        List<com.example.passmanager.data.model.Credential> filteredList = new ArrayList<>();
        // User-typed text: compare in the phone's own locale on both sides
        java.util.Locale locale = java.util.Locale.getDefault();
        String query = text.toLowerCase(locale);
        for (com.example.passmanager.data.model.Credential item : allCredentials) {
            String title = item.getTitle() != null ? item.getTitle().toLowerCase(locale) : "";
            String username = item.getUsername() != null ? item.getUsername().toLowerCase(locale) : "";
            if (title.contains(query) || username.contains(query)) {
                filteredList.add(item);
            }
        }
        adapter.filterList(filteredList);
    }

    // Package-private: Home uses it to gate editing a login, same as the Vault tab.
    // In a duress session the duress PIN keeps working, so the decoy vault behaves normally.
    void authenticateUser(Runnable onSuccessAction) {
        boolean isDuressMode = getIntent().getBooleanExtra("IS_DURESS_MODE", false);
        VaultUnlock.authenticate(this, isDuressMode, duress -> onSuccessAction.run());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // e.g. "GitHub saved" left by the Add/Edit login screen that just closed
        CharSequence pending = Messages.takePending();
        if (pending != null) Messages.show(this, pending);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Don't leave a revealed password on screen while Ledger is out of sight
        if (credentialSheet != null && credentialSheet.isShowing()) credentialSheet.dismiss();
    }

    // --- VAULT ACTIONS ---

    // Login detail sheet: password hidden until "Show"; copy, fill on computer, edit.
    private void showCredentialSheet(com.example.passmanager.data.model.Credential credential, String decryptedPassword) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_credential, null);
        sheet.setContentView(content);
        secureDialogWindow(sheet); // the sheet is its own window; the activity's setting doesn't cover it

        String title = credential.getTitle() != null ? credential.getTitle() : "";
        String username = credential.getUsername() != null ? credential.getUsername() : "";

        ((android.widget.TextView) content.findViewById(R.id.sheet_monogram)).setText(CredentialAdapter.monogramFor(title));
        ((android.widget.TextView) content.findViewById(R.id.sheet_title)).setText(title);
        android.widget.TextView usernameView = content.findViewById(R.id.sheet_username);
        usernameView.setText(username);
        usernameView.setVisibility(username.isEmpty() ? View.GONE : View.VISIBLE);

        int score = credential.getHealthScore();
        CredentialAdapter.bindStrengthLabel(content.findViewById(R.id.sheet_strength), score, getString(CredentialAdapter.strengthLabel(score)));

        android.widget.TextView passwordView = content.findViewById(R.id.sheet_password);
        com.google.android.material.button.MaterialButton toggle = content.findViewById(R.id.sheet_toggle_password);
        passwordView.setText(decryptedPassword);
        passwordView.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        toggle.setOnClickListener(v -> {
            boolean hidden = passwordView.getTransformationMethod() != null;
            passwordView.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            toggle.setIconResource(hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
            toggle.setContentDescription(getString(hidden ? R.string.sheet_hide_password : R.string.sheet_show_password));
        });

        content.findViewById(R.id.sheet_copy).setOnClickListener(v -> {
            com.example.passmanager.security.ClipboardHelper.copySensitive(MainActivity.this, "Vault Password", decryptedPassword);
            Messages.show(MainActivity.this, R.string.copied_password);
            sheet.dismiss();
        });

        content.findViewById(R.id.sheet_fill_computer).setOnClickListener(v -> {
            sheet.dismiss();
            pendingInjectionPayload = decryptedPassword;
            pendingUsernamePayload = credential.getUsername();
            pendingSiteName = credential.getTitle();
            launchFillScanner();
        });

        content.findViewById(R.id.sheet_edit).setOnClickListener(v -> {
            sheet.dismiss();
            openEditor(credential);
        });

        credentialSheet = sheet;
        sheet.show();
    }

    private void launchFillScanner() {
        com.journeyapps.barcodescanner.ScanOptions options = new com.journeyapps.barcodescanner.ScanOptions();
        options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.scan_prompt_fill));
        options.setCameraId(0);
        options.setBeepEnabled(false);
        options.setCaptureActivity(PortraitCaptureActivity.class);
        options.setOrientationLocked(true);
        barcodeLauncher.launch(options);
    }

    void openEditor(com.example.passmanager.data.model.Credential credential) {
        android.content.Intent intent = new android.content.Intent(MainActivity.this, EditCredentialActivity.class);
        intent.putExtra("CREDENTIAL_ID", credential.getId());
        intent.putExtra("CREDENTIAL_TITLE", credential.getTitle());
        intent.putExtra("CREDENTIAL_USERNAME", credential.getUsername());
        intent.putExtra("CREDENTIAL_ENCRYPTED_PW", credential.getEncryptedPassword());
        intent.putExtra("CREDENTIAL_IV", credential.getEncryptionIv());
        intent.putExtra("CREDENTIAL_TOTP_SECRET", credential.getTotpSecret());
        startActivity(intent);
    }

    // Confirm, delete, then offer Undo (re-inserts the same row, same ID)
    private void confirmDelete(com.example.passmanager.data.model.Credential credential) {
        String title = credential.getTitle() != null ? credential.getTitle() : "";
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(MainActivity.this)
                .setTitle(getString(R.string.delete_title, title))
                .setMessage(R.string.delete_body)
                .setPositiveButton(R.string.delete_confirm, (d, which) -> {
                    vaultViewModel.delete(credential);
                    Messages.showWithAction(MainActivity.this, getString(R.string.deleted, title),
                            R.string.action_undo, v -> vaultViewModel.insert(credential));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
    }

    // Swipe feedback: colored background plus an icon at the edge being revealed
    private void drawSwipeBackground(android.graphics.Canvas canvas, View item, float dX) {
        boolean deleting = dX > 0;
        int background = com.google.android.material.color.MaterialColors.getColor(item,
                deleting ? com.google.android.material.R.attr.colorErrorContainer : com.google.android.material.R.attr.colorSecondaryContainer);
        int foreground = com.google.android.material.color.MaterialColors.getColor(item,
                deleting ? com.google.android.material.R.attr.colorOnErrorContainer : com.google.android.material.R.attr.colorOnSecondaryContainer);

        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(background);
        if (deleting) {
            canvas.drawRect(item.getLeft(), item.getTop(), item.getLeft() + dX, item.getBottom(), paint);
        } else {
            canvas.drawRect(item.getRight() + dX, item.getTop(), item.getRight(), item.getBottom(), paint);
        }

        android.graphics.drawable.Drawable icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this, deleting ? R.drawable.ic_delete : R.drawable.ic_edit);
        if (icon == null) return;
        icon = icon.mutate();
        icon.setTint(foreground);
        int size = getResources().getDimensionPixelSize(R.dimen.icon_size);
        int margin = getResources().getDimensionPixelSize(R.dimen.space_6);
        int top = item.getTop() + (item.getHeight() - size) / 2;
        int left = deleting ? item.getLeft() + margin : item.getRight() - margin - size;
        // Only draw once the revealed area can hold the icon
        if (Math.abs(dX) > margin + size) {
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
    }

    private void secureDialogWindow(android.app.Dialog dialog) {
        com.example.passmanager.security.ScreenPrivacy.apply(dialog);
    }

    // --- SHELL HELPERS ---

    // Edge to edge: pad the content below the status bar (and away from side cutouts).
    // The navigation bar view pads itself for the gesture/nav bar at the bottom.
    private void applySystemBarInsets() {
        View root = findViewById(R.id.main_root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });
    }

    // Material "fade through" between navigation-bar tabs; skipped when the user turned animations off
    private androidx.fragment.app.FragmentTransaction tabTransaction() {
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            transaction.setCustomAnimations(R.anim.fade_through_enter, R.anim.fade_through_exit);
        }
        return transaction;
    }

    private void playEnterAnimation(View view) {
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_through_enter));
        }
    }

    // --- OPTICAL KEY ENCRYPTION ENGINE (E2EE) ---
    private android.util.Pair<String, String> encryptForTransmission(String plaintext, String base64Key) throws Exception {
        // 1. Decode the AES key pulled from the optical QR code
        byte[] decodedKey = android.util.Base64.decode(base64Key, android.util.Base64.DEFAULT);
        javax.crypto.SecretKey originalKey = new javax.crypto.spec.SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");

        // 2. Generate an ephemeral 12-byte IV for this specific transmission
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.spec.GCMParameterSpec parameterSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);

        // 3. Encrypt the payload using AES-256-GCM
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, originalKey, parameterSpec);
        byte[] cipherText = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 4. Encode the IV and Ciphertext for safe network transit
        String base64Iv = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP);
        String base64Cipher = android.util.Base64.encodeToString(cipherText, android.util.Base64.NO_WRAP);

        return new android.util.Pair<>(base64Iv, base64Cipher);
    }

    // --- THE CAMERA SCANNER & NETWORK BRIDGE ---
    private final androidx.activity.result.ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> barcodeLauncher =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), result -> {

                if (result.getContents() == null) {
                    Messages.show(this, R.string.scan_cancelled);
                    pendingInjectionPayload = null;
                    pendingUsernamePayload = null;
                    pendingTitlePayload = null;
                    pendingSiteName = null;
                } else {
                    final String capturedPass = pendingInjectionPayload;
                    final String capturedUser = pendingUsernamePayload;
                    final String capturedTitle = pendingTitlePayload;
                    final String capturedSiteName = pendingSiteName;

                    pendingInjectionPayload = null;
                    pendingUsernamePayload = null;
                    pendingTitlePayload = null;
                    pendingSiteName = null;

                    if (capturedPass == null || capturedUser == null) {
                        return; // Scan without a pending login: nothing to send
                    }

                    // 1. Is this a code from a Ledger extension, and from which computer?
                    com.example.passmanager.security.BridgeTrust.Payload qr;
                    try {
                        qr = com.example.passmanager.security.BridgeTrust.parse(result.getContents());
                    } catch (Exception e) {
                        Messages.showLong(this, R.string.bridge_bad_qr);
                        return;
                    }
                    com.example.passmanager.security.BridgeTrust.Status status =
                            com.example.passmanager.security.BridgeTrust.check(qr, System.currentTimeMillis());

                    // 2. Ask before anything leaves the phone (and before a new account is saved)
                    showSendConfirmation(qr, status, capturedUser, capturedSiteName, () -> {
                        if (capturedTitle != null) saveNewAccount(capturedTitle, capturedUser, capturedPass);
                        sendToComputer(qr, capturedUser, capturedPass, capturedSiteName);
                    });
                }
            });

    // Before sending: say where the login is going.
    //  - Known computer (signed by an install the user remembered): one tap.
    //  - New computer: check words that must match the Ledger toolbar pop-up, optional "remember".
    //  - Old unsigned extension: same warning, no words.
    //  - Bad signature or expired: nothing can be sent.
    private void showSendConfirmation(com.example.passmanager.security.BridgeTrust.Payload qr,
                                      com.example.passmanager.security.BridgeTrust.Status status,
                                      String username, String siteName, Runnable send) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_confirm_send, null);
        sheet.setContentView(content);
        secureDialogWindow(sheet);

        android.widget.ImageView icon = content.findViewById(R.id.confirm_icon);
        android.widget.TextView title = content.findViewById(R.id.confirm_title);
        android.widget.TextView body = content.findViewById(R.id.confirm_body);
        View wordsBox = content.findViewById(R.id.confirm_words_box);
        android.widget.TextView words = content.findViewById(R.id.confirm_words);
        View rememberRow = content.findViewById(R.id.confirm_remember_row);
        com.google.android.material.materialswitch.MaterialSwitch remember = content.findViewById(R.id.confirm_remember);
        View nameLayout = content.findViewById(R.id.confirm_name_layout);
        android.widget.EditText nameInput = content.findViewById(R.id.confirm_name);
        com.google.android.material.button.MaterialButton sendButton = content.findViewById(R.id.confirm_send);

        com.example.passmanager.security.KnownComputers known = new com.example.passmanager.security.KnownComputers(this);
        String fingerprint = qr.fingerprint();
        com.example.passmanager.security.KnownComputers.Computer computer =
                status == com.example.passmanager.security.BridgeTrust.Status.SIGNED ? known.get(fingerprint) : null;
        String loginName = siteName != null && !siteName.isEmpty() ? siteName : getString(R.string.confirm_this_login);

        int warning = com.google.android.material.color.MaterialColors.getColor(icon, R.attr.colorAttention);
        int danger = com.google.android.material.color.MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorError);
        int calm = com.google.android.material.color.MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorPrimary);

        if (status == com.example.passmanager.security.BridgeTrust.Status.INVALID) {
            icon.setImageResource(R.drawable.ic_error);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(danger));
            title.setText(R.string.confirm_invalid_title);
            body.setText(R.string.confirm_invalid_body);
            sendButton.setVisibility(View.GONE);
            ((com.google.android.material.button.MaterialButton) content.findViewById(R.id.confirm_cancel)).setText(R.string.confirm_close);
        } else if (computer != null) {
            icon.setImageResource(R.drawable.ic_shield);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(calm));
            title.setText(getString(R.string.confirm_known_title, loginName, computer.name));
            body.setText(R.string.confirm_known_body);
            sendButton.setText(R.string.confirm_send);
        } else {
            boolean signed = status == com.example.passmanager.security.BridgeTrust.Status.SIGNED;
            icon.setImageResource(R.drawable.ic_warning);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(warning));
            title.setText(signed ? R.string.confirm_new_title : R.string.confirm_old_title);
            body.setText(signed ? R.string.confirm_new_body : R.string.confirm_old_body);
            if (signed) {
                String[] checkWords = com.example.passmanager.security.BridgeTrust.checkWords(qr.publicKey, qr.room, loadCheckWords());
                words.setText(String.join(" · ", checkWords));
                wordsBox.setVisibility(View.VISIBLE);
                rememberRow.setVisibility(View.VISIBLE);
                rememberRow.setOnClickListener(v -> remember.toggle());
                remember.setOnCheckedChangeListener((b, on) -> nameLayout.setVisibility(on ? View.VISIBLE : View.GONE));
                sendButton.setText(R.string.confirm_send_words_match);
            } else {
                sendButton.setText(R.string.confirm_send);
            }
        }

        content.findViewById(R.id.confirm_cancel).setOnClickListener(v -> sheet.dismiss());
        sendButton.setOnClickListener(v -> {
            if (computer != null) {
                known.markUsed(fingerprint);
            } else if (fingerprint != null && remember.isChecked()) {
                String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                known.remember(fingerprint, name.isEmpty() ? getString(R.string.confirm_name_default) : name);
            }
            sheet.dismiss();
            send.run();
        });
        sheet.show();
    }

    // Shared with the extension (Extension-pass/lib/check_words.txt); BridgeTrustTest keeps them identical
    private java.util.List<String> loadCheckWords() {
        java.util.List<String> list = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                getResources().openRawResource(R.raw.check_words), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) list.add(line.trim());
            }
        } catch (java.io.IOException e) {
            android.util.Log.e("LedgerBridge", "Could not read check words", e);
        }
        return list;
    }

    // "New account on computer": save the generated login once the user has confirmed the computer
    private void saveNewAccount(String title, String username, String password) {
        try {
            android.util.Pair<String, String> encryptedData =
                    com.example.passmanager.security.EncryptionUtil.encryptPassword(password);
            vaultViewModel.insert(new com.example.passmanager.data.model.Credential(title, username,
                    encryptedData.first, encryptedData.second,
                    com.example.passmanager.PasswordStrength.score(password), null));
            Messages.show(MainActivity.this, getString(R.string.bridge_saved_new, title));
        } catch (Exception e) {
            Messages.showLong(MainActivity.this, R.string.error_encrypt);
        }
    }

    // Encrypt the login with the code's one-time key and send it through the relay
    private void sendToComputer(com.example.passmanager.security.BridgeTrust.Payload qr,
                                String capturedUser, String capturedPass, String capturedSiteName) {
        try {
            String targetRoom = qr.room;
            String opticalKey = qr.key;

            // Encrypt username + password together using the Optical Key,
            //    so the relay never sees either in plaintext
            org.json.JSONObject secretBundle = new org.json.JSONObject();
            secretBundle.put("u", capturedUser);
            secretBundle.put("p", capturedPass);
            if (capturedSiteName != null) secretBundle.put("t", capturedSiteName); // for the extension's site check
            android.util.Pair<String, String> encryptedTransmission = encryptForTransmission(secretBundle.toString(), opticalKey);
            String transmissionIv = encryptedTransmission.first;
            String transmissionPayload = encryptedTransmission.second;

            // Connect to the room on Ledger's own relay (relay/). No API key: the room ID from the
            // QR code is the only address, and only ciphertext goes through it.
            String relayUrl = RelayAddress.roomUrl(BuildConfig.RELAY_URL, targetRoom);
            if (relayUrl == null) {
                Messages.showLong(this, BuildConfig.RELAY_URL.isEmpty() ? R.string.bridge_no_relay : R.string.bridge_bad_qr);
                return;
            }
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request request = new okhttp3.Request.Builder().url(relayUrl).build();

            Messages.show(this, R.string.bridge_sending);

            // 4. Fire the BLIND payload over WebSockets
            client.newWebSocket(request, new okhttp3.WebSocketListener() {
                @Override
                public void onOpen(okhttp3.WebSocket webSocket, okhttp3.Response response) {
                    try {
                        org.json.JSONObject payloadWrapper = new org.json.JSONObject();
                        payloadWrapper.put("room", targetRoom);

                        // Only ciphertext goes to the relay
                        payloadWrapper.put("payload", transmissionPayload);
                        payloadWrapper.put("iv", transmissionIv);

                        webSocket.send(payloadWrapper.toString());
                        // One message per session: close gracefully once it is queued (the close frame goes after it)
                        webSocket.close(1000, null);

                        runOnUiThread(() -> Messages.show(MainActivity.this, R.string.bridge_sent));
                    } catch (Exception e) {
                        android.util.Log.e("LedgerBridge", "Relay send failed", e);
                        webSocket.close(1011, null);
                    }
                }

                @Override
                public void onFailure(okhttp3.WebSocket webSocket, Throwable t, okhttp3.Response response) {
                    runOnUiThread(() -> Messages.showLong(MainActivity.this, R.string.bridge_failed));
                }
            });

        } catch (Exception e) {
            android.util.Log.e("LedgerBridge", "E2EE Setup Failed: ", e);
            Messages.showLong(this, R.string.bridge_bad_qr);
        }
    }

    // "New account on computer": Ledger generates the password, saves the login, then fills it via the extension
    private void showSignUpInjectorDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_new_account, null);
        com.google.android.material.textfield.TextInputLayout titleLayout = form.findViewById(R.id.layout_new_title);
        com.google.android.material.textfield.TextInputLayout usernameLayout = form.findViewById(R.id.layout_new_username);
        android.widget.EditText titleInput = form.findViewById(R.id.input_new_title);
        android.widget.EditText usernameInput = form.findViewById(R.id.input_new_username);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_account_title)
                .setMessage(R.string.new_account_body)
                .setView(form)
                .setPositiveButton(R.string.new_account_continue, null) // wired below so errors keep the dialog open
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newTitle = titleInput.getText().toString().trim();
            String newUser = usernameInput.getText().toString().trim();

            titleLayout.setError(newTitle.isEmpty() ? getString(R.string.field_site_required) : null);
            usernameLayout.setError(newUser.isEmpty() ? getString(R.string.field_username_required) : null);
            if (newTitle.isEmpty() || newUser.isEmpty()) return;

            pendingTitlePayload = newTitle;
            pendingSiteName = newTitle;
            pendingUsernamePayload = newUser;
            pendingInjectionPayload = SecurityUtil.generateSecurePassword();

            dialog.dismiss();
            launchFillScanner();
        }));
        dialog.show();
    }

    // Also opened from the empty state on Home
    void showAddBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog bottomSheetDialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.view.View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_add, null);
        bottomSheetDialog.setContentView(sheetView);

        android.widget.LinearLayout btnManual = sheetView.findViewById(R.id.btn_manual_entry);
        android.widget.LinearLayout btnGenerate = sheetView.findViewById(R.id.btn_generate_inject);

        btnManual.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            startActivity(new android.content.Intent(MainActivity.this, com.example.passmanager.ui.main.AddCredentialActivity.class));
        });

        btnGenerate.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            showSignUpInjectorDialog();
        });

        bottomSheetDialog.show();
    }
}
