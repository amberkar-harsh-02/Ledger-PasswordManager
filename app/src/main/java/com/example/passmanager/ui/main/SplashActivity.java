package com.example.passmanager.ui.main;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Launcher entry point. Android 12+ already shows the system splash screen (app icon on the
 * surface color, see Theme.Ledger.Launch), so this activity draws nothing and routes straight
 * on: Welcome decides between first-run setup and the lock screen.
 * Lint flags the class name only; it has no launch screen of its own.
 */
@android.annotation.SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(SplashActivity.this, WelcomeActivity.class));
        finish();
    }
}
