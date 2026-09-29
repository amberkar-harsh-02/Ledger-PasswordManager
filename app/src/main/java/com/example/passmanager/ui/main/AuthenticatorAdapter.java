package com.example.passmanager.ui.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passmanager.R;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.security.ClipboardHelper;
import com.example.passmanager.security.TotpEngine;
import com.example.passmanager.security.TotpSecretCodec;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AuthenticatorAdapter extends RecyclerView.Adapter<AuthenticatorAdapter.AuthViewHolder> {

    // Ring turns amber for the last few seconds so the user knows the code is about to change
    private static final int WARNING_SECONDS = 5;

    private List<Credential> credentials = new ArrayList<>();
    private final Set<Integer> revealedItemIds = new HashSet<>();
    private final Context context;

    public AuthenticatorAdapter(Context context) {
        this.context = context;
    }

    public void setCredentials(List<Credential> credentials) {
        this.credentials = credentials;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AuthViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_authenticator, parent, false);
        return new AuthViewHolder(itemView);
    }

    // Full rebind (scrolling, list changes)
    @Override
    public void onBindViewHolder(@NonNull AuthViewHolder holder, int position) {
        Credential current = credentials.get(position);
        String title = current.getTitle() != null ? current.getTitle() : "";
        String username = current.getUsername() != null ? current.getUsername() : "";

        holder.monogram.setText(CredentialAdapter.monogramFor(title));
        holder.titleText.setText(title);
        holder.usernameText.setText(username);
        holder.usernameText.setVisibility(username.isEmpty() ? View.GONE : View.VISIBLE);
        holder.revealButton.setContentDescription(context.getString(R.string.codes_show_copy, title));

        updateTick(holder, current);

        // Show and copy: the whole row or the ring button
        View.OnClickListener reveal = v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            Credential cred = credentials.get(adapterPosition);
            revealedItemIds.add(cred.getId());

            String code = TotpEngine.generateTOTP(TotpSecretCodec.reveal(cred.getTotpSecret()));
            ClipboardHelper.copySensitive(context, "2FA Code", code);
            Messages.show(context, R.string.codes_copied);

            notifyItemChanged(adapterPosition, "TICK");
        };
        holder.row.setOnClickListener(reveal);
        holder.revealButton.setOnClickListener(reveal);
    }

    // Partial rebind every second from the fragment's clock
    @Override
    public void onBindViewHolder(@NonNull AuthViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains("TICK")) {
            updateTick(holder, credentials.get(position));
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    private void updateTick(AuthViewHolder holder, Credential cred) {
        int timeRemaining = TotpEngine.getRemainingSeconds();

        // The ring drains as the 30-second window runs out
        holder.progressTimer.setProgressCompat(timeRemaining, true);
        holder.progressTimer.setIndicatorColor(MaterialColors.getColor(holder.progressTimer,
                timeRemaining <= WARNING_SECONDS ? R.attr.colorAttention : androidx.appcompat.R.attr.colorPrimary));

        // SECURITY: Auto-hide the code the exact moment the 30-second window resets
        if (timeRemaining == 30) {
            revealedItemIds.remove(cred.getId());
        }

        String title = cred.getTitle() != null ? cred.getTitle() : "";
        String username = cred.getUsername() != null ? cred.getUsername() : "";

        if (revealedItemIds.contains(cred.getId())) {
            String code = TotpEngine.generateTOTP(TotpSecretCodec.reveal(cred.getTotpSecret()));
            // Grouped like "482 913" so it's easy to read and type
            String grouped = code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
            holder.codeText.setText(grouped);
            holder.codeText.setTextColor(MaterialColors.getColor(holder.codeText, com.google.android.material.R.attr.colorOnSurface));
            holder.actionIcon.setImageResource(R.drawable.ic_content_copy);
            holder.row.setContentDescription(context.getString(R.string.codes_row_revealed, title, username, grouped, timeRemaining));
        } else {
            holder.codeText.setText(R.string.codes_hidden);
            holder.codeText.setTextColor(MaterialColors.getColor(holder.codeText, com.google.android.material.R.attr.colorOnSurfaceVariant));
            holder.actionIcon.setImageResource(R.drawable.ic_visibility);
            holder.row.setContentDescription(context.getString(R.string.codes_row_hidden, title, username, timeRemaining));
        }
    }

    @Override
    public int getItemCount() {
        return credentials.size();
    }

    class AuthViewHolder extends RecyclerView.ViewHolder {
        private final View row;
        private final TextView monogram;
        private final TextView titleText;
        private final TextView usernameText;
        private final TextView codeText;
        private final CircularProgressIndicator progressTimer;
        private final View revealButton;
        private final ImageView actionIcon;

        public AuthViewHolder(View itemView) {
            super(itemView);
            row = itemView.findViewById(R.id.row_auth);
            monogram = itemView.findViewById(R.id.text_auth_monogram);
            titleText = itemView.findViewById(R.id.text_auth_title);
            usernameText = itemView.findViewById(R.id.text_auth_username);
            codeText = itemView.findViewById(R.id.text_auth_code);
            progressTimer = itemView.findViewById(R.id.progress_auth_timer);
            revealButton = itemView.findViewById(R.id.btn_reveal_code);
            actionIcon = itemView.findViewById(R.id.icon_auth_action);
        }
    }
}
