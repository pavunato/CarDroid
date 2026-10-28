package com.cardroid.app.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    static class Memory implements UpdateChecker.Store {
        long last; String json; boolean succeeded;
        public boolean lastCheckSucceeded() { return succeeded; }
        public void saveCheckSucceeded(boolean value) { succeeded = value; }
        public long lastAttempt() { return last; }
        public String cachedRelease() { return json; }
        public void saveAttempt(long time) { last = time; }
        public void saveRelease(String body) { json = body; }
    }
    @Test public void failedCheckDoesNotPretendCachedDataIsFreshOnNextAutomaticCheck() {
        Memory store = new Memory();
        UpdateChecker success = new UpdateChecker(store, () -> new UpdateChecker.Response(200,
            "{\"tag_name\":\"v0.1.1\",\"draft\":false,\"prerelease\":false,\"html_url\":\"https://github.com/pavunato/CarDroid/releases/tag/v0.1.1\",\"assets\":[]}"));
        success.check("0.1.1", true, 1000);
        UpdateChecker offline = new UpdateChecker(store, () -> { throw new java.io.IOException("offline"); });
        assertEquals(UpdateChecker.Status.ERROR, offline.check("0.1.1", true, 2000).status);
        assertEquals(UpdateChecker.Status.ERROR, offline.check("0.1.1", false, 3000).status);
    }
    @Test public void httpErrorsMalformedReleaseAndOfflineAreErrorsAndThrottled() {
        for (int code : new int[]{403, 429, 404, 500, 200}) {
            Memory store = new Memory(); int[] calls = {0};
            UpdateChecker checker = new UpdateChecker(store, () -> { calls[0]++; return new UpdateChecker.Response(code, "{}"); });
            assertEquals(UpdateChecker.Status.ERROR, checker.check("0.1.1", false, 1000).status);
            assertEquals(UpdateChecker.Status.ERROR, checker.check("0.1.1", false, 2000).status);
            assertEquals(1, calls[0]);
            assertNull(store.json);
            checker.check("0.1.1", false, 1000 + UpdateChecker.CHECK_INTERVAL_MS);
            assertEquals(2, calls[0]);
        }
    }
    @Test public void automaticCheckIsThrottledButManualCheckBypassesCache() {
        Memory store = new Memory();
        int[] calls = {0};
        UpdateChecker checker = new UpdateChecker(store, () -> {
            calls[0]++;
            return new UpdateChecker.Response(200, "{\"tag_name\":\"v0.1.2\",\"draft\":false,\"prerelease\":false,\"html_url\":\"https://github.com/pavunato/CarDroid/releases/tag/v0.1.2\",\"assets\":[]}");
        });
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, checker.check("0.1.1", false, 1000).status);
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, checker.check("0.1.1", false, 2000).status);
        assertEquals(1, calls[0]);
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, checker.check("0.1.1", true, 2000).status);
        assertEquals(2, calls[0]);
        assertNotNull(store.json);
        assertEquals(2000, checker.lastAttempt());
    }
}
