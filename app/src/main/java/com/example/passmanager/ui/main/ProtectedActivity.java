package com.example.passmanager.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.passmanager.R;
import com.example.passmanager.security.VaultSession;

/**
 * Base for every screen that shows vault contents (Vault, Add login, Edit login).
 *
 * - Opened without an unlock in this process (e.g. Android restored the app after killing it):
 *   goes to the lock screen instead of showing anything.
 * - Back after the auto-lock time: covers the screen and asks for fingerprint or PIN before
 *   anything shows again. Covering, not closing, keeps unsaved edits.
 *
 * Subclasses call super.onCreate and then stop if {@link #redirectedToLockScreen()} is true.
 */
public abstract class ProtectedActivity extends AppCompatActivity {

    private boolean redirected = false;
    private View lockCover;
    private boolean prompting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!VaultSession.isUnlocked()) {
            redirected = true;
            startActivity(new Intent(this, LockScreenActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }
    }

    /** True when this screen is closing because the vault isn't unlocked; do no further setup. */
    protected boolean redirectedToLockScreen() {
        return redirected;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!redirected && VaultSession.isLockPending()) {
            showLockCover();
        }
    }

    private void showLockCover() {
        if (lockCover == null) {
            ViewGroup content = findViewById(android.R.id.content);
            lockCover = getLayoutInflater().inflate(R.layout.view_lock_cover, content, false);
            content.addView(lockCover);
            lockCover.findViewById(R.id.btn_lock_cover_unlock).setOnClickListener(v -> promptUnlock());
        }
        lockCover.setVisibility(View.VISIBLE);
        promptUnlock();
    }

    private void promptUnlock() {
        if (prompting) return;
        prompting = true;
        // In a duress session the duress PIN keeps working, so the decoy vault behaves normally
        VaultUnlock.authenticate(this, VaultSession.isDuress(), new VaultUnlock.Callback() {
            @Override
            public void onUnlocked(boolean duress) {
                prompting = false;
                VaultSession.onReauthenticated();
                lockCover.setVisibility(View.GONE);
            }

            @Override
            public void onCancelled() {
                prompting = false; // the cover stays; "Unlock" tries again
            }
        });
    }
}
