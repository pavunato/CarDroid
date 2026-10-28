package com.cardroid.app.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;

/** Phone-only update settings. Download/install always remains an explicit user/browser action. */
public final class UpdateActivity extends Activity {
    private TextView latest, status, checked;
    private Button check, download, releasePage;
    private GitHubRelease release;
    private String installed;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installed = UpdateChecks.installedVersion(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);
        setContentView(scroll);
        TextView title = text(column, "CarDroid updates");
        title.setTextSize(26);
        text(column, "Current version: " + installed);
        latest = text(column, "Latest stable version: not checked");
        status = text(column, "Not checked yet");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        checked = text(column, "");
        check = button(column, "Check now", () -> runCheck(true));
        download = button(column, "Download APK in browser", () -> open(release.downloadUrl));
        releasePage = button(column, "View release on GitHub", () -> open(release.pageUrl));
        text(column, "Automatic checks run at most once every 24 hours while CarDroid is opened. Check now retries immediately. No APK is downloaded or installed automatically.");
        text(column, "Only published stable GitHub releases are checked. Versions use MAJOR.MINOR.PATCH (tags may start with v); prerelease precedence follows SemVer. Build metadata does not affect precedence. A newer local build is never offered a downgrade.");
        button(column, "Back", this::finish);
        render(UpdateChecks.checker(this).cached(installed));
        runCheck(false);
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(16);
        view.setPadding(0, 10, 0, 10); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setOnClickListener(v -> action.run());
        parent.addView(view); return view;
    }
    private void runCheck(boolean manual) {
        check.setEnabled(false);
        download.setVisibility(View.GONE); releasePage.setVisibility(View.GONE);
        status.setText("Checking GitHub…");
        UpdateChecks.check(this, manual, result -> runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) render(result);
        }));
    }
    private void render(UpdateChecker.Result result) {
        release = result.release;
        latest.setText("Latest stable version: " + (release == null ? "unknown" : release.tag));
        status.setText(result.message + (result.cached ? " (cached)" : "")
                + (result.status == UpdateChecker.Status.ERROR && release != null ? "\nShowing previously cached release information." : ""));
        long last = UpdateChecks.checker(this).lastAttempt();
        checked.setText(last == 0 ? "Never checked" : "Last attempt: " + DateFormat.getDateTimeInstance().format(new Date(last)));
        check.setEnabled(true);
        boolean available = result.status == UpdateChecker.Status.UPDATE_AVAILABLE && release != null;
        download.setVisibility(available && release.downloadUrl != null ? View.VISIBLE : View.GONE);
        releasePage.setVisibility(release != null ? View.VISIBLE : View.GONE);
        if (available && release.downloadUrl == null) {
            status.append("\nNo single APK download is available. Open the release page to review available assets.");
        }
    }
    private void open(String url) {
        if (url == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "No browser is available to open GitHub.", Toast.LENGTH_LONG).show(); }
        catch (SecurityException e) { Toast.makeText(this, "This device does not allow opening the browser.", Toast.LENGTH_LONG).show(); }
    }
}
