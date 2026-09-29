package com.example.passmanager.ui.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passmanager.PasswordStrength;
import com.example.passmanager.R;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.ui.viewmodel.VaultViewModel;
import com.google.android.material.divider.MaterialDividerItemDecoration;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Home: greeting, the vault balance (strong / fair / weak), and the logins that need attention.
 */
public class HomeFragment extends Fragment {

    private TextView textGreeting;
    private TextView textTotal;
    private VaultBalanceBar balanceBar;
    private View balanceLegend;
    private TextView textStrong;
    private TextView textFair;
    private TextView textWeak;
    private TextView textAttentionTitle;
    private TextView textAttentionHint;
    private RecyclerView attentionList;
    private View emptyState;

    private CredentialAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        textGreeting = view.findViewById(R.id.textHeaderGreeting);
        textTotal = view.findViewById(R.id.text_total_logins);
        balanceBar = view.findViewById(R.id.balance_bar);
        balanceLegend = view.findViewById(R.id.balance_legend);
        textStrong = view.findViewById(R.id.text_strong_count);
        textFair = view.findViewById(R.id.text_fair_count);
        textWeak = view.findViewById(R.id.text_weak_count);
        textAttentionTitle = view.findViewById(R.id.text_attention_title);
        textAttentionHint = view.findViewById(R.id.text_attention_hint);
        attentionList = view.findViewById(R.id.recyclerView_vulnerabilities);
        emptyState = view.findViewById(R.id.empty_state);

        textGreeting.setText(greeting());

        // --- Logins that need attention ---
        attentionList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CredentialAdapter();
        attentionList.setAdapter(adapter);
        MaterialDividerItemDecoration divider = new MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL);
        divider.setDividerInsetStart(getResources().getDimensionPixelSize(R.dimen.list_divider_inset));
        divider.setLastItemDecorated(false);
        attentionList.addItemDecoration(divider);

        // --- CHECK THREAT LEVEL ---
        boolean isDuressMode = requireActivity().getIntent().getBooleanExtra("IS_DURESS_MODE", false);

        // Empty vault: offer to add a login (never in duress mode, which hides adding)
        View addButton = view.findViewById(R.id.btn_empty_add);
        addButton.setVisibility(isDuressMode ? View.GONE : View.VISIBLE);
        addButton.setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).showAddBottomSheet();
        });

        // Tap to change a weak password. Gated by the same unlock as the Vault tab,
        // because the edit screen shows the decrypted password.
        adapter.setOnItemClickListener(credential -> {
            if (requireActivity() instanceof MainActivity) {
                MainActivity activity = (MainActivity) requireActivity();
                activity.authenticateUser(() -> activity.openEditor(credential));
            }
        });

        VaultViewModel vaultViewModel = new ViewModelProvider(requireActivity()).get(VaultViewModel.class);
        vaultViewModel.getAllCredentials().observe(getViewLifecycleOwner(), credentials -> {
            // THE ILLUSION: in duress mode the vault always looks empty
            bind(isDuressMode || credentials == null ? new ArrayList<>() : credentials);
        });

        return view;
    }

    private void bind(List<Credential> credentials) {
        int strong = 0, fair = 0, weak = 0;
        List<Credential> needsAttention = new ArrayList<>();
        for (Credential cred : credentials) {
            int score = cred.getHealthScore();
            if (score == PasswordStrength.WEAK) weak++;
            else if (score == PasswordStrength.FAIR) fair++;
            else strong++;
            if (PasswordStrength.needsAttention(score)) needsAttention.add(cred);
        }

        boolean empty = credentials.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        attentionList.setVisibility(empty ? View.GONE : View.VISIBLE);
        textAttentionTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        textAttentionHint.setVisibility(empty ? View.GONE : View.VISIBLE);

        textTotal.setText(getResources().getQuantityString(R.plurals.home_total_logins, credentials.size(), credentials.size()));
        balanceBar.setCounts(strong, fair, weak);
        textStrong.setText(getString(R.string.home_count_strong, strong));
        textFair.setText(getString(R.string.home_count_fair, fair));
        textWeak.setText(getString(R.string.home_count_weak, weak));
        balanceLegend.setContentDescription(getString(R.string.home_balance_description, strong, fair, weak));

        textAttentionHint.setText(needsAttention.isEmpty() ? R.string.home_all_strong : R.string.home_attention_hint);
        adapter.setCredentials(needsAttention);
    }

    private String greeting() {
        String firstName = requireContext()
                .getSharedPreferences("VaultSecurityPrefs", android.content.Context.MODE_PRIVATE)
                .getString("USER_FIRST_NAME", "")
                .trim();
        boolean hasName = !firstName.isEmpty();

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) {
            return hasName ? getString(R.string.home_greeting_morning, firstName) : getString(R.string.home_greeting_morning_no_name);
        } else if (hour >= 12 && hour < 17) {
            return hasName ? getString(R.string.home_greeting_afternoon, firstName) : getString(R.string.home_greeting_afternoon_no_name);
        }
        return hasName ? getString(R.string.home_greeting_evening, firstName) : getString(R.string.home_greeting_evening_no_name);
    }
}
