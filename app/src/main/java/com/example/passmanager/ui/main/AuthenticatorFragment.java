package com.example.passmanager.ui.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passmanager.R;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.security.TotpEngine;
import com.example.passmanager.security.TotpSecretCodec;
import com.example.passmanager.ui.viewmodel.VaultViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

public class AuthenticatorFragment extends Fragment {

    private VaultViewModel vaultViewModel;
    private AuthenticatorAdapter adapter;
    private List<Credential> allVaultCredentials = new ArrayList<>();

    // The Master Clock
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            if (adapter != null && adapter.getItemCount() > 0) {
                // Send a "TICK" payload so it updates the rings smoothly without jittering the list
                adapter.notifyItemRangeChanged(0, adapter.getItemCount(), "TICK");
            }
            // Pulse again in exactly 1 second
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_authenticator, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_authenticator);
        View emptyState = view.findViewById(R.id.empty_codes);
        TextView emptyBody = view.findViewById(R.id.text_empty_auth);
        View hint = view.findViewById(R.id.text_codes_hint);
        FloatingActionButton fabAdd = view.findViewById(R.id.fab_add_auth);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AuthenticatorAdapter(requireContext());
        recyclerView.setAdapter(adapter);
        MaterialDividerItemDecoration divider = new MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL);
        divider.setDividerInsetStart(getResources().getDimensionPixelSize(R.dimen.list_divider_inset));
        divider.setLastItemDecorated(false);
        recyclerView.addItemDecoration(divider);

        // --- DURESS MODE CHECK ---
        boolean isDuressMode = requireActivity().getIntent().getBooleanExtra("IS_DURESS_MODE", false);

        // If in Duress Mode, hide the ability to add new keys
        if (isDuressMode) {
            fabAdd.setVisibility(View.GONE);
        }

        vaultViewModel = new ViewModelProvider(requireActivity()).get(VaultViewModel.class);

        // 1. Observe the Vault and Filter for 2FA Codes
        vaultViewModel.getAllCredentials().observe(getViewLifecycleOwner(), credentials -> {
            List<Credential> authList = new ArrayList<>();

            // THE DURESS INTERCEPTOR: If compromised, the list is always empty
            if (!isDuressMode && credentials != null) {
                allVaultCredentials = credentials; // Keep a copy of everything for the "Link" sheet
                for (Credential cred : credentials) {
                    if (cred.getTotpSecret() != null && !cred.getTotpSecret().trim().isEmpty()) {
                        authList.add(cred);
                    }
                }
            }

            adapter.setCredentials(authList);
            boolean empty = authList.isEmpty();
            recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
            hint.setVisibility(empty ? View.GONE : View.VISIBLE);
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            emptyBody.setText(isDuressMode ? R.string.codes_empty_body_duress : R.string.codes_empty_body);
        });

        // 2. The Link Button
        fabAdd.setOnClickListener(v -> showLinkSheet());

        return view;
    }

    // "Link a 2FA code": choose a login without a code, paste the website's Base32 secret
    private void showLinkSheet() {
        // Find accounts that DON'T have a 2FA code yet
        List<Credential> eligibleAccounts = new ArrayList<>();
        List<String> accountNames = new ArrayList<>();
        for (Credential c : allVaultCredentials) {
            if (c.getTotpSecret() == null || c.getTotpSecret().trim().isEmpty()) {
                eligibleAccounts.add(c);
                String username = c.getUsername() != null && !c.getUsername().isEmpty() ? " (" + c.getUsername() + ")" : "";
                accountNames.add(c.getTitle() + username);
            }
        }

        if (eligibleAccounts.isEmpty()) {
            Messages.showLong(getContext(), R.string.codes_nothing_to_link);
            return;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_link_2fa, null);
        sheet.setContentView(content);
        // The secret key is visible while typing; keep it out of screenshots and recents
        com.example.passmanager.security.ScreenPrivacy.apply(sheet);

        TextInputLayout accountLayout = content.findViewById(R.id.layout_link_account);
        MaterialAutoCompleteTextView accountInput = content.findViewById(R.id.input_link_account);
        TextInputLayout secretLayout = content.findViewById(R.id.layout_link_secret);
        EditText secretInput = content.findViewById(R.id.input_link_secret);

        accountInput.setSimpleItems(accountNames.toArray(new String[0]));
        final int[] selected = {eligibleAccounts.size() == 1 ? 0 : -1};
        if (selected[0] == 0) accountInput.setText(accountNames.get(0), false);
        accountInput.setOnItemClickListener((parent, v, position, id) -> {
            selected[0] = position;
            accountLayout.setError(null);
        });

        // Paste button inside the secret field
        secretLayout.setEndIconOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                secretInput.setText(clip.getItemAt(0).getText().toString().trim());
                secretInput.setSelection(secretInput.length());
            }
        });
        secretInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { secretLayout.setError(null); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        content.findViewById(R.id.btn_link_confirm).setOnClickListener(v -> {
            String secret = secretInput.getText().toString().trim();
            boolean ok = true;
            if (selected[0] < 0) {
                accountLayout.setError(getString(R.string.codes_link_pick_account));
                ok = false;
            }
            if (!TotpEngine.isValidSecret(secret)) {
                secretLayout.setError(getString(R.string.codes_link_invalid));
                ok = false;
            }
            if (!ok) return;

            Credential targetCred = eligibleAccounts.get(selected[0]);
            try {
                targetCred.setTotpSecret(TotpSecretCodec.seal(secret)); // Encrypted at rest
            } catch (Exception e) {
                Messages.showLong(getContext(), R.string.error_encrypt);
                return;
            }

            vaultViewModel.update(targetCred);
            Messages.show(getContext(), getString(R.string.codes_linked, targetCred.getTitle()));
            sheet.dismiss();
        });

        sheet.show();
    }

    // --- MANAGE THE CLOCK LIFECYCLE ---
    // We only want the clock ticking if the user is actually looking at the tab!
    @Override
    public void onResume() {
        super.onResume();
        clockHandler.post(clockRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockRunnable);
    }
}
