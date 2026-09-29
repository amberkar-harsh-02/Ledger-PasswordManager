package com.example.passmanager;

import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/**
 * Builds the rows Ledger shows in the system autofill dropdown (layout autofill_dropdown_item).
 * Same wording as the vault: site or app name on top, username underneath.
 */
public final class AutofillPresentation {

    private AutofillPresentation() {}

    /** A saved login offered for this screen. */
    public static RemoteViews login(Context context, @Nullable String title, @Nullable String username) {
        boolean hasUser = username != null && !username.trim().isEmpty();
        return row(context,
                hasUser ? username : context.getString(R.string.autofill_password_only),
                title,
                R.drawable.ic_autofill_key);
    }

    /** Opens the picker when nothing matched this screen. */
    public static RemoteViews searchLedger(Context context) {
        return row(context,
                context.getString(R.string.autofill_search_title),
                context.getString(R.string.autofill_search_subtitle),
                R.drawable.ic_autofill_search);
    }

    // Intent extras naming the app/site that asked to be filled, set by VaultAutofillService
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_TARGET_LABEL = "target_label";
    public static final String EXTRA_TARGET_WEB_DOMAIN = "target_web_domain";

    /**
     * Where a password is about to go, for the fingerprint/PIN prompt:
     * "Into github.com in Chrome" for a browser, "Into Totally Legit Game (com.evil.game)" for an app.
     * The package name is shown on purpose: an app can call itself "GitHub", but not take GitHub's package.
     */
    public static String describeTarget(Context context, @Nullable String packageName,
                                        @Nullable String appLabel, @Nullable String webDomain) {
        String label = appLabel != null && !appLabel.trim().isEmpty() ? appLabel : packageName;
        if (packageName == null) return context.getString(R.string.autofill_prompt_into_unknown);
        if (AutofillMatcher.isKnownBrowser(packageName) && webDomain != null && !webDomain.trim().isEmpty()) {
            return context.getString(R.string.autofill_prompt_into_site, webDomain, label);
        }
        return context.getString(R.string.autofill_prompt_into_app, label, packageName);
    }

    private static RemoteViews row(Context context, String primary, @Nullable String secondary, int iconRes) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.autofill_dropdown_item);
        views.setTextViewText(R.id.autofill_text, primary);
        views.setImageViewResource(R.id.autofill_icon, iconRes);
        if (secondary != null && !secondary.trim().isEmpty()) {
            views.setTextViewText(R.id.autofill_subtitle, secondary);
            views.setViewVisibility(R.id.autofill_subtitle, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.autofill_subtitle, View.GONE);
        }
        return views;
    }
}
