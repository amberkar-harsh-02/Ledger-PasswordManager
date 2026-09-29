package com.example.passmanager.ui.main;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.passmanager.BuildConfig;
import com.example.passmanager.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.concurrent.Executors;

public class AboutFragment extends Fragment {

    private static final String EXTENSION_LINK = "https://github.com/amberkar-harsh-02/Ledger-extension/releases/latest";

    private int versionTapCount = 0;
    private TextView textCacheSize;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_about, container, false);

        TextView textVersion = view.findViewById(R.id.text_version_info);
        textCacheSize = view.findViewById(R.id.text_cache_size);

        // Real version from the build, not a hard-coded string
        textVersion.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));

        // --- Share the desktop extension link ---
        view.findViewById(R.id.btn_share).setOnClickListener(v -> {
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.about_extension_share_text, EXTENSION_LINK));
            sendIntent.setType("text/plain");
            com.example.passmanager.security.VaultSession.expectReturn(); // share sheet, back in a moment
            startActivity(Intent.createChooser(sendIntent, getString(R.string.about_extension_share_title)));
        });

        // --- The Easter Egg ---
        textVersion.setOnClickListener(v -> {
            versionTapCount++;
            if (versionTapCount == 7) {
                Messages.showLong(requireContext(), R.string.about_easter_egg_done);
                versionTapCount = 0; // Reset
            } else if (versionTapCount >= 3) {
                int left = 7 - versionTapCount;
                Messages.show(requireContext(), getResources().getQuantityString(R.plurals.about_easter_egg_taps_left, left, left));
            }
        });

        // --- How Ledger protects your data ---
        view.findViewById(R.id.row_field_manual).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.about_how_it_works)
                        .setMessage(R.string.about_how_it_works_body)
                        .setPositiveButton(R.string.action_done, null)
                        .show());

        // --- Clear cache (really deletes the cache directory's contents) ---
        view.findViewById(R.id.row_clear_cache).setOnClickListener(v -> confirmClearCache());
        refreshCacheSize();

        return view;
    }

    private void refreshCacheSize() {
        Context appContext = requireContext().getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            long bytes = sizeOf(appContext.getCacheDir());
            textCacheSize.post(() -> {
                if (!isAdded()) return;
                textCacheSize.setText(bytes == 0
                        ? getString(R.string.about_cache_empty)
                        : getString(R.string.about_cache_size, Formatter.formatShortFileSize(appContext, bytes)));
            });
        });
    }

    private void confirmClearCache() {
        Context appContext = requireContext().getApplicationContext();
        android.app.Activity host = requireActivity();
        long bytes = sizeOf(appContext.getCacheDir());
        if (bytes == 0) {
            Messages.show(requireActivity(), R.string.about_cache_empty);
            return;
        }
        String size = Formatter.formatShortFileSize(appContext, bytes);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_clear_cache_title)
                .setMessage(getString(R.string.about_clear_cache_body, size))
                .setPositiveButton(R.string.about_clear_cache_confirm, (dialog, which) ->
                        Executors.newSingleThreadExecutor().execute(() -> {
                            long before = sizeOf(appContext.getCacheDir());
                            deleteContents(appContext.getCacheDir());
                            long freed = Math.max(0, before - sizeOf(appContext.getCacheDir()));
                            textCacheSize.post(() -> {
                                Messages.show(host, appContext.getString(R.string.about_cache_cleared,
                                        Formatter.formatShortFileSize(appContext, freed)));
                                if (isAdded()) refreshCacheSize();
                            });
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    // Deletes everything inside the directory but keeps the directory itself
    private static void deleteContents(File dir) {
        File[] children = dir != null ? dir.listFiles() : null;
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) deleteContents(child);
            //noinspection ResultOfMethodCallIgnored
            child.delete();
        }
    }
}
