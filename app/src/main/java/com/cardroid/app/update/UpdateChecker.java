package com.cardroid.app.update;

/** Blocking checker; Android calls it on Dispatchers.IO, never the UI thread. */
public final class UpdateChecker {
    public static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    public enum Status { NOT_CHECKED, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, ERROR }
    public interface Store {
        long lastAttempt(); String cachedRelease();
        boolean lastCheckSucceeded(); void saveCheckSucceeded(boolean succeeded);
        void saveAttempt(long time); void saveRelease(String json);
    }
    public interface Transport { Response fetch() throws Exception; }
    public static final class Response {
        public final int code; public final String body;
        public Response(int code, String body) { this.code = code; this.body = body; }
    }
    public static final class Result {
        public final Status status; public final GitHubRelease release; public final String message;
        public final boolean cached;
        public Result(Status status, GitHubRelease release, String message, boolean cached) {
            this.status = status; this.release = release; this.message = message; this.cached = cached;
        }
    }
    private final Store store; private final Transport transport;
    public UpdateChecker(Store store, Transport transport) { this.store = store; this.transport = transport; }

    public long lastAttempt() { return store.lastAttempt(); }

    public Result cached(String installed) {
        try { return evaluate(installed, GitHubRelease.parse(store.cachedRelease()), true); }
        catch (Exception ignored) { return new Result(Status.NOT_CHECKED, null, "Not checked yet", false); }
    }
    public synchronized Result check(String installed, boolean manual, long now) {
        long last = store.lastAttempt();
        if (!manual && last > 0 && now >= last && now - last < CHECK_INTERVAL_MS) {
            Result result = cached(installed);
            return !store.lastCheckSucceeded() || result.status == Status.NOT_CHECKED
                    ? new Result(Status.ERROR, result.release, "Previous check failed. Tap Check now to retry.", true) : result;
        }
        store.saveAttempt(now);
        store.saveCheckSucceeded(false);
        try {
            Response response = transport.fetch();
            if (response.code != 200) {
                String message = response.code == 403 || response.code == 429
                        ? "GitHub rate limit or access denied. Please try again later."
                        : response.code == 404 ? "No published stable release was found."
                        : "GitHub returned HTTP " + response.code + ". Try again later.";
                return new Result(Status.ERROR, cached(installed).release, message, false);
            }
            GitHubRelease release = GitHubRelease.parse(response.body);
            Result result = evaluate(installed, release, false);
            store.saveRelease(response.body);
            store.saveCheckSucceeded(true);
            return result;
        } catch (java.io.IOException e) {
            return new Result(Status.ERROR, cached(installed).release, "Cannot reach GitHub. Check your connection and try again.", false);
        } catch (Exception e) {
            return new Result(Status.ERROR, cached(installed).release, "Cannot compare versions or read the release. Expected vMAJOR.MINOR.PATCH tags.", false);
        }
    }
    private Result evaluate(String installed, GitHubRelease release, boolean cached) {
        boolean newer = release.isNewerThan(installed);
        return new Result(newer ? Status.UPDATE_AVAILABLE : Status.UP_TO_DATE, release,
                newer ? "Update available" : "Up to date (no newer stable release)", cached);
    }
}
