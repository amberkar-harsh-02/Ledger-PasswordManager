package com.example.passmanager.ui.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.passmanager.PasswordStrength;
import com.example.passmanager.R;
import com.example.passmanager.data.model.Credential;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CredentialAdapter extends RecyclerView.Adapter<CredentialAdapter.CredentialHolder> {

    private List<Credential> credentials = new ArrayList<>();
    private OnItemClickListener listener;
    private boolean showStrength = true;

    /** The autofill picker hides the strength label: there it only matters which login to fill. */
    public void setShowStrength(boolean showStrength) {
        this.showStrength = showStrength;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CredentialHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_credential, parent, false);
        return new CredentialHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull CredentialHolder holder, int position) {
        Credential credential = credentials.get(position);
        Context context = holder.itemView.getContext();

        String title = credential.getTitle() != null ? credential.getTitle() : "";
        String username = credential.getUsername() != null ? credential.getUsername() : "";

        holder.textViewTitle.setText(title);
        holder.textViewUsername.setText(username);
        holder.textViewUsername.setVisibility(username.isEmpty() ? View.GONE : View.VISIBLE);
        holder.textMonogram.setText(monogramFor(title));

        int score = credential.getHealthScore();
        String strength = context.getString(strengthLabel(score));
        bindStrengthLabel(holder.textHealthBadge, score, strength);
        holder.textHealthBadge.setVisibility(showStrength ? View.VISIBLE : View.GONE);

        // One spoken summary for the whole row
        holder.itemView.setContentDescription(
                context.getString(R.string.vault_row_description, title, username, strength));

        // Edit and delete are swipe gestures in the Vault; offer them as TalkBack actions too
        for (int actionId : holder.accessibilityActionIds) {
            androidx.core.view.ViewCompat.removeAccessibilityAction(holder.itemView, actionId);
        }
        holder.accessibilityActionIds.clear();
        if (editListener != null) {
            holder.accessibilityActionIds.add(androidx.core.view.ViewCompat.addAccessibilityAction(holder.itemView,
                    context.getString(R.string.sheet_edit_login), (v, args) -> {
                        editListener.onItemClick(credential);
                        return true;
                    }));
        }
        if (deleteListener != null) {
            holder.accessibilityActionIds.add(androidx.core.view.ViewCompat.addAccessibilityAction(holder.itemView,
                    context.getString(R.string.delete_confirm), (v, args) -> {
                        deleteListener.onItemClick(credential);
                        return true;
                    }));
        }
    }

    private OnItemClickListener editListener;
    private OnItemClickListener deleteListener;

    /** TalkBack "Edit" / "Delete" actions for rows whose visual controls are swipe gestures. */
    public void setAccessibilityActions(OnItemClickListener edit, OnItemClickListener delete) {
        this.editListener = edit;
        this.deleteListener = delete;
        notifyDataSetChanged();
    }

    static String monogramFor(String title) {
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return "?";
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
    }

    static int strengthLabel(int score) {
        switch (score) {
            case PasswordStrength.WEAK: return R.string.strength_weak;
            case PasswordStrength.FAIR: return R.string.strength_fair;
            case PasswordStrength.STRONG: return R.string.strength_strong;
            default: return R.string.strength_very_strong;
        }
    }

    // Weak and fair "need attention": amber words on an amber container with a warning icon.
    // Strong passwords stay neutral so a healthy vault looks calm.
    static void bindStrengthLabel(TextView label, int score, String text) {
        Context context = label.getContext();
        boolean attention = PasswordStrength.needsAttention(score);

        int color = attention
                ? MaterialColors.getColor(label, R.attr.colorOnAttentionContainer)
                : MaterialColors.getColor(label, com.google.android.material.R.attr.colorOnSurfaceVariant);
        Drawable icon = AppCompatResources.getDrawable(context, attention ? R.drawable.ic_warning : R.drawable.ic_check_circle);
        if (icon != null) {
            int size = context.getResources().getDimensionPixelSize(R.dimen.strength_icon_size);
            icon = icon.mutate();
            icon.setBounds(0, 0, size, size);
        }

        label.setText(text);
        label.setTextColor(color);
        label.setCompoundDrawablesRelative(icon, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(label, ColorStateList.valueOf(color));
        label.setBackgroundResource(attention ? R.drawable.bg_strength_attention : 0);
    }

    @Override
    public int getItemCount() {
        return credentials.size();
    }

    public Credential getCredentialAt(int position) {
        return credentials.get(position);
    }

    public void setCredentials(List<Credential> credentials) {
        this.credentials = credentials;
        notifyDataSetChanged();
    }

    public void filterList(List<Credential> filteredList) {
        this.credentials = filteredList;
        notifyDataSetChanged();
    }

    public interface OnItemClickListener {
        void onItemClick(Credential credential);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    class CredentialHolder extends RecyclerView.ViewHolder {
        private final java.util.List<Integer> accessibilityActionIds = new ArrayList<>();
        private final TextView textMonogram;
        private final TextView textViewTitle;
        private final TextView textViewUsername;
        private final TextView textHealthBadge;

        public CredentialHolder(View itemView) {
            super(itemView);
            textMonogram = itemView.findViewById(R.id.text_monogram);
            textViewTitle = itemView.findViewById(R.id.text_title);
            textViewUsername = itemView.findViewById(R.id.text_username);
            textHealthBadge = itemView.findViewById(R.id.text_health_badge);

            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onItemClick(credentials.get(position));
                }
            });
        }
    }
}
