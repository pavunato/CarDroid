package com.cardroid.app.update;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-scoped checker shared by setup UI and the update screen. */
public final class UpdateChecks {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static UpdateChecker checker;
    private UpdateChecks() {}

    static synchronized UpdateChecker checker(Context context) {
        if (checker == null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("github_updates", Context.MODE_PRIVATE);
            checker = new UpdateChecker(new UpdateChecker.Store() {
                public long lastAttempt() { return prefs.getLong("last_attempt", 0); }
                public String cachedRelease() { return prefs.getString("release", null); }
                public boolean lastCheckSucceeded() { return prefs.getBoolean("succeeded", false); }
                public void saveAttempt(long time) { prefs.edit().putLong("last_attempt", time).apply(); }
                public void saveRelease(String json) { prefs.edit().putString("release", json).apply(); }
                public void saveCheckSucceeded(boolean value) { prefs.edit().putBoolean("succeeded", value).apply(); }
            }, new GitHubTransport());
        }
        return checker;
    }

    public static String installedVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }

    /** Call once from MainActivity.onCreate; checks at most daily, including failed attempts. No dialogs or installs. */
    public static void checkAutomatically(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> checker(app).check(installedVersion(app), false, System.currentTimeMillis()));
    }

    static void check(Context context, boolean manual, java.util.function.Consumer<UpdateChecker.Result> done) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> done.accept(checker(app).check(installedVersion(app), manual, System.currentTimeMillis())));
    }
}
