package com.example.classpulse.ui.online;

import android.graphics.Typeface;
import android.view.View;
import androidx.core.content.ContextCompat;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.databinding.FragmentShellBinding;
import com.google.android.material.button.MaterialButton;

/** The three account-owned destinations; never routes to the local demo repository. */
public final class OnlineNavigation {
    private OnlineNavigation() {}

    public static void bind(FragmentShellBinding shell, MainActivity host, String selected) {
        shell.bottomNav.setVisibility(View.VISIBLE);
        tab(shell.navHome, host, R.string.nav_classes, "online_home", selected);
        tab(shell.navRecords, host, R.string.nav_records, "online_records", selected);
        tab(shell.navSettings, host, R.string.nav_profile, "account", selected);
    }

    private static void tab(
            MaterialButton button, MainActivity host, int label, String route, String selected) {
        boolean active = route.equals(selected);
        button.setText(label);
        button.setSelected(active);
        button.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(
                ContextCompat.getColor(host, active ? R.color.cp_brand : R.color.cp_muted));
        button.setOnClickListener(v -> host.openOnlineTab(route));
    }
}
